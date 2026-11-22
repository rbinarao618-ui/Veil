package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.model.BuiltInTexture
import com.example.model.ClockTimeUnit
import com.example.model.ShaderTelemetry
import com.example.model.ShaderVariable
import com.example.model.TextureFilterMode
import com.example.model.TextureWrapMode
import com.example.model.UniformType
import com.example.model.ValueMode
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.PrismAmber
import com.example.ui.theme.PrismCyan
import com.example.ui.theme.PrismEmerald
import com.example.ui.theme.PrismViolet
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VariablesPanel(
    variables: List<ShaderVariable>,
    telemetry: ShaderTelemetry,
    autoInjectEnabled: Boolean,
    isOverlayRunning: Boolean,
    isScreenCaptureActive: Boolean,
    screenCaptureSize: Pair<Int, Int>,
    onAddVariable: (UniformType) -> Unit,
    onRemoveVariable: (String) -> Unit,
    onChangeType: (String, UniformType) -> Unit,
    onChangeValueMode: (String, ValueMode) -> Unit,
    onRenameVariable: (String, String) -> Unit,
    onUpdateVariable: (String, (ShaderVariable) -> ShaderVariable) -> Unit,
    onImportTextureUri: (String, Uri) -> Unit,
    onRequestScreenCapture: () -> Unit,
    onStopScreenCapture: () -> Unit,
    onRefreshCleanScreen: () -> Unit,
    onInsertIntoEditor: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingTextureVariableId by remember { mutableStateOf<String?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            val targetId = pendingTextureVariableId
            pendingTextureVariableId = null
            if (uri != null && targetId != null) {
                onImportTextureUri(targetId, uri)
            }
        }
    )

    var renameTarget by remember { mutableStateOf<ShaderVariable?>(null) }

    renameTarget?.let { target ->
        RenameVariableDialog(
            initialName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                onRenameVariable(target.id, newName)
                renameTarget = null
            }
        )
    }

    if (variables.isEmpty()) {
        // Intentional blank Variables/Uniforms empty state with direct setup buttons
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.large
                        )
                        .testTag("variables_empty_state")
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Text(
                            text = "Variables / Uniforms Menu is Blank",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "No uniforms have been configured yet. Add a variable below to automatically name it and control your GLSL shader in real time:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            UniformType.entries.forEach { type ->
                                QuickAddTypeButton(
                                    type = type,
                                    onClick = { onAddVariable(type) }
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .testTag("variables_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AddVariableBar(onAddVariable = onAddVariable)
            }

            items(variables, key = { it.id }) { variable ->
                val isActiveInShader = when (variable.type) {
                    UniformType.NONE -> false
                    UniformType.DEFINITION -> variable.defineEnabled &&
                        (variable.name in telemetry.activeDefineNames)
                    else -> variable.name in telemetry.activeUniformNames
                }

                VariableCard(
                    variable = variable,
                    isActiveInShader = isActiveInShader,
                    autoInjectEnabled = autoInjectEnabled,
                    isOverlayRunning = isOverlayRunning,
                    isScreenCaptureActive = isScreenCaptureActive,
                    screenCaptureSize = screenCaptureSize,
                    onChangeType = { onChangeType(variable.id, it) },
                    onChangeValueMode = { onChangeValueMode(variable.id, it) },
                    onRequestRename = { renameTarget = variable },
                    onUpdate = { transform -> onUpdateVariable(variable.id, transform) },
                    onPickTexture = {
                        pendingTextureVariableId = variable.id
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRequestScreenCapture = onRequestScreenCapture,
                    onStopScreenCapture = onStopScreenCapture,
                    onRefreshCleanScreen = onRefreshCleanScreen,
                    onInsertIntoEditor = { onInsertIntoEditor(variable.name) },
                    onDelete = { onRemoveVariable(variable.id) },
                )
            }
        }
    }
}

@Composable
private fun QuickAddTypeButton(
    type: UniformType,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("empty_add_${type.name.lowercase()}"),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(
            imageVector = typeIcon(type),
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(type.label)
    }
}

@Composable
private fun AddVariableBar(
    onAddVariable: (UniformType) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Button(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("add_variable_button"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Variable")
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                UniformType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label) },
                        leadingIcon = { Icon(typeIcon(type), contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAddVariable(type)
                        },
                        modifier = Modifier.testTag("menu_add_${type.name.lowercase()}")
                    )
                }
            }
        }

        UniformType.entries.forEach { type ->
            AssistChip(
                onClick = { onAddVariable(type) },
                label = { Text("+ ${type.shortLabel}") },
                modifier = Modifier.testTag("chip_add_${type.name.lowercase()}"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariableCard(
    variable: ShaderVariable,
    isActiveInShader: Boolean,
    autoInjectEnabled: Boolean,
    isOverlayRunning: Boolean,
    isScreenCaptureActive: Boolean,
    screenCaptureSize: Pair<Int, Int>,
    onChangeType: (UniformType) -> Unit,
    onChangeValueMode: (ValueMode) -> Unit,
    onRequestRename: () -> Unit,
    onUpdate: ((ShaderVariable) -> ShaderVariable) -> Unit,
    onPickTexture: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onStopScreenCapture: () -> Unit,
    onRefreshCleanScreen: () -> Unit,
    onInsertIntoEditor: () -> Unit,
    onDelete: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var typeMenuOpen by remember { mutableStateOf(false) }

    val accentColor = when (variable.type) {
        UniformType.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        UniformType.VALUE -> PrismCyan
        UniformType.TEXTURE -> PrismViolet
        UniformType.CHECKBOX -> PrismEmerald
        UniformType.DEFINITION -> PrismAmber
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium
            )
            .testTag("variable_card_${variable.name}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Card Header: Type Selector + Auto-Generated GLSL Name + Status + Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box {
                        Surface(
                            color = accentColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { typeMenuOpen = true }
                                .testTag("type_selector_${variable.name}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = typeIcon(variable.type),
                                    contentDescription = "Change variable type",
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = variable.type.shortLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = accentColor,
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = typeMenuOpen,
                            onDismissRequest = { typeMenuOpen = false }
                        ) {
                            UniformType.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.label) },
                                    leadingIcon = { Icon(typeIcon(t), contentDescription = null) },
                                    onClick = {
                                        typeMenuOpen = false
                                        onChangeType(t)
                                    }
                                )
                            }
                        }
                    }

                    // Uniform Name Pill + Edit
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRequestRename)
                            .testTag("rename_var_${variable.name}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isActiveInShader) PrismEmerald
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    )
                            )
                            Text(
                                text = variable.name,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Rename uniform",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (variable.type != UniformType.NONE) {
                        IconButton(
                            onClick = onInsertIntoEditor,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("insert_var_${variable.name}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = "Insert uniform into GLSL editor",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("delete_var_${variable.name}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete variable",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // GLSL Declaration Preview & Auto-declare toggle
            if (variable.type != UniformType.NONE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(variable.glslDeclaration()))
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy declaration",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = variable.glslDeclaration(),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (variable.type != UniformType.DEFINITION && autoInjectEnabled) {
                        FilterChip(
                            selected = variable.autoDeclare,
                            onClick = { onUpdate { it.copy(autoDeclare = !it.autoDeclare) } },
                            label = {
                                Text(
                                    text = if (variable.autoDeclare) "Auto-Inject" else "Manual Decl",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            // Type-Specific Real-Time Controls
            when (variable.type) {
                UniformType.NONE -> {
                    Text(
                        text = "Unassigned slot. Tap \"None\" above to configure as Value, Texture, Checkbox, or #define.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                UniformType.VALUE -> {
                    ValueControls(
                        variable = variable,
                        onChangeValueMode = onChangeValueMode,
                        onUpdate = onUpdate,
                    )
                }

                UniformType.TEXTURE -> {
                    TextureControls(
                        variable = variable,
                        isOverlayRunning = isOverlayRunning,
                        isScreenCaptureActive = isScreenCaptureActive,
                        screenCaptureSize = screenCaptureSize,
                        onPickTexture = onPickTexture,
                        onRequestScreenCapture = onRequestScreenCapture,
                        onStopScreenCapture = onStopScreenCapture,
                        onRefreshCleanScreen = onRefreshCleanScreen,
                        onUpdate = onUpdate,
                    )
                }

                UniformType.CHECKBOX -> {
                    CheckboxControls(
                        variable = variable,
                        onUpdate = onUpdate,
                    )
                }

                UniformType.DEFINITION -> {
                    DefinitionControls(
                        variable = variable,
                        onUpdate = onUpdate,
                    )
                }
            }
        }
    }
}

@Composable
private fun ValueControls(
    variable: ShaderVariable,
    onChangeValueMode: (ValueMode) -> Unit,
    onUpdate: ((ShaderVariable) -> ShaderVariable) -> Unit,
) {
    var showRangeSettings by remember { mutableStateOf(false) }

    // Value Mode selector pills
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ValueMode.entries.forEach { mode ->
            FilterChip(
                selected = variable.valueMode == mode,
                onClick = { onChangeValueMode(mode) },
                label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.testTag("value_mode_${mode.name.lowercase()}_${variable.name}")
            )
        }
    }

    when (variable.valueMode) {
        ValueMode.CONSTANT -> {
            val safeMin = variable.minValue
            val safeMax = if (variable.maxValue > safeMin) variable.maxValue else safeMin + 1f
            val clamped = variable.constantValue.coerceIn(safeMin, safeMax)

            PrecisionSliderRow(
                label = "Value",
                value = clamped,
                range = safeMin..safeMax,
                step = variable.stepValue,
                onValueChange = { next ->
                    onUpdate { it.copy(constantValue = next) }
                },
                sliderTestTag = "slider_const_${variable.name}",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0f to 1f, -1f to 1f, 0f to 10f, 0f to 500f).forEach { (lo, hi) ->
                        AssistChip(
                            onClick = {
                                onUpdate {
                                    it.copy(
                                        minValue = lo,
                                        maxValue = hi,
                                        stepValue = if (hi >= 100f) 1f else 0.01f,
                                        constantValue = it.constantValue.coerceIn(lo, hi)
                                    )
                                }
                            },
                            label = {
                                Text(
                                    "${formatCompact(lo)}..${formatCompact(hi)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }

                TextButton(
                    onClick = { showRangeSettings = !showRangeSettings },
                    modifier = Modifier.testTag("range_settings_toggle_${variable.name}")
                ) {
                    Text(if (showRangeSettings) "Hide Range" else "Bounds")
                }
            }

            AnimatedVisibility(visible = showRangeSettings) {
                RangeConfigRow(
                    minVal = variable.minValue,
                    maxVal = variable.maxValue,
                    stepVal = variable.stepValue,
                    onApply = { minV, maxV, stepV ->
                        onUpdate {
                            it.copy(
                                minValue = minV,
                                maxValue = maxV.coerceAtLeast(minV + 0.001f),
                                stepValue = stepV.coerceAtLeast(0.0001f),
                                constantValue = it.constantValue.coerceIn(minV, maxV.coerceAtLeast(minV + 0.001f))
                            )
                        }
                    }
                )
            }
        }

        ValueMode.TOTAL_TIME -> {
            PrecisionSliderRow(
                label = "Speed Multiplier",
                value = variable.timeSpeed.coerceIn(-5f, 5f),
                range = -5f..5f,
                step = 0.05f,
                suffix = "x",
                onValueChange = { next -> onUpdate { it.copy(timeSpeed = next) } },
                sliderTestTag = "slider_time_speed_${variable.name}"
            )
            PrecisionSliderRow(
                label = "Time Offset",
                value = variable.timeOffset.coerceIn(-30f, 30f),
                range = -30f..30f,
                step = 0.1f,
                suffix = "s",
                onValueChange = { next -> onUpdate { it.copy(timeOffset = next) } },
                sliderTestTag = "slider_time_offset_${variable.name}"
            )
            PrecisionSliderRow(
                label = "Loop Period (0 = Infinite)",
                value = variable.timeLoop.coerceIn(0f, 60f),
                range = 0f..60f,
                step = 0.5f,
                suffix = "s",
                onValueChange = { next -> onUpdate { it.copy(timeLoop = next) } },
                sliderTestTag = "slider_time_loop_${variable.name}"
            )
        }

        ValueMode.CLOCK_TIME -> {
            var unitMenuOpen by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Clock Unit", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(
                        onClick = { unitMenuOpen = true },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text(variable.clockUnit.label, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = unitMenuOpen,
                        onDismissRequest = { unitMenuOpen = false }
                    ) {
                        ClockTimeUnit.entries.forEach { unit ->
                            DropdownMenuItem(
                                text = { Text(unit.label) },
                                onClick = {
                                    unitMenuOpen = false
                                    onUpdate { it.copy(clockUnit = unit) }
                                }
                            )
                        }
                    }
                }
            }

            PrecisionSliderRow(
                label = "Scale",
                value = variable.clockScale.coerceIn(0.01f, 10f),
                range = 0.01f..10f,
                step = 0.05f,
                suffix = "x",
                onValueChange = { next -> onUpdate { it.copy(clockScale = next) } },
                sliderTestTag = "slider_clock_scale_${variable.name}"
            )
            PrecisionSliderRow(
                label = "Offset",
                value = variable.clockOffset.coerceIn(-12f, 12f),
                range = -12f..12f,
                step = 0.05f,
                onValueChange = { next -> onUpdate { it.copy(clockOffset = next) } },
                sliderTestTag = "slider_clock_offset_${variable.name}"
            )
        }

        ValueMode.SINE_WAVE -> {
            PrecisionSliderRow(
                label = "Frequency (Hz)",
                value = variable.waveFrequency.coerceIn(0.05f, 10f),
                range = 0.05f..10f,
                step = 0.05f,
                suffix = "Hz",
                onValueChange = { next -> onUpdate { it.copy(waveFrequency = next) } },
                sliderTestTag = "slider_wave_freq_${variable.name}"
            )
            PrecisionSliderRow(
                label = "Amplitude",
                value = variable.waveAmplitude.coerceIn(0f, 5f),
                range = 0f..5f,
                step = 0.02f,
                onValueChange = { next -> onUpdate { it.copy(waveAmplitude = next) } },
                sliderTestTag = "slider_wave_amp_${variable.name}"
            )
            PrecisionSliderRow(
                label = "Phase & Bias",
                value = variable.waveBias.coerceIn(-2f, 2f),
                range = -2f..2f,
                step = 0.02f,
                onValueChange = { next -> onUpdate { it.copy(waveBias = next) } },
                sliderTestTag = "slider_wave_bias_${variable.name}"
            )
        }

        ValueMode.FRAME_DELTA -> {
            PrecisionSliderRow(
                label = "Delta Scale",
                value = variable.timeSpeed.coerceIn(0.1f, 10f),
                range = 0.1f..10f,
                step = 0.1f,
                suffix = "x",
                onValueChange = { next -> onUpdate { it.copy(timeSpeed = next) } },
                sliderTestTag = "slider_delta_scale_${variable.name}"
            )
        }

        ValueMode.RESOLUTION_X, ValueMode.RESOLUTION_Y -> {
            PrecisionSliderRow(
                label = "Resolution Multiplier",
                value = variable.constantValue.coerceIn(0.1f, 2f),
                range = 0.1f..2f,
                step = 0.05f,
                suffix = "x",
                onValueChange = { next -> onUpdate { it.copy(constantValue = next) } },
                sliderTestTag = "slider_res_scale_${variable.name}"
            )
        }
    }
}

@Composable
private fun PrecisionSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    suffix: String = "",
    onValueChange: (Float) -> Unit,
    sliderTestTag: String,
) {
    val effectiveStep = step.coerceAtLeast(0.0001f)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = {
                        onValueChange((value - effectiveStep).coerceIn(range.start, range.endInclusive))
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Decrease $label",
                        modifier = Modifier.size(16.dp)
                    )
                }

                Text(
                    text = "${String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')}$suffix",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )

                IconButton(
                    onClick = {
                        onValueChange((value + effectiveStep).coerceIn(range.start, range.endInclusive))
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Increase $label",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { raw ->
                val snapped = (raw / effectiveStep).roundToLong() * effectiveStep
                onValueChange(snapped.coerceIn(range.start, range.endInclusive))
            },
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(sliderTestTag)
        )
    }
}

@Composable
private fun RangeConfigRow(
    minVal: Float,
    maxVal: Float,
    stepVal: Float,
    onApply: (Float, Float, Float) -> Unit,
) {
    var minText by remember(minVal) { mutableStateOf(formatCompact(minVal)) }
    var maxText by remember(maxVal) { mutableStateOf(formatCompact(maxVal)) }
    var stepText by remember(stepVal) { mutableStateOf(formatCompact(stepVal)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = minText,
            onValueChange = { minText = it },
            label = { Text("Min") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = maxText,
            onValueChange = { maxText = it },
            label = { Text("Max") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = stepText,
            onValueChange = { stepText = it },
            label = { Text("Step") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = {
                val lo = minText.toFloatOrNull() ?: minVal
                val hi = maxText.toFloatOrNull() ?: maxVal
                val st = stepText.toFloatOrNull() ?: stepVal
                onApply(lo, hi, st)
            },
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Text("Set")
        }
    }
}

@Composable
private fun TextureControls(
    variable: ShaderVariable,
    isOverlayRunning: Boolean,
    isScreenCaptureActive: Boolean,
    screenCaptureSize: Pair<Int, Int>,
    onPickTexture: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onStopScreenCapture: () -> Unit,
    onRefreshCleanScreen: () -> Unit,
    onUpdate: ((ShaderVariable) -> ShaderVariable) -> Unit,
) {
    val isLiveScreenSelected = variable.textureUri.isNullOrBlank() &&
        variable.builtInTexture == BuiltInTexture.SCREEN_CAPTURE

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        if (isLiveScreenSelected && isScreenCaptureActive) PrismEmerald else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!variable.textureUri.isNullOrBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(File(variable.textureUri)),
                        contentDescription = "Selected shader texture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (isLiveScreenSelected) {
                    Icon(
                        imageVector = Icons.Default.Smartphone,
                        contentDescription = "Live screen texture",
                        tint = if (isScreenCaptureActive) PrismEmerald else PrismCyan
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = PrismViolet
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when {
                        !variable.textureUri.isNullOrBlank() -> "Custom Image Texture"
                        isLiveScreenSelected && isScreenCaptureActive && screenCaptureSize.first > 0 ->
                            "Live Screen (${screenCaptureSize.first}×${screenCaptureSize.second})"
                        isLiveScreenSelected -> "Live Screen Texture"
                        else -> "Built-in: ${variable.builtInTexture.label}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onPickTexture,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("pick_texture_${variable.name}")
                    ) {
                        Text("Pick Photo")
                    }
                    if (!variable.textureUri.isNullOrBlank()) {
                        TextButton(
                            onClick = { onUpdate { it.copy(textureUri = null) } }
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }

        // Texture types (Live Screen + Built-in procedural textures)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BuiltInTexture.entries.filter { it != BuiltInTexture.NONE }.forEach { preset ->
                val selected = variable.textureUri == null && variable.builtInTexture == preset
                FilterChip(
                    selected = selected,
                    onClick = {
                        onUpdate {
                            it.copy(
                                textureUri = null,
                                builtInTexture = preset,
                                wrapMode = if (preset == BuiltInTexture.SCREEN_CAPTURE) {
                                    TextureWrapMode.CLAMP_TO_EDGE
                                } else {
                                    it.wrapMode
                                }
                            )
                        }
                        if (preset == BuiltInTexture.SCREEN_CAPTURE && !isScreenCaptureActive) {
                            onRequestScreenCapture()
                        }
                    },
                    leadingIcon = if (preset == BuiltInTexture.SCREEN_CAPTURE) {
                        {
                            Icon(
                                imageVector = Icons.Default.Smartphone,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null,
                    label = { Text(preset.label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.testTag("texture_preset_${preset.id}_${variable.name}")
                )
            }
        }

        if (isLiveScreenSelected) {
            Surface(
                color = if (isScreenCaptureActive) {
                    PrismEmerald.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("screen_capture_status_${variable.name}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (isScreenCaptureActive) {
                            "Streaming live screen pixels to sampler2D"
                        } else {
                            "Grant screen capture to sample the live display"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isScreenCaptureActive) {
                            PrismEmerald
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    if (isScreenCaptureActive) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isOverlayRunning) {
                                TextButton(
                                    onClick = onRefreshCleanScreen,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("refresh_screen_capture_${variable.name}")
                                ) {
                                    Text("Refresh")
                                }
                            }
                            FilledTonalButton(
                                onClick = onStopScreenCapture,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("stop_screen_capture_${variable.name}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Stop")
                            }
                        }
                    } else {
                        Button(
                            onClick = onRequestScreenCapture,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("start_screen_capture_${variable.name}")
                        ) {
                            Text("Capture")
                        }
                    }
                }
            }
        }

        // Filter & Wrap Mode
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextureFilterMode.entries.forEach { fm ->
                FilterChip(
                    selected = variable.filterMode == fm,
                    onClick = { onUpdate { it.copy(filterMode = fm) } },
                    label = { Text(fm.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextureWrapMode.entries.forEach { wm ->
                FilterChip(
                    selected = variable.wrapMode == wm,
                    onClick = { onUpdate { it.copy(wrapMode = wm) } },
                    label = { Text(wm.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
    }
}

@Composable
private fun CheckboxControls(
    variable: ShaderVariable,
    onUpdate: ((ShaderVariable) -> ShaderVariable) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (variable.checked) "State: TRUE (1)" else "State: FALSE (0)",
                style = MaterialTheme.typography.titleSmall,
                color = if (variable.checked) PrismEmerald else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !variable.checkboxAsFloat,
                    onClick = { onUpdate { it.copy(checkboxAsFloat = false) } },
                    label = { Text("bool", style = MaterialTheme.typography.labelMedium) }
                )
                FilterChip(
                    selected = variable.checkboxAsFloat,
                    onClick = { onUpdate { it.copy(checkboxAsFloat = true) } },
                    label = { Text("float (0/1)", style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        Switch(
            checked = variable.checked,
            onCheckedChange = { next -> onUpdate { it.copy(checked = next) } },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("checkbox_switch_${variable.name}")
        )
    }
}

@Composable
private fun DefinitionControls(
    variable: ShaderVariable,
    onUpdate: ((ShaderVariable) -> ShaderVariable) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = variable.defineValue,
            onValueChange = { next -> onUpdate { it.copy(defineValue = next) } },
            label = { Text("Macro Value (optional)") },
            placeholder = { Text("1") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
            modifier = Modifier
                .weight(1f)
                .testTag("define_value_input_${variable.name}")
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (variable.defineEnabled) "Active" else "Off",
                style = MaterialTheme.typography.labelMedium,
                color = if (variable.defineEnabled) PrismAmber else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Switch(
                checked = variable.defineEnabled,
                onCheckedChange = { next -> onUpdate { it.copy(defineEnabled = next) } },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("define_switch_${variable.name}")
            )
        }
    }
}

@Composable
private fun RenameVariableDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename GLSL Identifier") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Invalid characters or GLSL keywords will be sanitized automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = JetBrainsMonoFamily),
                    label = { Text("Uniform / Macro Name") }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun typeIcon(type: UniformType) = when (type) {
    UniformType.NONE -> Icons.Default.RadioButtonUnchecked
    UniformType.VALUE -> Icons.Default.Tune
    UniformType.TEXTURE -> Icons.Default.Image
    UniformType.CHECKBOX -> Icons.Default.CheckBox
    UniformType.DEFINITION -> Icons.Default.AddCircleOutline
}

private fun formatCompact(v: Float): String {
    return if (v == v.toInt().toFloat()) v.toInt().toString()
    else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}
