package com.gassplayer.android.ui.ios

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.AppSettings
import com.gassplayer.android.data.CatalogState
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.MediaSourceConfig
import com.gassplayer.android.data.ParentalState
import com.gassplayer.android.data.WatchEntry
import com.gassplayer.android.ui.MainActivity
import com.gassplayer.android.ui.MainViewModel
import com.gassplayer.android.ui.IosPlayerScreen

private data class MainDestination(val route: String, val title: String, val icon: ImageVector)

@Composable
fun IosParityNavHost(vm: MainViewModel, app: GassPlayerApplication) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val watch by vm.watch.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isTv = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    var showSplash by rememberSaveable { mutableStateOf(true) }
    var route by rememberSaveable { mutableStateOf("home") }
    var playerItem by remember { mutableStateOf<MediaItem?>(null) }
    var detailItem by remember { mutableStateOf<MediaItem?>(null) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLogin by rememberSaveable { mutableStateOf(false) }

    if (showSplash) {
        SplashScreenView(onFinished = { showSplash = false })
        return
    }

    val destinations = listOf(
        MainDestination("home", "Home", Icons.Default.Home),
        MainDestination("live", "Live TV", Icons.Default.LiveTv),
        MainDestination("movies", "VOD", Icons.Default.Movie),
        MainDestination("series", "Serie TV", Icons.Default.Tv),
    )

    IosBackground(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (isTv) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f), modifier = Modifier.fillMaxHeight().width(112.dp)) {
                    Spacer(Modifier.height(12.dp))
                    destinations.forEach { dest ->
                        NavigationRailItem(
                            selected = route == dest.route,
                            onClick = { route = dest.route },
                            icon = { Icon(dest.icon, null) },
                            label = { Text(dest.title, fontSize = 11.sp) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(selected = showSources, onClick = { showSources = true }, icon = { Icon(Icons.Default.SettingsInputAntenna, null) }, label = { Text("Sorgenti", fontSize = 11.sp) })
                    NavigationRailItem(selected = showSettings, onClick = { showSettings = true }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Impostazioni", fontSize = 11.sp) })
                }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = if (isTv) 20.dp else 16.dp)) {
                IosTopBar(
                    route = route,
                    onSearch = { route = "search" },
                    onSources = { showSources = true },
                    onSettings = { showSettings = true }
                )
                if (loading && route != "sources") LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!message.isNullOrBlank() && route !in setOf("sources", "settings")) {
                    IosGlassCard(padding = PaddingValues(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = IosOrange)
                            Spacer(Modifier.width(8.dp))
                            Text(message.orEmpty(), modifier = Modifier.weight(1f), color = IosOrange, maxLines = 3)
                            IosGlassIconButton(Icons.Default.Refresh, "Riprova", onClick = { vm.refresh(true) }, size = 38)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (route) {
                        "home" -> IosHomeView(vm, catalog, favorites, watch, sources, onNavigate = { if (it == "sources") showSources = true else route = it }, onPlay = { playerItem = it })
                        "live" -> IosChannelGridView(app, vm, catalog?.live.orEmpty(), catalog?.liveCategories.orEmpty(), favorites, parental, MediaKind.LIVE, onPlay = { playerItem = it })
                        "movies" -> IosChannelGridView(app, vm, catalog?.movies.orEmpty(), catalog?.vodCategories.orEmpty(), favorites, parental, MediaKind.MOVIE, onPlay = { playerItem = it }, onOpenDetail = { detailItem = it })
                        "series" -> IosChannelGridView(app, vm, catalog?.series.orEmpty(), catalog?.seriesCategories.orEmpty(), favorites, parental, MediaKind.SERIES, onPlay = { playerItem = it }, onOpenDetail = { detailItem = it })
                        "epg" -> EpgGridScreenCompat(app, vm, catalog, favorites, settings, onPlay = { playerItem = it })
                        "search" -> IosGlobalSearchView(app, vm, catalog, onPlay = { playerItem = it }, onRoute = { route = it }, onOpenDetail = { detailItem = it })
                        "downloads" -> IosDownloadsView(app)
                        "diagnostics" -> IosDebugConsoleView(app)
                        "epg-manage" -> IosEpgManageView(app)
                        "home-customize" -> IosHomeCustomizationView(vm)
                        "vpn" -> IosPersonalVpnView(app)
                        "parental" -> IosParentalLockView(vm, parental)
                        "trakt" -> IosTraktConnectView(app, vm)
                        "metadata" -> IosMetadataSettingsView(vm)
                        "source-manager" -> IosSourceManagerView(app, vm)
                        else -> IosHomeView(vm, catalog, favorites, watch, sources, onNavigate = { if (it == "sources") showSources = true else route = it }, onPlay = { playerItem = it })
                    }
                }
                if (!isTv) {
                    IosTabBar(destinations, route, onSelect = { route = it }, onSources = { showSources = true }, onSettings = { showSettings = true })
                }
            }
        }

        if (playerItem != null) {
            IosPlayerScreen(
                app,
                playerItem!!,
                settings,
                catalog,
                onBack = { playerItem = null },
                onPip = { (context as? MainActivity)?.enterPlayerPip() },
                onNavigateToItem = { playerItem = it },
                onOpenSearch = { playerItem = null; route = "search" }
            )
        }
    }


    if (detailItem != null) {
        when (detailItem!!.kind) {
            MediaKind.MOVIE -> IosMovieDetailView(app, vm, detailItem!!, catalog?.allItems.orEmpty(), favorites, onDismiss = { detailItem = null }, onPlay = { playerItem = it; detailItem = null })
            MediaKind.SERIES -> IosSeriesEpisodesView(app, vm, detailItem!!, catalog?.episodes.orEmpty(), catalog?.allItems.orEmpty(), favorites, onDismiss = { detailItem = null }, onPlay = { playerItem = it; detailItem = null })
            else -> Unit
        }
    }

    if (showSources) {
        IosSourcesDialog(app, vm, onDismiss = { showSources = false }, onNavigate = { route = it; showSources = false })
    }
    if (showSettings) {
        IosSettingsDialog(app, vm, settings, onDismiss = { showSettings = false }, onNavigate = { if (it == "sources") { showSettings = false; showSources = true } else { route = it; showSettings = false } })
    }
    if (showLogin) {
        IosLoginDialog(app, vm, onDismiss = { showLogin = false })
    }
}

@Composable
private fun IosTopBar(route: String, onSearch: () -> Unit, onSources: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("GassPlayer", fontSize = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(routeTitle(route), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f))
        }
        IosGlassIconButton(Icons.Default.Search, "Cerca", onSearch, size = 40)
        Spacer(Modifier.width(8.dp))
        IosGlassIconButton(Icons.Default.SettingsInputAntenna, "Sorgenti", onSources, size = 40)
        Spacer(Modifier.width(8.dp))
        IosGlassIconButton(Icons.Default.Settings, "Impostazioni", onSettings, size = 40)
    }
}

private val CatalogState.allItems: List<MediaItem> get() = live + movies + series + episodes

private fun routeTitle(route: String) = when (route) {
    "home" -> "Home"
    "live" -> "Live TV"
    "movies" -> "VOD"
    "series" -> "Serie TV"
    "epg" -> "Guida TV"
    "search" -> "Ricerca globale"
    "downloads" -> "Download"
    else -> "GassPlayer"
}

@Composable
private fun IosTabBar(destinations: List<MainDestination>, route: String, onSelect: (String) -> Unit, onSources: () -> Unit, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f), tonalElevation = 2.dp, shadowElevation = 10.dp, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            destinations.forEach { dest ->
                NavigationBarItem(selected = route == dest.route, onClick = { onSelect(dest.route) }, icon = { Icon(dest.icon, null) }, label = { Text(dest.title, fontSize = 10.sp) })
            }
            NavigationBarItem(selected = false, onClick = onSources, icon = { Icon(Icons.Default.SettingsInputAntenna, null) }, label = { Text("Sorgenti", fontSize = 10.sp) })
            NavigationBarItem(selected = false, onClick = onSettings, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Impost.", fontSize = 10.sp) })
        }
    }
}

@Composable
fun EpgGridScreenCompat(app: GassPlayerApplication, vm: MainViewModel, catalog: CatalogState?, favorites: com.gassplayer.android.data.FavoriteState, settings: AppSettings, onPlay: (MediaItem) -> Unit) {
    com.gassplayer.android.ui.EpgGridScreen(app, vm, catalog?.live.orEmpty(), catalog?.liveCategories.orEmpty(), favorites, settings, onPlay)
}
