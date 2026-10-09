package com.gassplayer.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.getWorkInfoByIdFlow
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
    onPlay: (MediaItem, Long) -> Unit
) {
    val settings by app.prefs.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    val currentWatch = watch.firstOrNull { it.contentId == item.id }
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    var meta by remember(item.id) { mutableStateOf<MetadataResult?>(null) }
    var ratings by remember(item.id) { mutableStateOf<Ratings?>(null) }
    var traktRating by remember(item.id) { mutableStateOf<Double?>(null) }
    var isLoadingDetail by remember(item.id) { mutableStateOf(true) }
    var showAlternates by remember(item.id) { mutableStateOf(false) }
    var actionMessage by remember(item.id) { mutableStateOf<String?>(null) }
    var downloadWorkId by remember(item.id) { mutableStateOf<String?>(null) }
    val downloadInfo = rememberDownloadWorkInfo(app, downloadWorkId)
    val alternates = remember(allItems, item.id) { allItems.sameTitleAs(item).filter { it.kind == MediaKind.MOVIE } }
    val topProgress = detailTopProgress(scrollState)

    LaunchedEffect(item.id, settings.tmdbApiKey, settings.omdbApiKey, settings.traktClientId) {
        isLoadingDetail = true
        meta = if (settings.tmdbApiKey.isBlank()) null else {
            val direct = item.tmdbId?.takeIf { it.isNotBlank() }?.let { id -> runCatching { app.tmdb.details(id, settings.tmdbApiKey, false) }.getOrNull() }
            direct ?: runCatching { app.tmdb.searchMovies(item.title, settings.tmdbApiKey).firstOrNull() }.getOrNull()?.let { found ->
                runCatching { app.tmdb.details(found.id, settings.tmdbApiKey, false) }.getOrNull() ?: found
            }
        }
        ratings = runCatching { app.omdb.lookup(item.title, settings.omdbApiKey) }.getOrNull()
        traktRating = runCatching { app.trakt.ratings(item.title, settings.traktClientId) }.getOrNull()
        isLoadingDetail = false
    }

    Box(Modifier.fillMaxSize()) {
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
                        onClick = { onPlay(item, currentWatch?.positionMs ?: 0L) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).focusable(),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(.95f), contentColor = Color(0xFF111521))
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if ((currentWatch?.positionMs ?: 0L) > 0L) "Riprendi" else "Riproduci il film", fontWeight = FontWeight.SemiBold)
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
                            enabled = alternates.isNotEmpty()
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
                                    modifier = Modifier.width(245.dp).clickable { onPlay(alt, 0L) },
                                    cornerRadius = 16.dp,
                                    contentPadding = 12.dp
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
        MediaDetailScrollTopBar(title = meta?.title?.takeIf { it.isNotBlank() } ?: item.title, progress = topProgress, onClose = onBack)
    }

    if (showAlternates) {
        AlertDialog(
            onDismissRequest = { showAlternates = false },
            title = { Text("Altre fonti") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(alternates, key = { it.id }) { alt ->
                        Surface(
                            onClick = { showAlternates = false; onPlay(alt, 0L) },
                            color = Color.Transparent,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().focusable()
                        ) {
                            LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 14.dp, contentPadding = 12.dp) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(alt.title, color = glassForeground(), fontWeight = FontWeight.SemiBold)
                                    Text(alt.sourceId, color = glassForeground(.62f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAlternates = false }) { Text("Chiudi") } },
            containerColor = Color(0xFF121722),
            titleContentColor = Color.White,
            textContentColor = Color.White
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
    onOpenAlternateSeries: (MediaItem) -> Unit = {}
) {
    val settings by app.prefs.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    var loadedEpisodes by remember(series.id) { mutableStateOf(episodes.filter { it.seriesId == series.id.substringAfterLast(':') }) }
    var loadingEpisodes by remember(series.id) { mutableStateOf(false) }
    var loadError by remember(series.id) { mutableStateOf<String?>(null) }
    var meta by remember(series.id) { mutableStateOf<MetadataResult?>(null) }
    var showAlternates by remember(series.id) { mutableStateOf(false) }
    var actionMessage by remember(series.id) { mutableStateOf<String?>(null) }
    var downloadWorkId by remember(series.id) { mutableStateOf<String?>(null) }
    val downloadInfo = rememberDownloadWorkInfo(app, downloadWorkId)
    var tmdbEpisodesBySeason by remember(series.id) { mutableStateOf<Map<Int, Map<Int, TmdbEpisodeResult>>>(emptyMap()) }
    var season by remember(series.id) { mutableStateOf(0) }
    val seriesKey = series.id.substringAfterLast(':')
    val topProgress = detailTopProgress(scrollState)
    val alternates = remember(allSeries, series.id, series.title) {
        allSeries.filter { it.id != series.id && it.kind == MediaKind.SERIES && it.title.normalizedMediaTitle() == series.title.normalizedMediaTitle() }
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
        meta = if (settings.tmdbApiKey.isBlank()) null else {
            val direct = series.tmdbId?.takeIf { it.isNotBlank() }?.let { id -> runCatching { app.tmdb.details(id, settings.tmdbApiKey, true) }.getOrNull() }
            direct ?: runCatching { app.tmdb.searchSeries(series.title, settings.tmdbApiKey).firstOrNull() }.getOrNull()?.let { found ->
                runCatching { app.tmdb.details(found.id, settings.tmdbApiKey, true) }.getOrNull() ?: found
            }
        }
    }
    LaunchedEffect(series.id) { fetchEpisodes() }
    LaunchedEffect(meta?.id, season, settings.tmdbApiKey) {
        val tvId = meta?.id ?: return@LaunchedEffect
        if (settings.tmdbApiKey.isBlank() || season < 0 || season in tmdbEpisodesBySeason) return@LaunchedEffect
        val seasonEpisodes = runCatching { app.tmdb.seasonEpisodes(tvId, season, settings.tmdbApiKey).associateBy { it.episodeNumber } }.getOrDefault(emptyMap())
        tmdbEpisodesBySeason = tmdbEpisodesBySeason + (season to seasonEpisodes)
    }
    LaunchedEffect(episodes, series.id) {
        val candidates = episodes.filter { it.seriesId == seriesKey || it.seriesId == series.id }
        if (candidates.isNotEmpty() && loadedEpisodes.isEmpty()) loadedEpisodes = candidates
    }

    val sortedEpisodes = remember(loadedEpisodes) {
        loadedEpisodes.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }, { it.title.lowercase(Locale.ROOT) }))
    }
    val seasons = remember(sortedEpisodes) { sortedEpisodes.groupBy { it.seasonNumber ?: 1 }.toSortedMap() }
    LaunchedEffect(seasons.keys) {
        if (seasons.isNotEmpty() && season !in seasons.keys) season = seasons.keys.first()
    }
    val visibleEpisodes = seasons[season].orEmpty()
    val allWatch = remember(watch, sortedEpisodes) { sortedEpisodes.mapNotNull { ep -> watch.firstOrNull { it.contentId == ep.id }?.let { ep.id to it } }.toMap() }
    val resumeEpisode = remember(sortedEpisodes, allWatch) { sortedEpisodes.firstOrNull { (allWatch[it.id]?.positionMs ?: 0L) > 0L } }

    Box(Modifier.fillMaxSize()) {
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
                            if (ep != null) onPlay(ep, allWatch[ep.id]?.positionMs ?: 0L)
                            else actionMessage = "Nessun episodio disponibile per questa serie."
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).focusable(),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(.95f), contentColor = Color(0xFF111521))
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (resumeEpisode != null) "Riprendi episodio" else "Riproduci il primo episodio", fontWeight = FontWeight.SemiBold)
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
                        DetailActionButton(Icons.Default.Search, "Altre fonti", modifier = Modifier.weight(1f), enabled = alternates.isNotEmpty()) {
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
                                    modifier = Modifier.focusable()
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${visibleEpisodes.size} episodi", color = DetailMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { fetchEpisodes() } }, enabled = !loadingEpisodes) {
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
                    val fraction = if ((state?.durationMs ?: 0L) > 0L) (state!!.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
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
                        onPlay = { onPlay(ep, state?.positionMs ?: 0L) },
                        onDownload = {
                            val id = runCatching { app.downloads.enqueue(ep, settings.downloadWifiOnly) }.getOrNull()
                            actionMessage = if (id != null) "Download di ${ep.title} aggiunto alla coda." else "Impossibile avviare il download."
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
        MediaDetailScrollTopBar(title = meta?.title?.takeIf { it.isNotBlank() } ?: series.title, progress = topProgress, onClose = onBack)
    }

    if (showAlternates) {
        AlertDialog(
            onDismissRequest = { showAlternates = false },
            title = { Text("Altre fonti") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(alternates, key = { it.id }) { alt ->
                        Surface(onClick = { showAlternates = false; onOpenAlternateSeries(alt) }, modifier = Modifier.fillMaxWidth().focusable(), color = Color.Transparent, shape = RoundedCornerShape(14.dp)) {
                            LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 14.dp, contentPadding = 12.dp) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(alt.title, color = glassForeground(), fontWeight = FontWeight.SemiBold)
                                    Text(alt.sourceId, color = glassForeground(.62f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAlternates = false }) { Text("Chiudi") } },
            containerColor = Color(0xFF121722), titleContentColor = Color.White, textContentColor = Color.White
        )
    }
}

@Composable
private fun MediaDetailHero(item: MediaItem, meta: MetadataResult?, height: androidx.compose.ui.unit.Dp = DetailHeroHeight) {
    val screenBackground = Color(0xFF070910)
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
            modifier = Modifier.size(48.dp).focusable()
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
                        Box(Modifier.size(68.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF3D5278), Color(0xFF19243A)))), contentAlignment = Alignment.Center) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp).focusable(),
        shape = RoundedCornerShape(17.dp),
        color = Color.Transparent,
        border = BorderStroke(.65.dp, Color.White.copy(.13f))
    ) {
        LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 17.dp, contentPadding = 0.dp) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp)).background(Color(0xFF111723))) {
                    val image = episode.backdropUrl ?: episode.posterUrl
                    if (!image.isNullOrBlank()) {
                        AsyncImage(image, contentDescription = episode.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF263247), Color(0xFF10141D)))), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(.7f), modifier = Modifier.size(34.dp))
                        }
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.08f), Color.Black.copy(.32f)))))
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                    if (progress > 0f) {
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.White.copy(.26f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color.White))
                        }
                    }
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
                        IconButton(onClick = onDownload, modifier = Modifier.size(38.dp).focusable()) {
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
private fun MediaDetailScrollTopBar(title: String, progress: Float, onClose: () -> Unit) {
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
                        0f to Color(0xFF090C14).copy(.88f),
                        .62f to Color(0xFF090C14).copy(.72f),
                        1f to Color(0xFF090C14).copy(.02f)
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
            modifier = Modifier.align(Alignment.TopEnd).padding(top = safeTop).size(rowHeight).focusable(),
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
