package com.streamplayer.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.streamplayer.app.MainActivity
import com.streamplayer.app.R
import com.streamplayer.app.service.AudioStreamService

/**
 * Creates and manages the notification channel and persistent playback notification.
 * Uses NotificationCompat for API 23+ backward compatibility.
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stream Player",
                NotificationManager.IMPORTANCE_LOW   // silent, no sound/vibration
            ).apply {
                description = "Background audio stream playback"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun build(streamName: String, isPlaying: Boolean): Notification {
        // Tap notification → open MainActivity
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        // Stop action button
        val stopIntent = PendingIntent.getService(
            context, 0,
            Intent(context, AudioStreamService::class.java).apply {
                action = AudioStreamService.ACTION_STOP
            },
            immutableFlag()
        )

        // Play action button (shown when paused/connecting)
        val playIntent = PendingIntent.getService(
            context, 1,
            Intent(context, AudioStreamService::class.java).apply {
                action = AudioStreamService.ACTION_PLAY
            },
            immutableFlag()
        )

        val statusText = when {
            isPlaying -> context.getString(R.string.status_playing)
            else -> context.getString(R.string.status_connecting)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(streamName)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0)
            )

        if (isPlaying) {
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_stop,
                    context.getString(R.string.action_stop),
                    stopIntent
                )
            )
        } else {
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_play,
                    context.getString(R.string.action_play),
                    playIntent
                )
            )
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_stop,
                    context.getString(R.string.action_stop),
                    stopIntent
                )
            )
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "stream_player_channel"

        /**
         * Returns PendingIntent.FLAG_IMMUTABLE on API 23+ (where it exists),
         * or 0 on API 22 (Android 5.x). ART resolves field accesses lazily
         * per instruction, so the FLAG_IMMUTABLE branch is never reached on API 22.
         */
        @Suppress("InlinedApi")
        fun immutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}
