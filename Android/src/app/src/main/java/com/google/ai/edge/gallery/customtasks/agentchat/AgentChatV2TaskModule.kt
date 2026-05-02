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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.customtasks.mobileactions.MobileActionsTools
import com.google.ai.edge.gallery.customtasks.orchestrator.SkillCreatorTools
import com.google.ai.edge.gallery.customtasks.orchestrator.WorkspaceTools
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * Exact copy of [AgentChatTask] with a separate task ID.
 *
 * Purpose: start from identical Agent Skills behaviour, then incrementally add multi-agent
 * features so each step can be tested independently.
 */
class AgentChatV2Task @Inject constructor() : CustomTask {
  private val agentTools = AgentTools()

  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_AGENT_CHAT_V2,
      label = "Multi-Agent Skills",
      category = Category.LLM,
      iconVectorResourceId = R.drawable.agent,
      newFeature = true,
      models = mutableListOf(),
      description = "Incremental multi-agent build on top of Agent Skills",
      shortDescription = "Agent Skills base for multi-agent development",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt =
        """
        You are an AI assistant that helps users by answering questions and completes tasks using skills.

        CRITICAL RULE: You MUST execute all steps silently. Do NOT output any internal thoughts, reasoning, or intermediate text at ANY step. Output ONLY the final result.

        ── STEP 1: DECIDE WHAT TO DO ──────────────────────────────────────────────────────
        Read the user message. Choose EXACTLY ONE branch below and follow it completely.

        BRANCH A — SKILL CREATION
        Trigger: the user explicitly asks to create, add, teach, save, or build a new skill.
        Action: call the appropriate tool immediately (no skill lookup first).
          • Use `createTextSkill(name, skillMd)` for persona, role-play, or knowledge-injection skills.
            - `name`: kebab-case (e.g. "pirate-coach")
            - `skillMd`: the COMPLETE SKILL.md file as a single string, including frontmatter. Example:
              "---\nname: pirate-coach\ndescription: Responds in pirate speak.\n---\n\nWhen the user asks anything, respond entirely in pirate speak."
          • Use `createJsSkill(name, skillMd, indexHtmlContent)` for skills requiring JavaScript.
            - `skillMd`: same format as above, body should instruct the LLM to call run_js.
            - `indexHtmlContent`: full HTML file content. MUST define: window['ai_edge_gallery_get_result'] = async (data) => { ... }
          • After the tool returns successfully, output ONLY: "Skill '<name>' has been created and is ready to use."
          • If the tool returns an error, output ONLY the error message.

        BRANCH B — SKILL MANAGEMENT
        Trigger: the user asks to list, show, or delete skills.
          • To list skills: call `listAvailableSkills` and output the result as a plain list.
          • To delete a skill: call `deleteCreatedSkill` with the exact skill name and confirm.

        BRANCH C — WORKSPACE FILE OPERATIONS
        Trigger: the user asks to read, write, list, create, or delete files in their workspace,
        or otherwise references the workspace folder.
          • Use `listFiles(relativePath)` to see directory contents (use "" for the root).
          • Use `readFile(relativePath)` to read text content.
          • Use `writeFile(relativePath, content)` to create or overwrite a text file.
          • Use `createDirectory(relativePath)` for new folders.
          • Use `deleteFile(relativePath)` to remove a file or empty directory.
          • All paths are relative to the workspace root. Never use absolute paths or `..`.
          • If a tool returns "Workspace not set", instruct the user to choose a workspace folder
            via the Multi-Agent Orchestrator screen, then output ONLY that instruction.
          • Output ONLY a brief confirmation of what was done (or the file content for reads).

        BRANCH D — MOBILE / DEVICE ACTIONS
        Trigger: the user asks to control the device or perform a phone action — e.g. turn the
        flashlight on/off, create a contact, send an email, show a place on the map, open WiFi
        settings, or create a calendar event.
          • Call EXACTLY ONE of the mobile-action tools that matches the request:
            `turnOnFlashlight`, `turnOffFlashlight`, `createContact`, `sendEmail`,
            `showLocationOnMap`, `openWifiSettings`, `createCalendarEvent`.
          • Pass the parameters extracted from the user message verbatim (no skill lookup first).
          • After the tool returns, output ONLY a one-sentence confirmation of the action taken.

        BRANCH E — EXECUTE A SKILL
        Trigger: anything else (a task, question, or action the user wants performed).
        Steps (execute in order, silently):
          1. Find the most relevant skill from the list below:
             ___SKILLS___
          2. If a relevant skill exists, call `load_skill` to read its instructions.
          3. Follow the skill's instructions exactly to complete the task.
             Output ONLY the final result: one-sentence summary + result. No intermediate text.
          4. If no relevant skill exists, answer the question directly using your own knowledge.
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
      // SkillCreatorTools shares the same SkillManagerViewModel as AgentTools so newly created
      // skills become immediately available to load_skill / run_js in the same conversation.
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
        )

      // WorkspaceTools reads the SAF folder URI persisted by OrchestratorViewModel so the user
      // only has to grant access once (via the Multi-Agent Orchestrator screen or the workspace
      // selector inside this screen). The lambda is called on every tool invocation, so changing
      // the workspace from the UI takes effect immediately without resetting the session.
      val workspacePrefs =
        context.getSharedPreferences("orchestrator_prefs", Context.MODE_PRIVATE)
      val workspaceTools =
        WorkspaceTools(
          context = context,
          workspaceUriProvider = {
            workspacePrefs.getString("workspace_uri", null)?.takeIf { it.isNotEmpty() }
          },
          onFileRead = { path ->
            agentTools.sendAction(
              SkillProgressAgentAction(
                label = "Read file \"$path\"",
                inProgress = false,
              )
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

      // MobileActionsTools lets the model trigger device-side intents (flashlight, contacts,
      // email, map, WiFi settings, calendar). The actual Android intent is dispatched on the UI
      // thread by AgentChatScreen when it observes the resulting MobileActionAgentAction.
      val mobileActionsTools =
        MobileActionsTools(
          onFunctionCalled = { mobileAction ->
            agentTools.sendAction(MobileActionAgentAction(mobileAction))
          }
        )

      LlmChatModelHelper.initialize(
        context = context,
        model = model,
        supportImage = true,
        supportAudio = true,
        onDone = onDone,
        systemInstruction =
          if (agentTools.skillManagerViewModel.getSelectedSkills().isEmpty()) {
            null
          } else {
            agentTools.skillManagerViewModel.getSystemPrompt(task.defaultSystemPrompt)
          },
        tools =
          listOf(
            tool(agentTools),
            tool(skillCreatorTools),
            tool(workspaceTools),
            tool(mobileActionsTools),
          ),
        enableConversationConstrainedDecoding = true,
      )
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AgentChatScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
      taskId = BuiltInTaskId.LLM_AGENT_CHAT_V2,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AgentChatV2TaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return AgentChatV2Task()
  }
}
