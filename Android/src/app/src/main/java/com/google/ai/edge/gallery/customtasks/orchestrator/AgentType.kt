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

/** Enumeration of specialist agent types available to the planner. */
enum class AgentType(val id: String, val displayName: String, val description: String) {
  MOBILE_AGENT(
    id = "mobile_agent",
    displayName = "Mobile Agent",
    description =
      "Handles device actions: flashlight, contacts, calendar events, email, SMS, map, WiFi settings.",
  ),
  APP_LAUNCHER(
    id = "app_launcher",
    displayName = "App Launcher",
    description =
      "Lists installed apps, launches apps by name or package, and sends structured data to apps via Intents.",
  ),
  WORKSPACE_AGENT(
    id = "workspace_agent",
    displayName = "Workspace Agent",
    description =
      "Creates, reads, writes, lists, and deletes files and directories in the user-defined workspace folder.",
  ),
  SKILL_CREATOR(
    id = "skill_creator",
    displayName = "Skill Creator",
    description =
      "Generates and immediately imports new text-only or JavaScript skills into the skill library.",
  );

  companion object {
    fun fromId(id: String): AgentType? =
      entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
  }
}
