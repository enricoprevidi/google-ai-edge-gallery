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
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Per-model tool configuration for the V2 Multi-Agent Orchestrator.
 *
 * The user picks, in the Specialists bottom sheet, which tool groups are exposed to:
 *   - the **planner** model (one set), and
 *   - each **specialist model** for each [AgentType] dispatch slot.
 *
 * If nothing is configured, the orchestrator falls back to the historical hard-coded mappings
 * captured by [PlannerToolsConfig.defaultFor] and [SpecialistToolsConfig.defaultsFor].
 *
 * Persistence: SharedPreferences `orchestrator_prefs`, keys `v2_planner_tools` (a JSON object
 * with field `groups: [String]`) and `v2_specialist_tools` (a JSON object keyed by model name,
 * whose values are JSON objects keyed by [AgentType.id] with `[String]` group lists).
 */

private const val PREFS = "orchestrator_prefs"
private const val KEY_PLANNER_TOOLS = "v2_planner_tools"
private const val KEY_SPECIALIST_TOOLS = "v2_specialist_tools"
private const val TAG = "AGToolsConfig"

/**
 * The user-selectable tool groups. Each group corresponds to one [com.google.ai.edge.litertlm.ToolSet]
 * implementation already present in the orchestrator package.
 */
enum class ToolGroup(val displayName: String, val description: String) {
  WORKSPACE(
    displayName = "Workspace",
    description = "listFiles, readFile, writeFile, createDirectory, deleteFile",
  ),
  SKILL_CREATOR(
    displayName = "Skill Creator",
    description = "createTextSkill, createJsSkill, listAvailableSkills",
  ),
  MOBILE_ACTIONS(
    displayName = "Mobile Actions",
    description = "turnOnFlashlight, turnOffFlashlight, createContact, sendEmail, sendSms, openMap, ...",
  ),
  SKILL_EXEC(
    displayName = "Skill Execution",
    description = "load_skill, run_js (execute installed skills)",
  ),
  MOBILE_AGENT(
    displayName = "Mobile Agent",
    description = "Specialist mobile-control surface incl. flashMorseCode",
  ),
  APP_LAUNCHER(
    displayName = "App Launcher",
    description = "listInstalledApps, launchApp, sendIntent",
  ),
  DISPATCH(
    displayName = "Dispatch to Specialist",
    description = "dispatchToAgent — delegate sub-tasks to specialist models",
  ),
}

/**
 * Tool configuration for the planner model.
 *
 * The default surface ([defaultFor]) mirrors today's hard-coded behaviour in
 * [OrchestratorV2Task.initializeModelFn]: workspace, skill creator, mobile actions and skill
 * execution always; dispatch only when the pool actually contains specialists.
 */
data class PlannerToolsConfig(val groups: Set<ToolGroup>) {
  fun toJson(): String {
    val obj = JsonObject()
    val arr = com.google.gson.JsonArray()
    groups.forEach { arr.add(it.name) }
    obj.add("groups", arr)
    return obj.toString()
  }

  companion object {
    /** Tool groups that make sense for the planner. */
    val VALID: Set<ToolGroup> = setOf(
      ToolGroup.WORKSPACE,
      ToolGroup.SKILL_CREATOR,
      ToolGroup.MOBILE_ACTIONS,
      ToolGroup.SKILL_EXEC,
      ToolGroup.DISPATCH,
    )

    /**
     * The historical default tool surface. [poolHasSpecialists] controls whether [ToolGroup.DISPATCH]
     * is included — exactly the same conditional as the existing code in `OrchestratorV2TaskModule`.
     */
    fun defaultFor(poolHasSpecialists: Boolean): PlannerToolsConfig {
      val base = mutableSetOf(
        ToolGroup.WORKSPACE,
        ToolGroup.SKILL_CREATOR,
        ToolGroup.MOBILE_ACTIONS,
        ToolGroup.SKILL_EXEC,
      )
      if (poolHasSpecialists) base.add(ToolGroup.DISPATCH)
      return PlannerToolsConfig(base)
    }

    fun fromJson(json: String?): PlannerToolsConfig? {
      if (json.isNullOrBlank()) return null
      return try {
        val obj = JsonParser.parseString(json).asJsonObject
        val arr = obj.getAsJsonArray("groups") ?: return null
        val set = mutableSetOf<ToolGroup>()
        for (e in arr) {
          val name = e.asString
          runCatching { ToolGroup.valueOf(name) }.getOrNull()?.let { set.add(it) }
        }
        PlannerToolsConfig(set)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to parse planner tools config: $json", t)
        null
      }
    }
  }
}

/**
 * Tool configuration for a single specialist model, broken down per [AgentType] dispatch slot.
 *
 * If a slot is missing from [perAgentType], callers should fall back to [defaultsFor]. This means
 * users that haven't touched the new UI continue to see the exact same behaviour they have today.
 */
data class SpecialistToolsConfig(val perAgentType: Map<AgentType, Set<ToolGroup>>) {
  fun toJson(): String {
    val obj = JsonObject()
    for ((agentType, groups) in perAgentType) {
      val arr = com.google.gson.JsonArray()
      groups.forEach { arr.add(it.name) }
      obj.add(agentType.id, arr)
    }
    return obj.toString()
  }

  /** Returns the configured groups for [agentType], or [defaultsFor] if unset. */
  fun groupsFor(agentType: AgentType): Set<ToolGroup> =
    perAgentType[agentType] ?: defaultsFor(agentType)

  companion object {
    /**
     * Tool groups that can be assigned to a specialist slot. Every non-[ToolGroup.DISPATCH] group
     * is valid for every [AgentType] — the [AgentType] only drives the *default* selection and the
     * dispatch routing, not which tools the user is allowed to attach to that slot.
     */
    fun validFor(@Suppress("UNUSED_PARAMETER") agentType: AgentType): Set<ToolGroup> =
      ToolGroup.values().toSet() - ToolGroup.DISPATCH

    /**
     * Historical defaults for each [AgentType] specialist slot — matches the hard-coded mapping
     * in [PlannerTools.buildSpecialistConfig].
     */
    fun defaultsFor(agentType: AgentType): Set<ToolGroup> = when (agentType) {
      AgentType.MOBILE_AGENT -> setOf(ToolGroup.MOBILE_AGENT)
      AgentType.APP_LAUNCHER -> setOf(ToolGroup.APP_LAUNCHER)
      AgentType.WORKSPACE_AGENT -> setOf(ToolGroup.WORKSPACE)
      AgentType.SKILL_CREATOR -> setOf(ToolGroup.SKILL_CREATOR)
      AgentType.SKILL_AGENT -> setOf(ToolGroup.SKILL_EXEC)
    }

    /** Configuration that exactly mirrors the historical defaults across all agent types. */
    fun allDefaults(): SpecialistToolsConfig =
      SpecialistToolsConfig(AgentType.values().associateWith { defaultsFor(it) })

    fun fromJson(json: String?): SpecialistToolsConfig? {
      if (json.isNullOrBlank()) return null
      return try {
        val obj = JsonParser.parseString(json).asJsonObject
        val map = mutableMapOf<AgentType, Set<ToolGroup>>()
        for ((key, value) in obj.entrySet()) {
          val agentType = AgentType.fromId(key) ?: continue
          val arr = value.asJsonArray
          val groups = mutableSetOf<ToolGroup>()
          for (e in arr) {
            runCatching { ToolGroup.valueOf(e.asString) }.getOrNull()?.let { groups.add(it) }
          }
          map[agentType] = groups
        }
        SpecialistToolsConfig(map)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to parse specialist tools config: $json", t)
        null
      }
    }
  }
}

/** Static load/save helpers for the two SharedPreferences keys. */
object OrchestratorToolsConfig {
  fun loadPlanner(context: Context): PlannerToolsConfig? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return PlannerToolsConfig.fromJson(prefs.getString(KEY_PLANNER_TOOLS, null))
  }

  fun savePlanner(context: Context, config: PlannerToolsConfig) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_PLANNER_TOOLS, config.toJson()).apply()
  }

  fun clearPlanner(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.edit().remove(KEY_PLANNER_TOOLS).apply()
  }

  /** Loads the per-model specialist tool config, or null if no configuration exists yet. */
  fun loadSpecialist(context: Context, modelName: String): SpecialistToolsConfig? {
    val map = loadAllSpecialists(context)
    return map[modelName]
  }

  fun loadAllSpecialists(context: Context): Map<String, SpecialistToolsConfig> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_SPECIALIST_TOOLS, null) ?: return emptyMap()
    return try {
      val obj = JsonParser.parseString(raw).asJsonObject
      val out = mutableMapOf<String, SpecialistToolsConfig>()
      for ((modelName, value) in obj.entrySet()) {
        val cfg = SpecialistToolsConfig.fromJson(value.toString()) ?: continue
        out[modelName] = cfg
      }
      out
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to parse specialist tools map: $raw", t)
      emptyMap()
    }
  }

  fun saveAllSpecialists(context: Context, configs: Map<String, SpecialistToolsConfig>) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val obj = JsonObject()
    for ((modelName, cfg) in configs) {
      obj.add(modelName, JsonParser.parseString(cfg.toJson()))
    }
    prefs.edit().putString(KEY_SPECIALIST_TOOLS, Gson().toJson(obj)).apply()
  }
}
