package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ShaderVeilApp
import com.example.data.OverlayRuntimeBridge
import com.example.model.EngineSettings
import com.example.model.ShaderMode
import com.example.model.ShaderProject
import com.example.model.ShaderTelemetry
import com.example.model.ShaderVariable
import com.example.model.UniformType
import com.example.model.ValueMode
import com.example.overlay.ScreenCaptureService
import com.example.overlay.ShaderOverlayService
import com.example.shader.RenderSnapshot
import com.example.shader.ScreenCaptureManager
import com.example.shader.ShaderDefaults
import com.example.shader.TextureLoader
import com.example.shader.UniformNaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class MainNavDestination {
    STUDIO,
    LIBRARY,
    ENGINE,
}

enum class StudioSubTab(val label: String) {
    VARIABLES("Variables"),
    GLSL("GLSL Code"),
    DIAGNOSTICS("Diagnostics"),
}

enum class SeparateStageTab(val label: String) {
    FRAGMENT("Fragment"),
    VERTEX("Vertex (Optional)"),
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ShaderVeilApp
    private val repository = app.repository
    private val settingsStore = app.settingsStore

    private val _currentNav = MutableStateFlow(MainNavDestination.STUDIO)
    val currentNav: StateFlow<MainNavDestination> = _currentNav.asStateFlow()

    private val _studioTab = MutableStateFlow(StudioSubTab.VARIABLES)
    val studioTab: StateFlow<StudioSubTab> = _studioTab.asStateFlow()

    private val _separateStageTab = MutableStateFlow(SeparateStageTab.FRAGMENT)
    val separateStageTab: StateFlow<SeparateStageTab> = _separateStageTab.asStateFlow()

    private val _activeProject = MutableStateFlow(ShaderDefaults.createBlankProject())
    val activeProject: StateFlow<ShaderProject> = _activeProject.asStateFlow()

    val savedProjects: StateFlow<List<ShaderProject>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<EngineSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EngineSettings())

    val renderSnapshot: StateFlow<RenderSnapshot> = OverlayRuntimeBridge.snapshot
    val telemetry: StateFlow<ShaderTelemetry> = OverlayRuntimeBridge.telemetry
    val isOverlayRunning: StateFlow<Boolean> = OverlayRuntimeBridge.isOverlayRunning
    val isOverlayPaused: StateFlow<Boolean> = OverlayRuntimeBridge.isOverlayPaused
    val isScreenCaptureActive: StateFlow<Boolean> = ScreenCaptureManager.isCapturing
    val screenCaptureSize: StateFlow<Pair<Int, Int>> = ScreenCaptureManager.captureSize
    val runtimeError: StateFlow<String?> = OverlayRuntimeBridge.runtimeError

    private val _pendingInsertSnippet = MutableStateFlow<String?>(null)
    val pendingInsertSnippet: StateFlow<String?> = _pendingInsertSnippet.asStateFlow()

    private var saveJob: Job? = null
    private var userInteractedBeforeInit = false

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { engineSettings ->
                OverlayRuntimeBridge.updateSettings(engineSettings)
            }
        }

        viewModelScope.launch {
            val count = repository.count()
            if (count == 0) {
                val blank = ShaderDefaults.createBlankProject()
                repository.saveProject(blank)
                if (!userInteractedBeforeInit) {
                    settingsStore.setActiveShaderId(blank.id)
                    setActiveProjectInternal(blank, persist = false)
                }
            } else if (!userInteractedBeforeInit) {
                val savedId = settingsStore.activeShaderIdFlow.first()
                val loaded = savedId?.let { repository.getById(it) }
                    ?: repository.allProjects.first().firstOrNull()
                    ?: ShaderDefaults.createBlankProject()
                if (!userInteractedBeforeInit) {
                    setActiveProjectInternal(loaded, persist = false)
                }
            }
        }
    }

    fun selectNav(dest: MainNavDestination) {
        _currentNav.value = dest
    }

    fun selectStudioTab(tab: StudioSubTab) {
        _studioTab.value = tab
    }

    fun selectSeparateStageTab(tab: SeparateStageTab) {
        _separateStageTab.value = tab
    }

    fun togglePreviewPaused() {
        val current = OverlayRuntimeBridge.snapshot.value.isPaused
        OverlayRuntimeBridge.setPreviewPaused(!current)
    }

    fun resetShaderTime() {
        OverlayRuntimeBridge.triggerTimeReset()
    }

    fun toggleOverlayService() {
        if (OverlayRuntimeBridge.isOverlayRunning.value) {
            ShaderOverlayService.stop(app)
        } else {
            ShaderOverlayService.start(app)
        }
    }

    fun setOverlayPaused(paused: Boolean) {
        OverlayRuntimeBridge.setOverlayPaused(paused)
    }

    fun stopScreenCapture() {
        ScreenCaptureService.stop(app)
    }

    fun refreshCleanScreenFrame() {
        if (OverlayRuntimeBridge.isOverlayRunning.value) {
            ShaderOverlayService.refreshCleanScreen(app)
        }
    }

    fun reportRuntimeError(message: String) {
        OverlayRuntimeBridge.reportRuntimeError(message)
    }

    fun clearRuntimeError() {
        OverlayRuntimeBridge.clearRuntimeError()
    }

    fun updateSettings(transform: (EngineSettings) -> EngineSettings) {
        viewModelScope.launch {
            settingsStore.updateSettings(transform)
        }
    }

    // --- Shader Editing ---

    fun updateTitle(title: String) {
        mutateProject { it.copy(title = title) }
    }

    fun updateMode(mode: ShaderMode) {
        mutateProject { it.copy(mode = mode) }
    }

    fun updateUseCustomVertex(enabled: Boolean) {
        mutateProject { it.copy(useCustomVertex = enabled) }
    }

    fun updateMeshSubdivisions(subdivisions: Int) {
        mutateProject { it.copy(meshSubdivisions = subdivisions.coerceIn(1, 64)) }
    }

    fun updateCombinedSource(source: String) {
        mutateProject { it.copy(combinedSource = source) }
    }

    fun updateVertexSource(source: String) {
        mutateProject { it.copy(vertexSource = source) }
    }

    fun updateFragmentSource(source: String) {
        mutateProject { it.copy(fragmentSource = source) }
    }

    fun requestInsertIntoEditor(snippet: String) {
        _pendingInsertSnippet.value = snippet
        _studioTab.value = StudioSubTab.GLSL
    }

    fun consumePendingInsertSnippet() {
        _pendingInsertSnippet.value = null
    }

    // --- Variables / Uniforms Management ---

    fun addVariable(type: UniformType) {
        mutateProject { project ->
            val defaultMode = ValueMode.CONSTANT
            val autoName = UniformNaming.generateAutoName(
                type = type,
                valueMode = defaultMode,
                existingVariables = project.variables,
            )
            val newVar = ShaderVariable(
                name = autoName,
                isAutoNamed = true,
                type = type,
                valueMode = defaultMode,
            )
            project.copy(variables = project.variables + newVar)
        }
    }

    fun removeVariable(id: String) {
        mutateProject { project ->
            project.copy(variables = project.variables.filterNot { it.id == id })
        }
    }

    fun changeVariableType(id: String, newType: UniformType) {
        mutateProject { project ->
            val updated = project.variables.map { v ->
                if (v.id != id) v else {
                    val nextName = if (v.isAutoNamed) {
                        UniformNaming.generateAutoName(
                            type = newType,
                            valueMode = v.valueMode,
                            existingVariables = project.variables,
                            excludeId = v.id,
                        )
                    } else {
                        v.name
                    }
                    v.copy(type = newType, name = nextName)
                }
            }
            project.copy(variables = updated)
        }
    }

    fun changeValueMode(id: String, newMode: ValueMode) {
        mutateProject { project ->
            val updated = project.variables.map { v ->
                if (v.id != id) v else {
                    val nextName = if (v.isAutoNamed) {
                        UniformNaming.generateAutoName(
                            type = v.type,
                            valueMode = newMode,
                            existingVariables = project.variables,
                            excludeId = v.id,
                        )
                    } else {
                        v.name
                    }
                    v.copy(valueMode = newMode, name = nextName)
                }
            }
            project.copy(variables = updated)
        }
    }

    fun renameVariable(id: String, rawName: String) {
        mutateProject { project ->
            val updated = project.variables.map { v ->
                if (v.id != id) v else {
                    val sanitized = UniformNaming.sanitizeIdentifier(
                        raw = rawName,
                        fallbackType = v.type,
                        fallbackValueMode = v.valueMode,
                        existingVariables = project.variables,
                        excludeId = v.id,
                    )
                    v.copy(name = sanitized, isAutoNamed = false)
                }
            }
            project.copy(variables = updated)
        }
    }

    fun updateVariable(id: String, transform: (ShaderVariable) -> ShaderVariable) {
        mutateProject { project ->
            project.copy(
                variables = project.variables.map { v ->
                    if (v.id == id) transform(v) else v
                }
            )
        }
    }

    fun importTextureForVariable(variableId: String, uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                TextureLoader.importPickerUriToInternalStorage(app, uri)
            }
            if (path != null) {
                updateVariable(variableId) {
                    it.copy(textureUri = path)
                }
            }
        }
    }

    // --- Library & Templates ---

    fun createNewBlankShader() {
        val blank = ShaderDefaults.createBlankProject().copy(
            id = UUID.randomUUID().toString(),
            title = "Blank Veil ${(savedProjects.value.size + 1)}",
            updatedAt = System.currentTimeMillis(),
        )
        setActiveProjectInternal(blank, persist = true)
        _currentNav.value = MainNavDestination.STUDIO
        _studioTab.value = StudioSubTab.VARIABLES
    }

    fun loadProject(project: ShaderProject) {
        setActiveProjectInternal(project, persist = false)
        viewModelScope.launch {
            settingsStore.setActiveShaderId(project.id)
        }
        _currentNav.value = MainNavDestination.STUDIO
        _studioTab.value = StudioSubTab.VARIABLES
    }

    fun cloneFromTemplate(template: ShaderProject) {
        val copy = template.copy(
            id = UUID.randomUUID().toString(),
            title = template.title,
            variables = template.variables.map { it.copy(id = UUID.randomUUID().toString()) },
            updatedAt = System.currentTimeMillis(),
        )
        setActiveProjectInternal(copy, persist = true)
        _currentNav.value = MainNavDestination.STUDIO
        _studioTab.value = StudioSubTab.VARIABLES
    }

    fun duplicateProject(project: ShaderProject) {
        val copy = project.copy(
            id = UUID.randomUUID().toString(),
            title = "${project.title} Copy",
            variables = project.variables.map { it.copy(id = UUID.randomUUID().toString()) },
            updatedAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            repository.saveProject(copy)
        }
    }

    fun deleteProject(project: ShaderProject) {
        viewModelScope.launch {
            repository.deleteProject(project.id)
            if (_activeProject.value.id == project.id) {
                val remaining = repository.allProjects.first().firstOrNull()
                if (remaining != null) {
                    setActiveProjectInternal(remaining, persist = false)
                } else {
                    val fresh = ShaderDefaults.createBlankProject()
                    setActiveProjectInternal(fresh, persist = true)
                }
            }
        }
    }

    private fun mutateProject(transform: (ShaderProject) -> ShaderProject) {
        userInteractedBeforeInit = true
        val next = transform(_activeProject.value).copy(updatedAt = System.currentTimeMillis())
        setActiveProjectInternal(next, persist = true)
    }

    private fun setActiveProjectInternal(project: ShaderProject, persist: Boolean) {
        if (persist) userInteractedBeforeInit = true
        _activeProject.value = project
        OverlayRuntimeBridge.updateProject(project)
        if (persist) {
            saveJob?.cancel()
            saveJob = viewModelScope.launch {
                settingsStore.setActiveShaderId(project.id)
                delay(280)
                repository.saveProject(project)
            }
        }
    }
}
