package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gassplayer.android.data.CatalogState
import com.gassplayer.android.data.FavoriteState
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.MediaSourceConfig
import com.gassplayer.android.data.WatchEntry
import com.gassplayer.android.ui.MainViewModel
import com.gassplayer.android.ui.PlayerGlassButton

private data class HomeSection(val id: String, val title: String, val icon: ImageVector)

@Composable
fun IosHomeView(
    vm: MainViewModel,
    catalog: CatalogState?,
    favorites: FavoriteState,
    watch: List<WatchEntry>,
    sources: List<MediaSourceConfig>,
    onNavigate: (String) -> Unit,
    onPlay: (MediaItem) -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val visibleSections = settings.homeSectionOrder.ifEmpty { listOf("heading", "continueWatching", "sourceCard", "sources", "liveTV", "guidaTV", "favoriteChannels", "favoriteSeries", "favoriteMovies", "onDemand") }
        .distinct().filterNot { settings.hiddenHomeSections.contains(it) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(top = 2.dp, bottom = 34.dp)
    ) {
        items(visibleSections, key = { it }) { section ->
            when (section) {
                "heading" -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeAction("Live TV", Icons.Default.LiveTv, IosRed) { onNavigate("live") }
                        HomeAction("Film", Icons.Default.Movie, IosPurple) { onNavigate("movies") }
                        HomeAction("Serie", Icons.Default.Tv, IosBlue) { onNavigate("series") }
                        HomeAction("Guida TV", Icons.Default.CalendarMonth, IosGreen) { onNavigate("epg") }
                        IosGlassIconButton(Icons.Default.Tune, "Personalizza Home", onClick = { onNavigate("home-customize") }, size = 42)
                    }
                }
                "search" -> IosGlassCard {
                    IosTextField(query, { query = it }, "Cerca nel catalogo", modifier = Modifier.fillMaxWidth())
                    if (query.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        IosGlassPrimaryButton("Apri ricerca globale", Icons.Default.Search) { onNavigate("search") }
                    }
                }
                "sourceCard" -> HomeSourceCard(sources, onAdd = { onNavigate("sources") })
                "sources" -> HomeSourcesSummary(sources, onOpen = { onNavigate("sources") })
                "continueWatching" -> {
                    val entries = watch.take(settings.historyLimit.coerceIn(1, 100))
                    if (entries.isNotEmpty()) {
                        IosSectionHeader("Continua a guardare", "Riprendi esattamente da dove eri rimasto")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(entries, key = { it.contentId }) { entry ->
                                val media = catalog?.allItems.orEmpty().firstOrNull { it.id == entry.contentId }
                                if (media != null) IosMediaCard(media, compact = false, onClick = { onPlay(media) }) else {
                                    IosGlassCard(modifier = Modifier.width(210.dp)) {
                                        Text(entry.title, fontWeight = FontWeight.SemiBold)
                                        Text(formatPosition(entry.positionMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                                    }
                                }
                            }
                        }
                    }
                }
                "liveTV" -> HomeLibraryRail("Live TV", "Canali in diretta dalla sorgente attiva", Icons.Default.LiveTv, IosRed, catalog?.live.orEmpty(), favorites.live, onPlay, onNavigate)
                "favoriteChannels" -> HomeLibraryRail("Canali preferiti", "I tuoi canali salvati", Icons.Default.Star, IosOrange, catalog?.live.orEmpty().filter { it.id in favorites.live }, favorites.live, onPlay, onNavigate, isFavoriteRail = true)
                "favoriteMovies" -> HomeLibraryRail("Film preferiti", "I titoli che hai salvato", Icons.Default.Star, IosPurple, catalog?.movies.orEmpty().filter { it.id in favorites.movies }, favorites.movies, onPlay, onNavigate, isFavoriteRail = true)
                "favoriteSeries" -> HomeLibraryRail("Serie TV preferite", "Le serie che hai salvato", Icons.Default.Star, IosBlue, catalog?.series.orEmpty().filter { it.id in favorites.series }, favorites.series, onPlay, onNavigate, isFavoriteRail = true)
                "guidaTV" -> {
                    IosGlassCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.CalendarMonth, null, tint = IosGreen, modifier = Modifier.size(26.dp)) }
                            Column(Modifier.weight(1f)) {
                                Text("Guida TV", fontWeight = FontWeight.SemiBold)
                                Text("Programmi attuali, prossimi e catch-up", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                            }
                            IosGlassIconButton(Icons.Default.ChevronRight, "Apri Guida TV", onClick = { onNavigate("epg") })
                        }
                    }
                }
                "onDemand" -> HomeLibraryRail("On demand", "Film e contenuti VOD", Icons.Default.Movie, IosPurple, catalog?.movies.orEmpty(), favorites.movies, onPlay, onNavigate)
                "trendingMovies", "trendingSeries", "categories" -> Unit
                else -> Unit
            }
        }
        if (sources.isEmpty()) {
            item {
                IosEmptyState("Nessuna sorgente configurata", "Aggiungi una playlist Xtream o M3U per iniziare.", Icons.Default.SettingsInputAntenna, "Aggiungi playlist") { onNavigate("sources") }
            }
        }
    }
}

private val CatalogState.allItems: List<MediaItem> get() = live + movies + series + episodes

@Composable
private fun RowScope.HomeAction(title: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    PlayerGlassButton(modifier = Modifier.weight(1f).height(48.dp), onClick = onClick) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(title, fontSize = 12.sp)
    }
}

@Composable
private fun HomeSourceCard(sources: List<MediaSourceConfig>, onAdd: () -> Unit) {
    val active = sources.firstOrNull { it.isEnabled }
    IosGlassCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.SettingsInputAntenna, null, tint = active?.let { sourceTint(it.type) } ?: IosBlue, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (active != null) "Sorgente attiva" else "Nessuna sorgente attiva", fontWeight = FontWeight.SemiBold)
                Text(active?.name ?: "Aggiungi la tua playlist IPTV", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
            }
            if (active == null) IosGlassPrimaryButton("Aggiungi", Icons.Default.Add, modifier = Modifier.width(160.dp), onClick = onAdd)
        }
    }
}

@Composable
private fun HomeSourcesSummary(sources: List<MediaSourceConfig>, onOpen: () -> Unit) {
    IosGlassCard {
        IosSectionHeader("Sorgenti", "Gestisci playlist, verifica connessione e seleziona la sorgente attiva")
        Spacer(Modifier.height(8.dp))
        if (sources.isEmpty()) Text("Nessuna sorgente", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
        sources.take(4).forEachIndexed { index, source ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) { androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawCircle(sourceTint(source.type)) } }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(source.name, fontWeight = FontWeight.Medium); Text(source.type.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.54f)) }
                if (source.isEnabled) Text("Attiva", color = IosGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp)); IosGlassPrimaryButton("Gestisci sorgenti", Icons.Default.SettingsInputAntenna, onClick = onOpen)
    }
}

@Composable
private fun HomeLibraryRail(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    items: List<MediaItem>,
    favoriteIds: Set<String>,
    onPlay: (MediaItem) -> Unit,
    onNavigate: (String) -> Unit,
    isFavoriteRail: Boolean = false
) {
    if (items.isEmpty() && isFavoriteRail) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp); Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)) }
            IosGlassIconButton(Icons.Default.ChevronRight, "Apri $title", onClick = { onNavigate(when (title) { "Live TV", "Canali preferiti" -> "live"; "Film preferiti", "On demand" -> "movies"; "Serie TV preferite" -> "series"; else -> "home" }) }, size = 38)
        }
        if (items.isEmpty()) IosEmptyState("Nessun contenuto", "Quando il catalogo sarà pronto vedrai qui i tuoi elementi.", icon)
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(items.take(18), key = { it.id }) { item -> IosMediaCard(item, onClick = { onPlay(item) }) } }
    }
}

private fun formatPosition(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
