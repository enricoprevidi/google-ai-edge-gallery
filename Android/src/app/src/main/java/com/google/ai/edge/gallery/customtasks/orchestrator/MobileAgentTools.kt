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

  @Tool(
    description =
      "Flashes the device flashlight in Morse code for the given text. Each letter is encoded " +
        "as dots and dashes with proper Morse timing (dot = unit, dash = 3 units, intra-letter " +
        "gap = 1 unit, inter-letter gap = 3 units, inter-word gap = 7 units). Useful for SOS " +
        "or any short signalling. Blocks until the entire pattern has finished playing."
  )
  fun flashMorseCode(
    @ToolParam(
      description =
        "Plain text to signal (e.g. 'SOS', 'HELP'). Letters and digits supported; everything " +
          "else is treated as a word separator."
    )
    text: String,
    @ToolParam(
      description =
        "Length of one Morse 'unit' in milliseconds. Defaults to 200 (typical hand signalling " +
          "speed). Lower = faster."
    )
    unitMs: Int = 200,
  ): Map<String, String> {
    Log.d(TAG, "flashMorseCode: text='$text' unit=${unitMs}ms")
    val unit = unitMs.coerceIn(40, 1000).toLong()
    val pattern = buildMorseSchedule(text, unit)
    if (pattern.isEmpty()) {
      return mapOf("error" to "No Morse-encodable characters in input.")
    }
    return try {
      // Make sure the torch is off before we start so the first ON edge is visible.
      setFlashlight(enabled = false)
      for ((on, durationMs) in pattern) {
        setFlashlight(enabled = on)
        Thread.sleep(durationMs)
      }
      setFlashlight(enabled = false)
      onActionTaken(OrchestratorMobileAction("Flashed Morse: \"$text\""))
      mapOf("result" to "success", "morse" to text)
    } catch (e: Exception) {
      // Always make sure the torch ends OFF, even on failure.
      runCatching { setFlashlight(enabled = false) }
      mapOf("error" to (e.message ?: "Failed to flash Morse code"))
    }
  }

  /**
   * Returns a list of (torchOn, durationMs) steps that play [text] in Morse code.
   * Standard timing: dot = 1 unit ON, dash = 3 units ON, gap between symbols of one letter =
   * 1 unit OFF, gap between letters = 3 units OFF, gap between words = 7 units OFF.
   */
  private fun buildMorseSchedule(text: String, unit: Long): List<Pair<Boolean, Long>> {
    val schedule = mutableListOf<Pair<Boolean, Long>>()
    val words = text.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    for ((wi, word) in words.withIndex()) {
      for ((li, letter) in word.withIndex()) {
        val code = MORSE_TABLE[letter] ?: continue
        for ((si, symbol) in code.withIndex()) {
          val onDuration = if (symbol == '.') unit else 3L * unit
          schedule += true to onDuration
          if (si != code.lastIndex) schedule += false to unit // intra-letter gap
        }
        if (li != word.lastIndex) schedule += false to 3L * unit // inter-letter gap
      }
      if (wi != words.lastIndex) schedule += false to 7L * unit // inter-word gap
    }
    return schedule
  }

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

  companion object {
    /** International Morse code table (letters + digits). */
    private val MORSE_TABLE: Map<Char, String> =
      mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
        'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
        'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
        'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--",
        '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.",
      )
  }
}
