package com.gassplayer.android.data

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class SourceType { XTREAM, M3U8, PLEX, JELLYFIN, EMBY }

@Serializable
data class MediaSourceConfig(
    val id: String,
    val name: String,
    val type: SourceType,
    val host: String,
    val username: String? = null,
    val password: String? = null,
    val apiToken: String? = null,
    val playlistUrl: String? = null,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0,
    val isPinned: Boolean = false,
    val lastVerifiedAt: Long? = null,
    val lastVerificationSucceeded: Boolean = false,
    val lastKnownChannelCount: Int = 0,
    val iconName: String? = null
)

@Serializable
data class XtreamCredentials(val host: String, val username: String, val password: String)

@Serializable
data class XtreamAccountInfo(val username: String = "", val status: String = "", val expDate: Long? = null, val activeConnections: Int? = null, val maxConnections: Int? = null)

@Serializable
data class Category(val id: String, val name: String, val sourceId: String = "")

@Serializable
enum class MediaKind { LIVE, MOVIE, SERIES, EPISODE }

@Serializable
data class MediaItem(
    val id: String,
    val sourceId: String,
    val kind: MediaKind,
    val title: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val group: String? = null,
    val categoryId: String? = null,
    val number: Int? = null,
    val year: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val rating: Double? = null,
    val durationSec: Long? = null,
    val tmdbId: String? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    val seriesId: String? = null,
    val hasArchive: Boolean = false,
    val metadataTag: String? = null,
    val streamHeaders: Map<String, String> = emptyMap(),
    val streamMimeType: String? = null
)

@Serializable
data class SeriesInfo(val id: String, val sourceId: String, val title: String, val posterUrl: String? = null, val backdropUrl: String? = null, val plot: String? = null, val genre: String? = null, val rating: Double? = null)

@Serializable
data class EpgProgram(val id: String, val streamId: String, val title: String, val description: String? = null, val startMs: Long, val endMs: Long, val hasArchive: Boolean = false) {
    fun isCurrent(now: Long = System.currentTimeMillis()) = now in startMs until endMs
    fun progress(now: Long = System.currentTimeMillis()): Float = if (endMs <= startMs) 0f else ((now - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

@Serializable
data class M3UPlaylistSnapshot(val sourceId: String, val channels: List<MediaItem> = emptyList(), val groups: Map<String, List<String>> = emptyMap(), val updatedAt: Long = System.currentTimeMillis())

@Serializable
data class WatchEntry(val contentId: String, val title: String, val kind: MediaKind, val url: String, val positionMs: Long = 0, val durationMs: Long = 0, val lastWatchedMs: Long = System.currentTimeMillis(), val season: Int? = null, val episode: Int? = null)

@Serializable
data class FavoriteState(val live: Set<String> = emptySet(), val movies: Set<String> = emptySet(), val series: Set<String> = emptySet())

@Serializable
data class AppSettings(
    val theme: String = "system",
    val density: String = "comfortable",
    val language: String = "system",
    val autoplayNextEpisode: Boolean = true,
    val resumePlayback: Boolean = true,
    val preferredPlaybackSpeed: Float = 1f,
    val showChannelNumbers: Boolean = false,
    val subtitleLanguage: String = "it",
    val preferredDns: String = "1.1.1.1",
    val downloadWifiOnly: Boolean = true,
    val catalogRefreshInterval: String = "manual",
    val catalogRefreshOnLaunch: Boolean = false,
    val showEpgInChannelTiles: Boolean = true,
    val preloadSeries: Boolean = true,
    val epgAutoUpdateEnabled: Boolean = true,
    val minBufferSec: Int = 15,
    val maxBufferSec: Int = 90,
    val accurateSeek: Boolean = true,
    val hardwareDecode: Boolean = true,
    val asyncDecode: Boolean = true,
    val softwareDecode: Boolean = false,
    val ffmpegLowResolution: String = "full",
    val videoDelayMs: Int = 0,
    val aspectRatio: String = "fit",
    val loopPlayback: Boolean = false,
    val adaptiveBitrate: Boolean = true,
    val httpCache: Boolean = true,
    val audioOnly: Boolean = false,
    val deinterlace: Boolean = true,
    val preserveImageSubtitles: Boolean = true,
    val panorama360: Boolean = false,
    val autoRotate360: Boolean = true,
    val ffmpegFilters: String = "",
    val ffmpegOptions: String = "",
    val tmdbApiKey: String = "",
    val omdbApiKey: String = "",
    val traktClientId: String = "",
    val traktClientSecret: String = "",
    val openSubtitlesApiKey: String = "",
    val customUserAgent: String = "",
    val homeSectionOrder: List<String> = defaultHomeSections,
    val hiddenHomeSections: Set<String> = emptySet()
)

val defaultHomeSections = listOf("heading", "continueWatching", "sourceCard", "sources", "liveTV", "guidaTV", "favoriteChannels", "favoriteSeries", "favoriteMovies", "onDemand")

enum class RefreshInterval(val id: String, val millis: Long?) {
    MANUAL("manual", null), FIFTEEN_MIN("fifteenMinutes", 15 * 60_000L), THIRTY_MIN("thirtyMinutes", 30 * 60_000L), HOUR("oneHour", 60 * 60_000L), THREE_HOURS("threeHours", 3 * 60 * 60_000L), SIX_HOURS("sixHours", 6 * 60 * 60_000L), TWELVE_HOURS("twelveHours", 12 * 60 * 60_000L), DAILY("daily", 24 * 60 * 60_000L);
    companion object { fun from(id: String) = entries.firstOrNull { it.id == id } ?: MANUAL }
}


@Serializable
data class ExternalEpgSource(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val urlString: String,
    val isEnabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)

@Serializable
data class MergedPlaylist(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val memberSourceIds: List<String>,
    val sortOrder: Int = 0
)

@Serializable
data class SourceBackup(val version: Int = 1, val exportedAt: Long = System.currentTimeMillis(), val sources: List<MediaSourceConfig>)

@Serializable
data class PreferencesBackup(val version: Int = 1, val exportedAt: Long = System.currentTimeMillis(), val settings: AppSettings)

@Serializable
data class VPNConfig(
    val protocol: String = "ikev2",
    val serverEndpoint: String = "",
    val username: String = "",
    val password: String = "",
    val serverPublicKey: String? = null,
    val presharedKey: String? = null,
    val clientPrivateKey: String? = null,
    val clientAddress: String? = "10.66.66.2/32",
    val dns: List<String> = listOf("1.1.1.1"),
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val mtu: Int = 1400
) {
    val isValid: Boolean get() = serverEndpoint.isNotBlank() && when (protocol.lowercase(Locale.ROOT)) {
        "ikev2" -> username.isNotBlank() && password.isNotBlank()
        "wireguard" -> !serverPublicKey.isNullOrBlank() && !clientPrivateKey.isNullOrBlank()
        "openvpn" -> true
        else -> false
    }
}

@Serializable
data class DiagnosticEntry(val at: Long = System.currentTimeMillis(), val level: String, val message: String, val tag: String = "app")

@Serializable
data class SearchHistory(val terms: List<String> = emptyList())

@Serializable
data class TraktAccount(val accessToken: String = "", val refreshToken: String = "", val username: String = "")
