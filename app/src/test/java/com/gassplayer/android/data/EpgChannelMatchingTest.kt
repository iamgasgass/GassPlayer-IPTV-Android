package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgChannelMatchingTest {
    private fun program(id: String, title: String, start: Long = 1_000L) = EpgProgram(
        id = "$id:$start", streamId = id, title = title, startMs = start, endMs = start + 1_800_000L,
    )

    @Test
    fun normalizesCountryPrefixesQualityAndUnicodeDecorations() {
        assertEquals("rai1", EpgChannelNormalizer.normalize("IT| RAI ONE FHD"))
        assertEquals("bbc1", EpgChannelNormalizer.normalize("UK - BBC ONE ᴴᴰ"))
        assertEquals("skyatlantic", EpgChannelNormalizer.normalize("[UK] Sky Atlantic HD/RAW"))
    }

    @Test
    fun resolvesProviderChannelByGuideDisplayNameWhenIdsDiffer() {
        val guide = XmlTvGuide(
            programs = listOf(program("rai1.xmltv", "TG1")),
            channels = listOf(XmlTvChannelAlias("rai1.xmltv", "Rai 1")),
        )
        val index = EpgGuideIndex.build(listOf("free-guide" to guide))

        assertEquals("TG1", index.find("m3u-source", "", "stream-42", "IT| RAI 1 HD").single().title)
    }

    @Test
    fun mergesAllFeedsWithoutDuplicatingTheSameProgramme() {
        val first = XmlTvGuide(
            programs = listOf(program("rai1.it", "TG1")),
            channels = listOf(XmlTvChannelAlias("rai1.it", "Rai 1")),
        )
        val second = XmlTvGuide(
            programs = listOf(program("Rai1", "TG1"), program("Rai1", "TG2", 2_800_000L)),
            channels = listOf(XmlTvChannelAlias("Rai1", "RAI ONE HD")),
        )
        val index = EpgGuideIndex.build(listOf("feed-a" to first, "feed-b" to second))

        assertEquals(listOf("TG1", "TG2"), index.find("m3u", null, "42", "Rai 1 FHD").map { it.title })
    }

    @Test
    fun refusesAmbiguousRegionalPrefixMatches() {
        val guide = XmlTvGuide(
            programs = listOf(program("bbc-london", "London News"), program("bbc-wales", "Wales News", 2_800_000L)),
            channels = listOf(
                XmlTvChannelAlias("bbc-london", "BBC One London"),
                XmlTvChannelAlias("bbc-wales", "BBC One Wales"),
            ),
        )
        val index = EpgGuideIndex.build(listOf("guide" to guide))

        assertTrue(index.find("m3u", null, "1", "BBC One").isEmpty())
    }
}
