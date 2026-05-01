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

  @Tool(
    description =
      "Returns the list of available specialist models in the pool, including their names " +
        "and recommended use-cases. Call this before dispatching to pick the best model."
  )
  fun listSpecialistModels(): List<String> = agentModelPool.specialistRoster()

  @Tool(
    description =
      "Dispatches a sub-task to a specialized agent and returns the agent's response. " +
        "Valid agent types: 'mobile_agent' (flashlight, contacts, calendar, email, map, WiFi), " +
        "'app_launcher' (list/launch apps, send intents), " +
        "'workspace_agent' (read/write files in the workspace folder), " +
        "'skill_creator' (generate and import new skills), " +
        "'skill_agent' (execute installed skills like query-wikipedia, qr-code, calculate-hash via JavaScript). " +
        "Use modelName to select the best specialist from the pool (use listSpecialistModels to discover options). " +
        "Always use this tool rather than attempting to perform device actions directly."
  )
  fun dispatchToAgent(
    @ToolParam(
      description = "Agent to use: 'mobile_agent', 'app_launcher', 'workspace_agent', or 'skill_creator'."
    )
    agentType: String,
    @ToolParam(
      description =
        "Detailed description of the task for the agent. Include all relevant parameters."
    )
    request: String,
    @ToolParam(
      description =
        "Name of the specialist model to use (from listSpecialistModels). " +
          "Leave empty to let the system pick the default specialist."
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

    val (systemInstruction, tools) = buildSpecialistConfig(type)
    val result =
      agentModelPool.dispatchBlocking(
        request = request,
        preferredModelName = modelName,
        systemInstruction = systemInstruction,
        tools = tools,
      )
    onActionTaken(DispatchAction(agentType = type, request = request, result = result))
    return mapOf("agent" to type.displayName, "model" to modelName, "result" to result)
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private fun buildSpecialistConfig(
    agentType: AgentType
  ): Pair<Contents, List<com.google.ai.edge.litertlm.ToolProvider>> {
    val now = LocalDateTime.now()
    val dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    val dayOfWeek = now.format(DateTimeFormatter.ofPattern("EEEE"))
    val baseCtx = "Current date/time: $dateTime ($dayOfWeek)."

    return when (agentType) {
      AgentType.MOBILE_AGENT -> {
        val mobileTools = MobileAgentTools(context = context, onActionTaken = onActionTaken)
        val prompt =
          Contents.of(
            listOf(
              Content.Text("You are a mobile device control agent. $baseCtx"),
              Content.Text(
                "Perform the requested device action using the available tools. " +
                  "Return a brief confirmation of what was done."
              ),
            )
          )
        Pair(prompt, listOf(tool(mobileTools)))
      }

      AgentType.APP_LAUNCHER -> {
        val launcherTools =
          AppLauncherTools(context = context, onActionTaken = onActionTaken)
        val prompt =
          Contents.of(
            listOf(
              Content.Text("You are an app launcher agent. $baseCtx"),
              Content.Text(
                "List installed apps, launch apps, or send structured data to apps using the tools. " +
                  "Return a brief confirmation of the action taken."
              ),
            )
          )
        Pair(prompt, listOf(tool(launcherTools)))
      }

      AgentType.WORKSPACE_AGENT -> {
        val ws = WorkspaceTools(
          context = context,
          workspaceUri = workspaceUri(),
          onActionTaken = onActionTaken,
        )
        val prompt =
          Contents.of(
            listOf(
              Content.Text(
                "You are a workspace file agent. $baseCtx " +
                  "Workspace root: ${ws.getWorkspacePath()}."
              ),
              Content.Text(
                "Manage files in the workspace using the available tools. " +
                  "For write operations, prefer creating files in appropriate subdirectories. " +
                  "Return a brief summary of all actions taken."
              ),
            )
          )
        Pair(prompt, listOf(tool(ws)))
      }

      AgentType.SKILL_CREATOR -> {
        val skillCreator =
          SkillCreatorTools(
            context = context,
            skillManagerViewModel = skillManagerViewModel,
            onSkillCreated = { name ->
              onActionTaken(SkillCreatedAction(skillName = name))
            },
          )
        val prompt =
          Contents.of(
            listOf(
              Content.Text("You are a skill creator agent. $baseCtx"),
              Content.Text(
                "Create new skills and import them into the skill library using the available tools. " +
                  "For JS skills, generate complete, working index.html content. " +
                  "Return the name of the created skill and a brief description."
              ),
            )
          )
        Pair(prompt, listOf(tool(skillCreator)))
      }

      AgentType.SKILL_AGENT -> {
        // Configure the shared AgentTools so it can resolve skill URLs.
        agentTools.context = context
        agentTools.skillManagerViewModel = skillManagerViewModel

        val availableSkills = skillManagerViewModel.getSelectedSkillsNamesAndDescriptions()
          .ifEmpty { "(none installed)" }
        val prompt =
          Contents.of(
            listOf(
              Content.Text("You are a skill execution agent. $baseCtx"),
              Content.Text(
                "You can execute installed skills using the available tools:\n" +
                  "- loadSkill(skillName): loads a skill and returns its instructions.\n" +
                  "- runJs(skillName, scriptName, data): executes a skill's JavaScript and returns the result.\n" +
                  "\nAvailable skills:\n$availableSkills\n" +
                  "\nFor query-wikipedia, call: loadSkill(\"query-wikipedia\") then runJs(\"query-wikipedia\", \"index.html\", <json-data>).\n" +
                  "For all skills the scriptName is typically \"index.html\". Always follow the instructions returned by loadSkill for the correct data format."
              ),
            )
          )
        Pair(prompt, listOf(tool(agentTools)))
      }
    }
  }
}
