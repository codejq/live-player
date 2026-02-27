package com.streamplayer.app.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.streamplayer.app.service.AudioStreamService

/**
 * AlarmManager-driven watchdog that fires every [INTERVAL_MS] (2 minutes).
 *
 * Each firing:
 *  1. Sends ACTION_PLAY to AudioStreamService via startForegroundService()
 *     (using getBroadcast so Android allows starting a foreground service
 *     from background — getService/getActivity would be blocked on API 26+).
 *  2. Reschedules itself for the next interval (self-healing chain).
 *
 * AudioStreamService's alreadyActive guard ensures a running stream is never
 * interrupted by these periodic pings.
 *
 * Scheduled from:
 *  - StreamPlayerApplication.onCreate()  — on every process start
 *  - BootReceiver.onReceive()            — after device reboot
 *  - onReceive() itself                  — keeps the chain alive indefinitely
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Kick the service (no-op if already playing, restarts if dead)
        val serviceIntent = Intent(context, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_PLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // Keep the chain alive
        schedule(context)
    }

    companion object {
        const val ACTION = "com.streamplayer.ALARM_RESTART"

        private const val INTERVAL_MS = 2 * 60 * 1_000L   // 2 minutes
        private const val REQUEST_CODE = 2001

        /**
         * Schedule (or reschedule) the repeating alarm.
         * Safe to call multiple times — FLAG_UPDATE_CURRENT replaces any existing alarm.
         */
        fun schedule(context: Context) {
            val pi = buildPendingIntent(context)
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            setAlarm(alarm, triggerAt, pi)
        }

        /**
         * Cancel the alarm (useful if we ever want to fully disable the watchdog).
         */
        fun cancel(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.cancel(buildPendingIntent(context))
        }

        /**
         * One-shot alarm — used by AudioStreamService.onTaskRemoved() for a fast
         * 1-second restart after the user swipes the app from recents.
         */
        fun scheduleOneShot(context: Context, delayMs: Long) {
            // Use a different request code so it doesn't clobber the repeating alarm
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE + 1,
                Intent(context, RestartReceiver::class.java).apply { action = ACTION },
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            setAlarm(alarm, triggerAt, pi)
        }

        // ─────────────────────────────────────────────
        // Internals
        // ─────────────────────────────────────────────

        private fun buildPendingIntent(context: Context) = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RestartReceiver::class.java).apply { action = ACTION },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        /** FLAG_IMMUTABLE exists only on API 23+. Guard so API 22 never resolves it. */
        @Suppress("InlinedApi")
        private fun immutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        private fun setAlarm(alarm: AlarmManager, triggerAt: Long, pi: PendingIntent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    // Fires exactly even in Doze; requires SCHEDULE_EXACT_ALARM on API 31+
                    // or USE_EXACT_ALARM on API 33+. Falls back if permission not granted.
                    alarm.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                    )
                } catch (_: SecurityException) {
                    // Permission not granted — use the doze-aware inexact variant.
                    // May fire several minutes late but still fires during deep sleep.
                    alarm.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                    )
                }
            } else {
                alarm.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }
    }
}
