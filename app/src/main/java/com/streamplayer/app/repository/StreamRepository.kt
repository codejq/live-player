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
            .apply()
    }

    fun load(): StreamConfig {
        val defaults = StreamConfig()
        return StreamConfig(
            url = prefs.getString(KEY_URL, defaults.url) ?: defaults.url,
            name = prefs.getString(KEY_NAME, defaults.name) ?: defaults.name,
            autoStartOnBoot = prefs.getBoolean(KEY_AUTO_BOOT, defaults.autoStartOnBoot),
            reconnectDelaySeconds = prefs.getInt(KEY_DELAY, defaults.reconnectDelaySeconds),
            maxRetries = prefs.getInt(KEY_RETRIES, defaults.maxRetries)
        )
    }

    companion object {
        private const val PREFS_NAME = "stream_prefs"
        private const val KEY_URL = "url"
        private const val KEY_NAME = "name"
        private const val KEY_AUTO_BOOT = "auto_boot"
        private const val KEY_DELAY = "delay"
        private const val KEY_RETRIES = "retries"
    }
}
