/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.orchestrator

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGAgentModelPool"

/**
 * Wraps a specialist [model] with a per-model mutex for sequential dispatch.
 *
 * [sharedWithPlanner] is informational only: true when this specialist points to the same weights
 * file as the planner (shown in the UI roster). The specialist **always** gets its own Engine
 * instance — the planner Engine is never reused during dispatch, preserving the planner's
 * multi-turn conversation history across all specialist calls.
 */
data class SpecialistEntry(
  val model: Model,
  val sharedWithPlanner: Boolean,
  val mutex: Mutex = Mutex(),
)

/**
 * Manages a pool of warm LLM specialist engines alongside the planner engine.
 *
 * - The **planner** model drives the conversation and dispatches sub-tasks via tool calls.
 * - Each **specialist** model handles a specific sub-task using domain-specific ToolSets.
 * - **Independent specialists** ([SpecialistEntry.sharedWithPlanner] = false) have their own
 *   Engine. They are initialized concurrently and cleaned up independently.
 * - **Shared specialists** ([SpecialistEntry.sharedWithPlanner] = true) reuse the planner's
 *   Engine. At dispatch time the planner's [Conversation] is saved, a new specialist conversation
 *   is created on the same Engine, and the planner's conversation is restored afterwards.
 *   No extra GPU memory is allocated for them.
 * - The planner chooses the best specialist by name at dispatch time; unrecognised names fall back
 *   to the first available specialist.
 */
class AgentModelPool(
  val plannerModel: Model,
  /** All available specialist models, keyed by their [Model.name]. */
  val specialists: Map<String, SpecialistEntry>,
) {
  /** Serializes access to the planner's Engine when a shared specialist is dispatched. */
  private val plannerEngineMutex = Mutex()

  /** Cached planner config so a shared-engine dispatch can re-create the planner conversation. */
  private var cachedPlannerSystemPrompt: Contents? = null
  private var cachedPlannerTools: List<ToolProvider> = emptyList()
  /**
   * Initializes the planner and ALL specialist models concurrently.
   *
   * Each specialist always gets its own Engine (even when [SpecialistEntry.sharedWithPlanner] is
   * true), so the planner's conversation is never touched during specialist dispatch.
   *
   * [onDone] is called once every engine is ready (or on first error).
   */
  fun initializeAll(
    context: Context,
    plannerSystemPrompt: Contents?,
    plannerTools: List<ToolProvider>,
    onDone: (String) -> Unit,
  ) {
    cachedPlannerSystemPrompt = plannerSystemPrompt
    cachedPlannerTools = plannerTools
    // total = planner + independent specialists only (shared ones reuse the planner Engine)
    val total = 1 + specialists.values.count { !it.sharedWithPlanner }
    val completedCount = AtomicInteger(0)
    val firstError = AtomicReference("")

    fun checkDone(error: String) {
      if (error.isNotEmpty()) firstError.compareAndSet("", error)
      if (completedCount.incrementAndGet() == total) onDone(firstError.get())
    }

    Log.d(TAG, "Initializing planner: ${plannerModel.name}")
    LlmChatModelHelper.initialize(
      context = context,
      model = plannerModel,
      supportImage = false,
      supportAudio = false,
      systemInstruction = plannerSystemPrompt,
      tools = plannerTools,
      enableConversationConstrainedDecoding = true,
      onDone = { error ->
        Log.d(TAG, "Planner ready. error='$error'")
        checkDone(error)
      },
    )

    for (entry in specialists.values) {
      if (entry.sharedWithPlanner) {
        Log.d(TAG, "Skipping shared-engine specialist: ${entry.model.name} (reuses planner Engine)")
        continue
      }
      Log.d(TAG, "Initializing independent specialist: ${entry.model.name}")
      LlmChatModelHelper.initialize(
        context = context,
        model = entry.model,
        supportImage = false,
        supportAudio = false,
        systemInstruction = null,
        tools = listOf(),
        onDone = { error ->
          Log.d(TAG, "Specialist ${entry.model.name} ready. error='$error'")
          checkDone(error)
        },
      )
    }
  }

  /** Closes all engines and frees GPU memory. */
  fun cleanUpAll() {
    LlmChatModelHelper.cleanUp(plannerModel) { Log.d(TAG, "Planner cleaned up.") }
    // Only independent specialists have their own Engine; shared ones reuse the planner Engine.
    for (entry in specialists.values) {
      if (entry.sharedWithPlanner) {
        Log.d(TAG, "Skipping cleanup for shared-engine specialist: ${entry.model.name}")
        continue
      }
      LlmChatModelHelper.cleanUp(entry.model) {
        Log.d(TAG, "Specialist ${entry.model.name} cleaned up.")
      }
    }
  }

  /**
   * Returns a short description of each specialist model for inclusion in the planner system
   * prompt, so the LLM can make informed dispatch decisions.
   */
  fun specialistRoster(): List<String> =
    specialists.values.map { entry ->
      val shared = if (entry.sharedWithPlanner) " [same weights as planner]" else ""
      "  • ${entry.model.name}$shared: ${entry.model.info.ifEmpty { entry.model.displayName.ifEmpty { entry.model.name } }}"
    }

  /**
   * Tolerant resolution of a planner-supplied model name to a specialist [Map.Entry] of the
   * pool. The lookup is exact-key first, then substring (any specialist whose key appears inside
   * the requested string — small planners often hallucinate the verbose roster description into
   * `modelName`), then falls back to the first specialist. Returns the **map key** (the
   * user-facing original model name, without the internal `-specialist` suffix) so callers can
   * use it for per-model configuration lookups.
   */
  fun resolveSpecialistKey(preferredName: String): String? {
    if (specialists.isEmpty()) return null
    if (specialists.containsKey(preferredName)) return preferredName
    if (preferredName.isNotBlank()) {
      val match = specialists.keys.firstOrNull { preferredName.contains(it) }
      if (match != null) return match
    }
    return specialists.keys.firstOrNull()
  }

  /**
   * Dispatches a request to the best available specialist engine, chosen by [preferredModelName].
   *
   * Falls back to the first specialist in the pool if [preferredModelName] is not found.
   *
   * - **Independent specialists** ([SpecialistEntry.sharedWithPlanner] = false): the specialist's
   *   own conversation is reset and inference runs on its dedicated Engine.
   * - **Shared specialists** ([SpecialistEntry.sharedWithPlanner] = true): the planner's Engine is
   *   borrowed. The planner's [Conversation] is saved before a new specialist conversation is
   *   created, and is restored in a `finally` block so the planner's history is never lost.
   */
  @OptIn(ExperimentalApi::class)
  fun dispatchBlocking(
    request: String,
    preferredModelName: String,
    systemInstruction: Contents,
    tools: List<ToolProvider>,
  ): String {
    // Tolerant lookup: small planners often hallucinate the verbose roster description into
    // modelName. Reuse the public resolver so callers (e.g. PlannerTools) can pre-resolve the
    // same key to load per-model config.
    val key = resolveSpecialistKey(preferredModelName)
    val entry = key?.let { specialists[it] } ?: run {
      OrchestratorStatus.addLog("system", "dispatchBlocking: no specialists available")
      return "Error: no specialist models available in the pool."
    }
    OrchestratorStatus.addLog(
      "system",
      "dispatch \u2192 ${entry.model.name} (sharedEngine=${entry.sharedWithPlanner})",
    )

    return if (entry.sharedWithPlanner) {
      // --- Shared-engine path: borrow planner Engine. The litertlm engine only allows ONE
      // conversation at a time, so we MUST close the planner's conversation before creating the
      // specialist conversation, then re-create the planner conversation afterwards from the
      // cached planner config (system prompt + tools). The planner's per-turn history is reset
      // — acceptable here because the planner is a stateless router for each user turn.
      Log.d(TAG, "Dispatching to shared specialist '${entry.model.name}' (planner Engine)")
      runBlocking {
        plannerEngineMutex.withLock {
          val plannerInstance = plannerModel.instance as? LlmModelInstance
            ?: return@withLock "Error: planner model not initialized."

          // Close planner conversation so the engine accepts a new one.
          try {
            plannerInstance.conversation.close()
          } catch (e: Exception) {
            Log.w(TAG, "Closing planner conversation before specialist dispatch failed", e)
          }

          val accelerator = plannerModel.getStringConfigValue(
            key = ConfigKeys.ACCELERATOR,
            defaultValue = Accelerator.GPU.label,
          )
          val samplerCfg =
            if (accelerator == Accelerator.NPU.label || accelerator == Accelerator.TPU.label) {
              null
            } else {
              SamplerConfig(
                topK = plannerModel.getIntConfigValue(
                  key = ConfigKeys.TOPK,
                  defaultValue = DEFAULT_TOPK,
                ),
                topP = plannerModel.getFloatConfigValue(
                  key = ConfigKeys.TOPP,
                  defaultValue = DEFAULT_TOPP,
                ).toDouble(),
                temperature = plannerModel.getFloatConfigValue(
                  key = ConfigKeys.TEMPERATURE,
                  defaultValue = DEFAULT_TEMPERATURE,
                ).toDouble(),
              )
            }

          val specialistConv = run {
            com.google.ai.edge.litertlm.ExperimentalFlags.enableConversationConstrainedDecoding = true
            try {
              plannerInstance.engine.createConversation(
                ConversationConfig(
                  samplerConfig = samplerCfg,
                  systemInstruction = systemInstruction,
                  tools = tools,
                )
              )
            } finally {
              com.google.ai.edge.litertlm.ExperimentalFlags.enableConversationConstrainedDecoding = false
            }
          }
          plannerInstance.conversation = specialistConv

          val result = StringBuilder()
          try {
            val contents = Contents.of(listOf(Content.Text(request)))
            OrchestratorStatus.addLog(entry.model.name, "inference start (shared engine)")
            specialistConv.sendMessageAsync(contents)
              .collect { message -> result.append(message.toString()) }
            Log.d(TAG, "Shared dispatch done ('${entry.model.name}'). Length: ${result.length}")
            OrchestratorStatus.addLog(entry.model.name, "inference done (${result.length} chars)")
          } catch (e: Exception) {
            Log.e(TAG, "Shared specialist inference error", e)
            OrchestratorStatus.addLog(entry.model.name, "inference EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            result.append("Error during specialist inference: ${e.message}")
          } finally {
            try {
              specialistConv.close()
            } catch (e: Exception) {
              Log.w(TAG, "Closing specialist conversation failed", e)
            }
            // Re-create the planner conversation from cached config so subsequent planner
            // turns work. History from prior planner turns is lost — acceptable because each
            // user turn re-prompts the planner and dispatches once.
            try {
              com.google.ai.edge.litertlm.ExperimentalFlags.enableConversationConstrainedDecoding = true
              val newPlannerConv = plannerInstance.engine.createConversation(
                ConversationConfig(
                  samplerConfig = samplerCfg,
                  systemInstruction = cachedPlannerSystemPrompt,
                  tools = cachedPlannerTools,
                )
              )
              plannerInstance.conversation = newPlannerConv
            } catch (e: Exception) {
              Log.e(TAG, "Failed to recreate planner conversation", e)
              OrchestratorStatus.addLog("system", "Failed to recreate planner conversation: ${e.message}")
            } finally {
              com.google.ai.edge.litertlm.ExperimentalFlags.enableConversationConstrainedDecoding = false
            }
            Log.d(TAG, "Planner conversation recreated after shared dispatch.")
          }
          result.toString()
        }
      }
    } else {
      // --- Independent specialist path: own Engine, reset its conversation ---
      val targetModel = entry.model
      Log.d(TAG, "Dispatching to independent specialist '${entry.model.name}'")
      runBlocking {
        entry.mutex.withLock {
          val instance = targetModel.instance as? LlmModelInstance
          if (instance == null) {
            Log.e(TAG, "Specialist ${entry.model.name} not initialized")
            return@withLock "Error: specialist model '${entry.model.name}' is not initialized."
          }

          // Reset conversation: swaps system prompt + tools, keeps GPU weights hot (~50 ms).
          LlmChatModelHelper.resetConversation(
            model = targetModel,
            supportImage = false,
            supportAudio = false,
            systemInstruction = systemInstruction,
            tools = tools,
            enableConversationConstrainedDecoding = true,
          )

          val updatedInstance = targetModel.instance as? LlmModelInstance
            ?: return@withLock "Error: specialist model reset failed."

          val contents = Contents.of(listOf(Content.Text(request)))
          val result = StringBuilder()
          try {
            OrchestratorStatus.addLog(entry.model.name, "inference start (own engine)")
            updatedInstance.conversation
              .sendMessageAsync(contents)
              .collect { message -> result.append(message.toString()) }
          } catch (e: Exception) {
            Log.e(TAG, "Specialist inference error", e)
            OrchestratorStatus.addLog(entry.model.name, "inference EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            return@withLock "Error during specialist inference: ${e.message}"
          }

          Log.d(TAG, "Dispatch done ('${entry.model.name}'). Response length: ${result.length}")
          OrchestratorStatus.addLog(entry.model.name, "inference done (${result.length} chars)")
          result.toString()
        }
      }
    }
  }
}
