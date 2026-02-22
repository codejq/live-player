package com.streamplayer.app

import android.app.Application
import com.streamplayer.app.worker.WatchdogWorker

/**
 * Application class — schedules the WorkManager watchdog on every app start.
 */
class StreamPlayerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        WatchdogWorker.schedule(this)
    }
}
