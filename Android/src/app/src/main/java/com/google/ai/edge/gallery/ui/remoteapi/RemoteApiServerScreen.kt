/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.ui.remoteapi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.service.RemoteApiPrefs
import com.google.ai.edge.gallery.service.RemoteApiServerHolder
import com.google.ai.edge.gallery.service.RemoteApiServerService
import com.google.ai.edge.gallery.service.RemoteApiServerStatus
import com.google.ai.edge.gallery.service.lanIpv4
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteApiServerScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onBackClicked: () -> Unit,
) {
  val context = LocalContext.current
  val mmUiState by modelManagerViewModel.uiState.collectAsState()
  val status by RemoteApiServerHolder.status.collectAsState()

  // Mutable config (local UI state, persisted on changes/start).
  var cfg by remember { mutableStateOf(RemoteApiPrefs.read(context)) }

  // Refreshable LAN IP.
  var lanIp by remember { mutableStateOf(lanIpv4(context) ?: "—") }
  val scope = rememberCoroutineScope()

  // Discoverable downloaded LLM models.
  val downloadedModels = remember(mmUiState) {
    mmUiState.tasks.flatMap { it.models }
      .filter { mmUiState.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED }
      .distinctBy { it.name }
      .sortedBy { it.displayName.ifEmpty { it.name } }
  }

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("Remote API Server") },
        navigationIcon = {
          IconButton(onClick = onBackClicked) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

      // ── Status card ─────────────────────────────────────────────────
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when (status) {
              is RemoteApiServerStatus.Running -> Color(0xFF2E7D32)
              is RemoteApiServerStatus.Starting -> Color(0xFFF9A825)
              is RemoteApiServerStatus.Error -> Color(0xFFC62828)
              else -> Color.Gray
            }
            Box(
              modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = when (val s = status) {
                is RemoteApiServerStatus.Running -> "Running"
                is RemoteApiServerStatus.Starting -> "Starting (${s.modelName})…"
                is RemoteApiServerStatus.Error -> "Error"
                else -> "Stopped"
              },
              style = MaterialTheme.typography.titleMedium,
            )
          }

          when (val s = status) {
            is RemoteApiServerStatus.Running -> {
              val url = "http://${s.ip}:${s.port}/v1"
              CopyRow(label = "Base URL", value = url, context = context)
              Text(
                text = "Model: ${s.modelName}",
                style = MaterialTheme.typography.bodyMedium,
              )
            }
            is RemoteApiServerStatus.Error -> {
              Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            else -> {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  text = "Wi-Fi IP: $lanIp",
                  style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { lanIp = lanIpv4(context) ?: "—" }) {
                  Icon(Icons.Rounded.Refresh, contentDescription = "Refresh IP")
                }
              }
            }
          }

          Spacer(Modifier.height(4.dp))
          val isRunning = status is RemoteApiServerStatus.Running ||
            status is RemoteApiServerStatus.Starting
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = {
                RemoteApiPrefs.write(context, cfg)
                RemoteApiServerService.start(context)
              },
              enabled = !isRunning && cfg.modelName.isNotBlank() && cfg.port in 1024..65535,
            ) { Text("Start") }
            OutlinedButton(
              onClick = { RemoteApiServerService.stop(context) },
              enabled = isRunning,
            ) { Text("Stop") }
          }
        }
      }

      // ── Configuration card ──────────────────────────────────────────
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text("Configuration", style = MaterialTheme.typography.titleMedium)

          ModelDropdown(
            selected = cfg.modelName,
            options = downloadedModels.map { it.name },
            onSelect = {
              cfg = cfg.copy(modelName = it)
              RemoteApiPrefs.write(context, cfg)
            },
          )

          OutlinedTextField(
            value = cfg.port.toString(),
            onValueChange = { v ->
              val n = v.toIntOrNull() ?: return@OutlinedTextField
              cfg = cfg.copy(port = n)
            },
            label = { Text("Port (1024-65535)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          OutlinedTextField(
            value = cfg.maxTokens.toString(),
            onValueChange = { v ->
              val n = v.toIntOrNull() ?: return@OutlinedTextField
              cfg = cfg.copy(maxTokens = n)
            },
            label = { Text("Max output tokens (advisory)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Require API token", style = MaterialTheme.typography.bodyLarge)
              Text(
                "Clients must send Authorization: Bearer <token>",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = cfg.requireToken,
              onCheckedChange = {
                cfg = cfg.copy(requireToken = it)
                RemoteApiPrefs.write(context, cfg)
              },
            )
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text("API Token", style = MaterialTheme.typography.bodyLarge)
              Text(
                cfg.apiToken,
                style = MaterialTheme.typography.bodySmall.copy(
                  fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(onClick = { copyToClipboard(context, "api_token", cfg.apiToken) }) {
              Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy token")
            }
            IconButton(onClick = {
              val newToken = UUID.randomUUID().toString().replace("-", "")
              cfg = cfg.copy(apiToken = newToken)
              RemoteApiPrefs.write(context, cfg)
            }) {
              Icon(Icons.Rounded.Refresh, contentDescription = "Rotate token")
            }
          }
        }
      }

      // ── Usage hint ─────────────────────────────────────────────────
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            "How to use from your laptop",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          Text(
            "Configure VS Code (Continue.dev) with provider=openai, " +
              "apiBase=http://<phone-ip>:${cfg.port}/v1, model=${cfg.modelName.ifBlank { "<select above>" }}, " +
              "and apiKey=<token above>. Phone and laptop must be on the same Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            "Test with:\n" +
              "curl http://<phone-ip>:${cfg.port}/v1/models -H \"Authorization: Bearer <token>\"",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
  selected: String,
  options: List<String>,
  onSelect: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = !expanded },
  ) {
    OutlinedTextField(
      value = selected.ifBlank { "Select a model" },
      onValueChange = {},
      readOnly = true,
      label = { Text("Model") },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier
        .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
        .fillMaxWidth(),
    )
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      modifier = Modifier.fillMaxWidth(0.9f),
    ) {
      if (options.isEmpty()) {
        DropdownMenuItem(
          text = { Text("No downloaded LLM models") },
          onClick = { expanded = false },
          enabled = false,
        )
      } else {
        options.forEach { name ->
          DropdownMenuItem(
            text = { Text(name) },
            onClick = { onSelect(name); expanded = false },
          )
        }
      }
    }
  }
}

@Composable
private fun CopyRow(label: String, value: String, context: Context) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.labelMedium)
      Text(
        value,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
      )
    }
    IconButton(onClick = { copyToClipboard(context, label, value) }) {
      Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy")
    }
  }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText(label, value))
}
