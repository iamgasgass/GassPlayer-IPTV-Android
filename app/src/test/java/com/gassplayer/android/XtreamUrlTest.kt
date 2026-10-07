package com.gassplayer.android

import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.XtreamCredentials
import com.gassplayer.android.data.XtreamRepository
import com.gassplayer.android.data.NetworkApi
import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamUrlTest {
    @Test fun stripsPlayerApiPathFromProviderHost() {
        val repo = XtreamRepository(NetworkApi())
        val c = XtreamCredentials("https://provider.test:25461/player_api.php", "u", "p")
        val method = repo::class.java.getDeclaredMethod("streamUrl", XtreamCredentials::class.java, String::class.java, MediaKind::class.java, String::class.java)
        method.isAccessible = true
        assertEquals("https://provider.test:25461/live/u/p/22.ts", method.invoke(repo, c, "22", MediaKind.LIVE, "ts"))
    }

    @Test fun buildsProviderStreamUrls() {
        val repo = XtreamRepository(NetworkApi())
        val c = XtreamCredentials("http://provider.test:8080/", "u", "p")
        val method = repo::class.java.getDeclaredMethod("streamUrl", XtreamCredentials::class.java, String::class.java, MediaKind::class.java, String::class.java)
        method.isAccessible = true
        assertEquals("http://provider.test:8080/live/u/p/22.ts", method.invoke(repo, c, "22", MediaKind.LIVE, "ts"))
        assertEquals("http://provider.test:8080/movie/u/p/33.mp4", method.invoke(repo, c, "33", MediaKind.MOVIE, "mp4"))
        assertEquals("http://provider.test:8080/series/u/p/44.mp4", method.invoke(repo, c, "44", MediaKind.EPISODE, "mp4"))
    }
}
