package com.gassplayer.android.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quello che Android trasmette deve coincidere con quello che trasmette l'app iOS. */
class TransmissionParityTest {
    @Test
    fun thirdPartyApisAreContactedOnlyAtTheExactAddress() {
        for (url in listOf(
            "https://api.themoviedb.org/3/movie/1?api_key=k",
            "https://www.omdbapi.com/?i=tt1&apikey=k",
            "https://api.trakt.tv/oauth/device/token",
            "https://api.opensubtitles.com/api/v1/subtitles?query=x",
        )) assertEquals(listOf(url), NetworkApi.candidateUrls(url))
        // I pannelli IPTV conservano le alternative di trasporto.
        assertTrue(NetworkApi.candidateUrls("https://panel.example/live/u/p/1.ts").size > 1)
    }

    @Test
    fun streamCredentialsAreEncodedAsPathSegmentsLikeIos() {
        assertEquals("user%20name", XtreamRepository.pathSegment("user name"))
        assertEquals("a+b@c:d", XtreamRepository.pathSegment("a+b@c:d"))
        assertEquals("a%2Fb%3Fc%23d%25", XtreamRepository.pathSegment("a/b?c#d%"))
        assertEquals("%C3%A8", XtreamRepository.pathSegment("è"))
        assertEquals("abcXYZ019-._~", XtreamRepository.pathSegment("abcXYZ019-._~"))
    }

    @Test
    fun iosSourceBackupIsReadable() {
        val ios = """
            {"exportedAt":"2026-10-01T10:00:00Z","sources":[
              {"host":"http://panel.example:8080","id":"5B2A6D9E-0F7C-4C35-9E8D-1A2B3C4D5E6F","isEnabled":true,"isPinned":true,
               "name":"Casa","password":"p w","sortOrder":0,"type":"Xtream Codes","username":"u","lastVerifiedAt":"2026-09-30T08:00:00Z",
               "lastVerificationSucceeded":true,"lastKnownChannelCount":1200},
              {"host":"https://lists.example/tv.m3u","id":"6B2A6D9E-0F7C-4C35-9E8D-1A2B3C4D5E6F","name":"Lista","type":"M3U / M3U8 Playlist"}
            ],"version":1}
        """.trimIndent()
        val sources = IosBackupCodec.decodeSources(JsonStore.json.parseToJsonElement(ios))!!
        assertEquals(2, sources.size)
        assertEquals(SourceType.XTREAM, sources[0].type)
        assertEquals("p w", sources[0].password)
        assertEquals(1200, sources[0].lastKnownChannelCount)
        assertNotNull(sources[0].lastVerifiedAt)
        assertEquals(SourceType.M3U8, sources[1].type)
        assertEquals("https://lists.example/tv.m3u", sources[1].playlistUrl)
    }

    @Test
    fun exportedSourcesUseTheIosShape() {
        val out = IosBackupCodec.encodeSources(listOf(MediaSourceConfig(id = "not-a-uuid", name = "X", type = SourceType.M3U8, host = "h", playlistUrl = "https://l/x.m3u")), now = 0L)
        val root = JsonStore.json.parseToJsonElement(out).jsonObject
        assertEquals(1, root["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("1970-01-01T00:00:00Z", root["exportedAt"]!!.jsonPrimitive.content)
        val source = root["sources"]!!.jsonArray[0].jsonObject
        assertEquals("M3U / M3U8 Playlist", source["type"]!!.jsonPrimitive.content)
        assertEquals("https://l/x.m3u", source["host"]!!.jsonPrimitive.content)
        // iOS decodifica `id` come UUID: un id Android non-UUID diventa un UUID stabile.
        assertEquals(36, source["id"]!!.jsonPrimitive.content.length)
        // E Android rilegge il proprio export.
        assertEquals(SourceType.M3U8, IosBackupCodec.decodeSources(root)!!.single().type)
    }

    @Test
    fun iosPreferencesBackupMapsOntoAppSettings() {
        val root = JsonStore.json.parseToJsonElement(
            """{"autoplayNextEpisode":false,"resumePlayback":true,"preferredPlaybackSpeed":1.5,"channelGridDensity":"compact",
               "showChannelNumbers":true,"subtitleLanguage":"en","preferredDNS":"8.8.8.8","downloadWifiOnly":false,"theme":"Chiaro",
               "catalogRefreshInterval":"sixHours","catalogRefreshOnLaunch":true,"showEPGInChannelTiles":false,"preloadSeries":false,
               "epgAutoUpdateEnabled":false,"version":1,"exportedAt":"2026-10-01T10:00:00Z"}"""
        )
        assertTrue(IosBackupCodec.isPreferencesBackup(root))
        val s = IosBackupCodec.applyPreferences(root as JsonObject, AppSettings())
        assertFalse(s.autoplayNextEpisode)
        assertEquals(1.5f, s.preferredPlaybackSpeed, 0.0001f)
        assertEquals("light", s.theme)
        assertEquals("sixHours", s.catalogRefreshInterval)
        assertEquals("8.8.8.8", s.preferredDns)
        val back = JsonStore.json.parseToJsonElement(IosBackupCodec.encodePreferences(s)).jsonObject
        assertEquals("Chiaro", back["theme"]!!.jsonPrimitive.content)
        assertEquals("compact", back["channelGridDensity"]!!.jsonPrimitive.content)
    }
}
