/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.service

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Helpers that let the local OpenAI-compatible chat endpoint participate in
 * agentic clients like Continue, Cursor, Aider, etc.
 *
 * The locally-hosted model exposed by [RemoteApiServerService] does not have
 * native OpenAI tool-call support, so we emulate it at the HTTP boundary:
 *
 *   1. Inbound: when the client sends a `tools` array, we describe each tool to
 *      the model via a system-prompt suffix and instruct it to emit calls in a
 *      tagged JSON form `<tool_call>{"name":..., "arguments":{...}}</tool_call>`.
 *   2. History: prior assistant `tool_calls` and `role:tool` results are
 *      flattened into plain-text turns the model can read.
 *   3. Outbound: we scan the model's text for `<tool_call>...</tool_call>`
 *      blocks (or a JSON object containing both "name" and "arguments") and
 *      surface them in the response as a standard `tool_calls` array with
 *      `finish_reason="tool_calls"`.
 *
 * This is intentionally a thin bridge — it does not execute tools, it just
 * shuttles them between the OpenAI wire format and the model's text channel.
 */
internal object OpenAiToolBridge {

  data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
  )

  data class ParsedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
  )

  /** Result of scanning a completed model response. */
  data class ParseResult(
    val toolCalls: List<ParsedToolCall>,
    val remainingText: String,
  )

  // ── Inbound: build the system-prompt suffix describing available tools ─────

  fun parseToolSpecs(tools: JsonArray?): List<ToolSpec> {
    if (tools == null || tools.size() == 0) return emptyList()
    val out = mutableListOf<ToolSpec>()
    for (el in tools) {
      val obj = el as? JsonObject ?: continue
      val type = obj.get("type")?.asString
      // Only OpenAI "function" tools are supported.
      if (type != null && type != "function") continue
      val fn = obj.getAsJsonObject("function") ?: continue
      val name = fn.get("name")?.asString ?: continue
      val description = fn.get("description")?.asString ?: ""
      val params = fn.get("parameters")
      val paramsJson = if (params == null || params.isJsonNull) "{}" else params.toString()
      out.add(ToolSpec(name, description, paramsJson))
    }
    return out
  }

  fun buildToolSystemSuffix(specs: List<ToolSpec>): String {
    if (specs.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("## Tool calling format (MANDATORY)\n\n")
    sb.append("You have access to tools. Do NOT use your built-in / native function-call ")
    sb.append("syntax. Do NOT emit any special tokens such as `<|tool_call|>`, `<|call|>`, ")
    sb.append("`<tool_call|>`, or `call:name{...}`. The ONLY accepted format is plain text ")
    sb.append("that looks exactly like this, on its own line, with valid JSON arguments:\n\n")
    sb.append("<tool_call>{\"name\": \"<function_name>\", \"arguments\": <json_object>}</tool_call>\n\n")
    sb.append("Examples (you must follow this shape exactly):\n")
    sb.append("<tool_call>{\"name\": \"read_file\", \"arguments\": {\"filepath\": \"tetris.html\"}}</tool_call>\n")
    sb.append("<tool_call>{\"name\": \"file_glob_search\", \"arguments\": {\"pattern\": \"**/*.js\"}}</tool_call>\n\n")
    sb.append("Rules (read carefully):\n")
    sb.append("- When you decide to call a tool, output ONLY the <tool_call>...</tool_call> ")
    sb.append("block(s) and nothing else — no prose before or after, no markdown fences.\n")
    sb.append("- The tool parameters MUST be nested under the key \"arguments\". Do NOT put ")
    sb.append("parameter keys (like \"filepath\", \"changes\", \"pattern\") as siblings of ")
    sb.append("\"name\" — they must live inside the \"arguments\" object.\n")
    sb.append("- Always include the closing </tool_call> tag immediately after the closing }.\n")
    sb.append("- Arguments must be a single valid JSON object using double-quoted keys and ")
    sb.append("strings. No trailing commas. No comments.\n")
    sb.append("- Inside string values, newlines MUST be written as \\n, tabs as \\t, and any ")
    sb.append("literal double-quote as \\\". Never emit a raw newline inside a JSON string.\n")
    sb.append("- One block per tool call. You may chain multiple blocks on separate lines.\n")
    sb.append("- If a tool is not needed, reply with normal text instead.\n\n")
    sb.append("## Available tools (use these exact names):\n")
    for (s in specs) {
      sb.append("- `").append(s.name).append("`")
      if (s.description.isNotBlank()) sb.append(" — ").append(s.description.trim())
      sb.append("\n  parameters schema: ").append(s.parametersJson).append("\n")
    }
    return sb.toString().trimEnd()
  }

  // ── Inbound: flatten history entries the OpenAI schema requires us to honour ─

  /**
   * Formats a prior assistant turn whose `tool_calls` we already routed. We
   * render them back to the model as text so it has a faithful transcript.
   */
  fun formatAssistantToolCalls(toolCalls: JsonArray?): String {
    if (toolCalls == null || toolCalls.size() == 0) return ""
    val sb = StringBuilder()
    for (el in toolCalls) {
      val obj = el as? JsonObject ?: continue
      val fn = obj.getAsJsonObject("function") ?: continue
      val name = fn.get("name")?.asString ?: continue
      val args = fn.get("arguments")?.let { a ->
        if (a.isJsonPrimitive) a.asString else a.toString()
      } ?: "{}"
      if (sb.isNotEmpty()) sb.append("\n")
      sb.append("<tool_call>{\"name\": \"")
        .append(name)
        .append("\", \"arguments\": ")
        .append(args.ifBlank { "{}" })
        .append("}</tool_call>")
    }
    return sb.toString()
  }

  /** Formats a `role: "tool"` message back to the model. */
  fun formatToolResult(toolName: String?, content: String): String {
    val nameLabel = toolName?.takeIf { it.isNotBlank() } ?: "tool"
    return "<tool_result name=\"$nameLabel\">\n${content.trim()}\n</tool_result>"
  }

  // ── Outbound: pull tool calls out of model text ────────────────────────────

  // Matches our preferred protocol: <tool_call>{json}</tool_call>. The opening
  // and closing tags may have leaked special-token pipes around them, e.g.
  // `<|tool_call|>`, `<|/tool_call|>`, or unbalanced variants like `<tool_call|>`.
  private val TAG_REGEX = Regex(
    pattern = "<\\|?/?tool_call\\|?>\\s*(\\{[\\s\\S]*?\\})\\s*<\\|?/?tool_call\\|?>",
    options = setOf(RegexOption.IGNORE_CASE),
  )

  // Matches Gemma's native syntax when it leaks as text:
  //   <|tool_call>call:read_file{filepath:"tetris.html"}<tool_call|>
  //   call:read_file{filepath: "tetris.html"}
  private val NATIVE_CALL_REGEX = Regex(
    pattern = "call:\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(\\{[\\s\\S]*?\\})",
    options = setOf(RegexOption.IGNORE_CASE),
  )

  // Strips Gemma's leaked special tokens (e.g. `<|tool_call|>`, `<|"|>`).
  private val SPECIAL_TOKEN_REGEX = Regex("<\\|[^|>]{0,40}\\|>")

  /**
   * Scans [text] for tool-call markers. We try, in order:
   *   1. Our preferred <tool_call>{json}</tool_call> protocol (tolerant of
   *      leaked pipe characters around the tags).
   *   2. Gemma's leaked native function-call syntax `call:name{...}`.
   *   3. A bare JSON object containing `name` + `arguments`.
   */
  fun extractToolCalls(text: String): ParseResult {
    // 1. Preferred tagged protocol.
    val matches = TAG_REGEX.findAll(text).toList()
    if (matches.isNotEmpty()) {
      val calls = mutableListOf<ParsedToolCall>()
      val remaining = StringBuilder()
      var cursor = 0
      for ((idx, m) in matches.withIndex()) {
        remaining.append(text, cursor, m.range.first)
        cursor = m.range.last + 1
        val parsed = parseCallJson(m.groupValues[1], idx) ?: continue
        calls.add(parsed)
      }
      remaining.append(text, cursor, text.length)
      if (calls.isNotEmpty()) {
        return ParseResult(calls, stripSpecialTokens(remaining.toString()).trim())
      }
    }

    // 2. Gemma's native syntax leaked as text.
    val cleaned = stripSpecialTokens(text)
    val nativeMatches = NATIVE_CALL_REGEX.findAll(cleaned).toList()
    if (nativeMatches.isNotEmpty()) {
      val calls = mutableListOf<ParsedToolCall>()
      val remaining = StringBuilder()
      var cursor = 0
      for ((idx, m) in nativeMatches.withIndex()) {
        remaining.append(cleaned, cursor, m.range.first)
        cursor = m.range.last + 1
        val name = m.groupValues[1]
        val argsJson = relaxedJsonToJson(m.groupValues[2])
        calls.add(
          ParsedToolCall(
            id = "call_${System.currentTimeMillis()}_$idx",
            name = name,
            argumentsJson = argsJson,
          )
        )
      }
      remaining.append(cleaned, cursor, cleaned.length)
      if (calls.isNotEmpty()) return ParseResult(calls, remaining.toString().trim())
    }

    // 3. Heuristic fallback: a single JSON object response with name+arguments.
    val trimmed = cleaned.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      val parsed = parseCallJson(trimmed, 0)
      if (parsed != null) return ParseResult(listOf(parsed), "")
    }
    return ParseResult(emptyList(), cleaned)
  }

  private fun stripSpecialTokens(text: String): String =
    SPECIAL_TOKEN_REGEX.replace(text, "")

  /**
   * Best-effort conversion of a relaxed `{key: value, ...}` object (unquoted
   * keys, single-quoted strings) into strict JSON that Gson can parse. Falls
   * back to `{}` if it cannot be coerced.
   */
  private fun relaxedJsonToJson(raw: String): String {
    try {
      com.google.gson.JsonParser.parseString(raw)
      return raw
    } catch (_: Exception) { /* fall through */ }
    var s = raw.replace(
      Regex("([,{\\s])([A-Za-z_][A-Za-z0-9_]*)\\s*:"),
      "$1\"$2\":"
    )
    s = s.replace(Regex("'([^'\\n]*?)'"), "\"$1\"")
    return try {
      com.google.gson.JsonParser.parseString(s); s
    } catch (_: Exception) {
      "{}"
    }
  }

  private fun parseCallJson(raw: String, index: Int): ParsedToolCall? {
    val repaired = repairRawNewlinesInStrings(raw)
    val obj = try {
      com.google.gson.JsonParser.parseString(repaired) as? JsonObject
    } catch (_: Exception) {
      // Last-ditch: try the original.
      try { com.google.gson.JsonParser.parseString(raw) as? JsonObject }
      catch (_: Exception) { null }
    } ?: return null
    val name = obj.get("name")?.asString ?: return null
    // Prefer the canonical "arguments" / "parameters" wrappers; if neither is
    // present, treat every other top-level key ("filepath", "pattern", etc.)
    // as the arguments. This rescues models that emit a flat layout.
    val argsEl: JsonElement? = obj.get("arguments") ?: obj.get("parameters")
    val argsJson = when {
      argsEl != null && !argsEl.isJsonNull -> {
        if (argsEl.isJsonPrimitive && argsEl.asJsonPrimitive.isString) argsEl.asString
        else argsEl.toString()
      }
      else -> {
        val flat = JsonObject()
        for ((k, v) in obj.entrySet()) {
          if (k == "name" || k == "type" || k == "id") continue
          flat.add(k, v)
        }
        if (flat.size() == 0) "{}" else flat.toString()
      }
    }
    return ParsedToolCall(
      id = "call_${System.currentTimeMillis()}_$index",
      name = name,
      argumentsJson = argsJson.ifBlank { "{}" },
    )
  }

  /**
   * Escapes raw control characters (newline, carriage return, tab) that appear
   * inside JSON string literals, since strict JSON requires them to be
   * `\\n`/`\\r`/`\\t`. We walk the string respecting backslash escapes and
   * string boundaries so we don't touch characters outside of strings.
   */
  private fun repairRawNewlinesInStrings(raw: String): String {
    val out = StringBuilder(raw.length + 16)
    var inString = false
    var escape = false
    var i = 0
    while (i < raw.length) {
      val c = raw[i]
      if (inString) {
        when {
          escape -> { out.append(c); escape = false }
          c == '\\' -> { out.append(c); escape = true }
          c == '"' -> { out.append(c); inString = false }
          c == '\n' -> out.append("\\n")
          c == '\r' -> out.append("\\r")
          c == '\t' -> out.append("\\t")
          else -> out.append(c)
        }
      } else {
        if (c == '"') inString = true
        out.append(c)
      }
      i++
    }
    return out.toString()
  }

  /** Builds the OpenAI `tool_calls` JSON array for a non-streaming response. */
  fun toToolCallsArray(calls: List<ParsedToolCall>): JsonArray {
    val arr = JsonArray()
    for (c in calls) {
      val tc = JsonObject().apply {
        addProperty("id", c.id)
        addProperty("type", "function")
        add("function", JsonObject().apply {
          addProperty("name", c.name)
          // OpenAI sends arguments as a stringified JSON object.
          addProperty("arguments", c.argumentsJson)
        })
      }
      arr.add(tc)
    }
    return arr
  }
}
