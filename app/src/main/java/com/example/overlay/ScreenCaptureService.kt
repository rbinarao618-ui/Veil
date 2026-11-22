package com.example.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.MainActivity
import com.example.R
import com.example.data.OverlayRuntimeBridge
import com.example.shader.ScreenCaptureManager

class ScreenCaptureService : Service() {

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentDensityDpi: Int = DisplayMetrics.DENSITY_DEFAULT

    @Volatile
    private var isDestroyed = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            ScreenCaptureManager.clear()
            if (!isDestroyed) {
                stopSelf()
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (isDestroyed) return
            captureHandler?.post {
                if (isDestroyed) return@post
                runCatching {
                    val (scaledW, scaledH) = ScreenCaptureManager.computeScaledCaptureSize(width, height)
                    if (scaledW != currentWidth || scaledH != currentHeight) {
                        recreateReaderAndResize(scaledW, scaledH)
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Failed resizing screen capture reader", err)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isDestroyed = false
        createNotificationChannel()
        captureThread = HandlerThread("ShaderVeil-ScreenCapture").apply {
            start()
            captureHandler = Handler(looper)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ScreenCaptureManager.clear()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.let {
            runCatching { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }.getOrNull()
        }

        val fgStarted = runCatching {
            startCaptureForeground(buildNotification())
        }.onFailure { err ->
            Log.e(TAG, "Unable to start ScreenCaptureService foreground", err)
            OverlayRuntimeBridge.reportRuntimeError("Screen capture could not start: ${err.localizedMessage ?: "foreground service blocked"}")
        }.isSuccess

        if (!fgStarted || resultCode != Activity.RESULT_OK || resultData == null) {
            ScreenCaptureManager.clear()
            stopSelf()
            return START_NOT_STICKY
        }

        startProjection(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startCaptureForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        releaseProjection()

        val result = runCatching {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection session unavailable")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, captureHandler)

            val (rawW, rawH, dpi) = resolveDisplayBounds()
            val (scaledW, scaledH) = ScreenCaptureManager.computeScaledCaptureSize(rawW, rawH)
            val scaledDpi = ((dpi.toFloat() * scaledW.toFloat()) / rawW.toFloat())
                .toInt()
                .coerceAtLeast(DisplayMetrics.DENSITY_LOW)
            currentDensityDpi = scaledDpi
            currentWidth = scaledW
            currentHeight = scaledH

            val reader = createConfiguredReader(scaledW, scaledH)
            imageReader = reader

            virtualDisplay = projection.createVirtualDisplay(
                "ShaderVeilScreenCapture",
                scaledW,
                scaledH,
                scaledDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            ) ?: throw IllegalStateException("VirtualDisplay creation returned null")

            ScreenCaptureManager.setCapturing(true)
            OverlayRuntimeBridge.clearRuntimeError()
        }

        result.onFailure { err ->
            Log.e(TAG, "Screen capture projection failed", err)
            releaseProjection()
            ScreenCaptureManager.clear()
            OverlayRuntimeBridge.reportRuntimeError(
                "Screen capture stopped: ${err.localizedMessage ?: err.javaClass.simpleName}"
            )
            stopSelf()
        }
    }

    private fun recreateReaderAndResize(nextW: Int, nextH: Int) {
        if (isDestroyed) return
        val vd = virtualDisplay ?: return
        currentWidth = nextW
        currentHeight = nextH
        val oldReader = imageReader
        val newReader = createConfiguredReader(nextW, nextH)
        imageReader = newReader
        vd.resize(nextW, nextH, currentDensityDpi)
        vd.surface = newReader.surface
        oldReader?.let { reader ->
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
        }
    }

    private fun createConfiguredReader(width: Int, height: Int): ImageReader {
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                if (isDestroyed) return@setOnImageAvailableListener
                val img = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    ScreenCaptureManager.publishImage(img)
                } catch (t: Throwable) {
                    Log.w(TAG, "Dropped screen capture frame", t)
                } finally {
                    runCatching { img.close() }
                }
            }, captureHandler)
        }
    }

    private fun resolveDisplayBounds(): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        val fallbackDpi = dm.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wmBounds = runCatching {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val bounds = wm.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    Triple(bounds.width().coerceAtLeast(64), bounds.height().coerceAtLeast(64), fallbackDpi)
                } else null
            }.getOrNull()
            if (wmBounds != null) return wmBounds
        }

        val displayBounds = runCatching {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching null
            @Suppress("DEPRECATION")
            val realSize = Point().also { display.getRealSize(it) }
            if (realSize.x > 0 && realSize.y > 0) {
                Triple(realSize.x.coerceAtLeast(64), realSize.y.coerceAtLeast(64), fallbackDpi)
            } else null
        }.getOrNull()
        if (displayBounds != null) return displayBounds

        return Triple(dm.widthPixels.coerceAtLeast(64), dm.heightPixels.coerceAtLeast(64), fallbackDpi)
    }

    private fun releaseProjection() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        imageReader?.let { reader ->
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
        }
        imageReader = null
        mediaProjection?.let { proj ->
            runCatching { proj.unregisterCallback(projectionCallback) }
            runCatching { proj.stop() }
        }
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.capture_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.capture_channel_description)
                    setShowBadge(false)
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shaderveil)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.capture_action_stop), stopIntent)
            .build()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isDestroyed || virtualDisplay == null) return
        captureHandler?.post {
            if (isDestroyed) return@post
            runCatching {
                val (rawW, rawH, dpi) = resolveDisplayBounds()
                val (scaledW, scaledH) = ScreenCaptureManager.computeScaledCaptureSize(rawW, rawH)
                if (scaledW != currentWidth || scaledH != currentHeight) {
                    currentDensityDpi = ((dpi.toFloat() * scaledW.toFloat()) / rawW.toFloat())
                        .toInt()
                        .coerceAtLeast(DisplayMetrics.DENSITY_LOW)
                    recreateReaderAndResize(scaledW, scaledH)
                }
            }
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        captureHandler?.removeCallbacksAndMessages(null)
        releaseProjection()
        ScreenCaptureManager.clear()
        runCatching { captureThread?.quitSafely() }
        captureThread = null
        captureHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "shaderveil_capture_channel"
        private const val NOTIFICATION_ID = 4042
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_START = "com.example.shaderveil.ACTION_CAPTURE_START"
        const val ACTION_STOP = "com.example.shaderveil.ACTION_CAPTURE_STOP"

        fun start(context: Context, resultCode: Int, data: Intent) {
            runCatching {
                val intent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { err ->
                Log.e(TAG, "Failed starting ScreenCaptureService", err)
                ScreenCaptureManager.clear()
                OverlayRuntimeBridge.reportRuntimeError(
                    "Unable to start screen capture: ${err.localizedMessage ?: err.javaClass.simpleName}"
                )
            }
        }

        fun stop(context: Context) {
            if (!ScreenCaptureManager.isCapturing.value) {
                ScreenCaptureManager.clear()
                return
            }
            runCatching {
                val intent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            }.onFailure {
                ScreenCaptureManager.clear()
            }
        }
    }
}
