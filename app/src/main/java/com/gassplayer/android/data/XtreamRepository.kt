package com.gassplayer.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.net.URI
import java.util.Locale

class XtreamRepository(private val api: NetworkApi) {
    suspend fun authenticate(creds: XtreamCredentials): XtreamAccountInfo {
        val root = api.getJson(playerApi(creds, emptyMap()))
        val obj = root as? JsonObject ?: error("Risposta Xtream non valida")
        val info = obj["user_info"] as? JsonObject
            ?: return XtreamAccountInfo() // some panels omit user_info; the content calls decide
        val auth = info.intOrNull("auth")
        val status = info.stringOrNull("status").orEmpty()
        if (auth == 0) error("Credenziali Xtream non valide")
        if (status.equals("Disabled", true) || status.equals("Banned", true)) error("Account Xtream non attivo ($status)")
        if (status.equals("Expired", true)) error("Account Xtream scaduto")
        return XtreamAccountInfo(
            info.stringOrNull("username").orEmpty(),
            status,
            info.longOrNull("exp_date"),
            info.intOrNull("active_cons"),
            info.intOrNull("max_connections")
        )
    }

    /**
     * Loads the whole Xtream catalog with every request in flight at once (live/VOD/series lists and
     * their categories), streaming each JSON array instead of building a tree in memory, so a
     * 100k-channel panel loads in roughly the time of its slowest single response.
     * Failures are never swallowed silently: if nothing at all can be loaded the thrown error
     * explains why (credentials, HTML page, HTTP status, timeout...).
     */
    suspend fun loadCatalog(source: MediaSourceConfig): CatalogBundle = coroutineScope {
        val creds = XtreamCredentials(serverBase(source.host), source.username.orEmpty(), source.password.orEmpty())
        val authD = async { runCatching { authenticate(creds) } }
        val liveCatD = async { runCatching { categories(creds, "get_live_categories", source) } }
        val vodCatD = async { runCatching { categories(creds, "get_vod_categories", source) } }
        val serCatD = async { runCatching { categories(creds, "get_series_categories", source) } }
        val liveD = async { runCatching { withRetry { api.getJsonObjects(playerApi(creds, mapOf("action" to "get_live_streams"))) { it.toLiveOrNull(source, creds) } } } }
        val vodD = async { runCatching { withRetry { api.getJsonObjects(playerApi(creds, mapOf("action" to "get_vod_streams"))) { it.toVodOrNull(source, creds) } } } }
        val serD = async { runCatching { withRetry { api.getJsonObjects(playerApi(creds, mapOf("action" to "get_series"))) { it.toSeriesOrNull(source) } } } }

        val auth = authD.await()
        val liveCategories = liveCatD.await().getOrDefault(emptyList())
        val vodCategories = vodCatD.await().getOrDefault(emptyList())
        val seriesCategories = serCatD.await().getOrDefault(emptyList())
        val liveR = liveD.await(); val vodR = vodD.await(); val serR = serD.await()
        // Some panels leave whole categories out of the global list. Like the iOS app, only the categories that are
        // completely absent are queried directly (typically none), and categories confirmed empty are remembered 12 h.
        val liveGlobal = liveR.getOrNull()?.let { recoverMissingCategories(source, "get_live_streams", liveCategories, it) { o -> o.toLiveOrNull(source, creds) } } ?: emptyList()
        val vodGlobal = vodR.getOrNull()?.let { recoverMissingCategories(source, "get_vod_streams", vodCategories, it) { o -> o.toVodOrNull(source, creds) } } ?: emptyList()
        var live = liveGlobal.distinctBy { it.id }.mapIndexed { i, m -> if (m.number == null) m.copy(number = i + 1) else m }
        var movies = vodGlobal.distinctBy { it.id }
        var series = serR.getOrDefault(emptyList()).distinctBy { it.id }

        // A subset of panels expose get_series (or even all lists) but return an empty/error
        // response. Their generated M3U still contains every entry, so use it as a targeted
        // fallback instead of failing the entire Xtream source.
        var fallbackEpisodes = emptyList<MediaItem>()
        var fallbackError: Throwable? = null
        if (series.isEmpty() || live.isEmpty() || movies.isEmpty()) {
            runCatching {
                api.readTextStream(xtreamPlaylist(creds), mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, text/plain, */*")) { reader, finalUrl ->
                    M3UParser.parse(source.id, reader, NetworkApi.stripInlineHeaders(finalUrl))
                }
            }.onFailure { fallbackError = it }.onSuccess { parsed ->
                if (series.isEmpty()) series = parsed.filter { it.kind == MediaKind.SERIES }.distinctBy { it.id }
                if (live.isEmpty()) {
                    live = parsed.filter { it.kind == MediaKind.LIVE }.mapIndexed { i, item -> item.copy(number = item.number ?: i + 1) }.distinctBy { it.id }
                }
                if (movies.isEmpty()) movies = parsed.filter { it.kind == MediaKind.MOVIE }.distinctBy { it.id }
                fallbackEpisodes = parsed.filter { it.kind == MediaKind.EPISODE }.distinctBy { it.id }
            }
        }

        if (live.isEmpty() && movies.isEmpty() && series.isEmpty()) {
            val reason = auth.exceptionOrNull() ?: liveR.exceptionOrNull() ?: vodR.exceptionOrNull() ?: serR.exceptionOrNull() ?: fallbackError
            throw IllegalStateException(
                "Xtream '${source.name}': nessun contenuto ricevuto" + (reason?.message?.let { " — $it" } ?: " (il provider ha risposto con liste vuote)")
            )
        }
        CatalogBundle(liveCategories, vodCategories, seriesCategories, live, movies, series, fallbackEpisodes)
    }

    /** iOS RetryPolicy: 3 attempts, 0.5 s doubling, only for transient backend errors (5xx/408/429). */
    private suspend fun <T> withRetry(maxAttempts: Int = 3, initialDelayMs: Long = 500, block: suspend () -> T): T {
        var attempt = 0
        var wait = initialDelayMs
        while (true) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                attempt++
                val transient = e is NetworkApi.HttpStatusException && (e.code in 500..599 || e.code == 408 || e.code == 429)
                if (attempt >= maxAttempts || !transient) throw e
                kotlinx.coroutines.delay(wait)
                wait *= 2
            }
        }
    }

    /**
     * Port of `fetchAllStreamsReportingEmpty`: the global response is authoritative; only categories that are
     * completely absent from it are queried (batches of 6 in parallel). Known-empty categories are skipped when the
     * global list is not empty; a failed request is never treated as "empty".
     */
    private suspend fun recoverMissingCategories(
        source: MediaSourceConfig,
        action: String,
        categories: List<Category>,
        global: List<MediaItem>,
        map: (JsonObject) -> MediaItem?
    ): List<MediaItem> {
        if (categories.isEmpty()) return global
        val creds = XtreamCredentials(serverBase(source.host), source.username.orEmpty(), source.password.orEmpty())
        val globalIds = global.mapNotNull { it.categoryId?.trim()?.takeIf { id -> id.isNotEmpty() } }.toSet()
        val allIds = categories.map { it.id.trim() }.filter { it.isNotEmpty() }.toSet()
        val absent = allIds - globalIds
        val storeKey = "${source.id}|$action"
        val known = EmptyCategoryStore.load(storeKey)
        val skipped = if (global.isEmpty()) emptySet() else absent.intersect(known.ids)
        val missing = (absent - skipped).toList()
        if (missing.isEmpty()) {
            EmptyCategoryStore.save(storeKey, skipped, known)
            return global
        }
        val collected = ArrayList<MediaItem>()
        val confirmedEmpty = HashSet(skipped)
        for (batch in missing.chunked(CATEGORY_BATCH_SIZE)) {
            val results = coroutineScope {
                batch.map { id ->
                    async { id to runCatching { api.getJsonObjects(playerApi(creds, mapOf("action" to action, "category_id" to id))) { o -> map(o) } }.getOrNull() }
                }.map { it.await() }
            }
            for ((id, items) in results) {
                if (items == null) continue // request failed: NOT empty
                if (items.isEmpty()) confirmedEmpty += id else collected += items
            }
        }
        EmptyCategoryStore.save(storeKey, confirmedEmpty, known)
        return (global + collected).distinctBy { it.id }
    }

    /**
     * Counts live + VOD entries without keeping them: used to verify a source. Loading the whole
     * catalog just to read two sizes doubled memory use while the real catalog was already loaded.
     * Returns 0 when the lightweight calls find nothing, so callers can fall back to [loadCatalog].
     */
    suspend fun countContent(source: MediaSourceConfig): Int = coroutineScope {
        val creds = XtreamCredentials(serverBase(source.host), source.username.orEmpty(), source.password.orEmpty())
        authenticate(creds)
        val live = async { runCatching { api.getJsonObjects(playerApi(creds, mapOf("action" to "get_live_streams"))) { 1 }.size }.getOrDefault(0) }
        val vod = async { runCatching { api.getJsonObjects(playerApi(creds, mapOf("action" to "get_vod_streams"))) { 1 }.size }.getOrDefault(0) }
        live.await() + vod.await()
    }

    /**
     * Cheap connectivity/credentials check used when adding a source: authenticates, then counts
     * categories (three tiny requests in parallel). Fails with a readable message if the panel is
     * unreachable, rejects the credentials or answers with something that is not an Xtream API.
     */
    suspend fun probe(source: MediaSourceConfig): Int = coroutineScope {
        val creds = XtreamCredentials(serverBase(source.host), source.username.orEmpty(), source.password.orEmpty())
        val authD = async { runCatching { authenticate(creds) } }
        val catsD = listOf("get_live_categories", "get_vod_categories", "get_series_categories").map { a -> async { runCatching { categories(creds, a, source).size } } }
        val auth = authD.await()
        val counts = catsD.map { it.await() }
        auth.exceptionOrNull()?.let { if (counts.all { c -> c.getOrDefault(0) == 0 }) throw it }
        val total = counts.sumOf { it.getOrDefault(0) }
        if (total == 0 && auth.isFailure) throw (auth.exceptionOrNull() ?: IllegalStateException("Risposta non valida"))
        if (total == 0 && auth.getOrNull()?.status.isNullOrBlank()) {
            throw counts.firstNotNullOfOrNull { it.exceptionOrNull() } ?: IllegalStateException("Nessuna categoria ricevuta: controlla host, porta e credenziali")
        }
        total
    }

    private suspend fun categories(creds: XtreamCredentials, action: String, source: MediaSourceConfig): List<Category> =
        api.getJsonObjects(playerApi(creds, mapOf("action" to action))) { o ->
            val id = o.stringOrNull("category_id") ?: return@getJsonObjects null
            Category(id, o.stringOrNull("category_name").orEmpty().ifBlank { id }, source.id)
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
            val target = normalizeSeriesMatch(seriesName)
            // Streamed + filtered while parsing: only this series' episodes are ever kept in memory.
            api.readTextStream(playlistUrl, mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, text/plain, */*")) { reader, finalUrl ->
                M3UParser.parse(source.id, reader, NetworkApi.stripInlineHeaders(finalUrl), keep = {
                    it.kind == MediaKind.EPISODE &&
                        (normalizeSeriesMatch(it.title) == target || normalizeSeriesMatch(it.group.orEmpty()) == target)
                })
            }
                .filter { it.kind == MediaKind.EPISODE }
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

    private fun serverBase(rawHost: String): String = Companion.serverBase(rawHost)

    companion object {
        /** Normalises whatever the user pasted (bare host, host:port, full get.php / player_api.php URL). */
        fun serverBase(rawHost: String): String {
            var raw = rawHost.trim().replace(" ", "")
            require(raw.isNotBlank()) { "Host Xtream mancante" }
            if (!raw.contains("://")) raw = "http://$raw"
            val uri = runCatching { URI(raw.substringBefore('?').substringBefore('#')) }.getOrNull()
                ?: error("Host Xtream non valido")
            val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: "http"
            val authority = uri.rawAuthority ?: error("Host Xtream non valido")
            var path = uri.rawPath.orEmpty().trimEnd('/')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                if (path.lowercase(Locale.ROOT).endsWith(suffix)) path = path.dropLast(suffix.length)
            }
            return "$scheme://$authority${if (path.isBlank()) "" else "/${path.trim('/')}"}".trimEnd('/')
        }

        /** Extracts username/password when the user pasted a full `get.php?username=..&password=..` URL. */
        fun credentialsFromUrl(rawHost: String): Pair<String, String>? {
            val q = rawHost.substringAfter('?', "")
            if (q.isBlank()) return null
            val map = q.split('&').mapNotNull { part ->
                val k = part.substringBefore('=', ""); val v = part.substringAfter('=', "")
                if (k.isBlank()) null else k.lowercase(Locale.ROOT) to runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            }.toMap()
            val u = map["username"] ?: return null
            val p = map["password"] ?: return null
            return u to p
        }
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun JsonObject.toLiveOrNull(source: MediaSourceConfig, creds: XtreamCredentials): MediaItem? {
        val sid = stringOrNull("stream_id") ?: stringOrNull("id") ?: return null
        if (sid.isBlank()) return null
        return toLive(source, 0, creds).let { if (stringOrNull("num") == null) it.copy(number = null) else it }
    }
    private fun JsonObject.toVodOrNull(source: MediaSourceConfig, creds: XtreamCredentials): MediaItem? {
        val sid = stringOrNull("stream_id") ?: stringOrNull("id") ?: return null
        if (sid.isBlank()) return null
        return toVod(source, 0, creds).let { if (stringOrNull("num") == null) it.copy(number = null) else it }
    }
    private fun JsonObject.toSeriesOrNull(source: MediaSourceConfig): MediaItem? {
        val sid = stringOrNull("series_id") ?: stringOrNull("id") ?: stringOrNull("stream_id") ?: return null
        if (sid.isBlank()) return null
        return toSeries(source)
    }

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
            metadataTag = stringOrNull("epg_channel_id"),
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
private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it != "null" }?.let { it.trim() }?.takeIf { it.isNotEmpty() }
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

private const val CATEGORY_BATCH_SIZE = 6

/** Categories confirmed empty by the provider, remembered 12 h per source+list (iOS: `emptyCategories` defaults). */
object EmptyCategoryStore {
    private const val TTL_MS = 12 * 3600_000L
    class Known(val ids: Set<String>, val savedAt: Long?)

    private var prefs: android.content.SharedPreferences? = null
    fun init(context: android.content.Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("gassplayer_empty_categories", android.content.Context.MODE_PRIVATE)
    }

    fun load(key: String): Known {
        val raw = prefs?.getString(key, null) ?: return Known(emptySet(), null)
        val savedAt = raw.substringBefore('|').toLongOrNull() ?: return Known(emptySet(), null)
        if (System.currentTimeMillis() - savedAt >= TTL_MS) return Known(emptySet(), null)
        return Known(raw.substringAfter('|', "").split(',').filter { it.isNotEmpty() }.toSet(), savedAt)
    }

    fun save(key: String, ids: Set<String>, previous: Known) {
        val editor = prefs?.edit() ?: return
        if (ids.isEmpty()) { editor.remove(key).apply(); return }
        // No new verification (all already known): keep the original date so the 12 h expiry does not slide.
        val savedAt = (if (ids == previous.ids) previous.savedAt else null) ?: System.currentTimeMillis()
        editor.putString(key, "$savedAt|${ids.joinToString(",")}").apply()
    }
}
