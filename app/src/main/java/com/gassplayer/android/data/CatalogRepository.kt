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

    suspend fun loadAll(force: Boolean = false): CatalogState = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
        val sources = prefs.sourcesFlow.first().filter { it.isEnabled }
        val appSettings = prefs.settingsFlow.first()
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val all = sources.map { source ->
            async {
                loadPermits.withPermit {
                    try {
                        loadSource(source, force, appSettings)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        errors += friendlyError(source, t)
                        System.gc()
                        runCatching { restore(source.id) }.getOrNull() ?: emptySnapshot(source.id)
                    }
                }
            }
        }.awaitAll()
        val live = all.mergeDedupe { it.live }
        val movies = all.mergeDedupe { it.movies }
        val series = all.mergeDedupe { it.series }
        val episodes = all.mergeDedupe { it.episodes }
        CatalogState(all.flatMap { it.liveCategories }.distinctBy { it.id to it.sourceId }, all.flatMap { it.vodCategories }.distinctBy { it.id to it.sourceId }, all.flatMap { it.seriesCategories }.distinctBy { it.id to it.sourceId }, live, movies, series, episodes, System.currentTimeMillis(), errors.toList())
        }
    }

    private fun friendlyError(source: MediaSourceConfig, t: Throwable): String =
        if (t is OutOfMemoryError) "Playlist '${source.name}' troppo grande per la memoria disponibile su questo dispositivo. Usa una lista più piccola o filtrata dal provider."
        else (t.message ?: t.javaClass.simpleName)

    private suspend fun loadSource(source: MediaSourceConfig, force: Boolean, appSettings: AppSettings): SourceSnapshot {
        if (!force) restore(source.id)?.takeIf { System.currentTimeMillis() - it.updatedAt < 6 * 60 * 60_000L && (it.live.isNotEmpty() || it.movies.isNotEmpty() || it.series.isNotEmpty()) }?.let { return it }
        val snapshot = when (source.type) {
            SourceType.XTREAM -> {
                val b = xtream.loadCatalog(source)
                val preload = appSettings.preloadSeries
                val episodes = if (preload) b.episodes + b.series.take(12).flatMap { runCatching { xtream.seriesEpisodes(source, it.id.substringAfterLast(':'), it.title) }.getOrDefault(emptyList()) } else b.episodes
                SourceSnapshot(source.id, b.liveCategories, b.vodCategories, b.seriesCategories, b.live, b.movies, b.series, episodes, System.currentTimeMillis())
            }
            SourceType.M3U8 -> {
                val playlistUrl = source.playlistUrl ?: source.host
                val sourceHeaders = NetworkApi.extractInlineHeaders(playlistUrl)
                // Streamed: the playlist body is parsed while it downloads, never held as one array/String.
                val parsed = network.readTextStream(playlistUrl, mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, */*")) { reader, finalUrl ->
                    M3UParser.parse(source.id, reader, NetworkApi.stripInlineHeaders(finalUrl), defaultHeaders = sourceHeaders)
                }
                if (parsed.isEmpty()) error("Playlist '${source.name}' vuota o in un formato non riconosciuto")
                SourceSnapshot(
                    source.id, emptyList(), emptyList(), emptyList(),
                    parsed.filter { it.kind == MediaKind.LIVE }, parsed.filter { it.kind == MediaKind.MOVIE },
                    parsed.filter { it.kind == MediaKind.SERIES }, parsed.filter { it.kind == MediaKind.EPISODE },
                    System.currentTimeMillis()
                )
            }
            else -> SourceSnapshot(source.id, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), System.currentTimeMillis())
        }
        // A failed cache write (disk full...) must not throw away a catalog that loaded fine.
        runCatching { persist(snapshot) }
        return snapshot
    }

    suspend fun loadSeriesEpisodes(sourceId: String, seriesId: String, seriesTitle: String? = null): List<MediaItem> = withContext(Dispatchers.IO) {
        val source = prefs.sourcesFlow.first().firstOrNull { it.id == sourceId } ?: return@withContext emptyList()
        val previous = restore(sourceId)
        val cached = previous?.episodes.orEmpty().filter { it.seriesId == seriesId }
        if (source.type != SourceType.XTREAM) return@withContext cached
        val fetched = runCatching { xtream.seriesEpisodes(source, seriesId, seriesTitle) }.getOrDefault(emptyList())
        if (fetched.isEmpty()) return@withContext cached
        if (previous != null) {
            val mergedEpisodes = (previous.episodes.filterNot { it.seriesId == seriesId } + fetched).distinctBy { it.id }
            runCatching { persist(previous.copy(episodes = mergedEpisodes, updatedAt = System.currentTimeMillis())) }
        }
        fetched
    }

    private fun emptySnapshot(sourceId: String) = SourceSnapshot(sourceId, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0L)

    // ---- On-disk cache -------------------------------------------------------------------------
    // Format (gzip): line 1 = JSON header (categories + counts), then ONE MediaItem JSON per line in
    // the order live, movies, series, episodes. Written and read line by line, so neither saving nor
    // restoring ever builds a giant String, and repeated strings are shared while loading.

    @kotlinx.serialization.Serializable
    private data class CacheHeader(
        val sourceId: String, val liveCategories: List<Category>, val vodCategories: List<Category>, val seriesCategories: List<Category>,
        val updatedAt: Long, val live: Int, val movies: Int, val series: Int, val episodes: Int
    )

    private fun file(sourceId: String): File = File(context.filesDir, "catalog_v3_${sourceId.hashCode()}.jsonl.gz")
    private fun legacyFile(sourceId: String): File = File(context.filesDir, "catalog_v2_${sourceId.hashCode()}.json")

    private fun persist(snapshot: SourceSnapshot) {
        val json = JsonStore.json
        val target = file(snapshot.sourceId)
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            BufferedWriter(OutputStreamWriter(GZIPOutputStream(tmp.outputStream().buffered(64 * 1024), 64 * 1024), Charsets.UTF_8), 64 * 1024).use { w ->
                w.write(json.encodeToString(CacheHeader.serializer(), CacheHeader(
                    snapshot.sourceId, snapshot.liveCategories, snapshot.vodCategories, snapshot.seriesCategories, snapshot.updatedAt,
                    snapshot.live.size, snapshot.movies.size, snapshot.series.size, snapshot.episodes.size
                )))
                w.write("\n")
                for (list in listOf(snapshot.live, snapshot.movies, snapshot.series, snapshot.episodes)) {
                    for (item in list) { w.write(json.encodeToString(MediaItem.serializer(), item)); w.write("\n") }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) error("Impossibile salvare la cache del catalogo")
            legacyFile(snapshot.sourceId).delete()
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun restore(sourceId: String): SourceSnapshot? = runCatching {
        val f = file(sourceId)
        if (!f.exists()) return@runCatching null
        val json = JsonStore.json
        BufferedReader(InputStreamReader(GZIPInputStream(f.inputStream().buffered(64 * 1024), 64 * 1024), Charsets.UTF_8), 64 * 1024).use { r ->
            val header = json.decodeFromString(CacheHeader.serializer(), r.readLine() ?: return@runCatching null)
            val pool = Interner()
            fun read(count: Int): List<MediaItem> {
                val out = ArrayList<MediaItem>(count)
                repeat(count) {
                    val line = r.readLine() ?: return out
                    out += pool.share(json.decodeFromString(MediaItem.serializer(), line))
                }
                return out
            }
            val live = read(header.live); val movies = read(header.movies); val series = read(header.series); val episodes = read(header.episodes)
            SourceSnapshot(header.sourceId, header.liveCategories, header.vodCategories, header.seriesCategories, live, movies, series, episodes, header.updatedAt)
        }
    }.getOrNull()

    fun clearCache() = context.filesDir.listFiles()?.filter { it.name.startsWith("catalog_") }?.forEach { it.delete() }
    fun cacheCount(): Int = context.filesDir.listFiles()?.count { it.name.startsWith("catalog_") && !it.name.endsWith(".tmp") } ?: 0
}

/** Shares identical strings/maps between items so 400k entries don't each carry their own copy of the group, mime type... */
private class Interner {
    private val strings = HashMap<String, String>()
    private val maps = HashMap<Map<String, String>, Map<String, String>>()
    private fun s(v: String?): String? = if (v == null) null else strings.getOrPut(v) { v }
    fun share(i: MediaItem): MediaItem = i.copy(
        sourceId = s(i.sourceId)!!, group = s(i.group), categoryId = s(i.categoryId), streamMimeType = s(i.streamMimeType),
        genre = s(i.genre), year = s(i.year), streamHeaders = if (i.streamHeaders.isEmpty()) emptyMap() else maps.getOrPut(i.streamHeaders) { i.streamHeaders }
    )
}

@kotlinx.serialization.Serializable
data class SourceSnapshot(val sourceId: String, val liveCategories: List<Category>, val vodCategories: List<Category>, val seriesCategories: List<Category>, val live: List<MediaItem>, val movies: List<MediaItem>, val series: List<MediaItem>, val episodes: List<MediaItem>, val updatedAt: Long)

data class CatalogState(val liveCategories: List<Category>, val vodCategories: List<Category>, val seriesCategories: List<Category>, val live: List<MediaItem>, val movies: List<MediaItem>, val series: List<MediaItem>, val episodes: List<MediaItem>, val updatedAt: Long, val errors: List<String> = emptyList()) {
    /** Built once per catalog (it used to be rebuilt — a full list copy — on every recomposition/lookup). */
    val allItems: List<MediaItem> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ArrayList<MediaItem>(live.size + movies.size + series.size + episodes.size).apply { addAll(live); addAll(movies); addAll(series); addAll(episodes) }
    }
}

/** Merges the per-source lists and drops duplicates using a 64-bit key instead of a long concatenated String per item. */
private inline fun List<SourceSnapshot>.mergeDedupe(pick: (SourceSnapshot) -> List<MediaItem>): List<MediaItem> {
    val total = sumOf { pick(it).size }
    val seen = HashSet<Long>(total * 2)
    val out = ArrayList<MediaItem>(total)
    for (snap in this) for (item in pick(snap)) {
        val a = "${item.kind.ordinal}:${item.title.trim().lowercase()}:${item.sourceId}".hashCode().toLong()
        val key = (a shl 32) xor (item.streamUrl.hashCode().toLong() and 0xffffffffL)
        if (seen.add(key)) out += item
    }
    out.trimToSize()
    return out
}
