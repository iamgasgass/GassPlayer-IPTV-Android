package com.gassplayer.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcRoutingTest {
    @Test
    fun protocolsThatExoPlayerCannotOpenGoToVlc() {
        assertTrue(VlcRouting.prefersVlc("rtmp://live.example.com/app/stream"))
        assertTrue(VlcRouting.prefersVlc("mms://media.example.com/tv"))
        assertTrue(VlcRouting.prefersVlc("udp://@239.0.0.1:1234"))
        assertTrue(VlcRouting.prefersVlc("rtp://239.1.1.1:5000"))
        assertTrue(VlcRouting.prefersVlc("srt://host:9000"))
        assertTrue(VlcRouting.prefersVlc("RTSP://camera.local/stream1"))
    }

    @Test
    fun containersThatExoPlayerCannotReadGoToVlc() {
        assertTrue(VlcRouting.prefersVlc("http://host:8080/movie/u/p/123.avi"))
        assertTrue(VlcRouting.prefersVlc("http://host/series/u/p/9.WMV?token=1"))
        assertTrue(VlcRouting.prefersVlc("https://cdn.example.com/a/b/film.rmvb|User-Agent=VLC"))
        assertTrue(VlcRouting.prefersVlc("http://host/stream", "video/x-msvideo"))
    }

    @Test
    fun commonFormatsStayOnExoPlayer() {
        assertFalse(VlcRouting.prefersVlc("http://host:8080/live/u/p/1.ts"))
        assertFalse(VlcRouting.prefersVlc("http://host:8080/live/u/p/1.m3u8"))
        assertFalse(VlcRouting.prefersVlc("http://host/movie/u/p/2.mkv"))
        assertFalse(VlcRouting.prefersVlc("https://cdn.example.com/master.mpd"))
        assertFalse(VlcRouting.prefersVlc("http://host/play?id=avi"))
        assertFalse(VlcRouting.prefersVlc(""))
    }

    @Test
    fun exoFallbackOnlyForSchemesItSupports() {
        assertTrue(VlcRouting.exoCanOpen("http://host/x.avi"))
        assertTrue(VlcRouting.exoCanOpen("rtsp://camera/stream"))
        assertTrue(VlcRouting.exoCanOpen("udp://@239.0.0.1:1234"))
        assertFalse(VlcRouting.exoCanOpen("rtmp://host/app"))
        assertFalse(VlcRouting.exoCanOpen("mms://host/tv"))
    }

    @Test
    fun extensionIgnoresQueryAndKodiHeaders() {
        assertEquals("avi", VlcRouting.extensionOf("http://h/a/b.avi?x=1#f"))
        assertEquals("mp4", VlcRouting.extensionOf("http://h/a/b.mp4|Referer=http://x"))
        assertEquals("", VlcRouting.extensionOf("http://h/a/b"))
        assertEquals("", VlcRouting.extensionOf("http://h.example.com"))
    }
}
