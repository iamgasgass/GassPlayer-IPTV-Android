package com.gassplayer.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlin.math.roundToInt

private val DetailText = Color(0xFFF7F8FC)
private val DetailMuted = Color(0xFFB5BBC9)
private val DetailAccent = Color(0xFF9ABEFF)
private val DetailHeroHeight = 380.dp

/** Movie detail translated from MovieDetailView + MediaDetailComponents.swift. */
@Composable
fun MovieDetailScreen(
    app: GassPlayerApplication,
    item: MediaItem,
    allItems: List<MediaItem>,
    favorite: FavoriteState,
    onBack: () -> Unit,
    onPlay: (MediaItem, Long) -> Unit,
    entryFocusRequester: FocusRequester? = null,
    /** Metadata already known by the caller (a TMDB trending card): shown at once while details load. */
    initialMeta: MetadataResult? = null,
) {
    val remoteFirstDevice = LocalContext.current.isTvOrFireTvDevice()
    val settings by app.prefs.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    val sourceConfigs by app.sources.sources.collectAsStateWithLifecycle(emptyList())
    val currentWatch = watch.firstOrNull { it.contentId == item.id }
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    // The main "Riproduci" button always owns a requester (the shell's entry point when provided), so
    // the close button can send DPAD DOWN straight to it.
    val localPlayFocusRequester = remember { FocusRequester() }
    val playFocusRequester = entryFocusRequester ?: localPlayFocusRequester
    // X (close) -> DOWN: scroll the sheet back to the top if needed and focus the play button at once.
    val focusPlayFromClose: () -> Boolean = {
        scope.launch {
            for (index in 0..2) {
                runCatching { scrollState.scrollToItem(index) }
                if (playFocusRequester.requestFocusWithRetry(attempts = 3, delayMillis = 30L)) break
            }
        }
        true
    }
    var meta by remember(item.id) { mutableStateOf(initialMeta) }
    var ratings by remember(item.id) { mutableStateOf<Ratings?>(null) }
    var traktRating by remember(item.id) { mutableStateOf<Double?>(null) }
    var isLoadingDetail by remember(item.id) { mutableStateOf(true) }
    var showAlternates by remember(item.id) { mutableStateOf(false) }
    var actionMessage by remember(item.id) { mutableStateOf<String?>(null) }
    var downloadWorkId by remember(item.id) { mutableStateOf<String?>(null) }
    val downloadInfo = rememberDownloadWorkInfo(app, downloadWorkId)
    val alternates = remember(allItems, item.id, item.sourceId) {
        // distinctBy: the catalogue DB does not enforce unique ids, and a title listed twice in another source
        // gave the LazyRow below a duplicated key, crashing the app when the detail opened.
        allItems.sameTitleAs(item).filter { it.kind == MediaKind.MOVIE && it.sourceId != item.sourceId }.distinctBy { it.id }
    }
    val canSearchAlternates = remember(sourceConfigs, item.sourceId) {
        sourceConfigs.any { it.isEnabled && it.type == SourceType.XTREAM && it.id != item.sourceId }
    }
    val topProgress = detailTopProgress(scrollState)
    val tvLayout = isTvDetailLayout()

    // Immagine di "Continua a guardare" allineata a quella della scheda (MovieDetailView.swift: updateImage).
    val detailImage = meta?.backdropUrl ?: item.backdropUrl
    LaunchedEffect(detailImage, currentWatch?.imageUrl) {
        val url = detailImage?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (currentWatch != null && currentWatch.imageUrl != url) app.watch.updateImage(url) { it.contentId == item.id }
    }

    // Every detail page has a deterministic first DPAD target, even when posters/metadata load later.
    LaunchedEffect(item.id, playFocusRequester, remoteFirstDevice) {
        if (remoteFirstDevice) {
            delay(90L)
            playFocusRequester.requestFocusWithRetry(attempts = 6, delayMillis = 45L)
        }
    }

    LaunchedEffect(item.id, settings.tmdbApiKey, settings.omdbApiKey, settings.traktClientId) {
        isLoadingDetail = true
        val fetched = if (settings.tmdbApiKey.isBlank()) null else {
            val direct = item.tmdbId?.takeIf { it.isNotBlank() }?.let { id -> runCatching { app.tmdb.details(id, settings.tmdbApiKey, false) }.getOrNull() }
            direct ?: runCatching { app.tmdb.searchMovies(item.title, settings.tmdbApiKey).firstOrNull() }.getOrNull()?.let { found ->
                runCatching { app.tmdb.details(found.id, settings.tmdbApiKey, false) }.getOrNull() ?: found
            }
        }
        // Keep the metadata already shown (e.g. from the trending card) if the request fails.
        meta = fetched ?: meta
        isLoadingDetail = false
        // Ratings in parallel, after the main metadata: OMDb by IMDb id when TMDB provides it (exact),
        // otherwise by the original title rather than the playlist name ("IT: Title [4K]").
        val omdbTitle = meta?.originalTitle?.takeIf { it.isNotBlank() } ?: meta?.title?.takeIf { it.isNotBlank() } ?: item.title
        coroutineScope {
            val omdb = async { runCatching { app.omdb.lookup(omdbTitle, settings.omdbApiKey, meta?.externalId) }.getOrNull() }
            val trakt = async { runCatching { app.trakt.ratings(item.title, settings.traktClientId) }.getOrNull() }
            ratings = omdb.await()
            traktRating = trakt.await()
        }
    }

    val toggleFavorite: () -> Unit = { scope.launch { app.favorites.toggle(item) } }
    val toggleTrailerAudio: () -> Unit = { scope.launch { app.prefs.saveSettings(settings.copy(detailTrailerMuted = !settings.detailTrailerMuted)) } }
    val startDownload: () -> Unit = {
        if (downloadInfo?.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
            actionMessage = "Il download è già in coda o in corso."
        } else {
            downloadWorkId = runCatching { app.downloads.enqueue(item, settings.downloadWifiOnly) }.getOrNull()
            actionMessage = if (downloadWorkId != null) "Download aggiunto alla coda." else "Impossibile avviare il download."
        }
    }
    val downloadLabel = when (downloadInfo?.state) {
        WorkInfo.State.RUNNING -> "${((downloadInfo?.progress?.getFloat("progress", 0f) ?: 0f) * 100).roundToInt()}%"
        WorkInfo.State.SUCCEEDED -> "Scaricato"
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "In coda"
        else -> "Scarica"
    }

    if (tvLayout) {
        // Come PlaybackPositionStore iOS: un film visto fino in fondo (ultimi 45 s) non si "riprende" dalla fine.
        val position = currentWatch?.resumeFromMs() ?: 0L
        val duration = currentWatch?.durationMs ?: 0L
        val watchedFraction = currentWatch?.watchedFraction()
        TvMediaDetailLayout(
            item = item,
            meta = meta,
            listState = scrollState,
            rating = meta?.rating ?: item.rating,
            secondary = listOfNotNull(meta?.year ?: item.year, item.durationSec?.takeIf { it > 0 }?.let(::formatRuntime)).joinToString(" · ").ifBlank { null },
            genres = metadataGenres(meta, item),
            overview = meta?.overview?.takeIf { it.isNotBlank() } ?: item.plot?.takeIf { it.isNotBlank() },
            loading = isLoadingDetail,
            playLabel = if (position > 0L) "Riprendi" else "Riproduci",
            playProgress = watchedFraction,
            playFocusRequester = playFocusRequester,
            onPlay = { onPlay(item, position) },
            actions = listOf(
                TvDetailAction(
                    icon = if (item.id in favorite.movies) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = if (item.id in favorite.movies) "Preferito" else "Preferiti",
                    onClick = toggleFavorite,
                    tint = if (item.id in favorite.movies) Color(0xFFFF88A5) else DetailText
                ),
                TvDetailAction(
                    icon = if (downloadInfo?.state == WorkInfo.State.SUCCEEDED) Icons.Default.CheckCircle else Icons.Default.Download,
                    label = downloadLabel,
                    onClick = startDownload,
                    tint = if (downloadInfo?.state == WorkInfo.State.SUCCEEDED) SourcesGreenForDetail else DetailText
                ),
                TvDetailAction(Icons.Default.Search, "Altre fonti", onClick = { showAlternates = true }, enabled = canSearchAlternates),
            ),
            actionMessage = actionMessage,
            onClose = onBack,
        ) {
            if (alternates.isNotEmpty()) {
                item(key = "tv-alternates-${item.id}") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvRowHeader("SORGENTI ALTERNATIVE")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = TvRowInset)) {
                            items(alternates, key = { it.id }) { alt ->
                                LiquidGlassFocusableSurface(
                                    modifier = Modifier.width(260.dp),
                                    cornerRadius = 16.dp,
                                    contentPadding = 12.dp,
                                    onClick = { onPlay(alt, 0L) }
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(alt.title, color = glassForeground(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(alt.sourceId, color = glassForeground(.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Riproduci da questa sorgente", color = DetailAccent, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "tv-cast-${item.id}") {
                TvCastRow(
                    names = meta?.cast?.takeIf { it.isNotEmpty() } ?: item.cast?.split(',', ';')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty(),
                    people = meta?.castMembers.orEmpty()
                )
            }
            item(key = "tv-ratings-${item.id}") { TvRatingsBlock(meta?.rating ?: item.rating, ratings, traktRating) }
        }
    } else Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 42.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item(key = "hero-${item.id}") { MediaDetailHero(item = item, meta = meta) }
            item(key = "main-${item.id}") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    Spacer(Modifier.height(2.dp))
                    MediaDetailMetaRow(
                        rating = meta?.rating ?: item.rating,
                        secondary = listOfNotNull(item.year, item.durationSec?.takeIf { it > 0 }?.let(::formatRuntime)).joinToString(" · ").ifBlank { null },
                        genres = metadataGenres(meta, item)
                    )
                    Button(
                        onClick = { onPlay(item, currentWatch?.resumeFromMs() ?: 0L) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                            .focusRequester(playFocusRequester)
                            .remoteFocusOutline(cornerRadius = 28.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(.95f), contentColor = Color(0xFF111111))
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if ((currentWatch?.resumeFromMs() ?: 0L) > 0L) "Riprendi" else "Riproduci il film", fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DetailActionButton(
                            icon = if (item.id in favorite.movies) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            label = if (item.id in favorite.movies) "Preferito" else "Preferiti",
                            tint = if (item.id in favorite.movies) Color(0xFFFF88A5) else DetailText,
                            modifier = Modifier.weight(1f)
                        ) { scope.launch { app.favorites.toggle(item) } }
                        DetailActionButton(
                            icon = if (settings.detailTrailerMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            label = if (settings.detailTrailerMuted) "Anteprima muta" else "Audio anteprima",
                            modifier = Modifier.weight(1f)
                        ) { scope.launch { app.prefs.saveSettings(settings.copy(detailTrailerMuted = !settings.detailTrailerMuted)) } }
                        DetailActionButton(
                            icon = if (downloadInfo?.state == WorkInfo.State.SUCCEEDED) Icons.Default.CheckCircle else Icons.Default.Download,
                            label = when (downloadInfo?.state) {
                                WorkInfo.State.RUNNING -> "${((downloadInfo?.progress?.getFloat("progress", 0f) ?: 0f) * 100).roundToInt()}%"
                                WorkInfo.State.SUCCEEDED -> "Scaricato"
                                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "In coda"
                                else -> "Scarica"
                            },
                            modifier = Modifier.weight(1f),
                            tint = if (downloadInfo?.state == WorkInfo.State.SUCCEEDED) SourcesGreenForDetail else DetailText,
                            progress = if (downloadInfo?.state == WorkInfo.State.RUNNING) downloadInfo.progress.getFloat("progress", 0f) else null
                        ) {
                            if (downloadInfo?.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
                                actionMessage = "Il download è già in coda o in corso."
                            } else {
                                downloadWorkId = runCatching { app.downloads.enqueue(item, settings.downloadWifiOnly) }.getOrNull()
                                actionMessage = if (downloadWorkId != null) "Download aggiunto alla coda." else "Impossibile avviare il download."
                            }
                        }
                        DetailActionButton(
                            icon = Icons.Default.Search,
                            label = "Altre fonti",
                            modifier = Modifier.weight(1f),
                            enabled = canSearchAlternates
                        ) { showAlternates = true }
                    }
                    actionMessage?.let { Text(it, color = DetailAccent, fontSize = 12.sp) }
                    val overview = meta?.overview?.takeIf { it.isNotBlank() } ?: item.plot?.takeIf { it.isNotBlank() }
                    if (!overview.isNullOrBlank()) {
                        Text(overview, color = DetailText.copy(.92f), fontSize = 14.sp, lineHeight = 21.sp)
                    } else if (isLoadingDetail) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = DetailAccent, trackColor = Color.White.copy(.1f))
                    }
                }
            }
            item(key = "ratings-${item.id}") {
                MediaDetailRatings(
                    tmdb = meta?.rating ?: item.rating,
                    ratings = ratings,
                    trakt = traktRating,
                    modifier = Modifier.padding(top = 22.dp)
                )
            }
            item(key = "cast-${item.id}") {
                MediaDetailCast(
                    names = meta?.cast?.takeIf { it.isNotEmpty() } ?: item.cast?.split(',', ';')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty(),
                    people = meta?.castMembers.orEmpty(),
                    modifier = Modifier.padding(top = 20.dp)
                )
            }
            if (alternates.isNotEmpty()) {
                item(key = "alternates-${item.id}") {
                    DetailSection(title = "SORGENTI ALTERNATIVE", modifier = Modifier.padding(top = 22.dp)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 20.dp)) {
                            items(alternates, key = { it.id }) { alt ->
                                LiquidGlassFocusableSurface(
                                    modifier = Modifier.width(245.dp),
                                    cornerRadius = 16.dp,
                                    contentPadding = 12.dp,
                                    onClick = { onPlay(alt, 0L) }
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(alt.title, color = glassForeground(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(alt.sourceId, color = glassForeground(.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Riproduci da questa sorgente", color = DetailAccent, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        MediaDetailScrollTopBar(title = meta?.title?.takeIf { it.isNotBlank() } ?: item.title, progress = topProgress, onClose = onBack, onMoveDown = focusPlayFromClose)
    }

    if (showAlternates) {
        AlternateSourcesView(
            title = item.title,
            kind = MediaKind.MOVIE,
            excludingSourceId = item.sourceId,
            candidates = allItems,
            configuredSources = sourceConfigs,
            onDismiss = { showAlternates = false },
            onSelect = { alternate -> onPlay(alternate, 0L) }
        )
    }
}

/** SeriesEpisodesView / detail layout: hero, actions, seasons, resumable episodes and alternatives. */
@Composable
fun SeriesDetailScreen(
    app: GassPlayerApplication,
    series: MediaItem,
    episodes: List<MediaItem>,
    favorite: FavoriteState,
    onBack: () -> Unit,
    onPlay: (MediaItem, Long) -> Unit,
    allSeries: List<MediaItem> = emptyList(),
    onOpenAlternateSeries: (MediaItem) -> Unit = {},
    entryFocusRequester: FocusRequester? = null,
    /** Metadata already known by the caller (a TMDB trending card): shown at once while details load. */
    initialMeta: MetadataResult? = null,
) {
    val remoteFirstDevice = LocalContext.current.isTvOrFireTvDevice()
    val settings by app.prefs.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    val sourceConfigs by app.sources.sources.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    // The main "Riproduci" button always owns a requester (the shell's entry point when provided), so
    // the close button can send DPAD DOWN straight to it.
    val localPlayFocusRequester = remember { FocusRequester() }
    val playFocusRequester = entryFocusRequester ?: localPlayFocusRequester
    // X (close) -> DOWN: scroll the sheet back to the top if needed and focus the play button at once.
    val focusPlayFromClose: () -> Boolean = {
        scope.launch {
            for (index in 0..2) {
                runCatching { scrollState.scrollToItem(index) }
                if (playFocusRequester.requestFocusWithRetry(attempts = 3, delayMillis = 30L)) break
            }
        }
        true
    }
    // Do not filter the entire disk-backed episode catalogue on the UI thread. The scoped repository
    // query below reads only this series' cached rows and refreshes that series in the background.
    var loadedEpisodes by remember(series.id) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loadingEpisodes by remember(series.id) { mutableStateOf(false) }
    var loadError by remember(series.id) { mutableStateOf<String?>(null) }
    var meta by remember(series.id) { mutableStateOf(initialMeta) }
    var showAlternates by remember(series.id) { mutableStateOf(false) }
    var actionMessage by remember(series.id) { mutableStateOf<String?>(null) }
    var downloadWorkId by remember(series.id) { mutableStateOf<String?>(null) }
    val downloadInfo = rememberDownloadWorkInfo(app, downloadWorkId)
    var tmdbEpisodesBySeason by remember(series.id) { mutableStateOf<Map<Int, Map<Int, TmdbEpisodeResult>>>(emptyMap()) }
    var season by remember(series.id) { mutableStateOf(0) }
    val seriesKey = series.id.substringAfterLast(':')
    val topProgress = detailTopProgress(scrollState)
    val tvLayout = isTvDetailLayout()
    LaunchedEffect(series.id, playFocusRequester, remoteFirstDevice) {
        if (remoteFirstDevice) {
            delay(90L)
            playFocusRequester.requestFocusWithRetry(attempts = 6, delayMillis = 45L)
        }
    }
    val alternates = remember(allSeries, series.id, series.title, series.sourceId) {
        allSeries.sameTitleAs(series).filter { it.kind == MediaKind.SERIES && it.sourceId != series.sourceId && it.title.normalizedMediaTitle() == series.title.normalizedMediaTitle() }.distinctBy { it.id }
    }
    val canSearchAlternates = remember(sourceConfigs, series.sourceId) {
        sourceConfigs.any { it.isEnabled && it.type == SourceType.XTREAM && it.id != series.sourceId }
    }

    suspend fun fetchEpisodes() {
        loadingEpisodes = true
        loadError = null
        val result = runCatching { app.catalog.loadSeriesEpisodes(series.sourceId, seriesKey, series.title) }
        result.onSuccess { fetched ->
            loadedEpisodes = (loadedEpisodes + fetched).filter { it.seriesId == seriesKey || it.seriesId == series.id }.distinctBy { it.id }
        }.onFailure { loadError = it.message ?: "Impossibile caricare gli episodi" }
        loadingEpisodes = false
    }

    LaunchedEffect(series.id, settings.tmdbApiKey) {
        meta = (if (settings.tmdbApiKey.isBlank()) null else {
            val direct = series.tmdbId?.takeIf { it.isNotBlank() }?.let { id -> runCatching { app.tmdb.details(id, settings.tmdbApiKey, true) }.getOrNull() }
            direct ?: runCatching { app.tmdb.searchSeries(series.title, settings.tmdbApiKey).firstOrNull() }.getOrNull()?.let { found ->
                runCatching { app.tmdb.details(found.id, settings.tmdbApiKey, true) }.getOrNull() ?: found
            }
        }) ?: meta // keep the metadata already shown if the request fails
    }
    LaunchedEffect(series.id) { fetchEpisodes() }
    LaunchedEffect(meta?.id, season, settings.tmdbApiKey) {
        val tvId = meta?.id ?: return@LaunchedEffect
        if (settings.tmdbApiKey.isBlank() || season < 0 || season in tmdbEpisodesBySeason) return@LaunchedEffect
        val seasonEpisodes = runCatching { app.tmdb.seasonEpisodes(tvId, season, settings.tmdbApiKey).associateBy { it.episodeNumber } }.getOrDefault(emptyMap())
        tmdbEpisodesBySeason = tmdbEpisodesBySeason + (season to seasonEpisodes)
    }
    val sortedEpisodes = remember(loadedEpisodes) {
        loadedEpisodes.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }, { it.title.lowercase(Locale.ROOT) }))
    }
    val seasons = remember(sortedEpisodes) { sortedEpisodes.groupBy { it.seasonNumber ?: 1 }.toSortedMap() }
    LaunchedEffect(seasons.keys) {
        if (seasons.isNotEmpty() && season !in seasons.keys) season = seasons.keys.first()
    }
    val visibleEpisodes = seasons[season].orEmpty()
    val allWatch = remember(watch, sortedEpisodes) {
        val byId = watch.associateBy { it.contentId }
        sortedEpisodes.mapNotNull { ep -> byId[ep.id]?.let { ep.id to it } }.toMap()
    }
    // Episodio da riprendere: l'ultimo aperto; se è stato visto fino in fondo si passa al successivo (iOS).
    val resumeEpisode = remember(sortedEpisodes, allWatch) {
        val last = sortedEpisodes.filter { allWatch[it.id] != null }.maxByOrNull { allWatch[it.id]?.lastWatchedMs ?: 0L }
        if (last != null && allWatch[last.id]?.isCompletedWatch() == true) {
            sortedEpisodes.getOrNull(sortedEpisodes.indexOfFirst { it.id == last.id } + 1)
        } else last
    }
    // La scheda si apre sulla stagione dell'episodio da riprendere.
    var seasonFollowsResume by remember(series.id) { mutableStateOf(true) }
    LaunchedEffect(resumeEpisode?.id, seasons.keys) {
        val resumeSeason = resumeEpisode?.let { it.seasonNumber ?: 1 }
        if (seasonFollowsResume && resumeSeason != null && resumeSeason in seasons.keys) season = resumeSeason
    }

    // Immagine di "Continua a guardare" allineata a quella della scheda (SeriesEpisodesView.swift: updateImage).
    val detailImage = meta?.backdropUrl ?: series.backdropUrl
    LaunchedEffect(detailImage, watch, sortedEpisodes) {
        val url = detailImage?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val episodeIds = sortedEpisodes.mapTo(HashSet()) { it.id }
        val matches: (WatchEntry) -> Boolean = { entry ->
            entry.contentId in episodeIds || (entry.seriesId == seriesKey && entry.sourceId == series.sourceId)
        }
        if (watch.any { matches(it) && it.imageUrl != url }) app.watch.updateImage(url, matches)
    }

    if (tvLayout) {
        val resumeState = resumeEpisode?.let { allWatch[it.id] }
        val playTarget = resumeEpisode ?: sortedEpisodes.firstOrNull()
        val episodesRowState = androidx.compose.foundation.lazy.rememberLazyListState()
        val seriesRail = LocalNavRailFocusRequester.current
        val railLeftNav = Modifier.focusProperties { if (seriesRail != null) left = seriesRail }
        // Riga episodi già posizionata sull'episodio da riprendere.
        LaunchedEffect(season, visibleEpisodes.size, resumeEpisode?.id) {
            val index = visibleEpisodes.indexOfFirst { it.id == resumeEpisode?.id }
            runCatching { episodesRowState.scrollToItem(index.coerceAtLeast(0)) }
        }
        TvMediaDetailLayout(
            item = series,
            meta = meta,
            listState = scrollState,
            rating = meta?.rating ?: series.rating,
            secondary = listOfNotNull(meta?.year ?: series.year, seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 stagione" else "$it stagioni" }).joinToString(" · ").ifBlank { null },
            genres = metadataGenres(meta, series),
            overview = meta?.overview?.takeIf { it.isNotBlank() } ?: series.plot?.takeIf { it.isNotBlank() },
            loading = meta == null && loadingEpisodes,
            playLabel = when {
                resumeEpisode != null && (allWatch[resumeEpisode.id]?.resumeFromMs() ?: 0L) > 0L ->
                    "Riprendi S${resumeEpisode.seasonNumber ?: 1} E${resumeEpisode.episodeNumber ?: 1}"
                else -> "Riproduci S${playTarget?.seasonNumber ?: 1} E${playTarget?.episodeNumber ?: 1}"
            },
            playProgress = resumeState?.watchedFraction(),
            playFocusRequester = playFocusRequester,
            onPlay = {
                if (playTarget != null) onPlay(playTarget, allWatch[playTarget.id]?.resumeFromMs() ?: 0L)
                else actionMessage = "Nessun episodio disponibile per questa serie."
            },
            actions = listOf(
                TvDetailAction(
                    icon = if (series.id in favorite.series) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = if (series.id in favorite.series) "Preferita" else "Preferiti",
                    onClick = { scope.launch { app.favorites.toggle(series) } },
                    tint = if (series.id in favorite.series) Color(0xFFFF88A5) else DetailText
                ),
                TvDetailAction(Icons.Default.Search, "Altre fonti", onClick = { showAlternates = true }, enabled = canSearchAlternates),
                TvDetailAction(Icons.Default.Refresh, if (loadingEpisodes) "Aggiorno…" else "Aggiorna", onClick = { if (!loadingEpisodes) scope.launch { fetchEpisodes() } }),
            ),
            actionMessage = actionMessage ?: loadError,
            onClose = onBack,
        ) {
            if (seasons.size > 1) {
                item(key = "tv-seasons-${series.id}") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvRowHeader("STAGIONI")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = TvRowInset)) {
                            itemsIndexed(seasons.keys.toList(), key = { _, number -> number }) { index, number ->
                                TvSeasonChip(
                                    label = if (number == 0) "Speciali" else "Stagione $number",
                                    selected = number == season,
                                    onClick = { seasonFollowsResume = false; season = number },
                                    modifier = if (index == 0) railLeftNav else Modifier
                                )
                            }
                        }
                    }
                }
            }
            item(key = "tv-episodes-${series.id}") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvRowHeader(
                        if (visibleEpisodes.isEmpty()) "EPISODI"
                        else "${if (season == 0) "SPECIALI" else "STAGIONE $season"} · ${visibleEpisodes.size} EPISODI"
                    )
                    when {
                        loadingEpisodes && sortedEpisodes.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.CenterStart) {
                            CircularProgressIndicator(color = DetailAccent)
                        }
                        visibleEpisodes.isEmpty() -> Text(
                            "Nessun episodio disponibile. Controlla la connessione alla sorgente e premi Aggiorna.",
                            color = DetailMuted,
                            modifier = Modifier.padding(horizontal = TvRowInset)
                        )
                        else -> LazyRow(
                            state = episodesRowState,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(horizontal = TvRowInset, vertical = 8.dp)
                        ) {
                            itemsIndexed(visibleEpisodes, key = { _, ep -> ep.id }) { index, ep ->
                                val state = allWatch[ep.id]
                                val fraction = state?.watchedFraction() ?: 0f
                                val tmdbFallback = tmdbEpisodesBySeason[season]?.get(ep.episodeNumber ?: 0)
                                TvEpisodeCard(
                                    episode = ep.copy(
                                        posterUrl = ep.posterUrl ?: tmdbFallback?.stillUrl,
                                        plot = ep.plot?.takeIf { it.isNotBlank() } ?: tmdbFallback?.overview,
                                        year = ep.year?.takeIf { it.isNotBlank() } ?: tmdbFallback?.airDate
                                    ),
                                    progress = fraction,
                                    isResume = ep.id == resumeEpisode?.id,
                                    modifier = if (index == 0) railLeftNav else Modifier,
                                    tmdbEpisode = tmdbFallback,
                                    onPlay = { onPlay(ep, state?.resumeFromMs() ?: 0L) },
                                    onDownload = {
                                        val id = runCatching { app.downloads.enqueue(ep, settings.downloadWifiOnly) }.getOrNull()
                                        actionMessage = if (id != null) "Download di ${ep.title} aggiunto alla coda." else "Impossibile avviare il download."
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "tv-series-cast-${series.id}") {
                TvCastRow(
                    names = meta?.cast?.takeIf { it.isNotEmpty() } ?: series.cast?.split(',', ';')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty(),
                    people = meta?.castMembers.orEmpty()
                )
            }
            item(key = "tv-series-ratings-${series.id}") { TvRatingsBlock(meta?.rating ?: series.rating, null, null) }
        }
    } else Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item(key = "series-hero-${series.id}") { MediaDetailHero(item = series, meta = meta) }
            item(key = "series-main-${series.id}") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MediaDetailMetaRow(
                        rating = meta?.rating ?: series.rating,
                        secondary = series.year,
                        genres = metadataGenres(meta, series)
                    )
                    Button(
                        onClick = {
                            val ep = resumeEpisode ?: sortedEpisodes.firstOrNull()
                            if (ep != null) onPlay(ep, allWatch[ep.id]?.resumeFromMs() ?: 0L)
                            else actionMessage = "Nessun episodio disponibile per questa serie."
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                            .focusRequester(playFocusRequester)
                            .remoteFocusOutline(cornerRadius = 28.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(.95f), contentColor = Color(0xFF111111))
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if ((resumeEpisode?.let { allWatch[it.id]?.resumeFromMs() } ?: 0L) > 0L) "Riprendi episodio" else if (resumeEpisode != null) "Riproduci episodio" else "Riproduci il primo episodio", fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DetailActionButton(
                            icon = if (series.id in favorite.series) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            label = if (series.id in favorite.series) "Preferita" else "Preferiti",
                            tint = if (series.id in favorite.series) Color(0xFFFF88A5) else DetailText,
                            modifier = Modifier.weight(1f)
                        ) { scope.launch { app.favorites.toggle(series) } }
                        DetailActionButton(
                            icon = if (settings.detailTrailerMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            label = if (settings.detailTrailerMuted) "Anteprima muta" else "Audio anteprima",
                            modifier = Modifier.weight(1f)
                        ) { scope.launch { app.prefs.saveSettings(settings.copy(detailTrailerMuted = !settings.detailTrailerMuted)) } }
                        DetailActionButton(
                            icon = if (downloadInfo?.state == WorkInfo.State.SUCCEEDED) Icons.Default.CheckCircle else Icons.Default.Download,
                            label = when (downloadInfo?.state) {
                                WorkInfo.State.RUNNING -> "${((downloadInfo?.progress?.getFloat("progress", 0f) ?: 0f) * 100).roundToInt()}%"
                                WorkInfo.State.SUCCEEDED -> "Scaricato"
                                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "In coda"
                                else -> "Scarica"
                            },
                            modifier = Modifier.weight(1f),
                            tint = if (downloadInfo?.state == WorkInfo.State.SUCCEEDED) SourcesGreenForDetail else DetailText,
                            progress = if (downloadInfo?.state == WorkInfo.State.RUNNING) downloadInfo.progress.getFloat("progress", 0f) else null
                        ) {
                            if (downloadInfo?.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
                                actionMessage = "Il download è già in coda o in corso."
                            } else {
                                val ep = resumeEpisode ?: sortedEpisodes.firstOrNull()
                                if (ep == null) actionMessage = "Nessun episodio scaricabile."
                                else {
                                    downloadWorkId = runCatching { app.downloads.enqueue(ep, settings.downloadWifiOnly) }.getOrNull()
                                    actionMessage = if (downloadWorkId != null) "Download di ${ep.title} aggiunto alla coda." else "Impossibile avviare il download."
                                }
                            }
                        }
                        DetailActionButton(Icons.Default.Search, "Altre fonti", modifier = Modifier.weight(1f), enabled = canSearchAlternates) {
                            showAlternates = true
                        }
                    }
                    actionMessage?.let { Text(it, color = DetailAccent, fontSize = 12.sp) }
                    val overview = meta?.overview?.takeIf { it.isNotBlank() } ?: series.plot?.takeIf { it.isNotBlank() }
                    if (!overview.isNullOrBlank()) Text(overview, color = DetailText.copy(.92f), fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
            item(key = "series-ratings-${series.id}") {
                MediaDetailRatings(meta?.rating ?: series.rating, null, null, Modifier.padding(top = 22.dp))
            }
            item(key = "series-cast-${series.id}") {
                MediaDetailCast(
                    names = meta?.cast?.takeIf { it.isNotEmpty() } ?: series.cast?.split(',', ';')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty(),
                    people = meta?.castMembers.orEmpty(),
                    modifier = Modifier.padding(top = 20.dp)
                )
            }
            item(key = "season-header-${series.id}") {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    Text("STAGIONI ED EPISODI", color = DetailMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(10.dp))
                    if (seasons.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 20.dp)) {
                            items(seasons.keys.toList(), key = { it }) { number ->
                                FilterChip(
                                    selected = number == season,
                                    onClick = { season = number },
                                    label = { Text(if (number == 0) "Stagione speciale" else "Stagione $number") },
                                    modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp)
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${visibleEpisodes.size} episodi", color = DetailMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { fetchEpisodes() } }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp), enabled = !loadingEpisodes) {
                            if (loadingEpisodes) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("Aggiorna")
                        }
                    }
                    loadError?.let { Text(it, color = Color(0xFFFF9E9E), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
                }
            }
            if (loadingEpisodes && sortedEpisodes.isEmpty()) {
                item(key = "loading-episodes-${series.id}") { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = DetailAccent) } }
            } else if (visibleEpisodes.isEmpty()) {
                item(key = "empty-episodes-${series.id}") {
                    Text("Nessun episodio disponibile. Controlla la connessione alla sorgente e aggiorna la serie.", color = DetailMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                }
            } else {
                items(visibleEpisodes, key = { it.id }) { ep ->
                    val state = allWatch[ep.id]
                    val fraction = state?.watchedFraction() ?: 0f
                    val tmdbFallback = tmdbEpisodesBySeason[season]?.get(ep.episodeNumber ?: 0)
                    val displayEpisode = ep.copy(
                        posterUrl = ep.posterUrl ?: tmdbFallback?.stillUrl,
                        plot = ep.plot?.takeIf { it.isNotBlank() } ?: tmdbFallback?.overview,
                        year = ep.year?.takeIf { it.isNotBlank() } ?: tmdbFallback?.airDate
                    )
                    EpisodeDetailRow(
                        episode = displayEpisode,
                        progress = fraction,
                        tmdbEpisode = tmdbFallback,
                        onPlay = { onPlay(ep, state?.resumeFromMs() ?: 0L) },
                        onDownload = {
                            val id = runCatching { app.downloads.enqueue(ep, settings.downloadWifiOnly) }.getOrNull()
                            actionMessage = if (id != null) "Download di ${ep.title} aggiunto alla coda." else "Impossibile avviare il download."
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
        MediaDetailScrollTopBar(title = meta?.title?.takeIf { it.isNotBlank() } ?: series.title, progress = topProgress, onClose = onBack, onMoveDown = focusPlayFromClose)
    }

    if (showAlternates) {
        AlternateSourcesView(
            title = series.title,
            kind = MediaKind.SERIES,
            excludingSourceId = series.sourceId,
            candidates = allSeries,
            configuredSources = sourceConfigs,
            onDismiss = { showAlternates = false },
            onSelect = { alternate -> onOpenAlternateSeries(alternate) }
        )
    }
}

@Composable
private fun MediaDetailHero(item: MediaItem, meta: MetadataResult?, height: androidx.compose.ui.unit.Dp = DetailHeroHeight) {
    val screenBackground = Color.Black
    Box(Modifier.fillMaxWidth().height(height).background(screenBackground)) {
        val image = meta?.backdropUrl ?: item.backdropUrl ?: item.posterUrl
        if (!image.isNullOrBlank()) AsyncImage(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // SwiftUI uses a short fade at the bottom of the edge-to-edge hero, not a full-image dark veil.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp).background(
                Brush.verticalGradient(listOf(Color.Transparent, screenBackground.copy(.4f), screenBackground.copy(.9f), screenBackground))
            )
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val logoUrl = meta?.logoUrl
            if (!logoUrl.isNullOrBlank()) {
                AsyncImage(logoUrl, contentDescription = meta?.title, modifier = Modifier.fillMaxWidth(.72f).heightIn(max = 88.dp), contentScale = ContentScale.Fit)
            } else {
                Text(
                    meta?.title?.takeIf { it.isNotBlank() } ?: item.title,
                    color = Color.White,
                    fontSize = 32.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun MediaDetailMetaRow(rating: Double?, secondary: String?, genres: List<String>) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            rating?.takeIf { it > 0 }?.let {
                Row(
                    Modifier.clip(RoundedCornerShape(7.dp)).background(Color.White.copy(.13f)).padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Star, null, tint = Color(0xFFFFD36D), modifier = Modifier.size(13.dp))
                    Text(String.format(Locale.ROOT, "%.1f", it), color = DetailText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            secondary?.takeIf { it.isNotBlank() }?.let { Text(it, color = DetailText.copy(.9f), fontSize = 13.sp) }
        }
        if (genres.isNotEmpty()) Text(genres.take(2).joinToString(", "), color = DetailMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = DetailText,
    enabled: Boolean = true,
    progress: Float? = null,
    onClick: () -> Unit
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(.7.dp, Color.White.copy(if (enabled) .18f else .07f)),
            modifier = Modifier.size(48.dp).remoteFocusOutline(cornerRadius = 50.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                LiquidGlassSurface(Modifier.fillMaxSize(), cornerRadius = 50.dp, contentPadding = 0.dp) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = label, tint = tint.copy(alpha = if (enabled) 1f else .35f), modifier = Modifier.size(19.dp))
                        if (progress != null) {
                            CircularProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxSize().padding(2.dp),
                                color = DetailAccent,
                                trackColor = Color.White.copy(.16f),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }
        Text(label, color = DetailMuted.copy(alpha = if (enabled) 1f else .45f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaDetailRatings(tmdb: Double?, ratings: Ratings?, trakt: Double? = null, modifier: Modifier = Modifier) {
    data class Badge(val id: String, val label: String, val value: String, val mark: String, val tint: Color)
    val entries = buildList {
        tmdb?.takeIf { it > 0 }?.let { add(Badge("tmdb", "TMDB", "${(it * 10).roundToInt()}%", "T", Color(0xFF01B4E4))) }
        ratings?.rottenTomatoes?.takeIf { it.isNotBlank() }?.let { add(Badge("rt", "Critiche", it, "RT", Color(0xFFF04444))) }
        trakt?.let { add(Badge("trakt", "Trakt", "${it.roundToInt()}%", "✓", Color(0xFFE31B50))) }
        ratings?.imdb?.takeIf { it > 0 }?.let { add(Badge("imdb", "IMDb", String.format(Locale.ROOT, "%.1f", it), "IMDb", Color(0xFFF5C518))) }
        ratings?.metacritic?.let { add(Badge("mc", "Metacritic", "$it", "M", Color(0xFF6B8DE3))) }
    }
    if (entries.isNotEmpty()) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailSectionHeader("VALUTAZIONI")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(horizontal = 20.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp), horizontalAlignment = Alignment.Start) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                Modifier.size(if (entry.mark == "IMDb") 28.dp else 23.dp)
                                    .clip(if (entry.mark == "RT") CircleShape else RoundedCornerShape(if (entry.mark == "IMDb") 5.dp else 7.dp))
                                    .background(entry.tint.copy(alpha = if (entry.mark == "IMDb") .95f else .18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(entry.mark, color = if (entry.mark == "IMDb") Color(0xFF171717) else entry.tint, fontSize = if (entry.mark == "IMDb") 8.sp else 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            }
                            Text(entry.label, color = DetailMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(entry.value, color = DetailText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaDetailCast(
    names: List<String>,
    people: List<MetadataCastMember> = emptyList(),
    modifier: Modifier = Modifier
) {
    val cast = remember(names, people) {
        if (people.isNotEmpty()) people.distinctBy { it.name }.take(20)
        else names.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20).map { MetadataCastMember(name = it) }
    }
    if (cast.isNotEmpty()) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailSectionHeader("CAST")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp)) {
                items(cast, key = { it.name }) { person ->
                    Row(Modifier.width(210.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(68.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF333333), Color(0xFF171717)))), contentAlignment = Alignment.Center) {
                            if (!person.profileUrl.isNullOrBlank()) {
                                AsyncImage(person.profileUrl, contentDescription = person.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Text(person.name.take(1).uppercase(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Column(Modifier.width(130.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(person.name, color = DetailText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            person.character?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = DetailMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = {
        DetailSectionHeader(title)
        content()
    })
}

@Composable
private fun DetailSectionHeader(title: String) {
    Text(title, color = DetailMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
private fun EpisodeDetailRow(
    episode: MediaItem,
    progress: Float,
    tmdbEpisode: TmdbEpisodeResult? = null,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(
        onClick = onPlay,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp).remoteFocusOutline(cornerRadius = 17.dp),
        shape = RoundedCornerShape(17.dp),
        color = Color.Transparent,
        border = BorderStroke(.65.dp, Color.White.copy(.13f))
    ) {
        LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 17.dp, contentPadding = 0.dp) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp)).background(Color(0xFF111111))) {
                    val image = episode.backdropUrl ?: episode.posterUrl
                    if (!image.isNullOrBlank()) {
                        AsyncImage(image, contentDescription = episode.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF222222), Color(0xFF080808)))), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(.7f), modifier = Modifier.size(34.dp))
                        }
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.08f), Color.Black.copy(.32f)))))
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                    if (progress > 0f) IosEpisodeProgressBar(progress, Modifier.align(Alignment.BottomStart))
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                "S${episode.seasonNumber ?: tmdbEpisode?.seasonNumber ?: 1} · E${episode.episodeNumber ?: tmdbEpisode?.episodeNumber ?: 0}",
                                color = DetailMuted,
                                fontSize = 13.sp
                            )
                            Text(episode.title, color = DetailText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = onDownload, modifier = Modifier.size(38.dp).remoteFocusOutline(cornerRadius = 50.dp)) {
                            Icon(Icons.Default.Download, "Scarica episodio", tint = DetailText.copy(.88f), modifier = Modifier.size(19.dp))
                        }
                    }
                    val plot = episode.plot?.takeIf { it.isNotBlank() } ?: tmdbEpisode?.overview?.takeIf { it.isNotBlank() }
                    if (!plot.isNullOrBlank()) Text(plot, color = DetailMuted, fontSize = 14.sp, lineHeight = 20.sp)
                    val releaseDate = formatEpisodeDate(episode.year?.takeIf { it.isNotBlank() } ?: tmdbEpisode?.airDate)
                    if (!releaseDate.isNullOrBlank()) Text(releaseDate, color = DetailText.copy(.82f), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

private fun formatEpisodeDate(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val raw = value.trim()
    return runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(raw) ?: return@runCatching raw
        SimpleDateFormat("d MMM yyyy", Locale.ITALIAN).format(parsed)
    }.getOrElse { raw }
}

@Composable
private fun MediaDetailScrollTopBar(title: String, progress: Float, onClose: () -> Unit, onMoveDown: (() -> Boolean)? = null) {
    val alpha by animateFloatAsState(progress.coerceIn(0f, 1f), label = "media-detail-topbar-progress")
    val safeTop = WindowInsets.statusBars.getTop(LocalDensity.current).let { with(LocalDensity.current) { it.toDp() } }
    val rowHeight = 44.dp
    val tailHeight = 26.dp
    Box(Modifier.fillMaxWidth().height(safeTop + rowHeight + tailHeight).padding(horizontal = 10.dp)) {
        // Android equivalent of SwiftUI's thinMaterial: full-width dark glass with a feathered lower edge.
        Box(
            Modifier.fillMaxWidth().height(safeTop + rowHeight + tailHeight).graphicsLayer { this.alpha = alpha }.background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(.88f),
                        .62f to Color.Black.copy(.72f),
                        1f to Color.Black.copy(.02f)
                    )
                )
            )
        )
        Text(
            title,
            color = Color.White.copy(alpha = alpha),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = safeTop, start = 62.dp, end = 62.dp).height(rowHeight).wrapContentHeight(Alignment.CenterVertically).graphicsLayer { this.alpha = alpha }
        )
        Surface(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = safeTop).size(rowHeight)
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onMoveDown?.invoke() == true
                }
                .remoteFocusOutline(cornerRadius = 50.dp),
            shape = CircleShape,
            color = Color.White.copy(.10f),
            border = BorderStroke(.7.dp, Color.White.copy(.27f))
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = Color.White, modifier = Modifier.size(19.dp)) }
        }
    }
}

@Composable
private fun detailTopProgress(state: androidx.compose.foundation.lazy.LazyListState): Float {
    val density = LocalDensity.current
    val heroPx = with(density) { DetailHeroHeight.toPx() }
    val safeTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val startPx = heroPx - with(density) { 14.dp.toPx() } - safeTopPx - with(density) { 100.dp.toPx() }
    val rampPx = with(density) { 60.dp.toPx() }.coerceAtLeast(1f)
    return remember(state, heroPx, safeTopPx, startPx, rampPx) {
        derivedStateOf {
            val scrolled = if (state.firstVisibleItemIndex > 0) heroPx + state.firstVisibleItemScrollOffset.toFloat() else state.firstVisibleItemScrollOffset.toFloat()
            ((scrolled - startPx) / rampPx).coerceIn(0f, 1f)
        }
    }.value
}

private val SourcesGreenForDetail = Color(0xFF63D995)

@Composable
private fun rememberDownloadWorkInfo(app: GassPlayerApplication, workId: String?): WorkInfo? {
    var result by remember(workId) { mutableStateOf<WorkInfo?>(null) }
    LaunchedEffect(workId) {
        result = null
        val uuid = workId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@LaunchedEffect
        runCatching {
            WorkManager.getInstance(app).getWorkInfoByIdFlow(uuid).collect { result = it }
        }
    }
    return result
}

private fun metadataGenres(meta: MetadataResult?, item: MediaItem): List<String> =
    meta?.genres?.takeIf { it.isNotEmpty() }
        ?: item.genre?.split(',', ';', '|')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

private fun formatRuntime(seconds: Long): String {
    val totalMinutes = seconds / 60
    return if (totalMinutes >= 60) "${totalMinutes / 60} h ${totalMinutes % 60} min" else "$totalMinutes min"
}

private fun String.normalizedMediaTitle(): String = lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

// MARK: - Layout TV (Android TV / Fire TV)
//
// Su TV la scheda mobile (hero 16:9 ritagliato a tutta larghezza, pulsante "Riproduci" a tutta larghezza,
// episodi in colonna a tutta larghezza) nascondeva gran parte del poster e obbligava a lunghe discese col
// telecomando. Qui: sfondo backdrop a schermo intero, poster 2:3 SEMPRE intero a sinistra, informazioni e
// azioni su UNA riga orizzontale (DPAD sinistra/destra), stagioni ed episodi in righe orizzontali, ogni riga
// raggiungibile con un solo DPAD giù. Il tasto Indietro chiude la scheda.

/** Margine orizzontale delle righe TV (le sezioni condivise Valutazioni/Cast hanno già 20 dp interni). */
private val TvListPadding = 28.dp
private val TvRowInset = 20.dp
private val TvFocusAccent = Color(0xFF8CB7FF)

/** Un'azione della riga principale della scheda TV. */
private class TvDetailAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val tint: Color = DetailText,
    val enabled: Boolean = true,
)

@Composable
private fun isTvDetailLayout(): Boolean {
    val context = LocalContext.current
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    return remember(context, widthDp) { context.isTvOrFireTvDevice() || widthDp >= 900 }
}

@Composable
private fun TvMediaDetailLayout(
    item: MediaItem,
    meta: MetadataResult?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    rating: Double?,
    secondary: String?,
    genres: List<String>,
    overview: String?,
    loading: Boolean,
    playLabel: String,
    playProgress: Float?,
    playFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    actions: List<TvDetailAction>,
    actionMessage: String?,
    onClose: () -> Unit,
    rows: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    // Navigazione esplicita col telecomando: SU dai pulsanti principali = "X"; SINISTRA da "Riproduci" (e dal
    // primo elemento delle righe sotto) = barra laterale. La ricerca spaziale sceglieva altro (righe episodi).
    val closeFocusRequester = remember { FocusRequester() }
    val railFocusRequester = LocalNavRailFocusRequester.current
    val actionNav = Modifier.focusProperties { up = closeFocusRequester }
    val playNav = Modifier.focusProperties {
        up = closeFocusRequester
        if (railFocusRequester != null) left = railFocusRequester
    }
    val backdrop = meta?.backdropUrl ?: item.backdropUrl
    val poster = meta?.posterUrl ?: item.posterUrl
    val title = meta?.title?.takeIf { it.isNotBlank() } ?: item.title

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(backdrop, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .62f)
        }
        // Velo a sinistra (testo leggibile) e in basso (righe di episodi/cast leggibili), immagine visibile a destra.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.Black.copy(.94f), .42f to Color.Black.copy(.74f), 1f to Color.Black.copy(.18f))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, .5f to Color.Black.copy(.25f), 1f to Color.Black.copy(.92f))
            )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = TvListPadding, end = TvListPadding, top = 30.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            item(key = "tv-header-${item.id}") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = TvRowInset),
                    horizontalArrangement = Arrangement.spacedBy(34.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    TvDetailPoster(poster = poster, fallback = backdrop, title = title)
                    Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val logoUrl = meta?.logoUrl
                        if (!logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                logoUrl, contentDescription = title,
                                modifier = Modifier.widthIn(max = 380.dp).heightIn(max = 92.dp),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart
                            )
                        } else {
                            Text(title, color = Color.White, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TvDetailMetaLine(rating = rating, secondary = secondary, genres = genres)
                        if (!overview.isNullOrBlank()) {
                            Text(overview, color = DetailText.copy(.9f), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 640.dp))
                        } else if (loading) {
                            LinearProgressIndicator(Modifier.width(260.dp), color = DetailAccent, trackColor = Color.White.copy(.1f))
                        }
                        Spacer(Modifier.height(2.dp))
                        // Riga azioni: tornandoci da una riga più in basso, la scheda torna in cima (poster intero).
                        Row(
                            Modifier.onFocusChanged { if (it.hasFocus) scope.launch { runCatching { listState.animateScrollToItem(0) } } },
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvPlayButton(playLabel, playProgress, playFocusRequester, onPlay, modifier = playNav)
                            actions.forEach { action -> TvActionButton(action, modifier = actionNav) }
                        }
                        actionMessage?.let { Text(it, color = DetailAccent, fontSize = 13.sp) }
                    }
                }
            }
            rows()
        }

        // Chiudi (anche il tasto Indietro chiude): GIÙ porta subito su "Riproduci".
        TvCloseButton(
            onClick = onClose,
            focusRequester = closeFocusRequester,
            onMoveDown = {
                scope.launch {
                    runCatching { listState.scrollToItem(0) }
                    playFocusRequester.requestFocusWithRetry(attempts = 4, delayMillis = 30L)
                }
                true
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 26.dp)
        )
    }
}

@Composable
private fun TvDetailPoster(poster: String?, fallback: String?, title: String) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .width(206.dp)
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF262626), Color(0xFF0C0C0C))))
            .border(BorderStroke(.8.dp, Color.White.copy(.22f)), shape),
        contentAlignment = Alignment.Center
    ) {
        when {
            // Il poster è 2:3 come il riquadro: si vede intero.
            !poster.isNullOrBlank() -> AsyncImage(poster, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Solo un'immagine orizzontale: intera (Fit), mai ritagliata.
            !fallback.isNullOrBlank() -> AsyncImage(fallback, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else -> Text(title.take(1).uppercase(), color = Color.White.copy(.7f), fontSize = 54.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TvDetailMetaLine(rating: Double?, secondary: String?, genres: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        rating?.takeIf { it > 0 }?.let {
            Row(
                Modifier.clip(RoundedCornerShape(7.dp)).background(Color.White.copy(.14f)).padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Star, null, tint = Color(0xFFFFD36D), modifier = Modifier.size(14.dp))
                Text(String.format(Locale.ROOT, "%.1f", it), color = DetailText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        secondary?.takeIf { it.isNotBlank() }?.let { Text(it, color = DetailText.copy(.9f), fontSize = 14.sp) }
        if (genres.isNotEmpty()) Text(genres.take(3).joinToString(" · "), color = DetailMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Stato di focus TV: chiaro con testo scuro quando focalizzato (stile tvOS), vetro scuro altrimenti. */
@Composable
private fun TvPillSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tv-pill-focus")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else Color.White.copy(if (enabled) .13f else .06f))
            .border(BorderStroke(.8.dp, Color.White.copy(if (focused) 0f else .22f)), RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickableCompat(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content(focused) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(enabled: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    this.then(
        Modifier.combinedClickable(
            interactionSource = null,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick
        )
    )

@Composable
private fun TvPlayButton(label: String, progress: Float?, focusRequester: FocusRequester, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvPillSurface(onClick = onClick, modifier = modifier.focusRequester(focusRequester)) { focused ->
        val color = if (focused) Color(0xFF111111) else Color.White
        Icon(Icons.Default.PlayArrow, null, tint = color, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (progress != null && progress > 0f) {
                Box(Modifier.width(110.dp).height(3.dp).clip(RoundedCornerShape(50)).background(color.copy(.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(color))
                }
            }
        }
    }
}

@Composable
private fun TvActionButton(action: TvDetailAction, modifier: Modifier = Modifier) {
    TvPillSurface(onClick = action.onClick, enabled = action.enabled, modifier = modifier) { focused ->
        val color = when {
            focused -> Color(0xFF111111)
            !action.enabled -> DetailText.copy(.35f)
            else -> action.tint
        }
        Icon(action.icon, contentDescription = action.label, tint = color, modifier = Modifier.size(19.dp))
        Text(action.label, color = if (focused) Color(0xFF111111) else DetailText.copy(if (action.enabled) .95f else .35f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun TvCloseButton(onClick: () -> Unit, onMoveDown: () -> Boolean, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.size(44.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onMoveDown() },
        shape = CircleShape,
        color = if (focused) Color.White else Color.White.copy(.10f),
        border = BorderStroke(.7.dp, Color.White.copy(if (focused) 0f else .27f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = if (focused) Color(0xFF111111) else Color.White, modifier = Modifier.size(19.dp))
        }
    }
}

/** Intestazione di una riga TV (stesso stile delle sezioni della scheda). */
@Composable
private fun TvRowHeader(title: String, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = TvRowInset), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = DetailMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
private fun TvSeasonChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvPillSurface(onClick = onClick, modifier = modifier) { focused ->
        Text(
            label,
            color = when {
                focused -> Color(0xFF111111)
                selected -> Color.White
                else -> DetailText.copy(.72f)
            },
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
        if (selected && !focused) Box(Modifier.size(6.dp).clip(CircleShape).background(DetailAccent))
    }
}

/** Card episodio TV: 16:9 intera, poi S·E, titolo, trama (2 righe). Pressione lunga = scarica episodio. */
@Composable
private fun TvEpisodeCard(
    episode: MediaItem,
    progress: Float,
    isResume: Boolean,
    tmdbEpisode: TmdbEpisodeResult?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "tv-episode-focus")
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .width(286.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickableCompat(enabled = true, onClick = onPlay, onLongClick = onDownload),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF222222), Color(0xFF080808))))
                .border(BorderStroke(if (focused) 2.5.dp else .7.dp, if (focused) TvFocusAccent else Color.White.copy(.16f)), shape),
            contentAlignment = Alignment.Center
        ) {
            val image = episode.backdropUrl ?: episode.posterUrl
            if (!image.isNullOrBlank()) AsyncImage(image, contentDescription = episode.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(.7f), modifier = Modifier.size(34.dp))
            if (focused) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(.18f)))
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
            if (isResume) {
                Text(
                    "RIPRENDI",
                    color = Color(0xFF111111),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(5.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (progress > 0f) IosEpisodeProgressBar(progress, Modifier.align(Alignment.BottomStart))
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "S${episode.seasonNumber ?: tmdbEpisode?.seasonNumber ?: 1} · E${episode.episodeNumber ?: tmdbEpisode?.episodeNumber ?: 0}",
                color = if (focused) DetailAccent else DetailMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(episode.title, color = DetailText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val plot = episode.plot?.takeIf { it.isNotBlank() } ?: tmdbEpisode?.overview?.takeIf { it.isNotBlank() }
            if (!plot.isNullOrBlank()) Text(plot, color = DetailMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val releaseDate = formatEpisodeDate(episode.year?.takeIf { it.isNotBlank() } ?: tmdbEpisode?.airDate)
            if (!releaseDate.isNullOrBlank()) Text(releaseDate, color = DetailText.copy(.7f), fontSize = 12.sp)
        }
    }
}

/** Cast con elementi focalizzabili: la riga si scorre col telecomando (prima non era raggiungibile). */
@Composable
private fun TvCastRow(names: List<String>, people: List<MetadataCastMember>) {
    val cast = remember(names, people) {
        if (people.isNotEmpty()) people.distinctBy { it.name }.take(20)
        else names.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20).map { MetadataCastMember(name = it) }
    }
    if (cast.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvRowHeader("CAST")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = TvRowInset)) {
            items(cast, key = { it.name }) { person ->
                var focused by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .width(230.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (focused) Color.White.copy(.16f) else Color.Transparent)
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF333333), Color(0xFF171717))))
                            .border(BorderStroke(if (focused) 2.5.dp else 0.dp, if (focused) TvFocusAccent else Color.Transparent), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!person.profileUrl.isNullOrBlank()) AsyncImage(person.profileUrl, contentDescription = person.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(person.name.take(1).uppercase(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(person.name, color = DetailText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        person.character?.takeIf { it.isNotBlank() }?.let { Text(it, color = DetailMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** Valutazioni in un riquadro focalizzabile, così anche l'ultima riga della scheda si raggiunge col DPAD. */
@Composable
private fun TvRatingsBlock(tmdb: Double?, ratings: Ratings?, trakt: Double?) {
    val hasAny = (tmdb ?: 0.0) > 0 || ratings?.rottenTomatoes?.isNotBlank() == true || trakt != null ||
        (ratings?.imdb ?: 0.0) > 0 || ratings?.metacritic != null
    if (!hasAny) return
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) Color.White.copy(.07f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(vertical = 8.dp)
    ) {
        MediaDetailRatings(tmdb = tmdb, ratings = ratings, trakt = trakt)
    }
}

/**
 * `episodeProgressBar` di SeriesEpisodesView.swift: capsula bianca alta 5, rientrata di 10 dai lati e dal
 * fondo della miniatura, larga quanto il punto in cui ci si è fermati (minimo 3), piena se l'episodio è
 * stato visto fino in fondo.
 */
@Composable
private fun IosEpisodeProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
        val width = (maxWidth * fraction.coerceIn(0f, 1f)).coerceAtLeast(3.dp)
        Box(Modifier.width(width).height(5.dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

/** Posizione da cui riprendere (PlaybackPositionStore.position iOS): 0 se < 5 s o se visto fino in fondo. */
internal fun WatchEntry.resumeFromMs(): Long =
    if (kind == MediaKind.LIVE || positionMs <= 5_000L || isCompletedWatch()) 0L else positionMs

/** Visto fino in fondo (o fino agli ultimi ~45 s), come `isCompleted` di PlaybackPositionStore. */
internal fun WatchEntry.isCompletedWatch(): Boolean = durationMs > 0L && positionMs >= durationMs - 45_000L

/** Frazione vista 0…1 per le barre di avanzamento (1 se completato), null se mai iniziato. */
internal fun WatchEntry.watchedFraction(): Float? = when {
    durationMs <= 0L -> null
    isCompletedWatch() -> 1f
    positionMs <= 0L -> null
    else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
