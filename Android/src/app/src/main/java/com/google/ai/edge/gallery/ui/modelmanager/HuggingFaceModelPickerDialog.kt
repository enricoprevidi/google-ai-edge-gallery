/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.modelmanager

import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGHuggingFacePicker"
private const val HF_API_BASE = "https://huggingface.co/api/models"

// ─── HuggingFace API response data classes ───────────────────────────────────

data class HFSibling(
  @SerializedName("rfilename") val rfilename: String,
  @SerializedName("size") val size: Long? = null,
)

data class HFModelApiResponse(
  @SerializedName("id") val id: String?,
  @SerializedName("sha") val sha: String?,
  @SerializedName("private") val isPrivate: Boolean?,
  @SerializedName("siblings") val siblings: List<HFSibling>?,
)

/** Lightweight summary returned by the /api/models?search=... endpoint. */
data class HFModelSummary(
  @SerializedName("id") val id: String?,
  @SerializedName("modelId") val modelId: String?,
  @SerializedName("downloads") val downloads: Long? = null,
  @SerializedName("likes") val likes: Long? = null,
)

// ─── Selection result passed back to caller ───────────────────────────────────

data class HuggingFaceModelSelection(
  val displayName: String,
  val modelId: String,
  val sha: String,
  val modelFile: String,
  val sizeInBytes: Long,
  val accelerators: List<Accelerator>,
)

// ─── Internal state ───────────────────────────────────────────────────────────

private sealed interface SearchState {
  object Idle : SearchState
  object Loading : SearchState
  data class Results(val models: List<HFModelSummary>) : SearchState
  data class Success(val response: HFModelApiResponse, val files: List<HFSibling>) : SearchState
  data class Error(val message: String) : SearchState
}

// ─── Main composable ──────────────────────────────────────────────────────────

@Composable
fun HuggingFaceModelPickerDialog(
  onDismiss: () -> Unit,
  onModelSelected: (HuggingFaceModelSelection) -> Unit,
  initialAuthors: List<String> = listOf("litert-community", "google"),
  initialSearchAll: Boolean = false,
  onPreferencesChanged: (authors: List<String>, searchAll: Boolean) -> Unit = { _, _ -> },
) {
  val scope = rememberCoroutineScope()
  var inputText by remember { mutableStateOf("") }
  var searchState by remember { mutableStateOf<SearchState>(SearchState.Idle) }
  var selectedFile by remember { mutableStateOf<HFSibling?>(null) }
  // Accelerators selectable for the new model. CPU is checked by default because
  // many community-converted models do not run correctly on GPU. Order is fixed
  // (CPU/GPU/NPU); the first checked accelerator becomes the runtime default.
  var cpuEnabled by remember { mutableStateOf(true) }
  var gpuEnabled by remember { mutableStateOf(false) }
  var npuEnabled by remember { mutableStateOf(false) }
  var authors by remember { mutableStateOf(initialAuthors) }
  var searchAll by remember { mutableStateOf(initialSearchAll) }
  var showSettings by remember { mutableStateOf(false) }
  var newAuthorInput by remember { mutableStateOf("") }

  fun extractModelId(raw: String): String {
    // Accept full URL like https://huggingface.co/litert-community/Qwen3-0.6B
    // or just owner/repo
    val trimmed = raw.trim()
    return if (trimmed.startsWith("https://huggingface.co/")) {
      trimmed.removePrefix("https://huggingface.co/").split("?").first().trimEnd('/')
    } else if (trimmed.startsWith("http://huggingface.co/")) {
      trimmed.removePrefix("http://huggingface.co/").split("?").first().trimEnd('/')
    } else {
      trimmed.split("?").first().trimEnd('/')
    }
  }

  /** Loads the file list for a specific modelId and transitions state. */
  fun loadModelFiles(modelId: String) {
    searchState = SearchState.Loading
    selectedFile = null
    scope.launch {
      val result = withContext(Dispatchers.IO) { fetchHuggingFaceModel(modelId) }
      if (result == null) {
        searchState = SearchState.Error(
          "Model not found or network error. Make sure the repo is public and contains .litertlm or .task files."
        )
      } else if (result.isPrivate == true) {
        searchState = SearchState.Error("This repository is private. Only public models are supported.")
      } else {
        val files = result.siblings
          ?.filter { sib ->
            (sib.rfilename.endsWith(".litertlm") || sib.rfilename.endsWith(".task")) &&
              !sib.rfilename.lowercase().contains("-web")
          }
          ?: emptyList()
        if (files.isEmpty()) {
          searchState = SearchState.Error("No .litertlm or .task files found in this repository.")
        } else {
          searchState = SearchState.Success(result, files)
          selectedFile = files.firstOrNull { it.rfilename.endsWith(".litertlm") } ?: files.first()
        }
      }
    }
  }

  fun performSearch() {
    val raw = inputText.trim()
    if (raw.isBlank()) {
      searchState = SearchState.Error("Enter a model name, owner/model ID, or HuggingFace URL.")
      return
    }
    // Direct fetch path: full URL or owner/repo input.
    val looksLikeId = raw.startsWith("https://huggingface.co/") ||
      raw.startsWith("http://huggingface.co/") ||
      raw.contains("/")
    if (looksLikeId) {
      val modelId = extractModelId(raw)
      if (modelId.isBlank() || !modelId.contains("/")) {
        searchState = SearchState.Error("Invalid model ID. Use format: owner/model-name")
        return
      }
      loadModelFiles(modelId)
      return
    }

    // Keyword search path.
    if (!searchAll && authors.isEmpty()) {
      searchState = SearchState.Error(
        "Add at least one author or enable Search all of HuggingFace."
      )
      return
    }
    searchState = SearchState.Loading
    selectedFile = null
    scope.launch {
      val results = withContext(Dispatchers.IO) {
        searchHuggingFaceModels(query = raw, authors = authors, searchAll = searchAll)
      }
      when {
        results.isEmpty() -> {
          searchState = SearchState.Error(
            if (searchAll) "No models match \"$raw\" on HuggingFace."
            else "No compatible models match \"$raw\" for: ${authors.joinToString(", ")}."
          )
        }
        results.size == 1 -> {
          val id = results.first().id ?: results.first().modelId
          if (id.isNullOrBlank()) {
            searchState = SearchState.Error("Search returned a result without an id.")
          } else {
            loadModelFiles(id)
          }
        }
        else -> {
          searchState = SearchState.Results(results)
        }
      }
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(0.95f)
        .padding(vertical = 24.dp),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier
          .padding(20.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {

        // Title
        Text("Add from HuggingFace", style = MaterialTheme.typography.titleLarge)
        Text(
          "Search by name (e.g. \"qwen 3.5\") or paste a HuggingFace model ID / URL.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Input row
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("Search or model ID") },
            placeholder = { Text("e.g. qwen 3.5 or litert-community/Qwen3-0.6B") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          IconButton(
            onClick = { performSearch() },
            enabled = searchState !is SearchState.Loading,
          ) {
            Icon(Icons.Rounded.Search, contentDescription = "Search")
          }
        }

        // Search settings (collapsible)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { showSettings = !showSettings }
            .padding(vertical = 4.dp),
        ) {
          Icon(
            if (showSettings) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
          Text(
            "Search settings",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.weight(1f))
          if (searchAll) {
            Text(
              "All HF",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.tertiary,
            )
          } else {
            Text(
              "${authors.size} author${if (authors.size == 1) "" else "s"}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        if (showSettings) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
              .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            // Search-all toggle
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Search all of HuggingFace", style = MaterialTheme.typography.bodyMedium)
                Text(
                  "Models outside the author list may not load.",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = searchAll,
                onCheckedChange = {
                  searchAll = it
                  onPreferencesChanged(authors, searchAll)
                },
              )
            }

            // Author list editor
            Text(
              "Authors to search",
              style = MaterialTheme.typography.labelMedium,
              color = if (searchAll) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface,
            )
            if (authors.isEmpty()) {
              Text(
                "(none — search disabled unless \"Search all\" is on)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .horizontalScroll(rememberScrollState()),
              ) {
                authors.forEach { author ->
                  androidx.compose.material3.AssistChip(
                    onClick = {
                      authors = authors - author
                      onPreferencesChanged(authors, searchAll)
                    },
                    label = { Text(author) },
                    trailingIcon = {
                      Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Remove $author",
                        modifier = Modifier.size(16.dp),
                      )
                    },
                    enabled = !searchAll,
                  )
                }
              }
            }
            // Add new author row
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              OutlinedTextField(
                value = newAuthorInput,
                onValueChange = { newAuthorInput = it },
                label = { Text("Add author") },
                placeholder = { Text("e.g. kaggle") },
                singleLine = true,
                enabled = !searchAll,
                modifier = Modifier.weight(1f),
              )
              TextButton(
                onClick = {
                  val cleaned = newAuthorInput.trim().lowercase()
                  // HF authors: lowercase letters/digits/hyphens (3-39 chars).
                  if (cleaned.isNotEmpty() &&
                    cleaned.matches(Regex("^[a-z0-9][a-z0-9-]{1,38}$")) &&
                    cleaned !in authors
                  ) {
                    authors = authors + cleaned
                    onPreferencesChanged(authors, searchAll)
                    newAuthorInput = ""
                  }
                },
                enabled = !searchAll && newAuthorInput.trim().isNotEmpty(),
              ) {
                Text("Add")
              }
            }
          }
        }

        // State display
        when (val state = searchState) {
          is SearchState.Idle -> {
            Text(
              "Tip: models must be in LiteRT-LM (.litertlm) or MediaPipe (.task) format. " +
                "By default, search is scoped to litert-community and google.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          is SearchState.Loading -> {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(modifier = Modifier.size(36.dp))
            }
          }

          is SearchState.Error -> {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Icon(
                Icons.Rounded.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
              )
              Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
          }

          is SearchState.Results -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                "${state.models.size} results — tap to load files",
                style = MaterialTheme.typography.labelMedium,
              )
              TextButton(onClick = { searchState = SearchState.Idle }) { Text("Clear") }
            }
            if (searchAll) {
              Text(
                "Note: results may include models without .litertlm/.task files.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
              )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              state.models.forEach { summary ->
                val id = summary.id ?: summary.modelId ?: return@forEach
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { loadModelFiles(id) }
                    .border(
                      1.dp,
                      MaterialTheme.colorScheme.outlineVariant,
                      RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      id,
                      style = MaterialTheme.typography.bodySmall,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                    )
                    val meta = buildList {
                      summary.downloads?.let { add("↓ $it") }
                      summary.likes?.let { add("♥ $it") }
                    }.joinToString("   ")
                    if (meta.isNotEmpty()) {
                      Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                }
              }
            }
          }

          is SearchState.Success -> {
            // Model info
            val modelId = state.response.id ?: extractModelId(inputText)
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .border(
                  1.dp,
                  MaterialTheme.colorScheme.outlineVariant,
                  RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Icon(
                  Icons.Rounded.CheckCircle,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(16.dp),
                )
                Text(
                  modelId,
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
              Text(
                "Commit: ${state.response.sha?.take(8) ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
              )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // File list
            Text(
              "Select model file (${state.files.size} found):",
              style = MaterialTheme.typography.labelMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              state.files.forEach { sib ->
                val isSelected = selectedFile == sib
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedFile = sib }
                    .border(
                      if (isSelected) 2.dp else 1.dp,
                      if (isSelected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant,
                      RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                  RadioButton(selected = isSelected, onClick = { selectedFile = sib })
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      sib.rfilename,
                      style = MaterialTheme.typography.bodySmall,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                    )
                    if (sib.size != null && sib.size > 0) {
                      Text(
                        sib.size.humanReadableSize(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                }
              }
            }

            // Accelerators selection
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              "Accelerators (first checked = default):",
              style = MaterialTheme.typography.labelMedium,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = cpuEnabled, onCheckedChange = { cpuEnabled = it })
                Text("CPU", style = MaterialTheme.typography.bodySmall)
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = gpuEnabled, onCheckedChange = { gpuEnabled = it })
                Text("GPU", style = MaterialTheme.typography.bodySmall)
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = npuEnabled, onCheckedChange = { npuEnabled = it })
                Text("NPU", style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }

        // Button row
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = onDismiss) { Text("Cancel") }
          Button(
            onClick = {
              val state = searchState
              val file = selectedFile
              if (state is SearchState.Success && file != null) {
                val modelId = state.response.id ?: extractModelId(inputText)
                val sha = state.response.sha ?: ""
                // Derive a clean display name from modelId (e.g., "Qwen3-0.6B" from owner/Qwen3-0.6B)
                val displayName = modelId.substringAfterLast("/")
                val accelerators = buildList {
                  if (cpuEnabled) add(Accelerator.CPU)
                  if (gpuEnabled) add(Accelerator.GPU)
                  if (npuEnabled) add(Accelerator.NPU)
                }
                onModelSelected(
                  HuggingFaceModelSelection(
                    displayName = displayName,
                    modelId = modelId,
                    sha = sha,
                    modelFile = file.rfilename,
                    sizeInBytes = file.size ?: 0L,
                    accelerators = accelerators,
                  )
                )
              }
            },
            enabled = searchState is SearchState.Success && selectedFile != null &&
              (cpuEnabled || gpuEnabled || npuEnabled),
          ) {
            Text("Add Model")
          }
        }
      }
    }
  }
}

// ─── HF API call (runs on IO dispatcher) ─────────────────────────────────────

private fun fetchHuggingFaceModel(modelId: String): HFModelApiResponse? {
  return try {
    val url = "$HF_API_BASE/$modelId?full=true"
    Log.d(TAG, "Fetching HF model info: $url")
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.connect()
    val code = connection.responseCode
    if (code == HttpURLConnection.HTTP_OK) {
      val body = connection.inputStream.bufferedReader().readText()
      Gson().fromJson(body, HFModelApiResponse::class.java)
    } else {
      Log.w(TAG, "HF API returned HTTP $code for $modelId")
      null
    }
  } catch (e: Exception) {
    Log.e(TAG, "Error fetching HF model info for $modelId", e)
    null
  }
}

/**
 * Calls the HuggingFace `/api/models?search=...` endpoint, optionally restricted to one or more
 * authors. When `searchAll == true`, the author filter is omitted. Authors are queried in parallel
 * and the results are merged + de-duplicated by id.
 */
private suspend fun searchHuggingFaceModels(
  query: String,
  authors: List<String>,
  searchAll: Boolean,
): List<HFModelSummary> {
  val encoded = URLEncoder.encode(query, "UTF-8")
  val urls: List<String> = if (searchAll) {
    listOf("$HF_API_BASE?search=$encoded&limit=30")
  } else {
    authors.map { author ->
      val a = URLEncoder.encode(author, "UTF-8")
      "$HF_API_BASE?search=$encoded&author=$a&limit=15"
    }
  }
  return coroutineScope {
    urls
      .map { url -> async(Dispatchers.IO) { fetchHFModelList(url) } }
      .awaitAll()
      .flatten()
      .distinctBy { it.id ?: it.modelId ?: "" }
      .filter { !(it.id ?: it.modelId).isNullOrBlank() }
  }
}

private fun fetchHFModelList(url: String): List<HFModelSummary> {
  return try {
    Log.d(TAG, "Searching HF: $url")
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.instanceFollowRedirects = true
    connection.connect()
    val code = connection.responseCode
    if (code == HttpURLConnection.HTTP_OK) {
      val body = connection.inputStream.bufferedReader().readText()
      val arrayType = object : com.google.gson.reflect.TypeToken<List<HFModelSummary>>() {}.type
      Gson().fromJson<List<HFModelSummary>>(body, arrayType) ?: emptyList()
    } else {
      Log.w(TAG, "HF search returned HTTP $code for $url")
      emptyList()
    }
  } catch (e: Exception) {
    Log.e(TAG, "Error searching HF: $url", e)
    emptyList()
  }
}
