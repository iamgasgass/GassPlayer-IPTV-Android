package com.gassplayer.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class CatalogRepository(private val context: Context, private val prefs: AppPreferences, private val xtream: XtreamRepository, private val network: NetworkApi) {
    private val refreshMutex = Mutex()
    /** Big playlists are parsed one or two at a time: N parallel 100 MB parses is what exhausts the heap. */
    private val loadPermits = Semaphore(2)
    /** Disk-backed store: the heap only ever holds the page currently on screen. */
    val db: CatalogDb = CatalogDb(context)

    init { deleteLegacyCaches() }

    suspend fun loadAll(force: Boolean = false): CatalogState = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
        val configured = prefs.sourcesFlow.first()
        val sources = configured.filter { it.isEnabled }
        val appSettings = prefs.settingsFlow.first()
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        runCatching { db.pruneExcept(configured.map { it.id }) }
        val metas = sources.map { source ->
            async {
                loadPermits.withPermit {
                    try {
                        loadSource(source, force, appSettings)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        errors += friendlyError(source, t)
                        System.gc()
                        // Keep serving whatever the cache still holds for this source.
                        db.sourceMeta(source.id) ?: CatalogDb.SourceMeta(0L, 0, emptyList(), emptyList(), emptyList())
                    }
                }
            }
        }.awaitAll()
        val ids = sources.map { it.id }
        CatalogState(
            metas.flatMap { it.liveCategories }.distinctBy { it.id to it.sourceId },
            metas.flatMap { it.vodCategories }.distinctBy { it.id to it.sourceId },
            metas.flatMap { it.seriesCategories }.distinctBy { it.id to it.sourceId },
            DbList(db, MediaKind.LIVE, ids), DbList(db, MediaKind.MOVIE, ids), DbList(db, MediaKind.SERIES, ids), DbList(db, MediaKind.EPISODE, ids),
            System.currentTimeMillis(), errors.toList(), db, ids
        )
        }
    }

    private fun friendlyError(source: MediaSourceConfig, t: Throwable): String =
        if (t is OutOfMemoryError) "Playlist '${source.name}' troppo grande per la memoria disponibile su questo dispositivo. Usa una lista più piccola o filtrata dal provider."
        else (t.message ?: t.javaClass.simpleName)

    private suspend fun loadSource(source: MediaSourceConfig, force: Boolean, appSettings: AppSettings): CatalogDb.SourceMeta {
        if (!force) db.sourceMeta(source.id)?.takeIf { System.currentTimeMillis() - it.updatedAt < 6 * 60 * 60_000L && it.count > 0 }?.let { return it }
        if (source.type == SourceType.M3U8) return loadM3u(source)
        val snapshot = when (source.type) {
            SourceType.XTREAM -> {
                val b = xtream.loadCatalog(source)
                val preload = appSettings.preloadSeries
                val episodes = if (preload) b.episodes + b.series.take(12).flatMap { runCatching { xtream.seriesEpisodes(source, it.id.substringAfterLast(':'), it.title) }.getOrDefault(emptyList()) } else b.episodes
                SourceSnapshot(source.id, b.liveCategories, b.vodCategories, b.seriesCategories, b.live, b.movies, b.series, episodes, System.currentTimeMillis())
            }
            else -> SourceSnapshot(source.id, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), System.currentTimeMillis())
        }
        // Written to disk in one transaction; the lists are garbage as soon as this function returns.
        db.replaceSource(source.id, snapshot)
        return CatalogDb.SourceMeta(snapshot.updatedAt, snapshot.live.size + snapshot.movies.size + snapshot.series.size + snapshot.episodes.size, snapshot.liveCategories, snapshot.vodCategories, snapshot.seriesCategories)
    }

    /**
     * M3U path: download -> parse -> SQLite in one pass. Channels and movies are written as they are
     * parsed; only series/episodes (which need grouping) are held in memory, so a 100 MB list uses a
     * few MB of heap. Rolled back untouched if anything fails, so the previous cache survives.
     */
    private suspend fun loadM3u(source: MediaSourceConfig): CatalogDb.SourceMeta {
        val playlistUrl = source.playlistUrl ?: source.host
        val sourceHeaders = NetworkApi.extractInlineHeaders(playlistUrl)
        val meta = network.readTextStream(playlistUrl, mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, */*")) { reader, finalUrl ->
            db.writeSource(source.id) { w ->
                val rest = M3UParser.parse(source.id, reader, NetworkApi.stripInlineHeaders(finalUrl), defaultHeaders = sourceHeaders, sink = { w.add(it) })
                for (item in rest) w.add(item)
                if (w.count == 0) null else {
                    val now = System.currentTimeMillis()
                    w.commit(now, emptyList(), emptyList(), emptyList())
                    CatalogDb.SourceMeta(now, w.count, emptyList(), emptyList(), emptyList())
                }
            }
        }
        // Checked outside the download block so an empty list is not re-downloaded for every URL variant.
        return meta ?: error("Playlist '${source.name}' vuota o in un formato non riconosciuto")
    }

    suspend fun loadSeriesEpisodes(sourceId: String, seriesId: String, seriesTitle: String? = null): List<MediaItem> = withContext(Dispatchers.IO) {
        val source = prefs.sourcesFlow.first().firstOrNull { it.id == sourceId } ?: return@withContext emptyList()
        val cached = db.episodesFor(sourceId, seriesId)
        if (source.type != SourceType.XTREAM) return@withContext cached
        val fetched = runCatching { xtream.seriesEpisodes(source, seriesId, seriesTitle) }.getOrDefault(emptyList())
        if (fetched.isEmpty()) return@withContext cached
        runCatching { db.replaceEpisodes(sourceId, seriesId, fetched.distinctBy { it.id }) }
        fetched
    }

    private fun deleteLegacyCaches() {
        runCatching { context.filesDir.listFiles()?.filter { it.name.startsWith("catalog_v2_") || it.name.startsWith("catalog_v3_") }?.forEach { it.delete() } }
    }

    fun clearCache() { runCatching { db.clearAll() }; deleteLegacyCaches() }
    fun cacheCount(): Int = runCatching { db.cachedSourceCount() }.getOrDefault(0)
}

@kotlinx.serialization.Serializable
data class SourceSnapshot(val sourceId: String, val liveCategories: List<Category>, val vodCategories: List<Category>, val seriesCategories: List<Category>, val live: List<MediaItem>, val movies: List<MediaItem>, val series: List<MediaItem>, val episodes: List<MediaItem>, val updatedAt: Long)

data class CatalogState(
    val liveCategories: List<Category>, val vodCategories: List<Category>, val seriesCategories: List<Category>,
    val live: List<MediaItem>, val movies: List<MediaItem>, val series: List<MediaItem>, val episodes: List<MediaItem>,
    val updatedAt: Long, val errors: List<String> = emptyList(),
    /** Backing store + enabled sources (null only for hand-built states, e.g. tests). */
    val store: CatalogDb? = null, val sourceIds: List<String> = emptyList()
) {
    /** Zero-copy concatenation (live + movies + series + episodes). */
    val allItems: List<MediaItem> by lazy(LazyThreadSafetyMode.PUBLICATION) { ConcatList(listOf(live, movies, series, episodes)) }

    fun findById(id: String): MediaItem? = store?.findById(id)?.takeIf { it.sourceId in sourceIds } ?: if (store == null) allItems.firstOrNull { it.id == id } else null

    /** Favourites etc.: items of [kind] whose id is in [ids], via an indexed lookup instead of a full scan. */
    fun byIds(kind: MediaKind, ids: Set<String>): List<MediaItem> = store?.byIds(kind, ids, sourceIds) ?: when (kind) {
        MediaKind.LIVE -> live; MediaKind.MOVIE -> movies; MediaKind.SERIES -> series; MediaKind.EPISODE -> episodes
    }.filter { it.id in ids }

    fun search(text: String, limit: Int = 100): List<MediaItem> = store?.search(text, limit, sourceIds)
        ?: allItems.asSequence().filter { it.title.contains(text, true) }.take(limit).toList()
}
