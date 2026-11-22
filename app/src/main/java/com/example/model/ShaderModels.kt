package com.example.model

import java.util.UUID

enum class ShaderMode(val label: String) {
    COMBINED("Combined GLSL ES"),
    SEPARATE("Vertex + Fragment"),
}

enum class UniformType(val label: String, val shortLabel: String) {
    NONE("None", "None"),
    VALUE("Value", "Value"),
    TEXTURE("Texture", "Texture"),
    CHECKBOX("Checkbox", "Check"),
    DEFINITION("Definition (#define)", "#define"),
}

enum class ValueMode(val label: String, val description: String) {
    CONSTANT("Constant", "Fixed float controlled by precision slider"),
    TOTAL_TIME("Total Time", "Elapsed seconds with speed, offset & loop"),
    CLOCK_TIME("Clock Time", "Real-time system wall clock value"),
    SINE_WAVE("Sine Wave", "Oscillating wave driven by elapsed time"),
    FRAME_DELTA("Frame Delta", "Per-frame delta time in seconds"),
    RESOLUTION_X("Viewport Width", "Render target width in pixels"),
    RESOLUTION_Y("Viewport Height", "Render target height in pixels"),
}

enum class ClockTimeUnit(val label: String) {
    SECONDS_OF_DAY("Seconds (0–86400)"),
    NORMALIZED_DAY("Normalized (0.0–1.0)"),
    HOURS_24("Hours (0–24)"),
    MINUTES_60("Minutes (0–60)"),
    SECONDS_60("Seconds (0–60)"),
}

enum class TextureFilterMode(val label: String) {
    LINEAR("Linear"),
    NEAREST("Nearest"),
}

enum class TextureWrapMode(val label: String) {
    REPEAT("Repeat"),
    CLAMP_TO_EDGE("Clamp"),
    MIRRORED_REPEAT("Mirror"),
}

enum class BuiltInTexture(val id: String, val label: String) {
    NONE("none", "Custom Image"),
    SCREEN_CAPTURE("screen_capture", "Live Screen"),
    CHECKERBOARD("checkerboard", "Checker Grid"),
    VALUE_NOISE("noise", "Soft Noise"),
    RADIAL_GLOW("radial", "Radial Glow"),
    COLOR_SPECTRUM("spectrum", "Prism Gradient"),
}

data class ShaderVariable(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isAutoNamed: Boolean = true,
    val type: UniformType = UniformType.NONE,
    val autoDeclare: Boolean = true,
    // Value mode parameters
    val valueMode: ValueMode = ValueMode.CONSTANT,
    val constantValue: Float = 0.5f,
    val minValue: Float = 0f,
    val maxValue: Float = 1f,
    val stepValue: Float = 0.01f,
    val timeSpeed: Float = 1f,
    val timeOffset: Float = 0f,
    val timeLoop: Float = 0f,
    val clockUnit: ClockTimeUnit = ClockTimeUnit.SECONDS_OF_DAY,
    val clockScale: Float = 1f,
    val clockOffset: Float = 0f,
    val waveFrequency: Float = 1f,
    val waveAmplitude: Float = 0.5f,
    val wavePhase: Float = 0f,
    val waveBias: Float = 0.5f,
    // Texture parameters
    val textureUri: String? = null,
    val builtInTexture: BuiltInTexture = BuiltInTexture.CHECKERBOARD,
    val filterMode: TextureFilterMode = TextureFilterMode.LINEAR,
    val wrapMode: TextureWrapMode = TextureWrapMode.REPEAT,
    // Checkbox parameters
    val checked: Boolean = false,
    val checkboxAsFloat: Boolean = false,
    // Definition (#define) parameters
    val defineEnabled: Boolean = true,
    val defineValue: String = "1",
) {
    fun glslDeclaration(): String = when (type) {
        UniformType.NONE -> "// $name (unassigned)"
        UniformType.VALUE -> "uniform float $name;"
        UniformType.TEXTURE -> "uniform sampler2D $name;"
        UniformType.CHECKBOX -> if (checkboxAsFloat) "uniform float $name;" else "uniform bool $name;"
        UniformType.DEFINITION -> if (defineValue.isBlank()) "#define $name" else "#define $name $defineValue"
    }
}

data class ShaderProject(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val mode: ShaderMode = ShaderMode.COMBINED,
    val useCustomVertex: Boolean = false,
    val combinedSource: String,
    val vertexSource: String,
    val fragmentSource: String,
    val variables: List<ShaderVariable> = emptyList(),
    val meshSubdivisions: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class AntiEchoMode(val label: String, val description: String) {
    CLEAN_PULSE(
        "Clean Pulse",
        "Briefly hides the fullscreen overlay to capture an echo-free display frame"
    ),
    LOCK_CLEAN(
        "Freeze Clean",
        "Zero flicker: locks the last echo-free display frame until refreshed"
    ),
    ALLOW_FEEDBACK(
        "Allow Echo",
        "Continuous un-gated capture including the overlay itself"
    ),
}

data class EngineSettings(
    val targetFps: Int = 60,
    val resolutionScale: Float = 0.75f,
    val overlayAlpha: Float = 1.0f,
    val pauseOnScreenOff: Boolean = true,
    val autoInjectUniforms: Boolean = true,
    val showCheckerboardInPreview: Boolean = true,
    val darkTheme: Boolean = true,
    val antiEchoMode: AntiEchoMode = AntiEchoMode.CLEAN_PULSE,
    val screenCaptureIntervalMs: Int = 500,
)

data class ShaderTelemetry(
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val renderWidth: Int = 0,
    val renderHeight: Int = 0,
    val compileSuccess: Boolean = true,
    val compileLog: String = "",
    val errorStage: String = "",
    val errorLine: Int? = null,
    val activeUniformNames: Set<String> = emptySet(),
    val activeDefineNames: Set<String> = emptySet(),
    val glesVersion: String = "OpenGL ES 3.0",
    val gpuRenderer: String = "",
    val lastPreprocessedVertex: String = "",
    val lastPreprocessedFragment: String = "",
)
