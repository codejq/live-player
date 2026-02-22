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
        // Use the permanent token-free URL: stream.radiojar.com/{stationId}
        // This never expires — the server returns a 302 redirect to a fresh tokenized URL automatically.
        // OkHttp follows the redirect transparently. Do NOT use the n12/n13 tokenized URLs as defaults.
        const val DEFAULT_URL = "https://stream.radiojar.com/8s5u5tpdtwzuv"
        const val DEFAULT_NAME = "Live Stream"
    }
}
