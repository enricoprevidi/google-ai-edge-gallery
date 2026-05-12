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
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.customtasks.agentchat.AgentChatScreen
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.MobileActionAgentAction
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.customtasks.mobileactions.MobileActionsTools
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGOrchestratorV2"

/**
 * Multi-Agent Orchestrator V2.
 *
 * Cloned from [com.google.ai.edge.gallery.customtasks.agentchat.AgentChatV2Task] (Multi-Agent
 * Skills) so the same V2 tool surface — Skill Creator, Workspace, Mobile Actions, AgentTools —
 * works out of the box with the same UI ([AgentChatScreen]).
 *
 * On top of the V2 base, the planner is also given the orchestrator's [PlannerTools]:
 *  - `listSpecialistModels()` — discover the pool of available specialist models.
 *  - `dispatchToAgent(agentType, request, modelName)` — delegate sub-tasks to a focused specialist
 *    model running on its own engine, with a domain-specific system prompt + tools.
 *
 * The planner can therefore EITHER act directly using its own toolset (fastest path for simple
 * requests) OR dispatch to a smaller specialist model (best when the sub-task is bounded and a
 * lightweight tool-calling model is sufficient — e.g. flashlight, contacts, calendar). This is
 * what enables truly compound workflows like "send SOS in Morse with the flashlight, then write
 * an entry to incidents/log.json" without overloading a single conversation.
 */
class OrchestratorV2Task @Inject constructor() : CustomTask {
  /** Shared AgentTools instance — owns the action channel consumed by the screen. */
  private val agentTools = AgentTools()

  // Lazily-allocated pool of specialist engines (planner + downloaded task models).
  private var agentModelPool: AgentModelPool? = null

  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_ORCHESTRATOR_V2,
      label = "Multi-Agent Orchestrator V2",
      category = Category.LLM,
      iconVectorResourceId = R.drawable.agent,
      newFeature = true,
      models = mutableListOf(),
      description =
        "Multi-Agent Skills enriched with orchestrator dispatch: the planner can act directly " +
          "or delegate sub-tasks to specialist models (mobile_agent, app_launcher, " +
          "workspace_agent, skill_creator, skill_agent).",
      shortDescription = "Multi-Agent Skills + planner dispatch",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt =
        """
        You are a PLANNER on an Android phone. Your job is to DECOMPOSE the user's request into a SEQUENCE of small steps and execute them in order by calling tools. Output ONLY the final user-facing reply, no thoughts.

        ── HOW TO PLAN ─────────────────────────────────────────────────────────────
        1. Read the full user message. If it contains MULTIPLE actions joined by "then", "and", commas, or numbered steps, treat each as a SEPARATE step.
        2. For EACH step, classify it independently using the categories below and execute it with ONE tool call (or answer it directly from your own knowledge if no tool applies).
        3. Execute the steps STRICTLY in the order the user wrote them. Wait for each tool's result before moving on.
        4. NEVER pass a compound request (multiple actions) into a single tool call or dispatchToAgent. The specialists are narrow and will fail.
        5. After the last step, output ONE short plain-text summary covering every step's outcome. Then STOP.

        ── STEP CATEGORIES ─────────────────────────────────────────────────────────
        Mobile / device action (flashlight, contacts, email, SMS, calendar, maps, WiFi, Morse):
          → call turnOnFlashlight / turnOffFlashlight / flashMorseCode / createContact / sendEmail / sendSms / openMap / openWifiSettings / createCalendarEvent directly,
            OR dispatchToAgent("mobile_agent", "<just this one action>", "") if a specialist is available and you cannot call the tool yourself.

        Workspace file (list / read / write / create / delete):
          → listFiles / readFile / writeFile / createDirectory / deleteFile,
            OR dispatchToAgent("workspace_agent", "<just this one file action>", "").

        Skill creation (user wants to create / add / save a new skill):
          → createTextSkill / createJsSkill.

        Skill execution (anything else that matches an available skill):
          → load_skill(name) then runJs(skillName, scriptName, data) if needed.
            Available skills: ___SKILLS___
            OR dispatchToAgent("skill_agent", "<one skill task>", "") / dispatchToAgent("skill_creator", "<one creation task>", "").

        Reasoning / arithmetic / definition / translation / counting / general knowledge:
          → DO NOT call any tool. DO NOT dispatch. Just compute or recall the answer yourself and remember it for the final summary.

        ── DISPATCH RULES ──────────────────────────────────────────────────────────
        - dispatchToAgent(agentType, request, modelName="") delegates ONE narrow sub-task to a specialist.
          The runtime picks the model; always pass modelName="".
          The "request" string must describe ONE action only — never include "then", "and", or multiple instructions.
        - As soon as dispatchToAgent returns "status":"completed", continue with the NEXT user step (or finish if this was the last one). Do not re-dispatch the same step.

        ── EXAMPLE ─────────────────────────────────────────────────────────────────
        User: "Turn on the flashlight, then count from 1 to 5, then calculate 3+19, then turn the flashlight off."
        Plan:
          Step 1 (mobile)    → turnOnFlashlight() OR dispatchToAgent("mobile_agent", "Turn on the flashlight", "")
          Step 2 (reasoning) → answer "1, 2, 3, 4, 5" mentally; no tool call.
          Step 3 (reasoning) → answer "22" mentally; no tool call.
          Step 4 (mobile)    → turnOffFlashlight() OR dispatchToAgent("mobile_agent", "Turn off the flashlight", "")
          Final reply: "Flashlight toggled on and off. Count: 1, 2, 3, 4, 5. 3+19 = 22."
        """
          .trimIndent(),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    agentTools.skillManagerViewModel.loadSkills {
      // Wire the shared AgentTools so SKILL_AGENT dispatches and direct skill execution both
      // route through the same WebView / JS bridge attached by AgentChatScreen.
      agentTools.context = context

      val workspacePrefs =
        context.getSharedPreferences("orchestrator_prefs", Context.MODE_PRIVATE)
      val workspaceUriProvider: () -> String? = {
        workspacePrefs.getString("workspace_uri", null)?.takeIf { it.isNotEmpty() }
      }

      // ── Direct toolset (V2 parity) ─────────────────────────────────────────────────
      val skillCreatorTools =
        SkillCreatorTools(
          context = context,
          skillManagerViewModel = agentTools.skillManagerViewModel,
          onSkillCreated = { skillName ->
            OrchestratorStatus.addLog("skill_creator", "created skill: $skillName")
            agentTools.sendAction(
              SkillProgressAgentAction(
                label = "Created skill \"$skillName\"",
                inProgress = false,
                addItemTitle = "Created skill \"$skillName\"",
                addItemDescription = "The skill has been imported and selected.",
              )
            )
          },
          workspaceUriProvider = workspaceUriProvider,
        )

      val workspaceTools =
        WorkspaceTools(
          context = context,
          workspaceUriProvider = workspaceUriProvider,
          onFileRead = { path ->
            OrchestratorStatus.addLog("workspace", "readFile: $path")
            agentTools.sendAction(
              SkillProgressAgentAction(label = "Read file \"$path\"", inProgress = false)
            )
          },
          onFileWritten = { path ->
            OrchestratorStatus.addLog("workspace", "writeFile: $path")
            agentTools.sendAction(
              SkillProgressAgentAction(
                label = "Wrote file \"$path\"",
                inProgress = false,
                addItemTitle = "Wrote file \"$path\"",
                addItemDescription = "Saved to the workspace folder.",
              )
            )
          },
        )

      val mobileActionsTools =
        MobileActionsTools(
          onFunctionCalled = { mobileAction ->
            OrchestratorStatus.addLog("mobile_actions", mobileAction.toString().take(120))
            agentTools.sendAction(MobileActionAgentAction(mobileAction))
          }
        )

      // ── Specialist pool (orchestrator dispatch) ────────────────────────────────────
      // Optionally filter the candidate specialists by names persisted via the Specialists
      // bottom sheet in the chat top bar. Empty / unset = include all downloaded models.
      val selectedNamesCsv =
        workspacePrefs.getString("v2_specialist_names", "")?.takeIf { it.isNotEmpty() }
      val selectedSpecialistNames: Set<String> =
        selectedNamesCsv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
          ?: emptySet()

      // The planner uses its OWN engine and gets ALL the V2 tools directly (workspace, mobile,
      // skill creator, skill execution). The specialist pool only contains GENUINELY INDEPENDENT
      // models — different weight files than the planner. Trying to share the planner's engine
      // for a specialist conversation deadlocks: the engine is mid-generation when dispatchToAgent
      // is invoked, so it cannot serve a second conversation. Independent specialists are loaded
      // into their own engines and can be dispatched to without recursion.
      val plannerPath = model.getPath(context)
      val specialistEntries = mutableMapOf<String, SpecialistEntry>()
      for (candidate in task.models) {
        if (candidate.name == model.name || candidate.totalBytes <= 0L) continue
        if (selectedSpecialistNames.isNotEmpty() &&
          !selectedSpecialistNames.contains(candidate.name)
        ) {
          continue
        }
        val candidatePath = candidate.getPath(context)
        if (candidatePath == plannerPath) {
          // Same weights as planner — skip to avoid the shared-engine deadlock described above.
          Log.d(
            TAG,
            "Skipping specialist '${candidate.name}' — same weights as planner; would deadlock.",
          )
          continue
        }
        specialistEntries[candidate.name] =
          SpecialistEntry(
            model =
              candidate.copy(
                name = "${candidate.name}-specialist",
                instance = null,
                initializing = false,
                cleanUpAfterInit = false,
                localModelFilePathOverride = candidatePath,
              ),
            sharedWithPlanner = false,
          )
      }
      val pool = AgentModelPool(plannerModel = model, specialists = specialistEntries)
      agentModelPool = pool

      val plannerTools =
        PlannerTools(
          context = context,
          agentModelPool = pool,
          skillManagerViewModel = agentTools.skillManagerViewModel,
          agentTools = agentTools,
          workspaceUri = workspaceUriProvider,
          onActionTaken = { orchestratorAction ->
            // Surface dispatch / specialist actions in the chat progress panel.
            agentTools.sendAction(
              SkillProgressAgentAction(
                label = orchestratorAction.label,
                inProgress = false,
              )
            )
          },
        )

      // ── System prompt selection ────────────────────────────────────────────────────
      // The MobileActions-270M Function Gemma model is fine-tuned ONLY on its lightweight prompt
      // + mobile-action tools. Feeding it the planner prompt would derail it. When that model is
      // chosen as the planner, fall back to the V2 / MobileActions behaviour: tiny prompt, only
      // mobile-action tools, no dispatch.
      val isMobileActionsModel = model.name.contains("MobileActions", ignoreCase = true)
      val systemInstruction: Contents? =
        when {
          isMobileActionsModel ->
            com.google.ai.edge.gallery.customtasks.mobileactions.getSystemPrompt()
          else ->
            agentTools.skillManagerViewModel.getSystemPrompt(task.defaultSystemPrompt)
        }

      // ── Initialize planner + independent specialists concurrently ──────────────────
      Log.d(
        TAG,
        "Initializing OrchestratorV2 — planner=${model.name}, " +
          "specialists=${specialistEntries.keys}",
      )
      OrchestratorStatus.setPlanner(model.name)

      // Build the planner's tool surface. The set of available tool groups is built once from
      // the locally constructed ToolSet instances; the actual subset exposed to the planner is
      // driven by the user's [PlannerToolsConfig] (or the historical default). dispatchToAgent
      // is only meaningful when the pool has at least one INDEPENDENT specialist model — when
      // it doesn't, [ToolGroup.DISPATCH] is filtered out so small models aren't confused.
      val poolHasSpecialists = specialistEntries.isNotEmpty()
      val plannerGroupProviders: Map<ToolGroup, com.google.ai.edge.litertlm.ToolProvider> =
        mapOf(
          ToolGroup.WORKSPACE to tool(workspaceTools),
          ToolGroup.SKILL_CREATOR to tool(skillCreatorTools),
          ToolGroup.MOBILE_ACTIONS to tool(mobileActionsTools),
          ToolGroup.SKILL_EXEC to tool(agentTools), // load_skill, run_js, ...
          ToolGroup.DISPATCH to tool(plannerTools),
        )

      val plannerGroups: Set<ToolGroup> = when {
        // MobileActions-270M is fine-tuned for a tiny tool surface; don't pollute it regardless
        // of saved prefs.
        isMobileActionsModel -> setOf(ToolGroup.MOBILE_ACTIONS)
        else -> {
          val saved = OrchestratorToolsConfig.loadPlanner(context)
          val effective = (saved?.groups ?: PlannerToolsConfig.defaultFor(poolHasSpecialists).groups)
            .intersect(PlannerToolsConfig.VALID)
            .toMutableSet()
          if (!poolHasSpecialists) effective.remove(ToolGroup.DISPATCH)
          effective
        }
      }

      val plannerToolList: List<com.google.ai.edge.litertlm.ToolProvider> =
        plannerGroups.mapNotNull { plannerGroupProviders[it] }

      val plannerToolNames: List<String> = plannerGroups.map {
        "${it.displayName.lowercase().replace(' ', '_')}: ${it.description}"
      }
      val modelEntries = mutableListOf(
        OrchestratorStatus.ModelEntry(
          name = model.name,
          role = "planner",
          sharedWithPlanner = false,
          tools = plannerToolNames,
          sizeBytes = model.totalBytes,
        )
      )
      for (entry in specialistEntries.values) {
        val baseName = entry.model.name.removeSuffix("-specialist")
        val cfg = OrchestratorToolsConfig.loadSpecialist(context, baseName)
        val labels = AgentType.values().map { agentType ->
          val groups = cfg?.groupsFor(agentType) ?: SpecialistToolsConfig.defaultsFor(agentType)
          "${agentType.id}: " + groups.joinToString(", ") { it.displayName }
        }
        modelEntries.add(
          OrchestratorStatus.ModelEntry(
            name = baseName,
            role = "specialist",
            sharedWithPlanner = false,
            tools = labels,
            sizeBytes = entry.model.totalBytes,
          )
        )
      }
      OrchestratorStatus.setModels(modelEntries)

      pool.initializeAll(
        context = context,
        plannerSystemPrompt = systemInstruction,
        plannerTools = plannerToolList,
        onDone = onDone,
      )
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    val pool = agentModelPool
    if (pool != null) {
      pool.cleanUpAll()
      agentModelPool = null
      OrchestratorStatus.reset()
      onDone()
    } else {
      LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
    }
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AgentChatScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
      taskId = BuiltInTaskId.LLM_ORCHESTRATOR_V2,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object OrchestratorV2TaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return OrchestratorV2Task()
  }
}
