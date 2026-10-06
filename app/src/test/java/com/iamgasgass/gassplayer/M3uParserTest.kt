package com.iamgasgass.gassplayer

import com.iamgasgass.gassplayer.data.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    @Test
    fun parsesAttributesAndUrls() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="rai1.it" tvg-name="Rai 1" tvg-logo="https://logo/1.png" group-title="Italia",Rai 1
            https://stream/rai1.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, "source")

        assertEquals(1, result.size)
        assertEquals("Rai 1", result[0].name)
        assertEquals("Italia", result[0].group)
        assertEquals("rai1.it", result[0].epgId)
        assertEquals("https://stream/rai1.m3u8", result[0].streamUrl)
    }

    @Test
    fun createsStableEnoughFallbackIdsAndNumbers() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="A",Canale A
            https://stream/a.m3u8
            #EXTINF:-1 group-title="B",Canale B
            https://stream/b.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, "source")

        assertEquals(2, result.size)
        assertEquals(1, result[0].number)
        assertEquals(2, result[1].number)
        assertTrue(result[0].id.startsWith("source-"))
        assertTrue(result[1].id.startsWith("source-"))
    }

    @Test
    fun detectsCatchupAttributes() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="one" catchup="append" catchup-days="7",Channel
            https://stream/one.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist)

        assertEquals(1, result.size)
        assertTrue(result[0].catchup)
    }
}
