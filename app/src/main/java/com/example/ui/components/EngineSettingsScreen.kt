package com.example.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.AntiEchoMode
import com.example.model.EngineSettings
import com.example.model.ShaderTelemetry
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.PrismAmber
import com.example.ui.theme.PrismEmerald
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngineSettingsScreen(
    settings: EngineSettings,
    telemetry: ShaderTelemetry,
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    isOverlayRunning: Boolean,
    isOverlayPaused: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onToggleOverlay: () -> Unit,
    onToggleOverlayPause: () -> Unit,
    onRefreshCleanScreen: () -> Unit,
    onUpdateSettings: ((EngineSettings) -> EngineSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("engine_settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text("Overlay & Optimization", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "System-wide TextureView + EGL14 GPU performance controls",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // System Overlay Permission & Control Card
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (hasOverlayPermission) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = if (hasOverlayPermission) PrismEmerald else PrismAmber,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (hasOverlayPermission) {
                                    "Display Over Other Apps Granted"
                                } else {
                                    "System Overlay Permission Required"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (hasOverlayPermission) {
                                    "Pass-through hardware TextureView overlay is ready."
                                } else {
                                    "Enable \"Allow display over other apps\" in Android Settings to render shaders system-wide."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!hasOverlayPermission) {
                            Button(
                                onClick = onRequestOverlayPermission,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("grant_overlay_permission_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Open Overlay Settings")
                            }
                        } else {
                            Button(
                                onClick = onToggleOverlay,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("engine_toggle_overlay_button"),
                                colors = if (isOverlayRunning) {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                } else {
                                    ButtonDefaults.buttonColors()
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = if (isOverlayRunning) Icons.Default.Stop else Icons.Default.Layers,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (isOverlayRunning) "Stop System Overlay" else "Start System Overlay")
                            }

                            if (isOverlayRunning) {
                                FilledTonalButton(
                                    onClick = onToggleOverlayPause,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("engine_pause_overlay_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isOverlayPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isOverlayPaused) "Resume" else "Pause")
                                }
                            }
                        }
                    }

                    if (!hasNotificationPermission) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Allow notification controls for quick Pause/Stop from shade",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(
                                onClick = onRequestNotificationPermission,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("Allow")
                            }
                        }
                    }
                }
            }
        }

        // GPU Optimization Controls
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("GPU & Battery Optimization", style = MaterialTheme.typography.titleMedium)
                    }

                    // Resolution Scale
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Render Resolution Scale (${(settings.resolutionScale * 100).roundToInt()}%)",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Lower resolution scales dramatically cut GPU fragment load and heat with hardware bilinear upscaling.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.25f to "25%", 0.50f to "50%", 0.75f to "75%", 1.0f to "100%").forEach { (scale, label) ->
                                FilterChip(
                                    selected = kotlin.math.abs(settings.resolutionScale - scale) < 0.02f,
                                    onClick = { onUpdateSettings { it.copy(resolutionScale = scale) } },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("res_scale_$label")
                                )
                            }
                        }
                    }

                    // Target FPS Cap
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Target Frame Rate", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30 to "30 FPS", 60 to "60 FPS", 90 to "90 FPS", 120 to "120 FPS", 0 to "VSync").forEach { (fps, label) ->
                                FilterChip(
                                    selected = settings.targetFps == fps,
                                    onClick = { onUpdateSettings { it.copy(targetFps = fps) } },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("fps_cap_$fps")
                                )
                            }
                        }
                    }

                    // Global Overlay Opacity
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Global Overlay Opacity", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${(settings.overlayAlpha * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = settings.overlayAlpha,
                            onValueChange = { alpha ->
                                onUpdateSettings { it.copy(overlayAlpha = alpha) }
                            },
                            valueRange = 0.05f..1.0f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("global_overlay_alpha_slider")
                        )
                    }

                    // Fullscreen Screen-Capture Anti-Echo Protection
                    SettingSwitchRow(
                        title = "Prevent Screen-Capture Echo",
                        subtitle = "Synchronizes capture with double-blank overlay frames to eliminate recursive feedback",
                        checked = settings.antiEchoMode != AntiEchoMode.ALLOW_FEEDBACK,
                        onCheckedChange = { enabled ->
                            onUpdateSettings {
                                it.copy(
                                    antiEchoMode = if (enabled) {
                                        AntiEchoMode.CLEAN_PULSE
                                    } else {
                                        AntiEchoMode.ALLOW_FEEDBACK
                                    }
                                )
                            }
                        },
                        testTag = "switch_anti_echo"
                    )

                    SettingSwitchRow(
                        title = "Pause Rendering When Screen Off",
                        subtitle = "Halts EGL draw loop when device sleeps to save battery",
                        checked = settings.pauseOnScreenOff,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(pauseOnScreenOff = v) } },
                        testTag = "switch_pause_screen_off"
                    )

                    SettingSwitchRow(
                        title = "Auto-Declare Uniforms in GLSL",
                        subtitle = "Automatically injects missing uniform float / sampler2D / bool lines so auto-named variables work instantly",
                        checked = settings.autoInjectUniforms,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(autoInjectUniforms = v) } },
                        testTag = "switch_auto_inject_uniforms"
                    )

                    SettingSwitchRow(
                        title = "Studio Dark Theme",
                        subtitle = "High-contrast obsidian surfaces tuned for shader inspection",
                        checked = settings.darkTheme,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(darkTheme = v) } },
                        testTag = "switch_dark_theme"
                    )
                }
            }
        }

        // Hardware Info Footer
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Renderer Telemetry", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${telemetry.glesVersion} · ${telemetry.gpuRenderer.ifBlank { "OpenGL ES" }}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag(testTag)
        )
    }
}
