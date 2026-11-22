package com.example

import com.example.model.ClockTimeUnit
import com.example.model.ShaderMode
import com.example.model.ShaderVariable
import com.example.model.UniformType
import com.example.model.ValueMode
import com.example.shader.GlShaderEngine
import com.example.shader.GlslPreprocessor
import com.example.shader.ShaderDefaults
import com.example.shader.UniformNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderPreprocessorAndUniformTest {

    @Test
    fun blankProjectStartsWithEmptyVariables() {
        val blank = ShaderDefaults.createBlankProject()
        assertTrue(blank.variables.isEmpty())
    }

    @Test
    fun autoNamingGeneratesUniqueValidIdentifiers() {
        val vars = mutableListOf<ShaderVariable>()
        val n1 = UniformNaming.generateAutoName(UniformType.VALUE, ValueMode.CONSTANT, vars)
        assertEquals("u_const_1", n1)
        vars += ShaderVariable(name = n1, type = UniformType.VALUE, valueMode = ValueMode.CONSTANT)

        val n2 = UniformNaming.generateAutoName(UniformType.VALUE, ValueMode.CONSTANT, vars)
        assertEquals("u_const_2", n2)

        val t1 = UniformNaming.generateAutoName(UniformType.VALUE, ValueMode.TOTAL_TIME, vars)
        assertEquals("u_total_time_1", t1)

        val c1 = UniformNaming.generateAutoName(UniformType.VALUE, ValueMode.CLOCK_TIME, vars)
        assertEquals("u_clock_time_1", c1)

        val tex1 = UniformNaming.generateAutoName(UniformType.TEXTURE, ValueMode.CONSTANT, vars)
        assertEquals("u_tex_1", tex1)

        val chk1 = UniformNaming.generateAutoName(UniformType.CHECKBOX, ValueMode.CONSTANT, vars)
        assertEquals("u_check_1", chk1)

        val def1 = UniformNaming.generateAutoName(UniformType.DEFINITION, ValueMode.CONSTANT, vars)
        assertEquals("DEF_1", def1)
    }

    @Test
    fun sanitizeIdentifierHandlesKeywordsAndLeadingDigits() {
        val sanitizedKeyword = UniformNaming.sanitizeIdentifier(
            raw = "float",
            fallbackType = UniformType.VALUE,
            fallbackValueMode = ValueMode.CONSTANT,
            existingVariables = emptyList(),
        )
        assertEquals("float_var", sanitizedKeyword)

        val sanitizedDigit = UniformNaming.sanitizeIdentifier(
            raw = "123_alpha!",
            fallbackType = UniformType.VALUE,
            fallbackValueMode = ValueMode.CONSTANT,
            existingVariables = emptyList(),
        )
        assertEquals("u_123_alpha", sanitizedDigit)
    }

    @Test
    fun preprocessorInjectsDefinesAndMissingUniformsAfterVersion() {
        val project = ShaderDefaults.createBlankProject().copy(
            variables = listOf(
                ShaderVariable(name = "u_const_1", type = UniformType.VALUE, valueMode = ValueMode.CONSTANT),
                ShaderVariable(name = "u_tex_1", type = UniformType.TEXTURE),
                ShaderVariable(name = "u_check_1", type = UniformType.CHECKBOX),
                ShaderVariable(name = "USE_TINT", type = UniformType.DEFINITION, defineEnabled = true, defineValue = "1"),
                ShaderVariable(name = "DISABLED_MACRO", type = UniformType.DEFINITION, defineEnabled = false, defineValue = "1"),
                ShaderVariable(name = "u_slot_1", type = UniformType.NONE),
            )
        )

        val result = GlslPreprocessor.process(project, autoInjectUniforms = true)
        assertTrue(result.fragmentSource.startsWith("#version 300 es\n"))
        assertTrue(result.fragmentSource.contains("#define USE_TINT 1"))
        assertFalse(result.fragmentSource.contains("DISABLED_MACRO"))
        assertTrue(result.fragmentSource.contains("uniform float u_const_1;"))
        assertTrue(result.fragmentSource.contains("uniform sampler2D u_tex_1;"))
        assertTrue(result.fragmentSource.contains("uniform bool u_check_1;"))
        assertFalse(result.fragmentSource.contains("u_slot_1"))

        val precisionIndex = result.fragmentSource.indexOf("precision highp float;")
        val uniformIndex = result.fragmentSource.indexOf("uniform float u_const_1;")
        assertTrue("precision must precede uniform declarations", precisionIndex in 0 until uniformIndex)

        // Verify ES 2.0 fallback down-leveling
        val es2Result = GlslPreprocessor.process(project, autoInjectUniforms = true, supportsGles3 = false)
        assertTrue(es2Result.fragmentSource.startsWith("#version 100\n"))
        assertTrue(es2Result.fragmentSource.contains("gl_FragColor"))
        assertFalse(es2Result.fragmentSource.contains("out vec4 fragColor"))
    }

    @Test
    fun combinedModeSplitsVertexAndFragmentMarkers() {
        val combined = """
            // @vertex
            #version 300 es
            precision highp float;
            in vec2 aPosition;
            in vec2 aTexCoord;
            out vec2 vUv;
            void main() {
                vUv = aTexCoord;
                gl_Position = vec4(aPosition * 0.9, 0.0, 1.0);
            }
            // @fragment
            #version 300 es
            precision highp float;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vUv, 0.0, 1.0);
            }
        """.trimIndent()

        val project = ShaderDefaults.createBlankProject().copy(
            mode = ShaderMode.COMBINED,
            combinedSource = combined,
        )

        val result = GlslPreprocessor.process(project, autoInjectUniforms = true)
        assertTrue(result.vertexSource.contains("aPosition * 0.9"))
        assertTrue(result.fragmentSource.contains("fragColor = vec4(vUv, 0.0, 1.0)"))
    }

    @Test
    fun evaluateValueModesProducesAccurateResults() {
        val constVar = ShaderVariable(
            name = "u_const_1",
            type = UniformType.VALUE,
            valueMode = ValueMode.CONSTANT,
            constantValue = 0.72f,
        )
        assertEquals(0.72f, GlShaderEngine.evaluateValueVariable(constVar, 5f, 0.016f, 43_200_000L, 1080, 1920), 0.0001f)

        val timeVar = ShaderVariable(
            name = "u_total_time_1",
            type = UniformType.VALUE,
            valueMode = ValueMode.TOTAL_TIME,
            timeSpeed = 2.0f,
            timeOffset = 1.0f,
            timeLoop = 5.0f,
        )
        // 3.0 * 2.0 + 1.0 = 7.0 -> mod 5.0 = 2.0
        assertEquals(2.0f, GlShaderEngine.evaluateValueVariable(timeVar, 3f, 0.016f, 0L, 1080, 1920), 0.0001f)

        val clockVar = ShaderVariable(
            name = "u_clock_time_1",
            type = UniformType.VALUE,
            valueMode = ValueMode.CLOCK_TIME,
            clockUnit = ClockTimeUnit.NORMALIZED_DAY,
            clockScale = 1.0f,
            clockOffset = 0.0f,
        )
        // 43_200_000 ms = 12:00 noon = 0.5 normalized day
        assertEquals(0.5f, GlShaderEngine.evaluateValueVariable(clockVar, 0f, 0.016f, 43_200_000L, 1080, 1920), 0.0001f)
    }

    @Test
    fun chromaticGlitchFragmentPreprocessesCleanlyWithVaryings() {
        val glitchTemplate = ShaderDefaults.starterTemplates().first { it.id == "tpl_chromatic_glitch" }
        val processed = GlslPreprocessor.process(glitchTemplate, autoInjectUniforms = true)

        assertFalse(processed.isGles3)
        assertTrue(processed.fragmentSource.startsWith("#version 100\n"))
        // Should not duplicate already-declared uniforms
        assertEquals(1, Regex("""uniform\s+sampler2D\s+sTexture\b""").findAll(processed.fragmentSource).count())
        assertEquals(1, Regex("""uniform\s+float\s+iTime\b""").findAll(processed.fragmentSource).count())
        assertEquals(1, Regex("""uniform\s+float\s+GLITCH\b""").findAll(processed.fragmentSource).count())
        // Default vertex shader must emit matching vTexCoord & vColor varyings
        assertTrue(processed.vertexSource.contains("varying vec2 vTexCoord;"))
        assertTrue(processed.vertexSource.contains("varying vec4 vColor;"))
        assertTrue(processed.vertexSource.contains("vColor = vec4(1.0);"))
    }

    @Test
    fun screenCaptureScalingPreservesAspectRatioAndEvenDimensions() {
        val (w1, h1) = com.example.shader.ScreenCaptureManager.computeScaledCaptureSize(1080, 2400, maxLongEdge = 720)
        assertEquals(324, w1)
        assertEquals(720, h1)
        assertEquals(0, w1 % 2)
        assertEquals(0, h1 % 2)

        val (w2, h2) = com.example.shader.ScreenCaptureManager.computeScaledCaptureSize(480, 320, maxLongEdge = 720)
        assertEquals(480, w2)
        assertEquals(320, h2)
    }

    @Test
    fun antiEchoGateBlocksFeedbackAndAllowsCleanWindowFrames() {
        val mgr = com.example.shader.ScreenCaptureManager
        try {
            val baseNs = 10_000_000_000L

            // Un-gated allows frames freely
            mgr.setOverlayGating(false)
            assertEquals(
                com.example.shader.ScreenCaptureManager.FrameDecision.COMMIT_DIRECT,
                mgr.classifyFrame(imageTimestampNs = baseNs, nowNs = baseNs)
            )
            assertTrue(mgr.shouldAcceptImage(imageTimestampNs = baseNs, nowNs = baseNs))

            // When fullscreen overlay gating is active and clean window is closed, all frames are blocked
            mgr.setOverlayGating(true)
            assertFalse(mgr.shouldAcceptImage(imageTimestampNs = baseNs + 150_000_000L, nowNs = baseNs + 150_000_000L))

            // Open clean capture window anchored to double-blank swap at baseNs
            mgr.beginCleanCaptureWindow(blankSwapNs = baseNs) {}

            // Frames arriving during initial 55ms pipeline drain are rejected
            assertEquals(
                com.example.shader.ScreenCaptureManager.FrameDecision.REJECT,
                mgr.classifyFrame(imageTimestampNs = baseNs + 40_000_000L, nowNs = baseNs + 40_000_000L)
            )

            // Frame with pre-hide composition timestamp (<35ms lead) is rejected even if delivered later
            assertEquals(
                com.example.shader.ScreenCaptureManager.FrameDecision.REJECT,
                mgr.classifyFrame(imageTimestampNs = baseNs + 20_000_000L, nowNs = baseNs + 80_000_000L)
            )

            // Clean frame in [55ms, 100ms) is staged cleanly (supports static screen updates)
            assertEquals(
                com.example.shader.ScreenCaptureManager.FrameDecision.STAGE_CANDIDATE,
                mgr.classifyFrame(imageTimestampNs = baseNs + 45_000_000L, nowNs = baseNs + 80_000_000L)
            )

            // Clean frame arriving >=100ms after double-blank swap commits immediately
            assertEquals(
                com.example.shader.ScreenCaptureManager.FrameDecision.COMMIT_DIRECT,
                mgr.classifyFrame(imageTimestampNs = baseNs + 60_000_000L, nowNs = baseNs + 115_000_000L)
            )
        } finally {
            mgr.setOverlayGating(false)
        }
    }
}
