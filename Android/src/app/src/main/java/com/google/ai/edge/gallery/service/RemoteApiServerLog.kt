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

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide log + current-request inspector for [RemoteApiServerService].
 *
 * Designed to be cheap (bounded ring buffer of events) and to never block the
 * worker threads — all state is just `StateFlow` writes that the UI observes.
 *
 * `events` holds the most recent [MAX_EVENTS] log lines across all requests.
 * `currentRequest` holds the full prompt / system instruction / streaming
 * response of the most recent or in-progress chat completion, so the user can
 * see exactly what the model is chewing on.
 */
object RemoteApiServerLog {

  private const val MAX_EVENTS = 200
  private const val MAX_PREVIEW_CHARS = 64_000

  enum class Level { DEBUG, INFO, WARN, ERROR }

  data class Event(
    val timestampMs: Long,
    val level: Level,
    val category: String,
    val message: String,
    val requestId: Long? = null,
  )

  /**
   * Snapshot of the latest chat request the server processed. Fields are
   * populated incrementally as the request progresses.
   */
  data class RequestSnapshot(
    val requestId: Long,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val clientHint: String = "",
    val path: String = "",
    val streaming: Boolean = false,
    val toolsCount: Int = 0,
    val systemInstruction: String = "",
    val prompt: String = "",
    val response: String = "",
    val toolCallsEmitted: Int = 0,
    val promptCharCount: Int = 0,
    val responseCharCount: Int = 0,
    /** Engine context size in tokens (== max_num_tokens at init). */
    val contextSizeTokens: Int = 0,
    /** Conservative chars-per-token estimate used for the usage gauge. */
    val charsPerToken: Int = 4,
    val finishReason: String? = null,
    val error: String? = null,
  ) {
    val promptApproxTokens: Int get() = promptCharCount / charsPerToken
    val responseApproxTokens: Int get() = responseCharCount / charsPerToken
    val durationMs: Long? get() = finishedAtMs?.minus(startedAtMs)
  }

  private val _events = MutableStateFlow<List<Event>>(emptyList())
  val events: StateFlow<List<Event>> = _events.asStateFlow()

  private val _currentRequest = MutableStateFlow<RequestSnapshot?>(null)
  val currentRequest: StateFlow<RequestSnapshot?> = _currentRequest.asStateFlow()

  private val nextRequestId = AtomicLong(1)

  fun log(level: Level, category: String, message: String, requestId: Long? = null) {
    val e = Event(System.currentTimeMillis(), level, category, message, requestId)
    val current = _events.value
    val next = if (current.size >= MAX_EVENTS) {
      current.drop(current.size - MAX_EVENTS + 1) + e
    } else current + e
    _events.value = next
  }

  fun info(category: String, message: String, requestId: Long? = null) =
    log(Level.INFO, category, message, requestId)
  fun warn(category: String, message: String, requestId: Long? = null) =
    log(Level.WARN, category, message, requestId)
  fun error(category: String, message: String, requestId: Long? = null) =
    log(Level.ERROR, category, message, requestId)
  fun debug(category: String, message: String, requestId: Long? = null) =
    log(Level.DEBUG, category, message, requestId)

  fun clearEvents() {
    _events.value = emptyList()
  }

  // ── Current request lifecycle ───────────────────────────────────────────

  fun beginRequest(
    clientHint: String,
    path: String,
    contextSizeTokens: Int,
  ): Long {
    val id = nextRequestId.getAndIncrement()
    _currentRequest.value = RequestSnapshot(
      requestId = id,
      startedAtMs = System.currentTimeMillis(),
      clientHint = clientHint,
      path = path,
      contextSizeTokens = contextSizeTokens,
    )
    return id
  }

  fun updatePrompt(
    streaming: Boolean,
    toolsCount: Int,
    systemInstruction: String,
    prompt: String,
  ) {
    val snap = _currentRequest.value ?: return
    _currentRequest.value = snap.copy(
      streaming = streaming,
      toolsCount = toolsCount,
      systemInstruction = systemInstruction.take(MAX_PREVIEW_CHARS),
      prompt = prompt.take(MAX_PREVIEW_CHARS),
      promptCharCount = prompt.length + systemInstruction.length,
    )
  }

  fun appendResponse(text: String) {
    if (text.isEmpty()) return
    val snap = _currentRequest.value ?: return
    val newResp = (snap.response + text).take(MAX_PREVIEW_CHARS)
    _currentRequest.value = snap.copy(
      response = newResp,
      responseCharCount = snap.responseCharCount + text.length,
    )
  }

  fun finishRequest(finishReason: String?, toolCallsEmitted: Int, error: String? = null) {
    val snap = _currentRequest.value ?: return
    _currentRequest.value = snap.copy(
      finishedAtMs = System.currentTimeMillis(),
      finishReason = finishReason,
      toolCallsEmitted = toolCallsEmitted,
      error = error,
    )
  }
}
