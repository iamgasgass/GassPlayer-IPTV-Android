package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySectionDefaultsTest {
    @Test
    fun categoryGridIsHiddenByDefaultAcrossAllLibraries() {
        val settings = AppSettings()
        assertEquals(setOf("categories"), settings.hiddenLiveSections)
        assertEquals(setOf("categories"), settings.hiddenMovieSections)
        assertEquals(setOf("categories"), settings.hiddenSeriesSections)
        assertTrue(defaultLiveSections.contains("categories"))
        assertTrue(defaultMovieSections.contains("categories"))
        assertTrue(defaultSeriesSections.contains("categories"))
    }

    @Test
    fun legacyDefaultLayoutsHideCategoriesButCustomLayoutsArePreserved() {
        assertEquals(
            setOf("categories"),
            migratedLegacyLibraryHiddenSections(defaultLiveSections, emptySet(), defaultLiveSections),
        )
        assertEquals(
            emptySet<String>(),
            migratedLegacyLibraryHiddenSections(listOf("categories", "search"), emptySet(), defaultLiveSections),
        )
        assertEquals(
            setOf("trendingMovies"),
            migratedLegacyLibraryHiddenSections(defaultMovieSections, setOf("trendingMovies"), defaultMovieSections),
        )
    }
}
