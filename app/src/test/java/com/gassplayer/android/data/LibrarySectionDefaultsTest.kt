package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySectionDefaultsTest {
    @Test
    fun freshInstallGroupLayoutDefaultsToCategorieTvOnEveryPlatform() {
        assertEquals("categorie_tv", defaultGroupUIStyle(isTelevision = true))
        assertEquals("categorie_tv", defaultGroupUIStyle(isTelevision = false))
        assertEquals("categorie_tv", AppSettings().groupUIStyle)
    }

    @Test
    fun oldMobileFactoryDefaultMigratesWhileVersionedAndCustomLayoutsArePreserved() {
        assertEquals("categorie_tv", migratedGroupUIStyle("scorrevole", isTelevision = false, hasDefaultsVersion = false))
        assertEquals("scorrevole", migratedGroupUIStyle("scorrevole", isTelevision = false, hasDefaultsVersion = true))
        assertEquals("espansibile", migratedGroupUIStyle("espansibile", isTelevision = false, hasDefaultsVersion = false))
        assertEquals("scorrevole", migratedGroupUIStyle("scorrevole", isTelevision = true, hasDefaultsVersion = false))
    }

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
