package com.streamplayer.app.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
import com.streamplayer.app.repository.StreamRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Core foreground service that manages ExoPlayer lifecycle, retry logic,
 * and persistent notification for background audio streaming.
 *
 * Returns START_STICKY so the OS restarts it automatically if killed.
 * onTaskRemoved schedules an AlarmManager restart for when the user
 * swipes the app out of recents.
 */
class AudioStreamService : LifecycleService() {

    companion object {
        const val ACTION_PLAY = "com.streamplayer.PLAY"
        const val ACTION_STOP = "com.streamplayer.STOP"
        const val ACTION_TOGGLE = "com.streamplayer.TOGGLE"
        const val NOTIFICATION_ID = 1001

        // Broadcast action to update UI
        const val ACTION_STATE_CHANGED = "com.streamplayer.STATE_CHANGED"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_IS_CONNECTING = "is_connecting"
        const val EXTRA_STREAM_NAME = "stream_name"
    }

    private lateinit var player: ExoPlayer
    private lateinit var config: StreamConfig
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var networkReceiver: NetworkReceiver
    private lateinit var noisyReceiver: BecomingNoisyReceiver

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var isIntentionallyStopped = false
    private var noisyRegistered = false

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
        when (intent?.action) {
            ACTION_PLAY -> {
                isIntentionallyStopped = false
                retryCount = 0
                play()
            }
            ACTION_STOP -> {
                isIntentionallyStopped = true
                stopPlayback()
                stopSelf()
            }
            ACTION_TOGGLE -> {
                if (player.isPlaying) {
                    isIntentionallyStopped = true
                    stopPlayback()
                } else {
                    isIntentionallyStopped = false
                    retryCount = 0
                    play()
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Schedule restart 1 second after user swipes app from recents
        if (!isIntentionallyStopped) {
            val restart = Intent(this, AudioStreamService::class.java).apply {
                action = ACTION_PLAY
            }
            val pi = PendingIntent.getService(
                this, 1, restart,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1_000L, pi)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkReceiver()
        unregisterNoisyReceiver()
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
            .readTimeout(0, TimeUnit.SECONDS)   // 0 = infinite — live streams never "finish" sending
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("StreamPlayer/1.0 (Android)")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000,  // min buffer — keep 20s ahead before rebuffering
                120_000, // max buffer — fill up to 2 min when possible
                8_000,   // buffer needed before starting playback (8s)
                8_000    // buffer needed after a rebuffer event (8s)
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))  // actually use OkHttp!
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
                    true  // handleAudioFocus = true (auto duck/pause/resume)
                )
            }
    }

    // ─────────────────────────────────────────────
    // Playback Control
    // ─────────────────────────────────────────────

    private fun play() {
        config = StreamRepository(this).load()
        handler.removeCallbacksAndMessages(null)

        val mediaItem = MediaItem.fromUri(config.url)
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        registerNoisyReceiver()
        updateNotification(isPlaying = false)
        broadcastState(isPlaying = false, isConnecting = true)
    }

    private fun stopPlayback() {
        handler.removeCallbacksAndMessages(null)
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
                Player.STATE_IDLE, Player.STATE_ENDED -> {
                    if (!isIntentionallyStopped) scheduleRetry()
                }
                Player.STATE_BUFFERING -> {
                    updateNotification(isPlaying = false)
                    broadcastState(isPlaying = false, isConnecting = true)
                }
                Player.STATE_READY -> { /* handled by onIsPlayingChanged */ }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!isIntentionallyStopped) {
                updateNotification(isPlaying = false)
                broadcastState(isPlaying = false)
                scheduleRetry()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateNotification(isPlaying = isPlaying)
            broadcastState(isPlaying = isPlaying)
            if (isPlaying) retryCount = 0
        }
    }

    // ─────────────────────────────────────────────
    // Retry Logic (exponential backoff, capped at 60s)
    // ─────────────────────────────────────────────

    private fun scheduleRetry() {
        if (config.maxRetries != -1 && retryCount >= config.maxRetries) {
            stopSelf()
            return
        }
        retryCount++
        // Exponential: delay * 2^(retryCount-1), capped at 60s
        val baseMs = config.reconnectDelaySeconds * 1_000L
        val delay = (baseMs * (1L shl minOf(retryCount - 1, 5))).coerceAtMost(60_000L)
        handler.postDelayed({ play() }, delay)
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
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            putExtra(EXTRA_IS_CONNECTING, isConnecting)
            putExtra(EXTRA_STREAM_NAME, config.name)
            `package` = packageName
        }
        sendBroadcast(intent)
    }

    // ─────────────────────────────────────────────
    // Receiver Registration
    // ─────────────────────────────────────────────

    private fun registerNetworkReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // For API 24+, dynamic registration (manifest registration is no-op on 24+)
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
            registerReceiver(noisyReceiver, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
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
