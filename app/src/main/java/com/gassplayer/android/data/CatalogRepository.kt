package com.gassplayer.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class CatalogRepository(private val context: Context, private val prefs: AppPreferences, private val xtream: XtreamRepository, private val network: NetworkApi) {
    suspend fun loadAll(force: Boolean = false): CatalogState = withContext(Dispatchers.IO) {
        val sources = prefs.sourcesFlow.first().filter { it.isEnabled }
        val all = sources.map { source ->
            async {
                runCatching { loadSource(source, force) }.getOrElse { restore(source.id) ?: emptySnapshot(source.id) }
            }
        }.awaitAll()
        val live = all.flatMap { it.live }.dedupeMedia()
        val movies = all.flatMap { it.movies }.dedupeMedia()
        val series = all.flatMap { it.series }.dedupeMedia()
        val episodes = all.flatMap { it.episodes }.dedupeMedia()
        CatalogState(all.flatMap { it.liveCategories }.distinctBy { it.id to it.sourceId }, all.flatMap { it.vodCategories }.distinctBy { it.id to it.sourceId }, all.flatMap { it.seriesCategories }.distinctBy { it.id to it.sourceId }, live, movies, series, episodes, System.currentTimeMillis())
    }

    private suspend fun loadSource(source: MediaSourceConfig, force: Boolean): SourceSnapshot {
        if (!force) restore(source.id)?.takeIf { System.currentTimeMillis() - it.updatedAt < 6 * 60 * 60_000L }?.let { return it }
        val snapshot = when (source.type) {
            SourceType.XTREAM -> {
                val b = xtream.loadCatalog(source)
                val preload = prefs.settingsFlow.first().preloadSeries
                val episodes = if (preload) b.series.take(40).flatMap { runCatching { xtream.seriesEpisodes(source, it.id.substringAfterLast(':')) }.getOrDefault(emptyList()) } else emptyList()
                SourceSnapshot(source.id, b.liveCategories, b.vodCategories, b.seriesCategories, b.live, b.movies, b.series, episodes, System.currentTimeMillis())
            }
            SourceType.M3U8 -> {
                val parsed = M3UParser.parse(source.id, network.getText(source.playlistUrl ?: source.host))
                SourceSnapshot(source.id, emptyList(), emptyList(), emptyList(), parsed.filter { it.kind == MediaKind.LIVE }, parsed.filter { it.kind == MediaKind.MOVIE }, parsed.filter { it.kind == MediaKind.SERIES }, parsed.filter { it.kind == MediaKind.EPISODE }, System.currentTimeMillis())
            }
            else -> SourceSnapshot(source.id, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), System.currentTimeMillis())
        }
        persist(snapshot); return snapshot
    }

    private fun emptySnapshot(sourceId: String) = SourceSnapshot(sourceId, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0L)

    private fun file(sourceId: String): File = File(context.filesDir, "catalog_${sourceId.hashCode()}.json")
    private fun persist(snapshot: SourceSnapshot) { file(snapshot.sourceId).writeText(JsonStore.json.encodeToString(snapshot)) }
    private fun restore(sourceId: String): SourceSnapshot? = runCatching { JsonStore.json.decodeFromString<SourceSnapshot>(file(sourceId).readText()) }.getOrNull()
    fun clearCache() = context.filesDir.listFiles()?.filter { it.name.startsWith("catalog_") }?.forEach { it.delete() }
    fun cacheCount(): Int = context.filesDir.listFiles()?.count { it.name.startsWith("catalog_") } ?: 0
}

@kotlinx.serialization.Serializable
data class SourceSnapshot(val sourceId: String, val liveCategories: List<Category>, val vodCategories: List<Category>, val seriesCategories: List<Category>, val live: List<MediaItem>, val movies: List<MediaItem>, val series: List<MediaItem>, val episodes: List<MediaItem>, val updatedAt: Long)

data class CatalogState(val liveCategories: List<Category>, val vodCategories: List<Category>, val seriesCategories: List<Category>, val live: List<MediaItem>, val movies: List<MediaItem>, val series: List<MediaItem>, val episodes: List<MediaItem>, val updatedAt: Long)

private fun List<MediaItem>.dedupeMedia(): List<MediaItem> = distinctBy { "${it.kind}:${it.title.trim().lowercase()}:${it.sourceId}:${it.streamUrl}" }
