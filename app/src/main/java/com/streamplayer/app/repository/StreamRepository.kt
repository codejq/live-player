package com.streamplayer.app.repository

import android.content.Context
import com.streamplayer.app.model.StreamConfig

/**
 * Persists and retrieves [StreamConfig] using SharedPreferences.
 * No database required — config is small and rarely written.
 */
class StreamRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(config: StreamConfig) {
        prefs.edit()
            .putString(KEY_URL, config.url)
            .putString(KEY_NAME, config.name)
            .putBoolean(KEY_AUTO_BOOT, config.autoStartOnBoot)
            .putInt(KEY_DELAY, config.reconnectDelaySeconds)
            .putInt(KEY_RETRIES, config.maxRetries)
            .putInt(KEY_VOLUME, config.volume)
            .apply()
    }

    fun load(): StreamConfig {
        val defaults = StreamConfig()
        return StreamConfig(
            url = prefs.getString(KEY_URL, defaults.url) ?: defaults.url,
            name = prefs.getString(KEY_NAME, defaults.name) ?: defaults.name,
            autoStartOnBoot = prefs.getBoolean(KEY_AUTO_BOOT, defaults.autoStartOnBoot),
            reconnectDelaySeconds = prefs.getInt(KEY_DELAY, defaults.reconnectDelaySeconds),
            maxRetries = prefs.getInt(KEY_RETRIES, defaults.maxRetries),
            volume = prefs.getInt(KEY_VOLUME, defaults.volume)
        )
    }

    /**
     * Persisted flag: did the user explicitly start the stream?
     * Used by WatchdogWorker, NetworkReceiver, and the START_STICKY null-intent
     * handler to decide whether to auto-restart after a crash or network restore.
     * Only Play sets it true; only Stop sets it false.
     */
    fun setUserWantsPlaying(wants: Boolean) {
        // commit() is synchronous — guarantees the flag survives a process kill
        // that happens before apply()'s background write completes.
        prefs.edit().putBoolean(KEY_USER_WANTS_PLAYING, wants).commit()
    }

    fun isUserWantsPlaying(): Boolean =
        // Default true so the stream auto-starts on first launch / after reinstall,
        // matching the autoStartOnBoot=true default in StreamConfig.
        prefs.getBoolean(KEY_USER_WANTS_PLAYING, true)

    companion object {
        private const val PREFS_NAME = "stream_prefs"
        private const val KEY_URL = "url"
        private const val KEY_NAME = "name"
        private const val KEY_AUTO_BOOT = "auto_boot"
        private const val KEY_DELAY = "delay"
        private const val KEY_RETRIES = "retries"
        private const val KEY_USER_WANTS_PLAYING = "user_wants_playing"
        private const val KEY_VOLUME = "volume"
    }
}
