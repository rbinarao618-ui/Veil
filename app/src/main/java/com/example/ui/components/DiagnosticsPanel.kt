package com.example.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.ShaderTelemetry
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.PrismEmerald
import com.example.ui.theme.PrismRose

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsPanel(
    telemetry: ShaderTelemetry,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("diagnostics_panel"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("GPU & Compiler Status", style = MaterialTheme.typography.titleMedium)
                    DiagnosticLine("Driver", telemetry.glesVersion)
                    DiagnosticLine("Renderer", telemetry.gpuRenderer.ifBlank { "Initializing..." })
                    DiagnosticLine(
                        "Buffer Size",
                        if (telemetry.renderWidth > 0) "${telemetry.renderWidth} × ${telemetry.renderHeight}" else "Pending"
                    )
                    DiagnosticLine(
                        "Compile",
                        if (telemetry.compileSuccess) "OK" else "${telemetry.errorStage} Error",
                        valueColor = if (telemetry.compileSuccess) PrismEmerald else PrismRose
                    )
                    if (telemetry.compileLog.isNotBlank()) {
                        Text(
                            text = telemetry.compileLog,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = if (telemetry.compileSuccess) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                PrismRose
                            }
                        )
                    }
                }
            }
        }

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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Active GPU Uniforms & #defines", style = MaterialTheme.typography.titleMedium)
                    if (telemetry.activeUniformNames.isEmpty() && telemetry.activeDefineNames.isEmpty()) {
                        Text(
                            text = "No active uniforms or #define macros bound in the current shader program.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            telemetry.activeUniformNames.forEach { name ->
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = "uniform $name",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily)
                                        )
                                    }
                                )
                            }
                            telemetry.activeDefineNames.forEach { name ->
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = "#define $name",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (telemetry.lastPreprocessedFragment.isNotBlank()) {
            item {
                PreprocessedSourceCard(
                    title = "Preprocessed Fragment GLSL",
                    source = telemetry.lastPreprocessedFragment
                )
            }
        }

        if (telemetry.lastPreprocessedVertex.isNotBlank()) {
            item {
                PreprocessedSourceCard(
                    title = "Preprocessed Vertex GLSL",
                    source = telemetry.lastPreprocessedVertex
                )
            }
        }
    }
}

@Composable
private fun DiagnosticLine(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = valueColor
        )
    }
}

@Composable
private fun PreprocessedSourceCard(title: String, source: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFF0D111A),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(
                text = source,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = Color(0xFFCBD5E1)
            )
        }
    }
}
