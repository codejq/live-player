package com.streamplayer.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Convenience helper to start/stop [AudioStreamService].
 * No binding needed — commands sent via Intent actions.
 */
object AudioServiceConnection {

    fun startPlay(context: Context) {
        sendAction(context, AudioStreamService.ACTION_PLAY)
    }

    fun startStop(context: Context) {
        sendAction(context, AudioStreamService.ACTION_STOP)
    }

    fun toggle(context: Context) {
        sendAction(context, AudioStreamService.ACTION_TOGGLE)
    }

    private fun sendAction(context: Context, action: String) {
        val intent = Intent(context, AudioStreamService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
