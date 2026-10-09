package com.gassplayer.android.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.launch
import java.util.UUID

private val Blue = Color(0xFF3478F6)
private val Dark = Color.Black

@Composable
fun GassPlayerNavHost(vm: MainViewModel, app: GassPlayerApplication) {
    var route by remember { mutableStateOf("home") }
    var playerItem by remember { mutableStateOf<MediaItem?>(null) }
    var managerSourceId by remember { mutableStateOf<String?>(null) }
    var epgManageReturnRoute by remember { mutableStateOf("settings") }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val favorite by vm.favorites.collectAsStateWithLifecycle()
    val watch by vm.watch.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tv = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    DisposableEffect(playerItem != null, context) {
        val activity = context.closestMainActivity()
        if (playerItem != null) activity?.enterPlayerMode()
        onDispose { if (playerItem != null) activity?.exitPlayerMode() }
    }

    if (playerItem != null) {
        IosPlayerScreen(app, playerItem!!, settings, catalog, onBack = { playerItem = null }, onPip = { (context as? MainActivity)?.enterPlayerPip() }, onNavigateToItem = { playerItem = it }, onOpenSearch = { playerItem = null; route = "search" })
        return
    }

    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    AdaptiveShell(route, tv, loading, message, onRetry = { vm.refresh(true) }, onRoute = { route = it }, title = when(route) {
        "home" -> "GassPlayer"; "live" -> "Live TV"; "movies" -> "Film"; "series" -> "Serie"; "epg" -> "Guida TV"; "search" -> "Cerca"; "sources" -> "Sorgenti"; "source-manager" -> "Gestisci sorgenti"; "downloads" -> "Download"; "settings" -> "Impostazioni"; "home-customize" -> "Personalizza Home"; "live-customize" -> "Sezioni Live TV"; "movies-customize" -> "Sezioni Film"; "series-customize" -> "Sezioni Serie TV"; "vpn" -> "VPN"; "parental" -> "Controllo genitori"; "diagnostics" -> "Diagnostica"; "backup" -> "Backup e migrazione"; "epg-manage" -> "Fonti EPG"; "merged" -> "Playlist unificate"; else -> "GassPlayer"
    }) {
        when (route) {
            "home" -> HomeScreen(vm, catalog, favorite, watch, sources, onRoute = { route = it }, onPlay = { playerItem = it })
            "live" -> CatalogScreen("Live TV", catalog?.live.orEmpty(), catalog?.liveCategories.orEmpty(), favorite, parental, vm, onPlay = { playerItem = it }, showNumbers = settings.showChannelNumbers, scopeKey = "live", onCustomize = { route = "live-customize" })
            "movies" -> CatalogScreen("Film", catalog?.movies.orEmpty(), catalog?.vodCategories.orEmpty(), favorite, parental, vm, onPlay = { playerItem = it }, detail = true, scopeKey = "movies", onCustomize = { route = "movies-customize" })
            "series" -> SeriesScreen(catalog?.series.orEmpty(), catalog?.episodes.orEmpty(), catalog?.seriesCategories.orEmpty(), favorite, parental, vm, onPlay = { playerItem = it }, onCustomize = { route = "series-customize" })
            "epg" -> EpgGridScreen(app, vm, catalog?.live.orEmpty(), catalog?.liveCategories.orEmpty(), favorite, settings, onPlay = { playerItem = it })
            "epg-manage" -> ExternalEpgManageScreen(app, onBack = { route = epgManageReturnRoute })
            "merged" -> MergedPlaylistScreen(app)
            "home-customize" -> HomeCustomizationScreen(vm, "home", onBack = { route = "home" })
            "live-customize" -> HomeCustomizationScreen(vm, "live", onBack = { route = "live" })
            "movies-customize" -> HomeCustomizationScreen(vm, "movies", onBack = { route = "movies" })
            "series-customize" -> HomeCustomizationScreen(vm, "series", onBack = { route = "series" })
            "trakt" -> TraktScreen(app, settings, vm)
            "search" -> SearchScreen(app, catalog, onPlay = { playerItem = it }, onRoute = { route = it })
            "sources" -> SourcesView(app, vm, onRoute = { route = it }, onManage = { managerSourceId = it.id; route = "source-manager" })
            "source-manager" -> SourceManagerView(app, vm, initialSourceId = managerSourceId, onBack = { managerSourceId = null; route = "sources" }, onRoute = { next ->
                if (next == "epg-manage") {
                    epgManageReturnRoute = "source-manager"
                    route = next
                } else {
                    managerSourceId = null
                    route = next
                }
            })
            "downloads" -> DownloadsScreen(app)
            "settings" -> SettingsHub(app, settings, vm, onOpen = { next -> if (next == "epg-manage") epgManageReturnRoute = "settings"; route = next })
            "vpn" -> VpnScreen(app)
            "parental" -> ParentalScreen(vm, parental)
            "diagnostics" -> DiagnosticsScreen(app)
            "backup" -> BackupScreen(app)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AdaptiveShell(route: String, tv: Boolean, loading: Boolean, message: String?, onRetry: () -> Unit, onRoute: (String) -> Unit, title: String, content: @Composable () -> Unit) {
    val destinations = listOf("home" to "Home", "live" to "Live TV", "movies" to "Film", "series" to "Serie", "epg" to "Guida", "search" to "Cerca", "sources" to "Sorgenti", "settings" to "Impostazioni")
    val railScrollState = rememberScrollState()
    val railScope = rememberCoroutineScope()
    LiquidGlassBackdrop(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            LiquidGlassSurface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(if (tv) 142.dp else 104.dp),
                cornerRadius = 26.dp,
                contentPadding = 7.dp
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(railScrollState).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    destinations.forEach { (id, label) ->
                        val bringIntoView = remember(id) { BringIntoViewRequester() }
                        var focused by remember(id) { mutableStateOf(false) }
                        Surface(
                            onClick = { onRoute(id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(bringIntoView)
                                .onFocusChanged { state ->
                                    focused = state.isFocused
                                    if (state.isFocused) railScope.launch { runCatching { bringIntoView.bringIntoView() } }
                                },
                            shape = RoundedCornerShape(18.dp),
                            color = Color.Transparent
                        ) {
                            LiquidGlassSurface(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 18.dp,
                                contentPadding = if (tv) 13.dp else 9.dp,
                                highlighted = route == id || focused,
                                suppressTopHighlight = true
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(navIcon(id), null, tint = if (route == id) Color(0xFF9ABEFF) else glassForeground(.75f), modifier = Modifier.size(if (tv) 25.dp else 22.dp))
                                    Text(label, color = if (route == id) glassForeground() else glassForeground(.72f), fontSize = if (tv) 12.sp else 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = if (route == id) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 28.dp else 16.dp, vertical = 18.dp)) {
                Text(
                    title,
                    color = glassForeground(),
                    fontSize = if (tv) 32.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                if (loading) {
                    Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF8CB7FF), trackColor = Color.White.copy(.08f))
                    Text("Caricamento playlist…", color = glassForeground().copy(.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                } else if (!message.isNullOrBlank() && route != "sources" && route != "settings") {
                    LiquidGlassSurface(Modifier.fillMaxWidth().padding(top = 8.dp), cornerRadius = 15.dp, contentPadding = 10.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFFFFB340)); Spacer(Modifier.width(8.dp))
                            Text(message, color = Color(0xFFFFD08A), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            TextButton(onRetry) { Text("Riprova") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp)); Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

private fun navIcon(id: String) = when(id) { "home" -> Icons.Default.Home; "live" -> Icons.Default.LiveTv; "movies" -> Icons.Default.Movie; "series" -> Icons.Default.Tv; "epg" -> Icons.Default.CalendarMonth; "search" -> Icons.Default.Search; "sources" -> Icons.Default.SettingsInputAntenna; else -> Icons.Default.Settings }

private val homeSectionTitles = linkedMapOf(
    "heading" to "Intestazione",
    "search" to "Ricerca",
    "categories" to "Categorie",
    "continueWatching" to "Continua a guardare",
    "sourceCard" to "Sorgente",
    "sources" to "Sorgenti",
    "liveTV" to "Live TV",
    "guidaTV" to "Guida TV",
    "onDemand" to "On demand",
    "favoriteChannels" to "Canali preferiti",
    "favoriteSeries" to "Serie TV preferite",
    "favoriteMovies" to "Film preferiti",
    "trendingSeries" to "Serie di tendenza",
    "trendingMovies" to "Film di tendenza"
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeScreen(vm: MainViewModel, catalog: CatalogState?, fav: FavoriteState, watch: List<WatchEntry>, sources: List<MediaSourceConfig>, onRoute: (String) -> Unit, onPlay: (MediaItem) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val activeSource by vm.activeSource.collectAsStateWithLifecycle()
    val allSectionOrder = remember(settings.homeSectionOrder) { (settings.homeSectionOrder + defaultHomeSections).distinct() }
    val visibleOrder = allSectionOrder.filterNot { settings.hiddenHomeSections.contains(it) }
    var homeMode by remember { mutableStateOf("overview") }
    var homeMenuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var selectedDetail by remember { mutableStateOf<MediaItem?>(null) }
    var trendingSeries by remember(settings.tmdbApiKey) { mutableStateOf<List<MetadataResult>>(emptyList()) }
    var trendingMovies by remember(settings.tmdbApiKey) { mutableStateOf<List<MetadataResult>>(emptyList()) }
    var trendingUnavailable by remember { mutableStateOf<String?>(null) }
    var continueMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(settings.tmdbApiKey, visibleOrder.contains("trendingSeries"), visibleOrder.contains("trendingMovies")) {
        if (settings.tmdbApiKey.isBlank()) {
            trendingSeries = emptyList(); trendingMovies = emptyList()
        } else {
            if (visibleOrder.contains("trendingSeries")) trendingSeries = runCatching { vm.app.tmdb.trending("tv", settings.tmdbApiKey) }.getOrDefault(emptyList())
            if (visibleOrder.contains("trendingMovies")) trendingMovies = runCatching { vm.app.tmdb.trending("movie", settings.tmdbApiKey) }.getOrDefault(emptyList())
        }
    }

    if (selectedDetail != null) {
        val detail = selectedDetail!!
        when (detail.kind) {
            MediaKind.MOVIE -> MovieDetailScreen(vm.app, detail, catalog?.movies.orEmpty(), fav, onBack = { selectedDetail = null }, onPlay = { item, pos -> onPlayWithPosition(onPlay, item, pos) })
            MediaKind.SERIES -> SeriesDetailScreen(vm.app, detail, catalog?.episodes.orEmpty(), fav, onBack = { selectedDetail = null }, onPlay = { item, pos -> onPlayWithPosition(onPlay, item, pos) }, allSeries = catalog?.series.orEmpty(), onOpenAlternateSeries = { selectedDetail = it })
            else -> onPlay(detail)
        }
        return
    }

    fun openLibraryItem(item: MediaItem) {
        when (item.kind) {
            MediaKind.MOVIE, MediaKind.SERIES -> selectedDetail = item
            MediaKind.LIVE, MediaKind.EPISODE -> onPlay(item)
        }
    }

    LaunchedEffect(query) {
        kotlinx.coroutines.delay(250)
        debouncedQuery = query.trim()
    }
    val searching = query.trim().isNotEmpty()
    val renderedOrder = if (searching) visibleOrder.filter { it == "heading" || it == "search" } else visibleOrder
    val homeSearchResults = remember(debouncedQuery, catalog) { if (debouncedQuery.length < 2) emptyList() else catalog?.search(debouncedQuery, 100).orEmpty() }
    val menuItems = listOf("overview" to "Home", "live" to "Preferiti Live TV", "movies" to "Preferiti Film", "series" to "Preferiti Serie TV")
    val favoriteItems = remember(homeMode, catalog, fav) {
        when (homeMode) {
            "live" -> catalog?.byIds(MediaKind.LIVE, fav.live).orEmpty()
            "movies" -> catalog?.byIds(MediaKind.MOVIE, fav.movies).orEmpty()
            "series" -> catalog?.byIds(MediaKind.SERIES, fav.series).orEmpty()
            else -> emptyList()
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The iOS Home menu is always available, even if the user hides the heading section.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Surface(onClick = { homeMenuOpen = true }, shape = RoundedCornerShape(50), color = Color.Transparent) {
                    LiquidGlassPill(selected = true) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(when (homeMode) { "live" -> Icons.Default.LiveTv; "movies" -> Icons.Default.Movie; "series" -> Icons.Default.Tv; else -> Icons.Default.Home }, null, tint = Color(0xFF9ABEFF), modifier = Modifier.size(17.dp))
                            Text(menuItems.first { it.first == homeMode }.second, color = glassForeground(), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Icon(Icons.Default.ExpandMore, null, tint = glassForeground().copy(.75f), modifier = Modifier.size(17.dp))
                        }
                    }
                }
                DropdownMenu(homeMenuOpen, { homeMenuOpen = false }) {
                    menuItems.forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { homeMode = id; homeMenuOpen = false }, leadingIcon = { Icon(when (id) { "live" -> Icons.Default.LiveTv; "movies" -> Icons.Default.Movie; "series" -> Icons.Default.Tv; else -> Icons.Default.Home }, null) }, trailingIcon = { if (homeMode == id) Icon(Icons.Default.Check, null) })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(onClick = { onRoute("home-customize") }, color = Color.Transparent, shape = RoundedCornerShape(50)) {
                LiquidGlassPill {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Tune, null, tint = glassForeground().copy(.85f), modifier = Modifier.size(16.dp))
                        Text("Personalizza", color = glassForeground(), fontSize = 13.sp)
                    }
                }
            }
        }

        if (homeMode != "overview") {
            if (favoriteItems.isEmpty()) {
                LiquidGlassSurface(Modifier.fillMaxWidth(), contentPadding = 22.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.FavoriteBorder, null, tint = Color(0xFF9ABEFF), modifier = Modifier.size(30.dp))
                        Text("Nessun preferito", color = glassForeground(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Aggiungi contenuti ai preferiti per ritrovarli qui.", color = glassForeground().copy(.68f))
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = libraryGridMin(settings, homeMode == "live")),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    items(favoriteItems, key = { it.id }) { item ->
                        MediaCard(item, true, onClick = { if (homeMode == "live") onPlay(item) else openLibraryItem(item) }, onToggleFavorite = { vm.toggleFavorite(item) })
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 48.dp)
            ) {
                items(renderedOrder, key = { it }) { section ->
                    Box(Modifier.animateItem()) {
                    when (section) {
                        "heading" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("Tutto il tuo intrattenimento", color = glassForeground(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                Text(if (activeSource != null) "Scegli cosa guardare dalla sorgente attiva." else "Configura una sorgente dalle Impostazioni quando vuoi.", color = glassForeground().copy(.64f), fontSize = 14.sp)
                            }
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { HomeActionChip("Live TV", Icons.Default.LiveTv) { onRoute("live") } }
                                item { HomeActionChip("Film", Icons.Default.Movie) { onRoute("movies") } }
                                item { HomeActionChip("Serie", Icons.Default.Tv) { onRoute("series") } }
                                item { HomeActionChip("Guida TV", Icons.Default.CalendarMonth) { onRoute("epg") } }
                            }
                        }
                        "search" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            EpgStyleSearchField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "Cerca Live TV, film e serie…"
                            )
                            if (!searching) {
                                Text("Ricerca globale nella sorgente attiva", color = glassForeground().copy(.5f), fontSize = 12.sp)
                            } else if (query.trim().length < 2) {
                                Text("Digita almeno due caratteri", color = glassForeground().copy(.55f), fontSize = 12.sp)
                            } else if (debouncedQuery != query.trim()) {
                                Text("Ricerca in corso…", color = glassForeground().copy(.55f), fontSize = 12.sp)
                            } else if (activeSource == null) {
                                EmptyHint("Attiva una sorgente dalle Impostazioni per cercare nei suoi titoli.")
                            } else if (homeSearchResults.isEmpty()) {
                                EmptyHint("Nessun risultato per “${query.trim()}”")
                            } else {
                                Text("${homeSearchResults.size} risultati", color = glassForeground().copy(.62f), fontSize = 12.sp)
                                val grouped = listOf(
                                    MediaKind.LIVE to "Live TV",
                                    MediaKind.MOVIE to "Film",
                                    MediaKind.SERIES to "Serie TV",
                                    MediaKind.EPISODE to "Episodi"
                                )
                                grouped.forEach { (kind, label) ->
                                    val resultGroup = homeSearchResults.filter { it.kind == kind }
                                    if (resultGroup.isNotEmpty()) Section(label, null) {
                                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            items(resultGroup.take(16), key = { it.id }) { item -> MediaCard(item, vm.appFavorite(fav, item), onClick = { openLibraryItem(item) }, onToggleFavorite = { vm.toggleFavorite(item) }) }
                                        }
                                    }
                                }
                            }
                        }
                        "continueWatching" -> {
                            val entries = watch.filter { entry ->
                                when (settings.homeContinueKind) {
                                    "live" -> entry.kind == MediaKind.LIVE
                                    "movie" -> entry.kind == MediaKind.MOVIE
                                    "series" -> entry.kind == MediaKind.SERIES || entry.kind == MediaKind.EPISODE
                                    else -> true
                                }
                            }.take(settings.historyLimit.coerceIn(1, 100))
                            if (entries.isNotEmpty()) Section("Continua a guardare", null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Filtro", color = glassForeground().copy(.55f), fontSize = 12.sp)
                                    Box {
                                        TextButton(onClick = { continueMenuOpen = true }) { Text(when (settings.homeContinueKind) { "live" -> "Live TV"; "movie" -> "Film"; "series" -> "Serie TV"; else -> "Tutti" }); Icon(Icons.Default.ExpandMore, null) }
                                        DropdownMenu(continueMenuOpen, { continueMenuOpen = false }) {
                                            listOf(null to "Tutti", "live" to "Live TV", "movie" to "Film", "series" to "Serie TV").forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { vm.updateSettings(settings.copy(homeContinueKind = value)); continueMenuOpen = false }) }
                                        }
                                    }
                                }
                                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(entries, key = { it.contentId }) { entry ->
                                        val media = catalog?.findById(entry.contentId) ?: MediaItem(entry.contentId, "", entry.kind, entry.title, entry.url, metadataTag = "resume:${entry.positionMs}")
                                        MediaCard(media, vm.appFavorite(fav, media), onClick = { onPlayWithPosition(onPlay, media, entry.positionMs) }, onToggleFavorite = { vm.toggleFavorite(media) })
                                    }
                                }
                            }
                        }
                        "sourceCard" -> {
                            val activeName = sources.firstOrNull { it.id == activeSource }?.name
                            LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 18.dp) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background((if (activeName == null) Color(0xFF9ABEFF) else Color(0xFF54D6A0)).copy(.16f)), contentAlignment = Alignment.Center) {
                                            Icon(if (activeName == null) Icons.Default.AutoAwesome else Icons.Default.CheckCircle, null, tint = if (activeName == null) Color(0xFF9ABEFF) else Color(0xFF54D6A0), modifier = Modifier.size(25.dp))
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(if (activeName == null) "Inizia quando vuoi" else "Sorgente pronta", color = glassForeground(), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                            Text(activeName ?: "Nessuna sorgente configurata", color = glassForeground().copy(.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${sources.size} sorgenti · ${catalog?.allItems?.size ?: 0} elementi", color = glassForeground().copy(.48f), fontSize = 12.sp)
                                        }
                                        if (sources.size > 1 && activeSource != null) {
                                            Box {
                                                Surface(onClick = { sourceMenuOpen = true }, color = Color.Transparent, shape = RoundedCornerShape(50)) {
                                                    LiquidGlassPill {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            Text("Cambia", color = glassForeground(), fontSize = 12.sp)
                                                            Icon(Icons.Default.ExpandMore, null, tint = glassForeground().copy(.7f), modifier = Modifier.size(16.dp))
                                                        }
                                                    }
                                                }
                                                DropdownMenu(sourceMenuOpen, { sourceMenuOpen = false }) {
                                                    sources.forEach { source ->
                                                        DropdownMenuItem(
                                                            text = { Text(source.name) },
                                                            leadingIcon = { Icon(Icons.Default.Storage, null) },
                                                            trailingIcon = { if (source.id == activeSource) Icon(Icons.Default.Check, null) },
                                                            onClick = { vm.setActive(source.id); sourceMenuOpen = false }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        OutlinedButton(onClick = { onRoute("sources") }) { Text(if (activeName == null) "Aggiungi" else "Gestisci") }
                                    }
                                    if (activeName == null) Text("Aggiungi una playlist M3U o un account supportato dalle Impostazioni. Live TV, film e serie appariranno qui dopo il caricamento.", color = glassForeground().copy(.62f), fontSize = 13.sp)
                                }
                            }
                        }
                        "sources" -> Section("Sorgenti", null) {
                            Surface(onClick = { onRoute("sources") }, color = Color.Transparent, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, contentPadding = 16.dp) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF3478F6).copy(.15f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Layers, null, tint = Color(0xFF9ABEFF), modifier = Modifier.size(23.dp)) }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text("Sorgenti", color = glassForeground(), fontWeight = FontWeight.SemiBold)
                                            Text(if (sources.isEmpty()) "Nessuna sorgente configurata" else "${sources.size} sorgenti configurate", color = glassForeground().copy(.58f), fontSize = 12.sp)
                                        }
                                        Icon(Icons.Default.ChevronRight, null, tint = glassForeground().copy(.42f))
                                    }
                                }
                            }
                        }
                        "liveTV" -> HomeDestinationCard("Live TV", if (activeSource == null) "Disponibile con una sorgente" else "${catalog?.live?.size ?: 0} canali disponibili", if (activeSource == null) "I canali appariranno qui" else "Canali in diretta dalla sorgente attiva", Icons.Default.LiveTv, Color(0xFFFF6961), "Apri Live TV") { onRoute("live") }
                        "guidaTV" -> HomeDestinationCard("Guida TV", "EPG e promemoria locali", "Consulta la programmazione e vai al canale che vuoi guardare.", Icons.Default.CalendarMonth, Color(0xFF9ABEFF), "Apri guida") { onRoute("epg") }
                        "favoriteChannels" -> {
                            val list = catalog?.byIds(MediaKind.LIVE, fav.live).orEmpty()
                            if (list.isNotEmpty()) Section("Canali preferiti", { homeMode = "live" }) {
                                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(list.take(20), key = { it.id }) { item -> MediaCard(item, true, onClick = { onPlay(item) }, onToggleFavorite = { vm.toggleFavorite(item) }) } }
                            }
                        }
                        "favoriteSeries" -> {
                            val list = catalog?.byIds(MediaKind.SERIES, fav.series).orEmpty()
                            if (list.isNotEmpty()) Section("Serie TV preferite", { homeMode = "series" }) {
                                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(list.take(20), key = { it.id }) { item -> MediaCard(item, true, onClick = { openLibraryItem(item) }, onToggleFavorite = { vm.toggleFavorite(item) }) } }
                            }
                        }
                        "favoriteMovies" -> {
                            val list = catalog?.byIds(MediaKind.MOVIE, fav.movies).orEmpty()
                            if (list.isNotEmpty()) Section("Film preferiti", { homeMode = "movies" }) {
                                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(list.take(20), key = { it.id }) { item -> MediaCard(item, true, onClick = { openLibraryItem(item) }, onToggleFavorite = { vm.toggleFavorite(item) }) } }
                            }
                        }
                        "onDemand" -> Section("On demand", null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                HomeCompactDestinationCard("VOD", if (activeSource == null) "Disponibile con una sorgente" else "Film e contenuti on demand", Icons.Default.Movie, Color(0xFFB19CFF), Modifier.weight(1f)) { onRoute("movies") }
                                HomeCompactDestinationCard("Serie TV", if (activeSource == null) "Disponibile con una sorgente" else "Scopri le tue serie", Icons.Default.Tv, Color(0xFF8CB7FF), Modifier.weight(1f)) { onRoute("series") }
                            }
                        }
                        "trendingSeries" -> Section("Serie di tendenza", { onRoute("series") }) {
                            if (settings.tmdbApiKey.isBlank()) EmptyHint("Configura la chiave API TMDB in Impostazioni per caricare le tendenze.")
                            else if (trendingSeries.isEmpty()) EmptyHint("Tendenze serie non disponibili: controlla la chiave TMDB e la rete.")
                            else androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(trendingSeries.take(16), key = { it.id }) { trend -> TrendingCard(trend, true) {
                                    val match = catalog?.series.orEmpty().firstOrNull { item -> item.title.matchesTrendTitle(trend.title) || (trend.originalTitle?.let { item.title.matchesTrendTitle(it) } == true) }
                                    if (match != null) openLibraryItem(match) else trendingUnavailable = trend.title
                                } }
                            }
                        }
                        "trendingMovies" -> Section("Film di tendenza", { onRoute("movies") }) {
                            if (settings.tmdbApiKey.isBlank()) EmptyHint("Configura la chiave API TMDB in Impostazioni per caricare le tendenze.")
                            else if (trendingMovies.isEmpty()) EmptyHint("Tendenze film non disponibili: controlla la chiave TMDB e la rete.")
                            else androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(trendingMovies.take(16), key = { it.id }) { trend -> TrendingCard(trend, false) {
                                    val match = catalog?.movies.orEmpty().firstOrNull { item -> item.title.matchesTrendTitle(trend.title) || (trend.originalTitle?.let { item.title.matchesTrendTitle(it) } == true) }
                                    if (match != null) openLibraryItem(match) else trendingUnavailable = trend.title
                                } }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    trendingUnavailable?.let { title ->
        AlertDialog(
            onDismissRequest = { trendingUnavailable = null },
            title = { Text("Titolo non presente nella playlist") },
            text = { Text("“$title” è tra le tendenze, ma non è stato trovato nella sorgente attiva.") },
            confirmButton = { TextButton(onClick = { trendingUnavailable = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { trendingUnavailable = null; onRoute("sources") }) { Text("Gestisci sorgenti") } }
        )
    }
}

private fun String.normalizedTitle(): String {
    val folded = java.text.Normalizer.normalize(lowercase(), java.text.Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .filter { it.isLetterOrDigit() || it.isWhitespace() }
        .trim()
    val noYear = folded.replace("\\s+(?:19|20)\\d{2}$".toRegex(), "").trim()
    return (if (noYear.length >= 2) noYear else folded).filter { it.isLetterOrDigit() }
}

/** Exact normalized matches first, then conservative partial matches for TMDB/playlist naming variants. */
private fun String.matchesTrendTitle(other: String): Boolean {
    val left = normalizedTitle()
    val right = other.normalizedTitle()
    if (left.isBlank() || right.isBlank()) return false
    if (left == right) return true
    if (minOf(left.length, right.length) < 5) return false
    return left.contains(right) || right.contains(left)
}

@Composable
private fun HomeActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color.Transparent) {
        LiquidGlassPill {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(icon, null, tint = Color(0xFF9ABEFF), modifier = Modifier.size(16.dp))
                Text(label, color = glassForeground(), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HomeCompactDestinationCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 150.dp), color = Color.Transparent, shape = RoundedCornerShape(22.dp)) {
        LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 16.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(.16f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(title, color = glassForeground(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(subtitle, color = glassForeground().copy(.58f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeDestinationCard(title: String, metric: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, buttonLabel: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(buttonLabel) }
        }
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color.Transparent) {
            LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 18.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(.16f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(27.dp)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, color = glassForeground(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(metric, color = glassForeground().copy(.76f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(subtitle, color = glassForeground().copy(.56f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = glassForeground().copy(.42f))
                }
            }
        }
    }
}

@Composable
private fun TrendingCard(item: MetadataResult, isSeries: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.width(156.dp).height(270.dp), color = Color.Transparent, shape = RoundedCornerShape(18.dp)) {
        LiquidGlassSurface(modifier = Modifier.fillMaxSize(), cornerRadius = 18.dp, contentPadding = 0.dp) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(205.dp).background(Color(0xFF101010))) {
                    val artwork = item.posterUrl ?: item.backdropUrl
                    if (artwork != null) AsyncImage(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.30f)))))
                    item.rating?.let { rating ->
                        LiquidGlassPill(Modifier.align(Alignment.TopEnd).padding(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) { Icon(Icons.Default.Star, null, tint = Color(0xFFFFD166), modifier = Modifier.size(12.dp)); Text(String.format(java.util.Locale.ROOT, "%.1f", rating), color = glassForeground(), fontSize = 11.sp) }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, color = glassForeground(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(if (isSeries) "Serie TV · Tendenza" else "Film · Tendenza", color = glassForeground().copy(.52f), fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable private fun Section(title: String, onSeeAll: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (onSeeAll != null) TextButton(onSeeAll) { Text("Vedi tutto", color = Color(0xFF9ABEFF)) }
        }
        content()
    }
}

@Composable private fun EmptyHint(text: String) { Text(text, color = glassForeground().copy(.52f), modifier = Modifier.padding(vertical = 12.dp), fontSize = 13.sp) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(item: MediaItem, favorite: Boolean, onClick: () -> Unit, onToggleFavorite: (() -> Unit)? = null) {
    val w = if (item.kind == MediaKind.LIVE) 190.dp else 160.dp
    val h = if (item.kind == MediaKind.LIVE) 108.dp else 220.dp
    var focused by remember(item.id) { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.025f else 1f, label = "media-card-focus")
    Surface(
        modifier = Modifier.width(w)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = null,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
                onLongClick = { onToggleFavorite?.invoke() }
            ),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(if (focused) 1.25.dp else .55.dp, glassForeground(if (focused) .42f else .15f))
    ) {
        LiquidGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 0.dp, highlighted = focused) {
            Column {
                Box(Modifier.fillMaxWidth().height(h).background(Color(0xFF0C0C0C))) {
                    item.backdropUrl?.takeIf { item.kind != MediaKind.LIVE }?.let { AsyncImage(it, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (item.posterUrl != null && item.kind != MediaKind.LIVE) AsyncImage(item.posterUrl, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.30f)))))
                    if (item.kind == MediaKind.LIVE && item.logoUrl != null) AsyncImage(item.logoUrl, null, modifier = Modifier.fillMaxSize().padding(18.dp), contentScale = ContentScale.Fit)
                    if (onToggleFavorite != null) {
                        Surface(
                            onClick = onToggleFavorite,
                            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(34.dp),
                            shape = RoundedCornerShape(50),
                            color = Color.Black.copy(.35f),
                            border = BorderStroke(.7.dp, Color.White.copy(.28f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (favorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
                                    tint = if (favorite) Color(0xFFFF91AD) else Color.White.copy(.92f),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, color = glassForeground(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    item.group?.takeIf { it.isNotBlank() }?.let { Text(it, color = glassForeground().copy(.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun CatalogScreen(
    title: String,
    items: List<MediaItem>,
    categories: List<Category>,
    favorite: FavoriteState,
    parental: ParentalState,
    vm: MainViewModel,
    onPlay: (MediaItem) -> Unit,
    showNumbers: Boolean = false,
    detail: Boolean = false,
    scopeKey: String = if (detail) "movies" else "live",
    onCustomize: () -> Unit = {}
) {
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var filter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember(scopeKey) { mutableStateOf("") }
    var trending by remember { mutableStateOf<List<MetadataResult>>(emptyList()) }
    var showTrendUnavailable by remember { mutableStateOf(false) }
    if (detail && selected != null) {
        MovieDetailScreen(vm.app, selected!!, vm.catalog.value?.movies.orEmpty(), favorite, onBack = { selected = null }, onPlay = { i, pos -> onPlayWithPosition(onPlay, i, pos) })
        return
    }
    val libSettings by vm.settings.collectAsStateWithLifecycle()
    val watch by vm.watch.collectAsStateWithLifecycle()
    val defaults = if (scopeKey == "live") defaultLiveSections else defaultMovieSections
    val rawOrder = if (scopeKey == "live") libSettings.liveSectionOrder else libSettings.movieSectionOrder
    val hiddenSections = if (scopeKey == "live") libSettings.hiddenLiveSections else libSettings.hiddenMovieSections
    val sectionOrder = remember(rawOrder, defaults) { (rawOrder + defaults).distinct() }
    val visibleSections = sectionOrder.filterNot { it in hiddenSections }
    LaunchedEffect(visibleSections.contains("categories")) { if (!visibleSections.contains("categories")) filter = null }
    LaunchedEffect(visibleSections.contains("search")) { if (!visibleSections.contains("search")) searchQuery = "" }
    val trendSection = "trendingMovies"

    LaunchedEffect(libSettings.tmdbApiKey, visibleSections.contains(trendSection), scopeKey) {
        trending = if (detail && visibleSections.contains(trendSection) && libSettings.tmdbApiKey.isNotBlank()) {
            runCatching { vm.app.tmdb.trending("movie", libSettings.tmdbApiKey) }.getOrDefault(emptyList())
        } else emptyList()
    }

    // Keep disk-backed catalogs lazy: applying Kotlin .filter to DbList here used to
    // decode/scan every item on the main thread whenever Live/Film changed route.
    val baseFiltered = remember(items, filter, parental.lockedIds) {
        items.inCategory(filter).withoutIds(parental.lockedIds)
    }
    val filtered = remember(baseFiltered, searchQuery) {
        baseFiltered.matchingText(searchQuery)
    }
    val watchedIds = remember(watch) { watch.filter { it.kind == MediaKind.MOVIE }.associateBy { it.contentId } }
    val continueItems = remember(items, watch, detail) {
        if (!detail) emptyList() else items.onlyIds(watchedIds.keys)
            .sortedByDescending { watchedIds[it.id]?.lastWatchedMs ?: 0L }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = glassForeground(), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onCustomize) {
                Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Modifica")
            }
        }

        var categoryHeaderShown = false
        visibleSections.forEach { section ->
            key(section) {
            when (section) {
                "search" -> EpgStyleSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = if (scopeKey == "live") "Cerca per nome del canale" else "Cerca per nome del film"
                )
                "categories" -> {
                    LibraryHeader(title, categories, items, filter, { filter = it }, libSettings, vm, allowPoster = detail, showGroups = true)
                    categoryHeaderShown = true
                }
                "continueWatching" -> if (detail && searchQuery.isBlank()) {
                    if (continueItems.isNotEmpty()) Section("Continua a guardare", null) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(continueItems.take(24), key = { it.id }) { item ->
                                MediaCard(item, vm.appFavorite(favorite, item), onClick = { selected = item }, onToggleFavorite = { vm.toggleFavorite(item) })
                            }
                        }
                    }
                }
                "trendingMovies" -> if (detail && searchQuery.isBlank()) {
                    Section("Film di tendenza", null) {
                        if (libSettings.tmdbApiKey.isBlank()) Text("Configura la chiave TMDB nelle impostazioni per vedere le tendenze.", color = glassForeground(.6f), fontSize = 12.sp)
                        else if (trending.isEmpty()) Text("Tendenze non disponibili: controlla la rete e la chiave TMDB.", color = glassForeground(.6f), fontSize = 12.sp)
                        else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(trending.take(16), key = { it.id }) { trend ->
                                TrendingCard(trend, false) {
                                    val match = items.firstOrNull { it.title.equals(trend.title, ignoreCase = true) }
                                        ?: items.firstOrNull { it.title.contains(trend.title, ignoreCase = true) }
                                    if (match != null) selected = match else showTrendUnavailable = true
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        if (!categoryHeaderShown) {
            LibraryHeader(title, categories, items, filter, { filter = it }, libSettings, vm, allowPoster = detail, showGroups = false)
        }
        if (filtered.isEmpty()) EmptyHint(when {
            items.isEmpty() -> "Nessun contenuto: controlla la sorgente in Sorgenti e riprova a ricaricare."
            searchQuery.isNotBlank() -> "Nessun risultato per ‘${searchQuery.trim()}’."
            else -> "Nessun risultato per questo gruppo."
        }) else LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = libraryGridMin(libSettings, !detail)),
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            items(filtered, key = { it.id }) { item ->
                MediaCard(item, vm.appFavorite(favorite, item), onClick = { if (detail) selected = item else onPlay(item) }, onToggleFavorite = { vm.toggleFavorite(item) })
            }
        }
    }

    if (showTrendUnavailable) AlertDialog(
        onDismissRequest = { showTrendUnavailable = false },
        confirmButton = { TextButton(onClick = { showTrendUnavailable = false }) { Text("OK") } },
        title = { Text("Non disponibile nella sorgente") },
        text = { Text("Questo titolo di tendenza non è presente nella playlist attiva.") }
    )
}

private fun onPlayWithPosition(onPlay: (MediaItem) -> Unit, item: MediaItem, position: Long) { onPlay(item.copy(metadataTag = "resume:$position")) }

@Composable
private fun SeriesScreen(
    series: List<MediaItem>,
    episodes: List<MediaItem>,
    categories: List<Category>,
    favorite: FavoriteState,
    parental: ParentalState,
    vm: MainViewModel,
    onPlay: (MediaItem) -> Unit,
    onCustomize: () -> Unit = {}
) {
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var filter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var trending by remember { mutableStateOf<List<MetadataResult>>(emptyList()) }
    var showTrendUnavailable by remember { mutableStateOf(false) }
    if (selected != null) {
        SeriesDetailScreen(vm.app, selected!!, episodes, favorite, onBack = { selected = null }, onPlay = { i, pos -> onPlayWithPosition(onPlay, i, pos) }, allSeries = series, onOpenAlternateSeries = { selected = it })
        return
    }
    val libSettings by vm.settings.collectAsStateWithLifecycle()
    val watch by vm.watch.collectAsStateWithLifecycle()
    val defaults = defaultSeriesSections
    val sectionOrder = remember(libSettings.seriesSectionOrder) { (libSettings.seriesSectionOrder + defaults).distinct() }
    val visibleSections = sectionOrder.filterNot { it in libSettings.hiddenSeriesSections }
    LaunchedEffect(visibleSections.contains("categories")) { if (!visibleSections.contains("categories")) filter = null }
    LaunchedEffect(visibleSections.contains("search")) { if (!visibleSections.contains("search")) searchQuery = "" }

    LaunchedEffect(libSettings.tmdbApiKey, visibleSections.contains("trendingSeries")) {
        trending = if (visibleSections.contains("trendingSeries") && libSettings.tmdbApiKey.isNotBlank()) {
            runCatching { vm.app.tmdb.trending("tv", libSettings.tmdbApiKey) }.getOrDefault(emptyList())
        } else emptyList()
    }

    // Apply category, parental and text predicates in SQLite for large playlist catalogs.
    val baseFiltered = remember(series, filter, parental.lockedIds) {
        series.inCategory(filter).withoutIds(parental.lockedIds)
    }
    val filtered = remember(baseFiltered, searchQuery) {
        baseFiltered.matchingText(searchQuery)
    }
    val watchedById = remember(watch) { watch.filter { it.kind == MediaKind.EPISODE || it.kind == MediaKind.SERIES }.associateBy { it.contentId } }
    val continueSectionVisible = "continueWatching" in visibleSections
    val watchedSeriesIds = remember(watch, episodes, continueSectionVisible) {
        if (!continueSectionVisible) emptySet()
        else episodes.seriesItemIdsForEpisodeIds(watch.filter { it.kind == MediaKind.EPISODE }.mapTo(HashSet()) { it.contentId })
    }
    val continueItems = remember(series, watchedById, watchedSeriesIds, continueSectionVisible) {
        if (!continueSectionVisible) emptyList()
        else series.onlyIds(watchedById.keys + watchedSeriesIds)
            .sortedByDescending { watchedById[it.id]?.lastWatchedMs ?: 0L }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Serie", color = glassForeground(), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onCustomize) { Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Modifica") }
        }
        var categoryHeaderShown = false
        visibleSections.forEach { section ->
            key(section) {
            when (section) {
                "search" -> EpgStyleSearchField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), placeholder = "Cerca per nome della serie")
                "categories" -> {
                    LibraryHeader("Serie", categories, series, filter, { filter = it }, libSettings, vm, allowPoster = true, showGroups = true)
                    categoryHeaderShown = true
                }
                "continueWatching" -> if (searchQuery.isBlank() && continueItems.isNotEmpty()) Section("Continua a guardare", null) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continueItems.take(24), key = { it.id }) { item -> MediaCard(item, item.id in favorite.series, onClick = { selected = item }, onToggleFavorite = { vm.toggleFavorite(item) }) }
                    }
                }
                "trendingSeries" -> if (searchQuery.isBlank()) Section("Serie di tendenza", null) {
                    if (libSettings.tmdbApiKey.isBlank()) Text("Configura la chiave TMDB nelle impostazioni per vedere le tendenze.", color = glassForeground(.6f), fontSize = 12.sp)
                    else if (trending.isEmpty()) Text("Tendenze non disponibili: controlla la rete e la chiave TMDB.", color = glassForeground(.6f), fontSize = 12.sp)
                    else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(trending.take(16), key = { it.id }) { trend ->
                            TrendingCard(trend, true) {
                                val match = series.firstOrNull { it.title.equals(trend.title, ignoreCase = true) }
                                    ?: series.firstOrNull { it.title.contains(trend.title, ignoreCase = true) }
                                if (match != null) selected = match else showTrendUnavailable = true
                            }
                        }
                    }
                }
            }
            }
        }
        if (!categoryHeaderShown) LibraryHeader("Serie", categories, series, filter, { filter = it }, libSettings, vm, allowPoster = true, showGroups = false)
        if (filtered.isEmpty()) EmptyHint(when {
            series.isEmpty() -> "Nessuna serie: controlla la sorgente e ricarica."
            searchQuery.isNotBlank() -> "Nessun risultato per ‘${searchQuery.trim()}’."
            else -> "Nessun risultato per questo gruppo."
        }) else LazyVerticalGrid(
            GridCells.Adaptive(libraryGridMin(libSettings, false)),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(filtered, key = { it.id }) { item -> MediaCard(item, item.id in favorite.series, onClick = { selected = item }, onToggleFavorite = { vm.toggleFavorite(item) }) }
        }
    }
    if (showTrendUnavailable) AlertDialog(onDismissRequest = { showTrendUnavailable = false }, confirmButton = { TextButton(onClick = { showTrendUnavailable = false }) { Text("OK") } }, title = { Text("Non disponibile nella sorgente") }, text = { Text("Questa serie di tendenza non è presente nella playlist attiva.") })
}

@Composable
private fun SearchScreen(app: GassPlayerApplication, catalog: CatalogState?, onPlay: (MediaItem) -> Unit, onRoute: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val history by app.search.flow.collectAsStateWithLifecycle(SearchHistory())
    val scope = rememberCoroutineScope()
    val results = remember(submitted, catalog) { if (submitted.isBlank()) emptyList() else catalog?.search(submitted, 100).orEmpty() }
    Column {
        EpgStyleSearchField(
            value = q,
            onValueChange = { q = it; submitted = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Cerca live, film e serie",
            onSubmit = { submitted = q; scope.launch { app.search.add(q) } }
        )
        Spacer(Modifier.height(10.dp))
        if (submitted.isBlank() && history.terms.isNotEmpty()) {
            Text("Ricerche recenti", color = glassForeground().copy(.6f))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(history.terms) { AssistChip({ q = it; submitted = it }, label = { Text(it) }) } }
            Spacer(Modifier.height(12.dp))
        }
        LazyVerticalGrid(GridCells.Adaptive(180.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(results) { MediaCard(it, false, onClick = { scope.launch { app.search.add(submitted) }; onPlay(it) }, onToggleFavorite = { scope.launch { app.favorites.toggle(it) } }) }
        }
    }
}

@Composable
private fun ExternalEpgManageScreen(app: GassPlayerApplication, onBack: () -> Unit) {
    val sources by app.externalEpg.flow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.focusable()) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(4.dp)); Text("Indietro") }
            Text("Fonti XMLTV esterne", color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nome fonte") })
        OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("URL XMLTV") })
        Button({ scope.launch { if (app.externalEpg.add(name, url)) { name = ""; url = "" } } }) { Text("Aggiungi fonte EPG") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.id }) { source ->
                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 0.dp) { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(source.name, color = glassForeground(), fontWeight = FontWeight.Bold); Text(source.urlString, color = glassForeground().copy(.6f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Switch(source.isEnabled, { enabled -> scope.launch { app.externalEpg.toggle(source.id, enabled) } })
                    IconButton({ scope.launch { app.externalEpg.remove(source.id) } }) { Icon(Icons.Default.Delete, null) }
                } }
            }
        }
    }
}

@Composable private fun LegacySourcesScreen(app: GassPlayerApplication, vm: MainViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ showAdd = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Aggiungi sorgente") }
            OutlinedButton({ scope.launch { app.prefs.saveSources(sources.sortedWith(compareBy<MediaSourceConfig> { if (it.isPinned) 0 else 1 }.thenBy { it.sortOrder })) }; vm.refresh() }) { Text("Ordina") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sources, key = { it.id }) { s ->
                var renameOpen by remember(s.id) { mutableStateOf(false) }
                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 0.dp) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if(s.isPinned) "★ ${s.name}" else s.name, color = glassForeground(), fontWeight = FontWeight.Bold)
                            Text("${s.type} • ${s.host}", color = glassForeground().copy(.6f))
                            Text(if (s.lastVerificationSucceeded) "Verificata • ${s.lastKnownChannelCount}" else "Non verificata", color = if (s.lastVerificationSucceeded) Color(0xFF6EDC82) else Color(0xFFFFB55E))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = s.isEnabled, onCheckedChange = { value -> scope.launch { app.sources.toggle(s.id, value); vm.refresh(true) } })
                            IconButton({ vm.setActive(s.id) }) { Icon(if (vm.activeSource.value == s.id) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null) }
                            IconButton({ renameOpen = true }) { Icon(Icons.Default.Edit, null) }
                            IconButton({ scope.launch { app.sources.pin(s.id, !s.isPinned); vm.refresh(false) } }) { Icon(Icons.Default.PushPin, null) }
                            IconButton({ scope.launch { app.sources.duplicate(s.id); vm.refresh(true) } }) { Icon(Icons.Default.ContentCopy, null) }
                            IconButton({ scope.launch { app.sources.verify(s); vm.refresh(false) } }) { Icon(Icons.Default.Verified, null) }
                            IconButton({ vm.deleteSource(s.id) }) { Icon(Icons.Default.Delete, null) }
                        }
                    }
                }
                if (renameOpen) RenameSourceDialog(s.name, onDismiss = { renameOpen = false }) { name -> scope.launch { app.sources.rename(s.id, name); vm.refresh(false); renameOpen = false } }
            }
        }
    }
    if (showAdd) AddSourceDialog(app, vm, { showAdd = false })
}

@Composable
private fun SourceDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        LiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 620.dp)
                .heightIn(max = 760.dp),
            cornerRadius = 28.dp,
            contentPadding = 22.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, darkSurface = false) {
                        Icon(Icons.Default.Close, "Chiudi")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun RenameSourceDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    SourceDialogFrame("Rinomina sorgente", onDismiss) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Nome") }
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerGlassButton(
                onClick = onDismiss,
                darkSurface = false
            ) { Text("Annulla") }
            Spacer(Modifier.width(10.dp))
            PlayerGlassButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name) },
                darkSurface = false
            ) { Text("Salva") }
        }
    }
}

@Composable
fun AddSourceDialog(app: GassPlayerApplication, vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = if (type == SourceType.XTREAM) {
        host.isNotBlank() && ((user.isNotBlank() && pass.isNotBlank()) || XtreamRepository.credentialsFromUrl(host) != null)
    } else {
        url.isNotBlank()
    }

    SourceDialogFrame("Nuova sorgente", { if (!busy) onDismiss() }) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Nome") }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SourceType.entries.take(2).forEach { sourceType ->
                FilterChip(
                    selected = sourceType == type,
                    onClick = { if (!busy) type = sourceType },
                    label = { Text(sourceType.name) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (type == SourceType.XTREAM) {
            OutlinedTextField(host, { host = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Host Xtream") })
            OutlinedTextField(user, { user = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username") })
            OutlinedTextField(pass, { pass = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") })
        } else {
            OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("URL M3U/M3U8") })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerGlassButton(
                enabled = !busy,
                onClick = onDismiss,
                darkSurface = false
            ) { Text("Annulla") }
            Spacer(Modifier.width(10.dp))
            PlayerGlassButton(
                enabled = valid && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        val pasted = if (type == SourceType.XTREAM) XtreamRepository.credentialsFromUrl(host) else null
                        val actualHost = if (type == SourceType.XTREAM) {
                            runCatching { XtreamRepository.serverBase(host) }.getOrElse {
                                error = it.message
                                busy = false
                                return@launch
                            }
                        } else url.trim()
                        user = user.ifBlank { pasted?.first.orEmpty() }
                        pass = pass.ifBlank { pasted?.second.orEmpty() }
                        val candidate = MediaSourceConfig(
                            id = UUID.randomUUID().toString(),
                            name = name.trim().ifBlank { "Sorgente" },
                            type = type,
                            host = actualHost,
                            username = user.trim().takeIf { it.isNotBlank() },
                            password = pass.takeIf { it.isNotBlank() },
                            playlistUrl = url.trim().takeIf { type != SourceType.XTREAM }
                        )
                        val result = runCatching {
                            when (type) {
                                SourceType.XTREAM -> app.xtream.probe(candidate)
                                SourceType.M3U8 -> {
                                    val playlistUrl = candidate.playlistUrl ?: candidate.host
                                    val sourceHeaders = NetworkApi.extractInlineHeaders(playlistUrl)
                                    // Streamed validation: counts entries without keeping the playlist in memory.
                                    val count = app.network.readTextStream(
                                        playlistUrl,
                                        mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, */*")
                                    ) { reader, finalUrl ->
                                        var n = 0
                                        M3UParser.parse(candidate.id, reader, NetworkApi.stripInlineHeaders(finalUrl), defaultHeaders = sourceHeaders, keep = { n++; false })
                                        n
                                    }
                                    count.takeIf { it > 0 }
                                        ?: error("Playlist M3U/M3U8 vuota o non riconosciuta")
                                }
                                else -> Unit
                            }
                        }
                        if (result.isSuccess) {
                            vm.addSource(candidate)
                            onDismiss()
                        } else {
                            error = result.exceptionOrNull()?.message ?: "Verifica sorgente fallita"
                        }
                        busy = false
                    }
                },
                darkSurface = false
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "Verifica…" else "Verifica e salva")
            }
        }
    }
}

@Composable private fun DownloadsScreen(app: GassPlayerApplication) {
    val infos by androidx.work.WorkManager.getInstance(app).getWorkInfosByTagFlow("gass_download").collectAsStateWithLifecycle(emptyList())
    Column {
        Text("Download in background", color = glassForeground())
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(infos){info->
                val p = info.progress.getInt("progress", if(info.state==androidx.work.WorkInfo.State.SUCCEEDED) 100 else 0)
                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 14.dp){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(info.outputData.getString("file") ?: info.id.toString(),color = glassForeground());LinearProgressIndicator(progress={p/100f},Modifier.fillMaxWidth(), color = Color(0xFF8CB7FF), trackColor = Color.White.copy(.08f));Text(info.state.name,color = glassForeground().copy(.6f))}}
            }
        }
    }
}

@Composable internal fun SettingInt(label:String, value:Int, range:IntRange, step:Int=1, onChange:(Int)->Unit){ Column(Modifier.fillMaxWidth()){ Text("$label: $value",color = glassForeground()); Slider(value=value.toFloat(),onValueChange={onChange(it.toInt())},valueRange=range.first.toFloat()..range.last.toFloat(),steps=((range.last-range.first)/step-1).coerceAtLeast(0)) } }
@Composable private fun SettingToggle(label:String, value:Boolean, onChange:(Boolean)->Unit){ Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){ Text(label,color = glassForeground()); Switch(value,onChange) } }
@Composable private fun SettingRow(label:String, value:String, choices:List<String>, onChange:(String)->Unit){ var expanded by remember{mutableStateOf(false)}; Box{ OutlinedButton({expanded=true},Modifier.fillMaxWidth()){ Text("$label: $value") }; DropdownMenu(expanded,{expanded=false}){choices.forEach{DropdownMenuItem({Text(it)},onClick={onChange(it);expanded=false})}} } }

@Composable private fun ParentalScreen(vm:MainViewModel,state:ParentalState){ var pin by remember{mutableStateOf("")}; var newPin by remember{mutableStateOf("")}; var msg by remember{mutableStateOf<String?>(null)}; val scope=rememberCoroutineScope(); Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("PIN locale con SHA-256 e blocco per contenuto/categoria",color = glassForeground().copy(.7f));OutlinedTextField(newPin,{newPin=it},label={Text("Nuovo PIN")});Button({scope.launch{vm.app.parental.setPin(newPin);msg="PIN impostato"}}){Text("Imposta PIN")};OutlinedTextField(pin,{pin=it},label={Text("Verifica PIN")});Button({scope.launch{msg=if(vm.app.parental.verify(pin))"PIN corretto" else "PIN non valido"}}){Text("Verifica")};msg?.let{Text(it,color = glassForeground())} }
}

@Composable private fun VpnScreen(app:GassPlayerApplication){
    val cfg by app.prefs.vpnFlow.collectAsStateWithLifecycle(VPNConfig())
    var server by remember(cfg){mutableStateOf(cfg.serverEndpoint)}; var user by remember(cfg){mutableStateOf(cfg.username)}; var pass by remember(cfg){mutableStateOf(cfg.password)}; var state by remember{mutableStateOf("Disconnessa")}; var discovery by remember{mutableStateOf<VPNConfig?>(null)}; val scope=rememberCoroutineScope()
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){ result -> if(result.resultCode==Activity.RESULT_OK) runCatching{app.vpn.startIkev2();state="Connessa"} }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("IKEv2 usa lo stack VPN nativo Android; WireGuard usa il tunnel ufficiale del progetto WireGuard.",color = glassForeground().copy(.7f))}
        item{OutlinedTextField(server,{server=it},Modifier.fillMaxWidth(),label={Text("Endpoint")})}
        item{OutlinedTextField(user,{user=it},Modifier.fillMaxWidth(),label={Text("Username")})}
        item{OutlinedTextField(pass,{pass=it},Modifier.fillMaxWidth(),label={Text("Password")})}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button({scope.launch{val n=cfg.copy(protocol="ikev2",serverEndpoint=server,username=user,password=pass); app.prefs.saveVpn(n); val intent=runCatching{app.vpn.provisionIkev2(n)}.getOrNull(); if(intent!=null) launcher.launch(intent) else runCatching{app.vpn.startIkev2();state="Connessa"}}}){Text("Configura IKEv2")};
            OutlinedButton({app.vpn.stopIkev2();state="Disconnessa"}){Text("Stop")}
        }}
        item{Button({scope.launch{discovery=app.vpn.providerDiscovery(server,user,pass)}}){Text("Scopri VPN dal provider")}}
        discovery?.let{ d -> item{LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 14.dp){Column(verticalArrangement=Arrangement.spacedBy(5.dp)){Text("Configurazione trovata: ${d.protocol}", color = glassForeground());Text(d.serverEndpoint, color = glassForeground().copy(.78f));Text("DNS: ${d.dns.joinToString()}", color = glassForeground().copy(.65f))}}} }
        item{Text("Stato: $state",color = glassForeground())}
        item{Text("OpenVPN resta rappresentato a livello di configurazione, come nella sorgente iOS: non viene pubblicizzato come motore cifrato integrato.",color = glassForeground().copy(.55f),fontSize=12.sp)}
    }
}

@Composable private fun BackupScreen(app:GassPlayerApplication){
    val context=LocalContext.current; val scope=rememberCoroutineScope(); var status by remember{mutableStateOf("")}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri-> if(uri!=null) scope.launch{val text=app.backup.fullExport(); context.contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray())};status="Backup esportato"}}
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri-> if(uri!=null) scope.launch{val text=context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty();runCatching{app.backup.importAny(text)};status="Import completato o parziale"}}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Button({create.launch("gassplayer-backup.json")}){Text("Esporta sorgenti + preferenze")};OutlinedButton({pick.launch(arrayOf("application/json","text/*"))}){Text("Importa JSON")};Button({scope.launch{app.cloud.requestBackup();status="Richiesta Auto Backup inviata"}}){Text("Sincronizza con backup Android")};Text(status,color = glassForeground())}
}

@Composable
private fun TraktScreen(app: GassPlayerApplication, settings: AppSettings, vm: MainViewModel) {
    val account by app.prefs.traktFlow.collectAsStateWithLifecycle(TraktAccount())
    var local by remember(settings) { mutableStateOf(settings) }
    var device by remember { mutableStateOf<TraktDeviceCode?>(null) }
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(device?.deviceCode) {
        val code = device ?: return@LaunchedEffect
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < code.expiresInSec * 1000L) {
            kotlinx.coroutines.delay(5000)
            val token = runCatching { app.trakt.pollDevice(local.traktClientId, local.traktClientSecret, code.deviceCode) }.getOrNull()
            if (token != null) { app.prefs.saveTrakt(token); device = null; message = "Trakt collegato"; break }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Text("Collega Trakt.tv", color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(local.traktClientId, { local = local.copy(traktClientId = it); vm.updateSettings(local) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Client ID") }) }
        item { OutlinedTextField(local.traktClientSecret, { local = local.copy(traktClientSecret = it); vm.updateSettings(local) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Client Secret") }) }
        if (account.accessToken.isNotBlank()) {
            item { LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 14.dp) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Connesso a Trakt.tv", color = glassForeground(), fontWeight = FontWeight.Bold); Text("Lo scrobbling di film/episodi usa il token salvato su questo dispositivo.", color = glassForeground().copy(.65f)); OutlinedButton({ scope.launch { app.prefs.saveTrakt(TraktAccount()) } }) { Text("Disconnetti") } } } }
        } else if (device == null) {
            item { Button(enabled = local.traktClientId.isNotBlank() && local.traktClientSecret.isNotBlank(), onClick = { scope.launch { device = app.trakt.deviceCode(local.traktClientId.trim(), local.traktClientSecret.trim()); message = if (device != null) "Autorizza il dispositivo e attendi la conferma." else "Impossibile iniziare il flusso Trakt." } }) { Text("Connetti a Trakt.tv") } }
        } else {
            val code = device!!
            item { LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 0.dp) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Codice dispositivo", color = glassForeground().copy(.7f)); Text(code.userCode, color = glassForeground(), fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(code.verificationUrl, color = glassForeground().copy(.7f)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (code.verificationUrl.startsWith("http")) code.verificationUrl else "https://${code.verificationUrl}"))) }) { Text("Apri Trakt") }; OutlinedButton({ device = null }) { Text("Annulla") } }; Text("In attesa dell'autorizzazione…", color = glassForeground().copy(.65f)) } } }
        }
        if (message.isNotBlank()) item { Text(message, color = glassForeground()) }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeCustomizationScreen(vm: MainViewModel, scopeKey: String = "home", onBack: () -> Unit = {}) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var local by remember(settings, scopeKey) { mutableStateOf(settings) }
    var continueMenu by remember { mutableStateOf(false) }
    val defaults = when (scopeKey) {
        "live" -> defaultLiveSections
        "movies" -> defaultMovieSections
        "series" -> defaultSeriesSections
        else -> defaultHomeSections
    }
    fun readOrder(s: AppSettings): List<String> = when (scopeKey) {
        "live" -> s.liveSectionOrder
        "movies" -> s.movieSectionOrder
        "series" -> s.seriesSectionOrder
        else -> s.homeSectionOrder
    }
    fun readHidden(s: AppSettings): Set<String> = when (scopeKey) {
        "live" -> s.hiddenLiveSections
        "movies" -> s.hiddenMovieSections
        "series" -> s.hiddenSeriesSections
        else -> s.hiddenHomeSections
    }
    fun saveLayout(order: List<String>, hidden: Set<String>, continueKind: String? = local.homeContinueKind) {
        val next = when (scopeKey) {
            "live" -> local.copy(liveSectionOrder = order, hiddenLiveSections = hidden)
            "movies" -> local.copy(movieSectionOrder = order, hiddenMovieSections = hidden)
            "series" -> local.copy(seriesSectionOrder = order, hiddenSeriesSections = hidden)
            else -> local.copy(homeSectionOrder = order, hiddenHomeSections = hidden, homeContinueKind = continueKind)
        }
        local = next
        vm.updateSettings(next)
    }

    val completeOrder = remember(readOrder(local), defaults) { (readOrder(local) + defaults).distinct() }
    val hiddenSet = readHidden(local).intersect(completeOrder.toSet())
    val visible = completeOrder.filterNot { it in hiddenSet }
    val hidden = completeOrder.filter { it in hiddenSet }
    val title = when (scopeKey) { "live" -> "Sezioni Live TV"; "movies" -> "Sezioni Film"; "series" -> "Sezioni Serie TV"; else -> "Sezioni Home" }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, contentPadding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Indietro", tint = glassForeground()) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, color = glassForeground(), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("Riordina, nascondi o riaggiungi le sezioni. Le modifiche si applicano subito.", color = glassForeground().copy(.63f), fontSize = 12.sp)
                    }
                    Icon(Icons.Default.Tune, null, tint = Color(0xFF9ABEFF), modifier = Modifier.size(24.dp))
                }
                OutlinedButton(onClick = { saveLayout(defaults, emptySet(), null) }) {
                    Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Ripristina ordine iOS")
                }
            }
        }
        Text("VISIBILI · ${visible.size}", color = glassForeground().copy(.58f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            items(visible, key = { it }) { id ->
                val index = visible.indexOf(id)
                LiquidGlassSurface(Modifier.fillMaxWidth().animateItem(), cornerRadius = 17.dp, contentPadding = 10.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.DragHandle, null, tint = glassForeground().copy(.45f))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(homeSectionTitles[id] ?: id, color = glassForeground(), fontWeight = FontWeight.Medium)
                            if (scopeKey == "home" && id == "continueWatching") {
                                Box {
                                    TextButton(onClick = { continueMenu = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                                        Icon(Icons.Default.Tune, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                        Text("Filtro: ${when (local.homeContinueKind) { "live" -> "Live TV"; "movie" -> "Film"; "series" -> "Serie TV"; else -> "Tutti" }}", fontSize = 12.sp)
                                    }
                                    DropdownMenu(continueMenu, { continueMenu = false }) {
                                        listOf(null to "Tutti", "live" to "Live TV", "movie" to "Film", "series" to "Serie TV").forEach { (value, label) ->
                                            DropdownMenuItem(text = { Text(label) }, onClick = { saveLayout(completeOrder, hiddenSet, value); continueMenu = false })
                                        }
                                    }
                                }
                            } else Text("Posizione ${index + 1}", color = glassForeground().copy(.48f), fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(enabled = index > 0, onClick = {
                                val nextVisible = visible.toMutableList(); val value = nextVisible.removeAt(index); nextVisible.add(index - 1, value)
                                saveLayout(nextVisible + hidden, hiddenSet)
                            }) { Icon(Icons.Default.KeyboardArrowUp, "Sposta su", tint = if (index > 0) glassForeground() else glassForeground(.18f)) }
                            IconButton(enabled = index < visible.lastIndex, onClick = {
                                val nextVisible = visible.toMutableList(); val value = nextVisible.removeAt(index); nextVisible.add(index + 1, value)
                                saveLayout(nextVisible + hidden, hiddenSet)
                            }) { Icon(Icons.Default.KeyboardArrowDown, "Sposta giù", tint = if (index < visible.lastIndex) glassForeground() else glassForeground(.18f)) }
                        }
                        IconButton(onClick = { saveLayout(completeOrder, hiddenSet + id) }) { Icon(Icons.Default.VisibilityOff, "Nascondi", tint = glassForeground().copy(.72f)) }
                    }
                }
            }
            if (hidden.isNotEmpty()) {
                item { Spacer(Modifier.height(8.dp)); Text("NASCOSTE · ${hidden.size}", color = glassForeground().copy(.58f), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                items(hidden, key = { "hidden-$it" }) { id ->
                    LiquidGlassSurface(Modifier.fillMaxWidth().animateItem(), cornerRadius = 17.dp, contentPadding = 10.dp) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.VisibilityOff, null, tint = glassForeground().copy(.38f))
                            Text(homeSectionTitles[id] ?: id, color = glassForeground().copy(.75f), modifier = Modifier.weight(1f))
                            TextButton(onClick = { saveLayout(completeOrder, hiddenSet - id) }) {
                                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Aggiungi")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MergedPlaylistScreen(app: GassPlayerApplication) {
    val sources by app.sources.sources.collectAsStateWithLifecycle(emptyList())
    val playlists by app.mergedPlaylists.flow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Playlist unificate", color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Raggruppa sorgenti in raccolte logiche, mantenendo l'origine di ogni contenuto.", color = glassForeground().copy(.65f))
        OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nome playlist") })
        LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sources, key = { it.id }) { source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected.contains(source.id), { selected = if (it) selected + source.id else selected - source.id })
                    Column { Text(source.name, color = glassForeground()); Text(source.host, color = glassForeground().copy(.5f), fontSize = 12.sp) }
                }
            }
        }
        Button({ scope.launch { app.mergedPlaylists.create(name, selected.toList()); name = ""; selected = emptySet() } }, enabled = name.isNotBlank() && selected.isNotEmpty()) { Text("Crea playlist unificata") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 0.dp) { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(playlist.name, color = glassForeground(), fontWeight = FontWeight.Bold); Text("${playlist.memberSourceIds.size} sorgenti", color = glassForeground().copy(.55f)) }
                    IconButton({ scope.launch { app.mergedPlaylists.remove(playlist.id) } }) { Icon(Icons.Default.Delete, null) }
                } }
            }
        }
    }
}

@Composable private fun DiagnosticsScreen(app:GassPlayerApplication){ var log by remember{mutableStateOf(app.diagnostics.read())};Column{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({log=app.diagnostics.read()}){Text("Aggiorna")};OutlinedButton({app.diagnostics.clear();log=""}){Text("Svuota")}};Spacer(Modifier.height(10.dp));LazyColumn{item{Text(log, color = glassForeground().copy(.75f), fontSize=12.sp)}}}}

private fun MainViewModel.appFavorite(state: FavoriteState,item:MediaItem)=when(item.kind){MediaKind.LIVE->item.id in state.live;MediaKind.MOVIE,MediaKind.EPISODE->item.id in state.movies;MediaKind.SERIES->item.id in state.series}

private fun Context.closestMainActivity(): MainActivity? {
    var current: Context = this
    while (current is android.content.ContextWrapper) {
        if (current is MainActivity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? MainActivity
}
