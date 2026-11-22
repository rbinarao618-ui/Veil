package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AntiEchoMode
import com.example.model.EngineSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "shaderveil_prefs")

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val TARGET_FPS = intPreferencesKey("target_fps")
        val RESOLUTION_SCALE = floatPreferencesKey("resolution_scale")
        val OVERLAY_ALPHA = floatPreferencesKey("overlay_alpha")
        val PAUSE_ON_SCREEN_OFF = booleanPreferencesKey("pause_on_screen_off")
        val AUTO_INJECT_UNIFORMS = booleanPreferencesKey("auto_inject_uniforms")
        val SHOW_CHECKERBOARD = booleanPreferencesKey("show_checkerboard")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val ANTI_ECHO_MODE = stringPreferencesKey("anti_echo_mode")
        val SCREEN_CAPTURE_INTERVAL_MS = intPreferencesKey("screen_capture_interval_ms")
        val ACTIVE_SHADER_ID = stringPreferencesKey("active_shader_id")
    }

    val settingsFlow: Flow<EngineSettings> = context.dataStore.data.map { prefs ->
        val antiEcho = prefs[Keys.ANTI_ECHO_MODE]?.let { raw ->
            runCatching { AntiEchoMode.valueOf(raw) }.getOrNull()
        } ?: AntiEchoMode.CLEAN_PULSE
        EngineSettings(
            targetFps = prefs[Keys.TARGET_FPS] ?: 60,
            resolutionScale = prefs[Keys.RESOLUTION_SCALE] ?: 0.75f,
            overlayAlpha = prefs[Keys.OVERLAY_ALPHA] ?: 1.0f,
            pauseOnScreenOff = prefs[Keys.PAUSE_ON_SCREEN_OFF] ?: true,
            autoInjectUniforms = prefs[Keys.AUTO_INJECT_UNIFORMS] ?: true,
            showCheckerboardInPreview = prefs[Keys.SHOW_CHECKERBOARD] ?: true,
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            antiEchoMode = antiEcho,
            screenCaptureIntervalMs = prefs[Keys.SCREEN_CAPTURE_INTERVAL_MS] ?: 500,
        )
    }

    val activeShaderIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_SHADER_ID]
    }

    suspend fun updateSettings(transform: (EngineSettings) -> EngineSettings) {
        context.dataStore.edit { prefs ->
            val antiEcho = prefs[Keys.ANTI_ECHO_MODE]?.let { raw ->
                runCatching { AntiEchoMode.valueOf(raw) }.getOrNull()
            } ?: AntiEchoMode.CLEAN_PULSE
            val current = EngineSettings(
                targetFps = prefs[Keys.TARGET_FPS] ?: 60,
                resolutionScale = prefs[Keys.RESOLUTION_SCALE] ?: 0.75f,
                overlayAlpha = prefs[Keys.OVERLAY_ALPHA] ?: 1.0f,
                pauseOnScreenOff = prefs[Keys.PAUSE_ON_SCREEN_OFF] ?: true,
                autoInjectUniforms = prefs[Keys.AUTO_INJECT_UNIFORMS] ?: true,
                showCheckerboardInPreview = prefs[Keys.SHOW_CHECKERBOARD] ?: true,
                darkTheme = prefs[Keys.DARK_THEME] ?: true,
                antiEchoMode = antiEcho,
                screenCaptureIntervalMs = prefs[Keys.SCREEN_CAPTURE_INTERVAL_MS] ?: 500,
            )
            val next = transform(current)
            prefs[Keys.TARGET_FPS] = next.targetFps
            prefs[Keys.RESOLUTION_SCALE] = next.resolutionScale
            prefs[Keys.OVERLAY_ALPHA] = next.overlayAlpha
            prefs[Keys.PAUSE_ON_SCREEN_OFF] = next.pauseOnScreenOff
            prefs[Keys.AUTO_INJECT_UNIFORMS] = next.autoInjectUniforms
            prefs[Keys.SHOW_CHECKERBOARD] = next.showCheckerboardInPreview
            prefs[Keys.DARK_THEME] = next.darkTheme
            prefs[Keys.ANTI_ECHO_MODE] = next.antiEchoMode.name
            prefs[Keys.SCREEN_CAPTURE_INTERVAL_MS] = next.screenCaptureIntervalMs
        }
    }

    suspend fun setActiveShaderId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_SHADER_ID] = id
        }
    }
}
