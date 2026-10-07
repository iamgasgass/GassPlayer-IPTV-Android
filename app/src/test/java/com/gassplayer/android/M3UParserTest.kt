package com.gassplayer.android

import com.gassplayer.android.data.M3UParser
import com.gassplayer.android.data.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3UParserTest {
    @Test fun parsesLiveAndSeriesMetadata() {
        val data = """
            #EXTM3U\n
            #EXTINF:-1 tvg-id="one" tvg-logo="https://img/logo.png" group-title="News",Channel One\n
            http://example.com/live.ts\n
            #EXTINF:-1 tvg-type="series" group-title="Drama",Show S02E03\n
            http://example.com/episode.mp4\n
        """.trimIndent()
        val result = M3UParser.parse("source", data)
        assertEquals(2, result.size)
        assertEquals(MediaKind.LIVE, result[0].kind)
        assertEquals(MediaKind.SERIES, result[1].kind)
        assertEquals(2, result[1].seasonNumber)
        assertEquals(3, result[1].episodeNumber)
        assertTrue(result[0].logoUrl!!.contains("logo.png"))
    }
}
