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
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

private const val TAG = "AGSkillCreatorTools"

// Max size guard for generated skill content.
private const val MAX_SKILL_MD_BYTES = 32_768
private const val MAX_JS_BYTES = 512_000

/**
 * Specialist ToolSet that generates new skills (text-only or JavaScript) and immediately imports
 * them into the skill library via [SkillManagerViewModel].
 */
class SkillCreatorTools(
  private val context: Context,
  private val skillManagerViewModel: SkillManagerViewModel,
  private val onActionTaken: (OrchestratorAction) -> Unit,
) : ToolSet {

  @Tool(
    description =
      "Creates and immediately imports a text-only skill. " +
        "Use this for persona, role-play, or knowledge-injection skills that don't require JavaScript."
  )
  fun createTextSkill(
    @ToolParam(
      description =
        "Kebab-case skill name matching the folder naming convention (e.g. 'fitness-coach')."
    )
    name: String,
    @ToolParam(description = "One-sentence description shown in the skill selector.") description: String,
    @ToolParam(description = "Full skill instructions in Markdown (the body after the frontmatter).")
    instructions: String,
  ): Map<String, String> {
    Log.d(TAG, "createTextSkill: $name")
    val safeName = sanitizeSkillName(name) ?: return mapOf("error" to "Invalid skill name: '$name'. Use kebab-case with letters, digits, and hyphens only.")

    val mdContent = buildSkillMd(safeName, description, instructions)
    if (mdContent.toByteArray().size > MAX_SKILL_MD_BYTES) {
      return mapOf("error" to "Skill content too large (max ${MAX_SKILL_MD_BYTES} bytes).")
    }

    return importSkill(
      name = safeName,
      skillMdContent = mdContent,
      extraFiles = emptyMap(),
    )
  }

  @Tool(
    description =
      "Creates and immediately imports a JavaScript skill with a hidden WebView runner. " +
        "The skill will appear in the skill library and can be invoked via 'run_js'. " +
        "The JavaScript entry point must expose window['ai_edge_gallery_get_result'] = async (data) => { ... }."
  )
  fun createJsSkill(
    @ToolParam(
      description = "Kebab-case skill name (e.g. 'fetch-jokes')."
    )
    name: String,
    @ToolParam(description = "One-sentence description shown in the skill selector.") description: String,
    @ToolParam(
      description =
        "Instructions block instructing the LLM to call run_js with specific parameters."
    )
    instructions: String,
    @ToolParam(
      description =
        "Full HTML content for scripts/index.html. " +
          "Must define window['ai_edge_gallery_get_result'] = async (data) => { ... }."
    )
    indexHtmlContent: String,
  ): Map<String, String> {
    Log.d(TAG, "createJsSkill: $name")
    val safeName = sanitizeSkillName(name) ?: return mapOf("error" to "Invalid skill name: '$name'. Use kebab-case.")

    if (indexHtmlContent.toByteArray().size > MAX_JS_BYTES) {
      return mapOf("error" to "index.html content too large (max ${MAX_JS_BYTES} bytes).")
    }

    val mdContent = buildSkillMd(safeName, description, instructions)
    return importSkill(
      name = safeName,
      skillMdContent = mdContent,
      extraFiles = mapOf("scripts/index.html" to indexHtmlContent),
    )
  }

  @Tool(description = "Returns the list of all currently installed skills (name and description).")
  fun listAvailableSkills(): Map<String, Any> {
    val skills =
      skillManagerViewModel.uiState.value.skills.map { state ->
        mapOf(
          "name" to state.skill.name,
          "description" to state.skill.description,
          "builtIn" to state.skill.builtIn,
          "selected" to state.skill.selected,
        )
      }
    return mapOf("count" to skills.size, "skills" to skills)
  }

  @Tool(description = "Deletes a skill that was previously created by the skill creator.")
  fun deleteCreatedSkill(
    @ToolParam(description = "The exact name of the skill to delete.") skillName: String
  ): Map<String, String> {
    val state = skillManagerViewModel.uiState.value.skills.firstOrNull { it.skill.name == skillName }
    if (state == null) return mapOf("error" to "Skill not found: '$skillName'")
    if (state.skill.builtIn) return mapOf("error" to "Cannot delete built-in skills.")

    skillManagerViewModel.deleteSkill(skillName)
    Log.d(TAG, "Deleted skill: $skillName")
    return mapOf("result" to "success", "deleted" to skillName)
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  /**
   * Writes skill files to internal storage, parses SKILL.md, and calls
   * [SkillManagerViewModel.addSkill] to register the skill.
   */
  private fun importSkill(
    name: String,
    skillMdContent: String,
    extraFiles: Map<String, String>,
  ): Map<String, String> {
    // Check for name collision.
    if (skillManagerViewModel.uiState.value.skills.any { it.skill.name == name }) {
      return mapOf("error" to "A skill named '$name' already exists.")
    }

    // Destination: context.filesDir/skills/<name>/
    val normalizedDir = name.replace("\\s+".toRegex(), "-")
    val destDir = context.filesDir.resolve("skills/$normalizedDir")
    return try {
      destDir.mkdirs()

      // Write SKILL.md.
      destDir.resolve("SKILL.md").writeText(skillMdContent)

      // Write extra files (e.g. scripts/index.html).
      for ((relativePath, content) in extraFiles) {
        val file = destDir.resolve(relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
      }

      // Parse SKILL.md into proto.
      val (proto, errors) =
        skillManagerViewModel.convertSkillMdToProto(
          mdContent = skillMdContent,
          builtIn = false,
          selected = true,
          importDir = "skills/$normalizedDir",
        )

      if (errors.isNotEmpty() || proto == null) {
        destDir.deleteRecursively()
        return mapOf("error" to "Failed to parse generated SKILL.md: ${errors.joinToString()}")
      }

      // Attach the import directory so the skill can locate its assets.
      val importDirName = destDir.relativeTo(context.filesDir).path
      val skillWithDir = proto.toBuilder().setImportDirName(importDirName).build()

      skillManagerViewModel.addSkill(skill = skillWithDir, addToDataStore = true)
      onActionTaken(SkillCreatedAction(skillName = name))
      Log.d(TAG, "Skill '$name' created and imported.")

      mapOf("result" to "success", "skillName" to name)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create skill '$name'", e)
      destDir.deleteRecursively()
      mapOf("error" to (e.message ?: "Unknown error creating skill"))
    }
  }

  private fun buildSkillMd(name: String, description: String, instructions: String): String =
    """---
name: $name
description: $description
---

$instructions
""".trimIndent()

  /** Returns null if the name contains invalid characters. */
  private fun sanitizeSkillName(name: String): String? {
    val trimmed = name.trim().lowercase()
    return if (trimmed.matches(Regex("[a-z0-9][a-z0-9-]*"))) trimmed else null
  }
}
