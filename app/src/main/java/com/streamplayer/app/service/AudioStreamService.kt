package com.streamplayer.app.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.lifecycle.LifecycleService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamplayer.app.model.StreamConfig
import com.streamplayer.app.notification.NotificationHelper
import com.streamplayer.app.receiver.BecomingNoisyReceiver
import com.streamplayer.app.receiver.NetworkReceiver
import com.streamplayer.app.receiver.RestartReceiver
import com.streamplayer.app.repository.StreamRepository
import okhttp3.OkHttpClient
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Core foreground service for background audio streaming.
 *
 * Recovery strategy — every stuck-connecting scenario is covered:
 *
 *  1. isRestarting stuck true   → 15s restartingTimeout resets it and schedules retry
 *  2. Buffering stalled forever → 30s bufferingTimeout calls scheduleRetry()
 *  3. Error retry cancelled     → onIsPlayingChanged(true) only cancels focusLossRetry,
 *                                  never the error-retry posted by scheduleRetry()
 *  4. Audio focus race          → Separate Runnable references; no broad null-clear
 *                                  except inside play() itself where it is safe
 *
 * Parallel audio: handleAudioFocus=false so the stream never mutes or interrupts
 * other apps (navigation, music, calls) — audio is mixed at OS level.
 */
class AudioStreamService : LifecycleService() {

    companion object {
        const val ACTION_PLAY   = "com.streamplayer.PLAY"
        const val ACTION_STOP   = "com.streamplayer.STOP"
        const val ACTION_TOGGLE = "com.streamplayer.TOGGLE"
        const val NOTIFICATION_ID = 1001

        const val ACTION_STATE_CHANGED = "com.streamplayer.STATE_CHANGED"
        const val EXTRA_IS_PLAYING     = "is_playing"
        const val EXTRA_IS_CONNECTING  = "is_connecting"
        const val EXTRA_STREAM_NAME    = "stream_name"

        /** Force reconnect if we are stuck buffering longer than this. */
        private const val BUFFERING_TIMEOUT_MS  = 30_000L

        /** Force-clear isRestarting if prepare() never reaches STATE_BUFFERING. */
        private const val RESTARTING_TIMEOUT_MS = 15_000L

        /**
         * Make-up gain (millibels; 100 mB = 1 dB) applied via LoudnessEnhancer at volume 100%.
         * Android's "safe media volume" caps a headset/Bluetooth output roughly 6 dB below the
         * real maximum. ~+8 dB of digital boost more than restores that gap so the stream is
         * audibly at full output, while the LoudnessEnhancer's built-in limiter guards clipping.
         * The boost scales linearly with the user's volume setting (so 50% ≈ +4 dB, etc.).
         */
        private const val HEADSET_BOOST_MILLIBELS = 800
    }

    private lateinit var player: ExoPlayer
    private lateinit var config: StreamConfig
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var networkReceiver: NetworkReceiver
    private lateinit var noisyReceiver: BecomingNoisyReceiver
    /** Digital boost effect that lets us exceed the headset safe-volume cap without
     *  touching setStreamVolume (so no system "high volume" warning is ever shown). */
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var isIntentionallyStopped = false
    private var currentPlaybackUrl: String? = null
    private var usedSslFallbackForCurrentConfig = false
    /** true while play() transitions stop→prepare; suppresses spurious STATE_IDLE retries. */
    private var isRestarting = false
    private var noisyRegistered = false

    // Stored Runnable references for surgical cancellation — compatible with API 22.
    // handler.removeCallbacks(runnable) works on all API levels;
    // handler.removeCallbacksAndMessages(token) needs API 29.
    private var bufferingTimeoutRunnable: Runnable? = null
    private var restartingTimeoutRunnable: Runnable? = null
    private var focusLossRetryRunnable: Runnable? = null

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        config = StreamRepository(this).load()
        notificationHelper = NotificationHelper(this)
        buildPlayer()
        startForeground(NOTIFICATION_ID, notificationHelper.build(config.name, false))
        registerNetworkReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val repo = StreamRepository(this)
        when (intent?.action) {
            ACTION_PLAY -> {
                isIntentionallyStopped = false
                repo.setUserWantsPlaying(true)
                // Skip restart only if the stream is genuinely active:
                //  - isRestarting:     stop→prepare in progress
                //  - STATE_BUFFERING:  downloading — don't interrupt
                //  - STATE_READY + isPlaying: audio is actually flowing
                val alreadyActive = isRestarting ||
                    player.playbackState == Player.STATE_BUFFERING ||
                    (player.playbackState == Player.STATE_READY && player.isPlaying)
                if (!alreadyActive) {
                    retryCount = 0
                    usedSslFallbackForCurrentConfig = false
                    play()
                }
            }
            ACTION_STOP -> {
                isIntentionallyStopped = true
                // If auto-relaunch is disabled, mark that the user no longer wants playback
                // so that watchdogs (WatchdogWorker, NetworkReceiver, RestartReceiver) stay idle.
                if (!config.autoRelaunch) repo.setUserWantsPlaying(false)
                stopPlayback()
                stopSelf()
            }
            ACTION_TOGGLE -> {
                if (player.isPlaying) {
                    isIntentionallyStopped = true
                    stopPlayback()
                } else {
                    isIntentionallyStopped = false
                    repo.setUserWantsPlaying(true)
                    retryCount = 0
                    usedSslFallbackForCurrentConfig = false
                    play()
                }
            }
            null -> {
                // START_STICKY restart (crash / OEM kill).
                // If auto-relaunch is disabled and the user had stopped playback, stay stopped.
                if (!config.autoRelaunch && !repo.isUserWantsPlaying()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                isIntentionallyStopped = false
                retryCount = 0
                play()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Use BroadcastReceiver (not getService) — startForegroundService is allowed
        // from a BroadcastReceiver context even on API 26+.
        // Only schedule a fast restart if auto-relaunch is enabled.
        if (StreamRepository(this).load().autoRelaunch) {
            RestartReceiver.scheduleOneShot(this, 1_000L)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cancelAllTimeouts()
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkReceiver()
        unregisterNoisyReceiver()
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        player.removeListener(playerListener)
        player.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // ─────────────────────────────────────────────
    // Player Construction
    // ─────────────────────────────────────────────

    private fun buildPlayer() {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // 0 = infinite — live stream never finishes
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("StreamPlayer/1.0 (Android)")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                8_000,   // minBuffer == bufferForPlayback — prevents rebuffer loop on live streams
                30_000,  // maxBuffer
                8_000,   // bufferForPlayback
                8_000    // bufferForPlaybackAfterRebuffer
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                exo.addListener(playerListener)
                exo.playWhenReady = true
                exo.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false   // handleAudioFocus=false: stream plays alongside other apps (GPS,
                            // music, calls) without claiming exclusive speaker access.
                )
            }

        // Pin a stable audio session id so the LoudnessEnhancer stays attached for the
        // player's whole lifetime (a session generated lazily on first playback could
        // change across reconnects). API 21+ — min SDK here is 22.
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val sessionId = audioManager.generateAudioSessionId()
            player.setAudioSessionId(sessionId)
            loudnessEnhancer = LoudnessEnhancer(sessionId)
        } catch (_: Exception) {
            // Effect unsupported on this device — playback still works, just without boost.
            loudnessEnhancer = null
        }
    }

    // ─────────────────────────────────────────────
    // Volume — bypasses the headset "safe media volume" cap without a warning dialog
    // ─────────────────────────────────────────────

    /**
     * Applies the user's volume setting and forces true full output on headsets.
     *
     * A 3.5mm jack (even a passive speaker) or any Bluetooth device makes Android treat the
     * output as "headphones" and cap STREAM_MUSIC at the safe-media-volume index, popping a
     * hearing-damage warning if you try to exceed it. Two complementary steps fix that:
     *
     *  1. Raise STREAM_MUSIC to its real maximum (best effort) so the baseline isn't throttled.
     *  2. Add make-up gain via LoudnessEnhancer, which boosts the decoded PCM directly and
     *     never calls setStreamVolume — so the safe-volume cap AND its warning are bypassed.
     */
    private fun applyVolume() {
        val vol = config.volume.coerceIn(1, 100)

        // ExoPlayer software attenuation (0.01–1.0) — keeps the slider meaningful.
        player.setVolume(vol / 100f)

        raiseSystemMusicVolumeToMax()

        loudnessEnhancer?.let { enhancer ->
            try {
                val gain = (HEADSET_BOOST_MILLIBELS * vol) / 100   // scales with the slider
                enhancer.setTargetGain(gain)
                enhancer.enabled = gain > 0
            } catch (_: Exception) {
                // Ignore — falls back to plain setVolume output.
            }
        }
    }

    /** Push the media stream to its hardware maximum. No FLAG_SHOW_UI, so the system volume
     *  panel never appears; wrapped because some OEM/Doze states reject the change. */
    private fun raiseSystemMusicVolumeToMax() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < max) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            }
        } catch (_: Exception) {
            // SecurityException (e.g. DND/zen restrictions) — non-fatal.
        }
    }

    // ─────────────────────────────────────────────
    // Playback Control
    // ─────────────────────────────────────────────

    private fun play(urlOverride: String? = null) {
        config = StreamRepository(this).load()
        val playbackUrl = urlOverride ?: config.url
        currentPlaybackUrl = playbackUrl

        // Cancel ALL pending callbacks (duplicate retries, stale timeouts).
        // Safe here because play() is the authoritative "start fresh" entry point.
        // Null the references since removeCallbacksAndMessages removed them from the queue.
        handler.removeCallbacksAndMessages(null)
        bufferingTimeoutRunnable  = null
        restartingTimeoutRunnable = null
        focusLossRetryRunnable    = null

        // Apply saved volume and force true full output even on a capped headset/Bluetooth route.
        applyVolume()

        val mediaItem = MediaItem.Builder()
            .setUri(playbackUrl)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1f)
                    .setMinPlaybackSpeed(1f)
                    .build()
            )
            .build()

        // Set BEFORE stop() so the immediate STATE_IDLE from stop() is suppressed.
        isRestarting = true
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        // ── Safety net #1: isRestarting stuck ───────────────────────────────────
        // If prepare() hangs and STATE_BUFFERING never fires (e.g. DNS failure
        // before the socket is established), isRestarting would stay true forever
        // and all subsequent ACTION_PLAY intents would be silently skipped.
        // After RESTARTING_TIMEOUT_MS: reset the flag and schedule a fresh retry.
        restartingTimeoutRunnable = Runnable {
            if (isRestarting && !isIntentionallyStopped) {
                isRestarting = false
                scheduleRetry()
            }
        }.also { handler.postDelayed(it, RESTARTING_TIMEOUT_MS) }

        registerNoisyReceiver()
        updateNotification(isPlaying = false)
        broadcastState(isPlaying = false, isConnecting = true)
    }

    private fun stopPlayback() {
        cancelAllTimeouts()
        handler.removeCallbacksAndMessages(null)
        isRestarting = false
        player.stop()
        unregisterNoisyReceiver()
        updateNotification(isPlaying = false)
        broadcastState(isPlaying = false)
    }

    // ─────────────────────────────────────────────
    // Player Listener
    // ─────────────────────────────────────────────

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_IDLE -> {
                    // Fired by player.stop() inside play() — handled by restartingTimeout.
                    // Real errors arrive via onPlayerError.
                }

                Player.STATE_BUFFERING -> {
                    // stop→prepare is complete — isRestarting no longer needed.
                    isRestarting = false
                    cancelRestartingTimeout()
                    updateNotification(isPlaying = false)
                    broadcastState(isPlaying = false, isConnecting = true)

                    // ── Safety net #2: buffering stall ──────────────────────────────────
                    // If the network is present but data stops flowing (carrier throttle,
                    // server hiccup), ExoPlayer will sit in STATE_BUFFERING indefinitely
                    // without ever erroring. Force a reconnect after BUFFERING_TIMEOUT_MS.
                    cancelBufferingTimeout()
                    bufferingTimeoutRunnable = Runnable {
                        if (player.playbackState == Player.STATE_BUFFERING &&
                            !isIntentionallyStopped) {
                            scheduleRetry()
                        }
                    }.also { handler.postDelayed(it, BUFFERING_TIMEOUT_MS) }
                }

                Player.STATE_READY -> {
                    // Audio is buffered; isPlaying transition handled by onIsPlayingChanged.
                    cancelBufferingTimeout()
                    cancelRestartingTimeout()
                }

                Player.STATE_ENDED -> {
                    // Live streams shouldn't end, but recover if they do.
                    isRestarting = false
                    cancelAllTimeouts()
                    if (!isIntentionallyStopped) scheduleRetry()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            isRestarting = false
            cancelAllTimeouts()
            if (!isIntentionallyStopped) {
                val fallbackUrl = currentPlaybackUrl?.let {
                    StreamConfig.sslFallbackUrlForPlayback(it, Build.VERSION.SDK_INT)
                }
                if (!usedSslFallbackForCurrentConfig && fallbackUrl != null && hasSslTrustFailure(error)) {
                    usedSslFallbackForCurrentConfig = true
                    play(fallbackUrl)
                    return
                }
                updateNotification(isPlaying = false)
                broadcastState(isPlaying = false)
                scheduleRetry()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateNotification(isPlaying = isPlaying)
            broadcastState(isPlaying = isPlaying)

            if (isPlaying) {
                retryCount = 0
                // ── FIX: only cancel the focus-loss retry, NOT all callbacks ────────
                // Cancelling all callbacks here would wipe out any pending error retry
                // posted by scheduleRetry() — causing the "stuck connecting" hang.
                // The error retry is self-guarding: when it fires, alreadyActive is
                // re-evaluated; if we're already playing it will be skipped.
                cancelFocusLossRetry()
            } else if (!isIntentionallyStopped &&
                player.playbackState == Player.STATE_READY) {
                // STATE_READY + not playing: stream buffered but paused.
                // With handleAudioFocus=false this is rare, but schedule a safety reconnect.
                // ── FIX: don't call removeCallbacksAndMessages(null) here ────────────
                // That would cancel a legitimate error retry from scheduleRetry().
                // Instead, surgically cancel only the previous focusLossRetry.
                cancelFocusLossRetry()
                focusLossRetryRunnable = Runnable {
                    if (!isIntentionallyStopped && !player.isPlaying) play()
                }.also { handler.postDelayed(it, 10_000L) }
            }
        }
    }

    private fun hasSslTrustFailure(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SSLException || cause is CertPathValidatorException || cause is CertificateException) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    // ─────────────────────────────────────────────
    // Retry Logic — exponential backoff, capped at 60 s
    // ─────────────────────────────────────────────

    private fun scheduleRetry() {
        if (config.maxRetries != -1 && retryCount >= config.maxRetries) {
            stopSelf()
            return
        }
        retryCount++
        val baseMs = config.reconnectDelaySeconds * 1_000L
        val delay  = (baseMs * (1L shl minOf(retryCount - 1, 5))).coerceAtMost(60_000L)
        handler.postDelayed({ play() }, delay)
    }

    // ─────────────────────────────────────────────
    // Timeout Helpers — surgical Runnable cancellation (API 22+)
    // ─────────────────────────────────────────────

    private fun cancelBufferingTimeout() {
        bufferingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        bufferingTimeoutRunnable = null
    }

    private fun cancelRestartingTimeout() {
        restartingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        restartingTimeoutRunnable = null
    }

    private fun cancelFocusLossRetry() {
        focusLossRetryRunnable?.let { handler.removeCallbacks(it) }
        focusLossRetryRunnable = null
    }

    private fun cancelAllTimeouts() {
        cancelBufferingTimeout()
        cancelRestartingTimeout()
        cancelFocusLossRetry()
    }

    // ─────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────

    private fun updateNotification(isPlaying: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notificationHelper.build(config.name, isPlaying))
    }

    // ─────────────────────────────────────────────
    // Broadcast state to UI
    // ─────────────────────────────────────────────

    private fun broadcastState(isPlaying: Boolean, isConnecting: Boolean = false) {
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING,    isPlaying)
            putExtra(EXTRA_IS_CONNECTING, isConnecting)
            putExtra(EXTRA_STREAM_NAME,   config.name)
            `package` = packageName
        }
        sendBroadcast(intent)
    }

    // ─────────────────────────────────────────────
    // Receiver Registration
    // ─────────────────────────────────────────────

    private fun registerNetworkReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkReceiver = NetworkReceiver()
            @Suppress("DEPRECATION")
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            registerReceiver(networkReceiver, filter)
        }
    }

    private fun unregisterNetworkReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && ::networkReceiver.isInitialized) {
            try { unregisterReceiver(networkReceiver) } catch (_: Exception) {}
        }
    }

    private fun registerNoisyReceiver() {
        if (!noisyRegistered) {
            noisyReceiver = BecomingNoisyReceiver()
            registerReceiver(
                noisyReceiver,
                IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            noisyRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyRegistered && ::noisyReceiver.isInitialized) {
            try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
            noisyRegistered = false
        }
    }
}
