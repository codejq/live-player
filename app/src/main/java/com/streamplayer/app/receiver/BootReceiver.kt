package com.streamplayer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.streamplayer.app.repository.StreamRepository
import com.streamplayer.app.service.AudioStreamService
import com.streamplayer.app.worker.WatchdogWorker
import com.streamplayer.app.receiver.RestartReceiver

/**
 * Starts the stream automatically on device boot if [StreamConfig.autoStartOnBoot] is enabled.
 * Also reschedules the WorkManager watchdog after reboot (WorkManager persists across reboots
 * automatically, but scheduling here ensures the watchdog is always active).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON") {
            return
        }

        val config = StreamRepository(context).load()

        // Always reschedule both watchdogs after boot
        WatchdogWorker.schedule(context)
        RestartReceiver.schedule(context)

        if (!config.autoStartOnBoot) return

        val serviceIntent = Intent(context, AudioStreamService::class.java).apply {
            this.action = AudioStreamService.ACTION_PLAY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
