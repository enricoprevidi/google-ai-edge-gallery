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
        You are an AI assistant on an Android phone. You complete user tasks by calling tools directly. Output ONLY the final result, no thoughts or intermediate text.

        Choose ONE branch per user message and follow it exactly:

        BRANCH A — SKILL CREATION
        Trigger: user asks to create / add / save / build a new skill.
          - createTextSkill(name, skillMd) for persona / role-play / knowledge skills.
          - createJsSkill(name, skillMd, indexHtmlContent) for JavaScript skills.
          - After success, output ONLY: "Skill '<name>' created."

        BRANCH B — WORKSPACE FILES
        Trigger: user asks to list, read, write, create, or delete files in the workspace.
          - listFiles("") for the workspace root, or listFiles("sub/dir").
          - readFile(path), writeFile(path, content), createDirectory(path), deleteFile(path).
          - If a tool returns "Workspace not set", output: "Please pick a workspace folder via the Workspace button."
          - Otherwise output a one-line confirmation (or the file content for reads).

        BRANCH C — MOBILE / DEVICE ACTIONS
        Trigger: user asks to control the device (flashlight, contacts, email, SMS, calendar, maps, WiFi).
          - Call exactly ONE matching tool: turnOnFlashlight, turnOffFlashlight, flashMorseCode, createContact, sendEmail, sendSms, openMap, openWifiSettings, createCalendarEvent.
          - Output a one-sentence confirmation.

        BRANCH D — EXECUTE A SKILL
        Trigger: anything else.
          1. Pick the most relevant skill from this list:
             ___SKILLS___
          2. Call load_skill(name) and follow its instructions.
          3. If runJs is needed, call runJs(skillName, scriptName, data).
          4. Output ONLY the final result.

        BRANCH E — DELEGATE TO ANOTHER MODEL (OPTIONAL)
        Use ONLY when an independent specialist model exists in the pool AND it is clearly better suited.
          - dispatchToAgent(agentType, request, modelName="").
          - As soon as it returns "status":"completed", stop calling tools and reply with a short plain-text summary.
        If you are unsure, prefer Branch A-D direct tools.
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
            agentTools.sendAction(
              SkillProgressAgentAction(label = "Read file \"$path\"", inProgress = false)
            )
          },
          onFileWritten = { path ->
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

      // Build the planner's tool surface. Direct toolsets (V2 parity) are always included so the
      // planner can act on workspace / mobile / skills without an LLM hop. dispatchToAgent is
      // additionally exposed only when there is at least one INDEPENDENT specialist model in the
      // pool — otherwise the planner has nothing to dispatch to and including the tool would
      // just confuse small models.
      val poolHasSpecialists = specialistEntries.isNotEmpty()
      val plannerToolList: List<com.google.ai.edge.litertlm.ToolProvider> =
        if (isMobileActionsModel) {
          // MobileActions-270M is fine-tuned for a tiny tool surface; don't pollute it.
          listOf(tool(mobileActionsTools))
        } else {
          val core = mutableListOf(
            tool(workspaceTools),
            tool(skillCreatorTools),
            tool(mobileActionsTools),
            tool(agentTools), // skill execution: load_skill, run_js, ...
          )
          if (poolHasSpecialists) core.add(tool(plannerTools))
          core
        }

      val plannerToolNames =
        if (isMobileActionsModel) {
          listOf("MobileActions: turnOnFlashlight, turnOffFlashlight, ...")
        } else {
          val names = mutableListOf(
            "workspace: listFiles, readFile, writeFile, createDirectory, deleteFile",
            "skill_creator: createTextSkill, createJsSkill, listAvailableSkills",
            "mobile_actions: turnOnFlashlight, turnOffFlashlight, createContact, sendEmail, ...",
            "skill_exec: load_skill, run_js",
          )
          if (poolHasSpecialists) names.add("dispatchToAgent (delegate to specialist model)")
          names
        }
      val specialistToolNames =
        listOf(
          "mobile_agent: turnOnFlashlight, turnOffFlashlight, flashMorseCode, createContact, sendEmail, sendSms, openMap, ...",
          "app_launcher: listInstalledApps, launchApp, sendIntent",
          "workspace_agent: listFiles, readFile, writeFile, createDirectory, deleteFile",
          "skill_creator: createTextSkill, createJsSkill, listAvailableSkills",
          "skill_agent: loadSkill, runJs",
        )
      val modelEntries = mutableListOf(
        OrchestratorStatus.ModelEntry(
          name = model.name,
          role = "planner",
          sharedWithPlanner = false,
          tools = plannerToolNames,
        )
      )
      for (entry in specialistEntries.values) {
        modelEntries.add(
          OrchestratorStatus.ModelEntry(
            name = entry.model.name.removeSuffix("-specialist"),
            role = "specialist",
            sharedWithPlanner = false,
            tools = specialistToolNames,
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
