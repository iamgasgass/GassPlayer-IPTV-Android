package com.gassplayer.android.data

import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.net.URI
import java.util.Locale

class XtreamRepository(private val api: NetworkApi) {
    suspend fun authenticate(creds: XtreamCredentials): XtreamAccountInfo {
        val root = api.getJson(playerApi(creds, emptyMap()))
        val info = root.jsonObject["user_info"]?.jsonObject ?: error("Risposta Xtream non valida")
        val auth = info.intOrNull("auth")
        val status = info.stringOrNull("status").orEmpty()
        if (auth == 0 || status.equals("Disabled", true)) error("Account Xtream non attivo")
        if (status.isBlank() && auth != 1) error("Account Xtream non verificabile")
        return XtreamAccountInfo(
            info.stringOrNull("username").orEmpty(),
            status,
            info.longOrNull("exp_date"),
            info.intOrNull("active_cons"),
            info.intOrNull("max_connections")
        )
    }

    suspend fun loadCatalog(source: MediaSourceConfig): CatalogBundle {
        val creds = XtreamCredentials(serverBase(source.host), source.username.orEmpty(), source.password.orEmpty())
        authenticate(creds)

        val liveCategories = requestArray(creds, "get_live_categories").map { Category(it.stringOrNull("category_id").orEmpty(), it.stringOrNull("category_name").orEmpty(), source.id) }
        val vodCategories = requestArray(creds, "get_vod_categories").map { Category(it.stringOrNull("category_id").orEmpty(), it.stringOrNull("category_name").orEmpty(), source.id) }
        val seriesCategories = requestArray(creds, "get_series_categories").map { Category(it.stringOrNull("category_id").orEmpty(), it.stringOrNull("category_name").orEmpty(), source.id) }
        var live = requestArray(creds, "get_live_streams").mapIndexed { i, o -> o.toLive(source, i, creds) }.filter { it.id.substringAfterLast(':').isNotBlank() }.distinctBy { it.id }
        var movies = requestArray(creds, "get_vod_streams").mapIndexed { i, o -> o.toVod(source, i, creds) }.filter { it.id.substringAfterLast(':').isNotBlank() }.distinctBy { it.id }
        var series = requestArray(creds, "get_series").map { it.toSeries(source) }.filter { it.id.substringAfterLast(':').isNotBlank() }.distinctBy { it.id }

        // A subset of panels expose get_series but return an empty/error response. Their
        // generated M3U still contains the series/episode entries, so use it as a targeted
        // fallback instead of failing the entire Xtream source.
        var fallbackEpisodes = emptyList<MediaItem>()
        if (series.isEmpty() || live.isEmpty() || movies.isEmpty()) {
            runCatching {
                val playlistUrl = xtreamPlaylist(creds)
                val fetched = api.getTextResult(playlistUrl, mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, text/plain, */*"))
                M3UParser.parse(source.id, fetched.text, NetworkApi.stripInlineHeaders(fetched.finalUrl))
            }.onSuccess { parsed ->
                if (series.isEmpty()) series = parsed.filter { it.kind == MediaKind.SERIES }.distinctBy { it.id }
                if (live.isEmpty()) {
                    live = parsed.filter { it.kind == MediaKind.LIVE }.mapIndexed { i, item ->
                        item.copy(number = item.number ?: i + 1)
                    }.distinctBy { it.id }
                }
                if (movies.isEmpty()) movies = parsed.filter { it.kind == MediaKind.MOVIE }.distinctBy { it.id }
                fallbackEpisodes = parsed.filter { it.kind == MediaKind.EPISODE }.distinctBy { it.id }
            }
        }

        return CatalogBundle(liveCategories, vodCategories, seriesCategories, live, movies, series, fallbackEpisodes)
    }

    suspend fun vodDetail(source: MediaSourceConfig, vodId: String): MediaItem? {
        val creds = XtreamCredentials(serverBase(source.host), source.username.orEmpty(), source.password.orEmpty())
        val root = api.getJson(playerApi(creds, mapOf("action" to "get_vod_info", "vod_id" to vodId))).jsonObject
        val o = root["info"]?.jsonObject ?: root.jsonObjectOrNull() ?: return null
        return o.toVod(source, 0, creds, forceId = vodId)
    }

    suspend fun seriesEpisodes(source: MediaSourceConfig, seriesId: String, seriesTitleHint: String? = null): List<MediaItem> {
        val creds = XtreamCredentials(serverBase(source.host), source.username.orEmpty(), source.password.orEmpty())
        val roots = buildList {
            runCatching {
                add(api.getJson(playerApi(creds, mapOf("action" to "get_series_info", "series_id" to seriesId))).jsonObject)
            }
            // A number of compatible Xtream panels expose a lighter streams endpoint instead of
            // embedding the complete episode object in get_series_info. Try it only as a fallback.
            runCatching {
                add(api.getJson(playerApi(creds, mapOf("action" to "get_series_streams", "series_id" to seriesId))).jsonObject)
            }
        }.distinctBy { it.toString() }
        val primaryRoot = roots.firstOrNull()

        val episodeObjects = roots.flatMap { root ->
            buildList {
                root["episodes"]?.let { addAll(flattenEpisodes(it)) }
                root["data"]?.let { addAll(flattenEpisodes(it)) }
                if (looksLikeEpisodeObject(root)) add(null to root)
            }
        }
        val direct = episodeObjects
            .mapNotNull { (seasonOverride, episode) -> episode.toEpisode(source, creds, seriesId, seasonOverride) }
            .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }, { it.title.lowercase(Locale.ROOT) }))
            .distinctBy { it.id }
        if (direct.isNotEmpty()) return direct

        // Some panels expose season lists separately. If present, use get_series_streams per season
        // before falling back to the much larger generated M3U playlist.
        val seasonNumbers = roots.flatMap { root ->
            root["seasons"]?.let { seasons ->
                when (seasons) {
                    is JsonArray -> seasons.mapNotNull { it.jsonObjectOrNull()?.intOrNull("season_number") ?: it.jsonObjectOrNull()?.stringOrNull("season")?.toIntOrNull() }
                    is JsonObject -> seasons.keys.mapNotNull { it.toIntOrNull() }
                    else -> emptyList()
                }
            } ?: emptyList()
        }.distinct().sorted()
        if (seasonNumbers.isNotEmpty()) {
            val seasonEpisodes = seasonNumbers.flatMap { season ->
                runCatching {
                    val obj = api.getJson(playerApi(creds, mapOf(
                        "action" to "get_series_streams",
                        "series_id" to seriesId,
                        "season" to season.toString()
                    )))
                    flattenEpisodes(obj).mapNotNull { (seasonOverride, episode) ->
                        episode.toEpisode(source, creds, seriesId, seasonOverride ?: season)
                    }
                }.getOrDefault(emptyList())
            }.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }, { it.title.lowercase(Locale.ROOT) }))
                .distinctBy { it.id }
            if (seasonEpisodes.isNotEmpty()) return seasonEpisodes
        }

        // Some panels expose the series catalogue but fail to return episode details from the
        // JSON API at all. Recover the episodes from the provider's generated M3U.
        // Recover the episodes from the provider's generated M3U. The title hint also lets
        // synthetic M3U-only series recover even when their synthetic ID is not an API series_id.
        val seriesName = seriesTitleHint?.takeIf { it.isNotBlank() }
            ?: primaryRoot?.get("info")?.jsonObjectOrNull()?.stringOrNull("name")
            ?: primaryRoot?.get("info")?.jsonObjectOrNull()?.stringOrNull("title")
        if (seriesName.isNullOrBlank()) return emptyList()
        return runCatching {
            val playlistUrl = xtreamPlaylist(creds)
            val fetched = api.getTextResult(playlistUrl, mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, text/plain, */*"))
            val target = normalizeSeriesMatch(seriesName)
            M3UParser.parse(source.id, fetched.text, NetworkApi.stripInlineHeaders(fetched.finalUrl))
                .filter {
                    it.kind == MediaKind.EPISODE &&
                        (normalizeSeriesMatch(it.title) == target || normalizeSeriesMatch(it.group.orEmpty()) == target)
                }
                .map { it.copy(seriesId = seriesId) }
                .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }, { it.title.lowercase(Locale.ROOT) }))
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    fun streamUrl(creds: XtreamCredentials, id: String, kind: MediaKind, extension: String = "ts"): String {
        val base = serverBase(creds.host)
        val u = enc(creds.username); val p = enc(creds.password)
        val cleanId = id.trim()
        val safeExt = extension.trim().trimStart('.').ifBlank { if (kind == MediaKind.LIVE) "ts" else "mp4" }
        return when (kind) {
            MediaKind.LIVE -> "$base/live/$u/$p/$cleanId.$safeExt"
            MediaKind.MOVIE -> "$base/movie/$u/$p/$cleanId.$safeExt"
            MediaKind.EPISODE, MediaKind.SERIES -> "$base/series/$u/$p/$cleanId.$safeExt"
        }
    }

    private suspend fun requestArray(creds: XtreamCredentials, action: String): List<JsonObject> = runCatching {
        api.getJson(playerApi(creds, mapOf("action" to action))).asObjectList()
    }.getOrDefault(emptyList())

    private fun playerApi(creds: XtreamCredentials, params: Map<String, String>): String {
        val pairs = linkedMapOf("username" to creds.username, "password" to creds.password) + params
        return "${serverBase(creds.host)}/player_api.php?" + pairs.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    private fun xtreamPlaylist(creds: XtreamCredentials): String {
        val base = serverBase(creds.host)
        return "$base/get.php?username=${enc(creds.username)}&password=${enc(creds.password)}&type=m3u_plus&output=m3u8"
    }

    private fun serverBase(rawHost: String): String {
        var raw = rawHost.trim()
        require(raw.isNotBlank()) { "Host Xtream mancante" }
        if (!raw.contains("://")) raw = "http://$raw"
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: "http"
        val authority = uri.rawAuthority ?: error("Host Xtream non valido")
        var path = uri.rawPath.orEmpty().trimEnd('/')
        val lower = path.lowercase(Locale.ROOT)
        if (lower.endsWith("/player_api.php")) path = path.dropLast("/player_api.php".length)
        if (path.lowercase(Locale.ROOT).endsWith("/get.php")) path = path.dropLast("/get.php".length)
        return "$scheme://$authority${if (path.isBlank()) "" else "/${path.trim('/')}"}".trimEnd('/')
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun JsonObject.toLive(source: MediaSourceConfig, index: Int, creds: XtreamCredentials): MediaItem {
        val sid = stringOrNull("stream_id") ?: stringOrNull("id").orEmpty()
        val ext = stringOrNull("container_extension").orEmpty().ifBlank { "ts" }
        return MediaItem(
            "${source.id}:live:$sid", source.id, MediaKind.LIVE,
            stringOrNull("name") ?: stringOrNull("title").orEmpty(),
            streamUrl(creds, sid, MediaKind.LIVE, ext),
            logoUrl = stringOrNull("stream_icon") ?: stringOrNull("icon"),
            group = stringOrNull("category_id"), categoryId = stringOrNull("category_id"),
            number = intOrNull("num") ?: index + 1,
            hasArchive = intOrNull("tv_archive") == 1,
            streamMimeType = mimeForExtension(ext)
        )
    }

    private fun JsonObject.toVod(source: MediaSourceConfig, index: Int, creds: XtreamCredentials, forceId: String? = null): MediaItem {
        val sid = forceId ?: stringOrNull("stream_id") ?: stringOrNull("id").orEmpty()
        val ext = stringOrNull("container_extension").orEmpty().ifBlank { "mp4" }
        val title = stringOrNull("name") ?: stringOrNull("title").orEmpty()
        return MediaItem(
            "${source.id}:movie:$sid", source.id, MediaKind.MOVIE, title,
            streamUrl(creds, sid, MediaKind.MOVIE, ext),
            posterUrl = stringOrNull("stream_icon") ?: stringOrNull("movie_image") ?: stringOrNull("cover"),
            backdropUrl = firstString("backdrop_path"),
            group = stringOrNull("category_id"), categoryId = stringOrNull("category_id"),
            number = intOrNull("num") ?: index + 1,
            plot = stringOrNull("plot") ?: stringOrNull("description"), genre = stringOrNull("genre"),
            cast = stringOrNull("cast"), director = stringOrNull("director"), rating = doubleOrNull("rating"),
            durationSec = longOrNull("duration_secs") ?: durationTextToSeconds(stringOrNull("duration")),
            tmdbId = stringOrNull("tmdb_id"), streamMimeType = mimeForExtension(ext)
        )
    }

    private fun JsonObject.toSeries(source: MediaSourceConfig): MediaItem {
        val sid = stringOrNull("series_id") ?: stringOrNull("id") ?: stringOrNull("stream_id").orEmpty()
        return MediaItem(
            "${source.id}:series:$sid", source.id, MediaKind.SERIES,
            stringOrNull("name") ?: stringOrNull("title").orEmpty(), "",
            posterUrl = stringOrNull("cover") ?: stringOrNull("cover_big") ?: stringOrNull("stream_icon"),
            backdropUrl = firstString("backdrop_path"), plot = stringOrNull("plot") ?: stringOrNull("description"),
            genre = stringOrNull("genre"), rating = doubleOrNull("rating"), categoryId = stringOrNull("category_id"),
            group = stringOrNull("category_id")
        )
    }

    private fun JsonObject.toEpisode(source: MediaSourceConfig, creds: XtreamCredentials, seriesId: String, seasonOverride: Int? = null): MediaItem? {
        val id = stringOrNull("id") ?: stringOrNull("episode_id") ?: return null
        val info = this["info"]?.jsonObjectOrNull()
        val season = intOrNull("season") ?: intOrNull("season_number") ?: intOrNull("season_num")
            ?: info?.intOrNull("season") ?: info?.intOrNull("season_number") ?: seasonOverride
        val episode = intOrNull("episode_num") ?: intOrNull("episode") ?: intOrNull("episode_number") ?: info?.intOrNull("episode_num") ?: info?.intOrNull("episode") ?: info?.intOrNull("episode_number")
        val ext = stringOrNull("container_extension") ?: info?.stringOrNull("container_extension") ?: "mp4"
        return MediaItem(
            "${source.id}:episode:$id", source.id, MediaKind.EPISODE,
            stringOrNull("title") ?: stringOrNull("name").orEmpty(), streamUrl(creds, id, MediaKind.EPISODE, ext),
            posterUrl = stringOrNull("movie_image") ?: stringOrNull("cover") ?: info?.stringOrNull("movie_image") ?: info?.stringOrNull("cover"),
            seasonNumber = season, episodeNumber = episode, seriesId = seriesId,
            plot = stringOrNull("plot") ?: info?.stringOrNull("plot") ?: info?.stringOrNull("description"),
            rating = doubleOrNull("rating") ?: info?.doubleOrNull("rating"),
            durationSec = longOrNull("duration_secs") ?: info?.longOrNull("duration_secs") ?: durationTextToSeconds(info?.stringOrNull("duration")),
            streamMimeType = mimeForExtension(ext)
        )
    }

    private fun mimeForExtension(ext: String): String? = when (ext.lowercase(Locale.ROOT).trimStart('.')) {
        "m3u8", "m3u" -> "application/vnd.apple.mpegurl"
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "ts", "mts" -> "video/mp2t"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        else -> null
    }

    private fun looksLikeEpisodeObject(o: JsonObject): Boolean =
        o.containsKey("id") || o.containsKey("episode_id") || o.containsKey("episode_num") || o.containsKey("episode_number")

    private fun flattenEpisodes(element: JsonElement, inheritedSeason: Int? = null): List<Pair<Int?, JsonObject>> = when (element) {
        is JsonArray -> element.flatMap { child ->
            when (child) {
                is JsonObject -> if (looksLikeEpisodeObject(child)) listOf(inheritedSeason to child) else flattenEpisodes(child, inheritedSeason)
                else -> emptyList()
            }
        }
        is JsonObject -> {
            if (looksLikeEpisodeObject(element)) listOf(inheritedSeason to element)
            else element.entries.flatMap { (key, value) ->
                val season = key.toIntOrNull() ?: inheritedSeason
                flattenEpisodes(value, season)
            }
        }
        else -> emptyList()
    }

}

data class CatalogBundle(
    val liveCategories: List<Category>, val vodCategories: List<Category>, val seriesCategories: List<Category>,
    val live: List<MediaItem>, val movies: List<MediaItem>, val series: List<MediaItem>, val episodes: List<MediaItem> = emptyList()
)

private fun JsonElement?.asObjectList(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it.jsonObjectOrNull() }
    is JsonObject -> {
        val data = this["data"]
        when (data) {
            is JsonArray -> data.mapNotNull { it.jsonObjectOrNull() }
            else -> if (looksLikeItem(this)) listOf(this) else values.mapNotNull { it.jsonObjectOrNull() }
        }
    }
    else -> emptyList()
}

private fun looksLikeItem(o: JsonObject): Boolean = listOf("stream_id", "series_id", "category_id", "category_name", "name", "id").any { o.containsKey(it) }
private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitiveOrNull()?.contentOrNull
private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitiveOrNull()?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitiveOrNull()?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
private fun JsonObject.doubleOrNull(key: String): Double? = this[key]?.jsonPrimitiveOrNull()?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? = this[key]?.jsonObjectOrNull()
private fun JsonElement.jsonObjectOrNull() = runCatching { jsonObject }.getOrNull()
private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
private fun JsonObject.firstString(key: String): String? = this[key]?.let { element ->
    when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { it.jsonPrimitiveOrNull()?.contentOrNull }
        is JsonPrimitive -> element.contentOrNull
        else -> null
    }
}
private fun normalizeSeriesMatch(title: String): String {
    val patterns = listOf(
        Regex("(?i)\\bS\\d{1,3}[\\s._-]*E\\d{1,3}\\b"),
        Regex("(?i)\\bSeason\\s*\\d{1,3}\\s*(?:Episode|Ep|E)\\s*\\d{1,3}\\b"),
        Regex("(?i)\\b\\d{1,3}\\s*x\\s*\\d{1,3}\\b")
    )
    val match = patterns.mapNotNull { it.find(title) }.minByOrNull { it.range.first }
    val base = match?.let { title.substring(0, it.range.first) } ?: title
    return base.replace(Regex("[\\s._:-]+$"), "").trim().lowercase(Locale.ROOT)
}

private fun durationTextToSeconds(s: String?): Long? = s?.split(":")?.let { parts -> if (parts.size in 2..3) parts.fold(0L) { a, p -> a * 60 + (p.toLongOrNull() ?: 0) } else null }
