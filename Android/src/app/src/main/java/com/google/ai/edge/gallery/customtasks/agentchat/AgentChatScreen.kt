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
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.customtasks.mobileactions.MobileActionsViewModel
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.BaseGalleryWebViewClient
import com.google.ai.edge.gallery.ui.common.GalleryWebView
import com.google.ai.edge.gallery.ui.common.buildTrackableUrlAnnotatedString
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageCollapsableProgressPanel
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWebView
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.LogMessage
import com.google.ai.edge.gallery.ui.common.chat.LogMessageLevel
import com.google.ai.edge.gallery.ui.common.chat.SendMessageTrigger
import com.google.ai.edge.gallery.ui.llmchat.LlmChatScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.tool
import java.lang.Exception
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

private const val TAG = "AGAgentChatScreen"
private val chatViewJavascriptInterface = ChatWebViewJavascriptInterface()

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  agentTools: AgentTools,
  taskId: String = BuiltInTaskId.LLM_AGENT_CHAT,
  viewModel: LlmChatViewModel = hiltViewModel(),
  skillManagerViewModel: SkillManagerViewModel = hiltViewModel(),
  mobileActionsViewModel: MobileActionsViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  agentTools.context = context
  agentTools.skillManagerViewModel = skillManagerViewModel
  agentTools.mobileActionsViewModel = mobileActionsViewModel
  val density = LocalDensity.current
  val windowInfo = LocalWindowInfo.current
  val screenWidthDp = remember { with(density) { windowInfo.containerSize.width.toDp() } }
  var showSkillManagerBottomSheet by remember { mutableStateOf(false) }
  var showAskInfoDialog by remember { mutableStateOf(false) }
  var currentAskInfoAction by remember { mutableStateOf<AskInfoAgentAction?>(null) }
  var askInfoInputValue by remember { mutableStateOf("") }
  var webViewRef: WebView? by remember { mutableStateOf(null) }
  val chatWebViewClient = remember { ChatWebViewClient(context = context) }
  var curSystemPrompt by remember { mutableStateOf(task.defaultSystemPrompt) }
  val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
  var sendMessageTrigger by remember { mutableStateOf<SendMessageTrigger?>(null) }
  var showAlertForDisabledSkill by remember { mutableStateOf(false) }
  var disabledSkillName by remember { mutableStateOf("") }
  // Specialist-models bottom sheet state — only meaningful for the V2 Orchestrator task.
  var showSpecialistsSheet by remember { mutableStateOf(false) }
  // Status bottom sheet (planner/specialist/RAM details) for the V2 Orchestrator.
  var showStatusSheet by remember { mutableStateOf(false) }

  // Workspace selector state — only meaningful for the V2 (Multi-Agent Skills) task. The URI is
  // shared with the Multi-Agent Orchestrator via the same SharedPreferences key.
  val workspacePrefs = remember(context) {
    context.getSharedPreferences("orchestrator_prefs", Context.MODE_PRIVATE)
  }
  var workspaceUri by remember {
    mutableStateOf(workspacePrefs.getString("workspace_uri", "") ?: "")
  }
  val workspacePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        context.contentResolver.takePersistableUriPermission(
          uri,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        workspacePrefs.edit().putString("workspace_uri", uri.toString()).apply()
        workspaceUri = uri.toString()
      }
    }

  LlmChatScreen(
    modelManagerViewModel = modelManagerViewModel,
    taskId = taskId,
    navigateUp = navigateUp,
    extraTopBarActions = {},
    subTopBar = {
      // Second row below the top app bar so the title has full width on the first row and
      // these buttons get plenty of room on a second row.
      if (
        taskId == BuiltInTaskId.LLM_AGENT_CHAT_V2 ||
          taskId == BuiltInTaskId.LLM_ORCHESTRATOR_V2
      ) {
        androidx.compose.foundation.layout.Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
          horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
          verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
          FilledTonalButton(
            onClick = { workspacePicker.launch(null) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
          ) {
            Icon(
              Icons.Outlined.Folder,
              contentDescription = "Set workspace",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              if (workspaceUri.isEmpty()) "Workspace" else "Workspace \u2713",
              fontSize = 12.sp,
            )
          }
          if (taskId == BuiltInTaskId.LLM_ORCHESTRATOR_V2) {
            FilledTonalButton(
              onClick = { showSpecialistsSheet = true },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
              modifier = Modifier.height(32.dp),
            ) {
              Icon(
                Icons.Outlined.Hub,
                contentDescription = "Specialist models",
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text("Specialists", fontSize = 12.sp)
            }
            OrchestratorStatusChip(onClick = { showStatusSheet = true })
          }
        }
      }
    },
    onFirstToken = { model ->
      updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
    },
    onGenerateResponseDone = { model ->
      // Show any image produced by tools.
      agentTools.resultImageToShow?.let { resultImage ->
        resultImage.base64?.let { base64 ->
          decodeBase64ToBitmap(base64String = base64)?.let { bitmap ->
            viewModel.addMessage(
              model = model,
              message =
                ChatMessageImage(
                  bitmaps = listOf(bitmap),
                  imageBitMaps = listOf(bitmap.asImageBitmap()),
                  side = ChatSide.AGENT,
                  maxSize = (screenWidthDp.value * 0.8).toInt(),
                  latencyMs = -1.0f,
                  hideSenderLabel = true,
                ),
            )
          }
        }
        // Clean up.
        agentTools.resultImageToShow = null
      }

      // Show any webview produced by tools.
      agentTools.resultWebviewToShow?.let { webview ->
        val url = webview.url ?: ""
        val iframe = webview.iframe == true
        val aspectRatio = webview.aspectRatio ?: 1.333f
        viewModel.addMessage(
          model = model,
          message =
            ChatMessageWebView(
              url = url,
              iframe = iframe,
              aspectRatio = aspectRatio,
              hideSenderLabel = true,
            ),
        )
        // Clean up.
        agentTools.resultWebviewToShow = null
      }

      updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
    },
    onResetSessionClickedOverride = { task, model ->
      resetSessionWithCurrentSkills(
        viewModel,
        modelManagerViewModel,
        skillManagerViewModel,
        task,
        curSystemPrompt,
        agentTools,
      )
    },
    onSkillClicked = { showSkillManagerBottomSheet = true },
    showImagePicker = true,
    showAudioPicker = true,
    getActiveSkills = {
      skillManagerViewModel.getSelectedSkills().map { skill ->
        if (skill.builtIn) skill.name else "custom_skill"
      }
    },
    composableBelowMessageList = { model ->
      val actionChannel = agentTools.actionChannel
      val doneIcon = ImageVector.vectorResource(R.drawable.skill)
      // Use rememberUpdatedState to ensure that LaunchedEffect captures the
      // latest active model when the model is switched during an ongoing skill execution.
      val currentModel by androidx.compose.runtime.rememberUpdatedState(model)
      LaunchedEffect(actionChannel) {
        for (action in actionChannel) {
          Log.d(TAG, "Handling action: $action")
          when (action) {
            is SkillProgressAgentAction -> {
              viewModel.updateCollapsableProgressPanelMessage(
                model = currentModel,
                title = action.label,
                inProgress = action.inProgress,
                doneIcon = doneIcon,
                addItemTitle = action.addItemTitle,
                addItemDescription = action.addItemDescription,
                customData = action.customData,
              )
            }
            is CallJsAgentAction -> {
              val skillName =
                if (action.url.contains("/skills/")) {
                  action.url.substringAfter("/skills/").substringBefore("/")
                } else if (action.url.startsWith(LOCAL_URL_BASE + "/")) {
                  action.url.substringAfter(LOCAL_URL_BASE + "/").substringBefore("/")
                } else {
                  action.url
                }
              try {
                // Set up a safety net timeout so we NEVER hang the chat or tool execution
                launch {
                  delay(60000L) // 60 seconds max
                  if (!action.result.isCompleted) {
                    Log.e(TAG, "JS Execution timed out, completing with error.")
                    Log.d(
                      TAG,
                      "Analytics: skill_execution, skill_name=$skillName, success=false, error_type=timeout",
                    )
                    firebaseAnalytics?.logEvent(
                      GalleryEvent.SKILL_EXECUTION.id,
                      Bundle().apply {
                        putString("skill_name", skillName)
                        putBoolean("success", false)
                        putString("error_type", "timeout")
                      },
                    )
                    action.result.complete(
                      "{\"error\": \"Skill execution timed out. Please check network connection.\"}"
                    )
                  }
                }

                // Load url.
                suspendCancellableCoroutine<Unit> { continuation ->
                  chatWebViewClient.setPageLoadListener {
                    chatWebViewClient.setPageLoadListener(null)
                    continuation.resume(Unit)
                  }
                  Log.d(TAG, "Loading url: ${action.url}")
                  webViewRef?.loadUrl(action.url)
                }

                // Execute JS.
                Log.d(TAG, "Start to run js")
                chatViewJavascriptInterface.onResultListener = { result ->
                  Log.d(TAG, "Got result:\n$result")
                  action.result.complete(result)
                  val isSuccess = !result.contains("\"error\":")
                  val errorType = if (isSuccess) "" else "js_error"
                  Log.d(
                    TAG,
                    "Analytics: skill_execution, skill_name=$skillName, success=$isSuccess, error_type=$errorType",
                  )
                  firebaseAnalytics?.logEvent(
                    GalleryEvent.SKILL_EXECUTION.id,
                    Bundle().apply {
                      putString("skill_name", skillName)
                      putBoolean("success", isSuccess)
                      putString("error_type", errorType)
                    },
                  )
                }

                val safeData = JSONObject.quote(action.data)
                val safeSecret = JSONObject.quote(action.secret)
                val script =
                  """
                  (async function() {
                      var startTs = Date.now();
                      while(true) {
                        if (typeof ai_edge_gallery_get_result === 'function') {
                          break;
                        }
                        await new Promise(resolve=>{
                          setTimeout(resolve, 100)
                        });
                        if (Date.now() - startTs > 10000) {
                          break;
                        }
                      }
                      var result = await ai_edge_gallery_get_result($safeData, $safeSecret);
                      AiEdgeGallery.onResultReady(result);
                  })()
                  """
                    .trimIndent()
                webViewRef?.evaluateJavascript(script, null)
              } catch (e: Exception) {
                Log.d(
                  TAG,
                  "Analytics: skill_execution, skill_name=$skillName, success=false, error_type=exception",
                )
                firebaseAnalytics?.logEvent(
                  GalleryEvent.SKILL_EXECUTION.id,
                  Bundle().apply {
                    putString("skill_name", skillName)
                    putBoolean("success", false)
                    putString("error_type", "exception")
                  },
                )
                action.result.completeExceptionally(e)
              }
            }
            is AskInfoAgentAction -> {
              currentAskInfoAction = action
              askInfoInputValue = "" // Reset input
              showAskInfoDialog = true
            }
            is MobileActionAgentAction -> {
              val details = action.action.functionCallDetails
              val paramsDesc =
                if (details.parameters.isEmpty()) ""
                else details.parameters.joinToString(", ") { "${it.first}=${it.second}" }
              viewModel.updateCollapsableProgressPanelMessage(
                model = currentModel,
                title = "Mobile action: ${details.functionName}",
                inProgress = false,
                doneIcon = action.action.icon,
                addItemTitle = details.functionName,
                addItemDescription = paramsDesc,
              )
              val error = mobileActionsViewModel.performAction(action.action, context)
              if (error.isNotEmpty()) {
                Log.e(TAG, "Mobile action error: $error")
              }
            }
          }
        }
      }

      GalleryWebView(
        modifier = Modifier.size(300.dp),
        onWebViewCreated = { webView ->
          webViewRef = webView
          webView.addJavascriptInterface(chatViewJavascriptInterface, "AiEdgeGallery")
        },
        customWebViewClient = chatWebViewClient,
        onConsoleMessage = { consoleMessage ->
          consoleMessage?.let { curConsoleMessage ->
            // Create a LogMessage from the ConsoleMessage and add it to the progress panel.
            val logMessage =
              LogMessage(
                level =
                  when (curConsoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.LOG -> LogMessageLevel.Info
                    ConsoleMessage.MessageLevel.ERROR -> LogMessageLevel.Error
                    ConsoleMessage.MessageLevel.WARNING -> LogMessageLevel.Warning
                    else -> LogMessageLevel.Info
                  },
                source = curConsoleMessage.sourceId(),
                lineNumber = curConsoleMessage.lineNumber(),
                message = curConsoleMessage.message(),
              )
            viewModel.addLogMessageToLastCollapsableProgressPanel(
              model = model,
              logMessage = logMessage,
            )
            Log.d(
              TAG,
              "${curConsoleMessage.message()} " +
                "-- From line ${curConsoleMessage.lineNumber()} of ${curConsoleMessage.sourceId()}",
            )
          }
        },
      )
    },
    allowEditingSystemPrompt = true,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = { newPrompt ->
      curSystemPrompt = newPrompt
      resetSessionWithCurrentSkills(
        viewModel,
        modelManagerViewModel,
        skillManagerViewModel,
        task,
        curSystemPrompt,
        agentTools,
        onDone = { model ->
          viewModel.addMessage(
            model = model,
            message = ChatMessageInfo(content = systemPromptUpdatedMessage),
          )
        },
      )
    },
    emptyStateComposable = { model ->
      val uiState by viewModel.uiState.collectAsState()
      val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
      val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[model.name]
      Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
          !WindowInsets.isImeVisible,
          enter = fadeIn(animationSpec = tween(200)),
          exit = fadeOut(animationSpec = tween(200)),
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
              modifier =
                Modifier.align(Alignment.Center)
                  .padding(horizontal = 32.dp)
                  .padding(bottom = 48.dp)
                  .verticalScroll(rememberScrollState()),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              if (taskId == BuiltInTaskId.LLM_ORCHESTRATOR_V2) {
                Text(
                  "Multi-Agent Orchestrator",
                  style =
                    MaterialTheme.typography.headlineMedium.copy(
                      fontWeight = FontWeight.Medium,
                      brush =
                        Brush.linearGradient(
                          colors = listOf(Color(0xFF85B1F8), Color(0xFF3174F1))
                        ),
                    ),
                  modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                  textAlign = TextAlign.Center,
                )
                Text(
                  "The planner can act directly OR dispatch sub-tasks to specialist agents:",
                  style =
                    MaterialTheme.typography.bodyMedium.copy(
                      fontSize = 14.sp,
                      lineHeight = 20.sp,
                    ),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.padding(bottom = 16.dp),
                )
                AgentDescription(
                  title = "mobile_agent",
                  body =
                    "Flashlight (incl. flashMorseCode for SOS), contacts, calendar events, " +
                      "email, SMS, maps, WiFi settings.",
                )
                AgentDescription(
                  title = "app_launcher",
                  body =
                    "Lists installed apps, launches them, or sends structured intents " +
                      "(e.g. share text, open URL).",
                )
                AgentDescription(
                  title = "workspace_agent",
                  body =
                    "Reads, writes, lists, creates and deletes files in the chosen workspace " +
                      "folder. Tap Workspace at the top to grant access.",
                )
                AgentDescription(
                  title = "skill_creator",
                  body =
                    "Generates and imports new text or JavaScript skills on the fly. New skills " +
                      "become immediately available to skill_agent.",
                )
                AgentDescription(
                  title = "skill_agent",
                  body =
                    "Executes installed skills via JavaScript: query-wikipedia, qr-code, " +
                      "calculate-hash, mood-tracker, restaurant-roulette, …",
                )
                Text(
                  "Try: \"Flash SOS in Morse with the flashlight\" or " +
                    "\"Look up Marie Curie on Wikipedia and add a contact for her\".",
                  style =
                    MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.padding(top = 12.dp),
                )
              } else {
                Text(
                  stringResource(R.string.introducing),
                  style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                  stringResource(R.string.agent_skills),
                  style =
                    MaterialTheme.typography.headlineLarge.copy(
                      fontWeight = FontWeight.Medium,
                      brush =
                        Brush.linearGradient(
                          colors = listOf(Color(0xFF85B1F8), Color(0xFF3174F1))
                        ),
                    ),
                  modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                )
                Text(
                  buildAnnotatedString {
                    append(
                      "Use specialized, high-order reasoning by loading different skills or "
                    )
                    append(
                      buildTrackableUrlAnnotatedString(
                        url = "https://github.com/google-ai-edge/gallery/tree/main/skills",
                        linkText = "creating\u00A0your\u00A0own",
                      )
                    )
                    append(
                      ".\n\nTry tapping a sample prompt below to see Agent Skills in action!"
                    )
                  },
                  style =
                    MaterialTheme.typography.headlineSmall.copy(
                      fontSize = 16.sp,
                      lineHeight = 22.sp,
                    ),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        }

        Row(
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for (promptChip in TRYOUT_CHIPS) {
            FilledTonalButton(
              enabled =
                modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZED &&
                  !uiState.isResettingSession,
              onClick = {
                // Skill is selected, trigger sending the message.
                if (skillManagerViewModel.isSkillSelected(promptChip.skillName)) {
                  sendMessageTrigger =
                    SendMessageTrigger(
                      model = model,
                      messages =
                        listOf(ChatMessageText(content = promptChip.prompt, side = ChatSide.USER)),
                    )
                  firebaseAnalytics?.logEvent(
                    GalleryEvent.BUTTON_CLICKED.id,
                    Bundle().apply {
                      putString("event_type", "agent_skills_prompt_chip")
                      putString("button_id", promptChip.label)
                    },
                  )
                }
                // Skill is not selected, show alert dialog.
                else {
                  disabledSkillName = promptChip.skillName
                  showAlertForDisabledSkill = true
                }
              },
              contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
              Icon(promptChip.icon, contentDescription = null, modifier = Modifier.size(20.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(promptChip.label)
            }
          }
        }
      }
    },
    sendMessageTrigger = sendMessageTrigger,
  )

  if (showAskInfoDialog && currentAskInfoAction != null) {
    val action = currentAskInfoAction!!
    SecretEditorDialog(
      title = action.dialogTitle,
      fieldLabel = action.fieldLabel,
      value = askInfoInputValue,
      onValueChange = { askInfoInputValue = it },
      onDone = {
        action.result.complete(askInfoInputValue)
        showAskInfoDialog = false
        currentAskInfoAction = null
      },
      onDismiss = {
        action.result.complete("")
        showAskInfoDialog = false
        currentAskInfoAction = null
      },
    )
  }

  if (showSkillManagerBottomSheet) {
    SkillManagerBottomSheet(
      agentTools = agentTools,
      skillManagerViewModel = skillManagerViewModel,
      onDismiss = { selectedSkillsChanged ->
        // Hide sheet.
        showSkillManagerBottomSheet = false

        // Reset session when selected skills changed.
        if (selectedSkillsChanged) {
          Log.d(TAG, "Selected skill changed. Resetting conversation.")
          resetSessionWithCurrentSkills(
            viewModel,
            modelManagerViewModel,
            skillManagerViewModel,
            task,
            curSystemPrompt,
            agentTools,
          )
        }
      },
    )
  }

  if (showAlertForDisabledSkill) {
    AlertDialog(
      onDismissRequest = { showAlertForDisabledSkill = false },
      title = { Text("The \"$disabledSkillName\" skill is currently disabled") },
      text = { Text(stringResource(R.string.enable_skill_dialog_content)) },
      confirmButton = {
        Button(onClick = { showAlertForDisabledSkill = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }

  // ── Specialist Models picker (V2 Orchestrator only) ─────────────────────────────
  if (showSpecialistsSheet && taskId == BuiltInTaskId.LLM_ORCHESTRATOR_V2) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val currentModel = modelManagerUiState.selectedModel

    val downloadedSpecialists =
      task.models.filter { m ->
        m.name != currentModel.name &&
          modelManagerUiState.modelDownloadStatus[m.name]?.status ==
            com.google.ai.edge.gallery.data.ModelDownloadStatusType.SUCCEEDED
      }
    val allDownloadedNames = downloadedSpecialists.map { it.name }.toSet()

    val savedCsv = workspacePrefs.getString("v2_specialist_names", "") ?: ""
    val savedSet = savedCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    var tempSelected by remember {
      mutableStateOf(if (savedSet.isEmpty()) allDownloadedNames else savedSet)
    }

    ModalBottomSheet(
      onDismissRequest = { showSpecialistsSheet = false },
      sheetState = sheetState,
    ) {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          "Specialist Models",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        HorizontalDivider()
        Text(
          "Planner: ${currentModel.name}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "Select which downloaded models are available as specialist agents in the model pool. " +
            "All checked models will be offered to the planner via dispatchToAgent.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (downloadedSpecialists.isEmpty()) {
          Text(
            "No other models downloaded. The planner will use its own weights as the only specialist.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          for (m in downloadedSpecialists) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Checkbox(
                checked = tempSelected.contains(m.name),
                onCheckedChange = { checked ->
                  tempSelected =
                    if (checked) tempSelected + m.name else tempSelected - m.name
                },
              )
              Text(m.name, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
        Button(
          onClick = {
            // Empty set = "all downloaded": store empty string so the V2 task uses everything.
            val csv =
              if (tempSelected == allDownloadedNames) "" else tempSelected.joinToString(",")
            workspacePrefs.edit().putString("v2_specialist_names", csv).apply()
            showSpecialistsSheet = false
            modelManagerViewModel.initializeModel(context, task, currentModel, force = true)
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Apply & Reinitialize")
        }
      }
    }
  }

  // ── Live status sheet (V2 Orchestrator only) ────────────────────────────────────
  if (showStatusSheet && taskId == BuiltInTaskId.LLM_ORCHESTRATOR_V2) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = { showStatusSheet = false },
      sheetState = sheetState,
    ) {
      OrchestratorStatusPanel()
    }
  }
}

@Composable
private fun AgentDescription(title: String, body: String) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    Text(
      title,
      style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
      color = MaterialTheme.colorScheme.primary,
    )
    Text(
      body,
      style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Compact pill in the chat top bar showing live orchestrator status. Tapping opens details. */
@Composable
private fun OrchestratorStatusChip(onClick: () -> Unit) {
  val context = LocalContext.current
  val activeSpecialist by
    com.google.ai.edge.gallery.customtasks.orchestrator.OrchestratorStatus.activeSpecialist
      .collectAsState()

  // Refresh RAM every 30 seconds.
  var freeRamMb by remember { mutableStateOf(readFreeRamMb(context)) }
  LaunchedEffect(Unit) {
    while (true) {
      freeRamMb = readFreeRamMb(context)
      kotlinx.coroutines.delay(30_000L)
    }
  }

  val busy = activeSpecialist != null
  val label = if (busy) "● ${freeRamMb}M" else "○ ${freeRamMb}M"
  FilledTonalButton(
    onClick = onClick,
    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    modifier = Modifier.height(32.dp),
  ) {
    Text(label, fontSize = 11.sp)
  }
}

/** Detailed status panel for the bottom sheet — planner / specialist / RAM / dispatch count. */
@Composable
private fun OrchestratorStatusPanel() {
  val context = LocalContext.current
  val plannerName by
    com.google.ai.edge.gallery.customtasks.orchestrator.OrchestratorStatus.plannerName
      .collectAsState()
  val activeSpecialist by
    com.google.ai.edge.gallery.customtasks.orchestrator.OrchestratorStatus.activeSpecialist
      .collectAsState()
  val lastActivity by
    com.google.ai.edge.gallery.customtasks.orchestrator.OrchestratorStatus.lastActivity
      .collectAsState()
  val dispatchCount by
    com.google.ai.edge.gallery.customtasks.orchestrator.OrchestratorStatus.dispatchCount
      .collectAsState()
  val models by
    com.google.ai.edge.gallery.customtasks.orchestrator.OrchestratorStatus.models
      .collectAsState()
  val log by
    com.google.ai.edge.gallery.customtasks.orchestrator.OrchestratorStatus.log
      .collectAsState()

  // RAM refreshed every 1.5s while the sheet is open so the user sees live values.
  var memInfo by remember { mutableStateOf(readMemoryInfo(context)) }
  LaunchedEffect(Unit) {
    while (true) {
      memInfo = readMemoryInfo(context)
      kotlinx.coroutines.delay(1_500L)
    }
  }

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 32.dp)
        .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      "Orchestrator status",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
    )
    HorizontalDivider()
    StatusRow("Planner", if (plannerName.isEmpty()) "—" else plannerName)
    StatusRow(
      "State",
      if (activeSpecialist != null) "Dispatching → ${activeSpecialist}" else "Idle",
    )
    StatusRow("Last activity", lastActivity)
    StatusRow("Dispatches done", dispatchCount.toString())
    HorizontalDivider()
    StatusRow("Free RAM", "${memInfo.first} MB")
    StatusRow("Total RAM", "${memInfo.second} MB")
    StatusRow(
      "Low memory",
      if (memInfo.third) "YES — system is reclaiming" else "no",
    )

    HorizontalDivider()
    Text(
      "Models in RAM (${models.size})",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(top = 4.dp),
    )
    if (models.isEmpty()) {
      Text(
        "(no model loaded yet)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      for (m in models) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
          Text(
            "${m.name}  [${m.role}]" + if (m.sharedWithPlanner) "  · shared engine" else "",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
          )
          for (t in m.tools) {
            Text(
              "  • $t",
              style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    HorizontalDivider()
    Text(
      "Orchestration log (${log.size})",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(top = 4.dp),
    )
    if (log.isEmpty()) {
      Text(
        "(empty — perform an action to see entries)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      val timeFmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }
      for (entry in log.takeLast(80)) {
        val tone = when (entry.source) {
          "planner" -> MaterialTheme.colorScheme.primary
          "system" -> MaterialTheme.colorScheme.onSurfaceVariant
          else -> MaterialTheme.colorScheme.tertiary
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
          Text(
            timeFmt.format(java.util.Date(entry.timestamp)),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
          )
          Text(
            "${entry.source}: ",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = tone,
            fontWeight = FontWeight.Medium,
          )
          Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }

    Text(
      "RAM refreshes every 1.5s while this panel is open. The compact chip in the top bar " +
        "refreshes every 30s.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

@Composable
private fun StatusRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth()) {
    Text(
      "$label:",
      modifier = Modifier.width(120.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
  }
}

private fun readFreeRamMb(context: Context): Long {
  val am =
    context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
      ?: return -1L
  val info = android.app.ActivityManager.MemoryInfo()
  am.getMemoryInfo(info)
  return info.availMem / (1024L * 1024L)
}

/** @return Triple(freeMb, totalMb, lowMemory). */
private fun readMemoryInfo(context: Context): Triple<Long, Long, Boolean> {
  val am =
    context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
      ?: return Triple(-1L, -1L, false)
  val info = android.app.ActivityManager.MemoryInfo()
  am.getMemoryInfo(info)
  return Triple(
    info.availMem / (1024L * 1024L),
    info.totalMem / (1024L * 1024L),
    info.lowMemory,
  )
}

private fun updateProgressPanel(viewModel: LlmChatViewModel, model: Model, agentTools: AgentTools) {
  // Update status.
  val lastProgressPanelMessage =
    viewModel.getLastMessageWithType(
      model = model,
      type = ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
    )
  if (
    lastProgressPanelMessage != null &&
      lastProgressPanelMessage is ChatMessageCollapsableProgressPanel
  ) {
    if (lastProgressPanelMessage.title.startsWith("Loading")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Loading", "Loaded"),
          inProgress = false,
        )
      )
    } else if (lastProgressPanelMessage.title.startsWith("Calling")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Calling", "Called"),
          inProgress = false,
        )
      )
    } else if (lastProgressPanelMessage.title.startsWith("Executing")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Executing", "Executed"),
          inProgress = false,
        )
      )
    }
  }
}

private fun resetSessionWithCurrentSkills(
  viewModel: LlmChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  skillManagerViewModel: SkillManagerViewModel,
  task: Task,
  curSystemPrompt: String,
  agentTools: AgentTools,
  onDone: (Model) -> Unit = {},
) {
  val model = modelManagerViewModel.uiState.value.selectedModel
  val newSelectedSkills = skillManagerViewModel.getSelectedSkills()
  viewModel.resetSession(
    task = task,
    model = model,
    systemInstruction =
      if (newSelectedSkills.isEmpty()) null
      else skillManagerViewModel.getSystemPrompt(curSystemPrompt),
    tools = listOf(tool(agentTools)),
    supportImage = true,
    supportAudio = true,
    onDone = { onDone(model) },
    enableConversationConstrainedDecoding = true,
  )
}

class ChatWebViewJavascriptInterface {
  var onResultListener: ((String) -> Unit)? = null

  @JavascriptInterface
  fun onResultReady(result: String) {
    onResultListener?.invoke(result)
  }
}

class ChatWebViewClient(val context: Context) : BaseGalleryWebViewClient(context = context) {
  private var onPageLoaded: (() -> Unit)? = null

  fun setPageLoadListener(listener: (() -> Unit)?) {
    onPageLoaded = listener
  }

  override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    Log.d(TAG, "page loaded")
    onPageLoaded?.invoke()
  }
}
