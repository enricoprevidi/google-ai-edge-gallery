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
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONObject

private const val TAG = "AGAppLauncherTools"

/**
 * Specialist ToolSet that lists installed apps, launches them, and sends structured data to them
 * via Intents. All Intents use FLAG_ACTIVITY_NEW_TASK.
 */
class AppLauncherTools(
  private val context: Context,
  private val onActionTaken: (OrchestratorAction) -> Unit,
) : ToolSet {

  @Tool(
    description =
      "Lists all user-installed apps on the device. Returns a list of app names and package names."
  )
  fun listInstalledApps(): Map<String, Any> {
    Log.d(TAG, "listInstalledApps")
    return try {
      val pm = context.packageManager
      val flags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          PackageManager.GET_META_DATA
        } else {
          @Suppress("DEPRECATION") PackageManager.GET_META_DATA
        }
      val apps =
        pm.getInstalledApplications(flags)
          .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
          .map { info ->
            mapOf(
              "name" to (pm.getApplicationLabel(info).toString()),
              "packageName" to info.packageName,
            )
          }
          .sortedBy { it["name"] as String }
      mapOf("count" to apps.size, "apps" to apps)
    } catch (e: Exception) {
      mapOf("error" to (e.message ?: "Failed to list apps"))
    }
  }

  @Tool(
    description =
      "Launches an installed app by its package name (e.g. 'com.spotify.music'). " +
        "Use listInstalledApps first to find the correct package name."
  )
  fun launchApp(
    @ToolParam(description = "The package name of the app to launch (e.g. 'com.spotify.music').")
    packageName: String
  ): Map<String, String> {
    Log.d(TAG, "launchApp: $packageName")
    val pm = context.packageManager
    val intent = pm.getLaunchIntentForPackage(packageName.trim())
    return if (intent != null) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
        context.startActivity(intent)
        val appName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        onActionTaken(LaunchAppAction(packageName = packageName, appName = appName))
        mapOf("result" to "success", "packageName" to packageName)
      } catch (e: Exception) {
        mapOf("error" to (e.message ?: "Failed to launch $packageName"))
      }
    } else {
      mapOf("error" to "App not found or not launchable: $packageName")
    }
  }

  @Tool(
    description =
      "Sends a structured Intent to another app. " +
        "Use this to open a specific screen in an app or pass data to it. " +
        "Provide the package name, an Android Intent action string, and optional extras as a JSON object."
  )
  fun sendDataToApp(
    @ToolParam(description = "The package name of the target app.") packageName: String,
    @ToolParam(
      description = "The Android Intent action string (e.g. 'android.intent.action.VIEW')."
    )
    intentAction: String,
    @ToolParam(
      description =
        "Optional JSON object of extras to include (e.g. '{\"android.intent.extra.TEXT\": \"hello\"}')."
    )
    extrasJson: String,
  ): Map<String, String> {
    Log.d(TAG, "sendDataToApp: $packageName action=$intentAction extras=$extrasJson")
    return try {
      val intent =
        Intent(intentAction.trim()).apply {
          `package` = packageName.trim()
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

      // Parse and attach extras.
      if (extrasJson.isNotBlank()) {
        try {
          val json = JSONObject(extrasJson)
          for (key in json.keys()) {
            intent.putExtra(key, json.getString(key))
          }
        } catch (e: Exception) {
          Log.w(TAG, "Could not parse extrasJson: $extrasJson", e)
        }
      }

      context.startActivity(intent)
      onActionTaken(SendIntentAction(packageName = packageName, intentAction = intentAction))
      mapOf("result" to "success", "packageName" to packageName, "action" to intentAction)
    } catch (e: Exception) {
      mapOf("error" to (e.message ?: "Failed to send intent to $packageName"))
    }
  }

  @Tool(description = "Opens a URL in the default browser or the appropriate app.")
  fun openUrl(
    @ToolParam(description = "The URL to open (must start with http:// or https://).") url: String
  ): Map<String, String> {
    Log.d(TAG, "openUrl: $url")
    return try {
      val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      context.startActivity(intent)
      onActionTaken(OpenUrlAction(url = url))
      mapOf("result" to "success", "url" to url)
    } catch (e: Exception) {
      mapOf("error" to (e.message ?: "Failed to open URL"))
    }
  }
}
