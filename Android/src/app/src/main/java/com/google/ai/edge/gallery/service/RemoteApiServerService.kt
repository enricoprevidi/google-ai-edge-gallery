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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking

private const val TAG = "AGRemoteApiServer"
private const val CHANNEL_ID = "remote_api_server"
private const val NOTIFICATION_ID = 7831

class RemoteApiServerService : Service() {
  companion object {
    const val ACTION_START = "com.google.ai.edge.gallery.action.START_REMOTE_API"
    const val ACTION_STOP = "com.google.ai.edge.gallery.action.STOP_REMOTE_API"

    fun start(context: Context) {
      val intent = Intent(context, RemoteApiServerService::class.java).apply { action = ACTION_START }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.startService(
        Intent(context, RemoteApiServerService::class.java).apply { action = ACTION_STOP }
      )
    }
  }

  private var serverSocket: ServerSocket? = null
  private val acceptExecutor = Executors.newSingleThreadExecutor()
  private val workerExecutor = Executors.newFixedThreadPool(2)
  private val inferenceMutex = Mutex()
  private val activeModelRef = AtomicReference<Model?>(null)
  // Effective engine context size for the active session, used to bound prompts.
  @Volatile private var activeContextSize: Int = RemoteApiPrefs.DEFAULT_CONTEXT_SIZE
  // Saved per-model MAX_TOKENS configValue we override at startup and restore on stop.
  private var savedModelMaxTokens: Pair<Model, Any?>? = null
  @Volatile private var running = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopServer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      else -> {
        startServer()
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    stopServer()
    acceptExecutor.shutdownNow()
    workerExecutor.shutdownNow()
    super.onDestroy()
  }

  private fun startServer() {
    if (running) return
    val cfg = RemoteApiPrefs.read(this)
    RemoteApiServerLog.info("server", "Start requested (model=${cfg.modelName}, port=${cfg.port}, contextSize=${cfg.contextSize})")
    if (cfg.modelName.isBlank()) {
      RemoteApiServerHolder.update(RemoteApiServerStatus.Error("No model selected."))
      RemoteApiServerLog.error("server", "No model selected")
      stopSelf()
      return
    }

    val vm = RemoteApiServerHolder.viewModel()
    if (vm == null) {
      RemoteApiServerHolder.update(
        RemoteApiServerStatus.Error("App not running; open the app first.")
      )
      stopSelf()
      return
    }
    val model = vm.getModelByName(cfg.modelName)
    if (model == null) {
      RemoteApiServerHolder.update(RemoteApiServerStatus.Error("Model '${cfg.modelName}' not found."))
      stopSelf()
      return
    }
    val status = vm.uiState.value.modelDownloadStatus[model.name]
    if (status?.status != ModelDownloadStatusType.SUCCEEDED) {
      RemoteApiServerHolder.update(
        RemoteApiServerStatus.Error("Model '${model.name}' is not downloaded.")
      )
      stopSelf()
      return
    }

    RemoteApiServerHolder.update(RemoteApiServerStatus.Starting(model.name))
    startForeground(
      NOTIFICATION_ID,
      buildNotification("Loading model ${model.name}…"),
      foregroundType()
    )

    // Initialize the model on a worker thread.
    workerExecutor.execute {
      try {
        // If a previous instance exists (e.g. from chat UI), tear it down so the
        // engine isn't double-loaded.
        if (model.instance != null) {
          LlmChatModelHelper.cleanUp(model) {}
        }
        // Override the model's MAX_TOKENS config for this session so the engine
        // is built with enough KV cache to swallow agent-style prompts from
        // Continue/Cursor (Continue's tool-augmented system prompt alone can
        // be 5-6K tokens). The chat-UI value is restored when the server stops.
        val maxTokensKey = com.google.ai.edge.gallery.data.ConfigKeys.MAX_TOKENS.label
        val prior = model.configValues[maxTokensKey]
        savedModelMaxTokens = model to prior
        activeContextSize = cfg.contextSize.coerceAtLeast(2048)
        val mutable = model.configValues.toMutableMap()
        mutable[maxTokensKey] = activeContextSize
        model.configValues = mutable
        Log.i(TAG, "Engine context size set to $activeContextSize (was $prior)")
        RemoteApiServerLog.info("engine", "Initializing ${model.name} with context size $activeContextSize (was $prior)")
        var initError: String? = null
        val initLatch = java.util.concurrent.CountDownLatch(1)
        LlmChatModelHelper.initialize(
          context = this,
          model = model,
          supportImage = false,
          supportAudio = false,
          onDone = { err ->
            if (err.isNotEmpty()) initError = err
            initLatch.countDown()
          },
          systemInstruction = null,
          tools = emptyList(),
          enableConversationConstrainedDecoding = false,
          coroutineScope = null,
        )
        initLatch.await()
        if (initError != null) {
          RemoteApiServerHolder.update(RemoteApiServerStatus.Error(initError!!))
          RemoteApiServerLog.error("engine", "Initialize failed: $initError")
          stopSelfQuietly()
          return@execute
        }
        activeModelRef.set(model)
        RemoteApiServerLog.info("engine", "Model loaded")

        // Bind the socket.
        val ss = ServerSocket(cfg.port, 50, java.net.InetAddress.getByName("0.0.0.0"))
        serverSocket = ss
        running = true
        val ip = lanIpv4(this) ?: "0.0.0.0"
        RemoteApiServerHolder.update(RemoteApiServerStatus.Running(model.name, ip, cfg.port))
        updateNotification("Serving ${model.name} at http://$ip:${cfg.port}")
        Log.i(TAG, "Server bound on $ip:${cfg.port}")
        RemoteApiServerLog.info("server", "Listening on http://$ip:${cfg.port}")

        acceptLoop(ss, cfg)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start server", e)
        RemoteApiServerLog.error("server", "Start failed: ${e.message ?: e.javaClass.simpleName}")
        RemoteApiServerHolder.update(
          RemoteApiServerStatus.Error("Failed to start: ${e.message ?: e.javaClass.simpleName}")
        )
        stopSelfQuietly()
      }
    }
  }

  private fun acceptLoop(ss: ServerSocket, cfg: RemoteApiPrefs.Config) {
    acceptExecutor.execute {
      while (running && !ss.isClosed) {
        try {
          val client = ss.accept()
          workerExecutor.execute { handleClient(client, cfg) }
        } catch (e: IOException) {
          if (running) Log.w(TAG, "accept() failed", e)
        }
      }
    }
  }

  private fun stopServer() {
    if (!running && serverSocket == null && activeModelRef.get() == null) return
    running = false
    try { serverSocket?.close() } catch (_: Exception) {}
    serverSocket = null
    val model = activeModelRef.getAndSet(null)
    if (model != null && model.instance != null) {
      try { LlmChatModelHelper.cleanUp(model) {} } catch (e: Exception) {
        Log.w(TAG, "cleanUp failed", e)
      }
    }
    // Restore the user's per-model MAX_TOKENS chat setting.
    val saved = savedModelMaxTokens
    if (saved != null) {
      val (m, prior) = saved
      val maxTokensKey = com.google.ai.edge.gallery.data.ConfigKeys.MAX_TOKENS.label
      val mutable = m.configValues.toMutableMap()
      if (prior == null) mutable.remove(maxTokensKey) else mutable[maxTokensKey] = prior
      m.configValues = mutable
      savedModelMaxTokens = null
    }
    RemoteApiServerHolder.update(RemoteApiServerStatus.Stopped)
    RemoteApiServerLog.info("server", "Stopped")
  }

  private fun stopSelfQuietly() {
    stopServer()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  // ── HTTP request handling ──────────────────────────────────────────────

  private fun handleClient(socket: Socket, cfg: RemoteApiPrefs.Config) {
    socket.use { s ->
      try {
        s.soTimeout = 60_000
        val input = s.getInputStream()
        val out = s.getOutputStream()
        val req = parseRequest(input) ?: return@use
        Log.d(TAG, "${req.method} ${req.path}")
        val clientHint = (s.remoteSocketAddress?.toString() ?: "") +
          (req.headers["user-agent"]?.let { " — $it" } ?: "")
        if (req.path != "/healthz") {
          RemoteApiServerLog.debug("http", "${req.method} ${req.path} from ${clientHint.ifBlank { "unknown" }}")
        }

        if (cfg.requireToken && req.path != "/healthz") {
          val auth = req.headers["authorization"] ?: ""
          if (!auth.equals("Bearer ${cfg.apiToken}", ignoreCase = false)) {
            sendJson(out, 401, """{"error":{"message":"Unauthorized","type":"invalid_request_error"}}""")
            return@use
          }
        }

        when {
          req.method == "GET" && req.path == "/healthz" -> sendJson(out, 200, """{"status":"ok"}""")
          req.method == "GET" && req.path == "/v1/models" -> handleModelsList(out)
          req.method == "POST" && req.path == "/v1/chat/completions" -> handleChat(out, req, cfg, clientHint)
          req.method == "OPTIONS" -> sendOptions(out)
          else -> sendJson(out, 404, """{"error":{"message":"Not found"}}""")
        }
      } catch (e: Exception) {
        Log.w(TAG, "Client handler error", e)
      }
    }
  }

  private fun handleModelsList(out: OutputStream) {
    val model = activeModelRef.get()
    val data = if (model == null) "[]"
    else """[{"id":"${escapeJson(model.name)}","object":"model","owned_by":"local"}]"""
    sendJson(out, 200, """{"object":"list","data":$data}""")
  }

  private fun handleChat(out: OutputStream, req: HttpRequest, cfg: RemoteApiPrefs.Config, clientHint: String) {
    val requestId = RemoteApiServerLog.beginRequest(
      clientHint = clientHint,
      path = req.path,
      contextSizeTokens = activeContextSize,
    )
    val model = activeModelRef.get()
    if (model == null || model.instance == null) {
      sendJson(out, 503, """{"error":{"message":"No model loaded"}}""")
      RemoteApiServerLog.finishRequest(finishReason = "error", toolCallsEmitted = 0, error = "No model loaded")
      RemoteApiServerLog.error("chat", "No model loaded", requestId)
      return
    }
    val body = try {
      JsonParser.parseString(req.body).asJsonObject
    } catch (e: Exception) {
      sendJson(out, 400, """{"error":{"message":"Invalid JSON: ${escapeJson(e.message ?: "")}"}}""")
      RemoteApiServerLog.finishRequest("error", 0, error = "Invalid JSON")
      return
    }
    val stream = body.get("stream")?.asBoolean ?: false
    val messages = body.getAsJsonArray("messages") ?: run {
      sendJson(out, 400, """{"error":{"message":"messages array is required"}}""")
      RemoteApiServerLog.finishRequest("error", 0, error = "messages array missing")
      return
    }
    val requestMaxTokens = body.get("max_tokens")?.let {
      runCatching { it.asInt }.getOrNull()
    }

    // OpenAI-style tool calling (used by Continue, Cursor, etc. agent modes).
    // We bridge it in/out of the model's plain text channel — see OpenAiToolBridge.
    val toolSpecs = OpenAiToolBridge.parseToolSpecs(body.getAsJsonArray("tools"))
    val toolsEnabled = toolSpecs.isNotEmpty()

    // Flatten OpenAI messages into a single prompt + system instruction.
    val systemSb = StringBuilder()
    val convoSb = StringBuilder()
    for (m in messages) {
      val obj = m.asJsonObject
      val role = obj.get("role")?.asString ?: "user"
      val content = extractContentAsText(obj.get("content"))
      when (role) {
        "system" -> {
          if (content.isBlank()) continue
          if (systemSb.isNotEmpty()) systemSb.append("\n")
          systemSb.append(content)
        }
        "user" -> {
          if (content.isBlank()) continue
          convoSb.append("User: ").append(content).append("\n")
        }
        "assistant" -> {
          val toolCallsText = OpenAiToolBridge.formatAssistantToolCalls(
            obj.getAsJsonArray("tool_calls")
          )
          if (content.isBlank() && toolCallsText.isBlank()) continue
          convoSb.append("Assistant: ")
          if (content.isNotBlank()) convoSb.append(content)
          if (toolCallsText.isNotBlank()) {
            if (content.isNotBlank()) convoSb.append("\n")
            convoSb.append(toolCallsText)
          }
          convoSb.append("\n")
        }
        "tool" -> {
          val toolName = obj.get("name")?.asString
            ?: obj.get("tool_call_id")?.asString
          convoSb.append("User: ")
            .append(OpenAiToolBridge.formatToolResult(toolName, content))
            .append("\n")
        }
        else -> {
          if (content.isBlank()) continue
          convoSb.append(role).append(": ").append(content).append("\n")
        }
      }
    }
    convoSb.append("Assistant: ")
    val prompt = convoSb.toString()

    val toolSuffix = OpenAiToolBridge.buildToolSystemSuffix(toolSpecs)
    val systemInstruction = buildString {
      append(systemSb)
      if (toolSuffix.isNotEmpty()) {
        if (isNotEmpty()) append("\n\n")
        append(toolSuffix)
      }
    }.ifBlank { null }

    // Snapshot the assembled prompt so the control-panel UI can show what the
    // model is about to see.
    RemoteApiServerLog.updatePrompt(
      streaming = stream,
      toolsCount = toolSpecs.size,
      systemInstruction = systemInstruction ?: "",
      prompt = prompt,
    )
    RemoteApiServerLog.info(
      "chat",
      "→ ${if (stream) "streaming" else "buffered"} request: tools=${toolSpecs.size}, " +
        "sys=${(systemInstruction?.length ?: 0)} chars, prompt=${prompt.length} chars",
      requestId,
    )

    // Soft input-length guard. The engine's prefill cache has a hard limit
    // (set at initialize time — see [activeContextSize]). We use ~4 chars-per-
    // token as a conservative estimate and reject before crashing the engine
    // mid-stream.
    //
    // Layout of the KV cache: [ system + prompt | response ]
    //   responseBudget  = max_tokens from request, else cfg.maxTokens, capped at 4096
    //   promptBudget    = activeContextSize - responseBudget - 512 (safety)
    val approxTokens = (prompt.length + (systemInstruction?.length ?: 0)) / 4
    val responseBudget = (requestMaxTokens ?: cfg.maxTokens).coerceIn(64, 4096)
    val promptBudget = (activeContextSize - responseBudget - 512).coerceAtLeast(2_000)
    if (approxTokens > promptBudget) {
      Log.w(TAG, "Rejecting oversized prompt: ~$approxTokens tokens > $promptBudget (response budget=$responseBudget)")
      RemoteApiServerLog.warn(
        "chat",
        "Rejected oversized prompt: ~$approxTokens > $promptBudget (response budget $responseBudget)",
        requestId,
      )
      sendJson(
        out,
        400,
        """{"error":{"message":"Prompt too long (~$approxTokens tokens, limit $promptBudget with $responseBudget reserved for response). Trim the conversation history or reduce file context.","type":"context_length_exceeded","code":"context_length_exceeded"}}""",
      )
      RemoteApiServerLog.finishRequest("error", 0, error = "context_length_exceeded")
      return
    }

    // Serialize all inference through the mutex.
    runBlocking {
      inferenceMutex.withLock {
        try {
          // Reset conversation so each request is stateless (clients send full history).
          val systemContents = systemInstruction?.let {
            com.google.ai.edge.litertlm.Contents.of(
              listOf(com.google.ai.edge.litertlm.Content.Text(it))
            )
          }
          LlmChatModelHelper.resetConversation(
            model = model,
            supportImage = false,
            supportAudio = false,
            systemInstruction = systemContents,
            tools = emptyList(),
            enableConversationConstrainedDecoding = false,
          )

          if (toolsEnabled) {
            // When tools are in play we must inspect the full text to detect
            // <tool_call> markers — so always buffer, then emit either a
            // streaming or non-streaming response with tool_calls if present.
            runToolAwareInference(out, model, prompt, stream, requestId)
          } else if (stream) {
            runStreamingInference(out, model, prompt, requestId)
          } else {
            runBufferedInference(out, model, prompt, requestId)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Inference error", e)
          RemoteApiServerLog.error("chat", "Inference exception: ${e.message ?: e.javaClass.simpleName}", requestId)
          RemoteApiServerLog.finishRequest("error", 0, error = e.message)
          try {
            sendJson(out, 500, """{"error":{"message":"${escapeJson(e.message ?: "Inference failed")}"}}""")
          } catch (_: Exception) {}
        }
      }
    }
  }

  private fun runStreamingInference(out: OutputStream, model: Model, prompt: String, requestId: Long) {
    val writer = PrintWriter(OutputStreamWriter(out, StandardCharsets.UTF_8), false)
    writer.print(
      "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/event-stream\r\n" +
        "Cache-Control: no-cache\r\n" +
        "Connection: keep-alive\r\n" +
        "Access-Control-Allow-Origin: *\r\n" +
        "X-Accel-Buffering: no\r\n" +
        "Transfer-Encoding: chunked\r\n\r\n"
    )
    writer.flush()

    val id = "chatcmpl-${System.currentTimeMillis()}"
    val created = System.currentTimeMillis() / 1000
    val modelName = model.name

    fun writeChunk(payload: String) {
      val data = "data: $payload\n\n"
      val bytes = data.toByteArray(StandardCharsets.UTF_8)
      // chunked encoding: hex-length\r\n<bytes>\r\n
      writer.print("${bytes.size.toString(16)}\r\n")
      writer.flush()
      out.write(bytes)
      out.write("\r\n".toByteArray())
      out.flush()
    }

    val done = java.util.concurrent.CountDownLatch(1)
    val errorRef = AtomicReference<String?>(null)

    LlmChatModelHelper.runInference(
      model = model,
      input = prompt,
      resultListener = { partialToken, isDone, _ ->
        if (isDone) {
          val finalChunk = JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion.chunk")
            addProperty("created", created)
            addProperty("model", modelName)
            add("choices", Gson().toJsonTree(
              listOf(
                mapOf(
                  "index" to 0,
                  "delta" to emptyMap<String, String>(),
                  "finish_reason" to "stop",
                )
              )
            ))
          }
          try {
            writeChunk(Gson().toJson(finalChunk))
            // closing chunk for chunked-transfer encoding
            writer.print("0\r\n\r\n")
            writer.flush()
          } catch (_: Exception) {}
          done.countDown()
        } else {
          if (partialToken.isNotEmpty()) {
            RemoteApiServerLog.appendResponse(partialToken)
            val chunk = JsonObject().apply {
              addProperty("id", id)
              addProperty("object", "chat.completion.chunk")
              addProperty("created", created)
              addProperty("model", modelName)
              add("choices", Gson().toJsonTree(
                listOf(
                  mapOf(
                    "index" to 0,
                    "delta" to mapOf("content" to partialToken),
                    "finish_reason" to null,
                  )
                )
              ))
            }
            try { writeChunk(Gson().toJson(chunk)) } catch (e: Exception) {
              errorRef.set(e.message); done.countDown()
            }
          }
        }
      },
      cleanUpListener = {},
      onError = { msg -> errorRef.set(msg); done.countDown() },
      images = emptyList(),
      audioClips = emptyList(),
      coroutineScope = null,
      extraContext = null,
    )
    done.await()
    val err = errorRef.get()
    if (err != null) {
      Log.w(TAG, "streaming error: $err")
      RemoteApiServerLog.error("chat", "Streaming error: $err", requestId)
      RemoteApiServerLog.finishRequest("error", 0, error = err)
    } else {
      RemoteApiServerLog.info("chat", "← streaming complete", requestId)
      RemoteApiServerLog.finishRequest("stop", 0)
    }
  }

  /**
   * Runs inference, buffers the full response, then emits a tool-aware reply.
   * If the model's output contains tool-call markers, we surface them in the
   * OpenAI `tool_calls` field with `finish_reason="tool_calls"`; otherwise we
   * fall back to plain content.
   */
  private fun runToolAwareInference(
    out: OutputStream,
    model: Model,
    prompt: String,
    stream: Boolean,
    requestId: Long,
  ) {
    val sb = StringBuilder()
    val done = java.util.concurrent.CountDownLatch(1)
    val errorRef = AtomicReference<String?>(null)
    LlmChatModelHelper.runInference(
      model = model,
      input = prompt,
      resultListener = { partialToken, isDone, _ ->
        if (partialToken.isNotEmpty()) {
          sb.append(partialToken)
          RemoteApiServerLog.appendResponse(partialToken)
        }
        if (isDone) done.countDown()
      },
      cleanUpListener = {},
      onError = { msg -> errorRef.set(msg); done.countDown() },
      images = emptyList(),
      audioClips = emptyList(),
      coroutineScope = null,
      extraContext = null,
    )
    done.await()
    val err = errorRef.get()
    if (err != null) {
      sendJson(out, 500, """{"error":{"message":"${escapeJson(err)}"}}""")
      RemoteApiServerLog.error("chat", "Tool-aware inference error: $err", requestId)
      RemoteApiServerLog.finishRequest("error", 0, error = err)
      return
    }
    val parsed = OpenAiToolBridge.extractToolCalls(sb.toString())
    val hasToolCalls = parsed.toolCalls.isNotEmpty()
    if (hasToolCalls) {
      val names = parsed.toolCalls.joinToString(", ") { it.name }
      RemoteApiServerLog.info("chat", "← tool_calls: $names", requestId)
    } else {
      RemoteApiServerLog.info("chat", "← plain text response (${parsed.remainingText.length} chars)", requestId)
    }
    val id = "chatcmpl-${System.currentTimeMillis()}"
    val created = System.currentTimeMillis() / 1000

    if (!stream) {
      val message = JsonObject().apply {
        addProperty("role", "assistant")
        if (hasToolCalls) {
          // OpenAI clients expect content to be null when tool_calls are set.
          add("content", com.google.gson.JsonNull.INSTANCE)
          add("tool_calls", OpenAiToolBridge.toToolCallsArray(parsed.toolCalls))
        } else {
          addProperty("content", parsed.remainingText)
        }
      }
      val response = JsonObject().apply {
        addProperty("id", id)
        addProperty("object", "chat.completion")
        addProperty("created", created)
        addProperty("model", model.name)
        add("choices", com.google.gson.JsonArray().apply {
          add(JsonObject().apply {
            addProperty("index", 0)
            add("message", message)
            addProperty("finish_reason", if (hasToolCalls) "tool_calls" else "stop")
          })
        })
      }
      sendJson(out, 200, Gson().toJson(response))
      RemoteApiServerLog.finishRequest(
        finishReason = if (hasToolCalls) "tool_calls" else "stop",
        toolCallsEmitted = parsed.toolCalls.size,
      )
      return
    }

    // Streaming path: emit the full result as one or two SSE chunks. Continue
    // and similar agent clients buffer chunks and only act on finish_reason,
    // so a single-shot emission is sufficient and avoids partial-JSON parsing.
    val writer = PrintWriter(OutputStreamWriter(out, StandardCharsets.UTF_8), false)
    writer.print(
      "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/event-stream\r\n" +
        "Cache-Control: no-cache\r\n" +
        "Connection: keep-alive\r\n" +
        "Access-Control-Allow-Origin: *\r\n" +
        "X-Accel-Buffering: no\r\n" +
        "Transfer-Encoding: chunked\r\n\r\n"
    )
    writer.flush()

    fun writeChunk(payload: String) {
      val data = "data: $payload\n\n"
      val bytes = data.toByteArray(StandardCharsets.UTF_8)
      writer.print("${bytes.size.toString(16)}\r\n"); writer.flush()
      out.write(bytes); out.write("\r\n".toByteArray()); out.flush()
    }

    fun baseChunk(delta: JsonObject, finish: String?): JsonObject = JsonObject().apply {
      addProperty("id", id)
      addProperty("object", "chat.completion.chunk")
      addProperty("created", created)
      addProperty("model", model.name)
      add("choices", com.google.gson.JsonArray().apply {
        add(JsonObject().apply {
          addProperty("index", 0)
          add("delta", delta)
          if (finish != null) addProperty("finish_reason", finish)
          else add("finish_reason", com.google.gson.JsonNull.INSTANCE)
        })
      })
    }

    try {
      if (hasToolCalls) {
        // Single chunk with role + tool_calls (each with arguments stringified).
        val toolCallsDelta = com.google.gson.JsonArray()
        for ((idx, c) in parsed.toolCalls.withIndex()) {
          toolCallsDelta.add(JsonObject().apply {
            addProperty("index", idx)
            addProperty("id", c.id)
            addProperty("type", "function")
            add("function", JsonObject().apply {
              addProperty("name", c.name)
              addProperty("arguments", c.argumentsJson)
            })
          })
        }
        val delta = JsonObject().apply {
          addProperty("role", "assistant")
          add("tool_calls", toolCallsDelta)
        }
        writeChunk(Gson().toJson(baseChunk(delta, null)))
        writeChunk(Gson().toJson(baseChunk(JsonObject(), "tool_calls")))
      } else {
        val delta = JsonObject().apply {
          addProperty("role", "assistant")
          addProperty("content", parsed.remainingText)
        }
        writeChunk(Gson().toJson(baseChunk(delta, null)))
        writeChunk(Gson().toJson(baseChunk(JsonObject(), "stop")))
      }
      writeChunk("[DONE]")
      writer.print("0\r\n\r\n")
      writer.flush()
      RemoteApiServerLog.finishRequest(
        finishReason = if (hasToolCalls) "tool_calls" else "stop",
        toolCallsEmitted = parsed.toolCalls.size,
      )
    } catch (e: Exception) {
      Log.w(TAG, "tool-aware stream emit failed", e)
      RemoteApiServerLog.error("chat", "Stream emit failed: ${e.message}", requestId)
      RemoteApiServerLog.finishRequest("error", parsed.toolCalls.size, error = e.message)
    }
  }

  private fun runBufferedInference(out: OutputStream, model: Model, prompt: String, requestId: Long) {
    val sb = StringBuilder()
    val done = java.util.concurrent.CountDownLatch(1)
    val errorRef = AtomicReference<String?>(null)
    LlmChatModelHelper.runInference(
      model = model,
      input = prompt,
      resultListener = { partialToken, isDone, _ ->
        if (partialToken.isNotEmpty()) {
          sb.append(partialToken)
          RemoteApiServerLog.appendResponse(partialToken)
        }
        if (isDone) done.countDown()
      },
      cleanUpListener = {},
      onError = { msg -> errorRef.set(msg); done.countDown() },
      images = emptyList(),
      audioClips = emptyList(),
      coroutineScope = null,
      extraContext = null,
    )
    done.await()
    val err = errorRef.get()
    if (err != null) {
      sendJson(out, 500, """{"error":{"message":"${escapeJson(err)}"}}""")
      RemoteApiServerLog.error("chat", "Buffered inference error: $err", requestId)
      RemoteApiServerLog.finishRequest("error", 0, error = err)
      return
    }
    val response = JsonObject().apply {
      addProperty("id", "chatcmpl-${System.currentTimeMillis()}")
      addProperty("object", "chat.completion")
      addProperty("created", System.currentTimeMillis() / 1000)
      addProperty("model", model.name)
      add("choices", Gson().toJsonTree(
        listOf(
          mapOf(
            "index" to 0,
            "message" to mapOf("role" to "assistant", "content" to sb.toString()),
            "finish_reason" to "stop",
          )
        )
      ))
    }
    sendJson(out, 200, Gson().toJson(response))
    RemoteApiServerLog.info("chat", "← buffered response (${sb.length} chars)", requestId)
    RemoteApiServerLog.finishRequest("stop", 0)
  }

  // ── HTTP plumbing ──────────────────────────────────────────────────────

  private data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
  )

  private fun parseRequest(input: InputStream): HttpRequest? {
    val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
    val line = reader.readLine() ?: return null
    val parts = line.split(" ")
    if (parts.size < 3) return null
    val method = parts[0]
    val path = parts[1].substringBefore('?')
    val headers = mutableMapOf<String, String>()
    while (true) {
      val h = reader.readLine() ?: break
      if (h.isEmpty()) break
      val idx = h.indexOf(':')
      if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
    }
    val len = headers["content-length"]?.toIntOrNull() ?: 0
    val body = if (len > 0) {
      val buf = CharArray(len)
      var read = 0
      while (read < len) {
        val n = reader.read(buf, read, len - read)
        if (n < 0) break
        read += n
      }
      String(buf, 0, read)
    } else ""
    return HttpRequest(method, path, headers, body)
  }

  private fun sendJson(out: OutputStream, status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    val statusText = when (status) {
      200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
      404 -> "Not Found"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
      else -> "OK"
    }
    val headers = "HTTP/1.1 $status $statusText\r\n" +
      "Content-Type: application/json; charset=utf-8\r\n" +
      "Content-Length: ${bytes.size}\r\n" +
      "Access-Control-Allow-Origin: *\r\n" +
      "Connection: close\r\n\r\n"
    out.write(headers.toByteArray(StandardCharsets.UTF_8))
    out.write(bytes)
    out.flush()
  }

  private fun sendOptions(out: OutputStream) {
    val headers = "HTTP/1.1 204 No Content\r\n" +
      "Access-Control-Allow-Origin: *\r\n" +
      "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
      "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
      "Content-Length: 0\r\n" +
      "Connection: close\r\n\r\n"
    out.write(headers.toByteArray(StandardCharsets.UTF_8))
    out.flush()
  }

  private fun extractContentAsText(content: com.google.gson.JsonElement?): String {
    if (content == null || content.isJsonNull) return ""
    if (content.isJsonPrimitive) return content.asString
    if (content.isJsonArray) {
      val sb = StringBuilder()
      for (part in content.asJsonArray) {
        if (part.isJsonObject) {
          val po = part.asJsonObject
          val type = po.get("type")?.asString
          if (type == "text" || type == null) {
            val t = po.get("text")?.asString
            if (!t.isNullOrEmpty()) sb.append(t)
          }
          // image_url / input_audio parts are ignored in v1 (text-only).
        }
      }
      return sb.toString()
    }
    return ""
  }

  private fun escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

  // ── Notification ──────────────────────────────────────────────────────

  private fun foregroundType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    else 0

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(NotificationManager::class.java)
      if (nm.getNotificationChannel(CHANNEL_ID) == null) {
        nm.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "Remote API Server", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Local LLM HTTP server status" }
        )
      }
    }
  }

  private fun buildNotification(text: String): Notification {
    val stopIntent = Intent(this, RemoteApiServerService::class.java).apply { action = ACTION_STOP }
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    else PendingIntent.FLAG_UPDATE_CURRENT
    val stopPi = PendingIntent.getService(this, 1, stopIntent, flags)
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle("Remote API Server")
      .setContentText(text)
      .setOngoing(true)
      .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
      .build()
  }

  private fun updateNotification(text: String) {
    val nm = getSystemService(NotificationManager::class.java) ?: return
    nm.notify(NOTIFICATION_ID, buildNotification(text))
  }
}

/** Returns the device's LAN IPv4 address (e.g. 192.168.x.x), or null if not on Wi-Fi. */
fun lanIpv4(context: Context): String? {
  // Try WifiManager first.
  try {
    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val ipInt = wm?.connectionInfo?.ipAddress ?: 0
    if (ipInt != 0) {
      return "${ipInt and 0xff}.${(ipInt shr 8) and 0xff}.${(ipInt shr 16) and 0xff}.${(ipInt shr 24) and 0xff}"
    }
  } catch (_: Exception) {}
  // Fallback: scan interfaces.
  try {
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
    for (iface in ifaces) {
      if (iface.isLoopback || !iface.isUp) continue
      for (addr in iface.inetAddresses) {
        val host = addr.hostAddress ?: continue
        if (!addr.isLoopbackAddress && host.indexOf(':') < 0 && host != "0.0.0.0") {
          return host
        }
      }
    }
  } catch (_: Exception) {}
  return null
}
