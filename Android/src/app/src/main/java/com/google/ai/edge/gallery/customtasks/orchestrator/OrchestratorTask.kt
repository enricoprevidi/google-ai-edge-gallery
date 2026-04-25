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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Multi-agent orchestrator task.
 *
 * Loads two models concurrently:
 *  - **Planner model** (user-selected) — drives the conversation and dispatches sub-tasks
 *  - **Specialist model** (same weights, separate Engine) — executes each sub-task using
 *    specialist ToolSets (MobileAgent, AppLauncher, WorkspaceAgent, SkillCreator)
 *
 * Between specialist calls, the shared engine is reset via [LlmChatModelHelper.resetConversation]
 * (~50 ms), keeping GPU weights warm and avoiding full model reloads.
 */
class OrchestratorTask @Inject constructor() : CustomTask {

  // Set from OrchestratorScreen before any inference runs (follows AgentChatTask pattern).
  lateinit var skillManagerViewModel: SkillManagerViewModel

  // Observable action list consumed by the screen for the dispatch trace UI.
  val curActions = mutableStateListOf<OrchestratorAction>()

  // Managed at runtime; populated during initializeModelFn.
  private var agentModelPool: AgentModelPool? = null
  private var plannerTools: PlannerTools? = null
  private var lastPlannerSystemPrompt: Contents? = null
  private var lastPlannerTools: List<com.google.ai.edge.litertlm.ToolProvider> = emptyList()

  override val task =
    Task(
      id = BuiltInTaskId.LLM_ORCHESTRATOR,
      label = "Multi-Agent Orchestrator",
      description =
        "A planner model that delegates tasks to specialist agents: " +
          "Mobile Actions, App Launcher, Workspace File Agent, and Skill Creator.",
      shortDescription = "Orchestrate multiple AI agents",
      category = Category.LLM,
      icon = Icons.Outlined.Hub,
      agentNameRes = R.string.chat_agent_agent_name,
      models = mutableListOf(),
      experimental = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    curActions.clear()

    // ── Build specialist pool ──────────────────────────────────────────────
    // All models in the task that are already downloaded serve as potential specialists.
    // If a candidate shares the same weights file as the planner we mark it as shared so
    // no second Engine is allocated (memory-safe deduplication).
    val plannerPath = model.getPath(context)

    // Gather all task models that have been downloaded (totalBytes > 0 as proxy).
    // The planner model is always included; other downloaded models extend the pool.
    val candidateModels: List<Model> = task.models
      .filter { it.name != model.name && it.totalBytes > 0L }
      .ifEmpty {
        // No additional models — fall back to a single specialist sharing the planner weights.
        emptyList()
      }

    // Build the specialist pool. Every specialist gets its own Engine instance via model.copy(),
    // so the planner's multi-turn conversation is never corrupted during specialist dispatches.
    val specialistEntries = mutableMapOf<String, SpecialistEntry>()

    // Include the planner's weights as a fallback specialist (same file, separate Engine).
    specialistEntries[model.name] = SpecialistEntry(
      model = model.copy(
        name = "${model.name}-specialist",
        instance = null,
        initializing = false,
        cleanUpAfterInit = false,
        localModelFilePathOverride = plannerPath,
      ),
      sharedWithPlanner = true, // same file bytes, separate Engine — shown in roster
    )

    // Add independent specialist entries for additional downloaded models.
    for (candidate in candidateModels) {
      val candidatePath = candidate.getPath(context)
      val shared = (candidatePath == plannerPath)
      // Always create a separate Engine instance, even for same-file models.
      // Using a unique name + localModelFilePathOverride causes LlmChatModelHelper to allocate
      // a new Engine pointing at the same file bytes — the planner Engine is never reused during
      // dispatch, so the planner's multi-turn history is preserved across all specialist calls.
      val specialistModel = candidate.copy(
        name = "${candidate.name}-specialist",
        instance = null,
        initializing = false,
        cleanUpAfterInit = false,
        localModelFilePathOverride = candidatePath,
      )
      specialistEntries[candidate.name] = SpecialistEntry(
        model = specialistModel,
        sharedWithPlanner = shared, // informational only — shown in the roster
      )
    }

    val pool = AgentModelPool(plannerModel = model, specialists = specialistEntries)
    agentModelPool = pool

    // Load skills before initializing so the planner system prompt includes the skills list.
    skillManagerViewModel.loadSkills {
      val workspaceUri: () -> String? = {
        context.getSharedPreferences("orchestrator_prefs", Context.MODE_PRIVATE)
          .getString("workspace_uri", null)
      }

      val tools =
        PlannerTools(
          context = context,
          agentModelPool = pool,
          skillManagerViewModel = skillManagerViewModel,
          workspaceUri = workspaceUri,
          onActionTaken = { curActions.add(it) },
        )
      plannerTools = tools

      val systemPrompt = buildPlannerSystemPrompt(skillManagerViewModel, pool)
      lastPlannerSystemPrompt = systemPrompt
      val toolProviders = listOf(tool(tools))
      lastPlannerTools = toolProviders

      pool.initializeAll(
        context = context,
        plannerSystemPrompt = systemPrompt,
        plannerTools = toolProviders,
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
    curActions.clear()
    val pool = agentModelPool
    if (pool != null) {
      pool.cleanUpAll()
      agentModelPool = null
    } else {
      // Fallback: clean up planner only.
      LlmChatModelHelper.cleanUp(model = model, onDone = {})
    }
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    OrchestratorScreen(
      task = task,
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      orchestratorTask = this,
    )
  }

  /** Returns the current planner system prompt and tools (needed for conversation reset). */
  fun getPlannerConfig(): Pair<Contents?, List<com.google.ai.edge.litertlm.ToolProvider>> =
    Pair(lastPlannerSystemPrompt, lastPlannerTools)

  // ── helpers ──────────────────────────────────────────────────────────────

  private fun buildPlannerSystemPrompt(
    skillManagerViewModel: SkillManagerViewModel,
    pool: AgentModelPool,
  ): Contents {
    @Suppress("JavaTimeDefaultTimeZone")
    val now = LocalDateTime.now()
    val dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    val dayOfWeek = now.format(DateTimeFormatter.ofPattern("EEEE"))

    val skillsList = skillManagerViewModel.getSelectedSkillsNamesAndDescriptions()
      .ifEmpty { "(none installed)" }

    val rosterLines = pool.specialistRoster().joinToString("\n")

    val promptText = """
You are a multi-agent orchestrator. Current time: $dateTime ($dayOfWeek).

You have access to these specialist agents via the dispatchToAgent tool:
- mobile_agent: Device control (flashlight, contacts, calendar, email, SMS, map, WiFi settings).
- app_launcher: List, launch, or send structured data to installed apps.
- workspace_agent: Create, read, write, list, delete files in the user's workspace folder.
- skill_creator: Generate and immediately import new text or JavaScript skills.

Available specialist models (pass the exact name as modelName in dispatchToAgent):
$rosterLines

Model selection guidance:
- Prefer lightweight models (e.g. containing "270M", "1B") for simple tool-calling tasks like mobile_agent or app_launcher.
- Prefer larger models (e.g. "4B", "Gemma-4") for complex reasoning, workspace_agent, or skill_creator tasks.
- If a model name contains "multimodal" or "vision", prefer it for tasks involving image understanding.
- If unsure, call listSpecialistModels() first to inspect the current pool.
- The planner model itself is always available as a fallback specialist (marked [shared with planner]).

Available skills:
$skillsList

Rules:
1. For EVERY request that requires a device or file action, use dispatchToAgent. Never attempt actions directly.
2. Choose the most appropriate specialist model for each sub-task based on the guidance above.
3. If a request spans multiple agents, dispatch them sequentially and aggregate the results.
4. After each dispatch, interpret the result and continue until the full request is satisfied.
5. Reply clearly and concisely, summarising what was done.
    """.trimIndent()

    return Contents.of(promptText)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object OrchestratorModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask = OrchestratorTask()
}
