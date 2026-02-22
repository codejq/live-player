package com.streamplayer.app.worker

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamplayer.app.repository.StreamRepository
import com.streamplayer.app.service.AudioStreamService
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker that checks whether [AudioStreamService] is running
 * every 15 minutes (minimum interval enforced by Android OS).
 *
 * If the service is not running and auto-start is enabled, restarts it.
 * This is the last line of defence against Doze mode, OEM battery killers, etc.
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = StreamRepository(applicationContext).load()

        if (isServiceRunning()) return Result.success()

        // Service is not running — restart if auto-start is configured
        val intent = Intent(applicationContext, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_PLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }

        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE).any { serviceInfo ->
            serviceInfo.service.className == AudioStreamService::class.java.name
        }
    }

    companion object {
        private const val WORK_NAME = "stream_watchdog"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(
                15, TimeUnit.MINUTES    // Minimum allowed by WorkManager
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // Don't replace existing scheduled work
                request
            )
        }
    }
}
