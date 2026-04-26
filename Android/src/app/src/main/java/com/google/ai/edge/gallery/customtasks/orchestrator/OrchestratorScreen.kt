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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SentimentVerySatisfied
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.SkillState
import com.google.ai.edge.gallery.customtasks.agentchat.TRYOUT_CHIPS
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.BaseGalleryWebViewClient
import com.google.ai.edge.gallery.ui.common.GalleryWebView
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/**
 * Main screen for the multi-agent orchestrator.
 *
 * Provides a chat interface backed by the planner model and shows a collapsible dispatch-trace
 * panel that summarises what each specialist agent did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrchestratorScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  orchestratorTask: OrchestratorTask,
  orchestratorViewModel: OrchestratorViewModel = hiltViewModel(),
  skillManagerViewModel: SkillManagerViewModel = hiltViewModel(),
) {
  val context = LocalContext.current

  // Mirror AgentChatScreen: keep agentTools in sync on every recomposition so that
  // skills work even if initializeModelFn ran before this composable was entered or
  // when a new NavBackStackEntry gives us a fresh SkillManagerViewModel instance.
  orchestratorTask.skillManagerViewModel = skillManagerViewModel
  val agentTools = orchestratorTask.agentTools
  agentTools.context = context
  agentTools.skillManagerViewModel = skillManagerViewModel

  // ── WebView infrastructure for skill JS execution (mirrors AgentChatScreen) ──
  val chatWebViewClient = remember { OrchestratorWebViewClient(context) }
  val chatViewJavascriptInterface = orchestratorChatViewJavascriptInterface
  var webViewRef: WebView? by remember { mutableStateOf(null) }
  LaunchedEffect(agentTools.actionChannel) {
      for (action in agentTools.actionChannel) {
        when (action) {
          is CallJsAgentAction -> {
            try {
              launch {
                delay(60000L)
                if (!action.result.isCompleted) {
                  action.result.complete("{\"error\": \"Skill execution timed out.\"}")
                }
              }
              suspendCancellableCoroutine<Unit> { continuation ->
                chatWebViewClient.setPageLoadListener {
                  chatWebViewClient.setPageLoadListener(null)
                  continuation.resume(Unit)
                }
                webViewRef?.loadUrl(action.url)
              }
              chatViewJavascriptInterface.onResultListener = { result ->
                action.result.complete(result)
              }
              val safeData = JSONObject.quote(action.data)
              val safeSecret = JSONObject.quote(action.secret)
              val script = """
                (async function() {
                    var startTs = Date.now();
                    while(true) {
                      if (typeof ai_edge_gallery_get_result === 'function') { break; }
                      await new Promise(resolve=>{ setTimeout(resolve, 100) });
                      if (Date.now() - startTs > 10000) { break; }
                    }
                    var result = await ai_edge_gallery_get_result($safeData, $safeSecret);
                    AiEdgeGallery.onResultReady(result);
                })()
              """.trimIndent()
              webViewRef?.evaluateJavascript(script, null)
            } catch (e: Exception) {
              action.result.completeExceptionally(e)
            }
          }
          is SkillProgressAgentAction -> { /* no-op */ }
          else -> { /* AskInfoAgentAction and others not used in orchestrator */ }
        }
      }
    }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val uiState by orchestratorViewModel.uiState.collectAsState()
  val skillUiState by skillManagerViewModel.uiState.collectAsState()

  val currentModel = modelManagerUiState.selectedModel
  val initStatus = modelManagerUiState.modelInitializationStatus[currentModel.name]
  val isInitialized = initStatus?.status == ModelInitializationStatusType.INITIALIZED
  val isInitializing = initStatus?.status == ModelInitializationStatusType.INITIALIZING

  // SAF workspace folder picker.
  val workspacePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        context.contentResolver.takePersistableUriPermission(
          uri,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        orchestratorViewModel.saveWorkspaceUri(uri.toString())
      }
    }

  var inputText by remember { mutableStateOf("") }
  var showActionsPanel by remember { mutableStateOf(true) }
  var showClearDialog by remember { mutableStateOf(false) }
  var showModelConfigSheet by remember { mutableStateOf(false) }
  var showSkillsSheet by remember { mutableStateOf(false) }
  var showAddMenu by remember { mutableStateOf(false) }

  val listState = rememberLazyListState()

  // Auto-scroll to last message.
  LaunchedEffect(uiState.chatMessages.size, uiState.streamingResponse) {
    if (uiState.chatMessages.isNotEmpty()) {
      listState.animateScrollToItem(uiState.chatMessages.size - 1)
    }
  }

  fun sendMessage() {
    val prompt = inputText.trim()
    if (prompt.isNotEmpty() && isInitialized && !uiState.isProcessing) {
      inputText = ""
      orchestratorViewModel.processUserPrompt(
        plannerModel = currentModel,
        userPrompt = prompt,
        onError = { /* TODO: show snackbar */ },
      )
    }
  }

  // Clear conversation dialog.
  if (showClearDialog) {
    AlertDialog(
      onDismissRequest = { showClearDialog = false },
      title = { Text("Clear conversation") },
      text = { Text("This will clear all messages and reset the planner conversation context.") },
      confirmButton = {
        Button(onClick = {
          val (sysPrompt, tools) = orchestratorTask.getPlannerConfig()
          orchestratorViewModel.resetPlannerConversation(currentModel, sysPrompt, tools)
          orchestratorTask.curActions.clear()
          showClearDialog = false
        }) {
          Text("Clear")
        }
      },
      dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } },
    )
  }

  // Low memory warning.
  if (uiState.lowMemoryWarning) {
    AlertDialog(
      onDismissRequest = { orchestratorViewModel.dismissLowMemoryWarning() },
      icon = { Icon(Icons.Outlined.Warning, contentDescription = null) },
      title = { Text("Low memory warning") },
      text = {
        Text(
          "Running two model engines simultaneously may exceed available memory. " +
            "Consider using a smaller model (e.g. Gemma-4-E2B or smaller)."
        )
      },
      confirmButton = {
        Button(onClick = { orchestratorViewModel.dismissLowMemoryWarning() }) { Text("OK") }
      },
    )
  }

  // ── Model Configuration bottom sheet ────────────────────────────────────
  if (showModelConfigSheet) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Models that are downloaded and not the current planner.
    val downloadedSpecialists = task.models.filter { m ->
      m.name != currentModel.name &&
        modelManagerUiState.modelDownloadStatus[m.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }
    val allDownloadedNames = downloadedSpecialists.map { it.name }.toSet()

    // Temp selection: pre-populate from task, defaulting to "all" when empty.
    var tempSelected by remember {
      mutableStateOf(
        orchestratorTask.selectedSpecialistNames.ifEmpty { allDownloadedNames }
      )
    }

    ModalBottomSheet(
      onDismissRequest = { showModelConfigSheet = false },
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
          "Model Configuration",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        // ── Planner ──────────────────────────────────────────────────────
        Text(
          "Planner Model",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            Icons.Outlined.Hub,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
          Text(currentModel.name, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
          "To change the planner, use the model picker at the top of the screen.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // ── Specialists ───────────────────────────────────────────────────
        Text(
          "Specialist Models",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          "Select which downloaded models are available as specialist agents. " +
            "All checked models will be offered to the planner for dispatch.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (downloadedSpecialists.isEmpty()) {
          Text(
            "No other models downloaded. Download additional models to configure specialist assignments.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          for (m in downloadedSpecialists) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
            ) {
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

        Spacer(modifier = Modifier.height(4.dp))

        Button(
          onClick = {
            // Empty set = "all downloaded" — only store an explicit set when it differs.
            orchestratorTask.selectedSpecialistNames =
              if (tempSelected == allDownloadedNames) emptySet() else tempSelected
            showModelConfigSheet = false
            modelManagerViewModel.initializeModel(context, task, currentModel, force = true)
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Apply & Reinitialize")
        }
      }
    }
  }

  // Skills picker sheet.
  if (showSkillsSheet) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { showSkillsSheet = false }, sheetState = sheetState) {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("Available Skills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        HorizontalDivider()
        if (skillUiState.skills.isEmpty()) {
          Text("No skills installed.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          for (skillState in skillUiState.skills) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(skillState.skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (skillState.skill.description.isNotEmpty()) {
                  Text(skillState.skill.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
              }
              Switch(
                checked = skillState.skill.selected,
                onCheckedChange = { skillManagerViewModel.setSkillSelected(skillState, it) },
              )
            }
            HorizontalDivider()
          }
        }
      }
    }
  }

  // Wrap in a Box so the hidden WebView does not affect the Column layout.
  Box(modifier = Modifier.fillMaxSize()) {
    // Hidden 1dp WebView overlay — used to execute skill JavaScript without affecting layout.
    // Always created so the actionChannel LaunchedEffect above can always drive it,
    // regardless of when PlannerTools is initialized.
    GalleryWebView(
      modifier = Modifier.size(300.dp),
      onWebViewCreated = { webView ->
        webViewRef = webView
        webView.addJavascriptInterface(chatViewJavascriptInterface, "AiEdgeGallery")
      },
      customWebViewClient = chatWebViewClient,
    )

  Column(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()
  ) {
    // ── Header bar ──────────────────────────────────────────────────────────
    Surface(tonalElevation = 2.dp) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            Icons.Outlined.Hub,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.width(6.dp))
          Column {
            Text("Multi-Agent Orchestrator", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (uiState.workspaceUri.isNotEmpty()) {
              Text(
                "Workspace set",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          // Set workspace button.
          FilledTonalButton(
            onClick = { workspacePicker.launch(null) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp),
          ) {
            Icon(
              Icons.Outlined.Folder,
              contentDescription = "Set workspace",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Workspace", fontSize = 12.sp)
          }
          // Model configuration button.
          IconButton(onClick = { showModelConfigSheet = true }) {
            Icon(Icons.Outlined.Settings, contentDescription = "Model configuration")
          }
          // Clear conversation button.
          IconButton(onClick = { showClearDialog = true }, enabled = !uiState.isProcessing) {
            Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear conversation")
          }
        }
      }
    }

    // ── Initialization overlay ───────────────────────────────────────────────
    if (!isInitialized && !isInitializing) {
      Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Card(
          colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text("Select and download a model to get started.", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              "Recommended: Gemma-4-E2B-IT — planner and specialist engines share the same weights.",
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    if (isInitializing) {
      Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          Text("Loading planner + specialist engines…", fontSize = 13.sp)
        }
      }
    }

    // ── Dispatch-trace panel ────────────────────────────────────────────────
    if (orchestratorTask.curActions.isNotEmpty()) {
      ActionTracePanel(
        actions = orchestratorTask.curActions,
        expanded = showActionsPanel,
        onToggle = { showActionsPanel = !showActionsPanel },
      )
    }

    // ── Chat messages ────────────────────────────────────────────────────────
    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = PaddingValues(vertical = 8.dp),
    ) {
      items(uiState.chatMessages) { msg -> ChatBubble(message = msg) }
      if (uiState.streamingResponse.isNotEmpty()) {
        item {
          ChatBubble(
            message =
              OrchestratorChatMessage(content = uiState.streamingResponse, isUser = false),
            isStreaming = true,
          )
        }
      }
    }

    // ── Bottom area ──────────────────────────────────────────────────────────
    Surface(tonalElevation = 4.dp) {
      Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {

        // ── Chips row: active skill chips (blue) + agent-type chips (secondary) ──
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
            .padding(top = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Skill shortcut chips — clicking pre-fills the prompt (mirrors Agent Skills TRYOUT_CHIPS).
          for (chip in TRYOUT_CHIPS) {
            if (skillManagerViewModel.isSkillSelected(chip.skillName)) {
              AssistChip(
                onClick = { if (isInitialized && !uiState.isProcessing) inputText = chip.prompt },
                label = { Text(chip.label, fontSize = 11.sp) },
                leadingIcon = {
                  Icon(chip.icon, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                colors = AssistChipDefaults.assistChipColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                  leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
              )
            }
          }
          // Agent-type chips — secondary color.
          for (agentType in AgentType.entries) {
            AssistChip(
              onClick = {},
              label = { Text(agentType.displayName, fontSize = 11.sp) },
              leadingIcon = {
                Icon(agentTypeIcon(agentType), contentDescription = null, modifier = Modifier.size(14.dp))
              },
              colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
              ),
            )
          }
        }

        // ── Text input area (bordered, matching Agent Skills style) ──────────
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp)
              .padding(vertical = 8.dp)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
          ) {
            // Row 1: text field.
            TextField(
              value = inputText,
              onValueChange = { inputText = it },
              enabled = isInitialized && !uiState.isProcessing,
              placeholder = {
                Text(if (!isInitialized) "Waiting for model…" else "Ask the orchestrator…")
              },
              colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
              ),
              textStyle = MaterialTheme.typography.bodyLarge,
              minLines = 1,
              maxLines = 3,
              modifier = Modifier.fillMaxWidth(),
            )

            // Row 2 (offset upward): + button, Skills button, send button.
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .offset(y = (-8).dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              // Left cluster: + button + Skills button.
              Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                // ── + button with quick-action dropdown ──────────────────────
                Box {
                  OutlinedIconButton(
                    onClick = { showAddMenu = true },
                    enabled = isInitialized && !uiState.isProcessing,
                  ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Quick actions", modifier = Modifier.size(22.dp))
                  }
                  DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false },
                  ) {
                    DropdownMenuItem(
                      text = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Turn on flashlight")
                      }},
                      onClick = { inputText = "Turn on the flashlight"; showAddMenu = false },
                    )
                    DropdownMenuItem(
                      text = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Set an alarm")
                      }},
                      onClick = { inputText = "Set an alarm for 8:00 AM tomorrow"; showAddMenu = false },
                    )
                    DropdownMenuItem(
                      text = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Open an app")
                      }},
                      onClick = { inputText = "Open the camera app"; showAddMenu = false },
                    )
                    DropdownMenuItem(
                      text = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Query Wikipedia")
                      }},
                      onClick = { inputText = "Query Wikipedia about "; showAddMenu = false },
                    )
                    DropdownMenuItem(
                      text = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Read a file")
                      }},
                      onClick = { inputText = "Read the file named "; showAddMenu = false },
                    )
                  }
                }

                // ── Skills button ────────────────────────────────────────────
                OutlinedButton(
                  onClick = { showSkillsSheet = true },
                  enabled = isInitialized && !uiState.isProcessing,
                ) {
                  Text("Skills", fontSize = 12.sp)
                }
              }

              // ── Send / spinner button ────────────────────────────────────
              val canSend = isInitialized && !uiState.isProcessing && inputText.isNotBlank()
              Box(
                modifier = Modifier
                  .clip(CircleShape)
                  .alpha(if (canSend) 1f else 0.4f)
                  .background(MaterialTheme.colorScheme.primary)
                  .size(36.dp)
                  .then(if (canSend) Modifier.clickable { sendMessage() } else Modifier),
                contentAlignment = Alignment.Center,
              ) {
                if (uiState.isProcessing) {
                  CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                  Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send",
                    tint = Color.White, modifier = Modifier.size(20.dp).offset(x = 2.dp))
                }
              }
            }
          }
        }
      }
    }
  } // end Column
  } // end Box
}

// Returns the icon for a given AgentType.
private fun agentTypeIcon(agentType: AgentType): ImageVector =
  when (agentType) {
    AgentType.MOBILE_AGENT -> Icons.Outlined.PhoneAndroid
    AgentType.APP_LAUNCHER -> Icons.Outlined.Apps
    AgentType.WORKSPACE_AGENT -> Icons.Outlined.Folder
    AgentType.SKILL_CREATOR -> Icons.Outlined.AutoAwesome
    AgentType.SKILL_AGENT -> Icons.Outlined.Extension
  }

// ── Chat bubble ──────────────────────────────────────────────────────────────

@Composable
private fun ChatBubble(message: OrchestratorChatMessage, isStreaming: Boolean = false) {
  val isUser = message.isUser
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Box(
      modifier = Modifier
        .clip(
          RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = if (isUser) 16.dp else 4.dp,
            bottomEnd = if (isUser) 4.dp else 16.dp,
          )
        )
        .background(
          if (isUser) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceVariant
        )
        .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
      Text(
        text = message.content + if (isStreaming) "▍" else "",
        fontSize = 14.sp,
        color = if (isUser)
          MaterialTheme.colorScheme.onPrimaryContainer
        else
          MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

// ── Dispatch trace panel ────────────────────────────────────────────────────

@Composable
private fun ActionTracePanel(
  actions: SnapshotStateList<OrchestratorAction>,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  Surface(tonalElevation = 1.dp) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable(onClick = onToggle)
          .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            Icons.Outlined.AccountTree,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            "Agent dispatch trace (${actions.size})",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Icon(
          if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
          contentDescription = if (expanded) "Collapse" else "Expand",
          modifier = Modifier.size(18.dp),
        )
      }
      AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          actions.forEach { action -> ActionTraceCard(action) }
        }
      }
    }
  }
}

@Composable
private fun ActionTraceCard(action: OrchestratorAction) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    shape = RoundedCornerShape(8.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = action.icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column {
        Text(
          action.label,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (action.detail.isNotEmpty()) {
          Text(
            action.detail.take(120) + if (action.detail.length > 120) "…" else "",
            fontSize = 11.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
          )
        }
      }
    }
  }
}

// ── WebView helpers for skill JS execution ────────────────────────────────────

class OrchestratorWebViewJavascriptInterface {
  var onResultListener: ((String) -> Unit)? = null

  @JavascriptInterface
  fun onResultReady(result: String) {
    onResultListener?.invoke(result)
  }
}

/** File-level singleton — mirrors AgentChatScreen so the same JS interface is always used. */
private val orchestratorChatViewJavascriptInterface = OrchestratorWebViewJavascriptInterface()

class OrchestratorWebViewClient(context: android.content.Context) : BaseGalleryWebViewClient(context) {
  private var onPageLoaded: (() -> Unit)? = null

  fun setPageLoadListener(listener: (() -> Unit)?) {
    onPageLoaded = listener
  }

  override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    onPageLoaded?.invoke()
  }
}
