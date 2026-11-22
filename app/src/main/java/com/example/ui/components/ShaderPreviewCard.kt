package com.example.ui.components

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.OverlayRuntimeBridge
import com.example.model.ShaderTelemetry
import com.example.shader.GlShaderEngine
import com.example.shader.RenderSnapshot
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.PrismEmerald
import com.example.ui.theme.PrismRose
import java.util.Locale

@Composable
fun ShaderPreviewCard(
    snapshot: RenderSnapshot,
    telemetry: ShaderTelemetry,
    isOverlayRunning: Boolean,
    hasOverlayPermission: Boolean,
    onTogglePreviewPause: () -> Unit,
    onResetTime: () -> Unit,
    onToggleCheckerboard: () -> Unit,
    onToggleOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var expanded by remember { mutableStateOf(false) }
    var lifecyclePaused by remember { mutableStateOf(false) }

    val previewEngine = remember(context) {
        GlShaderEngine(
            context = context.applicationContext,
            tag = "InAppPreview",
            onTelemetry = { t ->
                if (!OverlayRuntimeBridge.isOverlayRunning.value) {
                    OverlayRuntimeBridge.publishTelemetry(t)
                }
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecyclePaused = event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            previewEngine.stop()
        }
    }

    val effectiveSnapshot = remember(snapshot, lifecyclePaused) {
        snapshot.copy(isPaused = snapshot.isPaused || lifecyclePaused)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.large
                )
        ) {
            // Top Preview Viewport
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (expanded) 256.dp else 164.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(Color(0xFF07090E))
            ) {
                if (snapshot.settings.showCheckerboardInPreview) {
                    CheckerboardBackdrop(modifier = Modifier.fillMaxSize())
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("shader_preview_surface"),
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            isOpaque = false
                            alpha = snapshot.settings.overlayAlpha
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    previewEngine.start(surface, width, height, effectiveSnapshot)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    previewEngine.resize(width, height)
                                }

                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    previewEngine.stop()
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                            }
                        }
                    },
                    update = { textureView ->
                        textureView.alpha = snapshot.settings.overlayAlpha.coerceIn(0f, 1f)
                        previewEngine.updateSnapshot(effectiveSnapshot)
                    }
                )

                // Top-left Telemetry Pill
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = Color(0xCC0B0E14),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.border(
                            1.dp,
                            Color.White.copy(alpha = 0.12f),
                            RoundedCornerShape(999.dp)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (telemetry.compileSuccess) PrismEmerald else PrismRose)
                            )
                            Text(
                                text = if (telemetry.compileSuccess) {
                                    String.format(
                                        Locale.US,
                                        "%.0f FPS · %.1fms",
                                        telemetry.fps,
                                        telemetry.frameTimeMs
                                    )
                                } else {
                                    "GLSL ERR"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                    }

                    if (telemetry.renderWidth > 0) {
                        Surface(
                            color = Color(0xB30B0E14),
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = "${telemetry.renderWidth}×${telemetry.renderHeight}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.78f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                // Top-right expand & checkerboard toggle
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = onToggleCheckerboard,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("toggle_checkerboard_button")
                    ) {
                        Surface(
                            color = if (snapshot.settings.showCheckerboardInPreview) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            } else {
                                Color(0x990B0E14)
                            },
                            shape = CircleShape,
                        ) {
                            Icon(
                                imageVector = Icons.Default.GridOn,
                                contentDescription = "Toggle transparency checkerboard",
                                tint = if (snapshot.settings.showCheckerboardInPreview) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White.copy(alpha = 0.8f)
                                },
                                modifier = Modifier.padding(6.dp).size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("toggle_preview_size_button")
                    ) {
                        Surface(
                            color = Color(0x990B0E14),
                            shape = CircleShape,
                        ) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (expanded) "Collapse preview" else "Expand preview",
                                tint = Color.White,
                                modifier = Modifier.padding(6.dp).size(18.dp)
                            )
                        }
                    }
                }
            }

            // Bottom Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onTogglePreviewPause,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("preview_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (snapshot.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (snapshot.isPaused) "Resume preview" else "Pause preview",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = onResetTime,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("preview_reset_time_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset shader time",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(
                            text = snapshot.project.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${snapshot.project.mode.label} · ${snapshot.project.variables.size} vars",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onToggleOverlay,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toggle_system_overlay_button"),
                    colors = if (isOverlayRunning) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (isOverlayRunning) Icons.Default.Stop else Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isOverlayRunning -> "Stop Overlay"
                            hasOverlayPermission -> "Start Overlay"
                            else -> "Allow Overlay"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckerboardBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val tileSize = 18.dp.toPx()
        val cols = (size.width / tileSize).toInt() + 1
        val rows = (size.height / tileSize).toInt() + 1
        val light = Color(0xFF1A2230)
        val dark = Color(0xFF10151F)
        drawRect(dark)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if ((r + c) % 2 == 0) {
                    drawRect(
                        color = light,
                        topLeft = Offset(c * tileSize, r * tileSize),
                        size = Size(tileSize, tileSize),
                    )
                }
            }
        }
    }
}
