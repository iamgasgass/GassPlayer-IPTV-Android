package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDetailModelsTest {
    @Test
    fun vodInfoDerivedFieldsMatchIos() {
        val info = XtreamVodInfo(
            movieImage = "http://x/poster.jpg",
            backdropPaths = listOf("http://x/back1.jpg", "http://x/back2.jpg"),
            cast = "Attore Uno, Attore Due ,",
            genre = "Drama, Crime",
            releaseDate = "2001-04-06",
            rating = "7,6",
            durationSecs = 7440,
            tmdbId = " 4133 ",
        )
        assertEquals(listOf("Attore Uno", "Attore Due"), info.castNames)
        assertEquals(listOf("Drama", "Crime"), info.genreNames)
        assertEquals("http://x/back1.jpg", info.backdropUrl)
        assertEquals("2001", info.year)
        assertEquals(124, info.runtimeMinutes)
        assertEquals(7.6, info.ratingValue!!, 0.0001)
        assertEquals(4133, info.tmdbIdValue)
    }

    @Test
    fun vodInfoFallsBackToMovieImageAndDurationText() {
        val info = XtreamVodInfo(movieImage = "http://x/poster.jpg", duration = "01:45:00", tmdbId = "0")
        assertEquals("http://x/poster.jpg", info.backdropUrl)
        assertEquals(105, info.runtimeMinutes)
        assertNull(info.tmdbIdValue)
    }

    @Test
    fun seriesInfoDerivedFields() {
        val info = XtreamSeriesInfo(
            XtreamSeriesDetails(cover = "http://x/cover.jpg", releaseDate = "2008-01-20", rating = "9.5", tmdbId = "1396", genre = "Drama"),
            listOf(XtreamSeasonInfo(1, name = "Stagione 1"))
        )
        assertEquals("http://x/cover.jpg", info.backdropUrl)
        assertEquals("2008", info.year)
        assertEquals(1396, info.tmdbIdValue)
        assertEquals("Stagione 1", info.seasonInfo(1)?.name)
    }

    @Test
    fun ratingsAndRuntimeFormatting() {
        val detail = MediaDetail(runtimeMinutes = 105, ratings = MediaRatings(tmdbPercent = 73, rottenTomatoesPercent = 87, imdbScore = 7.5, metacriticScore = 52))
        assertEquals("1h 45min", detail.runtimeLabel)
        assertEquals("2h", detail.copy(runtimeMinutes = 120).runtimeLabel)
        assertEquals("50min", detail.copy(runtimeMinutes = 50).runtimeLabel)
        val ratings = detail.ratings.toRatings()
        assertEquals("87%", ratings.rottenTomatoes)
        assertEquals(52, ratings.metacritic)
        assertEquals(7.3, detail.toMetadataResult("x").rating!!, 0.0001)
    }
}
