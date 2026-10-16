package com.gassplayer.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ArtworkUrlsTest {
    private val xtream = "http://panel.example.com:8080"

    @Test
    fun validUrlsAreReturnedUntouched() {
        val url = "https://cdn.example.com/logos/rai1.png?v=2"
        assertSame(url, ArtworkUrls.clean(url))
        assertEquals("http://x.it/a%20b.png", ArtworkUrls.clean("http://x.it/a%20b.png"))
    }

    @Test
    fun placeholdersAndJunkBecomeNull() {
        listOf(null, "", "  ", "null", "NULL", "none", "n/a", "0", "false", "undefined", "data:image/png;base64,xx").forEach {
            assertNull(it, ArtworkUrls.clean(it, xtream))
        }
        assertNull(ArtworkUrls.clean("images/x.png", base = null))
    }

    @Test
    fun escapedSlashesBackslashesAndQuotesAreFixed() {
        assertEquals("http://host.com/x.png", ArtworkUrls.clean("http:\\/\\/host.com\\/x.png"))
        assertEquals("http://host.com/x.png", ArtworkUrls.clean("\"http://host.com/x.png\""))
        assertEquals("http://panel.example.com:8080/img/x.png", ArtworkUrls.clean("img\\x.png", xtream))
    }

    @Test
    fun relativeAndProtocolRelativePathsAreResolved() {
        assertEquals("http://panel.example.com:8080/images/a.png", ArtworkUrls.clean("/images/a.png", xtream))
        assertEquals("http://panel.example.com:8080/images/a.png", ArtworkUrls.clean("images/a.png", xtream))
        assertEquals("https://cdn.host/x.png", ArtworkUrls.clean("//cdn.host/x.png", "https://p.tv/list.m3u"))
        assertEquals("http://cdn.host/x.png", ArtworkUrls.clean("//cdn.host/x.png"))
        // M3U: relative to the playlist folder.
        assertEquals("http://lists.tv/it/logos/rai.png", ArtworkUrls.clean("logos/rai.png", "http://lists.tv/it/list.m3u"))
        assertEquals("http://cdn.picons.org/rai.png", ArtworkUrls.clean("cdn.picons.org/rai.png"))
    }

    @Test
    fun spacesAndNonAsciiArePercentEncodedOnce() {
        assertEquals("http://h.com/Rai%201%20HD.png", ArtworkUrls.clean("http://h.com/Rai 1 HD.png"))
        assertEquals("http://h.com/citt%C3%A0.png", ArtworkUrls.clean("http://h.com/città.png"))
        assertEquals("http://h.com/a%20b%25.png", ArtworkUrls.clean("http://h.com/a%20b%.png"))
        assertEquals("http://h.com/a%7Cb.png", ArtworkUrls.clean("http://h.com/a|b.png"))
    }

    @Test
    fun itemsGetTheFieldTheirViewReads() {
        val movie = MediaItem("m", "s", MediaKind.MOVIE, "Film", "http://h/1.mp4", logoUrl = "http://h/poster.jpg")
        assertEquals("http://h/poster.jpg", ArtworkUrls.normalize(movie).posterUrl)
        val live = MediaItem("l", "s", MediaKind.LIVE, "Rai 1", "http://h/1.ts", posterUrl = "http://h/logo.png")
        assertEquals("http://h/logo.png", ArtworkUrls.normalize(live).logoUrl)
        val clean = MediaItem("c", "s", MediaKind.LIVE, "Rai 2", "http://h/2.ts", logoUrl = "http://h/rai2.png")
        assertSame(clean, ArtworkUrls.normalize(clean))
    }

    @Test
    fun firstValidSkipsUnusableValues() {
        assertEquals("http://h/cover.jpg", ArtworkUrls.firstValid(null, "none", "", "0", "http://h/cover.jpg", "http://h/other.jpg"))
        assertEquals("http://panel.example.com:8080/img/a.png", ArtworkUrls.firstValid(xtream, null, "/img/a.png"))
        assertNull(ArtworkUrls.firstValid(null, null, "null", "images/x.png"))
    }

    @Test
    fun hostsAcceptedByTheImageLoaderAreKept() {
        // java.net.URI rejected these: the logo/poster was dropped although OkHttp and iOS download it.
        assertEquals("http://img_cdn.example.com/logo.png", ArtworkUrls.clean("http://img_cdn.example.com/logo.png"))
        assertEquals("http://" + java.net.IDN.toASCII("rè.it") + "/a.png", ArtworkUrls.clean("http://rè.it/a.png"))
        assertEquals("http://h.com/x.png", ArtworkUrls.clean("HTTP://h.com/x.png"))
        assertEquals("http://h.com/x.png?w=2", ArtworkUrls.clean("http://h.com/x.png?w=2|User-Agent=VLC"))
        assertEquals("http://h.com/list/img/a.png", ArtworkUrls.clean("img/a.png", "http://h.com/list/pl.m3u"))
        assertEquals("http://h.com/img/a.png", ArtworkUrls.clean("../img/a.png", "http://h.com/list/pl.m3u"))
        assertNull(ArtworkUrls.clean("http://h.com:abc/a.png"))
    }

    @Test
    fun inlineDataImagesAreKept() {
        val data = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/\n2wCEAAkGBxMTEhUTExMWFhUXFxcYGBgXGB0Y"
        assertEquals(data.replace("\n", ""), ArtworkUrls.clean(data))
        assertNull(ArtworkUrls.clean("data:text/html;base64,PGh0bWw+"))
    }

    @Test
    fun templatePlaceholdersAreFilled() {
        val raw = "https://thumb.example.pro/http/unsafe/%7BresolutionXY%7D/filters:quality(%7BimageQualityPercentage%7D)/img.example.it/a/T_1.jpg"
        assertEquals("https://thumb.example.pro/http/unsafe/600x0/filters:quality(80)/img.example.it/a/T_1.jpg", ArtworkUrls.clean(raw))
        assertEquals("https://h.com/600x0/a.jpg", ArtworkUrls.clean("https://h.com/{resolutionXY}/a.jpg"))
    }

    @Test
    fun wikimediaThumbnailsUseStandardSteps() {
        // 188 px non e' una misura ammessa (HTTP 400/429): si passa alla successiva, 250 px.
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/en/thumb/5/53/Ducktv-logo.jpg/250px-Ducktv-logo.jpg",
            ArtworkUrls.clean("https://upload.wikimedia.org/wikipedia/en/thumb/5/53/Ducktv-logo.jpg/188px-Ducktv-logo.jpg")
        )
        // Misura gia' standard: invariata.
        val standard = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Logo.svg/120px-Logo.svg.png"
        assertEquals(standard, ArtworkUrls.clean(standard))
        // SVG convertito in PNG, misura non standard.
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Logo.svg/330px-Logo.svg.png",
            ArtworkUrls.clean("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Logo.svg/300px-Logo.svg.png")
        )
        // Oltre 3840 px: l'originale.
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/a/ab/Big.jpg",
            ArtworkUrls.clean("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Big.jpg/5000px-Big.jpg")
        )
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/en/5/53/Ducktv-logo.jpg",
            ArtworkUrls.wikimediaOriginal("https://upload.wikimedia.org/wikipedia/en/thumb/5/53/Ducktv-logo.jpg/250px-Ducktv-logo.jpg")
        )
        // Altri server: invariati.
        assertEquals("https://www.lyngsat-logo.com/logo/tv/rr/rtl_kockica_hr.png", ArtworkUrls.clean("https://www.lyngsat-logo.com/logo/tv/rr/rtl_kockica_hr.png"))
    }
}
