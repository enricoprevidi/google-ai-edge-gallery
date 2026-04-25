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
    val entry = specialists[preferredModelName]
      ?: specialists.values.firstOrNull()
      ?: return "Error: no specialist models available in the pool."

    return if (entry.sharedWithPlanner) {
      // --- Shared-engine path: borrow planner Engine, save and restore planner conversation ---
      Log.d(TAG, "Dispatching to shared specialist '${entry.model.name}' (planner Engine)")
      runBlocking {
        plannerEngineMutex.withLock {
          val plannerInstance = plannerModel.instance as? LlmModelInstance
            ?: return@withLock "Error: planner model not initialized."

          // Save the planner's conversation — we must NOT close it.
          val savedPlannerConversation = plannerInstance.conversation

          val accelerator = plannerModel.getStringConfigValue(
            key = ConfigKeys.ACCELERATOR,
            defaultValue = Accelerator.GPU.label,
          )
          val specialistConv = plannerInstance.engine.createConversation(
            ConversationConfig(
              samplerConfig =
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
                },
              systemInstruction = systemInstruction,
              tools = tools,
            )
          )
          // Swap in the specialist conversation temporarily.
          plannerInstance.conversation = specialistConv

          val result = StringBuilder()
          try {
            val contents = Contents.of(listOf(Content.Text(request)))
            specialistConv.sendMessageAsync(contents)
              .collect { message -> result.append(message.toString()) }
            Log.d(TAG, "Shared dispatch done ('${entry.model.name}'). Length: ${result.length}")
          } catch (e: Exception) {
            Log.e(TAG, "Shared specialist inference error", e)
            result.append("Error during specialist inference: ${e.message}")
          } finally {
            specialistConv.close()
            // Restore the planner's conversation so its history is preserved.
            plannerInstance.conversation = savedPlannerConversation
            Log.d(TAG, "Planner conversation restored after shared dispatch.")
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
            enableConversationConstrainedDecoding = false,
          )

          val updatedInstance = targetModel.instance as? LlmModelInstance
            ?: return@withLock "Error: specialist model reset failed."

          val contents = Contents.of(listOf(Content.Text(request)))
          val result = StringBuilder()
          try {
            updatedInstance.conversation
              .sendMessageAsync(contents)
              .collect { message -> result.append(message.toString()) }
          } catch (e: Exception) {
            Log.e(TAG, "Specialist inference error", e)
            return@withLock "Error during specialist inference: ${e.message}"
          }

          Log.d(TAG, "Dispatch done ('${entry.model.name}'). Response length: ${result.length}")
          result.toString()
        }
      }
    }
  }
}
