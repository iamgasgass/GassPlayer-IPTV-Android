package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkApiTest {
    @Test
    fun preservesSignedQueryWhenSwitchingHttpsToHttp() {
        val original = "https://host.example/live/index.m3u8?token=a%2Fb%3Dc&expires=123"
        val candidates = NetworkApi.candidateUrls(original)

        assertEquals(original, candidates.first())
        assertTrue(candidates.contains("http://host.example/live/index.m3u8?token=a%2Fb%3Dc&expires=123"))
        assertTrue(candidates.contains("http://host.example:443/live/index.m3u8?token=a%2Fb%3Dc&expires=123"))
    }

    @Test
    fun correctsExplicitTlsAndCleartextPorts() {
        val candidates = NetworkApi.candidateUrls("http://host.example:443/live")
        assertTrue(candidates.contains("https://host.example:443/live"))
    }
    @Test
    fun includesCommonXtreamTlsPortPair() {
        val candidates = NetworkApi.candidateUrls("https://host.example:25461/player_api.php?x=1")
        assertTrue(candidates.contains("https://host.example:25463/player_api.php?x=1"))
        assertTrue(candidates.contains("http://host.example:25463/player_api.php?x=1"))
    }

    @Test
    fun stripsInlineHeadersFromPlaylistSubscriptionUrl() {
        val raw = "https://playlist.example/list.m3u8|User-Agent=TestUA/1.0&Referer=https%3A%2F%2Fref.example%2F"
        assertEquals("https://playlist.example/list.m3u8", NetworkApi.stripInlineHeaders(raw))
    }

    @Test
    fun includesStandardPortsAsLastResort() {
        val candidates = NetworkApi.candidateUrls("http://host.example:8000/live/1.ts")
        assertTrue(candidates.contains("https://host.example:443/live/1.ts"))
        assertTrue(candidates.contains("http://host.example:80/live/1.ts"))
    }

}
