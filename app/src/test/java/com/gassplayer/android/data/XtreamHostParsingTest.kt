package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamHostParsingTest {
    @Test fun stripsPlayerApiAndGetPhp() {
        assertEquals("http://h.example:8080", XtreamRepository.serverBase("http://h.example:8080/player_api.php?username=a&password=b"))
        assertEquals("http://h.example:8080", XtreamRepository.serverBase("h.example:8080/get.php?username=a&password=b&type=m3u_plus"))
        assertEquals("https://h.example/panel", XtreamRepository.serverBase("https://h.example/panel/"))
    }

    @Test fun extractsCredentialsFromPastedPlaylistUrl() {
        val c = XtreamRepository.credentialsFromUrl("http://h.example:8080/get.php?username=user%401&password=p%26w&type=m3u_plus")
        assertEquals("user@1" to "p&w", c)
        assertNull(XtreamRepository.credentialsFromUrl("http://h.example:8080"))
    }

    @Test fun parsesXmltvTimestamps() {
        val utc = XmlTvParser.xmlTvTime("20260101120000 +0000")
        assertEquals(1767268800000L, utc)
        assertEquals(utc, XmlTvParser.xmlTvTime("20260101130000 +0100"))
    }

    @Test fun gunzipLeavesPlainBytesUntouched() {
        val bytes = "#EXTM3U".toByteArray()
        assertEquals("#EXTM3U", NetworkApi.decodeText(NetworkApi.gunzipIfNeeded(bytes)))
    }
}
