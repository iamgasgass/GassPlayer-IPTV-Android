package com.gassplayer.android.ui

import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrendMatchingTest {
    private fun movie(id: String, title: String, tmdbId: String? = null, year: String? = null) =
        MediaItem(id = id, sourceId = "src", kind = MediaKind.MOVIE, title = title, streamUrl = "", tmdbId = tmdbId, year = year)

    private fun query(vararg titles: String, year: String? = null, tmdbId: String = "1") =
        TrendQuery(tmdbId = tmdbId, titles = titles.toList(), year = year)

    @Test
    fun playlistTagsAndQualityWordsAreIgnored() {
        assertEquals("dunepartedue", trendTitleKey("IT: Dune - Parte Due (2024) [4K]"))
        assertEquals("strangerthings", trendTitleKey("Stranger Things [ITA] FHD"))
        assertEquals("it", trendTitleKey("IT | It (2017) HD"))
        assertEquals("pokemondetectivepikachu", trendTitleKey("Pokémon: Detective Pikachu"))
    }

    @Test
    fun exactTitleAndYearPickTheRightWork() {
        val q = query("Dune - Parte due", "Dune: Part Two", year = "2024")
        val match = bestTrendMatch(listOf(movie("old", "Dune (1984)"), movie("new", "IT: Dune - Parte Due (2024) [4K]")), q)
        assertEquals("new", match?.id)
    }

    @Test
    fun remakesWithADifferentYearAreRejected() {
        assertNull(bestTrendMatch(listOf(movie("a", "Il Re Leone (1994)")), query("Il re leone", year = "2019")))
        assertNull(bestTrendMatch(listOf(movie("a", "Blade Runner (1982)")), query("Blade Runner 2049", year = "2017")))
    }

    @Test
    fun numbersThatAreTitlesAreNotReadAsYears() {
        assertEquals("a", bestTrendMatch(listOf(movie("a", "Blade Runner 2049")), query("Blade Runner 2049", year = "2017"))?.id)
        assertEquals("b", bestTrendMatch(listOf(movie("b", "1917")), query("1917", year = "2019"))?.id)
    }

    @Test
    fun shortOrLooseTitlesDoNotOpenAWrongWork() {
        assertNull(bestTrendMatch(listOf(movie("a", "It Follows")), query("It", year = "2014")))
        assertNull(bestTrendMatch(listOf(movie("a", "Money Heist Korea")), query("La casa di carta", "Money Heist", year = "2017")))
    }

    @Test
    fun tmdbIdWinsOverTitles() {
        val match = bestTrendMatch(listOf(movie("a", "Oppenheimer"), movie("b", "Oppenheimer (versione IMAX)", tmdbId = "872585")), query("Oppenheimer", tmdbId = "872585"))
        assertEquals("b", match?.id)
    }
}
