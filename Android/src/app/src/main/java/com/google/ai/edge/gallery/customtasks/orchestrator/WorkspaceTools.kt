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
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

private const val TAG = "AGWorkspaceTools"

/**
 * Specialist ToolSet for file operations on a user-granted Storage Access Framework (SAF) folder.
 *
 * The workspace URI must be persisted externally (e.g. SharedPreferences) and passed here at
 * construction time. If [workspaceUri] is null, all operations return an error instructing the user
 * to set the workspace via the UI.
 *
 * [onFileRead] / [onFileWritten] are invoked with the relative path on success so hosts can wire
 * progress events to whatever sink they use (orchestrator action stream, AgentTools channel, etc.).
 *
 * Security: All relative paths are validated to prevent directory traversal attacks.
 */
class WorkspaceTools(
  private val context: Context,
  private val workspaceUriProvider: () -> String?,
  private val onFileRead: (relativePath: String) -> Unit = {},
  private val onFileWritten: (relativePath: String) -> Unit = {},
) : ToolSet {

  /** Returns a user-visible representation of the workspace root (for system prompt injection). */
  fun getWorkspacePath(): String {
    val uri = workspaceUriProvider()
    return if (uri.isNullOrEmpty()) "(not set)" else uri
  }

  @Tool(description = "Lists files and directories in the workspace at the given relative path.")
  fun listFiles(
    @ToolParam(
      description =
        "Relative path within the workspace (e.g. 'src/main'). Use '' or '.' for the root."
    )
    relativePath: String,
  ): Map<String, Any> {
    val root = resolveRoot() ?: return workspaceNotSetError()
    val dir = navigateTo(root, relativePath) ?: return mapOf("error" to "Path not found: $relativePath")
    if (!dir.isDirectory) return mapOf("error" to "Not a directory: $relativePath")

    val entries =
      (dir.listFiles() ?: emptyArray()).map { f ->
        mapOf("name" to (f.name ?: ""), "isDirectory" to f.isDirectory, "size" to f.length())
      }
    return mapOf("path" to relativePath, "count" to entries.size, "entries" to entries)
  }

  @Tool(description = "Reads the text content of a file in the workspace.")
  fun readFile(
    @ToolParam(description = "Relative path to the file (e.g. 'src/main/Main.kt').") relativePath: String
  ): Map<String, String> {
    val root = resolveRoot() ?: return workspaceNotSetError()
    val safeRelative = sanitizePath(relativePath) ?: return pathTraversalError()
    val file = navigateTo(root, safeRelative) ?: return mapOf("error" to "File not found: $relativePath")
    if (!file.isFile) return mapOf("error" to "Not a file: $relativePath")

    return try {
      val content =
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
          ?: return mapOf("error" to "Could not open file")
      onFileRead(relativePath)
      mapOf("result" to "success", "path" to relativePath, "content" to content)
    } catch (e: Exception) {
      mapOf("error" to (e.message ?: "Failed to read file"))
    }
  }

  @Tool(
    description =
      "Writes text content to a file in the workspace. Creates the file (and any parent directories) if it does not exist."
  )
  fun writeFile(
    @ToolParam(description = "Relative path to the file (e.g. 'output/result.txt').") relativePath: String,
    @ToolParam(description = "Text content to write.") content: String,
  ): Map<String, String> {
    val root = resolveRoot() ?: return workspaceNotSetError()
    val safeRelative = sanitizePath(relativePath) ?: return pathTraversalError()

    val parts = safeRelative.split("/")
    val fileName = parts.last()
    val dirParts = parts.dropLast(1)

    // Create or navigate to parent directory.
    val parentDir =
      if (dirParts.isEmpty()) root
      else
        dirParts.fold(root as DocumentFile?) { cur, seg ->
          cur?.findFile(seg) ?: cur?.createDirectory(seg)
        }
          ?: return mapOf("error" to "Could not create parent directories for $relativePath")

    // Find or create the file.
    val existing = parentDir.findFile(fileName)
    val fileUri: Uri =
      if (existing != null && existing.isFile) {
        existing.uri
      } else {
        val newFile = parentDir.createFile("text/plain", fileName)
          ?: return mapOf("error" to "Could not create file: $relativePath")
        newFile.uri
      }

    return try {
      context.contentResolver.openOutputStream(fileUri, "wt")?.bufferedWriter()?.use {
        it.write(content)
      }
        ?: return mapOf("error" to "Could not open output stream for $relativePath")
      onFileWritten(relativePath)
      mapOf("result" to "success", "path" to relativePath)
    } catch (e: Exception) {
      mapOf("error" to (e.message ?: "Failed to write file"))
    }
  }

  @Tool(description = "Creates a directory (and any missing parent directories) in the workspace.")
  fun createDirectory(
    @ToolParam(description = "Relative path of the directory to create (e.g. 'src/main/kotlin').") relativePath: String
  ): Map<String, String> {
    val root = resolveRoot() ?: return workspaceNotSetError()
    val safeRelative = sanitizePath(relativePath) ?: return pathTraversalError()

    val created =
      safeRelative.split("/").filter { it.isNotEmpty() }.fold(root as DocumentFile?) { cur, seg ->
        cur?.findFile(seg) ?: cur?.createDirectory(seg)
      }

    return if (created != null) {
      mapOf("result" to "success", "path" to relativePath)
    } else {
      mapOf("error" to "Failed to create directory: $relativePath")
    }
  }

  @Tool(description = "Deletes a file or empty directory from the workspace.")
  fun deleteFile(
    @ToolParam(description = "Relative path to the file or directory to delete.") relativePath: String
  ): Map<String, String> {
    val root = resolveRoot() ?: return workspaceNotSetError()
    val safeRelative = sanitizePath(relativePath) ?: return pathTraversalError()
    val target = navigateTo(root, safeRelative) ?: return mapOf("error" to "Not found: $relativePath")

    return if (target.delete()) {
      mapOf("result" to "success", "path" to relativePath)
    } else {
      mapOf("error" to "Could not delete: $relativePath")
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private fun resolveRoot(): DocumentFile? {
    val workspaceUri = workspaceUriProvider()
    if (workspaceUri.isNullOrEmpty()) return null
    return try {
      DocumentFile.fromTreeUri(context, Uri.parse(workspaceUri))
    } catch (e: Exception) {
      Log.e(TAG, "Invalid workspace URI: $workspaceUri", e)
      null
    }
  }

  private fun navigateTo(root: DocumentFile, relativePath: String): DocumentFile? {
    val clean = relativePath.trim().trimStart('/')
    if (clean.isEmpty() || clean == ".") return root
    return clean.split("/").filter { it.isNotEmpty() }.fold(root as DocumentFile?) { cur, seg ->
      cur?.findFile(seg)
    }
  }

  /** Returns null if the path attempts directory traversal. */
  private fun sanitizePath(path: String): String? {
    val clean = path.trim().trimStart('/')
    if (clean.contains("..")) return null
    if (clean.startsWith("/")) return null
    return clean
  }

  private fun workspaceNotSetError(): Map<String, String> =
    mapOf("error" to "Workspace not set. Please tap 'Set Workspace' in the app to choose a folder.")

  private fun pathTraversalError(): Map<String, String> =
    mapOf("error" to "Invalid path: directory traversal is not allowed.")
}
