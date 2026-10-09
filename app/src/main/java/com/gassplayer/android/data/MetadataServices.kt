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
    suspend fun details(id: String, apiKey: String, series: Boolean): MetadataResult? = withContext(Dispatchers.IO) { if (apiKey.isBlank()) return@withContext null; val path = if (series) "tv" else "movie"; api.getJson("https://api.themoviedb.org/3/$path/$id?api_key=${enc(apiKey)}&language=it-IT&append_to_response=credits,external_ids,images").jsonObject.toMeta() }
    private fun JsonObject.toMeta(): MetadataResult? {
        val id = stringOrNull("id") ?: return null
        return MetadataResult(
            id = id,
            title = stringOrNull("title") ?: stringOrNull("name") ?: "",
            rating = doubleOrNull("vote_average"),
            posterUrl = stringOrNull("poster_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
            backdropUrl = stringOrNull("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
            overview = stringOrNull("overview"),
            genres = this["genres"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList(),
            cast = this["credits"]?.jsonObject?.get("cast")?.jsonArray?.take(8)?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList(),
            externalId = this["external_ids"]?.jsonObject?.get("imdb_id")?.jsonPrimitive?.contentOrNull,
            originalTitle = stringOrNull("original_title") ?: stringOrNull("original_name")
        )
    }
    private fun JsonObject.stringOrNull(k: String)=this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.doubleOrNull(k:String)=this[k]?.jsonPrimitive?.doubleOrNull
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
}

class OmdbService(private val api: NetworkApi) {
    suspend fun lookup(title: String, apiKey: String): Ratings? = withContext(Dispatchers.IO) { if (apiKey.isBlank()) return@withContext null; val o = api.getJson("https://www.omdbapi.com/?apikey=${enc(apiKey)}&t=${enc(title)}").jsonObject; Ratings(o["imdbRating"]?.jsonPrimitive?.doubleOrNull, o["Ratings"]?.jsonArray?.firstOrNull { it.jsonObject["Source"]?.jsonPrimitive?.contentOrNull == "Rotten Tomatoes" }?.jsonObject?.get("Value")?.jsonPrimitive?.contentOrNull, o["Metascore"]?.jsonPrimitive?.intOrNull) }
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
    val externalId: String? = null,
    val originalTitle: String? = null
)

data class Ratings(val imdb: Double?, val rottenTomatoes: String?, val metacritic: Int?)
data class SubtitleResult(val id: String, val language: String, val release: String)
data class TraktDeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresInSec: Int)
