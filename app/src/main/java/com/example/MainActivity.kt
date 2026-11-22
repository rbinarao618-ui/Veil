package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.overlay.ScreenCaptureService
import com.example.ui.components.DiagnosticsPanel
import com.example.ui.components.EngineSettingsScreen
import com.example.ui.components.GlslEditorPanel
import com.example.ui.components.LibraryScreen
import com.example.ui.components.ShaderPreviewCard
import com.example.ui.components.VariablesPanel
import com.example.ui.theme.ShaderVeilTheme
import com.example.ui.viewmodel.MainNavDestination
import com.example.ui.viewmodel.StudioSubTab
import com.example.ui.viewmodel.StudioViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val studioViewModel: StudioViewModel = viewModel()
            val settings by studioViewModel.settings.collectAsStateWithLifecycle()

            ShaderVeilTheme(darkTheme = settings.darkTheme) {
                ShaderVeilAppScreen(viewModel = studioViewModel)
            }
        }
    }
}

@Composable
fun ShaderVeilAppScreen(viewModel: StudioViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentNav by viewModel.currentNav.collectAsStateWithLifecycle()
    val studioTab by viewModel.studioTab.collectAsStateWithLifecycle()
    val separateStageTab by viewModel.separateStageTab.collectAsStateWithLifecycle()
    val activeProject by viewModel.activeProject.collectAsStateWithLifecycle()
    val savedProjects by viewModel.savedProjects.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val renderSnapshot by viewModel.renderSnapshot.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val isOverlayRunning by viewModel.isOverlayRunning.collectAsStateWithLifecycle()
    val isOverlayPaused by viewModel.isOverlayPaused.collectAsStateWithLifecycle()
    val isScreenCaptureActive by viewModel.isScreenCaptureActive.collectAsStateWithLifecycle()
    val screenCaptureSize by viewModel.screenCaptureSize.collectAsStateWithLifecycle()
    val pendingInsertSnippet by viewModel.pendingInsertSnippet.collectAsStateWithLifecycle()
    val runtimeError by viewModel.runtimeError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(runtimeError) {
        val message = runtimeError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearRuntimeError()
    }

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
        if (Settings.canDrawOverlays(context)) {
            viewModel.toggleOverlayService()
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(context, result.resultCode, data)
        }
    }

    fun requestScreenCapture() {
        runCatching {
            val projectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                    ?: throw IllegalStateException("MediaProjection service unavailable")
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            screenCaptureLauncher.launch(intent)
        }.onFailure { err ->
            viewModel.reportRuntimeError(
                "Screen capture prompt unavailable: ${err.localizedMessage ?: err.javaClass.simpleName}"
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasNotificationPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openOverlayPermissionSettings() {
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }.onFailure {
            runCatching {
                val fallback = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(fallback)
            }.onFailure { err ->
                viewModel.reportRuntimeError(
                    "Unable to open overlay settings: ${err.localizedMessage ?: err.javaClass.simpleName}"
                )
            }
        }
    }

    fun handleToggleOverlay() {
        if (isOverlayRunning) {
            viewModel.toggleOverlayService()
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            showOverlayPermissionDialog = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.toggleOverlayService()
        }
    }

    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            title = { Text("Allow Display Over Other Apps") },
            text = {
                Text(
                    "ShaderVeil needs the \"Display over other apps\" permission to draw your compiled GLSL ES shaders system-wide as a pass-through GPU overlay."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayPermissionDialog = false
                        openOverlayPermissionSettings()
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermissionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    BackHandler(enabled = currentNav != MainNavDestination.STUDIO) {
        viewModel.selectNav(MainNavDestination.STUDIO)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= 840.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!isExpandedWidth) {
                    NavigationBar {
                        MainNavDestination.entries.forEach { dest ->
                            val selected = currentNav == dest
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectNav(dest) },
                                icon = {
                                    Icon(
                                        imageVector = navIcon(dest, selected),
                                        contentDescription = navLabel(dest)
                                    )
                                },
                                label = { Text(navLabel(dest)) },
                                modifier = Modifier.testTag("nav_${dest.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpandedWidth) {
                    NavigationRail {
                        MainNavDestination.entries.forEach { dest ->
                            val selected = currentNav == dest
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectNav(dest) },
                                icon = {
                                    Icon(
                                        imageVector = navIcon(dest, selected),
                                        contentDescription = navLabel(dest)
                                    )
                                },
                                label = { Text(navLabel(dest)) },
                                modifier = Modifier.testTag("rail_${dest.name.lowercase()}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (currentNav) {
                        MainNavDestination.STUDIO -> {
                            val previewCard: @Composable (Modifier) -> Unit = { mod ->
                                ShaderPreviewCard(
                                    snapshot = renderSnapshot,
                                    telemetry = telemetry,
                                    isOverlayRunning = isOverlayRunning,
                                    hasOverlayPermission = hasOverlayPermission,
                                    onTogglePreviewPause = viewModel::togglePreviewPaused,
                                    onResetTime = viewModel::resetShaderTime,
                                    onToggleCheckerboard = {
                                        viewModel.updateSettings {
                                            it.copy(showCheckerboardInPreview = !it.showCheckerboardInPreview)
                                        }
                                    },
                                    onToggleOverlay = ::handleToggleOverlay,
                                    modifier = mod,
                                )
                            }

                            val workspaceTabs: @Composable (Modifier) -> Unit = { mod ->
                                Column(modifier = mod) {
                                    SingleChoiceSegmentedButtonRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                    ) {
                                        StudioSubTab.entries.forEachIndexed { index, tab ->
                                            val labelText = if (tab == StudioSubTab.VARIABLES) {
                                                "Variables (${activeProject.variables.size})"
                                            } else {
                                                tab.label
                                            }
                                            SegmentedButton(
                                                selected = studioTab == tab,
                                                onClick = { viewModel.selectStudioTab(tab) },
                                                shape = SegmentedButtonDefaults.itemShape(
                                                    index = index,
                                                    count = StudioSubTab.entries.size
                                                ),
                                                modifier = Modifier.testTag("studio_tab_${tab.name.lowercase()}")
                                            ) {
                                                Text(labelText, style = MaterialTheme.typography.labelLarge)
                                            }
                                        }
                                    }

                                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                        when (studioTab) {
                                            StudioSubTab.VARIABLES -> {
                                                VariablesPanel(
                                                    variables = activeProject.variables,
                                                    telemetry = telemetry,
                                                    autoInjectEnabled = settings.autoInjectUniforms,
                                                    isOverlayRunning = isOverlayRunning,
                                                    isScreenCaptureActive = isScreenCaptureActive,
                                                    screenCaptureSize = screenCaptureSize,
                                                    onAddVariable = viewModel::addVariable,
                                                    onRemoveVariable = viewModel::removeVariable,
                                                    onChangeType = viewModel::changeVariableType,
                                                    onChangeValueMode = viewModel::changeValueMode,
                                                    onRenameVariable = viewModel::renameVariable,
                                                    onUpdateVariable = viewModel::updateVariable,
                                                    onImportTextureUri = viewModel::importTextureForVariable,
                                                    onRequestScreenCapture = ::requestScreenCapture,
                                                    onStopScreenCapture = viewModel::stopScreenCapture,
                                                    onRefreshCleanScreen = viewModel::refreshCleanScreenFrame,
                                                    onInsertIntoEditor = viewModel::requestInsertIntoEditor,
                                                )
                                            }

                                            StudioSubTab.GLSL -> {
                                                GlslEditorPanel(
                                                    project = activeProject,
                                                    telemetry = telemetry,
                                                    separateStageTab = separateStageTab,
                                                    pendingInsertSnippet = pendingInsertSnippet,
                                                    onConsumeInsertSnippet = viewModel::consumePendingInsertSnippet,
                                                    onSelectMode = viewModel::updateMode,
                                                    onSelectSeparateStage = viewModel::selectSeparateStageTab,
                                                    onToggleCustomVertex = viewModel::updateUseCustomVertex,
                                                    onChangeMeshSubdivisions = viewModel::updateMeshSubdivisions,
                                                    onUpdateCombinedSource = viewModel::updateCombinedSource,
                                                    onUpdateVertexSource = viewModel::updateVertexSource,
                                                    onUpdateFragmentSource = viewModel::updateFragmentSource,
                                                )
                                            }

                                            StudioSubTab.DIAGNOSTICS -> {
                                                DiagnosticsPanel(telemetry = telemetry)
                                            }
                                        }
                                    }
                                }
                            }

                            if (isExpandedWidth) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    previewCard(Modifier.weight(0.42f))
                                    workspaceTabs(Modifier.weight(0.58f).fillMaxHeight())
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    previewCard(
                                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                    workspaceTabs(Modifier.weight(1f).fillMaxWidth())
                                }
                            }
                        }

                        MainNavDestination.LIBRARY -> {
                            LibraryScreen(
                                activeProjectId = activeProject.id,
                                savedProjects = savedProjects,
                                onCreateBlank = viewModel::createNewBlankShader,
                                onSelectProject = viewModel::loadProject,
                                onRenameActiveTitle = viewModel::updateTitle,
                                onDuplicateProject = viewModel::duplicateProject,
                                onDeleteProject = viewModel::deleteProject,
                                onLoadTemplate = viewModel::cloneFromTemplate,
                            )
                        }

                        MainNavDestination.ENGINE -> {
                            EngineSettingsScreen(
                                settings = settings,
                                telemetry = telemetry,
                                hasOverlayPermission = hasOverlayPermission,
                                hasNotificationPermission = hasNotificationPermission,
                                isOverlayRunning = isOverlayRunning,
                                isOverlayPaused = isOverlayPaused,
                                onRequestOverlayPermission = ::openOverlayPermissionSettings,
                                onRequestNotificationPermission = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onToggleOverlay = ::handleToggleOverlay,
                                onToggleOverlayPause = {
                                    viewModel.setOverlayPaused(!isOverlayPaused)
                                },
                                onRefreshCleanScreen = viewModel::refreshCleanScreenFrame,
                                onUpdateSettings = viewModel::updateSettings,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun navLabel(dest: MainNavDestination): String = when (dest) {
    MainNavDestination.STUDIO -> "Studio"
    MainNavDestination.LIBRARY -> "Library"
    MainNavDestination.ENGINE -> "Engine"
}

private fun navIcon(dest: MainNavDestination, selected: Boolean) = when (dest) {
    MainNavDestination.STUDIO -> if (selected) Icons.Filled.Tune else Icons.Outlined.Tune
    MainNavDestination.LIBRARY -> if (selected) Icons.Filled.CollectionsBookmark else Icons.Outlined.CollectionsBookmark
    MainNavDestination.ENGINE -> if (selected) Icons.Filled.Speed else Icons.Outlined.Speed
}
