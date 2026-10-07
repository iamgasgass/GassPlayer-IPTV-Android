package com.gassplayer.android.data

import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlCandidatesTest {
    @Test
    fun keepsTransportCandidatesAheadOfContainerVariants() {
        val original = "https://host.example:25461/series/u/p/123.mp4?token=a%2Fb%3Dc"
        val candidates = StreamUrlCandidates.ordered(original)
        assertTrue(candidates.first() == original)
        assertTrue(candidates.take(8).any { it == "https://host.example:25463/series/u/p/123.mp4?token=a%2Fb%3Dc" })
        assertTrue(candidates.any { it.endsWith(".m3u8?token=a%2Fb%3Dc") })
    }

    @Test
    fun addsHlsAndTsAlternativesForExtensionlessPlaylistStreams() {
        val candidates = StreamUrlCandidates.ordered("https://cdn.example/live/channel?id=7")
        assertTrue(candidates.any { it.contains("/live/channel.m3u8?id=7") })
        assertTrue(candidates.any { it.contains("/live/channel.ts?id=7") })
    }
}
