package com.gassplayer.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDiagnosticsTest {
    private fun probe(url: String = "http://h/movie/u/p/1.mp4", status: Int? = 200, failure: NetFailure? = null, errorPage: Boolean = false) =
        StreamProbeResult(url, url, "ua", status, null, failure, errorPage)

    @Test fun urlHelpersFollowXtreamShape() {
        assertEquals("movie", StreamDiagnostics.xtreamKind("http://h:80/movie/user/pass/123.mkv"))
        assertEquals("live", StreamDiagnostics.xtreamKind("http://h/live/user/pass/9.ts?x=1"))
        assertNull(StreamDiagnostics.xtreamKind("http://h/some/file.mp4"))
        assertEquals("mkv", StreamDiagnostics.extensionOf("http://h/movie/u/p/123.MKV?token=a.b"))
        assertEquals("http://h/movie/u/p/123.mp4?token=a.b", StreamDiagnostics.replaceExtension("http://h/movie/u/p/123.mkv?token=a.b", "mp4"))
        assertNull(StreamDiagnostics.replaceExtension("http://h/movie/u/p/123", "mp4"))
    }

    @Test fun detectsErrorPagesServedWithHttp200() {
        assertTrue(StreamDiagnostics.looksLikeErrorPage("<!DOCTYPE html><html>".toByteArray(), null))
        assertTrue(StreamDiagnostics.looksLikeErrorPage(ByteArray(10), "application/json"))
        assertTrue(StreamDiagnostics.looksLikeErrorPage("{\"error\":\"expired\"}".toByteArray(), null))
        assertFalse(StreamDiagnostics.looksLikeErrorPage("#EXTM3U\n#EXT-X-VERSION:3".toByteArray(), "text/plain"))
        assertFalse(StreamDiagnostics.looksLikeErrorPage(ByteArray(512) { 0x47 }, "video/mp2t"))
    }

    @Test fun probeClassification() {
        assertTrue(probe(status = 206).isPlayable)
        assertFalse(probe(status = 200, errorPage = true).isPlayable)
        assertTrue(StreamDiagnostics.isTransient(probe(status = 503)))
        assertTrue(StreamDiagnostics.isTransient(probe(status = null, failure = NetFailure.TIMEOUT)))
        assertFalse(StreamDiagnostics.isTransient(probe(status = 404)))
        assertTrue(StreamDiagnostics.isConnectionLimit(probe(status = 884)))
        assertTrue(StreamDiagnostics.shouldTryOtherUserAgents(probe(status = 403)))
        assertTrue(StreamDiagnostics.shouldTryOtherUserAgents(probe(status = 200, errorPage = true)))
        assertFalse(StreamDiagnostics.shouldTryOtherUserAgents(probe(status = 404)))
        assertTrue(StreamDiagnostics.isInconclusive(probe(status = 405)))
        assertTrue(StreamDiagnostics.isInconclusive(probe(status = null, failure = NetFailure.TLS)))
        assertFalse(StreamDiagnostics.isInconclusive(probe(status = 403)))
    }

    @Test fun messagesNameTheRealCause() {
        assertTrue(StreamDiagnostics.describe(probe(status = 403), emptyList()).contains("rifiutato"))
        assertTrue(StreamDiagnostics.describe(probe(status = 884), emptyList()).contains("connessioni simultanee"))
        assertTrue(StreamDiagnostics.describe(probe(status = 503), emptyList()).contains("backend"))
        assertTrue(StreamDiagnostics.describe(probe(status = null, failure = NetFailure.TIMEOUT), emptyList()).contains("timeout"))
        assertTrue(StreamDiagnostics.describe(probe(status = 200, errorPage = true), emptyList()).contains("pagina di errore"))
        val log = listOf(probe("http://h/movie/u/p/1.mp4", 404), probe("http://h/movie/u/p/1.mkv", 404))
        assertTrue(StreamDiagnostics.describe(log[0], log).contains("MKV: HTTP 404"))
    }

    @Test fun movieCandidatesIncludeOtherContainers() {
        val c = StreamDiagnostics.candidatesFor("http://h/movie/u/p/1.mkv")
        assertTrue(c.any { it.endsWith("/1.mp4") })
        assertTrue(c.any { it.endsWith("/1.m3u8") })
    }

    @Test fun resolutionCacheStoresAndInvalidates() {
        val res = StreamResolution("http://h/movie/u/p/1.mkv", "http://h/movie/u/p/1.mp4", "ua")
        ResolutionCache.store(res, res.requestedUrl)
        assertNotNull(ResolutionCache.fresh(res.requestedUrl))
        ResolutionCache.invalidate(res.requestedUrl)
        assertNull(ResolutionCache.fresh(res.requestedUrl))
    }

    @Test fun userAgentLadderStartsWithVlc() {
        StreamUserAgents.custom = null
        assertEquals(StreamUserAgents.DEFAULT_VLC, StreamUserAgents.ladder.first())
        StreamUserAgents.custom = "MyAgent"
        assertEquals("MyAgent", StreamUserAgents.ladder.first())
        StreamUserAgents.custom = null
    }
}
