package com.miku.mikuremote.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Jembatan state antara ServerService dan UI. */
object ServiceBus {
    private val _wsState = MutableStateFlow("OFFLINE")
    val wsState: StateFlow<String> get() = _wsState

    private val _serverMode = MutableStateFlow(false)
    val serverMode: StateFlow<Boolean> get() = _serverMode

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> get() = _running

    // ---- Phase 3B: state baru ----

    private val _screenState = MutableStateFlow("READY")
    val screenState: StateFlow<String> get() = _screenState

    private val _cameraState = MutableStateFlow("READY")
    val cameraState: StateFlow<String> get() = _cameraState

    private val _guardEnabled = MutableStateFlow(false)
    val guardEnabled: StateFlow<Boolean> get() = _guardEnabled

    private val _guardConfig = MutableStateFlow(GuardConfig())
    val guardConfig: StateFlow<GuardConfig> get() = _guardConfig

    fun setWsState(s: String) { _wsState.value = s }
    fun setServerMode(on: Boolean) { _serverMode.value = on }
    fun setRunning(r: Boolean) { _running.value = r }
    fun setScreenState(s: String) { _screenState.value = s }
    fun setCameraState(s: String) { _cameraState.value = s }
    fun setGuardEnabled(on: Boolean) { _guardEnabled.value = on }
    fun setGuardConfig(c: GuardConfig) { _guardConfig.value = c }
}
