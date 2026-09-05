package dev.autotune.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import dev.autotune.core.engine.AppProfile
import dev.autotune.core.engine.StabilizerEngine
import dev.autotune.core.features.AnalysisFormat
import dev.autotune.tv.R
import dev.autotune.tv.capture.AnalysisSource
import dev.autotune.tv.capture.AnalysisSourceFactory
import dev.autotune.tv.capture.SourceKind
import dev.autotune.tv.effects.AudioOutputProcessor
import dev.autotune.tv.effects.OutputProcessorFactory
import dev.autotune.tv.session.PlaybackMonitor
import dev.autotune.tv.settings.SettingsRepository
import dev.autotune.tv.ui.MainActivity
import kotlin.math.pow

/**
 * The long-running piece: reads audio, runs the engine, drives the effect.
 *
 * Lifecycle is deliberately forgiving. It starts at boot with whatever it is
 * allowed to do at that moment - on most devices that means the fixed dialogue
 * preset only - and upgrades itself to full adaptive stabilisation as soon as
 * the user grants capture from the app. It never stops just because one part is
 * unavailable.
 */
class StabilizerService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var settings: SettingsRepository
    private lateinit var monitor: PlaybackMonitor
    private lateinit var engine: StabilizerEngine

    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    // Written on the analysis thread, read from the main thread when the
    // notification is rebuilt.
    @Volatile
    private var source: AnalysisSource? = null

    @Volatile
    private var processor: AudioOutputProcessor? = null
    private var projection: MediaProjection? = null

    @Volatile
    private var analysing = false

    private val format = AnalysisFormat.DEFAULT

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "media projection revoked")
            handler?.post { stopAnalysis(); startAnalysis(userInitiated = false) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        monitor = PlaybackMonitor(this)
        engine = StabilizerEngine(settings.toConfig(), format)
        settings.registerListener(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_PROJECTION -> {
                promoteForeground(withProjection = true, withMicrophone = false)
                adoptProjection(intent)
            }
            else -> {
                val userInitiated = intent?.getBooleanExtra(EXTRA_USER_INITIATED, false) == true
                promoteForeground(
                    withProjection = projection != null,
                    withMicrophone = userInitiated && settings.allowMicrophoneFallback,
                )
                ensureRunning(userInitiated)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        settings.unregisterListener(this)
        stopEverything()
        super.onDestroy()
    }

    // ------------------------------------------------------------- lifecycle

    private fun ensureRunning(userInitiated: Boolean) {
        if (worker == null) {
            // Audio analysis gets its own thread at audio priority: a dropped
            // block here would be heard as a late duck.
            val thread = HandlerThread("autotune-analysis", Process.THREAD_PRIORITY_URGENT_AUDIO)
            thread.start()
            worker = thread
            handler = Handler(thread.looper)
        }
        handler?.post {
            attachProcessor()
            startAnalysis(userInitiated)
            startMonitoring()
            publishStatus()
            updateNotification()
        }
    }

    private fun attachProcessor() {
        if (processor != null) return
        val created = OutputProcessorFactory.create(settings.toConfig())
        processor = created
        // Until analysis is up, the fixed preset is what the user actually hears.
        created?.applyStaticPreset(settings.toConfig())
    }

    private fun startAnalysis(userInitiated: Boolean) {
        if (analysing) return
        if (!settings.enabled) return

        val allowMicrophone = settings.allowMicrophoneFallback && userInitiated
        val created = AnalysisSourceFactory.create(this, projection, allowMicrophone, format)
        source = created
        if (created == null) {
            Log.i(TAG, "no analysis source available; running the fixed preset")
            processor?.applyStaticPreset(settings.toConfig())
            return
        }
        if (created.kind == SourceKind.MICROPHONE) {
            promoteForeground(withProjection = projection != null, withMicrophone = true)
        }
        engine.reset()
        analysing = true
        handler?.post(::analysisLoop)
    }

    /**
     * The audio loop.
     *
     * Reposts itself instead of spinning in a `while` so that settings changes,
     * profile switches and shutdown all get a turn on the same thread and no
     * locking is needed around the engine.
     */
    private fun analysisLoop() {
        val active = source
        if (!analysing || active == null) return

        val read = try {
            active.read(buffer)
        } catch (error: Exception) {
            Log.w(TAG, "capture failed", error)
            -1
        }

        if (read < 0) {
            stopAnalysis()
            publishStatus()
            updateNotification()
            // Something took the source away (a projection revoked, the mic
            // grabbed by another app). Try again shortly rather than giving up.
            handler?.postDelayed({ startAnalysis(userInitiated = false) }, RETRY_DELAY_MS)
            return
        }

        if (read > 0) {
            if (active.isClosedLoop) removeOwnGain(buffer, read)
            val state = engine.process(buffer, read)

            val now = System.currentTimeMillis()
            if (now - lastEffectUpdate >= AudioOutputProcessor.MIN_UPDATE_INTERVAL_MS) {
                processor?.apply(state)
                lastEffectUpdate = now
            }
            if (now - lastStatusUpdate >= STATUS_INTERVAL_MS) {
                StabilizerStatusBus.publishState(state)
                lastStatusUpdate = now
            }
            if (now - lastNotificationUpdate >= NOTIFICATION_INTERVAL_MS) {
                updateNotification()
                monitor.refresh()
                lastNotificationUpdate = now
            }
        }
        handler?.post(::analysisLoop)
    }

    /**
     * The microphone hears Autotune's own correction, so the loop would chase
     * itself. Undoing the applied gain recovers an estimate of the source level.
     */
    private fun removeOwnGain(samples: FloatArray, length: Int) {
        val applied = engine.state.gainDb
        if (applied == 0f) return
        val factor = 10.0.pow(-applied / 20.0).toFloat()
        for (i in 0 until length) samples[i] *= factor
    }

    private fun stopAnalysis() {
        analysing = false
        source?.close()
        source = null
    }

    private fun startMonitoring() {
        monitor.start { profile, packageName ->
            handler?.post {
                engine.profile = if (settings.perAppProfiles) profile else AppProfile.GENERIC
                StabilizerStatusBus.update { it.copy(playingPackage = packageName) }
                updateNotification()
            }
        }
    }

    private fun stopEverything() {
        handler?.post {
            stopAnalysis()
            processor?.close()
            processor = null
        }
        monitor.stop()
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
        worker?.quitSafely()
        worker = null
        handler = null
        StabilizerStatusBus.clear()
        stopForegroundCompat()
    }

    // ------------------------------------------------------------ projection

    private fun adoptProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            ensureRunning(userInitiated = true)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val granted = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (granted == null) {
            Log.w(TAG, "media projection could not be created")
            ensureRunning(userInitiated = true)
            return
        }
        // Android 14 requires a callback to be registered before capture starts.
        granted.registerCallback(projectionCallback, Handler(mainLooper))
        projection = granted
        ensureRunning(userInitiated = true)
        handler?.post {
            // Restart analysis so the better source is picked up immediately.
            stopAnalysis()
            startAnalysis(userInitiated = true)
            publishStatus()
            updateNotification()
        }
    }

    // ---------------------------------------------------------- notification

    /**
     * Foreground service types are the fiddliest part of this app.
     *
     * `microphone` and `mediaProjection` are "while-in-use" types: Android 14
     * refuses to start them from the background, and refuses `microphone` at all
     * without the runtime permission in hand. So the service claims the narrow
     * `specialUse` type at boot and only widens it when the user has actually
     * done something - opened the app, granted capture - that makes the wider
     * type legal.
     */
    private fun promoteForeground(withProjection: Boolean, withMicrophone: Boolean) {
        val notification = buildNotification()
        val wantsMicrophone = withMicrophone && AnalysisSourceFactory.hasRecordPermission(this)

        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (withProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (wantsMicrophone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        try {
            if (types != 0) {
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                // Pre-34 with nothing special to claim: the manifest types apply.
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            // A rejected wide type must not take the whole service down; fall
            // back to the narrowest thing the platform will accept.
            Log.w(TAG, "could not start foreground with types $types", error)
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val active = source
        val text = when {
            active == null && AnalysisSourceFactory.hasRecordPermission(this) ->
                getString(R.string.status_no_analysis)
            active == null -> getString(R.string.notification_needs_permission)
            else -> getString(
                R.string.notification_text_format,
                getString(R.string.status_analysis_format, active.label),
                getString(R.string.meter_gain_format, engine.state.gainDb),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun publishStatus() {
        val active = source
        StabilizerStatusBus.update {
            it.copy(
                running = true,
                sourceKind = active?.kind,
                sourceLabel = active?.label,
                processorLabel = processor?.label,
                playingPackage = monitor.currentPackage,
            )
        }
    }

    // ------------------------------------------------------------- settings

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        handler?.post {
            val config = settings.toConfig()
            engine.config = config
            if (!config.enabled) {
                stopAnalysis()
                processor?.close()
                processor = null
                stopSelf()
            } else {
                attachProcessor()
                if (!analysing) startAnalysis(userInitiated = false)
                if (source == null) processor?.applyStaticPreset(config)
            }
            publishStatus()
            updateNotification()
        }
    }

    private val buffer = FloatArray(AnalysisFormat.DEFAULT.hopSize * 4)
    private var lastEffectUpdate = 0L
    private var lastStatusUpdate = 0L
    private var lastNotificationUpdate = 0L

    companion object {
        private const val TAG = "StabilizerService"
        private const val CHANNEL_ID = "autotune-stabilizer"
        private const val NOTIFICATION_ID = 1001

        private const val RETRY_DELAY_MS = 5_000L
        private const val STATUS_INTERVAL_MS = 100L
        private const val NOTIFICATION_INTERVAL_MS = 5_000L

        const val ACTION_START = "dev.autotune.tv.action.START"
        const val ACTION_STOP = "dev.autotune.tv.action.STOP"
        const val ACTION_SET_PROJECTION = "dev.autotune.tv.action.SET_PROJECTION"
        const val EXTRA_USER_INITIATED = "userInitiated"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }
}
