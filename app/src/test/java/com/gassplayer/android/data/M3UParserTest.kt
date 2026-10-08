package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3UParserTest {
    @Test
    fun parsesIptvHeadersRelativeUrlsAndQueryParameters() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="demo" tvg-logo="https://logo.example/a.png" group-title="News",News, One
            #EXTVLCOPT:http-referrer=https://ref.example/
            #EXTVLCOPT:http-user-agent=Test UA/1.0
            channel/one.m3u8|Origin="https://origin.example"&Referer="https://ref.example/x?a=1&b=2"|Cookie="sid=abc%20123"
            #EXTINF:-1 tvg-name="Relative",Relative
            //cdn.example/live/stream.m3u8?token=a%2Fb%3Dc
        """.trimIndent()

        val parsed = M3UParser.parse("source", text, "https://playlist.example/root/list.m3u")

        assertEquals(2, parsed.size)
        assertEquals("https://playlist.example/root/channel/one.m3u8", parsed[0].streamUrl)
        assertEquals("News, One", parsed[0].title)
        assertEquals("https://ref.example/x?a=1&b=2", parsed[0].streamHeaders["Referer"])
        assertEquals("Test UA/1.0", parsed[0].streamHeaders["User-Agent"])
        assertEquals("https://origin.example", parsed[0].streamHeaders["Origin"])
        assertEquals("sid=abc 123", parsed[0].streamHeaders["Cookie"])
        assertEquals("https://cdn.example/live/stream.m3u8?token=a%2Fb%3Dc", parsed[1].streamUrl)
    }

    @Test
    fun parsesDirectlyFromInputStreamWithoutChangingResults() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="demo" group-title="News",Demo
            https://cdn.example/live/demo.m3u8|User-Agent=StreamUA/1.0&Referer=https%3A%2F%2Fref.example%2F
        """.trimIndent()

        val parsed = M3UParser.parse(
            "source",
            text.byteInputStream(),
            "https://playlist.example/list.m3u"
        )

        assertEquals(1, parsed.size)
        assertEquals("https://cdn.example/live/demo.m3u8", parsed.single().streamUrl)
        assertEquals("StreamUA/1.0", parsed.single().streamHeaders["User-Agent"])
        assertEquals("https://ref.example/", parsed.single().streamHeaders["Referer"])
    }

    @Test
    fun treatsHlsManifestAsOnePlayableItem() {
        val hls = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            seg001.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = M3UParser.parse("hls", hls, "https://cdn.example/live/index.m3u8")

        assertEquals(1, parsed.size)
        assertEquals("https://cdn.example/live/index.m3u8", parsed[0].streamUrl)
        assertEquals("application/vnd.apple.mpegurl", parsed[0].streamMimeType)
        assertTrue(parsed[0].kind == MediaKind.LIVE)
    }


    @Test
    fun appliesSourceHeadersToHlsManifestAndEntries() {
        val hls = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            seg001.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = M3UParser.parse(
            "hls",
            hls,
            "https://cdn.example/live/index.m3u8",
            defaultHeaders = mapOf("User-Agent" to "TestPlayer/1.0", "Referer" to "https://origin.example/")
        )

        assertEquals("TestPlayer/1.0", parsed.single().streamHeaders["User-Agent"])
        assertEquals("https://origin.example/", parsed.single().streamHeaders["Referer"])
    }

    @Test
    fun supportsKodiHeaderDirectives() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Demo
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=KodiUA/1.0&Referer=https%3A%2F%2Fref.example%2F&Origin=https%3A%2F%2Forigin.example
            https://example.com/live.m3u8
        """.trimIndent()

        val item = M3UParser.parse("source", text).single()

        assertEquals("KodiUA/1.0", item.streamHeaders["User-Agent"])
        assertEquals("https://ref.example/", item.streamHeaders["Referer"])
        assertEquals("https://origin.example", item.streamHeaders["Origin"])
    }
    @Test
    fun turnsSeasonEpisodeEntriesIntoSyntheticSeriesAndEpisodes() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="Serie TV",Example Show S01E01 - Pilot
            https://cdn.example/series/1.mp4
            #EXTINF:-1 group-title="Serie TV",Example Show S01E02 - Next
            https://cdn.example/series/2.mp4
        """.trimIndent()

        val parsed = M3UParser.parse("source", text)
        val series = parsed.filter { it.kind == MediaKind.SERIES }
        val episodes = parsed.filter { it.kind == MediaKind.EPISODE }

        assertEquals(1, series.size)
        assertEquals(2, episodes.size)
        assertTrue(episodes.all { it.seriesId == series.single().id.substringAfterLast(':') })
        assertEquals(1, episodes.first().seasonNumber)
        assertEquals(1, episodes.first().episodeNumber)
    }

    @Test
    fun attachesEpisodesToExplicitSeriesEntry() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-type="series",Example Show
            https://cdn.example/series/index.m3u8
            #EXTINF:-1 group-title="Example Show",Example Show S02E03
            https://cdn.example/series/203.mp4
        """.trimIndent()

        val parsed = M3UParser.parse("source", text)
        val series = parsed.first { it.kind == MediaKind.SERIES }
        val episode = parsed.first { it.kind == MediaKind.EPISODE }
        assertEquals(series.id.substringAfterLast(':'), episode.seriesId)
        assertEquals(2, episode.seasonNumber)
        assertEquals(3, episode.episodeNumber)
    }

    @Test
    fun supportsExtHttpJsonHeaders() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Demo
            #EXTHTTP:{"User-Agent":"JsonUA/1.0","Referer":"https://ref.example/"}
            https://example.com/live.m3u8
        """.trimIndent()

        val item = M3UParser.parse("source", text).single()

        assertEquals("JsonUA/1.0", item.streamHeaders["User-Agent"])
        assertEquals("https://ref.example/", item.streamHeaders["Referer"])
    }

    @Test
    fun recognizesCommonSeasonEpisodeNotationsAndExplicitEpisodeType() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="Serie",Example Show 1x02
            https://cdn.example/series/102.mp4
            #EXTINF:-1 tvg-type="episode" group-title="Serie",Example Show Season 2 Episode 3
            https://cdn.example/series/203.mkv
        """.trimIndent()

        val episodes = M3UParser.parse("source", text).filter { it.kind == MediaKind.EPISODE }
        assertEquals(2, episodes.size)
        assertEquals(1, episodes[0].seasonNumber)
        assertEquals(2, episodes[0].episodeNumber)
        assertEquals(2, episodes[1].seasonNumber)
        assertEquals(3, episodes[1].episodeNumber)
    }

    @Test
    fun recognizesPunctuationInSeasonEpisodeAndEpisodeOnlyWithSeriesHint() {
        val text = """
            #EXTM3U
            #EXTINF:-1 series-title="Example Show" group-title="Serie TV",S01.E02 - Episode
            https://cdn.example/series/102.mp4
            #EXTINF:-1 series-title="Example Show" group-title="Serie TV",Episode 3
            https://cdn.example/series/103.mp4
        """.trimIndent()

        val parsed = M3UParser.parse("source", text)
        val episodes = parsed.filter { it.kind == MediaKind.EPISODE }
        val series = parsed.filter { it.kind == MediaKind.SERIES }

        assertEquals(2, episodes.size)
        assertEquals(1, episodes[0].seasonNumber)
        assertEquals(2, episodes[0].episodeNumber)
        assertEquals(3, episodes[1].episodeNumber)
        assertEquals(1, series.size)
        assertEquals("Example Show", series.single().title)
        assertTrue(episodes.all { it.seriesId == series.single().id.substringAfterLast(':') })
    }

    @Test
    fun infersHlsMimeFromExtensionlessUrlHints() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Live HLS
            https://cdn.example/live/channel?id=7&format=m3u8
        """.trimIndent()

        val item = M3UParser.parse("source", text).single()
        assertEquals("application/vnd.apple.mpegurl", item.streamMimeType)
    }

}


