package com.example.shader

import com.example.model.ShaderMode
import com.example.model.ShaderProject
import com.example.model.ShaderVariable
import com.example.model.UniformType
import com.example.model.ValueMode

data class PreprocessedShaders(
    val vertexSource: String,
    val fragmentSource: String,
    val isGles3: Boolean,
    val activeDefines: Set<String>,
)

object UniformNaming {
    private val glslKeywords = setOf(
        "attribute", "const", "uniform", "varying", "buffer", "shared", "coherent",
        "volatile", "restrict", "readonly", "writeonly", "layout", "centroid", "flat",
        "smooth", "noperspective", "patch", "sample", "break", "continue", "do", "for",
        "while", "switch", "case", "default", "if", "else", "subroutine", "in", "out",
        "inout", "float", "double", "int", "void", "bool", "true", "false", "invariant",
        "precise", "discard", "return", "mat2", "mat3", "mat4", "dmat2", "dmat3", "dmat4",
        "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3", "mat3x4", "mat4x2", "mat4x3",
        "mat4x4", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "bvec2", "bvec3",
        "bvec4", "uvec2", "uvec3", "uvec4", "uint", "lowp", "mediump", "highp",
        "precision", "sampler2D", "sampler3D", "samplerCube", "struct", "main"
    )

    fun basePrefix(type: UniformType, valueMode: ValueMode): String = when (type) {
        UniformType.NONE -> "u_slot"
        UniformType.VALUE -> when (valueMode) {
            ValueMode.CONSTANT -> "u_const"
            ValueMode.TOTAL_TIME -> "u_total_time"
            ValueMode.CLOCK_TIME -> "u_clock_time"
            ValueMode.SINE_WAVE -> "u_wave"
            ValueMode.FRAME_DELTA -> "u_delta"
            ValueMode.RESOLUTION_X -> "u_res_x"
            ValueMode.RESOLUTION_Y -> "u_res_y"
        }
        UniformType.TEXTURE -> "u_tex"
        UniformType.CHECKBOX -> "u_check"
        UniformType.DEFINITION -> "DEF"
    }

    fun generateAutoName(
        type: UniformType,
        valueMode: ValueMode,
        existingVariables: List<ShaderVariable>,
        excludeId: String? = null,
    ): String {
        val existingNames = existingVariables
            .asSequence()
            .filter { it.id != excludeId }
            .map { it.name }
            .toSet()

        val prefix = basePrefix(type, valueMode)
        var index = 1
        while (true) {
            val candidate = "${prefix}_$index"
            if (candidate !in existingNames && candidate !in glslKeywords) {
                return candidate
            }
            index++
        }
    }

    fun sanitizeIdentifier(
        raw: String,
        fallbackType: UniformType,
        fallbackValueMode: ValueMode,
        existingVariables: List<ShaderVariable>,
        excludeId: String? = null,
    ): String {
        val cleaned = buildString {
            for (ch in raw.trim()) {
                when {
                    ch.isLetterOrDigit() || ch == '_' -> append(ch)
                    ch.isWhitespace() || ch == '-' -> append('_')
                }
            }
        }
        var result = cleaned.trim('_')
        if (result.isEmpty()) {
            return generateAutoName(fallbackType, fallbackValueMode, existingVariables, excludeId)
        }
        if (result.first().isDigit()) {
            result = if (fallbackType == UniformType.DEFINITION) "D_$result" else "u_$result"
        }
        if (result.startsWith("gl_")) {
            result = "u_${result.removePrefix("gl_")}"
        }
        if (result in glslKeywords) {
            result = "${result}_var"
        }
        val existingNames = existingVariables
            .asSequence()
            .filter { it.id != excludeId }
            .map { it.name }
            .toSet()
        if (result !in existingNames) return result

        var suffix = 2
        while ("${result}_$suffix" in existingNames) {
            suffix++
        }
        return "${result}_$suffix"
    }
}

object GlslPreprocessor {

    private val versionRegex = Regex("""(?m)^\s*#version\s+(.+)$""")
    private val precisionLineRegex = Regex("""(?m)^\s*precision\s+(lowp|mediump|highp)\s+(float|int|sampler2D)\s*;\s*$""")
    private val floatPrecisionRegex = Regex("""(?m)^\s*precision\s+(lowp|mediump|highp)\s+float\s*;""")
    private val vertexMarkerRegex = Regex("""(?im)^\s*//\s*@vertex\b.*$""")
    private val fragmentMarkerRegex = Regex("""(?im)^\s*//\s*@fragment\b.*$""")
    private val ifdefVertexRegex = Regex("""(?m)^\s*#ifdef\s+VERTEX\b""")
    private val ifdefFragmentRegex = Regex("""(?m)^\s*#ifdef\s+FRAGMENT\b""")
    private val lineCommentRegex = Regex("""(?m)//.*$""")
    private val es1HintRegex = Regex("""\b(gl_FragColor|texture2D|varying|attribute)\b""")

    fun defaultVertexShader(
        isGles3: Boolean,
        fragmentSource: String = "",
    ): String {
        val usesTexCoord = Regex("""\bvTexCoord\b""").containsMatchIn(fragmentSource)
        val usesColor = Regex("""\bvColor\b""").containsMatchIn(fragmentSource)
        val usesUv = Regex("""\bvUv\b""").containsMatchIn(fragmentSource) || !usesTexCoord

        return if (isGles3) {
            buildString {
                appendLine("#version 300 es")
                appendLine("precision highp float;")
                appendLine("in vec2 aPosition;")
                appendLine("in vec2 aTexCoord;")
                if (usesUv) appendLine("out vec2 vUv;")
                if (usesTexCoord) appendLine("out vec2 vTexCoord;")
                if (usesColor) appendLine("out vec4 vColor;")
                appendLine("void main() {")
                if (usesUv) appendLine("    vUv = aTexCoord;")
                if (usesTexCoord) appendLine("    vTexCoord = aTexCoord;")
                if (usesColor) appendLine("    vColor = vec4(1.0);")
                appendLine("    gl_Position = vec4(aPosition, 0.0, 1.0);")
                append("}")
            }
        } else {
            buildString {
                appendLine("#version 100")
                appendLine("precision highp float;")
                appendLine("attribute vec2 aPosition;")
                appendLine("attribute vec2 aTexCoord;")
                if (usesUv) appendLine("varying vec2 vUv;")
                if (usesTexCoord) appendLine("varying vec2 vTexCoord;")
                if (usesColor) appendLine("varying vec4 vColor;")
                appendLine("void main() {")
                if (usesUv) appendLine("    vUv = aTexCoord;")
                if (usesTexCoord) appendLine("    vTexCoord = aTexCoord;")
                if (usesColor) appendLine("    vColor = vec4(1.0);")
                appendLine("    gl_Position = vec4(aPosition, 0.0, 1.0);")
                append("}")
            }
        }
    }

    fun process(
        project: ShaderProject,
        autoInjectUniforms: Boolean,
        supportsGles3: Boolean = true,
    ): PreprocessedShaders {
        val (rawVertex, rawFragment) = extractStages(project)

        val fragmentVersionLine = versionRegex.find(rawFragment)?.value?.trim()
        val vertexVersionLine = rawVertex?.let { versionRegex.find(it)?.value?.trim() }

        val requestedGles3 = when {
            fragmentVersionLine != null -> {
                fragmentVersionLine.contains("300") ||
                    fragmentVersionLine.contains("310") ||
                    fragmentVersionLine.contains("320")
            }
            vertexVersionLine != null -> {
                vertexVersionLine.contains("300") ||
                    vertexVersionLine.contains("310") ||
                    vertexVersionLine.contains("320")
            }
            es1HintRegex.containsMatchIn(rawFragment) -> false
            else -> true
        }

        val isGles3 = requestedGles3 && supportsGles3

        val activeDefines = project.variables
            .asSequence()
            .filter { it.type == UniformType.DEFINITION && it.defineEnabled && it.name.isNotBlank() }
            .map { it.name }
            .toSet()

        val adaptedFragment = if (!supportsGles3 && requestedGles3) {
            downlevelGles3ToEs2(rawFragment, isVertex = false)
        } else {
            rawFragment
        }

        val adaptedVertex = when {
            rawVertex.isNullOrBlank() -> null
            !supportsGles3 && requestedGles3 -> downlevelGles3ToEs2(rawVertex, isVertex = true)
            else -> rawVertex
        }

        val processedFragment = injectHeader(
            rawSource = adaptedFragment,
            targetGles3 = isGles3,
            variables = project.variables,
            autoInjectUniforms = autoInjectUniforms,
        )

        val processedVertex = if (adaptedVertex.isNullOrBlank()) {
            injectHeader(
                rawSource = defaultVertexShader(isGles3, processedFragment),
                targetGles3 = isGles3,
                variables = project.variables.filter { it.type == UniformType.DEFINITION },
                autoInjectUniforms = false,
            )
        } else {
            injectHeader(
                rawSource = adaptedVertex,
                targetGles3 = isGles3,
                variables = project.variables,
                autoInjectUniforms = autoInjectUniforms,
            )
        }

        return PreprocessedShaders(
            vertexSource = processedVertex,
            fragmentSource = processedFragment,
            isGles3 = isGles3,
            activeDefines = activeDefines,
        )
    }

    private fun extractStages(project: ShaderProject): Pair<String?, String> {
        if (project.mode == ShaderMode.SEPARATE) {
            val vert = if (project.useCustomVertex && project.vertexSource.isNotBlank()) {
                project.vertexSource
            } else {
                null
            }
            return vert to project.fragmentSource
        }

        val combined = project.combinedSource
        val vMatch = vertexMarkerRegex.find(combined)
        val fMatch = fragmentMarkerRegex.find(combined)

        if (vMatch != null && fMatch != null) {
            return if (vMatch.range.first < fMatch.range.first) {
                val vertPart = combined.substring(vMatch.range.last + 1, fMatch.range.first).trim()
                val fragPart = combined.substring(fMatch.range.last + 1).trim()
                vertPart.ifBlank { null } to fragPart
            } else {
                val fragPart = combined.substring(fMatch.range.last + 1, vMatch.range.first).trim()
                val vertPart = combined.substring(vMatch.range.last + 1).trim()
                vertPart.ifBlank { null } to fragPart
            }
        } else if (fMatch != null && vMatch == null) {
            val fragPart = combined.substring(fMatch.range.last + 1).trim()
            return null to fragPart
        } else if (ifdefVertexRegex.containsMatchIn(combined) && ifdefFragmentRegex.containsMatchIn(combined)) {
            val versionHeader = versionRegex.find(combined)?.value ?: "#version 300 es"
            val bodyWithoutVersion = versionRegex.replaceFirst(combined, "")
            val vertWrapped = "$versionHeader\n#define VERTEX 1\n$bodyWithoutVersion"
            val fragWrapped = "$versionHeader\n#define FRAGMENT 1\n$bodyWithoutVersion"
            return vertWrapped to fragWrapped
        }

        return null to combined
    }

    private fun injectHeader(
        rawSource: String,
        targetGles3: Boolean,
        variables: List<ShaderVariable>,
        autoInjectUniforms: Boolean,
    ): String {
        val defaultVersion = if (targetGles3) "#version 300 es" else "#version 100"
        val trimmed = rawSource.trimStart()
        val existingVersion = versionRegex.find(trimmed)
        val versionLine: String
        var body: String

        if (existingVersion != null) {
            val rawVer = existingVersion.value.trim()
            versionLine = if (!targetGles3 && rawVer.contains("300")) "#version 100" else rawVer
            body = trimmed.removeRange(existingVersion.range).trimStart('\r', '\n')
        } else {
            versionLine = defaultVersion
            body = trimmed
        }

        // Preserve any user precision qualifiers at the top so uniform declarations ALWAYS follow precision!
        val extractedPrecisionLines = mutableListOf<String>()
        if (floatPrecisionRegex.containsMatchIn(body)) {
            precisionLineRegex.findAll(body).forEach { match ->
                extractedPrecisionLines += match.value.trim()
            }
            body = precisionLineRegex.replace(body, "").trimStart('\r', '\n')
        } else {
            extractedPrecisionLines += "precision highp float;"
        }

        val strippedForLookup = lineCommentRegex.replace(body, "")

        val defineLines = mutableListOf<String>()
        for (variable in variables) {
            if (variable.type == UniformType.DEFINITION && variable.defineEnabled && variable.name.isNotBlank()) {
                val alreadyDefined = Regex("""(?m)^\s*#define\s+${Regex.escape(variable.name)}\b""")
                    .containsMatchIn(strippedForLookup)
                if (!alreadyDefined) {
                    val valuePart = variable.defineValue.trim()
                    defineLines += if (valuePart.isEmpty()) {
                        "#define ${variable.name}"
                    } else {
                        "#define ${variable.name} $valuePart"
                    }
                }
            }
        }

        val uniformLines = mutableListOf<String>()
        if (autoInjectUniforms) {
            for (variable in variables) {
                if (!variable.autoDeclare || variable.name.isBlank()) continue
                val declaration = when (variable.type) {
                    UniformType.VALUE -> "uniform float ${variable.name};"
                    UniformType.TEXTURE -> "uniform sampler2D ${variable.name};"
                    UniformType.CHECKBOX -> {
                        if (variable.checkboxAsFloat) "uniform float ${variable.name};"
                        else "uniform bool ${variable.name};"
                    }
                    UniformType.NONE, UniformType.DEFINITION -> null
                } ?: continue

                val alreadyDeclared = Regex(
                    """\buniform\s+(?:(?:lowp|mediump|highp)\s+)?\w+\s+${Regex.escape(variable.name)}\b"""
                ).containsMatchIn(strippedForLookup)
                if (!alreadyDeclared) {
                    uniformLines += declaration
                }
            }
        }

        return buildString {
            append(versionLine)
            append('\n')
            defineLines.forEach { line ->
                append(line)
                append('\n')
            }
            extractedPrecisionLines.forEach { line ->
                append(line)
                append('\n')
            }
            uniformLines.forEach { line ->
                append(line)
                append('\n')
            }
            append(body)
        }
    }

    private fun downlevelGles3ToEs2(source: String, isVertex: Boolean): String {
        var s = source
        s = versionRegex.replace(s, "#version 100")
        if (isVertex) {
            s = s.replace(Regex("""(?m)^\s*in\s+"""), "attribute ")
            s = s.replace(Regex("""(?m)^\s*out\s+"""), "varying ")
        } else {
            s = s.replace(Regex("""(?m)^\s*in\s+"""), "varying ")
            val outMatch = Regex("""(?m)^\s*out\s+vec4\s+(\w+)\s*;\s*$""").find(s)
            if (outMatch != null) {
                val outName = outMatch.groupValues[1]
                s = s.removeRange(outMatch.range)
                s = s.replace(Regex("""\b${Regex.escape(outName)}\b"""), "gl_FragColor")
            }
            s = s.replace(Regex("""\btexture\s*\("""), "texture2D(")
        }
        return s
    }

    fun parseCompilerErrorLine(log: String): Int? {
        val match = Regex("""(?:ERROR:\s*\d+:|\b\d+:)(\d+)""").find(log)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
