package com.example.data

import com.example.model.EngineSettings
import com.example.model.ShaderProject
import com.example.model.ShaderTelemetry
import com.example.shader.RenderSnapshot
import com.example.shader.ShaderDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object OverlayRuntimeBridge {
    private val _snapshot = MutableStateFlow(
        RenderSnapshot(
            project = ShaderDefaults.createBlankProject(),
            settings = EngineSettings(),
            isPaused = false,
            resetEpoch = 0L,
        )
    )
    val snapshot: StateFlow<RenderSnapshot> = _snapshot.asStateFlow()

    private val _isOverlayRunning = MutableStateFlow(false)
    val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()

    private val _isOverlayPaused = MutableStateFlow(false)
    val isOverlayPaused: StateFlow<Boolean> = _isOverlayPaused.asStateFlow()

    private val _telemetry = MutableStateFlow(ShaderTelemetry())
    val telemetry: StateFlow<ShaderTelemetry> = _telemetry.asStateFlow()

    private val _runtimeError = MutableStateFlow<String?>(null)
    val runtimeError: StateFlow<String?> = _runtimeError.asStateFlow()

    fun updateProject(project: ShaderProject) {
        _snapshot.value = _snapshot.value.copy(project = project)
    }

    fun updateSettings(settings: EngineSettings) {
        _snapshot.value = _snapshot.value.copy(settings = settings)
    }

    fun setPreviewPaused(paused: Boolean) {
        _snapshot.value = _snapshot.value.copy(isPaused = paused)
    }

    fun triggerTimeReset() {
        _snapshot.value = _snapshot.value.copy(resetEpoch = _snapshot.value.resetEpoch + 1L)
    }

    fun setOverlayRunning(running: Boolean) {
        _isOverlayRunning.value = running
        if (!running) {
            _isOverlayPaused.value = false
        }
    }

    fun setOverlayPaused(paused: Boolean) {
        _isOverlayPaused.value = paused
    }

    fun publishTelemetry(telemetry: ShaderTelemetry) {
        _telemetry.value = telemetry
    }

    fun reportRuntimeError(message: String?) {
        _runtimeError.value = message
    }

    fun clearRuntimeError() {
        _runtimeError.value = null
    }
}
