/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.google.ai.edge.gallery.customtasks.orchestrator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide observable status for the V2 Orchestrator. Updated by [AgentModelPool] and
 * [PlannerTools] before/after each dispatch, and read by the status sheet in `AgentChatScreen`.
 */
object OrchestratorStatus {
  /** A model currently held in RAM (engine initialized) and the agent role(s) it can play. */
  data class ModelEntry(
    val name: String,
    val role: String, // "planner" | "specialist" | "planner+specialist"
    val sharedWithPlanner: Boolean,
    val tools: List<String>,
    /** Approximate weights size on disk in bytes (≈ RAM footprint when loaded). 0 = unknown. */
    val sizeBytes: Long = 0L,
  )

  /** A single entry in the orchestration log. */
  data class LogEntry(
    val timestamp: Long,
    val source: String, // e.g. "planner", "mobile_agent", "system"
    val message: String,
  )

  private val _plannerName = MutableStateFlow<String>("")
  val plannerName: StateFlow<String> = _plannerName.asStateFlow()

  /** Specialist currently running a dispatch (null when idle). */
  private val _activeSpecialist = MutableStateFlow<String?>(null)
  val activeSpecialist: StateFlow<String?> = _activeSpecialist.asStateFlow()

  /** Short human-readable description of the in-flight or last action. */
  private val _lastActivity = MutableStateFlow<String>("idle")
  val lastActivity: StateFlow<String> = _lastActivity.asStateFlow()

  /** Number of dispatches completed so far. */
  private val _dispatchCount = MutableStateFlow(0)
  val dispatchCount: StateFlow<Int> = _dispatchCount.asStateFlow()

  /** Every model currently held in RAM with the toolset it can use. */
  private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
  val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

  /** Append-only event log of orchestration steps. */
  private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
  val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

  private const val MAX_LOG_ENTRIES = 200

  fun setPlanner(name: String) {
    _plannerName.value = name
    _lastActivity.value = "ready"
    _activeSpecialist.value = null
    _dispatchCount.value = 0
    addLog("system", "Planner ready: $name")
  }

  fun beginDispatch(agentType: String, specialistName: String, request: String) {
    _activeSpecialist.value = "$specialistName ($agentType)"
    val short = request.take(120)
    _lastActivity.value = "→ $agentType: $short"
    addLog("planner", "dispatchToAgent($agentType, model=$specialistName): $short")
  }

  fun endDispatch(agentType: String, resultPreview: String) {
    _activeSpecialist.value = null
    val short = resultPreview.take(120)
    _lastActivity.value = "✓ $agentType: $short"
    _dispatchCount.value = _dispatchCount.value + 1
    addLog(agentType, "result: $short")
  }

  /** Replaces the in-RAM model registry. Called by [AgentModelPool] after init. */
  fun setModels(entries: List<ModelEntry>) {
    _models.value = entries
    val summary = entries.joinToString { "${it.name}[${it.role}]" }
    addLog("system", "Models in RAM: $summary")
  }

  /** Adds a free-form log entry. Also updates [lastActivity] for non-system entries so the
   *  in-chat loading bubble can surface the current action. */
  fun addLog(source: String, message: String) {
    val entry = LogEntry(System.currentTimeMillis(), source, message)
    val cur = _log.value
    val next =
      if (cur.size >= MAX_LOG_ENTRIES) cur.drop(cur.size - MAX_LOG_ENTRIES + 1) + entry
      else cur + entry
    _log.value = next
    if (source != "system") {
      _lastActivity.value = "$source: ${message.take(80)}"
    }
  }

  fun reset() {
    _plannerName.value = ""
    _activeSpecialist.value = null
    _lastActivity.value = "idle"
    _dispatchCount.value = 0
    _models.value = emptyList()
    _log.value = emptyList()
  }
}
