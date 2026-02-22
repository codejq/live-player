package com.streamplayer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.streamplayer.app.service.AudioStreamService

/**
 * Pauses playback when headphones are unplugged or Bluetooth audio device disconnects.
 * This follows Android audio best-practices: apps should pause when audio output changes
 * unexpectedly to avoid blasting audio through the speaker.
 *
 * Dynamically registered in [AudioStreamService] when playback starts,
 * unregistered when playback stops.
 */
class BecomingNoisyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return

        // Send STOP — user can tap Play in notification to resume
        val stopIntent = Intent(context, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_STOP
        }
        context.startService(stopIntent)
    }
}
