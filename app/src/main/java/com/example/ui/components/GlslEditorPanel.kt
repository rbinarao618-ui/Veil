package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ShaderMode
import com.example.model.ShaderProject
import com.example.model.ShaderTelemetry
import com.example.model.UniformType
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.PrismAmber
import com.example.ui.theme.PrismCyan
import com.example.ui.theme.PrismEmerald
import com.example.ui.theme.PrismRose
import com.example.ui.theme.PrismViolet
import com.example.ui.viewmodel.SeparateStageTab

@Composable
fun GlslEditorPanel(
    project: ShaderProject,
    telemetry: ShaderTelemetry,
    separateStageTab: SeparateStageTab,
    pendingInsertSnippet: String?,
    onConsumeInsertSnippet: () -> Unit,
    onSelectMode: (ShaderMode) -> Unit,
    onSelectSeparateStage: (SeparateStageTab) -> Unit,
    onToggleCustomVertex: (Boolean) -> Unit,
    onChangeMeshSubdivisions: (Int) -> Unit,
    onUpdateCombinedSource: (String) -> Unit,
    onUpdateVertexSource: (String) -> Unit,
    onUpdateFragmentSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeSource = when (project.mode) {
        ShaderMode.COMBINED -> project.combinedSource
        ShaderMode.SEPARATE -> when (separateStageTab) {
            SeparateStageTab.FRAGMENT -> project.fragmentSource
            SeparateStageTab.VERTEX -> project.vertexSource
        }
    }

    var textFieldValue by remember(project.id, project.mode, separateStageTab) {
        mutableStateOf(TextFieldValue(activeSource))
    }

    // Keep synced when external template/project switch changes source
    LaunchedEffect(activeSource) {
        if (textFieldValue.text != activeSource) {
            val safeSel = TextRange(
                textFieldValue.selection.start.coerceAtMost(activeSource.length),
                textFieldValue.selection.end.coerceAtMost(activeSource.length)
            )
            textFieldValue = TextFieldValue(activeSource, safeSel)
        }
    }

    fun applyTextChange(next: TextFieldValue) {
        textFieldValue = next
        when (project.mode) {
            ShaderMode.COMBINED -> onUpdateCombinedSource(next.text)
            ShaderMode.SEPARATE -> when (separateStageTab) {
                SeparateStageTab.FRAGMENT -> onUpdateFragmentSource(next.text)
                SeparateStageTab.VERTEX -> onUpdateVertexSource(next.text)
            }
        }
    }

    LaunchedEffect(pendingInsertSnippet) {
        val snippet = pendingInsertSnippet ?: return@LaunchedEffect
        val cur = textFieldValue
        val start = cur.selection.min.coerceIn(0, cur.text.length)
        val end = cur.selection.max.coerceIn(0, cur.text.length)
        val updatedText = cur.text.substring(0, start) + snippet + cur.text.substring(end)
        val cursor = start + snippet.length
        applyTextChange(TextFieldValue(updatedText, TextRange(cursor)))
        onConsumeInsertSnippet()
    }

    val highlighter = remember { GlslSyntaxVisualTransformation() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Mode Switcher: Combined GLSL ES vs Separate Vertex + Fragment
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ShaderMode.entries.forEachIndexed { idx, mode ->
                SegmentedButton(
                    selected = project.mode == mode,
                    onClick = { onSelectMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(idx, ShaderMode.entries.size),
                    modifier = Modifier.testTag("shader_mode_${mode.name.lowercase()}")
                ) {
                    Text(mode.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (project.mode == ShaderMode.SEPARATE) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SeparateStageTab.entries.forEach { tab ->
                            FilterChip(
                                selected = separateStageTab == tab,
                                onClick = { onSelectSeparateStage(tab) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("stage_tab_${tab.name.lowercase()}")
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (separateStageTab == SeparateStageTab.VERTEX) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Custom", style = MaterialTheme.typography.labelMedium)
                                Switch(
                                    checked = project.useCustomVertex,
                                    onCheckedChange = onToggleCustomVertex,
                                    modifier = Modifier.testTag("toggle_custom_vertex")
                                )
                            }
                        }
                    }

                    // Mesh Subdivision Picker for Vertex Shaders
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Grid4x4,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Mesh:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf(1, 8, 16, 32, 64).forEach { subdiv ->
                            FilterChip(
                                selected = project.meshSubdivisions == subdiv,
                                onClick = { onChangeMeshSubdivisions(subdiv) },
                                label = { Text("${subdiv}×${subdiv}") },
                                modifier = Modifier.testTag("mesh_subdiv_$subdiv")
                            )
                        }
                    }
                }
            }
        } else {
            // Combined GLSL ES helper bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tip: Use // @vertex & // @fragment to combine both stages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        if (!textFieldValue.text.contains("@vertex", ignoreCase = true)) {
                            val scaffold = """
                                // @vertex
                                #version 300 es
                                precision highp float;
                                in vec2 aPosition;
                                in vec2 aTexCoord;
                                out vec2 vUv;
                                void main() {
                                    vUv = aTexCoord;
                                    gl_Position = vec4(aPosition, 0.0, 1.0);
                                }

                                // @fragment
                            """.trimIndent() + "\n" + textFieldValue.text
                            applyTextChange(TextFieldValue(scaffold))
                        }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("+ @vertex")
                }
            }
        }

        // Compile Error Banner
        AnimatedVisibility(visible = !telemetry.compileSuccess && telemetry.compileLog.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("glsl_error_banner")
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = buildString {
                                append(telemetry.errorStage.ifBlank { "GLSL" })
                                append(" Error")
                                telemetry.errorLine?.let { append(" (Line $it)") }
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = telemetry.compileLog.trim(),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        // Quick-Insert Configured Uniforms Bar
        val usableVariables = remember(project.variables) {
            project.variables.filter { it.type != UniformType.NONE }
        }
        if (usableVariables.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Insert:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                usableVariables.forEach { v ->
                    AssistChip(
                        onClick = {
                            val cur = textFieldValue
                            val start = cur.selection.min.coerceIn(0, cur.text.length)
                            val end = cur.selection.max.coerceIn(0, cur.text.length)
                            val updated = cur.text.substring(0, start) + v.name + cur.text.substring(end)
                            applyTextChange(TextFieldValue(updated, TextRange(start + v.name.length)))
                        },
                        label = {
                            Text(
                                text = v.name,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily)
                            )
                        }
                    )
                }
            }
        }

        // Monospace Code Editor with Line Numbers
        val editorEnabled = project.mode == ShaderMode.COMBINED ||
            separateStageTab == SeparateStageTab.FRAGMENT ||
            project.useCustomVertex

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFF0D111A),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(
                    width = 1.dp,
                    color = if (telemetry.compileSuccess) {
                        MaterialTheme.colorScheme.outlineVariant
                    } else {
                        PrismRose.copy(alpha = 0.65f)
                    },
                    shape = MaterialTheme.shapes.medium
                )
        ) {
            if (!editorEnabled) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Built-In Optimized Fullscreen Quad Vertex Shader Active",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Text(
                            text = "Toggle \"Custom\" above to write your own vertex shader and deform the screen mesh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            } else {
                val verticalScroll = rememberScrollState()
                val horizontalScroll = rememberScrollState()
                val lineCount = remember(textFieldValue.text) {
                    textFieldValue.text.count { it == '\n' } + 1
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                        .padding(vertical = 10.dp)
                ) {
                    // Gutter Line Numbers
                    Column(
                        modifier = Modifier
                            .width(40.dp)
                            .padding(end = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        for (line in 1..lineCount) {
                            val isErrLine = telemetry.errorLine == line && !telemetry.compileSuccess
                            Text(
                                text = line.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 19.sp,
                                    fontWeight = if (isErrLine) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (isErrLine) PrismRose else Color(0xFF475569),
                            )
                        }
                    }

                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { applyTextChange(it) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 12.5.sp,
                            lineHeight = 19.sp,
                            color = Color(0xFFE2E8F0),
                        ),
                        cursorBrush = SolidColor(PrismCyan),
                        visualTransformation = highlighter,
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalScroll)
                            .padding(end = 16.dp)
                            .testTag("glsl_code_editor")
                    )
                }
            }
        }
    }
}

private class GlslSyntaxVisualTransformation : VisualTransformation {
    private val keywords = Regex(
        """\b(void|in|out|inout|uniform|attribute|varying|const|precision|highp|mediump|lowp|return|if|else|for|while|break|continue|discard|struct|true|false)\b"""
    )
    private val types = Regex(
        """\b(float|int|uint|bool|vec2|vec3|vec4|ivec2|ivec3|ivec4|bvec2|bvec3|bvec4|mat2|mat3|mat4|sampler2D|samplerCube)\b"""
    )
    private val builtins = Regex(
        """\b(gl_Position|gl_FragColor|gl_FragCoord|texture|texture2D|mix|smoothstep|step|clamp|sin|cos|tan|abs|length|distance|dot|cross|normalize|pow|sqrt|fract|floor|ceil|mod|min|max)\b"""
    )
    private val preprocessor = Regex("""(?m)^\s*#(version|define|ifdef|ifndef|if|else|elif|endif)\b.*$""")
    private val comments = Regex("""(?m)//.*$""")
    private val numbers = Regex("""\b\d+(\.\d+)?\b""")

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val highlighted = buildAnnotatedString {
            append(raw)
            types.findAll(raw).forEach {
                addStyle(SpanStyle(color = PrismCyan, fontWeight = FontWeight.Medium), it.range.first, it.range.last + 1)
            }
            keywords.findAll(raw).forEach {
                addStyle(SpanStyle(color = PrismViolet, fontWeight = FontWeight.SemiBold), it.range.first, it.range.last + 1)
            }
            builtins.findAll(raw).forEach {
                addStyle(SpanStyle(color = PrismEmerald), it.range.first, it.range.last + 1)
            }
            numbers.findAll(raw).forEach {
                addStyle(SpanStyle(color = PrismAmber), it.range.first, it.range.last + 1)
            }
            preprocessor.findAll(raw).forEach {
                addStyle(SpanStyle(color = Color(0xFFF472B6)), it.range.first, it.range.last + 1)
            }
            comments.findAll(raw).forEach {
                addStyle(SpanStyle(color = Color(0xFF64748B)), it.range.first, it.range.last + 1)
            }
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}
