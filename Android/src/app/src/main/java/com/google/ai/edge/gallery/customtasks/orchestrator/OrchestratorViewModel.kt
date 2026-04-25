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

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGOrchestratorVM"

/** A single chat message in the orchestrator conversation. */
data class OrchestratorChatMessage(
  val content: String,
  val isUser: Boolean,
)

/** UI state for the orchestrator screen. */
data class OrchestratorUiState(
  val chatMessages: List<OrchestratorChatMessage> = emptyList(),
  val streamingResponse: String = "",
  val isProcessing: Boolean = false,
  val workspaceUri: String = "",
  val lowMemoryWarning: Boolean = false,
)

@HiltViewModel
class OrchestratorViewModel
@Inject
constructor(@ApplicationContext private val appContext: Context) : ViewModel() {

  private val _uiState = MutableStateFlow(OrchestratorUiState())
  val uiState = _uiState.asStateFlow()

  private val prefs by lazy {
    appContext.getSharedPreferences("orchestrator_prefs", Context.MODE_PRIVATE)
  }

  init {
    loadWorkspaceUri()
  }

  fun loadWorkspaceUri() {
    val uri = prefs.getString("workspace_uri", "") ?: ""
    _uiState.update { it.copy(workspaceUri = uri) }
  }

  fun saveWorkspaceUri(uri: String) {
    prefs.edit().putString("workspace_uri", uri).apply()
    _uiState.update { it.copy(workspaceUri = uri) }
  }

  fun getWorkspaceUri(): String = _uiState.value.workspaceUri

  /**
   * Checks whether loading [totalModelBytes] bytes of model weights is safe given the current
   * available RAM. Emits a warning into the UI state if the combined footprint would exceed 75% of
   * available memory.
   */
  fun checkMemoryBudget(totalModelBytes: Long) {
    val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    val threshold = (info.availMem * 0.75).toLong()
    Log.d(TAG, "Memory check: models=$totalModelBytes availMem=${info.availMem} threshold=$threshold")
    if (totalModelBytes > threshold) {
      _uiState.update { it.copy(lowMemoryWarning = true) }
    }
  }

  fun dismissLowMemoryWarning() {
    _uiState.update { it.copy(lowMemoryWarning = false) }
  }

  fun clearChat() {
    _uiState.update { it.copy(chatMessages = emptyList(), streamingResponse = "") }
  }

  /**
   * Sends [userPrompt] to the planner model and streams the response into [streamingResponse],
   * then commits it to [chatMessages] on completion.
   *
   * The planner model's [PlannerTools] handles [dispatchToAgent] calls autonomously during
   * inference — no extra wiring is required here.
   */
  fun processUserPrompt(
    plannerModel: Model,
    userPrompt: String,
    onError: (String) -> Unit,
  ) {
    if (plannerModel.instance == null) {
      onError("Model not initialized yet.")
      return
    }

    viewModelScope.launch(Dispatchers.Default) {
      _uiState.update {
        it.copy(
          isProcessing = true,
          streamingResponse = "",
          chatMessages = it.chatMessages + OrchestratorChatMessage(userPrompt, isUser = true),
        )
      }

      val instance = plannerModel.instance as? LlmModelInstance
      if (instance == null) {
        _uiState.update { it.copy(isProcessing = false) }
        onError("Model instance not available.")
        return@launch
      }

      val conversation = instance.conversation
      val contents = Contents.of(listOf(Content.Text(userPrompt)))
      val responseBuilder = StringBuilder()

      conversation
        .sendMessageAsync(contents)
        .catch { e ->
          Log.e(TAG, "Planner inference error", e)
          onError(e.message ?: "Unknown inference error")
        }
        .onCompletion {
          val finalResponse = responseBuilder.toString()
          _uiState.update { state ->
            state.copy(
              isProcessing = false,
              streamingResponse = "",
              chatMessages =
                state.chatMessages + OrchestratorChatMessage(finalResponse, isUser = false),
            )
          }
        }
        .collect { message ->
          responseBuilder.append(message.toString())
          _uiState.update { it.copy(streamingResponse = responseBuilder.toString()) }
        }
    }
  }

  /**
   * Resets the planner conversation (keeps the engine loaded; only the conversation context is
   * cleared). Pass the same [systemInstruction] and [tools] that were used at initialization.
   */
  fun resetPlannerConversation(
    plannerModel: Model,
    systemInstruction: Contents?,
    tools: List<com.google.ai.edge.litertlm.ToolProvider>,
  ) {
    LlmChatModelHelper.resetConversation(
      model = plannerModel,
      supportImage = false,
      supportAudio = false,
      systemInstruction = systemInstruction,
      tools = tools,
      enableConversationConstrainedDecoding = false,
    )
    clearChat()
  }
}
