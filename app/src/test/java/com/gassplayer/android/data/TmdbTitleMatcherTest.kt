package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stessi casi del matching di precisione di TMDBService.swift. */
class TmdbTitleMatcherTest {
    @Test
    fun providerPrefixesQualityTagsAndYearAreRemoved() {
        val parsed = TmdbTitleMatcher.parseTitle("IT - Blow (2001) [4K]")
        assertEquals("Blow", parsed.query)
        assertEquals("2001", parsed.year)
        assertNull(parsed.trailingYear)
        assertEquals("Suburbicon", TmdbTitleMatcher.parseTitle("|ITA| Suburbicon HD").query)
        assertEquals("Spider-Man: No Way Home", TmdbTitleMatcher.parseTitle("[EN] Spider-Man: No Way Home (2021)").query)
    }

    @Test
    fun tagsAreRemovedOnlyAsWholeWords() {
        assertEquals("Multiverse", TmdbTitleMatcher.parseTitle("Multiverse ITA").query)
        assertEquals("Wednesday", TmdbTitleMatcher.parseTitle("Wednesday SUB ITA").query)
        assertEquals("The Italian Job", TmdbTitleMatcher.parseTitle("The Italian Job").query)
    }

    @Test
    fun trailingYearIsAWeakHintAndBareNumbersStayTitles() {
        val parsed = TmdbTitleMatcher.parseTitle("The Italian Job 2003")
        assertEquals("The Italian Job 2003", parsed.query)
        assertEquals("2003", parsed.trailingYear)
        val nineteen = TmdbTitleMatcher.parseTitle("1917")
        assertEquals("1917", nineteen.query)
        assertNull(nineteen.trailingYear)
    }

    @Test
    fun normalizeFoldsAccentsAndPunctuation() {
        assertEquals("penelope", TmdbTitleMatcher.normalize("Penélope"))
        assertEquals("spider man", TmdbTitleMatcher.normalize("Spider-Man"))
    }

    @Test
    fun similarityNeverGivesAFullMatchToDifferentTitles() {
        assertEquals(1.0, TmdbTitleMatcher.titleSimilarity("blow", "blow"), 0.0)
        val partial = TmdbTitleMatcher.titleSimilarity("blow", "blow out")
        assertTrue(partial < 0.7 && partial > 0.6)
    }

    @Test
    fun exactTitleAndYearBeatsSimilarTitle() {
        val blow = MetadataResult(id = "1", title = "Blow", year = "2001", voteCount = 3000)
        val blowOut = MetadataResult(id = "2", title = "Blow Out", year = "1981", voteCount = 1500)
        val a = TmdbTitleMatcher.score(blow, "Blow", "2001")
        val b = TmdbTitleMatcher.score(blowOut, "Blow", "2001")
        assertTrue(a.total > b.total)
        assertTrue(a.total >= TmdbTitleMatcher.CONFIDENT_TOTAL_SCORE)
        // "Blow Out" del 1981 non supera nemmeno le soglie minime per "Blow (2001)".
        assertFalse(b.titleScore >= TmdbTitleMatcher.MINIMUM_TITLE_SCORE && b.total >= TmdbTitleMatcher.MINIMUM_TOTAL_SCORE)
    }

    @Test
    fun searchAttemptsGoFromSpecificToPermissive() {
        val attempts = TmdbTitleMatcher.searchAttempts(TmdbTitleMatcher.parseTitle("Spider-Man: No Way Home (2021)"), null)
        assertEquals("Spider-Man: No Way Home" to "2021", attempts.first())
        assertTrue(attempts.contains("Spider-Man: No Way Home" to null))
        assertTrue(attempts.contains("Spider-Man" to "2021"))
    }

    @Test
    fun providerTmdbIdIsTrustedByTitleOrCast() {
        val details = MetadataResult(id = "9", title = "Blow", cast = listOf("Johnny Depp", "Penélope Cruz"))
        assertTrue(TmdbTitleMatcher.isTrustworthy(details, "IT - Blow [4K]", emptyList()))
        val wrongTitle = details.copy(title = "Completely Different")
        assertTrue(TmdbTitleMatcher.isTrustworthy(wrongTitle, "Blow", listOf("Penelope Cruz")))
        assertFalse(TmdbTitleMatcher.isTrustworthy(wrongTitle, "Blow", listOf("Someone Else")))
    }
}
