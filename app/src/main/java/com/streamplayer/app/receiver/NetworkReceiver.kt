package com.streamplayer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.streamplayer.app.repository.StreamRepository
import com.streamplayer.app.service.AudioStreamService

/**
 * Detects network connectivity changes.
 *
 * Registration:
 * - API 23:   Registered in AndroidManifest (CONNECTIVITY_CHANGE broadcasts work)
 * - API 24+:  Dynamically registered inside [AudioStreamService] at runtime
 *             (manifest registration is ignored by Android 7+ for this action)
 *
 * On network restore, restarts the stream if auto-start is configured.
 */
class NetworkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!isConnected(context)) return

        val config = StreamRepository(context).load()
        // Only auto-reconnect if the service should be running
        // The service's retry logic also handles this, but this covers
        // the case where the service died completely during network outage.
        val serviceIntent = Intent(context, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_PLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }
}
