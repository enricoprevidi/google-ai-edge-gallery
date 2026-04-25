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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.ui.graphics.vector.ImageVector

/** Base class for all observable actions performed by the orchestrator and its specialists. */
abstract class OrchestratorAction(
  val icon: ImageVector,
  val label: String,
  val detail: String = "",
)

/** Records a dispatch from the planner to a specialist agent. */
class DispatchAction(
  val agentType: AgentType,
  val request: String,
  val result: String,
) :
  OrchestratorAction(
    icon = Icons.Outlined.SmartToy,
    label = "→ ${agentType.displayName}",
    detail = result,
  )

/** Records an app launch action. */
class LaunchAppAction(val packageName: String, val appName: String) :
  OrchestratorAction(
    icon = Icons.Outlined.Android,
    label = "Launched \"$appName\"",
    detail = packageName,
  )

/** Records sending an Intent to another app. */
class SendIntentAction(val packageName: String, val intentAction: String) :
  OrchestratorAction(
    icon = Icons.Outlined.Send,
    label = "Sent intent to $packageName",
    detail = intentAction,
  )

/** Records a file write in the workspace. */
class WorkspaceWriteAction(val filePath: String) :
  OrchestratorAction(
    icon = Icons.Outlined.Folder,
    label = "Wrote \"$filePath\"",
  )

/** Records a file read from the workspace. */
class WorkspaceReadAction(val filePath: String) :
  OrchestratorAction(
    icon = Icons.Outlined.Folder,
    label = "Read \"$filePath\"",
  )

/** Records creation of a new skill. */
class SkillCreatedAction(val skillName: String) :
  OrchestratorAction(
    icon = Icons.Outlined.Build,
    label = "Created skill \"$skillName\"",
  )

/** Records opening a URL or external resource. */
class OpenUrlAction(val url: String) :
  OrchestratorAction(
    icon = Icons.Outlined.OpenInNew,
    label = "Opened URL",
    detail = url,
  )

/** Lightweight action for generic mobile operations shown in the dispatch trace. */
class OrchestratorMobileAction(label: String) :
  OrchestratorAction(
    icon = Icons.Outlined.Smartphone,
    label = label,
  )
