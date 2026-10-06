package com.iamgasgass.gassplayer.network

import android.util.Base64
import com.iamgasgass.gassplayer.data.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class XtreamClient(private val http: HttpClient) {
    private fun api(source: MediaSource, action: String): String =
        "${source.url.trimEnd('/')}/player_api.php?username=${enc(source.username)}&password=${enc(source.password)}&action=$action"

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    suspend fun authenticate(source: MediaSource): Boolean {
        require(source.type == SourceType.XTREAM) { "Sorgente non Xtream" }
        val root = Json.parseToJsonElement(
            http.text(
                "${source.url.trimEnd('/')}/player_api.php?username=${enc(source.username)}&password=${enc(source.password)}"
            )
        ).jsonObject
        return root["user_info"]?.jsonObject?.get("auth")?.jsonPrimitive?.intOrNull == 1
    }

    suspend fun catalog(source: MediaSource): Catalog {
        check(authenticate(source)) { "Credenziali Xtream non valide o account disabilitato" }

        val liveCats = categories(source, "get_live_categories")
        val vodCats = categories(source, "get_vod_categories")
        val seriesCats = categories(source, "get_series_categories")

        val channels = http.jsonArray(api(source, "get_live_streams")).mapIndexed { index, element ->
            val obj = element.jsonObject
            val id = obj.string("stream_id")
            Channel(
                id = id,
                name = obj.string("name").ifBlank { "Canale ${index + 1}" },
                streamUrl = "${source.url.trimEnd('/')}/live/${enc(source.username)}/${enc(source.password)}/$id.ts",
                logo = obj.string("stream_icon"),
                group = liveCats.firstOrNull { it.id == obj.string("category_id") }?.name.orEmpty(),
                epgId = obj.string("epg_channel_id"),
                number = obj.int("num", index + 1),
                catchup = obj.int("tv_archive", 0) == 1,
                sourceId = source.id,
            )
        }

        val movies = http.jsonArray(api(source, "get_vod_streams")).map { element ->
            val obj = element.jsonObject
            val id = obj.string("stream_id")
            val extension = obj.string("container_extension").ifBlank { "mp4" }
            Movie(
                id = id,
                name = obj.string("name"),
                streamUrl = "${source.url.trimEnd('/')}/movie/${enc(source.username)}/${enc(source.password)}/$id.$extension",
                poster = obj.string("stream_icon"),
                categoryId = obj.string("category_id"),
                rating = obj.double("rating"),
                year = obj.string("year"),
                plot = obj.string("plot"),
                extension = extension,
                sourceId = source.id,
            )
        }

        val series = http.jsonArray(api(source, "get_series")).map { element ->
            val obj = element.jsonObject
            Series(
                id = obj.string("series_id"),
                name = obj.string("name"),
                poster = obj.string("cover"),
                categoryId = obj.string("category_id"),
                rating = obj.double("rating"),
                year = obj.string("year"),
                plot = obj.string("plot"),
                sourceId = source.id,
            )
        }

        return Catalog(
            liveCategories = liveCats,
            vodCategories = vodCats,
            seriesCategories = seriesCats,
            channels = channels,
            movies = movies,
            series = series,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun episodes(source: MediaSource, seriesId: String): List<Episode> {
        val root = Json.parseToJsonElement(
            http.text(api(source, "get_series_info") + "&series_id=${enc(seriesId)}")
        ).jsonObject

        return root["episodes"]?.jsonObject
            ?.flatMap { (season, list) ->
                list.jsonArray.mapIndexed { index, element ->
                    val obj = element.jsonObject
                    val id = obj.string("id")
                    val extension = obj.string("container_extension").ifBlank { "mp4" }
                    val info = obj["info"]?.jsonObject
                    Episode(
                        id = id,
                        title = obj.string("title").ifBlank { "Episodio ${index + 1}" },
                        season = season.toIntOrNull() ?: 1,
                        episode = obj.int("episode_num", index + 1),
                        streamUrl = "${source.url.trimEnd('/')}/series/${enc(source.username)}/${enc(source.password)}/$id.$extension",
                        image = info?.string("movie_image").orEmpty(),
                        plot = info?.string("plot").orEmpty(),
                        duration = info?.string("duration").orEmpty(),
                        extension = extension,
                    )
                }
            }
            .orEmpty()
            .sortedWith(compareBy<Episode> { it.season }.thenBy { it.episode })
    }

    suspend fun shortEpg(source: MediaSource, streamId: String, limit: Int = 30): List<EpgProgramme> {
        val root = Json.parseToJsonElement(
            http.text(
                api(source, "get_short_epg") +
                    "&stream_id=${enc(streamId)}&limit=${limit.coerceIn(1, 100)}"
            )
        ).jsonObject

        return root["epg_listings"]?.jsonArray
            ?.map { element ->
                val obj = element.jsonObject
                EpgProgramme(
                    channelId = streamId,
                    title = decodeBase64(obj.string("title")),
                    description = decodeBase64(obj.string("description")),
                    startMillis = obj.long("start_timestamp") * 1000,
                    endMillis = obj.long("stop_timestamp") * 1000,
                )
            }
            .orEmpty()
            .sortedBy { it.startMillis }
    }

    private suspend fun categories(source: MediaSource, action: String): List<Category> =
        http.jsonArray(api(source, action)).map {
            val obj = it.jsonObject
            Category(
                id = obj.string("category_id"),
                name = obj.string("category_name"),
                parentId = obj.int("parent_id"),
            )
        }

    private fun decodeBase64(value: String): String = runCatching {
        String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8)
    }.getOrDefault(value)
}

private suspend fun HttpClient.jsonArray(url: String): JsonArray {
    val element = Json.parseToJsonElement(text(url))
    return element as? JsonArray ?: error("Risposta JSON non valida")
}

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String, default: Int = 0): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: default

private fun JsonObject.long(key: String): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: 0L

private fun JsonObject.double(key: String): Double =
    this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
