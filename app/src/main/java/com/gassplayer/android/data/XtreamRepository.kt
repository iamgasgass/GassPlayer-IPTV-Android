package com.gassplayer.android.data

import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.Locale

class XtreamRepository(private val api: NetworkApi) {
    suspend fun authenticate(creds: XtreamCredentials): XtreamAccountInfo {
        val root = api.getJson(playerApi(creds, emptyMap()))
        val info = root.jsonObject["user_info"]?.jsonObject ?: error("Risposta Xtream non valida")
        val status = info.stringOrNull("status").orEmpty()
        if (status.isBlank() || status.equals("Disabled", true)) error("Account Xtream non attivo")
        return XtreamAccountInfo(info.stringOrNull("username").orEmpty(), status, info.longOrNull("exp_date"), info.intOrNull("active_cons"), info.intOrNull("max_connections"))
    }

    suspend fun loadCatalog(source: MediaSourceConfig): CatalogBundle {
        val creds = XtreamCredentials(source.host, source.username.orEmpty(), source.password.orEmpty())
        authenticate(creds)
        val liveCategories = api.getJson(playerApi(creds, mapOf("action" to "get_live_categories"))).asArraySafe().map { Category(it.stringOrNull("category_id").orEmpty(), it.stringOrNull("category_name").orEmpty(), source.id) }
        val vodCategories = api.getJson(playerApi(creds, mapOf("action" to "get_vod_categories"))).asArraySafe().map { Category(it.stringOrNull("category_id").orEmpty(), it.stringOrNull("category_name").orEmpty(), source.id) }
        val seriesCategories = api.getJson(playerApi(creds, mapOf("action" to "get_series_categories"))).asArraySafe().map { Category(it.stringOrNull("category_id").orEmpty(), it.stringOrNull("category_name").orEmpty(), source.id) }
        val live = api.getJson(playerApi(creds, mapOf("action" to "get_live_streams"))).asArraySafe().mapIndexed { i, o -> o.toLive(source, i, creds) }.distinctBy { it.id }
        val movies = api.getJson(playerApi(creds, mapOf("action" to "get_vod_streams"))).asArraySafe().mapIndexed { i, o -> o.toVod(source, i, creds) }.distinctBy { it.id }
        val series = api.getJson(playerApi(creds, mapOf("action" to "get_series"))).asArraySafe().map { it.toSeries(source) }.distinctBy { it.id }
        return CatalogBundle(liveCategories, vodCategories, seriesCategories, live, movies, series)
    }

    suspend fun vodDetail(source: MediaSourceConfig, vodId: String): MediaItem? {
        val creds = XtreamCredentials(source.host, source.username.orEmpty(), source.password.orEmpty())
        val o = api.getJson(playerApi(creds, mapOf("action" to "get_vod_info", "vod_id" to vodId))).jsonObject["info"]?.jsonObject ?: return null
        return o.toVod(source, 0, creds, forceId = vodId)
    }

    suspend fun seriesEpisodes(source: MediaSourceConfig, seriesId: String): List<MediaItem> {
        val creds = XtreamCredentials(source.host, source.username.orEmpty(), source.password.orEmpty())
        val root = api.getJson(playerApi(creds, mapOf("action" to "get_series_info", "series_id" to seriesId))).jsonObject
        val seasons = root["episodes"]?.jsonObject ?: return emptyList()
        return seasons.values.flatMap { seasonValue -> seasonValue.asJsonArrayOrEmpty().mapNotNull { e -> e.jsonObject.toEpisode(source, creds, seriesId) } }.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
    }

    fun streamUrl(creds: XtreamCredentials, id: String, kind: MediaKind, extension: String = "ts"): String {
        val base = creds.host.trimEnd('/')
        val u = enc(creds.username); val p = enc(creds.password)
        return when (kind) {
            MediaKind.LIVE -> "$base/live/$u/$p/$id.$extension"
            MediaKind.MOVIE -> "$base/movie/$u/$p/$id.$extension"
            MediaKind.EPISODE -> "$base/series/$u/$p/$id.$extension"
            MediaKind.SERIES -> "$base/series/$u/$p/$id.$extension"
        }
    }

    private fun playerApi(creds: XtreamCredentials, params: Map<String, String>): String {
        val pairs = linkedMapOf("username" to creds.username, "password" to creds.password) + params
        return "${creds.host.trimEnd('/')}/player_api.php?" + pairs.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun JsonObject.toLive(source: MediaSourceConfig, index: Int, creds: XtreamCredentials): MediaItem {
        val sid = stringOrNull("stream_id").orEmpty()
        val ext = stringOrNull("container_extension").orEmpty().ifBlank { "ts" }
        return MediaItem("${source.id}:live:$sid", source.id, MediaKind.LIVE, stringOrNull("name").orEmpty(), streamUrl(creds, sid, MediaKind.LIVE, ext), logoUrl = stringOrNull("stream_icon") ?: stringOrNull("icon"), group = stringOrNull("category_id"), categoryId = stringOrNull("category_id"), number = intOrNull("num") ?: index + 1, hasArchive = intOrNull("tv_archive") == 1)
    }
    private fun JsonObject.toVod(source: MediaSourceConfig, index: Int, creds: XtreamCredentials, forceId: String? = null): MediaItem {
        val sid = forceId ?: stringOrNull("stream_id").orEmpty(); val ext = stringOrNull("container_extension").orEmpty().ifBlank { "mp4" }
        return MediaItem("${source.id}:movie:$sid", source.id, MediaKind.MOVIE, stringOrNull("name") ?: stringOrNull("title").orEmpty(), streamUrl(creds, sid, MediaKind.MOVIE, ext), posterUrl = stringOrNull("stream_icon") ?: stringOrNull("movie_image"), backdropUrl = stringOrNull("backdrop_path"), group = stringOrNull("category_id"), categoryId = stringOrNull("category_id"), number = intOrNull("num") ?: index + 1, plot = stringOrNull("plot") ?: stringOrNull("description"), genre = stringOrNull("genre"), cast = stringOrNull("cast"), director = stringOrNull("director"), rating = doubleOrNull("rating"), durationSec = longOrNull("duration_secs") ?: durationTextToSeconds(stringOrNull("duration")), tmdbId = stringOrNull("tmdb_id"))
    }
    private fun JsonObject.toSeries(source: MediaSourceConfig): MediaItem = MediaItem("${source.id}:series:${stringOrNull("series_id").orEmpty()}", source.id, MediaKind.SERIES, stringOrNull("name").orEmpty(), "", posterUrl = stringOrNull("cover"), backdropUrl = stringOrNull("backdrop_path"), plot = stringOrNull("plot"), genre = stringOrNull("genre"), rating = doubleOrNull("rating"), categoryId = stringOrNull("category_id"))
    private fun JsonObject.toEpisode(source: MediaSourceConfig, creds: XtreamCredentials, seriesId: String): MediaItem? {
        val id = stringOrNull("id") ?: return null
        val season = intOrNull("season"); val episode = intOrNull("episode_num")
        val ext = stringOrNull("container_extension").orEmpty().ifBlank { "mp4" }
        return MediaItem("${source.id}:episode:$id", source.id, MediaKind.EPISODE, stringOrNull("title").orEmpty(), streamUrl(creds, id, MediaKind.EPISODE, ext), posterUrl = stringOrNull("info")?.takeIf { it.startsWith("http") }, seasonNumber = season, episodeNumber = episode, seriesId = seriesId, plot = stringOrNull("plot"), durationSec = longOrNull("duration_secs"))
    }
}

data class CatalogBundle(val liveCategories: List<Category>, val vodCategories: List<Category>, val seriesCategories: List<Category>, val live: List<MediaItem>, val movies: List<MediaItem>, val series: List<MediaItem>)

private fun JsonElement?.asArraySafe(): List<JsonObject> = when (this) { is JsonArray -> mapNotNull { it.jsonObjectOrNull() }; is JsonObject -> listOf(this); else -> emptyList() }
private fun JsonElement?.asJsonArrayOrEmpty(): JsonArray = this as? JsonArray ?: buildJsonArray { }
private fun JsonElement.jsonObjectOrNull() = runCatching { jsonObject }.getOrNull()
private fun JsonObject.stringOrNull(key: String): String? = this[key]?.let { el -> el.jsonPrimitive.contentOrNull ?: el.jsonPrimitive.longOrNull?.toString() }
private fun JsonObject.intOrNull(key: String): Int? = this[key]?.let { it.jsonPrimitive.intOrNull ?: it.jsonPrimitive.contentOrNull?.toDoubleOrNull()?.toInt() }
private fun JsonObject.longOrNull(key: String): Long? = this[key]?.let { it.jsonPrimitive.longOrNull ?: it.jsonPrimitive.contentOrNull?.toLongOrNull() }
private fun JsonObject.doubleOrNull(key: String): Double? = this[key]?.let { it.jsonPrimitive.doubleOrNull ?: it.jsonPrimitive.contentOrNull?.toDoubleOrNull() }
private fun durationTextToSeconds(s: String?): Long? = s?.split(":")?.let { parts -> if (parts.size in 2..3) parts.fold(0L) { a, p -> a * 60 + (p.toLongOrNull() ?: 0) } else null }
