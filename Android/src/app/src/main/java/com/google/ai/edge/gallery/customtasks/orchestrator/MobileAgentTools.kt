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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "AGMobileAgentTools"

/**
 * Specialist ToolSet for mobile device actions. Directly performs actions inline (no callback
 * chain) so it works correctly when called from the specialist inference thread. All Intents use
 * FLAG_ACTIVITY_NEW_TASK since they are started from a non-Activity context.
 */
class MobileAgentTools(
  private val context: Context,
  private val onActionTaken: (OrchestratorAction) -> Unit,
) : ToolSet {

  @Tool(description = "Turns the device flashlight on.")
  fun turnOnFlashlight(): Map<String, String> {
    Log.d(TAG, "turnOnFlashlight")
    val result = setFlashlight(enabled = true)
    onActionTaken(OrchestratorMobileAction("Flashlight ON"))
    return if (result.isEmpty()) mapOf("result" to "success") else mapOf("error" to result)
  }

  @Tool(description = "Turns the device flashlight off.")
  fun turnOffFlashlight(): Map<String, String> {
    Log.d(TAG, "turnOffFlashlight")
    val result = setFlashlight(enabled = false)
    onActionTaken(OrchestratorMobileAction("Flashlight OFF"))
    return if (result.isEmpty()) mapOf("result" to "success") else mapOf("error" to result)
  }

  @Tool(description = "Creates a contact in the phone's contact list.")
  fun createContact(
    @ToolParam(description = "First name of the contact.") firstName: String,
    @ToolParam(description = "Last name of the contact.") lastName: String,
    @ToolParam(description = "Phone number of the contact.") phoneNumber: String,
    @ToolParam(description = "Email address of the contact.") email: String,
  ): Map<String, String> {
    Log.d(TAG, "createContact: $firstName $lastName")
    val intent =
      Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(ContactsContract.Intents.Insert.NAME, "$firstName $lastName")
        putExtra(ContactsContract.Intents.Insert.EMAIL, email)
        putExtra(
          ContactsContract.Intents.Insert.EMAIL_TYPE,
          ContactsContract.CommonDataKinds.Email.TYPE_WORK,
        )
        putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
        putExtra(
          ContactsContract.Intents.Insert.PHONE_TYPE,
          ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
        )
      }
    return try {
      context.startActivity(intent)
      onActionTaken(OrchestratorMobileAction("Created contact: $firstName $lastName"))
      mapOf("result" to "success", "name" to "$firstName $lastName")
    } catch (e: Exception) {
      mapOf("error" to (e.message ?: "Failed to create contact"))
    }
  }

  @Tool(description = "Sends an email via the device's email client.")
  fun sendEmail(
    @ToolParam(description = "Recipient email address.") to: String,
    @ToolParam(description = "Subject of the email.") subject: String,
    @ToolParam(description = "Body text of the email.") body: String,
  ): Map<String, String> {
    Log.d(TAG, "sendEmail to=$to")
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        data = "mailto:".toUri()
        type = "text/plain"
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
      }
    return try {
      context.startActivity(intent)
      onActionTaken(OrchestratorMobileAction("Email to $to"))
      mapOf("result" to "success", "to" to to)
    } catch (e: Exception) {
      mapOf("error" to (e.message ?: "Failed to send email"))
    }
  }

  @Tool(description = "Shows a location on the map.")
  fun showLocationOnMap(
    @ToolParam(description = "The location to search (name, address, or business).") location: String
  ): Map<String, String> {
    Log.d(TAG, "showLocationOnMap: $location")
    val encoded = URLEncoder.encode(location, StandardCharsets.UTF_8.toString())
    val intent =
      Intent(Intent.ACTION_VIEW).apply {
        data = "geo:0,0?q=$encoded".toUri()
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return try {
      context.startActivity(intent)
      onActionTaken(OrchestratorMobileAction("Map: $location"))
      mapOf("result" to "success", "location" to location)
    } catch (e: Exception) {
      mapOf("error" to (e.message ?: "Failed to show map"))
    }
  }

  @Tool(description = "Opens the device WiFi settings screen.")
  fun openWifiSettings(): Map<String, String> {
    Log.d(TAG, "openWifiSettings")
    val intent =
      Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    return try {
      context.startActivity(intent)
      onActionTaken(OrchestratorMobileAction("Opened WiFi settings"))
      mapOf("result" to "success")
    } catch (e: Exception) {
      mapOf("error" to (e.message ?: "Failed to open WiFi settings"))
    }
  }

  @Tool(description = "Creates a new calendar event.")
  fun createCalendarEvent(
    @ToolParam(description = "Date and time in YYYY-MM-DDTHH:MM:SS format.") datetime: String,
    @ToolParam(description = "Title of the event.") title: String,
  ): Map<String, String> {
    Log.d(TAG, "createCalendarEvent: $title at $datetime")
    var ms = System.currentTimeMillis()
    try {
      val ldt = LocalDateTime.parse(datetime)
      ms = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
      Log.w(TAG, "Could not parse datetime '$datetime', using now.")
    }
    val intent =
      Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
        putExtra(CalendarContract.Events.TITLE, title)
      }
    return try {
      context.startActivity(intent)
      onActionTaken(OrchestratorMobileAction("Calendar: $title"))
      mapOf("result" to "success", "title" to title, "datetime" to datetime)
    } catch (e: Exception) {
      mapOf("error" to (e.message ?: "Failed to create calendar event"))
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private fun setFlashlight(enabled: Boolean): String {
    val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    return try {
      for (id in mgr.cameraIdList) {
        val hasFlash =
          mgr.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        if (hasFlash) {
          mgr.setTorchMode(id, enabled)
          return ""
        }
      }
      "No flash unit found"
    } catch (e: Exception) {
      e.message ?: "Unknown flashlight error"
    }
  }
}
