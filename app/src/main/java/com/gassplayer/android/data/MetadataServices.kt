package com.gassplayer.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URLEncoder

class TmdbService(private val api: NetworkApi) {
    suspend fun search(query: String, apiKey: String): List<MetadataResult> = withContext(Dispatchers.IO) { if (apiKey.isBlank()) return@withContext emptyList(); val root = api.getJson("https://api.themoviedb.org/3/search/multi?api_key=${enc(apiKey)}&query=${enc(query)}&language=it-IT"); root.jsonObject["results"]?.jsonArray?.mapNotNull { it.jsonObject.toMeta() } ?: emptyList() }
    /** Trending rails used by the iOS HomeView, returned as metadata cards to be matched against the active playlist. */
    suspend fun trending(mediaType: String, apiKey: String, window: String = "week"): List<MetadataResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val path = if (mediaType.equals("tv", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)) "tv" else "movie"
        val period = if (window == "day") "day" else "week"
        val root = api.getJson("https://api.themoviedb.org/3/trending/$path/$period?api_key=${enc(apiKey)}&language=it-IT")
        root.jsonObject["results"]?.jsonArray?.mapNotNull { it.jsonObject.toMeta() } ?: emptyList()
    }
    private val trendingRailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<TmdbTrendingItem>>>()
    private val genreNamesCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, String>>()

    /**
     * TMDBService.trending(isSeries:limit:) on iOS: the week's 20 most trending titles, pages 1-2 until the
     * limit, only titles with a backdrop (16:9 card), first genre in Italian, cached for 30 minutes.
     * Throws when nothing could be loaded, so the rail can show "Impossibile caricare…".
     */
    suspend fun trendingRail(isSeries: Boolean, apiKey: String, limit: Int = 20): List<TmdbTrendingItem> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Chiave API TMDB mancante" }
        val key = if (isSeries) "tv" else "movie"
        val cacheKey = "$key|$apiKey"
        trendingRailCache[cacheKey]?.let { (date, items) ->
            if (System.currentTimeMillis() - date < 1_800_000L) return@withContext items.take(limit)
        }
        val genres = genreNames(key, apiKey)
        val items = ArrayList<TmdbTrendingItem>()
        val seen = HashSet<String>()
        var lastError: Throwable? = null
        for (page in 1..2) {
            if (items.size >= limit) break
            val root = try {
                api.getJson("https://api.themoviedb.org/3/trending/$key/week?api_key=${enc(apiKey)}&language=it-IT&page=$page")
            } catch (error: Throwable) {
                lastError = error
                break
            }
            root.jsonObject["results"]?.jsonArray?.forEach { element ->
                val raw = element.jsonObject
                val id = raw.stringOrNull("id") ?: return@forEach
                val backdrop = raw.stringOrNull("backdrop_path")?.takeIf { it.isNotBlank() } ?: return@forEach
                val title = (raw.stringOrNull("title") ?: raw.stringOrNull("name"))?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                if (!seen.add(id)) return@forEach
                val date = raw.stringOrNull("release_date") ?: raw.stringOrNull("first_air_date")
                items += TmdbTrendingItem(
                    id = id,
                    title = title,
                    originalTitle = raw.stringOrNull("original_title") ?: raw.stringOrNull("original_name"),
                    genre = raw["genre_ids"]?.jsonArray?.firstNotNullOfOrNull { element -> element.jsonPrimitive.intOrNull?.let { genres[it] } },
                    backdropUrl = "https://image.tmdb.org/t/p/w780$backdrop",
                    year = date?.takeIf { it.length >= 4 }?.take(4),
                    isSeries = isSeries
                )
            }
        }
        if (items.isEmpty()) throw (lastError ?: IllegalStateException("Nessun titolo di tendenza"))
        trendingRailCache[cacheKey] = System.currentTimeMillis() to items
        items.take(limit)
    }

    private suspend fun genreNames(key: String, apiKey: String): Map<Int, String> {
        genreNamesCache[key]?.let { return it }
        val map = runCatching {
            api.getJson("https://api.themoviedb.org/3/genre/$key/list?api_key=${enc(apiKey)}&language=it-IT")
                .jsonObject["genres"]?.jsonArray?.mapNotNull { element ->
                    val genre = element.jsonObject
                    val id = genre["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val name = genre["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    id to name
                }?.toMap().orEmpty()
        }.getOrDefault(emptyMap())
        if (map.isNotEmpty()) genreNamesCache[key] = map
        return map
    }

    /** Type-specific title search used by detail screens to avoid selecting a same-title film/series/person. */
    suspend fun searchMovies(query: String, apiKey: String): List<MetadataResult> = searchByType(query, apiKey, "movie")
    suspend fun searchSeries(query: String, apiKey: String): List<MetadataResult> = searchByType(query, apiKey, "tv")
    private suspend fun searchByType(query: String, apiKey: String, type: String): List<MetadataResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || query.isBlank()) return@withContext emptyList()
        val root = api.getJson("https://api.themoviedb.org/3/search/$type?api_key=${enc(apiKey)}&query=${enc(query)}&language=it-IT")
        root.jsonObject["results"]?.jsonArray?.mapNotNull { it.jsonObject.toMeta() } ?: emptyList()
    }
    suspend fun details(id: String, apiKey: String, series: Boolean): MetadataResult? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val path = if (series) "tv" else "movie"
        api.getJson("https://api.themoviedb.org/3/$path/$id?api_key=${enc(apiKey)}&language=it-IT&append_to_response=credits,external_ids,images&include_image_language=it,en,null").jsonObject.toMeta()
    }
    /** Episode metadata fallback mirroring SeriesEpisodesView.swift (TMDB fills missing provider synopsis/stills). */
    suspend fun seasonEpisodes(tvId: String, season: Int, apiKey: String): List<TmdbEpisodeResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || tvId.isBlank() || season < 0) return@withContext emptyList()
        val root = api.getJson("https://api.themoviedb.org/3/tv/${enc(tvId)}/season/$season?api_key=${enc(apiKey)}&language=it-IT")
        root.jsonObject["episodes"]?.jsonArray?.mapNotNull { element ->
            val obj = element.jsonObject
            val number = obj["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            TmdbEpisodeResult(
                seasonNumber = obj["season_number"]?.jsonPrimitive?.intOrNull ?: season,
                episodeNumber = number,
                title = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                overview = obj["overview"]?.jsonPrimitive?.contentOrNull,
                stillUrl = obj["still_path"]?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w780$it" },
                airDate = obj["air_date"]?.jsonPrimitive?.contentOrNull,
                runtimeMinutes = obj["runtime"]?.jsonPrimitive?.intOrNull
            )
        } ?: emptyList()
    }
    private fun JsonObject.toMeta(): MetadataResult? {
        val id = stringOrNull("id") ?: return null
        val logos = this["images"]?.jsonObject?.get("logos")?.jsonArray.orEmpty()
        val logo = logos.mapNotNull { it.jsonObject }.sortedBy { image ->
            when (image["iso_639_1"]?.jsonPrimitive?.contentOrNull) { "it" -> 0; "en" -> 1; null -> 2; else -> 3 }
        }.firstOrNull()
        return MetadataResult(
            id = id,
            title = stringOrNull("title") ?: stringOrNull("name") ?: "",
            year = (stringOrNull("release_date") ?: stringOrNull("first_air_date"))?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } },
            rating = doubleOrNull("vote_average"),
            posterUrl = stringOrNull("poster_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
            backdropUrl = stringOrNull("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
            overview = stringOrNull("overview"),
            genres = this["genres"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList(),
            cast = this["credits"]?.jsonObject?.get("cast")?.jsonArray?.take(12)?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList(),
            castMembers = this["credits"]?.jsonObject?.get("cast")?.jsonArray?.take(12)?.mapNotNull { element ->
                val person = element.jsonObject
                val name = person["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                MetadataCastMember(
                    name = name,
                    character = person["character"]?.jsonPrimitive?.contentOrNull,
                    profileUrl = person["profile_path"]?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w185$it" }
                )
            } ?: emptyList(),
            externalId = this["external_ids"]?.jsonObject?.get("imdb_id")?.jsonPrimitive?.contentOrNull,
            originalTitle = stringOrNull("original_title") ?: stringOrNull("original_name"),
            logoUrl = logo?.get("file_path")?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w500$it" }
        )
    }
    private fun JsonObject.stringOrNull(k: String)=this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.doubleOrNull(k:String)=this[k]?.jsonPrimitive?.doubleOrNull
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
}

class OmdbService(private val api: NetworkApi) {
    suspend fun lookup(title: String, apiKey: String, imdbId: String? = null): Ratings? = withContext(Dispatchers.IO) { if (apiKey.isBlank()) return@withContext null; val o = api.getJson("https://www.omdbapi.com/?apikey=${enc(apiKey)}&" + (if (!imdbId.isNullOrBlank()) "i=${enc(imdbId)}" else "t=${enc(title)}")).jsonObject; Ratings(o["imdbRating"]?.jsonPrimitive?.doubleOrNull, o["Ratings"]?.jsonArray?.firstOrNull { it.jsonObject["Source"]?.jsonPrimitive?.contentOrNull == "Rotten Tomatoes" }?.jsonObject?.get("Value")?.jsonPrimitive?.contentOrNull, o["Metascore"]?.jsonPrimitive?.intOrNull) }
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
}

class OpenSubtitlesService(private val api: NetworkApi) {
    suspend fun search(query: String, apiKey: String): List<SubtitleResult> = withContext(Dispatchers.IO) { if (apiKey.isBlank()) return@withContext emptyList(); val root = api.getJson("https://api.opensubtitles.com/api/v1/subtitles?query=${URLEncoder.encode(query, "UTF-8")}", mapOf("Api-Key" to apiKey, "Accept" to "application/json")); root.jsonObject["data"]?.jsonArray?.mapNotNull { el -> val o = el.jsonObject; val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null; SubtitleResult(id, o["attributes"]?.jsonObject?.get("language")?.jsonPrimitive?.contentOrNull.orEmpty(), o["attributes"]?.jsonObject?.get("release")?.jsonPrimitive?.contentOrNull.orEmpty()) } ?: emptyList() }
}

class TraktService(private val api: NetworkApi) {
    suspend fun ratings(title: String, clientId: String): Double? = withContext(Dispatchers.IO) { if (clientId.isBlank()) return@withContext null; val o = api.getJson("https://api.trakt.tv/search/movie?query=${URLEncoder.encode(title, "UTF-8")}&limit=1", mapOf("trakt-api-version" to "2", "trakt-api-key" to clientId)).jsonArray.firstOrNull()?.jsonObject ?: return@withContext null; o["score"]?.jsonPrimitive?.doubleOrNull?.times(10) }
    suspend fun deviceCode(clientId: String, clientSecret: String): TraktDeviceCode? = withContext(Dispatchers.IO) { if (clientId.isBlank() || clientSecret.isBlank()) return@withContext null; val o = api.postJson("https://trakt.tv/oauth/device/code", JsonStore.json.encodeToString(kotlinx.serialization.json.buildJsonObject { put("client_id", clientId); }), mapOf("Content-Type" to "application/json")); TraktDeviceCode(o.jsonObject["device_code"]?.jsonPrimitive?.contentOrNull.orEmpty(), o.jsonObject["user_code"]?.jsonPrimitive?.contentOrNull.orEmpty(), o.jsonObject["verification_url"]?.jsonPrimitive?.contentOrNull.orEmpty(), o.jsonObject["expires_in"]?.jsonPrimitive?.intOrNull ?: 600) }
    suspend fun pollDevice(clientId: String, clientSecret: String, deviceCode: String): TraktAccount? = withContext(Dispatchers.IO) { if (clientId.isBlank() || clientSecret.isBlank() || deviceCode.isBlank()) return@withContext null; val o = api.postJson("https://trakt.tv/oauth/device/token", JsonStore.json.encodeToString(buildJsonObject { put("code", deviceCode); put("client_id", clientId); put("client_secret", clientSecret) })); val access=o.jsonObject["access_token"]?.jsonPrimitive?.contentOrNull ?: return@withContext null; TraktAccount(access, o.jsonObject["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(), "") }
    suspend fun scrobbleStart(account: TraktAccount, clientId: String, item: MediaItem, progress: Double) { scrobble("start", account, clientId, item, progress) }
    suspend fun scrobbleStop(account: TraktAccount, clientId: String, item: MediaItem, progress: Double) { scrobble("stop", account, clientId, item, progress) }
    private suspend fun scrobble(action:String, account:TraktAccount, clientId:String, item:MediaItem, progress:Double){ if(account.accessToken.isBlank()||clientId.isBlank()) return; api.postJson("https://api.trakt.tv/scrobble/$action", buildJsonObject { put("progress", progress.coerceIn(0.0,100.0)); put(if(item.kind==MediaKind.MOVIE)"movie" else "episode", buildJsonObject { put("title", item.title); item.tmdbId?.let{put("ids", buildJsonObject{put("tmdb", it.toIntOrNull() ?: 0)})} }) }.toString(), mapOf("Content-Type" to "application/json","trakt-api-key" to clientId,"trakt-api-version" to "2","Authorization" to "Bearer ${account.accessToken}")) }
}

@kotlinx.serialization.Serializable
data class MetadataResult(
    val id: String,
    val title: String,
    val rating: Double? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val castMembers: List<MetadataCastMember> = emptyList(),
    val externalId: String? = null,
    val originalTitle: String? = null,
    val logoUrl: String? = null,
    /** Release / first-air year from TMDB, used to match trending titles to the playlist accurately. */
    val year: String? = null
)

/** TMDBTrendingItem on iOS: one card of "Film/Serie di tendenza". */
data class TmdbTrendingItem(
    val id: String,
    val title: String,
    val originalTitle: String?,
    /** First genre (Italian), shown under the title. */
    val genre: String?,
    /** Horizontal 16:9 image for the card. */
    val backdropUrl: String?,
    val year: String?,
    val isSeries: Boolean
) {
    /** Metadata handed to the catalogue matcher and shown at once by the detail sheet. */
    fun toMetadata(): MetadataResult = MetadataResult(
        id = id,
        title = title,
        backdropUrl = backdropUrl,
        genres = listOfNotNull(genre),
        originalTitle = originalTitle,
        year = year
    )
}

@kotlinx.serialization.Serializable
data class MetadataCastMember(
    val name: String,
    val character: String? = null,
    val profileUrl: String? = null
)

data class TmdbEpisodeResult(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val overview: String? = null,
    val stillUrl: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null
)

data class Ratings(val imdb: Double?, val rottenTomatoes: String?, val metacritic: Int?)
data class SubtitleResult(val id: String, val language: String, val release: String)
data class TraktDeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresInSec: Int)
