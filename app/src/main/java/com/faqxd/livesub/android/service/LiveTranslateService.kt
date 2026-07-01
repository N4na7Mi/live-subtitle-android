package com.faqxd.livesub.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.faqxd.livesub.android.MainActivity
import com.faqxd.livesub.android.R
import com.faqxd.livesub.android.SessionLogActivity
import com.faqxd.livesub.android.audio.AudioCapture
import com.faqxd.livesub.android.audio.AudioPlayer
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.data.SessionLogStore
import com.faqxd.livesub.android.gemini.GeminiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the live-translate pipeline.
 *
 * Equivalent of [main.py:LiveBuddyApp]:
 *  - Owns [GeminiClient], [AudioCapture], [AudioPlayer], [CaptionOverlayView].
 *  - Started via [ACTION_START] from [MainActivity]; stopped via [ACTION_STOP]
 *    or system swipe-away (we re-launch the foreground notification).
 *  - Audio source is taken from [AppSettings.audioSource]:
 *      * "mic"     → AudioRecord(RECORD_AUDIO) inside [AudioCapture].
 *      * "system"  → MediaProjection loopback. The projection token is
 *                    forwarded to the service via the intent extra
 *                    [EXTRA_RESULT_CODE] / [EXTRA_RESULT_DATA].
 *
 * The HUD overlay is added as soon as the service starts, so the user sees
 * the floating panel immediately, even before the WebSocket connects.
 */
class LiveTranslateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settings: AppSettings? = null
    private var overlay: CaptionOverlayView? = null
    private var client: GeminiClient? = null
    private var capture: AudioCapture? = null
    private var player: AudioPlayer? = null
    private var mediaProjection: MediaProjection? = null
    private var clientGeneration = 0
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isActive = true
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                // On Android 14+ startForeground must declare the exact
                // service types in use. If a projection token is attached,
                // we'll use mediaProjection; otherwise it's mic-only.
                startForegroundIfNeeded(useSystemAudio = resultData != null)
                startPipeline(resultCode, resultData)
            }
            ACTION_STOP -> {
                stopServiceAndOverlay()
            }
            ACTION_TOGGLE -> togglePipeline()
            ACTION_APPLY_SETTINGS -> applyUpdatedSettings()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPipeline()
        overlay?.detach()
        overlay = null
        isActive = false
        scope.coroutineContext[Job]?.cancel()
    }

    // ---------- pipeline ----------

    private fun startPipeline(resultCode: Int, resultData: Intent?) {
        if (running) return
        val s = AppSettings.load(this).also { settings = it }
        if (s.apiKey.isBlank()) {
            notifyStatus(getString(R.string.err_no_api_key))
            return
        }

        // Overlay
        ensureOverlay(s)

        // Player (echo)
        if (s.echoTargetLanguage) {
            try {
                val p = AudioPlayer(volume = s.playbackVolume).also { player = it }
                p.start()
            } catch (e: Exception) {
                Log.w(TAG, "AudioPlayer init failed: ${e.message}")
                player = null
            }
        }

        SessionLogStore.startSession(this)
        overlay?.clear()

        // Gemini client
        val c = createConfiguredClient(s).also { client = it }

        running = true
        overlay?.setRunningState(true)
        overlay?.setStatus(getString(R.string.status_connecting))
        updateNotification(running = true)

        if (!c.start()) {
            cleanupAfterPipelineFailure()
            return
        }

        // Audio capture
        val cap = AudioCapture(
            onChunk = { pcm16 -> client?.sendAudio(pcm16) },
            chunkMs = s.audioChunkMs,
            targetRate = s.audioSampleRate,
        ).also { capture = it }
        try {
            if (s.audioSource == "system" && resultData != null) {
                startSystemCapture(cap, resultCode, resultData)
            } else {
                cap.startMicrophone()
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioCapture start failed", e)
            cleanupAfterPipelineFailure()
            val error = getString(R.string.err_capture, e.message ?: "unknown")
            overlay?.setStatus(error)
            notifyStatus(error)
            return
        }
    }

    private fun stopPipeline() {
        if (!running && client == null && capture == null) return
        running = false
        try { capture?.stop() } catch (_: Exception) {}
        capture = null
        try { player?.stop() } catch (_: Exception) {}
        player = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        clientGeneration++
        client?.stop()
        client = null
        overlay?.setRunningState(false)
        overlay?.setStatus(getString(R.string.status_stopped))
        updateNotification(running = false)
    }

    private fun cleanupAfterPipelineFailure() {
        running = false
        try { capture?.stop() } catch (_: Exception) {}
        capture = null
        try { player?.stop() } catch (_: Exception) {}
        player = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        clientGeneration++
        client?.stop()
        client = null
        overlay?.setRunningState(false)
        updateNotification(running = false)
    }

    private fun createConfiguredClient(s: AppSettings): GeminiClient =
        GeminiClient(listener = createClientListener(++clientGeneration)).also { c ->
            c.configure(
                apiKey = s.apiKey,
                sourceLang = s.sourceLanguage,
                targetLang = s.targetLanguage,
                inputAudioRate = s.audioSampleRate,
                systemPrompt = s.systemPrompt,
                echoTargetLanguage = s.echoTargetLanguage,
                apiBase = s.apiBase,
                apiHostOverride = s.apiHostOverride,
                proxyEnabled = s.proxyEnabled,
                proxyType = s.proxyType,
                proxyHost = s.proxyHost,
                proxyPort = s.proxyPort,
            )
        }

    private fun restartSession() {
        if (!running || capture == null) {
            overlay?.setStatus(getString(R.string.restart_requires_running))
            overlay?.setRunningState(false)
            return
        }

        val s = AppSettings.load(this).also { settings = it }
        overlay?.clear()
        overlay?.setStatus(getString(R.string.status_restarting_session))
        SessionLogStore.startSession(this)

        val old = client
        client = null
        clientGeneration++
        old?.stop()

        val next = createConfiguredClient(s).also { client = it }
        if (!next.start()) {
            cleanupAfterPipelineFailure()
            return
        }
        running = true
        overlay?.setRunningState(true)
        updateNotification(running = true)
    }

    private fun switchAudioSource() {
        val current = AppSettings.load(this)
        val nextSource = if (current.audioSource == "system") "mic" else "system"
        current.audioSource = nextSource
        current.save(this)
        settings = current

        stopPipeline()
        overlay?.setAudioSource(nextSource)
        overlay?.clear()

        if (nextSource == "system") {
            overlay?.setStatus(getString(R.string.status_need_system_audio_permission))
            startActivity(
                MainActivity.requestSystemAudioIntent(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            return
        }

        overlay?.setStatus(getString(R.string.status_switching_audio_source, getString(R.string.source_mic_short)))
        startPipeline(0, null)
    }

    private fun applyUpdatedSettings() {
        val previous = settings
        val next = AppSettings.load(this)
        settings = next
        overlay?.updateSettings(next)
        overlay?.setAudioSource(next.audioSource)

        val restartNeeded = running && previous != null && requiresSessionRestart(previous, next)
        val message = if (restartNeeded) {
            getString(R.string.settings_saved_restart_hint)
        } else {
            getString(R.string.settings_saved_applied)
        }
        overlay?.setStatus(message)
        updateNotification(running = running)
    }

    private fun requiresSessionRestart(old: AppSettings, new: AppSettings): Boolean =
        old.apiKey != new.apiKey ||
            old.apiBase != new.apiBase ||
            old.apiHostOverride != new.apiHostOverride ||
            old.proxyEnabled != new.proxyEnabled ||
            old.proxyType != new.proxyType ||
            old.proxyHost != new.proxyHost ||
            old.proxyPort != new.proxyPort ||
            old.sourceLanguage != new.sourceLanguage ||
            old.targetLanguage != new.targetLanguage ||
            old.audioSource != new.audioSource ||
            old.audioChunkMs != new.audioChunkMs ||
            old.audioSampleRate != new.audioSampleRate ||
            old.echoTargetLanguage != new.echoTargetLanguage ||
            old.playbackVolume != new.playbackVolume ||
            old.systemPrompt != new.systemPrompt

    private fun togglePipeline() {
        if (running) {
            stopPipeline()
            return
        }
        if (settings?.audioSource == "system") {
            overlay?.setStatus(getString(R.string.perm_system_audio_rationale))
            startActivity(
                MainActivity.requestSystemAudioIntent(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            return
        }
        startPipeline(0, null)
    }

    private fun stopServiceAndOverlay() {
        stopPipeline()
        overlay?.detach()
        overlay = null
        isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- overlay ----------

    private fun ensureOverlay(s: AppSettings) {
        if (overlay == null) {
            overlay = CaptionOverlayView(
                context = this,
                settings = s,
                callbacks = object : CaptionOverlayView.Callbacks {
                    override fun onToggleClicked() {
                        togglePipeline()
                    }
                    override fun onRestartClicked() {
                        restartSession()
                    }
                    override fun onLogClicked() {
                        startActivity(
                            Intent(this@LiveTranslateService, SessionLogActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    override fun onAudioSourceClicked() {
                        switchAudioSource()
                    }
                    override fun onCloseClicked() {
                        stopServiceAndOverlay()
                    }
                    override fun onSettingsClicked() {
                        startActivity(
                            Intent(this@LiveTranslateService, com.faqxd.livesub.android.SettingsActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
            overlay?.init()
        }
        overlay?.applyStyle()
        try {
            overlay?.attach()
            refreshOverlayFromSessionLog()
        } catch (e: Exception) {
            Log.e(TAG, "overlay attach failed: ${e.message}")
            notifyStatus(getString(R.string.err_no_overlay_perm))
        }
    }

    private fun refreshOverlayFromSessionLog() {
        val output = SessionLogStore.loadLastOutput(this)
        if (output.isNotBlank()) overlay?.setOutput(output)
    }

    // ---------- media projection ----------

    private fun startSystemCapture(cap: AudioCapture, resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        val mp = mpm.getMediaProjection(resultCode, data) ?: run {
            throw RuntimeException("MediaProjection token rejected")
        }
        mediaProjection = mp

        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val srcRate = 48000
        val srcChannels = AudioFormat.CHANNEL_IN_STEREO
        val minBuf = AudioRecord.getMinBufferSize(srcRate, srcChannels, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(8192)
        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(srcRate)
                    .setChannelMask(srcChannels)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw RuntimeException("Loopback AudioRecord not initialized")
        }
        cap.startSystemAudio(record, srcRate, /* channels = */ 2)
    }

    // ---------- gemini listener (forwards to overlay on main thread) ----------

    private fun createClientListener(generation: Int) = object : GeminiClient.Listener {
        override fun onInputTranscript(text: String) {
            if (generation != clientGeneration) return
            SessionLogStore.appendInput(this@LiveTranslateService, text)
        }
        override fun onOutputTranscript(text: String) {
            if (generation != clientGeneration) return
            SessionLogStore.appendOutput(this@LiveTranslateService, text)
            scope.launch {
                val currentOverlay = overlay
                if (currentOverlay == null || !currentOverlay.isAttached) {
                    ensureOverlay(settings ?: AppSettings.load(this@LiveTranslateService).also { settings = it })
                }
                refreshOverlayFromSessionLog()
            }
        }
        override fun onAudioChunk(pcm16: ByteArray) {
            if (generation != clientGeneration) return
            // AudioTrack writes are blocking; do them on a dedicated thread
            // (OkHttp dispatcher in this case) to avoid stalling the WS reader.
            player?.enqueuePcm16(pcm16)
        }
        override fun onStatus(status: String) {
            if (generation != clientGeneration) return
            scope.launch { overlay?.setStatus(status) }
        }
        override fun onConnected() {
            if (generation != clientGeneration) return
            scope.launch {
                overlay?.setStatus(getString(R.string.status_connected))
                updateNotification(running = true)
            }
        }
        override fun onDisconnected(reason: String) {
            if (generation != clientGeneration) return
            scope.launch {
                running = false
                try { capture?.stop() } catch (_: Exception) {}
                capture = null
                try { player?.stop() } catch (_: Exception) {}
                player = null
                try { mediaProjection?.stop() } catch (_: Exception) {}
                mediaProjection = null
                client = null
                overlay?.setRunningState(false)
                overlay?.setStatus(
                    "${getString(R.string.status_disconnected)}: ${reason.cleanDisconnectReason()}；点开始重试"
                        .take(120)
                )
                updateNotification(running = false)
            }
        }
    }

    // ---------- notification ----------

    private fun startForegroundIfNeeded(useSystemAudio: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
                nm.createNotificationChannel(channel)
            }
        }
        val notif = buildNotification(running)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (useSystemAudio) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(running: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(running))
    }

    private fun buildNotification(running: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LiveTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                if (running) getString(R.string.notif_text_running)
                else getString(R.string.notif_text_paused)
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    private fun notifyStatus(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    companion object {
        private const val TAG = "LiveTranslateService"
        private const val CHANNEL_ID = "live_translate"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.faqxd.livesub.android.START"
        const val ACTION_STOP = "com.faqxd.livesub.android.STOP"
        const val ACTION_TOGGLE = "com.faqxd.livesub.android.TOGGLE"
        const val ACTION_APPLY_SETTINGS = "com.faqxd.livesub.android.APPLY_SETTINGS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        @Volatile var isActive: Boolean = false
            private set

        fun startIntent(context: Context, resultCode: Int, data: Intent?): Intent =
            Intent(context, LiveTranslateService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, LiveTranslateService::class.java).setAction(ACTION_STOP)

        fun applySettingsIntent(context: Context): Intent =
            Intent(context, LiveTranslateService::class.java).setAction(ACTION_APPLY_SETTINGS)
    }
}

private fun String.cleanDisconnectReason(): String =
    removePrefix("Error:")
        .removePrefix("Closed:")
        .trim()
        .ifBlank { "unknown" }
