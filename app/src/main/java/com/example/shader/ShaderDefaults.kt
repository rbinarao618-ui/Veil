package com.example.shader

import com.example.model.BuiltInTexture
import com.example.model.ClockTimeUnit
import com.example.model.ShaderMode
import com.example.model.ShaderProject
import com.example.model.ShaderVariable
import com.example.model.UniformType
import com.example.model.ValueMode

object ShaderDefaults {

    val DEFAULT_SEPARATE_VERTEX = """
        #version 300 es
        precision highp float;
        in vec2 aPosition;
        in vec2 aTexCoord;
        out vec2 vUv;

        void main() {
            vUv = aTexCoord;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """.trimIndent()

    val DEFAULT_FRAGMENT_BLANK_WORKSPACE = """
        #version 300 es
        precision highp float;

        in vec2 vUv;
        out vec4 fragColor;

        // Variables/Uniforms starts blank.
        // Add Value, Texture, Checkbox, or #define variables
        // in the Variables tab and reference them directly here!

        void main() {
            vec2 centered = vUv - 0.5;
            float edge = smoothstep(0.32, 0.72, length(centered));
            vec3 tint = mix(vec3(0.08, 0.75, 0.90), vec3(0.96, 0.62, 0.05), vUv.x);
            fragColor = vec4(tint, edge * 0.35);
        }
    """.trimIndent()

    val CHROMATIC_GLITCH_FRAGMENT = """
        precision highp float;

        uniform sampler2D sTexture;
        uniform float iTime;
        uniform float GLITCH;

        varying vec2 vTexCoord;
        varying vec4 vColor;

        const int NUM_SAMPLES = 5;

        float sat(float t) {
            return clamp(t, 0.0, 1.0);
        }

        vec2 sat(vec2 t) {
            return clamp(t, 0.0, 1.0);
        }

        float remap(float t, float a, float b) {
            return sat((t - a) / (b - a));
        }

        float linterp(float t) {
            return sat(1.0 - abs(2.0 * t - 1.0));
        }

        vec3 spectrum_offset(float t) {
            float lo = step(t, 0.5);
            float hi = 1.0 - lo;

            float w = linterp(
                remap(t, 1.0 / 6.0, 5.0 / 6.0)
            );

            float neg_w = 1.0 - w;

            vec3 ret = vec3(lo, 1.0, hi) *
                       vec3(neg_w, w, neg_w);

            return pow(ret, vec3(1.0 / 2.2));
        }

        float rand(vec2 n) {
            return fract(
                sin(dot(n, vec2(12.9898, 78.233)))
                * 43758.5453
            );
        }

        float srand(vec2 n) {
            return rand(n) * 2.0 - 1.0;
        }

        float mytrunc(float x, float levels) {
            return floor(x * levels) / levels;
        }

        vec2 mytrunc(vec2 x, float levels) {
            return floor(x * levels) / levels;
        }

        void main() {
            vec2 uv = vTexCoord;

            float time = mod(iTime * 10000.0, 32.0) / 10.0;

            float gnm = sat(GLITCH);

            float rnd0 = rand(
                mytrunc(vec2(time, time), 6.0)
            );

            float r0 = sat((1.0 - gnm) * 0.7 + rnd0);

            float rnd1 = rand(
                vec2(mytrunc(uv.x, 10.0 * r0), time)
            );

            float r1 = 0.0001 - 0.7 * gnm + rnd1;

            float rnd2 = rand(
                vec2(mytrunc(uv.y, 40.0 * r1), time)
            );

            float r2 = sat(rnd2);

            float rnd3 = rand(
                vec2(mytrunc(uv.y, 2.0 * r0), time)
            );

            float r3 = (1.0 - sat(rnd3 + 0.8)) - 0.1;

            float pxrnd = rand(uv + time);

            float ofs = 0.05 * r2 * GLITCH;

            ofs += 0.5 * pxrnd * ofs;

            uv.y += 0.4 * r3 * GLITCH;

            const float RCP_NUM_SAMPLES_F =
                1.0 / float(NUM_SAMPLES);

            vec4 sum = vec4(0.0);
            vec3 wsum = vec3(0.0);

            for (int i = 0; i < NUM_SAMPLES; ++i) {

                float t = float(i) * RCP_NUM_SAMPLES_F;

                vec2 sampleUV = uv;

                sampleUV.x = sat(sampleUV.x + ofs * t);

                vec4 samplecol = texture2D(sTexture, sampleUV);

                vec3 s = spectrum_offset(t);

                samplecol.rgb *= s;

                sum += samplecol;
                wsum += s;
            }

            sum.rgb /= max(wsum, vec3(0.0001));

            sum.a *= RCP_NUM_SAMPLES_F;

            gl_FragColor = sum * vColor;
        }
    """.trimIndent()

    fun createBlankProject(): ShaderProject = ShaderProject(
        id = "default_blank_workspace",
        title = "Untitled Veil",
        mode = ShaderMode.COMBINED,
        useCustomVertex = false,
        combinedSource = DEFAULT_FRAGMENT_BLANK_WORKSPACE,
        vertexSource = DEFAULT_SEPARATE_VERTEX,
        fragmentSource = DEFAULT_FRAGMENT_BLANK_WORKSPACE,
        variables = emptyList(), // Intentionally blank until user configures variables!
        meshSubdivisions = 1,
    )

    fun starterTemplates(): List<ShaderProject> = listOf(
        ShaderProject(
            id = "tpl_chromatic_glitch",
            title = "Chromatic Glitch",
            mode = ShaderMode.COMBINED,
            useCustomVertex = false,
            combinedSource = CHROMATIC_GLITCH_FRAGMENT,
            vertexSource = DEFAULT_SEPARATE_VERTEX,
            fragmentSource = CHROMATIC_GLITCH_FRAGMENT,
            variables = listOf(
                ShaderVariable(
                    name = "sTexture",
                    isAutoNamed = false,
                    type = UniformType.TEXTURE,
                    builtInTexture = BuiltInTexture.SCREEN_CAPTURE,
                    wrapMode = com.example.model.TextureWrapMode.CLAMP_TO_EDGE,
                ),
                ShaderVariable(
                    name = "iTime",
                    isAutoNamed = false,
                    type = UniformType.VALUE,
                    valueMode = ValueMode.TOTAL_TIME,
                    timeSpeed = 1.0f,
                ),
                ShaderVariable(
                    name = "GLITCH",
                    isAutoNamed = false,
                    type = UniformType.VALUE,
                    valueMode = ValueMode.CONSTANT,
                    constantValue = 0.45f,
                    minValue = 0f,
                    maxValue = 1.0f,
                    stepValue = 0.01f,
                ),
            ),
            meshSubdivisions = 1,
        ),
        ShaderProject(
            id = "tpl_edge_aura",
            title = "Prism Edge Aura",
            mode = ShaderMode.COMBINED,
            useCustomVertex = false,
            combinedSource = """
                #version 300 es
                precision highp float;

                in vec2 vUv;
                out vec4 fragColor;

                void main() {
                    vec2 d = abs(vUv - 0.5) * 2.0;
                    float border = smoothstep(0.78, 1.0, max(d.x, d.y));
                    float pulse = 0.65 + 0.35 * sin(u_total_time_1 * 2.4 + vUv.y * 5.0);
                    vec3 col = mix(vec3(0.13, 0.83, 0.93), vec3(0.96, 0.62, 0.04), vUv.x);
                    #ifdef BOOST_GLOW
                    border *= 1.45;
                    #endif
                    float alpha = clamp(border * u_const_1 * pulse, 0.0, 0.85);
                    fragColor = vec4(col, alpha);
                }
            """.trimIndent(),
            vertexSource = DEFAULT_SEPARATE_VERTEX,
            fragmentSource = DEFAULT_FRAGMENT_BLANK_WORKSPACE,
            variables = listOf(
                ShaderVariable(
                    name = "u_total_time_1",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.TOTAL_TIME,
                    timeSpeed = 1.0f,
                ),
                ShaderVariable(
                    name = "u_const_1",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.CONSTANT,
                    constantValue = 0.55f,
                    minValue = 0f,
                    maxValue = 1.5f,
                    stepValue = 0.01f,
                ),
                ShaderVariable(
                    name = "BOOST_GLOW",
                    isAutoNamed = false,
                    type = UniformType.DEFINITION,
                    defineEnabled = true,
                    defineValue = "1",
                ),
            ),
            meshSubdivisions = 1,
        ),
        ShaderProject(
            id = "tpl_crt_scanline",
            title = "CRT Scanlines",
            mode = ShaderMode.COMBINED,
            useCustomVertex = false,
            combinedSource = """
                #version 300 es
                precision highp float;

                in vec2 vUv;
                out vec4 fragColor;

                void main() {
                    float lines = 0.5 + 0.5 * sin(vUv.y * u_const_1 + u_total_time_1 * 6.0);
                    float mask = u_check_1 ? smoothstep(0.25, 0.85, lines) : lines;
                    vec3 tint = vec3(0.02, 0.04, 0.06);
                    fragColor = vec4(tint, mask * u_const_2);
                }
            """.trimIndent(),
            vertexSource = DEFAULT_SEPARATE_VERTEX,
            fragmentSource = DEFAULT_FRAGMENT_BLANK_WORKSPACE,
            variables = listOf(
                ShaderVariable(
                    name = "u_total_time_1",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.TOTAL_TIME,
                    timeSpeed = 1.0f,
                ),
                ShaderVariable(
                    name = "u_const_1",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.CONSTANT,
                    constantValue = 420f,
                    minValue = 40f,
                    maxValue = 1200f,
                    stepValue = 10f,
                ),
                ShaderVariable(
                    name = "u_const_2",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.CONSTANT,
                    constantValue = 0.28f,
                    minValue = 0f,
                    maxValue = 0.8f,
                    stepValue = 0.01f,
                ),
                ShaderVariable(
                    name = "u_check_1",
                    type = UniformType.CHECKBOX,
                    checked = true,
                ),
            ),
            meshSubdivisions = 1,
        ),
        ShaderProject(
            id = "tpl_vertex_wave",
            title = "Vertex Wave Grid",
            mode = ShaderMode.SEPARATE,
            useCustomVertex = true,
            combinedSource = DEFAULT_FRAGMENT_BLANK_WORKSPACE,
            vertexSource = """
                #version 300 es
                precision highp float;
                in vec2 aPosition;
                in vec2 aTexCoord;
                out vec2 vUv;
                out float vHeight;

                void main() {
                    vUv = aTexCoord;
                    float wave = sin(aTexCoord.x * 8.0 + u_total_time_1 * 2.5) * 0.06 * u_const_1;
                    vHeight = wave;
                    gl_Position = vec4(aPosition.x, aPosition.y + wave, 0.0, 1.0);
                }
            """.trimIndent(),
            fragmentSource = """
                #version 300 es
                precision highp float;
                in vec2 vUv;
                in float vHeight;
                out vec4 fragColor;

                void main() {
                    float band = smoothstep(0.45, 0.5, abs(vUv.y - 0.5 - vHeight * 2.0));
                    vec3 col = mix(vec3(0.13, 0.83, 0.93), vec3(0.65, 0.55, 0.98), vUv.x);
                    fragColor = vec4(col, (1.0 - band) * 0.35);
                }
            """.trimIndent(),
            variables = listOf(
                ShaderVariable(
                    name = "u_total_time_1",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.TOTAL_TIME,
                ),
                ShaderVariable(
                    name = "u_const_1",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.CONSTANT,
                    constantValue = 1.0f,
                    minValue = 0f,
                    maxValue = 2.5f,
                    stepValue = 0.05f,
                ),
            ),
            meshSubdivisions = 32,
        ),
        ShaderProject(
            id = "tpl_texture_veil",
            title = "Texture Watermark",
            mode = ShaderMode.COMBINED,
            useCustomVertex = false,
            combinedSource = """
                #version 300 es
                precision highp float;

                in vec2 vUv;
                out vec4 fragColor;

                void main() {
                    vec2 uv = vUv * u_const_1 + vec2(u_total_time_1 * 0.05, 0.0);
                    vec4 tex = texture(u_tex_1, uv);
                    fragColor = vec4(tex.rgb, tex.a * u_const_2);
                }
            """.trimIndent(),
            vertexSource = DEFAULT_SEPARATE_VERTEX,
            fragmentSource = DEFAULT_FRAGMENT_BLANK_WORKSPACE,
            variables = listOf(
                ShaderVariable(
                    name = "u_tex_1",
                    type = UniformType.TEXTURE,
                    builtInTexture = BuiltInTexture.COLOR_SPECTRUM,
                ),
                ShaderVariable(
                    name = "u_total_time_1",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.TOTAL_TIME,
                    timeSpeed = 1.0f,
                ),
                ShaderVariable(
                    name = "u_const_1",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.CONSTANT,
                    constantValue = 2.0f,
                    minValue = 0.5f,
                    maxValue = 8.0f,
                    stepValue = 0.1f,
                ),
                ShaderVariable(
                    name = "u_const_2",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.CONSTANT,
                    constantValue = 0.25f,
                    minValue = 0f,
                    maxValue = 1.0f,
                    stepValue = 0.01f,
                ),
            ),
            meshSubdivisions = 1,
        ),
        ShaderProject(
            id = "tpl_clock_tint",
            title = "Circadian Clock Tint",
            mode = ShaderMode.COMBINED,
            useCustomVertex = false,
            combinedSource = """
                #version 300 es
                precision highp float;

                in vec2 vUv;
                out vec4 fragColor;

                void main() {
                    float dayPhase = clamp(u_clock_time_1, 0.0, 1.0);
                    vec3 cool = vec3(0.10, 0.55, 0.95);
                    vec3 warm = vec3(1.00, 0.55, 0.12);
                    vec3 tint = mix(cool, warm, 0.5 - 0.5 * cos(dayPhase * 6.283185));
                    fragColor = vec4(tint, u_const_1);
                }
            """.trimIndent(),
            vertexSource = DEFAULT_SEPARATE_VERTEX,
            fragmentSource = DEFAULT_FRAGMENT_BLANK_WORKSPACE,
            variables = listOf(
                ShaderVariable(
                    name = "u_clock_time_1",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.CLOCK_TIME,
                    clockUnit = ClockTimeUnit.NORMALIZED_DAY,
                ),
                ShaderVariable(
                    name = "u_const_1",
                    type = UniformType.VALUE,
                    valueMode = ValueMode.CONSTANT,
                    constantValue = 0.16f,
                    minValue = 0f,
                    maxValue = 0.6f,
                    stepValue = 0.01f,
                ),
            ),
            meshSubdivisions = 1,
        ),
    )
}
