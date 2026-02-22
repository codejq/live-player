package com.streamplayer.app.model

/**
 * Immutable data class representing all user-configurable stream settings.
 * Stored/loaded via [com.streamplayer.app.repository.StreamRepository].
 */
data class StreamConfig(
    val url: String = DEFAULT_URL,
    val name: String = DEFAULT_NAME,
    val autoStartOnBoot: Boolean = true,
    val reconnectDelaySeconds: Int = 5,
    val maxRetries: Int = -1   // -1 = infinite retries
) {
    companion object {
        const val DEFAULT_URL = "https://n12.radiojar.com/8s5u5tpdtwzuv?rj-ttl=5&rj-tok=AAABnIUADSoAgCb8D4alPlAfig"
        const val DEFAULT_NAME = "Live Stream"
    }
}
