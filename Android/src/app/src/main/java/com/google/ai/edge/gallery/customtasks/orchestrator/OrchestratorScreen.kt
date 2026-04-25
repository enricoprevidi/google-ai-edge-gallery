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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/**
 * Main screen for the multi-agent orchestrator.
 *
 * Provides a chat interface backed by the planner model and shows a collapsible dispatch-trace
 * panel that summarises what each specialist agent did.
 */
@Composable
fun OrchestratorScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  orchestratorTask: OrchestratorTask,
  orchestratorViewModel: OrchestratorViewModel = hiltViewModel(),
  skillManagerViewModel: SkillManagerViewModel = hiltViewModel(),
) {
  // Wire up skillManagerViewModel before any LaunchedEffect triggers model init.
  orchestratorTask.skillManagerViewModel = skillManagerViewModel

  val context = LocalContext.current
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val uiState by orchestratorViewModel.uiState.collectAsState()

  val currentModel = modelManagerUiState.selectedModel
  val initStatus = modelManagerUiState.modelInitializationStatus[currentModel.name]
  val isInitialized = initStatus?.status == ModelInitializationStatusType.INITIALIZED
  val isInitializing = initStatus?.status == ModelInitializationStatusType.INITIALIZING

  // SAF workspace folder picker.
  val workspacePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        // Persist permission so it survives app restarts.
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

  val listState = rememberLazyListState()

  // Auto-scroll to last message.
  LaunchedEffect(uiState.chatMessages.size, uiState.streamingResponse) {
    if (uiState.chatMessages.isNotEmpty()) {
      listState.animateScrollToItem(uiState.chatMessages.size - 1)
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
        Row {
          // Set workspace button.
          FilledTonalButton(
            onClick = { workspacePicker.launch(null) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp),
          ) {
            Icon(Icons.Outlined.Folder, contentDescription = "Set workspace", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Workspace", fontSize = 12.sp)
          }
          Spacer(modifier = Modifier.width(6.dp))
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
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text("Select and download a model to get started.", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              "Recommended: Gemma-4-E2B-IT (planner + specialist engines will share the same weights).",
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    if (isInitializing) {
      Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
      items(uiState.chatMessages) { msg ->
        ChatBubble(message = msg)
      }
      if (uiState.streamingResponse.isNotEmpty()) {
        item {
          ChatBubble(
            message = OrchestratorChatMessage(
              content = uiState.streamingResponse,
              isUser = false,
            ),
            isStreaming = true,
          )
        }
      }
    }

    // ── Input bar ────────────────────────────────────────────────────────────
    Surface(tonalElevation = 4.dp) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = inputText,
          onValueChange = { inputText = it },
          placeholder = {
            Text(
              if (!isInitialized) "Waiting for model…" else "Ask the orchestrator something…",
              fontSize = 13.sp,
            )
          },
          modifier = Modifier.weight(1f),
          enabled = isInitialized && !uiState.isProcessing,
          singleLine = false,
          maxLines = 4,
          keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.None),
          keyboardActions = KeyboardActions.Default,
          shape = RoundedCornerShape(12.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        IconButton(
          onClick = {
            val prompt = inputText.trim()
            if (prompt.isNotEmpty() && isInitialized && !uiState.isProcessing) {
              inputText = ""
              orchestratorViewModel.processUserPrompt(
                plannerModel = currentModel,
                userPrompt = prompt,
                onError = { /* TODO: show snackbar */ },
              )
            }
          },
          enabled = isInitialized && !uiState.isProcessing && inputText.isNotBlank(),
        ) {
          if (uiState.isProcessing) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
          } else {
            Icon(
              Icons.AutoMirrored.Outlined.Send,
              contentDescription = "Send",
              tint = if (inputText.isNotBlank() && isInitialized)
                MaterialTheme.colorScheme.primary
              else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
          }
        }
      }
    }
  }
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
