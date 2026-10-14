package com.gassplayer.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /**
     * Ogni catalogo ricomposto (anche dall'aggiornamento automatico in background, WorkManager): l'interfaccia
     * aperta lo riceve subito invece di restare sui dati precedenti fino al riavvio.
     */
    private val _updates = kotlinx.coroutines.flow.MutableSharedFlow<CatalogState>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val updates: kotlinx.coroutines.flow.SharedFlow<CatalogState> = _updates
    /** Big playlists are parsed one or two at a time: N parallel 100 MB parses is what exhausts the heap. */
    private val loadPermits = Semaphore(2)
    /** Disk-backed store: the heap only ever holds the page currently on screen. */
    val db: CatalogDb = CatalogDb(context)

    /**
     * Stato del caricamento per sorgente: impronta della configurazione (host/utente/password/URL) con cui la
     * cache e' stata scritta e validatori HTTP (ETag / Last-Modified) dell'ultima playlist M3U scaricata.
     */
    private val loadState = context.getSharedPreferences("catalog_load_state", Context.MODE_PRIVATE)

    init { deleteLegacyCaches() }

    /**
     * Catalogo dalla sola cache su disco, senza rete (lettura di poche righe di metadati): la UI lo mostra
     * all'istante mentre l'aggiornamento dalla rete prosegue in background. `null` se non c'e' nulla in cache.
     */
    suspend fun cachedState(): CatalogState? = withContext(Dispatchers.IO) {
        val all = prefs.sourcesFlow.first()
        // Bases used to resolve relative artwork paths (server / playlist URL of each source).
        ArtworkUrls.registerSources(all)
        // Come iOS: il catalogo mostrato e' SOLO quello della sorgente attiva.
        val source = resolveActive(all) ?: return@withContext null
        val meta = runCatching { db.sourceMeta(source.id) }.getOrNull()?.takeIf { it.count > 0 && cacheMatchesConfig(source) }
            ?: return@withContext null
        buildState(listOf(source.id), listOf(meta), emptyList())
    }

    /**
     * Sorgente attiva come `SourceManager.activeSource` iOS: quella scelta (anche dal menu "Cambia" della Home);
     * se non c'e' piu' (eliminata) o non e' mai stata scelta, la prima abilitata, che viene salvata come attiva.
     */
    private suspend fun resolveActive(configured: List<MediaSourceConfig>): MediaSourceConfig? {
        val stored = prefs.activeSourceFlow.first()
        val chosen = configured.firstOrNull { it.id == stored }
            ?: configured.firstOrNull { it.isEnabled }
            ?: configured.firstOrNull()
        if (chosen != null && chosen.id != stored) runCatching { prefs.saveActiveSource(chosen.id) }
        return chosen
    }

    /**
     * "Guarda tutte le liste insieme" (iOS `AllSourcesLiveView`): i canali Live di TUTTE le sorgenti abilitate,
     * dalla cache su disco. Indipendente dal catalogo della sorgente attiva. `null` se nulla e' in cache.
     */
    suspend fun allSourcesLiveState(): CatalogState? = withContext(Dispatchers.IO) {
        val enabled = prefs.sourcesFlow.first().filter { it.isEnabled }
        val cached = enabled.mapNotNull { source ->
            runCatching { db.sourceMeta(source.id) }.getOrNull()?.takeIf { it.count > 0 && cacheMatchesConfig(source) }?.let { source.id to it }
        }
        if (cached.isEmpty()) null else buildState(cached.map { it.first }, cached.map { it.second }, emptyList())
    }

    /**
     * [force] = riscarica dalla rete; con [forceSourceIds] solo quelle sorgenti (es. "Ricarica" di una sorgente),
     * le altre usano la cache se ancora valida. Senza force la rete si usa solo per sorgenti nuove, modificate
     * o con cache scaduta.
     */
    suspend fun loadAll(
        force: Boolean = false,
        forceSourceIds: Set<String>? = null,
        /** Solo queste sezioni (iOS `refresh(kind:)`, es. "Ricarica Live TV"); null = tutte. */
        onlyKinds: Set<MediaKind>? = null,
    ): CatalogState = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
        val configured = prefs.sourcesFlow.first()
        ArtworkUrls.registerSources(configured)
        val active = resolveActive(configured)
        // Si scaricano/aggiornano tutte le sorgenti abilitate (cosi' cambiare playlist dalla Home e' istantaneo e
        // "Guarda tutte le liste insieme" ha i canali), ma il catalogo pubblicato contiene solo quella attiva.
        val sources = configured.filter { it.isEnabled || it.id == active?.id }
        val appSettings = prefs.settingsFlow.first()
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        runCatching { db.pruneExcept(configured.map { it.id }) }
        val metas = sources.map { source ->
            async {
                loadPermits.withPermit {
                    try {
                        val forceThis = force && (forceSourceIds == null || source.id in forceSourceIds)
                        loadSource(source, forceThis, appSettings, if (forceThis) onlyKinds else null).also { rememberConfig(source); rememberArtworkVersion(source) }
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
        val activeIndex = sources.indexOfFirst { it.id == active?.id }
        val visibleIds = if (activeIndex >= 0) listOf(sources[activeIndex].id) else emptyList()
        val visibleMetas = if (activeIndex >= 0) listOf(metas[activeIndex]) else emptyList()
        buildState(visibleIds, visibleMetas, errors.toList()).also { _updates.tryEmit(it) }
        }
    }

    private fun buildState(ids: List<String>, metas: List<CatalogDb.SourceMeta>, errors: List<String>): CatalogState =
        CatalogState(
            metas.flatMap { it.liveCategories }.distinctBy { it.id to it.sourceId },
            metas.flatMap { it.vodCategories }.distinctBy { it.id to it.sourceId },
            metas.flatMap { it.seriesCategories }.distinctBy { it.id to it.sourceId },
            DbList(db, MediaKind.LIVE, ids), DbList(db, MediaKind.MOVIE, ids), DbList(db, MediaKind.SERIES, ids), DbList(db, MediaKind.EPISODE, ids),
            System.currentTimeMillis(), errors, db, ids
        )

    /** Impronta di cio' che determina il contenuto scaricato: se cambia, la cache della sorgente non vale piu'. */
    private fun configFingerprint(source: MediaSourceConfig): String =
        listOf(source.type.name, source.host, source.username.orEmpty(), source.password.orEmpty(), source.playlistUrl.orEmpty())
            .joinToString("\u0001").hashCode().toString(16)

    private fun cacheMatchesConfig(source: MediaSourceConfig): Boolean {
        val stored = loadState.getString("fp_${source.id}", null) ?: return true // cache scritta prima di questa versione
        return stored == configFingerprint(source)
    }

    /**
     * Versione della lettura di icone/poster con cui la cache della sorgente e' stata scritta. Le cache precedenti
     * hanno loghi M3U decodificati male e campi Xtream di riserva mancanti, che la normalizzazione in lettura non
     * puo' recuperare: vengono riscaricate UNA volta (il catalogo in cache resta visibile nel frattempo).
     */
    private fun artworkOutdated(source: MediaSourceConfig): Boolean =
        loadState.getInt("art_${source.id}", 0) < ARTWORK_PARSE_VERSION

    private fun rememberArtworkVersion(source: MediaSourceConfig) {
        if (artworkOutdated(source)) loadState.edit().putInt("art_${source.id}", ARTWORK_PARSE_VERSION).apply()
    }

    private fun rememberConfig(source: MediaSourceConfig) {
        val fp = configFingerprint(source)
        if (loadState.getString("fp_${source.id}", null) != fp) loadState.edit().putString("fp_${source.id}", fp).apply()
    }

    private fun friendlyError(source: MediaSourceConfig, t: Throwable): String =
        if (t is OutOfMemoryError) "Playlist '${source.name}' troppo grande per la memoria disponibile su questo dispositivo. Usa una lista più piccola o filtrata dal provider."
        else (t.message ?: t.javaClass.simpleName)

    private suspend fun loadSource(source: MediaSourceConfig, force: Boolean, appSettings: AppSettings, onlyKinds: Set<MediaKind>? = null): CatalogDb.SourceMeta {
        // Come XtreamCatalogStore iOS: senza richiesta esplicita la rete si usa SOLO se la sorgente non ha cache, se
        // la cache e' di un'altra configurazione (host/credenziali/URL cambiati) o se e' scaduto l'intervallo scelto in
        // Impostazioni ("Manuale" = mai). Prima valeva un limite fisso di 6 ore: all'avvio l'app riscaricava
        // playlist intere anche con aggiornamento manuale o giornaliero.
        if (!force && cacheMatchesConfig(source) && !artworkOutdated(source)) {
            val ttl = RefreshInterval.from(appSettings.catalogRefreshInterval).millis
            db.sourceMeta(source.id)?.takeIf { it.count > 0 && (ttl == null || System.currentTimeMillis() - it.updatedAt < ttl) }?.let { return it }
        }
        if (source.type == SourceType.M3U8) return loadM3u(source)
        var keepKinds: Set<MediaKind> = emptySet()
        val snapshot = when (source.type) {
            SourceType.XTREAM -> {
                // Con cache valida della stessa configurazione si possono aggiornare solo alcune sezioni.
                val canKeep = cacheMatchesConfig(source) && (db.sourceMeta(source.id)?.count ?: 0) > 0
                val kinds = onlyKinds?.takeIf { canKeep && !artworkOutdated(source) }?.intersect(setOf(MediaKind.LIVE, MediaKind.MOVIE, MediaKind.SERIES))?.takeIf { it.isNotEmpty() }
                    ?: setOf(MediaKind.LIVE, MediaKind.MOVIE, MediaKind.SERIES)
                val b = xtream.loadCatalog(source, kinds)
                keepKinds = if (canKeep) b.notRefreshed else emptySet()
                val preload = appSettings.preloadSeries
                // Anteprima episodi delle prime 12 serie: richieste in parallelo (max 4 alla volta) invece che in fila.
                val episodes = if (preload) b.episodes + coroutineScope {
                    val permits = Semaphore(4)
                    b.series.take(12).map { series ->
                        async {
                            permits.withPermit {
                                runCatching { xtream.seriesEpisodes(source, series.id.substringAfterLast(':'), series.title) }.getOrDefault(emptyList())
                            }
                        }
                    }.awaitAll().flatten()
                } else b.episodes
                SourceSnapshot(source.id, b.liveCategories, b.vodCategories, b.seriesCategories, b.live, b.movies, b.series, episodes, System.currentTimeMillis())
            }
            else -> SourceSnapshot(source.id, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), System.currentTimeMillis())
        }
        // Episodi: quelli gia' scaricati per serie (apertura delle schede) restano in cache se questo caricamento non
        // ne porta di nuovi, cosi' dopo un aggiornamento le serie si riaprono subito senza rete.
        if (snapshot.episodes.isEmpty() && cacheMatchesConfig(source) && MediaKind.SERIES !in keepKinds) keepKinds = keepKinds + MediaKind.EPISODE
        if (MediaKind.SERIES in keepKinds) keepKinds = keepKinds + MediaKind.EPISODE
        // Written to disk in one transaction; the lists are garbage as soon as this function returns.
        db.replaceSource(source.id, snapshot, keepKinds)
        return db.sourceMeta(source.id) ?: CatalogDb.SourceMeta(snapshot.updatedAt, snapshot.live.size + snapshot.movies.size + snapshot.series.size + snapshot.episodes.size, snapshot.liveCategories, snapshot.vodCategories, snapshot.seriesCategories)
    }

    /**
     * M3U path: download -> parse -> SQLite in one pass. Channels and movies are written as they are
     * parsed; only series/episodes (which need grouping) are held in memory, so a 100 MB list uses a
     * few MB of heap. Rolled back untouched if anything fails, so the previous cache survives.
     */
    private suspend fun loadM3u(source: MediaSourceConfig): CatalogDb.SourceMeta {
        val playlistUrl = source.playlistUrl ?: source.host
        val sourceHeaders = NetworkApi.extractInlineHeaders(playlistUrl)
        // Richiesta condizionale solo se la cache c'e' ed e' della stessa playlist: 304 = nessun download.
        val cached = db.sourceMeta(source.id)?.takeIf { it.count > 0 && cacheMatchesConfig(source) }
        val validatorKey = "m3u_${source.id}"
        val validators = loadState.getString(validatorKey, null)?.split('\n')
            ?.takeIf { cached != null && it.size == 3 && it[0] == playlistUrl && !artworkOutdated(source) }
        val outcome = network.readTextStreamIfChanged(
            playlistUrl,
            mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, */*"),
            etag = validators?.get(1),
            lastModified = validators?.get(2),
        ) { reader, finalUrl ->
            var headerEpgUrls = emptyList<String>()
            db.writeSource(source.id) { w ->
                val rest = M3UParser.parse(
                    source.id, reader, NetworkApi.stripInlineHeaders(finalUrl), defaultHeaders = sourceHeaders,
                    sink = { w.add(it) },
                    onEpgUrls = { headerEpgUrls = (headerEpgUrls + it).distinct() }
                )
                for (item in rest) w.add(item)
                if (w.count == 0) null else {
                    val now = System.currentTimeMillis()
                    w.commit(now, emptyList(), emptyList(), emptyList())
                    // Guida dichiarata dalla playlist: ricordata per la sorgente (aggiornata a ogni nuovo download).
                    loadState.edit().putString("epg_${source.id}", headerEpgUrls.joinToString("\n")).apply()
                    CatalogDb.SourceMeta(now, w.count, emptyList(), emptyList(), emptyList())
                }
            }
        }
        return when (outcome) {
            is NetworkApi.Conditional.NotModified -> {
                if (cached == null) {
                    loadState.edit().remove(validatorKey).apply()
                    error("Playlist '${source.name}': risposta 304 senza cache locale, riprova")
                }
                val now = System.currentTimeMillis()
                db.touchSource(source.id, now)
                cached.copy(updatedAt = now)
            }
            is NetworkApi.Conditional.Changed -> {
                // Checked outside the download block so an empty list is not re-downloaded for every URL variant.
                val meta = outcome.value
                if (meta == null) {
                    loadState.edit().remove(validatorKey).apply()
                    error("Playlist '${source.name}' vuota o in un formato non riconosciuto")
                }
                if (outcome.etag != null || outcome.lastModified != null) {
                    loadState.edit().putString(validatorKey, listOf(playlistUrl, outcome.etag.orEmpty(), outcome.lastModified.orEmpty()).joinToString("\n")).apply()
                } else loadState.edit().remove(validatorKey).apply()
                meta
            }
        }
    }

    /** Indirizzi XMLTV dichiarati nell'intestazione della playlist M3U della sorgente (vuoto se nessuno). */
    fun m3uEpgUrls(sourceId: String): List<String> =
        loadState.getString("epg_$sourceId", null)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    suspend fun loadSeriesEpisodes(sourceId: String, seriesId: String, seriesTitle: String? = null): List<MediaItem> = withContext(Dispatchers.IO) {
        val source = prefs.sourcesFlow.first().firstOrNull { it.id == sourceId } ?: return@withContext emptyList()
        val cached = db.episodesFor(sourceId, seriesId)
        if (source.type != SourceType.XTREAM) return@withContext cached
        val fetched = runCatching { xtream.seriesEpisodes(source, seriesId, seriesTitle) }.getOrDefault(emptyList())
        if (fetched.isEmpty()) return@withContext cached
        runCatching { db.replaceEpisodes(sourceId, seriesId, fetched.distinctBy { it.id }) }
        fetched.map(ArtworkUrls::normalize)
    }

    private fun deleteLegacyCaches() {
        runCatching { context.filesDir.listFiles()?.filter { it.name.startsWith("catalog_v2_") || it.name.startsWith("catalog_v3_") }?.forEach { it.delete() } }
    }

    fun clearCache() { runCatching { db.clearAll() }; deleteLegacyCaches() }

    private companion object {
        /** Incrementare quando cambia il modo in cui icone/poster vengono letti dalla playlist o dal pannello. */
        const val ARTWORK_PARSE_VERSION = 1
    }
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
