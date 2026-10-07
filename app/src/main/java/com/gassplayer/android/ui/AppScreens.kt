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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.launch
import java.util.UUID

private val Blue = Color(0xFF3478F6)
private val Dark = Color(0xFF050609)

@Composable
fun GassPlayerNavHost(vm: MainViewModel, app: GassPlayerApplication) {
    var route by remember { mutableStateOf("home") }
    var playerItem by remember { mutableStateOf<MediaItem?>(null) }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val favorite by vm.favorites.collectAsStateWithLifecycle()
    val watch by vm.watch.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tv = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    if (playerItem != null) {
        PlayerScreen(app, playerItem!!, settings, onBack = { playerItem = null }, onPip = { (context as? MainActivity)?.enterPlayerPip() })
        return
    }

    AdaptiveShell(route, tv, onRoute = { route = it }, title = when(route) {
        "home" -> "GassPlayer"; "live" -> "Live TV"; "movies" -> "Film"; "series" -> "Serie"; "epg" -> "Guida TV"; "search" -> "Cerca"; "sources" -> "Sorgenti"; "downloads" -> "Download"; "settings" -> "Impostazioni"; "home-customize" -> "Personalizza Home"; "vpn" -> "VPN"; "parental" -> "Controllo genitori"; "diagnostics" -> "Diagnostica"; "backup" -> "Backup e migrazione"; "epg-manage" -> "Fonti EPG"; "merged" -> "Playlist unificate"; else -> "GassPlayer"
    }) {
        when (route) {
            "home" -> HomeScreen(vm, catalog, favorite, watch, sources, onRoute = { route = it }, onPlay = { playerItem = it })
            "live" -> CatalogScreen("Live TV", catalog?.live.orEmpty(), catalog?.liveCategories.orEmpty(), favorite, parental, vm, onPlay = { playerItem = it }, showNumbers = settings.showChannelNumbers)
            "movies" -> CatalogScreen("Film", catalog?.movies.orEmpty(), catalog?.vodCategories.orEmpty(), favorite, parental, vm, onPlay = { playerItem = it }, detail = true)
            "series" -> SeriesScreen(catalog?.series.orEmpty(), catalog?.episodes.orEmpty(), catalog?.seriesCategories.orEmpty(), favorite, parental, vm, onPlay = { playerItem = it })
            "epg" -> EpgScreen(app, catalog?.live.orEmpty(), onPlay = { playerItem = it })
            "epg-manage" -> ExternalEpgManageScreen(app)
            "merged" -> MergedPlaylistScreen(app)
            "home-customize" -> HomeCustomizationScreen(vm)
            "trakt" -> TraktScreen(app, settings, vm)
            "search" -> SearchScreen(app, catalog, onPlay = { playerItem = it }, onRoute = { route = it })
            "sources" -> SourcesScreen(app, vm)
            "downloads" -> DownloadsScreen(app)
            "settings" -> SettingsScreen(app, settings, vm, onOpen = { route = it })
            "vpn" -> VpnScreen(app)
            "parental" -> ParentalScreen(vm, parental)
            "diagnostics" -> DiagnosticsScreen(app)
            "backup" -> BackupScreen(app)
        }
    }
}

@Composable
private fun AdaptiveShell(route: String, tv: Boolean, onRoute: (String) -> Unit, title: String, content: @Composable () -> Unit) {
    val destinations = listOf("home" to "Home", "live" to "Live TV", "movies" to "Film", "series" to "Serie", "epg" to "Guida", "search" to "Cerca", "sources" to "Sorgenti", "settings" to "Impostazioni")
    Row(Modifier.fillMaxSize().background(Dark)) {
        NavigationRail(modifier = Modifier.fillMaxHeight().width(if (tv) 132.dp else 92.dp), containerColor = Color(0xFF0E1016)) {
            Spacer(Modifier.height(12.dp)); destinations.forEach { (id, label) ->
                NavigationRailItem(selected = route == id, onClick = { onRoute(id) }, icon = { Icon(navIcon(id), null) }, label = { Text(label, maxLines = 1) }, modifier = Modifier)
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 28.dp else 16.dp, vertical = 18.dp)) {
            Text(title, color = Color.White, fontSize = if (tv) 32.sp else 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp)); Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

private fun navIcon(id: String) = when(id) { "home" -> Icons.Default.Home; "live" -> Icons.Default.LiveTv; "movies" -> Icons.Default.Movie; "series" -> Icons.Default.Tv; "epg" -> Icons.Default.CalendarMonth; "search" -> Icons.Default.Search; "sources" -> Icons.Default.SettingsInputAntenna; else -> Icons.Default.Settings }

@Composable
private fun HomeScreen(vm: MainViewModel, catalog: CatalogState?, fav: FavoriteState, watch: List<WatchEntry>, sources: List<MediaSourceConfig>, onRoute: (String) -> Unit, onPlay: (MediaItem) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val visibleOrder = settings.homeSectionOrder.ifEmpty { defaultHomeSections }.distinct().filterNot { settings.hiddenHomeSections.contains(it) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        items(visibleOrder, key = { it }) { section ->
            when (section) {
                "heading" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton({ onRoute("live") }) { Icon(Icons.Default.LiveTv, null); Spacer(Modifier.width(8.dp)); Text("Live TV") }
                    FilledTonalButton({ onRoute("movies") }) { Icon(Icons.Default.Movie, null); Spacer(Modifier.width(8.dp)); Text("Film") }
                    FilledTonalButton({ onRoute("series") }) { Icon(Icons.Default.Tv, null); Spacer(Modifier.width(8.dp)); Text("Serie") }
                    FilledTonalButton({ onRoute("epg") }) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("Guida TV") }
                    OutlinedButton({ onRoute("home-customize") }) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(8.dp)); Text("Personalizza") }
                }
                "continueWatching" -> if (watch.isNotEmpty()) Section("Continua a guardare", null) { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(watch.take(10)) { entry -> val media = catalog?.allItems?.firstOrNull { it.id == entry.contentId }; if (media != null) MediaCard(media, false, onClick = { onPlay(media) }) else Text(entry.title, color = Color.White) } } }
                "sourceCard" -> Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141720)), border = BorderStroke(1.dp, Color.White.copy(.08f)), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("Sorgenti", color = Color.White, fontWeight = FontWeight.Bold); Text("${sources.size} configurate • ${catalog?.allItems?.size ?: 0} elementi", color = Color.White.copy(.65f)) }; OutlinedButton({ onRoute("sources") }) { Text("Gestisci") } } }
                "sources" -> Section("Sorgenti", null) { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(sources.take(12)) { s -> AssistChip({ onRoute("sources") }, label = { Text(s.name) }) } } }
                "liveTV" -> Section("Live preferiti", null) { val list = catalog?.live?.filter { it.id in fav.live }.orEmpty(); if (list.isEmpty()) EmptyHint("Nessun canale preferito") else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(list.take(12)) { MediaCard(it, true, onClick = { onPlay(it) }) } } }
                "guidaTV" -> Section("Guida TV", null) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("EPG e reminder locali", color = Color.White.copy(.7f)); OutlinedButton({ onRoute("epg") }) { Text("Apri guida") } } }
                "favoriteChannels" -> Section("Canali preferiti", null) { val list = catalog?.live?.filter { it.id in fav.live }.orEmpty(); if (list.isEmpty()) EmptyHint("Nessun canale preferito") else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(list.take(12)) { MediaCard(it, true, onClick = { onPlay(it) }) } } }
                "favoriteSeries" -> Section("Serie TV preferite", null) { val list = catalog?.series?.filter { it.id in fav.series }.orEmpty(); if (list.isEmpty()) EmptyHint("Nessuna serie preferita") else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(list.take(12)) { MediaCard(it, true, onClick = { onPlay(it) }) } } }
                "favoriteMovies" -> Section("Film preferiti", null) { val list = catalog?.movies?.filter { it.id in fav.movies }.orEmpty(); if (list.isEmpty()) EmptyHint("Nessun film preferito") else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(list.take(12)) { MediaCard(it, true, onClick = { onPlay(it) }) } } }
                "onDemand" -> Section("On demand", null) { val list = catalog?.movies.orEmpty(); if (list.isEmpty()) EmptyHint("Nessun film disponibile") else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(list.take(12)) { MediaCard(it, it.id in fav.movies, onClick = { onPlay(it) }) } } }
            }
        }
    }
}

@Composable private fun Section(title: String, onSeeAll: (() -> Unit)?, content: @Composable () -> Unit) { Column { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); if (onSeeAll != null) TextButton(onSeeAll) { Text("Vedi tutto") } }; Spacer(Modifier.height(8.dp)); content() } }
@Composable private fun EmptyHint(text: String) { Text(text, color = Color.White.copy(.5f), modifier = Modifier.padding(vertical = 12.dp)) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(item: MediaItem, favorite: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val w = if (item.kind == MediaKind.LIVE) 190.dp else 160.dp; val h = if (item.kind == MediaKind.LIVE) 108.dp else 220.dp
    Card(modifier = Modifier.width(w).combinedClickable(onClick = onClick, onLongClick = onLongClick), colors = CardDefaults.cardColors(containerColor = Color(0xFF141720)), shape = MaterialTheme.shapes.medium) {
        Box(Modifier.fillMaxWidth().height(h).background(Color(0xFF0C0E13))) { item.posterUrl?.let { AsyncImage(it, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }; if (favorite) Icon(Icons.Default.Favorite, null, tint = Blue, modifier = Modifier.padding(8.dp).align(Alignment.TopEnd)); if (item.kind == MediaKind.LIVE && item.logoUrl != null) AsyncImage(item.logoUrl, null, modifier = Modifier.size(64.dp).align(Alignment.Center), contentScale = ContentScale.Fit) }
        Column(Modifier.padding(10.dp)) { Text(item.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium); item.group?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White.copy(.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    }
}

@Composable
private fun CatalogScreen(title: String, items: List<MediaItem>, categories: List<Category>, favorite: FavoriteState, parental: ParentalState, vm: MainViewModel, onPlay: (MediaItem) -> Unit, showNumbers: Boolean = false, detail: Boolean = false) {
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var filter by remember { mutableStateOf<String?>(null) }
    if (detail && selected != null) {
        MovieDetailScreen(vm.app, selected!!, vm.catalog.value?.allItems.orEmpty(), favorite, onBack = { selected = null }, onPlay = { i, pos -> onPlayWithPosition(onPlay, i, pos) })
        return
    }
    val categoryNames = categories.associate { it.id to it.name }
    val filterKeys = items.mapNotNull { it.categoryId ?: it.group }.distinct().take(60)
    val filtered = items.filter { filter == null || (it.categoryId ?: it.group) == filter }.filterNot { it.id in parental.lockedIds }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (filterKeys.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(filter == null, { filter = null }, label = { Text("Tutto") }) }
            items(filterKeys) { key -> FilterChip(filter == key, { filter = key }, label = { Text(categoryNames[key] ?: key) }) }
        }
        if (filtered.isEmpty()) EmptyHint("Catalogo vuoto o filtro senza risultati.") else LazyVerticalGrid(columns = GridCells.Adaptive(minSize = if (detail) 160.dp else 190.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(filtered) { item -> MediaCard(item, vm.appFavorite(favorite, item), onClick = { if (detail) selected = item else onPlay(item) }) }
        }
    }
}

private fun onPlayWithPosition(onPlay: (MediaItem) -> Unit, item: MediaItem, position: Long) { onPlay(item.copy(metadataTag = "resume:$position")) }

@Composable private fun SeriesScreen(series: List<MediaItem>, episodes: List<MediaItem>, categories: List<Category>, favorite: FavoriteState, parental: ParentalState, vm: MainViewModel, onPlay: (MediaItem) -> Unit) {
    var selected by remember { mutableStateOf<MediaItem?>(null) }; var filter by remember { mutableStateOf<String?>(null) }
    if (selected != null) { SeriesDetailScreen(vm.app, selected!!, episodes, favorite, onBack = { selected = null }, onPlay = { i, pos -> onPlayWithPosition(onPlay, i, pos) }); return }
    val names=categories.associate{it.id to it.name}; val keys=series.mapNotNull{it.categoryId ?: it.group}.distinct().take(60); val filtered=series.filterNot{it.id in parental.lockedIds}.filter{filter==null || (it.categoryId ?: it.group)==filter}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        if(keys.isNotEmpty()) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){item{FilterChip(filter==null,{filter=null},label={Text("Tutto")})};items(keys){k->FilterChip(filter==k,{filter=k},label={Text(names[k]?:k)})}}
        LazyVerticalGrid(GridCells.Adaptive(180.dp),contentPadding=PaddingValues(bottom=40.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){items(filtered){s->MediaCard(s,s.id in favorite.series,onClick={selected=s})}}
    }
}

@Composable
private fun SearchScreen(app: GassPlayerApplication, catalog: CatalogState?, onPlay: (MediaItem) -> Unit, onRoute: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val history by app.search.flow.collectAsStateWithLifecycle(SearchHistory())
    val scope = rememberCoroutineScope()
    val results = remember(submitted, catalog) { if (submitted.isBlank()) emptyList() else catalog?.allItems.orEmpty().filter { it.title.contains(submitted, true) }.take(100) }
    Column {
        OutlinedTextField(q, { q = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Cerca live, film, serie") }, trailingIcon = { IconButton({ submitted = q; scope.launch { app.search.add(q) } }) { Icon(Icons.Default.Search, null) } })
        Spacer(Modifier.height(10.dp))
        if (submitted.isBlank() && history.terms.isNotEmpty()) {
            Text("Ricerche recenti", color = Color.White.copy(.6f))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(history.terms) { AssistChip({ q = it; submitted = it }, label = { Text(it) }) } }
            Spacer(Modifier.height(12.dp))
        }
        LazyVerticalGrid(GridCells.Adaptive(180.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(results) { MediaCard(it, false, onClick = { scope.launch { app.search.add(submitted) }; onPlay(it) }) }
        }
    }
}

@Composable
private fun EpgScreen(app: GassPlayerApplication, live: List<MediaItem>, onPlay: (MediaItem) -> Unit) {
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var programs by remember { mutableStateOf<List<EpgProgram>>(emptyList()) }
    val sources by app.sources.sources.collectAsStateWithLifecycle(emptyList())
    val external by app.externalEpg.flow.collectAsStateWithLifecycle(emptyList())
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(live.take(80)) { c ->
                Card(onClick = { selected = c }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (c.logoUrl != null) AsyncImage(c.logoUrl, null, Modifier.size(42.dp))
                        Spacer(Modifier.width(8.dp)); Text(c.title, maxLines=1, overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        LaunchedEffect(selected?.id, external) {
            selected?.let { c ->
                val source = sources.firstOrNull { it.id == c.sourceId }
                var found = emptyList<EpgProgram>()
                if (source != null) {
                    found = runCatching { app.epg.shortEpg(source, c.id.substringAfterLast(':')) }.getOrDefault(emptyList())
                }
                if (found.isEmpty()) {
                    val channelKey = c.metadataTag?.takeIf { it.isNotBlank() } ?: c.id.substringAfterLast(':')
                    val normalizedTitle = c.title.lowercase().filter { it.isLetterOrDigit() }
                    val all = buildList {
                        for (e in external.filter { it.isEnabled }) {
                            runCatching { app.epg.xmltv(e.urlString, e.id) }.getOrDefault(emptyList()).also { addAll(it) }
                        }
                    }
                    found = all.filter { p ->
                        p.streamId.equals(channelKey, ignoreCase = true) || p.streamId.equals(c.metadataTag.orEmpty(), ignoreCase = true) || p.description.orEmpty().lowercase().filter { it.isLetterOrDigit() } == normalizedTitle
                    }.sortedBy { it.startMs }.take(60)
                }
                programs = found
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {  }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(programs) { p ->
                    Card(onClick = { if (selected != null) onPlay(selected!!) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(p.title, fontWeight = FontWeight.Bold); Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(p.startMs))) }
                                TextButton({ app.reminders.schedule(p) }) { Text("Ricorda") }
                            }
                            p.description?.let { Text(it, maxLines=3, overflow=TextOverflow.Ellipsis) }
                            LinearProgressIndicator(progress = { p.progress() }, Modifier.fillMaxWidth())
                            if (p.hasArchive) TextButton({ selected?.let { onPlay(it) } }) { Text("Catch-up") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExternalEpgManageScreen(app: GassPlayerApplication) {
    val sources by app.externalEpg.flow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Fonti XMLTV esterne", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nome fonte") })
        OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("URL XMLTV") })
        Button({ scope.launch { if (app.externalEpg.add(name, url)) { name = ""; url = "" } } }) { Text("Aggiungi fonte EPG") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.id }) { source ->
                Card { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(source.name, color = Color.White, fontWeight = FontWeight.Bold); Text(source.urlString, color = Color.White.copy(.6f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Switch(source.isEnabled, { enabled -> scope.launch { app.externalEpg.toggle(source.id, enabled) } })
                    IconButton({ scope.launch { app.externalEpg.remove(source.id) } }) { Icon(Icons.Default.Delete, null) }
                } }
            }
        }
    }
}

@Composable private fun SourcesScreen(app: GassPlayerApplication, vm: MainViewModel) {
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
                Card {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if(s.isPinned) "★ ${s.name}" else s.name, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("${s.type} • ${s.host}", color = Color.White.copy(.6f))
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
private fun RenameSourceDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rinomina sorgente") }, text = { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nome") }) }, confirmButton = { Button({ onSave(name) }) { Text("Salva") } }, dismissButton = { TextButton(onDismiss) { Text("Annulla") } })
}

@Composable
private fun AddSourceDialog(app: GassPlayerApplication, vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = if (type == SourceType.XTREAM) host.isNotBlank() && user.isNotBlank() && pass.isNotBlank() else url.isNotBlank()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Nuova sorgente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nome") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceType.entries.take(2).forEach { sourceType ->
                        FilterChip(selected = sourceType == type, onClick = { if (!busy) type = sourceType }, label = { Text(sourceType.name) })
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
            }
        },
        confirmButton = {
            Button(enabled = valid && !busy, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    val actualHost = host.trim().ifBlank { url.trim() }
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
                            SourceType.XTREAM -> app.xtream.authenticate(XtreamCredentials(candidate.host, candidate.username.orEmpty(), candidate.password.orEmpty()))
                            SourceType.M3U8 -> app.network.getText(candidate.playlistUrl ?: candidate.host).takeIf { it.isNotBlank() } ?: error("Playlist vuota")
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
            }) { Text(if (busy) "Verifica…" else "Verifica e salva") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable private fun DownloadsScreen(app: GassPlayerApplication) {
    val infos by androidx.work.WorkManager.getInstance(app).getWorkInfosByTagFlow("gass_download").collectAsStateWithLifecycle(emptyList())
    Column {
        Text("Download in background", color=Color.White)
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(infos){info->
                val p = info.progress.getInt("progress", if(info.state==androidx.work.WorkInfo.State.SUCCEEDED) 100 else 0)
                Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(info.outputData.getString("file") ?: info.id.toString(),color=Color.White);LinearProgressIndicator(progress={p/100f},Modifier.fillMaxWidth());Text(info.state.name,color=Color.White.copy(.6f))}}
            }
        }
    }
}

@Composable
private fun SettingsScreen(app: GassPlayerApplication, settings: AppSettings, vm: MainViewModel, onOpen:(String)->Unit) {
    var local by remember(settings) { mutableStateOf(settings) }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp), contentPadding=PaddingValues(bottom=48.dp)) {
        item { SettingToggle("Riprendi riproduzione", local.resumePlayback) { local=local.copy(resumePlayback=it); vm.updateSettings(local) } }
        item { SettingToggle("Autoplay prossimo episodio", local.autoplayNextEpisode) { local=local.copy(autoplayNextEpisode=it); vm.updateSettings(local) } }
        item { SettingToggle("Mostra numeri canale", local.showChannelNumbers) { local=local.copy(showChannelNumbers=it); vm.updateSettings(local) } }
        item { SettingToggle("Download solo Wi‑Fi", local.downloadWifiOnly) { local=local.copy(downloadWifiOnly=it); vm.updateSettings(local) } }
        item { SettingRow("Lingua app", local.language, listOf("system","it","en","es")) { local=local.copy(language=it); vm.updateSettings(local) } }
        item { SettingRow("Aggiornamento catalogo", local.catalogRefreshInterval, RefreshInterval.entries.map { it.id }) { local=local.copy(catalogRefreshInterval=it); vm.updateSettings(local) } }
        item { SettingToggle("Aggiorna catalogo all'avvio", local.catalogRefreshOnLaunch) { local=local.copy(catalogRefreshOnLaunch=it); vm.updateSettings(local) } }
        item { SettingToggle("Mostra EPG nelle card", local.showEpgInChannelTiles) { local=local.copy(showEpgInChannelTiles=it); vm.updateSettings(local) } }
        item { SettingToggle("Precarica serie", local.preloadSeries) { local=local.copy(preloadSeries=it); vm.updateSettings(local) } }
        item { SettingToggle("Aggiornamento EPG automatico", local.epgAutoUpdateEnabled) { local=local.copy(epgAutoUpdateEnabled=it); vm.updateSettings(local) } }
        item { SettingToggle("Adaptive bitrate", local.adaptiveBitrate) { local=local.copy(adaptiveBitrate=it); vm.updateSettings(local) } }
        item { SettingToggle("Hardware decoding", local.hardwareDecode) { local=local.copy(hardwareDecode=it, softwareDecode=!it); vm.updateSettings(local) } }
        item { SettingToggle("Async decode", local.asyncDecode) { local=local.copy(asyncDecode=it); vm.updateSettings(local) } }
        item { SettingToggle("Accurate seek", local.accurateSeek) { local=local.copy(accurateSeek=it); vm.updateSettings(local) } }
        item { SettingToggle("HTTP cache", local.httpCache) { local=local.copy(httpCache=it); vm.updateSettings(local) } }
        item { SettingToggle("Audio only", local.audioOnly) { local=local.copy(audioOnly=it); vm.updateSettings(local) } }
        item { SettingToggle("Deinterlace", local.deinterlace) { local=local.copy(deinterlace=it); vm.updateSettings(local) } }
        item { SettingToggle("Mantieni sottotitoli immagine", local.preserveImageSubtitles) { local=local.copy(preserveImageSubtitles=it); vm.updateSettings(local) } }
        item { SettingToggle("Panorama 360°", local.panorama360) { local=local.copy(panorama360=it); vm.updateSettings(local) } }
        item { SettingToggle("Rotazione automatica 360°", local.autoRotate360) { local=local.copy(autoRotate360=it); vm.updateSettings(local) } }
        item { SettingToggle("Loop riproduzione", local.loopPlayback) { local=local.copy(loopPlayback=it); vm.updateSettings(local) } }
        item { SettingRow("Lingua sottotitoli", local.subtitleLanguage, listOf("it","en","es")) { local=local.copy(subtitleLanguage=it); vm.updateSettings(local) } }
        item { SettingRow("Densità", local.density, listOf("compact","comfortable")) { local=local.copy(density=it); vm.updateSettings(local) } }
        item { SettingRow("Tema", local.theme, listOf("system","light","dark")) { local=local.copy(theme=it); vm.updateSettings(local) } }
        item { SettingInt("Buffer minimo (s)", local.minBufferSec, 1..60) { local=local.copy(minBufferSec=it.coerceAtLeast(1).coerceAtMost(local.maxBufferSec)); vm.updateSettings(local) } }
        item { SettingInt("Buffer massimo (s)", local.maxBufferSec, 5..180) { local=local.copy(maxBufferSec=it.coerceAtLeast(local.minBufferSec).coerceAtMost(180)); vm.updateSettings(local) } }
        item { SettingInt("Velocità", (local.preferredPlaybackSpeed*100).toInt(), 50..200, step=5) { local=local.copy(preferredPlaybackSpeed=it/100f); vm.updateSettings(local) } }
        item { SettingInt("A/V delay (ms)", local.videoDelayMs, -500..500, step=10) { local=local.copy(videoDelayMs=it); vm.updateSettings(local) } }
        item { SettingRow("Aspect ratio", local.aspectRatio, listOf("fit","fill","16:9","4:3","original")) { local=local.copy(aspectRatio=it); vm.updateSettings(local) } }
        item { SettingRow("FFmpeg low resolution", local.ffmpegLowResolution, listOf("full","half","quarter")) { local=local.copy(ffmpegLowResolution=it); vm.updateSettings(local) } }
        item { OutlinedTextField(local.ffmpegOptions,{local=local.copy(ffmpegOptions=it); vm.updateSettings(local)},modifier=Modifier.fillMaxWidth(),label={Text("Opzioni FFmpeg")}) }
        item { OutlinedTextField(local.ffmpegFilters,{local=local.copy(ffmpegFilters=it); vm.updateSettings(local)},modifier=Modifier.fillMaxWidth(),label={Text("Filtri FFmpeg")}) }
        item { Button({ onOpen("epg-manage") }, modifier=Modifier.fillMaxWidth()) { Text("Gestisci fonti EPG") } }
        item { Button({ onOpen("vpn") }, modifier=Modifier.fillMaxWidth()) { Text("VPN personale") } }
        item { Button({ onOpen("parental") }, modifier=Modifier.fillMaxWidth()) { Text("Controllo genitori") } }
        item { Button({ onOpen("downloads") }, modifier=Modifier.fillMaxWidth()) { Text("Download") } }
        item { Button({ onOpen("diagnostics") }, modifier=Modifier.fillMaxWidth()) { Text("Debug e diagnostica") } }
        item { Button({ onOpen("merged") }, modifier=Modifier.fillMaxWidth()) { Text("Playlist unificate") } }
        item { Button({ onOpen("backup") }, modifier=Modifier.fillMaxWidth()) { Text("Backup e migrazione") } }
        item { Button({ onOpen("home-customize") }, modifier=Modifier.fillMaxWidth()) { Text("Personalizza Home") } }
        item { Button({ onOpen("trakt") }, modifier=Modifier.fillMaxWidth()) { Text("Trakt.tv") } }
        item { Text("Metadata", color=Color.White, fontSize=18.sp, fontWeight=FontWeight.Bold, modifier=Modifier.padding(top=12.dp)) }
        item { OutlinedTextField(local.tmdbApiKey,{local=local.copy(tmdbApiKey=it); vm.updateSettings(local)},modifier=Modifier.fillMaxWidth(),label={Text("TMDB API key")}) }
        item { OutlinedTextField(local.omdbApiKey,{local=local.copy(omdbApiKey=it); vm.updateSettings(local)},modifier=Modifier.fillMaxWidth(),label={Text("OMDb API key")}) }
        item { OutlinedTextField(local.traktClientId,{local=local.copy(traktClientId=it); vm.updateSettings(local)},modifier=Modifier.fillMaxWidth(),label={Text("Trakt client ID")}) }
        item { OutlinedTextField(local.traktClientSecret,{local=local.copy(traktClientSecret=it); vm.updateSettings(local)},modifier=Modifier.fillMaxWidth(),label={Text("Trakt client secret")}) }
        item { OutlinedTextField(local.openSubtitlesApiKey,{local=local.copy(openSubtitlesApiKey=it); vm.updateSettings(local)},modifier=Modifier.fillMaxWidth(),label={Text("OpenSubtitles API key")}) }
        item { OutlinedTextField(local.customUserAgent,{local=local.copy(customUserAgent=it); vm.updateSettings(local)},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("User-Agent personalizzato")}) }
        item { OutlinedTextField(local.preferredDns,{local=local.copy(preferredDns=it); vm.updateSettings(local)},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("DNS preferito")}) }
    }
}

@Composable private fun SettingInt(label:String, value:Int, range:IntRange, step:Int=1, onChange:(Int)->Unit){ Column(Modifier.fillMaxWidth()){ Text("$label: $value",color=Color.White); Slider(value=value.toFloat(),onValueChange={onChange(it.toInt())},valueRange=range.first.toFloat()..range.last.toFloat(),steps=((range.last-range.first)/step-1).coerceAtLeast(0)) } }
@Composable private fun SettingToggle(label:String, value:Boolean, onChange:(Boolean)->Unit){ Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){ Text(label,color=Color.White); Switch(value,onChange) } }
@Composable private fun SettingRow(label:String, value:String, choices:List<String>, onChange:(String)->Unit){ var expanded by remember{mutableStateOf(false)}; Box{ OutlinedButton({expanded=true},Modifier.fillMaxWidth()){ Text("$label: $value") }; DropdownMenu(expanded,{expanded=false}){choices.forEach{DropdownMenuItem({Text(it)},onClick={onChange(it);expanded=false})}} } }

@Composable private fun ParentalScreen(vm:MainViewModel,state:ParentalState){ var pin by remember{mutableStateOf("")}; var newPin by remember{mutableStateOf("")}; var msg by remember{mutableStateOf<String?>(null)}; val scope=rememberCoroutineScope(); Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("PIN locale con SHA-256 e blocco per contenuto/categoria",color=Color.White.copy(.7f));OutlinedTextField(newPin,{newPin=it},label={Text("Nuovo PIN")});Button({scope.launch{vm.app.parental.setPin(newPin);msg="PIN impostato"}}){Text("Imposta PIN")};OutlinedTextField(pin,{pin=it},label={Text("Verifica PIN")});Button({scope.launch{msg=if(vm.app.parental.verify(pin))"PIN corretto" else "PIN non valido"}}){Text("Verifica")};msg?.let{Text(it,color=Color.White)} }
}

@Composable private fun VpnScreen(app:GassPlayerApplication){
    val cfg by app.prefs.vpnFlow.collectAsStateWithLifecycle(VPNConfig())
    var server by remember(cfg){mutableStateOf(cfg.serverEndpoint)}; var user by remember(cfg){mutableStateOf(cfg.username)}; var pass by remember(cfg){mutableStateOf(cfg.password)}; var state by remember{mutableStateOf("Disconnessa")}; var discovery by remember{mutableStateOf<VPNConfig?>(null)}; val scope=rememberCoroutineScope()
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){ result -> if(result.resultCode==Activity.RESULT_OK) runCatching{app.vpn.startIkev2();state="Connessa"} }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("IKEv2 usa lo stack VPN nativo Android; WireGuard usa il tunnel ufficiale del progetto WireGuard.",color=Color.White.copy(.7f))}
        item{OutlinedTextField(server,{server=it},Modifier.fillMaxWidth(),label={Text("Endpoint")})}
        item{OutlinedTextField(user,{user=it},Modifier.fillMaxWidth(),label={Text("Username")})}
        item{OutlinedTextField(pass,{pass=it},Modifier.fillMaxWidth(),label={Text("Password")})}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button({scope.launch{val n=cfg.copy(protocol="ikev2",serverEndpoint=server,username=user,password=pass); app.prefs.saveVpn(n); val intent=runCatching{app.vpn.provisionIkev2(n)}.getOrNull(); if(intent!=null) launcher.launch(intent) else runCatching{app.vpn.startIkev2();state="Connessa"}}}){Text("Configura IKEv2")};
            OutlinedButton({app.vpn.stopIkev2();state="Disconnessa"}){Text("Stop")}
        }}
        item{Button({scope.launch{discovery=app.vpn.providerDiscovery(server,user,pass)}}){Text("Scopri VPN dal provider")}}
        discovery?.let{ d -> item{Card{Column(Modifier.padding(14.dp)){Text("Configurazione trovata: ${d.protocol}");Text(d.serverEndpoint);Text("DNS: ${d.dns.joinToString()}")}}} }
        item{Text("Stato: $state",color=Color.White)}
        item{Text("OpenVPN resta rappresentato a livello di configurazione, come nella sorgente iOS: non viene pubblicizzato come motore cifrato integrato.",color=Color.White.copy(.55f),fontSize=12.sp)}
    }
}

@Composable private fun BackupScreen(app:GassPlayerApplication){
    val context=LocalContext.current; val scope=rememberCoroutineScope(); var status by remember{mutableStateOf("")}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri-> if(uri!=null) scope.launch{val text=app.backup.fullExport(); context.contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray())};status="Backup esportato"}}
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri-> if(uri!=null) scope.launch{val text=context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty();runCatching{app.backup.importAny(text)};status="Import completato o parziale"}}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Button({create.launch("gassplayer-backup.json")}){Text("Esporta sorgenti + preferenze")};OutlinedButton({pick.launch(arrayOf("application/json","text/*"))}){Text("Importa JSON")};Button({scope.launch{app.cloud.requestBackup();status="Richiesta Auto Backup inviata"}}){Text("Sincronizza con backup Android")};Text(status,color=Color.White)}
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
        item { Text("Collega Trakt.tv", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(local.traktClientId, { local = local.copy(traktClientId = it); vm.updateSettings(local) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Client ID") }) }
        item { OutlinedTextField(local.traktClientSecret, { local = local.copy(traktClientSecret = it); vm.updateSettings(local) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Client Secret") }) }
        if (account.accessToken.isNotBlank()) {
            item { Card { Column(Modifier.padding(14.dp)) { Text("Connesso a Trakt.tv", color = Color.White, fontWeight = FontWeight.Bold); Text("Lo scrobbling di film/episodi usa il token salvato su questo dispositivo.", color = Color.White.copy(.65f)); OutlinedButton({ scope.launch { app.prefs.saveTrakt(TraktAccount()) } }) { Text("Disconnetti") } } } }
        } else if (device == null) {
            item { Button(enabled = local.traktClientId.isNotBlank() && local.traktClientSecret.isNotBlank(), onClick = { scope.launch { device = app.trakt.deviceCode(local.traktClientId.trim(), local.traktClientSecret.trim()); message = if (device != null) "Autorizza il dispositivo e attendi la conferma." else "Impossibile iniziare il flusso Trakt." } }) { Text("Connetti a Trakt.tv") } }
        } else {
            val code = device!!
            item { Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Codice dispositivo", color = Color.White.copy(.7f)); Text(code.userCode, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(code.verificationUrl, color = Color.White.copy(.7f)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (code.verificationUrl.startsWith("http")) code.verificationUrl else "https://${code.verificationUrl}"))) }) { Text("Apri Trakt") }; OutlinedButton({ device = null }) { Text("Annulla") } }; Text("In attesa dell'autorizzazione…", color = Color.White.copy(.65f)) } } }
        }
        if (message.isNotBlank()) item { Text(message, color = Color.White) }
    }
}

@Composable
private fun HomeCustomizationScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var local by remember(settings) { mutableStateOf(settings) }
    val labels = mapOf("heading" to "Intestazione", "continueWatching" to "Continua a guardare", "sourceCard" to "Scheda sorgente", "sources" to "Sorgenti", "liveTV" to "Live TV", "guidaTV" to "Guida TV", "favoriteChannels" to "Canali preferiti", "favoriteSeries" to "Serie TV preferite", "favoriteMovies" to "Film preferiti", "onDemand" to "On demand")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sezioni Home", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Nascondi o riordina le sezioni; le impostazioni vengono conservate tra i riavvii.", color = Color.White.copy(.65f))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(local.homeSectionOrder, key = { it }) { id ->
                val index = local.homeSectionOrder.indexOf(id)
                Card { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = id !in local.hiddenHomeSections, onCheckedChange = { visible -> local = local.copy(hiddenHomeSections = if (visible) local.hiddenHomeSections - id else local.hiddenHomeSections + id); vm.updateSettings(local) })
                    Text(labels[id] ?: id, color = Color.White, modifier = Modifier.weight(1f))
                    IconButton(enabled = index > 0, onClick = { val list = local.homeSectionOrder.toMutableList(); list.add(index - 1, list.removeAt(index)); local = local.copy(homeSectionOrder = list); vm.updateSettings(local) }) { Icon(Icons.Default.KeyboardArrowUp, null) }
                    IconButton(enabled = index < local.homeSectionOrder.lastIndex, onClick = { val list = local.homeSectionOrder.toMutableList(); list.add(index + 1, list.removeAt(index)); local = local.copy(homeSectionOrder = list); vm.updateSettings(local) }) { Icon(Icons.Default.KeyboardArrowDown, null) }
                } }
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
        Text("Playlist unificate", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Raggruppa sorgenti in raccolte logiche, mantenendo l'origine di ogni contenuto.", color = Color.White.copy(.65f))
        OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nome playlist") })
        LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sources, key = { it.id }) { source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected.contains(source.id), { selected = if (it) selected + source.id else selected - source.id })
                    Column { Text(source.name, color = Color.White); Text(source.host, color = Color.White.copy(.5f), fontSize = 12.sp) }
                }
            }
        }
        Button({ scope.launch { app.mergedPlaylists.create(name, selected.toList()); name = ""; selected = emptySet() } }, enabled = name.isNotBlank() && selected.isNotEmpty()) { Text("Crea playlist unificata") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                Card { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(playlist.name, color = Color.White, fontWeight = FontWeight.Bold); Text("${playlist.memberSourceIds.size} sorgenti", color = Color.White.copy(.55f)) }
                    IconButton({ scope.launch { app.mergedPlaylists.remove(playlist.id) } }) { Icon(Icons.Default.Delete, null) }
                } }
            }
        }
    }
}

@Composable private fun DiagnosticsScreen(app:GassPlayerApplication){ var log by remember{mutableStateOf(app.diagnostics.read())};Column{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({log=app.diagnostics.read()}){Text("Aggiorna")};OutlinedButton({app.diagnostics.clear();log=""}){Text("Svuota")}};Spacer(Modifier.height(10.dp));LazyColumn{item{Text(log, color=Color.White.copy(.75f), fontSize=12.sp)}}}}

@Composable private fun PlayerScreen(app:GassPlayerApplication,item:MediaItem,settings:AppSettings,onBack:()->Unit,onPip:()->Unit){
    val scope=rememberCoroutineScope(); LaunchedEffect(item.id){app.playback.setSettings(settings);val start=item.metadataTag?.removePrefix("resume:")?.toLongOrNull()?:0L;app.playback.play(item,start)}
    DisposableEffect(item.id){ onDispose { val position=app.playback.player.currentPosition; val duration=app.playback.player.duration.coerceAtLeast(0L); scope.launch{app.watch.upsert(WatchEntry(item.id,item.title,item.kind,item.streamUrl,position,duration))} } }
    var speed by remember{mutableStateOf(settings.preferredPlaybackSpeed)};var locked by remember{mutableStateOf(false)}
    val context = LocalContext.current
    val playerView = remember(context) { PlayerView(context).apply { player=app.playback.player; useController=true; keepScreenOn=true; isFocusable=true; isFocusableInTouchMode=true } }
    Box(Modifier.fillMaxSize().background(Color.Black)){
        AndroidView(factory={ playerView }, update={ it.player=app.playback.player }, modifier=Modifier.fillMaxSize().focusable().onKeyEvent { event -> playerView.dispatchKeyEvent(event.nativeKeyEvent) })
        if(locked){Button({locked=false},Modifier.align(Alignment.Center)){Text("Sblocca player")}} else {Row(Modifier.align(Alignment.BottomCenter).padding(20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilledTonalButton({app.playback.skipBack()}){Text("−10")};FilledTonalButton({app.playback.togglePlayPause()}){Text("Play/Pausa")};FilledTonalButton({app.playback.skipForward()}){Text("+10")};Button({speed=if(speed>=2f)1f else speed+0.5f;app.playback.setSpeed(speed)}){Text("${speed}x")};OutlinedButton({locked=true}){Text("Blocca")};OutlinedButton({onPip()}){Text("PiP")};OutlinedButton({onBack()}){Text("Chiudi")}}};app.playback.error.collectAsStateWithLifecycle().value?.let{err->Text(err,color=Color.White,modifier=Modifier.align(Alignment.TopCenter).padding(20.dp))} }
}

private val CatalogState.allItems get() = live + movies + series + episodes
private fun MainViewModel.appFavorite(state: FavoriteState,item:MediaItem)=when(item.kind){MediaKind.LIVE->item.id in state.live;MediaKind.MOVIE,MediaKind.EPISODE->item.id in state.movies;MediaKind.SERIES->item.id in state.series}
