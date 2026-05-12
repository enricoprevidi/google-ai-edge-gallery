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
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "AGPlannerTools"

/**
 * ToolSet registered on the planner model's conversation. Exposes [listSpecialistModels] so the
 * planner can inspect the available model pool and [dispatchToAgent] to execute sub-tasks with the
 * most appropriate specialist model.
 */
class PlannerTools(
  private val context: Context,
  private val agentModelPool: AgentModelPool,
  private val skillManagerViewModel: SkillManagerViewModel,
  /** Shared AgentTools instance owned by OrchestratorTask so it's always available for
   *  the screen to subscribe to, even before the planner model is initialized. */
  val agentTools: AgentTools,
  private val workspaceUri: () -> String?,
  private val onActionTaken: (OrchestratorAction) -> Unit,
) : ToolSet {

  // NOTE: listSpecialistModels / listAvailableSkills were intentionally removed from the
  // planner's tool surface to prevent small models (e.g. Gemma 3-4B IT) from looping on
  // discovery calls. The roster is injected statically into the system prompt instead.

  @Tool(
    description =
      "Dispatches a sub-task to a specialized agent and returns the agent's response. " +
        "agentType: 'mobile_agent' (flashlight, contacts, calendar, email, map, WiFi, Morse), " +
        "'app_launcher' (list/launch apps, send intents), " +
        "'workspace_agent' (read/write/list files in the workspace folder), " +
        "'skill_creator' (generate and import new skills), " +
        "'skill_agent' (execute installed skills like query-wikipedia, qr-code via JavaScript)."
  )
  fun dispatchToAgent(
    @ToolParam(
      description = "Agent to use: 'mobile_agent', 'app_launcher', 'workspace_agent', 'skill_creator', or 'skill_agent'."
    )
    agentType: String,
    @ToolParam(
      description =
        "Detailed description of the task for the agent. Include all relevant parameters."
    )
    request: String,
    @ToolParam(
      description = "Always pass an empty string \"\". The runtime picks the specialist."
    )
    modelName: String = "",
  ): Map<String, String> {
    val type =
      AgentType.fromId(agentType)
        ?: return mapOf(
          "error" to
            "Unknown agent type: '$agentType'. Use: mobile_agent, app_launcher, workspace_agent, skill_creator, skill_agent."
        )
    Log.d(TAG, "dispatchToAgent: type=$type model='$modelName' request=${request.take(100)}")

    // Resolve the specialist key BEFORE building the tool config so any per-model overrides
    // configured by the user apply to the correct specialist (the resolver is tolerant of
    // hallucinated model names — see [AgentModelPool.resolveSpecialistKey]).
    var resolvedKey = agentModelPool.resolveSpecialistKey(modelName)

    // Capability-aware routing: if the planner-named specialist has NO tools configured for
    // the requested AgentType, fall through to the first specialist that does. Without this
    // a small planner that hallucinates a model name (or picks a specialist whose tools have
    // been intentionally disabled in the picker) silently dispatches to a no-op.
    val initialGroups = groupsForSpecialist(resolvedKey, type)
    if (initialGroups.isEmpty()) {
      val betterKey = agentModelPool.specialists.keys.firstOrNull { key ->
        key != resolvedKey && groupsForSpecialist(key, type).isNotEmpty()
      }
      if (betterKey != null) {
        Log.d(
          TAG,
          "Re-routing dispatch from '$resolvedKey' to '$betterKey' " +
            "(no tools configured for $type on the original specialist).",
        )
        OrchestratorStatus.addLog(
          "system",
          "rerouted ${type.id}: $resolvedKey \u2192 $betterKey (no tools on original)",
        )
        resolvedKey = betterKey
      }
    }

    val (systemInstruction, tools, postProcessResult) = buildSpecialistConfig(type, resolvedKey)
    val resolvedSpecialist =
      resolvedKey ?: (agentModelPool.specialistRoster().firstOrNull() ?: "default")
    OrchestratorStatus.beginDispatch(
      agentType = agentType,
      specialistName = resolvedSpecialist,
      request = request,
    )
    val rawResult =
      try {
        agentModelPool.dispatchBlocking(
          request = request,
          preferredModelName = resolvedKey ?: modelName,
          systemInstruction = systemInstruction,
          tools = tools,
        )
      } catch (t: Throwable) {
        Log.e(TAG, "dispatchBlocking threw", t)
        OrchestratorStatus.addLog(agentType, "EXCEPTION: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}")
        "Error: ${t.javaClass.simpleName}: ${t.message ?: "unknown failure"}"
      }
    val result = postProcessResult(rawResult)
    OrchestratorStatus.endDispatch(agentType = agentType, resultPreview = result)
    onActionTaken(DispatchAction(agentType = type, request = request, result = result))
    return mapOf(
      "agent" to type.displayName,
      "model" to (resolvedKey ?: modelName),
      "result" to result,
      "status" to "completed",
      "instruction_for_planner" to
        "Sub-task completed. Reply to the user with a SHORT plain-text summary now. Do NOT call any more tools for this user request.",
    )
  }

  /**
   * Returns the configured tool groups for a (specialist, [AgentType]) pair, applying the same
   * fallback rules used in [buildSpecialistConfig]: per-model config wins, then per-AgentType
   * defaults if no config exists, then intersected with the valid set for the slot. An empty
   * result means the user explicitly cleared the slot.
   */
  private fun groupsForSpecialist(modelKey: String?, agentType: AgentType): Set<ToolGroup> {
    if (modelKey == null) return emptySet()
    val cfg = OrchestratorToolsConfig.loadSpecialist(context, modelKey)
    return (cfg?.groupsFor(agentType) ?: SpecialistToolsConfig.defaultsFor(agentType))
      .intersect(SpecialistToolsConfig.validFor(agentType))
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  /**
   * Builds the system prompt and tool list for a specialist dispatch.
   *
   * The tool surface is determined by the user's [SpecialistToolsConfig] for the resolved
   * specialist [modelKey]. When no configuration exists (or the lookup fails), the historical
   * defaults from [SpecialistToolsConfig.defaultsFor] are used — preserving today's behaviour
   * for users that never visit the new picker.
   */
  private fun buildSpecialistConfig(
    agentType: AgentType,
    modelKey: String?,
  ): Triple<Contents, List<com.google.ai.edge.litertlm.ToolProvider>, (String) -> String> {
    val now = LocalDateTime.now()
    val dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    val dayOfWeek = now.format(DateTimeFormatter.ofPattern("EEEE"))
    val baseCtx = "Current date/time: $dateTime ($dayOfWeek)."

    // Default post-processor: pass the specialist's response through unchanged.
    var postProcess: (String) -> String = { it }

    // Resolve the configured groups for (specialist, agent type) — falling back to defaults
    // ONLY when no per-specialist config exists. If the user has explicitly cleared a slot
    // (saved config with an empty set), respect that and dispatch with no tools — otherwise
    // disabling tools in the UI would have no effect.
    val groups: Set<ToolGroup> = groupsForSpecialist(modelKey, agentType)

    // ── Special case: MobileActions-270M ─────────────────────────────────────────────
    // This model is fine-tuned ONLY on its tiny prompt + the MobileActionsTools surface. The
    // verbose V2 specialist prompt (role line + per-group tool descriptions) derails its
    // function-calling head and the model just emits prose. When the resolved specialist is
    // that model, replace the prompt + tools with the fine-tuned ones — regardless of which
    // ToolGroup checkboxes are configured for the slot.
    //
    // CRUCIAL: MobileActionsTools' @Tool methods only emit Action events; they don't toggle
    // the hardware. The chat screen subscribes to [MobileActionAgentAction] and calls
    // mobileActionsViewModel.performAction(...), which physically flips the flashlight, opens
    // settings, etc. So we must wire onFunctionCalled through agentTools.sendAction — exactly
    // like the planner-as-MobileActions path in OrchestratorV2TaskModule. Without this the
    // specialist appears to "succeed" but nothing happens on the device.
    if (modelKey?.contains("MobileActions", ignoreCase = true) == true) {
      val mobileActionsPrompt =
        com.google.ai.edge.gallery.customtasks.mobileactions.getSystemPrompt()
      // Track the actions emitted during THIS dispatch so we can return a clean, deterministic
      // result string to the planner. The fine-tuned MobileActions-270M's TEXT output is often
      // garbage tokens (e.g. "holders_holders_function_text..."), even when the function calls
      // themselves are correct. Feeding that back to the planner makes small planner models
      // loop calling dispatchToAgent until they hit the engine's recurring-tool-call cap.
      val capturedActions = mutableListOf<String>()
      val mobileActionsTool = tool(
        com.google.ai.edge.gallery.customtasks.mobileactions.MobileActionsTools(
          onFunctionCalled = { mobileAction ->
            val actionName = mobileAction::class.simpleName ?: mobileAction.toString().take(40)
            capturedActions.add(actionName)
            OrchestratorStatus.addLog("mobile_actions", mobileAction.toString().take(120))
            agentTools.sendAction(
              com.google.ai.edge.gallery.customtasks.agentchat.MobileActionAgentAction(mobileAction)
            )
            onActionTaken(
              DispatchAction(
                agentType = agentType,
                request = mobileAction.toString().take(120),
                result = "(mobile action emitted)",
              )
            )
          }
        )
      )
      postProcess = { _ ->
        if (capturedActions.isEmpty()) "No mobile action was performed."
        else "Performed: ${capturedActions.joinToString(", ")}."
      }
      return Triple(mobileActionsPrompt, listOf(mobileActionsTool), postProcess)
    }

    // Configure the shared AgentTools so it can resolve skill URLs (cheap; idempotent).
    agentTools.context = context
    agentTools.skillManagerViewModel = skillManagerViewModel

    // Lazily construct each ToolSet only when its group is actually selected.
    val providers = mutableListOf<com.google.ai.edge.litertlm.ToolProvider>()
    val toolDescriptions = mutableListOf<String>()
    for (group in groups) {
      when (group) {
        ToolGroup.MOBILE_AGENT -> {
          providers.add(tool(MobileAgentTools(context = context, onActionTaken = onActionTaken)))
          toolDescriptions.add(
            "- Mobile agent: turnOnFlashlight, turnOffFlashlight, flashMorseCode, createContact, " +
              "sendEmail, sendSms, openMap, openWifiSettings, createCalendarEvent. For repeated " +
              "flashlight patterns (SOS / Morse), call flashMorseCode(text, unitMs) instead of " +
              "toggling in a loop."
          )
        }
        ToolGroup.APP_LAUNCHER -> {
          providers.add(
            tool(AppLauncherTools(context = context, onActionTaken = onActionTaken))
          )
          toolDescriptions.add(
            "- App launcher: listInstalledApps, launchApp, sendIntent."
          )
        }
        ToolGroup.WORKSPACE -> {
          val ws = WorkspaceTools(
            context = context,
            workspaceUriProvider = workspaceUri,
            onFileRead = { path -> onActionTaken(WorkspaceReadAction(path)) },
            onFileWritten = { path -> onActionTaken(WorkspaceWriteAction(path)) },
          )
          providers.add(tool(ws))
          toolDescriptions.add(
            "- Workspace files: listFiles, readFile, writeFile, createDirectory, deleteFile " +
              "(workspace root: ${ws.getWorkspacePath()})."
          )
        }
        ToolGroup.SKILL_CREATOR -> {
          providers.add(
            tool(
              SkillCreatorTools(
                context = context,
                skillManagerViewModel = skillManagerViewModel,
                onSkillCreated = { name -> onActionTaken(SkillCreatedAction(skillName = name)) },
              )
            )
          )
          toolDescriptions.add(
            "- Skill creator: createTextSkill, createJsSkill, listAvailableSkills. For JS skills, " +
              "generate complete working index.html content."
          )
        }
        ToolGroup.SKILL_EXEC -> {
          val availableSkills = skillManagerViewModel.getSelectedSkillsNamesAndDescriptions()
            .ifEmpty { "(none installed)" }
          providers.add(tool(agentTools))
          toolDescriptions.add(
            "- Skill execution: loadSkill(skillName), runJs(skillName, scriptName, data). " +
              "Available skills:\n$availableSkills"
          )
        }
        ToolGroup.MOBILE_ACTIONS -> {
          providers.add(
            tool(
              com.google.ai.edge.gallery.customtasks.mobileactions.MobileActionsTools(
                onFunctionCalled = { mobileAction ->
                  onActionTaken(
                    DispatchAction(
                      agentType = agentType,
                      request = mobileAction.toString().take(120),
                      result = "(mobile action emitted)",
                    )
                  )
                }
              )
            )
          )
          toolDescriptions.add(
            "- Mobile actions: turnOnFlashlight, turnOffFlashlight, createContact, sendEmail, " +
              "sendSms, openMap, openWifiSettings, createCalendarEvent."
          )
        }
        ToolGroup.DISPATCH -> {
          // Specialists never get the planner's dispatch tool — guarded by validFor() above
          // but defended in depth here so a misconfigured prefs file can't recurse forever.
        }
      }
    }

    val role = when (agentType) {
      AgentType.MOBILE_AGENT -> "You are a mobile device control agent."
      AgentType.APP_LAUNCHER -> "You are an app launcher agent."
      AgentType.WORKSPACE_AGENT -> "You are a workspace file agent."
      AgentType.SKILL_CREATOR -> "You are a skill creator agent."
      AgentType.SKILL_AGENT -> "You are a skill execution agent."
    }

    val prompt = Contents.of(
      listOf(
        Content.Text("$role $baseCtx"),
        Content.Text(
          "Use the tools below when they apply. If NONE of the available tools fits the " +
            "request (e.g. the user asks for arithmetic, a definition, a translation, or any " +
            "other reasoning that doesn't require an external action), answer directly from " +
            "your own knowledge in one short sentence — do NOT refuse and do NOT mention the " +
            "tool list. Otherwise, perform the action and return a brief summary of what was " +
            "done.\n\n" + toolDescriptions.joinToString("\n")
        ),
      )
    )
    return Triple(prompt, providers, postProcess)
  }
}
