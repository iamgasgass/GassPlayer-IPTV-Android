package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.StringReader

class StreamingParseTest {
    private fun playlist(n: Int) = buildString {
        append("#EXTM3U\n")
        for (i in 1..n) append("#EXTINF:-1 tvg-id=\"c$i\" tvg-logo=\"http://l/$i.png\" group-title=\"News\",Channel $i\nhttp://host/live/u/p/$i.ts\n")
    }

    @Test fun streamingMatchesStringParse() {
        val text = playlist(500)
        val a = M3UParser.parse("s", text)
        val b = M3UParser.parse("s", StringReader(text).buffered())
        assertEquals(a, b)
        assertEquals(500, b.size)
    }

    @Test fun keepFilterDropsEntriesEarly() {
        var seen = 0
        val kept = M3UParser.parse("s", StringReader(playlist(100)).buffered(), keep = { seen++; it.title.endsWith("7") })
        assertEquals(100, seen)
        assertEquals(10, kept.size)
    }

    @Test fun repeatedGroupAndHeadersAreShared() {
        val items = M3UParser.parse("s", playlist(50))
        assertSame(items[0].group, items[49].group)
        assertSame(items[0].streamHeaders, items[49].streamHeaders)
    }

    @Test fun hlsManifestStillSingleItem() {
        val hls = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n"
        val out = M3UParser.parse("h", StringReader(hls).buffered(), "https://cdn.example/live/index.m3u8")
        assertEquals(1, out.size)
    }

    @Test fun sinkReceivesLiveAndMoviesAndReturnsOnlySeriesAndEpisodes() {
        val text = buildString {
            append("#EXTM3U\n")
            for (i in 1..20) append("#EXTINF:-1 group-title=\"News\",Channel $i\nhttp://h/live/u/p/$i.ts\n")
            for (i in 1..5) append("#EXTINF:-1 group-title=\"Film\",Movie $i\nhttp://h/movie/u/p/$i.mp4\n")
            for (e in 1..3) append("#EXTINF:-1 group-title=\"Serie\",Show S01E0$e\nhttp://h/series/u/p/$e.mp4\n")
            // exact duplicate of channel 1: must be dropped by the sink de-duplication
            append("#EXTINF:-1 group-title=\"News\",Channel 1\nhttp://h/live/u/p/1.ts\n")
        }
        val sunk = ArrayList<MediaItem>()
        val rest = M3UParser.parse("s", StringReader(text).buffered(), sink = { sunk += it })
        assertEquals(25, sunk.size)
        assert(sunk.all { it.kind == MediaKind.LIVE || it.kind == MediaKind.MOVIE })
        assert(rest.none { it.kind == MediaKind.LIVE || it.kind == MediaKind.MOVIE })
        assertEquals(3, rest.count { it.kind == MediaKind.EPISODE })
    }
}
