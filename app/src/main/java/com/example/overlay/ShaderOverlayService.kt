package com.example.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.OverlayRuntimeBridge
import com.example.model.AntiEchoMode
import com.example.model.BuiltInTexture
import com.example.model.ShaderProject
import com.example.model.UniformType
import com.example.shader.GlShaderEngine
import com.example.shader.ScreenCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ShaderOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var collectorJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null
    private var overlayTextureView: TextureView? = null
    private var glEngine: GlShaderEngine? = null

    private var screenOffPaused = false
    private var screenReceiverRegistered = false

    private var antiEchoActive = false
    private var lastAntiEchoMode: AntiEchoMode? = null
    private var lastIntervalMs: Int = 500
    private var capturingCleanFrame = false

    @Volatile
    private var isDestroyed = false

    private val periodicPulseRunnable = Runnable {
        if (!isDestroyed) {
            triggerCleanCapturePulse()
        }
    }

    private val restoreWatchdogRunnable = Runnable {
        if (!isDestroyed) {
            if (!ScreenCaptureManager.promoteStagedFrameIfAvailable()) {
                completeCleanCapturePulse()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isDestroyed) return
            val pauseOnOff = OverlayRuntimeBridge.snapshot.value.settings.pauseOnScreenOff
            if (!pauseOnOff) {
                screenOffPaused = false
                evaluateAntiEchoAndPushSnapshot()
                return
            }
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffPaused = true
                    evaluateAntiEchoAndPushSnapshot()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOffPaused = false
                    evaluateAntiEchoAndPushSnapshot()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isDestroyed = false
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                OverlayRuntimeBridge.setOverlayRunning(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                val next = !OverlayRuntimeBridge.isOverlayPaused.value
                OverlayRuntimeBridge.setOverlayPaused(next)
                updateNotification()
                evaluateAntiEchoAndPushSnapshot()
                return START_STICKY
            }
            ACTION_REFRESH_SCREEN -> {
                if (overlayTextureView != null && !isDestroyed) {
                    triggerCleanCapturePulse()
                }
                return START_STICKY
            }
        }

        val fgOk = runCatching {
            startForegroundWithType(buildNotification())
        }.onFailure { err ->
            Log.e(TAG, "Unable to start ShaderOverlayService foreground", err)
            OverlayRuntimeBridge.reportRuntimeError("Overlay service failed to start: ${err.localizedMessage ?: "foreground error"}")
        }.isSuccess

        if (!fgOk || !Settings.canDrawOverlays(this)) {
            OverlayRuntimeBridge.setOverlayRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (overlayTextureView == null) {
            if (attachOverlayView()) {
                registerScreenReceiver()
                startSnapshotObserver()
                OverlayRuntimeBridge.setOverlayRunning(true)
                OverlayRuntimeBridge.clearRuntimeError()
            } else {
                OverlayRuntimeBridge.setOverlayRunning(false)
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            updateNotification()
        }

        return START_STICKY
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun attachOverlayView(): Boolean {
        val wm = windowManager ?: return false
        val initialSnap = OverlayRuntimeBridge.snapshot.value
        val captureActive = ScreenCaptureManager.isCapturing.value
        val needsInitialAntiEcho = captureActive &&
            projectUsesScreenCapture(initialSnap.project) &&
            initialSnap.settings.antiEchoMode != AntiEchoMode.ALLOW_FEEDBACK

        if (needsInitialAntiEcho) {
            ScreenCaptureManager.setOverlayGating(true)
        }

        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = if (needsInitialAntiEcho) View.INVISIBLE else View.VISIBLE
        }

        val textureView = TextureView(this).apply {
            isOpaque = false
            alpha = if (needsInitialAntiEcho) 0f else initialSnap.settings.overlayAlpha.coerceIn(0f, 1f)
            visibility = View.VISIBLE
        }

        container.addView(
            textureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val engine = GlShaderEngine(
            context = applicationContext,
            tag = "SystemOverlay",
            onTelemetry = { telemetry ->
                OverlayRuntimeBridge.publishTelemetry(telemetry)
            }
        )

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (isDestroyed) return
                val snap = OverlayRuntimeBridge.snapshot.value
                val effectivePaused = isEffectivelyPaused(snap.settings.pauseOnScreenOff) || capturingCleanFrame
                engine.start(surface, width, height, snap.copy(isPaused = effectivePaused))
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                if (!isDestroyed) {
                    engine.resize(width, height)
                }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                engine.stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        val params = buildOverlayLayoutParams()

        return runCatching {
            wm.addView(container, params)
            glEngine = engine
            overlayContainer = container
            overlayTextureView = textureView
            true
        }.onFailure { err ->
            Log.e(TAG, "Failed attaching overlay window", err)
            ScreenCaptureManager.setOverlayGating(false)
            engine.stop()
            glEngine = null
            overlayContainer = null
            overlayTextureView = null
            OverlayRuntimeBridge.reportRuntimeError(
                "Could not attach shader overlay: ${err.localizedMessage ?: "window permission error"}"
            )
        }.getOrDefault(false)
    }

    private fun buildOverlayLayoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val container = overlayContainer ?: return
        val wm = windowManager ?: return
        runCatching {
            wm.updateViewLayout(container, buildOverlayLayoutParams())
        }
    }

    private fun startSnapshotObserver() {
        collectorJob?.cancel()
        collectorJob = serviceScope.launch {
            combine(
                OverlayRuntimeBridge.snapshot,
                OverlayRuntimeBridge.isOverlayPaused,
                ScreenCaptureManager.isCapturing,
            ) { snapshot, paused, captureActive ->
                Triple(snapshot, paused, captureActive)
            }.collect { (snapshot, _, _) ->
                if (!isDestroyed) {
                    if (!capturingCleanFrame) {
                        overlayTextureView?.alpha = snapshot.settings.overlayAlpha.coerceIn(0f, 1f)
                    }
                    evaluateAntiEchoAndPushSnapshot()
                }
            }
        }
    }

    private fun evaluateAntiEchoAndPushSnapshot() {
        if (isDestroyed) return
        val snap = OverlayRuntimeBridge.snapshot.value
        val paused = isEffectivelyPaused(snap.settings.pauseOnScreenOff)
        val captureActive = ScreenCaptureManager.isCapturing.value
        val mode = snap.settings.antiEchoMode
        val intervalMs = snap.settings.screenCaptureIntervalMs.coerceIn(200, 5000)

        val shouldGate = !paused &&
            captureActive &&
            projectUsesScreenCapture(snap.project) &&
            mode != AntiEchoMode.ALLOW_FEEDBACK

        val modeOrStateChanged = shouldGate != antiEchoActive ||
            mode != lastAntiEchoMode ||
            intervalMs != lastIntervalMs

        antiEchoActive = shouldGate
        lastAntiEchoMode = mode
        lastIntervalMs = intervalMs

        ScreenCaptureManager.setOverlayGating(shouldGate)

        if (!shouldGate) {
            cancelCleanPulseTimers()
            capturingCleanFrame = false
            ScreenCaptureManager.cancelCleanCaptureWindow()
            overlayTextureView?.alpha = snap.settings.overlayAlpha.coerceIn(0f, 1f)
            val vis = if (paused) View.INVISIBLE else View.VISIBLE
            overlayContainer?.visibility = vis
            overlayTextureView?.visibility = vis
            glEngine?.updateSnapshot(snap.copy(isPaused = paused))
            if (modeOrStateChanged) updateNotification()
            return
        }

        if (!capturingCleanFrame) {
            overlayTextureView?.alpha = snap.settings.overlayAlpha.coerceIn(0f, 1f)
            overlayContainer?.visibility = View.VISIBLE
            overlayTextureView?.visibility = View.VISIBLE
            glEngine?.updateSnapshot(snap.copy(isPaused = false))
        }

        if (modeOrStateChanged) {
            cancelCleanPulseTimers()
            updateNotification()
            if (mode == AntiEchoMode.CLEAN_PULSE || mode == AntiEchoMode.LOCK_CLEAN) {
                mainHandler.postDelayed(periodicPulseRunnable, 40L)
            }
        }
    }

    private fun triggerCleanCapturePulse() {
        if (isDestroyed || capturingCleanFrame || overlayTextureView == null) return
        val snap = OverlayRuntimeBridge.snapshot.value
        val paused = isEffectivelyPaused(snap.settings.pauseOnScreenOff)
        val captureActive = ScreenCaptureManager.isCapturing.value
        if (paused || !captureActive || !projectUsesScreenCapture(snap.project) ||
            snap.settings.antiEchoMode == AntiEchoMode.ALLOW_FEEDBACK
        ) {
            return
        }

        cancelCleanPulseTimers()

        capturingCleanFrame = true
        ScreenCaptureManager.setOverlayGating(true)
        ScreenCaptureManager.cancelCleanCaptureWindow()
        overlayTextureView?.alpha = 0f

        val openWindowAfterBlank = { blankSwapNs: Long ->
            if (!isDestroyed && capturingCleanFrame && overlayTextureView != null) {
                ScreenCaptureManager.beginCleanCaptureWindow(blankSwapNs = blankSwapNs) {
                    if (!isDestroyed) {
                        mainHandler.post { completeCleanCapturePulse() }
                    }
                }
                // Hiding the container right after 2 transparent swaps forces SurfaceFlinger to
                // composite an overlay-free display frame cleanly even when screen content is static.
                overlayContainer?.visibility = View.INVISIBLE
                overlayTextureView?.visibility = View.INVISIBLE
                mainHandler.postDelayed(restoreWatchdogRunnable, CLEAN_CAPTURE_WATCHDOG_MS)
            } else {
                completeCleanCapturePulse()
            }
        }

        val engine = glEngine
        if (engine != null) {
            engine.blankAndPause(snap) { blankSwapNs ->
                if (!isDestroyed) {
                    mainHandler.post { openWindowAfterBlank(blankSwapNs) }
                }
            }
        } else {
            openWindowAfterBlank(System.nanoTime())
        }
    }

    private fun completeCleanCapturePulse() {
        mainHandler.removeCallbacks(restoreWatchdogRunnable)
        ScreenCaptureManager.cancelCleanCaptureWindow()
        if (isDestroyed || !capturingCleanFrame) {
            capturingCleanFrame = false
            return
        }

        capturingCleanFrame = false
        val snap = OverlayRuntimeBridge.snapshot.value
        val paused = isEffectivelyPaused(snap.settings.pauseOnScreenOff)

        overlayTextureView?.alpha = snap.settings.overlayAlpha.coerceIn(0f, 1f)
        val vis = if (paused) View.INVISIBLE else View.VISIBLE
        overlayContainer?.visibility = vis
        overlayTextureView?.visibility = vis
        glEngine?.updateSnapshot(snap.copy(isPaused = paused))

        if (!paused &&
            ScreenCaptureManager.isCapturing.value &&
            projectUsesScreenCapture(snap.project) &&
            snap.settings.antiEchoMode == AntiEchoMode.CLEAN_PULSE
        ) {
            mainHandler.removeCallbacks(periodicPulseRunnable)
            mainHandler.postDelayed(
                periodicPulseRunnable,
                snap.settings.screenCaptureIntervalMs.toLong().coerceIn(250L, 5000L)
            )
        }
    }

    private fun cancelCleanPulseTimers() {
        mainHandler.removeCallbacks(periodicPulseRunnable)
        mainHandler.removeCallbacks(restoreWatchdogRunnable)
    }

    private fun isEffectivelyPaused(pauseOnScreenOff: Boolean): Boolean {
        return OverlayRuntimeBridge.isOverlayPaused.value ||
            (screenOffPaused && pauseOnScreenOff)
    }

    private fun projectUsesScreenCapture(project: ShaderProject): Boolean {
        return project.variables.any {
            it.type == UniformType.TEXTURE &&
                it.textureUri.isNullOrBlank() &&
                it.builtInTexture == BuiltInTexture.SCREEN_CAPTURE
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.overlay_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.overlay_channel_description)
                    setShowBadge(false)
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(channel)
            }
        }
    }

    private fun updateNotification() {
        if (isDestroyed) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val paused = OverlayRuntimeBridge.isOverlayPaused.value
        val snap = OverlayRuntimeBridge.snapshot.value
        val activeTitle = snap.project.title

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val togglePauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ShaderOverlayService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ShaderOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (paused) {
            getString(R.string.overlay_notification_paused)
        } else {
            getString(R.string.overlay_notification_title)
        }
        val pauseLabel = if (paused) {
            getString(R.string.overlay_action_resume)
        } else {
            getString(R.string.overlay_action_pause)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shaderveil)
            .setContentTitle(title)
            .setContentText("Shader: $activeTitle")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, pauseLabel, togglePauseIntent)
            .addAction(0, getString(R.string.overlay_action_stop), stopIntent)

        if (ScreenCaptureManager.isCapturing.value &&
            projectUsesScreenCapture(snap.project) &&
            snap.settings.antiEchoMode != AntiEchoMode.ALLOW_FEEDBACK
        ) {
            val refreshIntent = PendingIntent.getService(
                this,
                3,
                Intent(this, ShaderOverlayService::class.java).apply { action = ACTION_REFRESH_SCREEN },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.overlay_action_refresh), refreshIntent)
        }

        return builder.build()
    }

    override fun onDestroy() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        capturingCleanFrame = false
        ScreenCaptureManager.cancelCleanCaptureWindow()
        ScreenCaptureManager.setOverlayGating(false)
        collectorJob?.cancel()
        serviceScope.cancel()
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        glEngine?.stop()
        glEngine = null
        overlayContainer?.let { view ->
            runCatching { windowManager?.removeViewImmediate(view) }
        }
        overlayContainer = null
        overlayTextureView = null
        OverlayRuntimeBridge.setOverlayRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ShaderOverlayService"
        private const val CHANNEL_ID = "shaderveil_overlay_channel"
        private const val NOTIFICATION_ID = 4041
        private const val CLEAN_CAPTURE_WATCHDOG_MS = 165L
        const val ACTION_START = "com.example.shaderveil.ACTION_START"
        const val ACTION_STOP = "com.example.shaderveil.ACTION_STOP"
        const val ACTION_TOGGLE_PAUSE = "com.example.shaderveil.ACTION_TOGGLE_PAUSE"
        const val ACTION_REFRESH_SCREEN = "com.example.shaderveil.ACTION_REFRESH_SCREEN"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, ShaderOverlayService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { err ->
                Log.e(TAG, "Failed starting ShaderOverlayService", err)
                OverlayRuntimeBridge.setOverlayRunning(false)
                OverlayRuntimeBridge.reportRuntimeError(
                    "Unable to start overlay: ${err.localizedMessage ?: err.javaClass.simpleName}"
                )
            }
        }

        fun stop(context: Context) {
            if (!OverlayRuntimeBridge.isOverlayRunning.value) return
            runCatching {
                val intent = Intent(context, ShaderOverlayService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            }.onFailure {
                OverlayRuntimeBridge.setOverlayRunning(false)
            }
        }

        fun refreshCleanScreen(context: Context) {
            if (!OverlayRuntimeBridge.isOverlayRunning.value) return
            runCatching {
                val intent = Intent(context, ShaderOverlayService::class.java).apply {
                    action = ACTION_REFRESH_SCREEN
                }
                context.startService(intent)
            }
        }
    }
}
