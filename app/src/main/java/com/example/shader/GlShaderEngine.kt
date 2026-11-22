package com.example.shader

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.example.model.BuiltInTexture
import com.example.model.ClockTimeUnit
import com.example.model.EngineSettings
import com.example.model.ShaderProject
import com.example.model.ShaderTelemetry
import com.example.model.ShaderVariable
import com.example.model.UniformType
import com.example.model.ValueMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.PI
import kotlin.math.sin

data class RenderSnapshot(
    val project: ShaderProject,
    val settings: EngineSettings,
    val isPaused: Boolean,
    val resetEpoch: Long,
)

class GlShaderEngine(
    private val context: Context,
    private val tag: String = "GlShaderEngine",
    private val onTelemetry: (ShaderTelemetry) -> Unit,
) {
    private val snapshotRef = AtomicReference<RenderSnapshot?>(null)
    private val running = AtomicBoolean(false)
    private val dirtyGeometry = AtomicBoolean(true)
    private val dirtySize = AtomicBoolean(true)
    private val wakeLock = ReentrantLock()
    private val wakeCondition = wakeLock.newCondition()
    private val externalResetEpoch = AtomicLong(0L)
    private val pendingBlankCallback = AtomicReference<((Long) -> Unit)?>(null)

    @Volatile
    private var surfaceWidth: Int = 1

    @Volatile
    private var surfaceHeight: Int = 1

    private var renderThread: Thread? = null

    fun start(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        initialSnapshot: RenderSnapshot,
    ) {
        stop()
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        snapshotRef.set(initialSnapshot)
        externalResetEpoch.set(initialSnapshot.resetEpoch)
        running.set(true)
        dirtyGeometry.set(true)
        dirtySize.set(true)

        renderThread = Thread({
            runGlLoop(surfaceTexture)
        }, "ShaderVeil-GL-$tag").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun updateSnapshot(snapshot: RenderSnapshot) {
        val prev = snapshotRef.getAndSet(snapshot)
        if (prev == null || prev.project.meshSubdivisions != snapshot.project.meshSubdivisions) {
            dirtyGeometry.set(true)
        }
        if (prev == null || prev.settings.resolutionScale != snapshot.settings.resolutionScale) {
            dirtySize.set(true)
        }
        if (prev == null || prev.resetEpoch != snapshot.resetEpoch) {
            externalResetEpoch.set(snapshot.resetEpoch)
        }
        wakeLock.withLock {
            wakeCondition.signalAll()
        }
    }

    fun blankAndPause(pausedSnapshot: RenderSnapshot, onBlanked: (blankSwapNs: Long) -> Unit) {
        val paused = pausedSnapshot.copy(isPaused = true)
        snapshotRef.set(paused)
        if (!running.get()) {
            onBlanked(System.nanoTime())
            return
        }
        pendingBlankCallback.set(onBlanked)
        wakeLock.withLock {
            wakeCondition.signalAll()
        }
    }

    fun resize(width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        dirtySize.set(true)
        wakeLock.withLock {
            wakeCondition.signalAll()
        }
    }

    fun stop() {
        running.set(false)
        wakeLock.withLock {
            wakeCondition.signalAll()
        }
        renderThread?.let { t ->
            t.interrupt()
            try {
                t.join(500)
            } catch (_: InterruptedException) {
            }
        }
        renderThread = null
    }

    private data class BoundTexture(
        val glTexId: Int,
        val cacheKey: String,
        val screenFrameVersion: Long = -1L,
        val screenWidth: Int = 0,
        val screenHeight: Int = 0,
    )

    private fun runGlLoop(surfaceTexture: SurfaceTexture) {
        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        var currentProgram = 0
        var lastShaderKey = ""
        var activeUniformLocations = HashMap<String, Int>()
        var lastCompiledTelemetry = ShaderTelemetry()
        val textureCache = HashMap<String, BoundTexture>()
        var scratchScreenBuffer: ByteBuffer? = null

        var vboId = 0
        var iboId = 0
        var indexCount = 0
        var currentSubdivisions = -1

        var fboId = 0
        var fboTexId = 0
        var fboWidth = 0
        var fboHeight = 0
        var fboReady = false
        var blitProgram = 0
        var blitVboId = 0

        var windowWidth = surfaceWidth.coerceAtLeast(1)
        var windowHeight = surfaceHeight.coerceAtLeast(1)
        var eglWidth = windowWidth
        var eglHeight = windowHeight
        var renderWidth = windowWidth
        var renderHeight = windowHeight
        var currentScale = 1.0f

        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                Log.e(tag, "eglInitialize failed")
                return
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                Log.e(tag, "eglChooseConfig failed")
                return
            }
            val eglConfig = configs[0]!!

            var isGles3Context = true
            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                eglConfig,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                isGles3Context = false
                eglContext = EGL14.eglCreateContext(
                    eglDisplay,
                    eglConfig,
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0
                )
            }
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(tag, "eglCreateContext failed")
                return
            }

            windowWidth = surfaceWidth.coerceAtLeast(1)
            windowHeight = surfaceHeight.coerceAtLeast(1)
            surfaceTexture.setDefaultBufferSize(windowWidth, windowHeight)

            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surfaceTexture,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(tag, "eglCreateWindowSurface failed")
                return
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.e(tag, "eglMakeCurrent failed")
                return
            }

            EGL14.eglSwapInterval(eglDisplay, 1)

            val polledSize = queryEglSurfaceSize(eglDisplay, eglSurface, windowWidth, windowHeight)
            eglWidth = polledSize.first
            eglHeight = polledSize.second

            val glesVersionStr = GLES20.glGetString(GLES20.GL_VERSION) ?: "OpenGL ES"
            val gpuRendererStr = GLES20.glGetString(GLES20.GL_RENDERER) ?: "GPU"
            if (glesVersionStr.contains("OpenGL ES 2.")) {
                isGles3Context = false
            }

            // Allocate VBO/IBO + blit quad VBO once
            val buffers = IntArray(3)
            GLES20.glGenBuffers(3, buffers, 0)
            vboId = buffers[0]
            iboId = buffers[1]
            blitVboId = buffers[2]
            uploadBlitQuad(blitVboId)
            blitProgram = compileBlitProgram()

            val initialSnapshot = snapshotRef.get()
            currentScale = initialSnapshot?.settings?.resolutionScale?.coerceIn(0.25f, 1.0f) ?: 0.75f
            if (currentScale < 0.99f && blitProgram != 0) {
                val targetFboW = (eglWidth * currentScale).toInt().coerceAtLeast(16)
                val targetFboH = (eglHeight * currentScale).toInt().coerceAtLeast(16)
                val fboResult = ensureOffscreenFbo(fboId, fboTexId, fboWidth, fboHeight, targetFboW, targetFboH)
                fboId = fboResult.fboId
                fboTexId = fboResult.texId
                fboWidth = fboResult.width
                fboHeight = fboResult.height
                fboReady = fboResult.ready
            }
            renderWidth = if (fboReady) fboWidth else eglWidth
            renderHeight = if (fboReady) fboHeight else eglHeight

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glViewport(0, 0, eglWidth, eglHeight)
            dirtySize.set(false)

            var startTimeNs = System.nanoTime()
            var lastFrameNs = startTimeNs
            var lastResetHandled = externalResetEpoch.get()
            var fpsFrames = 0
            var fpsWindowStartNs = startTimeNs
            var currentFps = 0f
            var currentFrameMs = 0f

            while (running.get()) {
                val blankCb = pendingBlankCallback.getAndSet(null)
                if (blankCb != null) {
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        GLES20.glViewport(0, 0, eglWidth, eglHeight)
                        GLES20.glClearColor(0f, 0f, 0f, 0f)
                        repeat(2) {
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                        }
                    }
                    blankCb(System.nanoTime())
                }

                val snapshot = snapshotRef.get()
                if (snapshot == null) {
                    wakeLock.withLock {
                        try {
                            wakeCondition.await(50, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                        }
                    }
                    continue
                }

                if (snapshot.isPaused) {
                    wakeLock.withLock {
                        if (running.get() && (snapshotRef.get()?.isPaused == true) && pendingBlankCallback.get() == null) {
                            try {
                                wakeCondition.await(100, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                            }
                        }
                    }
                    lastFrameNs = System.nanoTime()
                    continue
                }

                val frameStartNs = System.nanoTime()
                val currentReset = externalResetEpoch.get()
                if (currentReset != lastResetHandled) {
                    startTimeNs = frameStartNs
                    lastResetHandled = currentReset
                }

                val deltaSec = ((frameStartNs - lastFrameNs).coerceAtLeast(0L)) / 1_000_000_000f
                lastFrameNs = frameStartNs
                val totalSec = (frameStartNs - startTimeNs) / 1_000_000_000f

                // Keep window surface at full native view resolution; apply resolutionScale via offscreen FBO
                val nextScale = snapshot.settings.resolutionScale.coerceIn(0.25f, 1.0f)
                if (dirtySize.getAndSet(false) || kotlin.math.abs(nextScale - currentScale) >= 0.01f) {
                    currentScale = nextScale
                    val nextWinW = surfaceWidth.coerceAtLeast(1)
                    val nextWinH = surfaceHeight.coerceAtLeast(1)
                    if (nextWinW != windowWidth || nextWinH != windowHeight) {
                        windowWidth = nextWinW
                        windowHeight = nextWinH
                        surfaceTexture.setDefaultBufferSize(windowWidth, windowHeight)
                        EGL14.eglMakeCurrent(
                            eglDisplay,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT
                        )
                        if (eglSurface != EGL14.EGL_NO_SURFACE) {
                            EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        }
                        eglSurface = EGL14.eglCreateWindowSurface(
                            eglDisplay,
                            eglConfig,
                            surfaceTexture,
                            intArrayOf(EGL14.EGL_NONE),
                            0
                        )
                        if (eglSurface == EGL14.EGL_NO_SURFACE ||
                            !EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                        ) {
                            Log.w(tag, "EGL surface lost during resize; ending render loop")
                            break
                        }
                        val polled = queryEglSurfaceSize(eglDisplay, eglSurface, windowWidth, windowHeight)
                        eglWidth = polled.first
                        eglHeight = polled.second
                    }

                    if (currentScale < 0.99f && blitProgram != 0) {
                        val targetFboW = (eglWidth * currentScale).toInt().coerceAtLeast(16)
                        val targetFboH = (eglHeight * currentScale).toInt().coerceAtLeast(16)
                        val fboResult = ensureOffscreenFbo(fboId, fboTexId, fboWidth, fboHeight, targetFboW, targetFboH)
                        fboId = fboResult.fboId
                        fboTexId = fboResult.texId
                        fboWidth = fboResult.width
                        fboHeight = fboResult.height
                        fboReady = fboResult.ready
                    } else {
                        fboReady = false
                    }
                    renderWidth = if (fboReady) fboWidth else eglWidth
                    renderHeight = if (fboReady) fboHeight else eglHeight
                    GLES20.glViewport(0, 0, eglWidth, eglHeight)
                }

                // Rebuild subdivided vertex mesh if needed
                val targetSubdiv = snapshot.project.meshSubdivisions.coerceIn(1, 64)
                if (dirtyGeometry.getAndSet(false) || targetSubdiv != currentSubdivisions) {
                    currentSubdivisions = targetSubdiv
                    indexCount = uploadGridMesh(vboId, iboId, targetSubdiv)
                }

                // Recompile shader program only when preprocessed source changes
                val preprocessed = GlslPreprocessor.process(
                    project = snapshot.project,
                    autoInjectUniforms = snapshot.settings.autoInjectUniforms,
                    supportsGles3 = isGles3Context,
                )
                val shaderKey = "${preprocessed.vertexSource}\u0000${preprocessed.fragmentSource}"
                if (shaderKey != lastShaderKey) {
                    lastShaderKey = shaderKey
                    val compileResult = compileProgram(preprocessed.vertexSource, preprocessed.fragmentSource)
                    if (compileResult.programId != 0) {
                        if (currentProgram != 0) {
                            GLES20.glDeleteProgram(currentProgram)
                        }
                        currentProgram = compileResult.programId
                        activeUniformLocations = resolveUniformLocations(
                            programId = currentProgram,
                            variables = snapshot.project.variables,
                        )
                        lastCompiledTelemetry = ShaderTelemetry(
                            fps = currentFps,
                            frameTimeMs = currentFrameMs,
                            renderWidth = renderWidth,
                            renderHeight = renderHeight,
                            compileSuccess = true,
                            compileLog = "Compiled & linked successfully",
                            errorStage = "",
                            errorLine = null,
                            activeUniformNames = activeUniformLocations.keys.toSet(),
                            activeDefineNames = preprocessed.activeDefines,
                            glesVersion = glesVersionStr,
                            gpuRenderer = gpuRendererStr,
                            lastPreprocessedVertex = preprocessed.vertexSource,
                            lastPreprocessedFragment = preprocessed.fragmentSource,
                        )
                        onTelemetry(lastCompiledTelemetry)
                    } else {
                        lastCompiledTelemetry = ShaderTelemetry(
                            fps = currentFps,
                            frameTimeMs = currentFrameMs,
                            renderWidth = renderWidth,
                            renderHeight = renderHeight,
                            compileSuccess = false,
                            compileLog = compileResult.errorLog,
                            errorStage = compileResult.errorStage,
                            errorLine = GlslPreprocessor.parseCompilerErrorLine(compileResult.errorLog),
                            activeUniformNames = activeUniformLocations.keys.toSet(),
                            activeDefineNames = preprocessed.activeDefines,
                            glesVersion = glesVersionStr,
                            gpuRenderer = gpuRendererStr,
                            lastPreprocessedVertex = preprocessed.vertexSource,
                            lastPreprocessedFragment = preprocessed.fragmentSource,
                        )
                        onTelemetry(lastCompiledTelemetry)
                    }
                } else {
                    // Refresh uniform location cache if variable list changed names without changing code
                    for (v in snapshot.project.variables) {
                        if (v.type != UniformType.NONE && v.type != UniformType.DEFINITION &&
                            v.name.isNotBlank() && currentProgram != 0 && !activeUniformLocations.containsKey(v.name)
                        ) {
                            val loc = GLES20.glGetUniformLocation(currentProgram, v.name)
                            if (loc >= 0) {
                                activeUniformLocations[v.name] = loc
                            }
                        }
                    }
                }

                if (eglSurface == EGL14.EGL_NO_SURFACE) {
                    continue
                }

                val useFbo = fboReady && fboId != 0 && fboTexId != 0 && blitProgram != 0
                if (useFbo) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                    GLES20.glViewport(0, 0, fboWidth, fboHeight)
                } else {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, eglWidth, eglHeight)
                }

                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                if (currentProgram != 0 && indexCount > 0) {
                    GLES20.glUseProgram(currentProgram)

                    scratchScreenBuffer = bindUniforms(
                        locations = activeUniformLocations,
                        variables = snapshot.project.variables,
                        totalSec = totalSec,
                        deltaSec = deltaSec,
                        width = renderWidth,
                        height = renderHeight,
                        textureCache = textureCache,
                        isGles3 = isGles3Context,
                        scratchScreenBuffer = scratchScreenBuffer,
                    )

                    val posLoc = GLES20.glGetAttribLocation(currentProgram, "aPosition")
                    val uvLoc = GLES20.glGetAttribLocation(currentProgram, "aTexCoord")

                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
                    GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, iboId)

                    val strideBytes = 4 * 4 // 4 floats: x, y, u, v
                    if (posLoc >= 0) {
                        GLES20.glEnableVertexAttribArray(posLoc)
                        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, strideBytes, 0)
                    }
                    if (uvLoc >= 0) {
                        GLES20.glEnableVertexAttribArray(uvLoc)
                        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, strideBytes, 2 * 4)
                    }

                    GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)

                    if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
                    if (uvLoc >= 0) GLES20.glDisableVertexAttribArray(uvLoc)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                    GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
                }

                if (useFbo) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, eglWidth, eglHeight)
                    GLES20.glDisable(GLES20.GL_BLEND)
                    GLES20.glClearColor(0f, 0f, 0f, 0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    drawBlitPass(blitProgram, blitVboId, fboTexId)
                    GLES20.glEnable(GLES20.GL_BLEND)
                }

                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    val err = EGL14.eglGetError()
                    if (!running.get() || err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_CONTEXT_LOST) {
                        break
                    }
                }

                val drawEndNs = System.nanoTime()
                val frameElapsedNs = drawEndNs - frameStartNs
                currentFrameMs = frameElapsedNs / 1_000_000f

                fpsFrames++
                val windowNs = drawEndNs - fpsWindowStartNs
                if (windowNs >= 500_000_000L) {
                    currentFps = (fpsFrames * 1_000_000_000f) / windowNs
                    fpsFrames = 0
                    fpsWindowStartNs = drawEndNs
                    onTelemetry(
                        lastCompiledTelemetry.copy(
                            fps = currentFps,
                            frameTimeMs = currentFrameMs,
                            renderWidth = renderWidth,
                            renderHeight = renderHeight,
                            activeUniformNames = activeUniformLocations.keys.toSet(),
                        )
                    )
                }

                // Target FPS pacing
                val targetFps = snapshot.settings.targetFps
                if (targetFps > 0 && running.get()) {
                    val targetNs = 1_000_000_000L / targetFps
                    val sleepNs = targetNs - (System.nanoTime() - frameStartNs)
                    if (sleepNs > 500_000L) {
                        val ms = sleepNs / 1_000_000L
                        val ns = (sleepNs % 1_000_000L).toInt()
                        try {
                            Thread.sleep(ms, ns)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Render loop crashed", t)
        } finally {
            runCatching {
                for (entry in textureCache.values) {
                    if (entry.glTexId != 0) {
                        GLES20.glDeleteTextures(1, intArrayOf(entry.glTexId), 0)
                    }
                }
                textureCache.clear()
                if (fboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                if (fboTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(fboTexId), 0)
                if (blitProgram != 0) GLES20.glDeleteProgram(blitProgram)
                if (currentProgram != 0) GLES20.glDeleteProgram(currentProgram)
                if (vboId != 0) GLES20.glDeleteBuffers(1, intArrayOf(vboId), 0)
                if (iboId != 0) GLES20.glDeleteBuffers(1, intArrayOf(iboId), 0)
                if (blitVboId != 0) GLES20.glDeleteBuffers(1, intArrayOf(blitVboId), 0)
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            }
        }
    }

    private data class FboAllocation(
        val fboId: Int,
        val texId: Int,
        val width: Int,
        val height: Int,
        val ready: Boolean,
    )

    private fun queryEglSurfaceSize(
        display: EGLDisplay,
        surface: EGLSurface,
        fallbackWidth: Int,
        fallbackHeight: Int,
    ): Pair<Int, Int> {
        val wArr = IntArray(1)
        val hArr = IntArray(1)
        val okW = EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, wArr, 0)
        val okH = EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, hArr, 0)
        val w = if (okW && wArr[0] > 0) wArr[0] else fallbackWidth.coerceAtLeast(1)
        val h = if (okH && hArr[0] > 0) hArr[0] else fallbackHeight.coerceAtLeast(1)
        return w to h
    }

    private fun ensureOffscreenFbo(
        existingFboId: Int,
        existingTexId: Int,
        existingW: Int,
        existingH: Int,
        targetW: Int,
        targetH: Int,
    ): FboAllocation {
        val fbo = if (existingFboId != 0) {
            existingFboId
        } else {
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            ids[0]
        }
        val tex = if (existingTexId != 0) {
            existingTexId
        } else {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            ids[0]
        }
        if (fbo == 0 || tex == 0) {
            return FboAllocation(fbo, tex, 0, 0, ready = false)
        }

        if (existingW != targetW || existingH != targetH) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                targetW,
                targetH,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            tex,
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return FboAllocation(
            fboId = fbo,
            texId = tex,
            width = targetW,
            height = targetH,
            ready = status == GLES20.GL_FRAMEBUFFER_COMPLETE,
        )
    }

    private fun uploadBlitQuad(blitVboId: Int) {
        val quad = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        val buf = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quad)
        buf.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, blitVboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun compileBlitProgram(): Int {
        val vert = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()
        val frag = """
            precision mediump float;
            uniform sampler2D uBlitTex;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uBlitTex, vUv);
            }
        """.trimIndent()
        return compileProgram(vert, frag).programId
    }

    private fun drawBlitPass(blitProgram: Int, blitVboId: Int, fboTexId: Int) {
        GLES20.glUseProgram(blitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
        val texLoc = GLES20.glGetUniformLocation(blitProgram, "uBlitTex")
        if (texLoc >= 0) GLES20.glUniform1i(texLoc, 0)

        val posLoc = GLES20.glGetAttribLocation(blitProgram, "aPos")
        val uvLoc = GLES20.glGetAttribLocation(blitProgram, "aUv")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, blitVboId)
        val stride = 4 * 4
        if (posLoc >= 0) {
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, stride, 0)
        }
        if (uvLoc >= 0) {
            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, stride, 2 * 4)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
        if (uvLoc >= 0) GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun uploadGridMesh(vboId: Int, iboId: Int, subdivisions: Int): Int {
        val cols = subdivisions + 1
        val vertCount = cols * cols
        val vertices = FloatArray(vertCount * 4)
        var vIdx = 0
        for (y in 0..subdivisions) {
            val v = y.toFloat() / subdivisions
            val py = -1f + 2f * v
            for (x in 0..subdivisions) {
                val u = x.toFloat() / subdivisions
                val px = -1f + 2f * u
                vertices[vIdx++] = px
                vertices[vIdx++] = py
                vertices[vIdx++] = u
                vertices[vIdx++] = 1f - v // Standard top-left UV for textures/screen space
            }
        }

        val indexCount = subdivisions * subdivisions * 6
        val indices = ShortArray(indexCount)
        var iIdx = 0
        for (y in 0 until subdivisions) {
            for (x in 0 until subdivisions) {
                val tl = (y * cols + x).toShort()
                val tr = (tl + 1).toShort()
                val bl = ((y + 1) * cols + x).toShort()
                val br = (bl + 1).toShort()
                indices[iIdx++] = tl
                indices[iIdx++] = bl
                indices[iIdx++] = tr
                indices[iIdx++] = tr
                indices[iIdx++] = bl
                indices[iIdx++] = br
            }
        }

        val vBuf: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vBuf.position(0)

        val iBuf: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indices)
        iBuf.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, vBuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, iboId)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, iBuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        return indexCount
    }

    private data class CompileResult(
        val programId: Int,
        val errorStage: String,
        val errorLog: String,
    )

    private fun compileProgram(vertexSrc: String, fragmentSrc: String): CompileResult {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs.first == 0) {
            return CompileResult(0, "Vertex", vs.second)
        }
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs.first == 0) {
            GLES20.glDeleteShader(vs.first)
            return CompileResult(0, "Fragment", fs.second)
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs.first)
        GLES20.glAttachShader(program, fs.first)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vs.first)
        GLES20.glDeleteShader(fs.first)

        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            return CompileResult(0, "Link", log.ifBlank { "Program link failed" })
        }
        return CompileResult(program, "", "")
    }

    private fun compileShader(type: Int, source: String): Pair<Int, String> {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            return 0 to log.ifBlank { "Shader compilation error" }
        }
        return shader to ""
    }

    private fun resolveUniformLocations(
        programId: Int,
        variables: List<ShaderVariable>,
    ): HashMap<String, Int> {
        val map = HashMap<String, Int>(variables.size)
        for (v in variables) {
            if (v.type != UniformType.NONE && v.type != UniformType.DEFINITION && v.name.isNotBlank()) {
                val loc = GLES20.glGetUniformLocation(programId, v.name)
                if (loc >= 0) {
                    map[v.name] = loc
                }
            }
        }
        return map
    }

    private fun bindUniforms(
        locations: HashMap<String, Int>,
        variables: List<ShaderVariable>,
        totalSec: Float,
        deltaSec: Float,
        width: Int,
        height: Int,
        textureCache: HashMap<String, BoundTexture>,
        isGles3: Boolean,
        scratchScreenBuffer: ByteBuffer?,
    ): ByteBuffer? {
        var texUnitIndex = 0
        var localScratch = scratchScreenBuffer
        val nowMs = System.currentTimeMillis()
        val tzOffset = TimeZone.getDefault().getOffset(nowMs)
        val localDayMs = ((nowMs + tzOffset) % 86_400_000L + 86_400_000L) % 86_400_000L

        for (v in variables) {
            if (v.type == UniformType.NONE || v.type == UniformType.DEFINITION) continue
            val loc = locations[v.name] ?: continue

            when (v.type) {
                UniformType.VALUE -> {
                    val value = evaluateValueVariable(v, totalSec, deltaSec, localDayMs, width, height)
                    GLES20.glUniform1f(loc, value)
                }
                UniformType.CHECKBOX -> {
                    if (v.checkboxAsFloat) {
                        GLES20.glUniform1f(loc, if (v.checked) 1.0f else 0.0f)
                    } else {
                        GLES20.glUniform1i(loc, if (v.checked) 1 else 0)
                    }
                }
                UniformType.TEXTURE -> {
                    if (texUnitIndex < 8) {
                        val existing = textureCache[v.id]
                        val glTexId: Int
                        if (v.textureUri.isNullOrBlank() &&
                            v.builtInTexture == BuiltInTexture.SCREEN_CAPTURE &&
                            ScreenCaptureManager.hasFrame()
                        ) {
                            val liveVersion = ScreenCaptureManager.latestFrameVersion()
                            val screenKey = "screen|${v.filterMode.name}|${v.wrapMode.name}"
                            if (existing != null &&
                                existing.cacheKey == screenKey &&
                                existing.screenFrameVersion == liveVersion
                            ) {
                                glTexId = existing.glTexId
                            } else {
                                var uploadedId = existing?.glTexId ?: 0
                                var uploadedW = existing?.screenWidth ?: 0
                                var uploadedH = existing?.screenHeight ?: 0
                                val canReuseSize = existing != null &&
                                    existing.cacheKey.startsWith("screen|")

                                localScratch = ScreenCaptureManager.readLatestFrame(localScratch) { w, h, pixels ->
                                    uploadedId = TextureLoader.uploadRgbaBufferToGlTexture(
                                        existingTextureId = uploadedId,
                                        width = w,
                                        height = h,
                                        canReuseExistingSize = canReuseSize && uploadedW == w && uploadedH == h,
                                        pixels = pixels,
                                        filterMode = v.filterMode,
                                        wrapMode = v.wrapMode,
                                        isGles3 = isGles3,
                                    )
                                    uploadedW = w
                                    uploadedH = h
                                }
                                if (uploadedId != 0) {
                                    textureCache[v.id] = BoundTexture(
                                        glTexId = uploadedId,
                                        cacheKey = screenKey,
                                        screenFrameVersion = liveVersion,
                                        screenWidth = uploadedW,
                                        screenHeight = uploadedH,
                                    )
                                }
                                glTexId = uploadedId
                            }
                        } else {
                            val cacheKey = "${v.textureUri.orEmpty()}|${v.builtInTexture.id}|${v.filterMode.name}|${v.wrapMode.name}"
                            glTexId = if (existing != null && existing.cacheKey == cacheKey) {
                                existing.glTexId
                            } else {
                                val bmp = TextureLoader.loadBitmapForVariable(context, v.textureUri, v.builtInTexture)
                                val newId = TextureLoader.uploadGlTexture(
                                    existingTextureId = existing?.glTexId ?: 0,
                                    bitmap = bmp,
                                    filterMode = v.filterMode,
                                    wrapMode = v.wrapMode,
                                    isGles3 = isGles3,
                                )
                                bmp.recycle()
                                textureCache[v.id] = BoundTexture(newId, cacheKey)
                                newId
                            }
                        }

                        if (glTexId != 0) {
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + texUnitIndex)
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTexId)
                            GLES20.glUniform1i(loc, texUnitIndex)
                            texUnitIndex++
                        }
                    }
                }
                UniformType.NONE, UniformType.DEFINITION -> Unit
            }
        }
        return localScratch
    }

    companion object {
        fun evaluateValueVariable(
            v: ShaderVariable,
            totalSec: Float,
            deltaSec: Float,
            localDayMs: Long,
            width: Int,
            height: Int,
        ): Float = when (v.valueMode) {
            ValueMode.CONSTANT -> v.constantValue
            ValueMode.TOTAL_TIME -> {
                val raw = totalSec * v.timeSpeed + v.timeOffset
                if (v.timeLoop > 0.001f) {
                    ((raw % v.timeLoop) + v.timeLoop) % v.timeLoop
                } else {
                    raw
                }
            }
            ValueMode.CLOCK_TIME -> {
                val secOfDay = localDayMs / 1000.0
                val base = when (v.clockUnit) {
                    ClockTimeUnit.SECONDS_OF_DAY -> secOfDay.toFloat()
                    ClockTimeUnit.NORMALIZED_DAY -> (secOfDay / 86400.0).toFloat()
                    ClockTimeUnit.HOURS_24 -> (secOfDay / 3600.0).toFloat()
                    ClockTimeUnit.MINUTES_60 -> ((secOfDay / 60.0) % 60.0).toFloat()
                    ClockTimeUnit.SECONDS_60 -> (secOfDay % 60.0).toFloat()
                }
                base * v.clockScale + v.clockOffset
            }
            ValueMode.SINE_WAVE -> {
                val angle = totalSec * v.waveFrequency * (2.0 * PI) + v.wavePhase
                (sin(angle).toFloat() * v.waveAmplitude) + v.waveBias
            }
            ValueMode.FRAME_DELTA -> deltaSec * v.timeSpeed
            ValueMode.RESOLUTION_X -> width.toFloat() * v.constantValue.coerceAtLeast(0.01f)
            ValueMode.RESOLUTION_Y -> height.toFloat() * v.constantValue.coerceAtLeast(0.01f)
        }
    }
}
