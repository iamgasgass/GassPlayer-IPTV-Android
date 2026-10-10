package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgGuideTest {
    private fun p(channel: String, start: Long, end: Long) = EpgProgram("$channel:$start", channel, "t$start", null, start, end)

    @Test fun xmltvTimeAcceptsZoneVariants() {
        val utc = 1767268800000L // 2026-01-01T12:00:00Z
        assertEquals(utc, XmlTvParser.xmlTvTime("20260101120000 +0000"))
        assertEquals(utc, XmlTvParser.xmlTvTime("20260101120000 UTC"))
        assertEquals(utc, XmlTvParser.xmlTvTime("20260101120000 GMT"))
        assertEquals(utc, XmlTvParser.xmlTvTime("20260101120000Z"))
        assertEquals(utc, XmlTvParser.xmlTvTime("20260101130000 +01:00"))
        assertEquals(utc, XmlTvParser.xmlTvTime("20260101070000 -0500"))
        assertEquals(utc, XmlTvParser.xmlTvTime("20260101130000 +01"))
        assertNotNull(XmlTvParser.xmlTvTime("20260101120000")) // senza fuso: fuso del dispositivo
        assertNotNull(XmlTvParser.xmlTvTime("202601011200 +0000")) // senza secondi
        assertNull(XmlTvParser.xmlTvTime("garbage"))
        assertNull(XmlTvParser.xmlTvTime("20261301120000 +0000")) // mese 13
    }

    @Test fun channelNamesNormalizeAcrossDecorations() {
        assertEquals("rai1", EpgNames.normalize("IT: Rai 1 HD"))
        assertEquals("rai1", EpgNames.normalize("RAI1"))
        assertEquals("rai1", EpgNames.normalize("Rai 1 FHD"))
        assertEquals("skysport", EpgNames.normalize("Sky Sport 4K"))
        assertEquals("tv8", EpgNames.normalize("TV8 HD"))
    }

    @Test fun mergeKeepsPanelDataAndFillsGaps() {
        val panel = listOf(p("a", 1000, 2000), p("a", 2000, 3000))
        val xml = listOf(p("x", 0, 1000), p("x", 1000, 2000), p("x", 2500, 3500), p("x", 3000, 4000))
        val merged = mergePrograms(panel, xml)
        assertEquals(listOf(0L, 1000L, 2000L, 3000L), merged.map { it.startMs })
        assertEquals("a", merged[1].streamId) // il pannello vince dove si sovrappone
        assertEquals(panel.sortedBy { it.startMs }, mergePrograms(panel, emptyList()))
        assertEquals(xml.sortedBy { it.startMs }, mergePrograms(emptyList(), xml))
    }

    @Test fun indexResolvesByTagTitleAliasThenStreamId() {
        val byTag = XmlTvData(listOf(p("Rai1.it", 1, 2)), mapOf("rai1" to "rai1.it"))
        val byStream = XmlTvData(listOf(p("77", 5, 6)), emptyMap())
        val idx = XmlTvIndex.build(listOf(byTag, byStream), 1)
        assertTrue(idx.ready)
        assertEquals(1L, idx.programsFor("rai1.IT", "9", "Qualcosa").single().startMs) // id EPG, senza maiuscole
        assertEquals(1L, idx.programsFor(null, "9", "IT: Rai 1 HD").single().startMs) // alias da display-name
        assertEquals(5L, idx.programsFor(null, "77", "Sconosciuto").single().startMs) // ultimo: id stream
        assertTrue(idx.programsFor(null, "1", "Niente").isEmpty())
        assertTrue(XmlTvIndex.EMPTY.programsFor("x", "1", "x").isEmpty())
    }

    @Test fun filterSignatureDependsOnlyOnChannelSet() {
        fun ch(id: String, title: String, tag: String?) = MediaItem(id = id, title = title, kind = MediaKind.LIVE, streamUrl = "http://h/$id", sourceId = "s", metadataTag = tag)
        val a = XmlTvFilter.forChannels(listOf(ch("s:live:1", "Rai 1 HD", "rai1.it"), ch("s:live:2", "Rai 2", null)))
        val b = XmlTvFilter.forChannels(listOf(ch("s:live:2", "Rai 2", null), ch("s:live:1", "Rai 1 HD", "rai1.it")))
        assertEquals(a.signature, b.signature)
        assertTrue("rai1.it" in a.ids && "1" in a.ids && "rai 1 hd" in a.ids)
        assertTrue("rai1" in a.names && "rai2" in a.names)
    }
}
