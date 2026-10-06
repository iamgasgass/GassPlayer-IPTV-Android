package com.iamgasgass.gassplayer.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.iamgasgass.gassplayer.network.HttpClient
import com.iamgasgass.gassplayer.network.XtreamClient
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val Context.dataStore by preferencesDataStore("gassplayer")

class AppStore(
    private val context: Context,
    private val http: HttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val xtream = XtreamClient(http)

    private val sourcesKey = stringPreferencesKey("sources")
    private val favKey = stringPreferencesKey("favorites")
    private val progressKey = stringPreferencesKey("progress")

    suspend fun sources(): List<MediaSource> =
        decode(context.dataStore.data.first()[sourcesKey], emptyList())

    suspend fun saveSources(value: List<MediaSource>) =
        edit(sourcesKey, json.encodeToString(value))

    suspend fun addSource(
        name: String,
        type: SourceType,
        url: String,
        user: String = "",
        pass: String = "",
        epgUrl: String = "",
    ): MediaSource {
        val normalizedUrl = normalizeUrl(url)
        require(normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
            "URL non valida"
        }
        if (type == SourceType.XTREAM) {
            require(user.isNotBlank()) { "Username obbligatorio per Xtream" }
            require(pass.isNotBlank()) { "Password obbligatoria per Xtream" }
        }
        val source = MediaSource(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Nuova sorgente" },
            type = type,
            url = normalizedUrl,
            username = user.trim(),
            password = pass,
            epgUrl = normalizeUrl(epgUrl),
        )
        saveSources(sources() + source)
        return source
    }

    suspend fun removeSource(id: String) {
        val source = sources().firstOrNull { it.id == id }
        saveSources(sources().filterNot { it.id == id })
        source?.let { File(context.cacheDir, "catalog-${it.id}.json").delete() }
    }

    suspend fun favorites(): Set<String> =
        decode<List<String>>(context.dataStore.data.first()[favKey], emptyList()).toSet()

    suspend fun toggleFavorite(id: String) {
        val values = favorites().toMutableSet()
        if (!values.add(id)) values.remove(id)
        edit(favKey, json.encodeToString(values.toList()))
    }

    suspend fun progress(): List<WatchProgress> =
        decode(context.dataStore.data.first()[progressKey], emptyList())

    suspend fun saveProgress(value: WatchProgress) {
        if (value.durationMs <= 0L || value.positionMs <= 0L) return
        val values = (
            progress().filterNot { it.mediaId == value.mediaId } + value.copy(
                updatedAt = System.currentTimeMillis(),
            )
        ).sortedByDescending { it.updatedAt }.take(100)
        edit(progressKey, json.encodeToString(values))
    }

    suspend fun loadCatalog(source: MediaSource, force: Boolean = false): Catalog {
        val file = File(context.cacheDir, "catalog-${source.id}.json")
        if (!force && file.exists()) {
            val age = System.currentTimeMillis() - file.lastModified()
            if (age in 0..15L.minutes()) {
                val cached = decode(file.readText(), Catalog())
                if (cached.channels.isNotEmpty() || cached.movies.isNotEmpty() || cached.series.isNotEmpty()) {
                    return cached
                }
            }
        }

        val catalog = when (source.type) {
            SourceType.XTREAM -> xtream.catalog(source)
            SourceType.M3U -> {
                val raw = http.text(source.url)
                require(raw.contains("#EXTM3U", ignoreCase = true)) {
                    "La risposta non sembra una playlist M3U valida"
                }
                val channels = M3uParser.parse(raw, source.id)
                require(channels.isNotEmpty()) { "Playlist M3U vuota o non riconosciuta" }
                Catalog(
                    liveCategories = channels
                        .map { it.group }
                        .filter(String::isNotBlank)
                        .distinct()
                        .map { Category(it, it) },
                    channels = channels,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }

        file.writeText(json.encodeToString(catalog))
        return catalog
    }

    suspend fun verify(source: MediaSource): Boolean = when (source.type) {
        SourceType.XTREAM -> xtream.authenticate(source)
        SourceType.M3U -> runCatching {
            val response = http.text(source.url)
            response.contains("#EXTM3U", ignoreCase = true) &&
                M3uParser.parse(response).isNotEmpty()
        }.getOrDefault(false)
    }

    suspend fun episodes(source: MediaSource, id: String): List<Episode> =
        xtream.episodes(source, id)

    suspend fun epg(source: MediaSource, channelId: String): List<EpgProgramme> =
        when (source.type) {
            SourceType.XTREAM -> xtream.shortEpg(source, channelId)
            SourceType.M3U -> if (source.epgUrl.isBlank()) {
                emptyList()
            } else {
                val stream = http.bytes(source.epgUrl)
                XmlTvParser.parse(stream.inputStream())
                    .filter {
                        it.channelId == channelId ||
                            it.channelId.equals(channelId, ignoreCase = true)
                    }
            }
        }

    suspend fun export(): String =
        json.encodeToString(
            AppBackup(
                sources = sources(),
                favorites = favorites(),
                history = progress(),
            )
        )

    suspend fun import(value: String) {
        val backup = json.decodeFromString<AppBackup>(value)
        saveSources(backup.sources)
        edit(favKey, json.encodeToString(backup.favorites.toList()))
        edit(progressKey, json.encodeToString(backup.history.take(100)))
    }

    private suspend fun edit(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    private inline fun <reified T> decode(value: String?, fallback: T): T =
        try {
            if (value == null) fallback else json.decodeFromString(value)
        } catch (_: Exception) {
            fallback
        }

    private fun normalizeUrl(value: String): String =
        value.trim().removeSuffix("/")

    private fun Long.minutes(): Long = this * 60_000L
}
