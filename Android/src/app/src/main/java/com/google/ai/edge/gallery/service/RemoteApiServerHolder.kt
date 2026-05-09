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

import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds a process-wide weak reference to the active [ModelManagerViewModel] so the
 * background [RemoteApiServerService] can look up downloaded models, and exposes the
 * server's runtime status as a flow consumable from Compose UI.
 */
object RemoteApiServerHolder {
  private var viewModelRef: WeakReference<ModelManagerViewModel>? = null

  fun registerViewModel(vm: ModelManagerViewModel) {
    viewModelRef = WeakReference(vm)
  }

  fun viewModel(): ModelManagerViewModel? = viewModelRef?.get()

  private val _status = MutableStateFlow<RemoteApiServerStatus>(RemoteApiServerStatus.Stopped)
  val status: StateFlow<RemoteApiServerStatus> = _status.asStateFlow()

  fun update(s: RemoteApiServerStatus) {
    _status.value = s
  }
}

sealed class RemoteApiServerStatus {
  object Stopped : RemoteApiServerStatus()
  data class Starting(val modelName: String) : RemoteApiServerStatus()
  data class Running(val modelName: String, val ip: String, val port: Int) : RemoteApiServerStatus()
  data class Error(val message: String) : RemoteApiServerStatus()
}
