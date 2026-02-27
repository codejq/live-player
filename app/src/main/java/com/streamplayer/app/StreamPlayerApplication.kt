package com.streamplayer.app

import android.app.Application
import com.streamplayer.app.receiver.RestartReceiver
import com.streamplayer.app.worker.WatchdogWorker

/**
 * Application class — schedules watchdogs on every app start.
 */
class StreamPlayerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        WatchdogWorker.schedule(this)
        // AlarmManager watchdog: checks every 2 minutes and restarts the service if dead.
        // More reliable than WorkManager for Samsung's aggressive battery killer.
        RestartReceiver.schedule(this)
    }
}
