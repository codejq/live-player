package com.streamplayer.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamConfigTest {

    @Test
    fun normalizeStreamUrl_keepsRadiojarPermanentHttpsUrlUnchanged() {
        assertEquals(
            "https://stream.radiojar.com/8s5u5tpdtwzuv",
            StreamConfig.normalizeStreamUrl("https://stream.radiojar.com/8s5u5tpdtwzuv")
        )
    }

    @Test
    fun normalizeStreamUrl_trimsWhitespace() {
        assertEquals(
            "https://stream.radiojar.com/8s5u5tpdtwzuv",
            StreamConfig.normalizeStreamUrl("  https://stream.radiojar.com/8s5u5tpdtwzuv  ")
        )
    }

    @Test
    fun normalizeStreamUrl_keepsCustomRadiojarNodeHttpsUrlsUnchanged() {
        assertEquals(
            "https://n10.radiojar.com/8s5u5tpdtwzuv?rj-ttl=5&rj-tok=AAABnuEaV10Ar8W0rZ9z9jMQLA",
            StreamConfig.normalizeStreamUrl("https://n10.radiojar.com/8s5u5tpdtwzuv?rj-ttl=5&rj-tok=AAABnuEaV10Ar8W0rZ9z9jMQLA")
        )
    }

    @Test
    fun normalizeStreamUrl_keepsOtherUrlsUnchangedExceptTrim() {
        assertEquals(
            "https://example.com/live.mp3",
            StreamConfig.normalizeStreamUrl(" https://example.com/live.mp3 ")
        )
    }

    @Test
    fun sslFallbackUrlForPlayback_convertsAllowlistedRadiojarHttpsBelowAndroid11() {
        assertEquals(
            "http://n10.radiojar.com/8s5u5tpdtwzuv?rj-ttl=5&rj-tok=abc",
            StreamConfig.sslFallbackUrlForPlayback(
                "https://n10.radiojar.com/8s5u5tpdtwzuv?rj-ttl=5&rj-tok=abc",
                22
            )
        )
    }

    @Test
    fun sslFallbackUrlForPlayback_doesNotConvertUnknownDomainsBelowAndroid11() {
        assertEquals(
            null,
            StreamConfig.sslFallbackUrlForPlayback("https://example.com/live.mp3", 22)
        )
    }

    @Test
    fun sslFallbackUrlForPlayback_doesNotConvertAllowlistedDomainsOnAndroid11OrNewer() {
        assertEquals(
            null,
            StreamConfig.sslFallbackUrlForPlayback("https://n10.radiojar.com/live.mp3", 30)
        )
    }
}
