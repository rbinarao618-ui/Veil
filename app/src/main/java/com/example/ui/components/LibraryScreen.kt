package com.example.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ShaderProject
import com.example.shader.ShaderDefaults
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.PrismAmber
import com.example.ui.theme.PrismEmerald

@Composable
fun LibraryScreen(
    activeProjectId: String,
    savedProjects: List<ShaderProject>,
    onCreateBlank: () -> Unit,
    onSelectProject: (ShaderProject) -> Unit,
    onRenameActiveTitle: (String) -> Unit,
    onDuplicateProject: (ShaderProject) -> Unit,
    onDeleteProject: (ShaderProject) -> Unit,
    onLoadTemplate: (ShaderProject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val templates = remember { ShaderDefaults.starterTemplates() }
    var renameProject by remember { mutableStateOf<ShaderProject?>(null) }

    renameProject?.let { target ->
        var titleText by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameProject = null },
            title = { Text("Rename Shader") },
            text = {
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    singleLine = true,
                    label = { Text("Shader Name") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (titleText.isNotBlank()) {
                            onSelectProject(target)
                            onRenameActiveTitle(titleText.trim())
                        }
                        renameProject = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameProject = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("library_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Shader Library", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Saved GLSL projects & real-time overlay templates",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onCreateBlank,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("new_blank_shader_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Blank")
                }
            }
        }

        if (savedProjects.isNotEmpty()) {
            item {
                Text(
                    text = "Your Shaders (${savedProjects.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(savedProjects, key = { it.id }) { project ->
                val isActive = project.id == activeProjectId
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .border(
                            width = 1.dp,
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = MaterialTheme.shapes.medium
                        )
                        .clickable { onSelectProject(project) }
                        .testTag("saved_shader_${project.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isActive) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Active shader",
                                        tint = PrismEmerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = project.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "${project.mode.label} · ${project.variables.size} variables · ${project.meshSubdivisions}×${project.meshSubdivisions} mesh",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { renameProject = project },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Rename shader",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onDuplicateProject(project) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Duplicate shader",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (savedProjects.size > 1) {
                                IconButton(
                                    onClick = { onDeleteProject(project) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete shader",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.size(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = PrismAmber,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Starter Templates",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        items(templates, key = { it.id }) { tpl ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                    .testTag("template_${tpl.id}")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(tpl.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${tpl.mode.label} · ${tpl.variables.size} preset uniforms",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { onLoadTemplate(tpl) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("load_template_${tpl.id}")
                    ) {
                        Text("Load Copy")
                    }
                }
            }
        }
    }
}
