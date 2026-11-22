package com.example.shader

import android.media.Image
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object ScreenCaptureManager {

    private val lock = ReentrantLock()
    private val frameVersion = AtomicLong(0L)

    private var frontBuffer: ByteBuffer? = null
    private var backBuffer: ByteBuffer? = null
    private var stagedBuffer: ByteBuffer? = null
    private var stagedWidth: Int = 0
    private var stagedHeight: Int = 0
    private var stagedTimestampNs: Long = 0L
    private var hasStagedCandidate: Boolean = false

    private var rowScratch: ByteArray = ByteArray(0)
    private var frontWidth: Int = 0
    private var frontHeight: Int = 0

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _captureSize = MutableStateFlow(0 to 0)
    val captureSize: StateFlow<Pair<Int, Int>> = _captureSize.asStateFlow()

    @Volatile
    private var overlayGateActive: Boolean = false

    @Volatile
    private var cleanWindowOpen: Boolean = false

    @Volatile
    private var blankSwapTimeNs: Long = 0L

    @Volatile
    private var minCleanContentTimestampNs: Long = 0L

    @Volatile
    private var cleanFrameCallback: (() -> Unit)? = null

    fun setCapturing(active: Boolean) {
        _isCapturing.value = active
        if (!active) {
            _captureSize.value = 0 to 0
            cleanWindowOpen = false
            cleanFrameCallback = null
            clearStaged()
        }
    }

    fun setOverlayGating(active: Boolean) {
        overlayGateActive = active
        if (!active) {
            cleanWindowOpen = false
            cleanFrameCallback = null
            clearStaged()
        }
    }

    fun isOverlayGatingActive(): Boolean = overlayGateActive

    fun beginCleanCaptureWindow(
        blankSwapNs: Long = System.nanoTime(),
        onCaptured: () -> Unit,
    ) {
        clearStaged()
        this.blankSwapTimeNs = blankSwapNs
        this.minCleanContentTimestampNs = blankSwapNs + MIN_COMPOSITION_LEAD_NS
        this.cleanFrameCallback = onCaptured
        this.cleanWindowOpen = true
    }

    fun cancelCleanCaptureWindow() {
        cleanWindowOpen = false
        cleanFrameCallback = null
        clearStaged()
    }

    /**
     * Evaluates whether an incoming ImageReader frame can be trusted to be free of overlay echo.
     * - When gated with clean window closed: rejects all frames (draining dirty overlay frames).
     * - When gated with clean window open:
     *   - Rejects frames arriving or timestamped before the transparent swap + pipeline drain lead.
     *   - Frames in the [MIN_STAGE_ELAPSED_NS, MIN_DIRECT_COMMIT_ELAPSED_NS) window are staged
     *     (in case the screen underneath is static and emits only one frame while hidden).
     *   - Frames arriving at >= MIN_DIRECT_COMMIT_ELAPSED_NS after the double-blank swap are
     *     committed immediately.
     */
    fun classifyFrame(imageTimestampNs: Long, nowNs: Long = System.nanoTime()): FrameDecision {
        if (!overlayGateActive) return FrameDecision.COMMIT_DIRECT
        if (!cleanWindowOpen) return FrameDecision.REJECT

        val sinceBlank = nowNs - blankSwapTimeNs
        if (sinceBlank < MIN_STAGE_ELAPSED_NS) {
            return FrameDecision.REJECT
        }
        val sameClockBase = imageTimestampNs > 0L &&
            kotlin.math.abs(nowNs - imageTimestampNs) < CLOCK_BASE_TOLERANCE_NS
        if (sameClockBase && imageTimestampNs < minCleanContentTimestampNs) {
            return FrameDecision.REJECT
        }

        return if (sinceBlank >= MIN_DIRECT_COMMIT_ELAPSED_NS) {
            FrameDecision.COMMIT_DIRECT
        } else {
            FrameDecision.STAGE_CANDIDATE
        }
    }

    fun shouldAcceptImage(imageTimestampNs: Long, nowNs: Long = System.nanoTime()): Boolean {
        return classifyFrame(imageTimestampNs, nowNs) != FrameDecision.REJECT
    }

    fun promoteStagedFrameIfAvailable(): Boolean {
        if (!cleanWindowOpen) return false
        val promoted = lock.withLock {
            val sBuf = stagedBuffer
            if (!hasStagedCandidate || sBuf == null || stagedWidth <= 0 || stagedHeight <= 0) {
                false
            } else {
                val prevFront = frontBuffer
                frontBuffer = sBuf
                stagedBuffer = prevFront
                frontWidth = stagedWidth
                frontHeight = stagedHeight
                hasStagedCandidate = false
                stagedWidth = 0
                stagedHeight = 0
                stagedTimestampNs = 0L
                frameVersion.incrementAndGet()
                true
            }
        }
        if (promoted) {
            if (_captureSize.value.first != frontWidth || _captureSize.value.second != frontHeight) {
                _captureSize.value = frontWidth to frontHeight
            }
            cleanWindowOpen = false
            val cb = cleanFrameCallback
            cleanFrameCallback = null
            cb?.invoke()
        }
        return promoted
    }

    fun hasFrame(): Boolean = _isCapturing.value && frameVersion.get() > 0L

    fun latestFrameVersion(): Long = frameVersion.get()

    fun publishImage(image: Image) {
        val nowNs = System.nanoTime()
        val decision = classifyFrame(image.timestamp, nowNs)
        if (decision == FrameDecision.REJECT) {
            return
        }

        val plane = runCatching { image.planes.firstOrNull() }.getOrNull() ?: return
        val src = plane.buffer
        val rawW = image.width
        val rawH = image.height
        if (rawW <= 0 || rawH <= 0) return

        val crop = runCatching { image.cropRect }.getOrNull()
        val cropLeft = (crop?.left ?: 0).coerceIn(0, rawW - 1)
        val cropTop = (crop?.top ?: 0).coerceIn(0, rawH - 1)
        val cropRight = (crop?.right ?: rawW).coerceIn(cropLeft + 1, rawW)
        val cropBottom = (crop?.bottom ?: rawH).coerceIn(cropTop + 1, rawH)
        val outWidth = cropRight - cropLeft
        val outHeight = cropBottom - cropTop
        if (outWidth <= 0 || outHeight <= 0) return

        val rowBytes = outWidth * 4
        val neededBytes = rowBytes * outHeight
        val rowStride = plane.rowStride.coerceAtLeast(rawW * 4)
        val pixelStride = plane.pixelStride.coerceAtLeast(1)

        var localBack = backBuffer
        if (localBack == null || localBack.capacity() < neededBytes) {
            localBack = ByteBuffer.allocateDirect(neededBytes)
            backBuffer = localBack
        }
        localBack.clear()
        src.rewind()
        val srcLimit = src.limit()

        val fastContiguous = pixelStride == 4 &&
            cropLeft == 0 &&
            cropTop == 0 &&
            outWidth == rawW &&
            rowStride == rowBytes &&
            src.remaining() >= neededBytes

        if (fastContiguous) {
            val savedLimit = src.limit()
            src.limit(neededBytes)
            localBack.put(src)
            src.limit(savedLimit)
        } else {
            if (rowScratch.size < rowBytes) {
                rowScratch = ByteArray(rowBytes)
            }
            for (y in 0 until outHeight) {
                val srcY = cropTop + y
                val rowStart = srcY * rowStride + cropLeft * pixelStride
                if (rowStart < 0 || rowStart >= srcLimit) {
                    rowScratch.fill(0, 0, rowBytes)
                    localBack.put(rowScratch, 0, rowBytes)
                    continue
                }
                src.position(rowStart)
                val avail = (srcLimit - rowStart).coerceAtLeast(0)
                if (pixelStride == 4) {
                    val copyLen = minOf(rowBytes, avail)
                    if (copyLen > 0) {
                        src.get(rowScratch, 0, copyLen)
                    }
                    if (copyLen < rowBytes) {
                        rowScratch.fill(0, copyLen, rowBytes)
                    }
                } else {
                    for (x in 0 until outWidth) {
                        val pxOffset = rowStart + x * pixelStride
                        val dstOffset = x * 4
                        if (pxOffset >= 0 && pxOffset + 3 < srcLimit) {
                            rowScratch[dstOffset] = src.get(pxOffset)
                            rowScratch[dstOffset + 1] = src.get(pxOffset + 1)
                            rowScratch[dstOffset + 2] = src.get(pxOffset + 2)
                            rowScratch[dstOffset + 3] = src.get(pxOffset + 3)
                        } else {
                            rowScratch[dstOffset] = 0
                            rowScratch[dstOffset + 1] = 0
                            rowScratch[dstOffset + 2] = 0
                            rowScratch[dstOffset + 3] = 0
                        }
                    }
                }
                localBack.put(rowScratch, 0, rowBytes)
            }
        }
        localBack.flip()

        if (decision == FrameDecision.STAGE_CANDIDATE) {
            lock.withLock {
                val prevStaged = stagedBuffer
                stagedBuffer = localBack
                backBuffer = prevStaged
                stagedWidth = outWidth
                stagedHeight = outHeight
                stagedTimestampNs = nowNs
                hasStagedCandidate = true
            }
            return
        }

        val consumedCleanWindow = cleanWindowOpen
        lock.withLock {
            val prevFront = frontBuffer
            frontBuffer = localBack
            backBuffer = prevFront
            frontWidth = outWidth
            frontHeight = outHeight
            hasStagedCandidate = false
            frameVersion.incrementAndGet()
        }

        if (_captureSize.value.first != outWidth || _captureSize.value.second != outHeight) {
            _captureSize.value = outWidth to outHeight
        }

        if (consumedCleanWindow) {
            cleanWindowOpen = false
            val cb = cleanFrameCallback
            cleanFrameCallback = null
            cb?.invoke()
        }
    }

    fun readLatestFrame(
        scratchBuffer: ByteBuffer?,
        onReady: (width: Int, height: Int, pixels: ByteBuffer) -> Unit,
    ): ByteBuffer? {
        var width = 0
        var height = 0
        var dst = scratchBuffer

        val copied = lock.withLock {
            val w = frontWidth
            val h = frontHeight
            val src = frontBuffer
            if (w <= 0 || h <= 0 || src == null) {
                false
            } else {
                val needed = w * h * 4
                var localDst = dst
                if (localDst == null || localDst.capacity() < needed) {
                    localDst = ByteBuffer.allocateDirect(needed)
                }
                localDst.clear()
                src.rewind()
                localDst.put(src)
                localDst.flip()
                dst = localDst
                width = w
                height = h
                true
            }
        }

        val readyDst = dst
        if (copied && readyDst != null) {
            onReady(width, height, readyDst)
        }
        return readyDst
    }

    fun clear() {
        cleanWindowOpen = false
        cleanFrameCallback = null
        lock.withLock {
            frontBuffer = null
            backBuffer = null
            stagedBuffer = null
            hasStagedCandidate = false
            stagedWidth = 0
            stagedHeight = 0
            frontWidth = 0
            frontHeight = 0
            frameVersion.set(0L)
        }
        _isCapturing.value = false
        _captureSize.value = 0 to 0
    }

    private fun clearStaged() {
        lock.withLock {
            hasStagedCandidate = false
            stagedWidth = 0
            stagedHeight = 0
            stagedTimestampNs = 0L
        }
    }

    fun computeScaledCaptureSize(rawWidth: Int, rawHeight: Int, maxLongEdge: Int = 1080): Pair<Int, Int> {
        val w = rawWidth.coerceAtLeast(64)
        val h = rawHeight.coerceAtLeast(64)
        val maxSide = maxOf(w, h)
        val scale = if (maxSide > maxLongEdge) maxLongEdge.toFloat() / maxSide else 1f
        val scaledW = ((w * scale).toInt().coerceAtLeast(64)) and 1.inv()
        val scaledH = ((h * scale).toInt().coerceAtLeast(64)) and 1.inv()
        return scaledW.coerceAtLeast(64) to scaledH.coerceAtLeast(64)
    }

    enum class FrameDecision {
        REJECT,
        STAGE_CANDIDATE,
        COMMIT_DIRECT,
    }

    private const val MIN_STAGE_ELAPSED_NS = 55_000_000L
    private const val MIN_DIRECT_COMMIT_ELAPSED_NS = 100_000_000L
    private const val MIN_COMPOSITION_LEAD_NS = 35_000_000L
    private const val CLOCK_BASE_TOLERANCE_NS = 5_000_000_000L
}
