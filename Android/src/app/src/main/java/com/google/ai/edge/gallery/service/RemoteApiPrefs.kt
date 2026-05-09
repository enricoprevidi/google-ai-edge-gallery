/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.service

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Lightweight SharedPreferences-backed config for the remote API server. */
object RemoteApiPrefs {
  private const val PREFS_NAME = "remote_api_server_prefs"
  private const val KEY_PORT = "port"
  private const val KEY_MODEL = "model_name"
  private const val KEY_TOKEN = "api_token"
  private const val KEY_REQUIRE_TOKEN = "require_token"
  private const val KEY_MAX_TOKENS = "max_tokens"

  const val DEFAULT_PORT = 8080
  const val DEFAULT_MAX_TOKENS = 2048

  data class Config(
    val port: Int,
    val modelName: String,
    val apiToken: String,
    val requireToken: Boolean,
    val maxTokens: Int,
  )

  private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun read(context: Context): Config {
    val p = prefs(context)
    var token = p.getString(KEY_TOKEN, null)
    if (token.isNullOrEmpty()) {
      token = UUID.randomUUID().toString().replace("-", "")
      p.edit().putString(KEY_TOKEN, token).apply()
    }
    return Config(
      port = p.getInt(KEY_PORT, DEFAULT_PORT),
      modelName = p.getString(KEY_MODEL, "") ?: "",
      apiToken = token,
      requireToken = p.getBoolean(KEY_REQUIRE_TOKEN, false),
      maxTokens = p.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS),
    )
  }

  fun write(context: Context, config: Config) {
    prefs(context).edit()
      .putInt(KEY_PORT, config.port)
      .putString(KEY_MODEL, config.modelName)
      .putString(KEY_TOKEN, config.apiToken)
      .putBoolean(KEY_REQUIRE_TOKEN, config.requireToken)
      .putInt(KEY_MAX_TOKENS, config.maxTokens)
      .apply()
  }

  fun rotateToken(context: Context): String {
    val token = UUID.randomUUID().toString().replace("-", "")
    prefs(context).edit().putString(KEY_TOKEN, token).apply()
    return token
  }
}
