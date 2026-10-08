package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.FavoriteState
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.ui.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun IosSeriesEpisodesView(app: GassPlayerApplication, vm: MainViewModel, series: MediaItem, episodes: List<MediaItem>, allItems: List<MediaItem>, favorites: FavoriteState, onDismiss: () -> Unit, onPlay: (MediaItem) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    var currentEpisodes by remember(series.id) { mutableStateOf(episodes.filter { it.seriesId == series.id || it.seriesId == series.id.substringAfterLast(':') }) }
    var selectedSeason by remember(series.id) { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var meta by remember(series.id) { mutableStateOf<com.gassplayer.android.data.MetadataResult?>(null) }
    val scope = rememberCoroutineScope()
    var showAlternateSources by remember { mutableStateOf(false) }
    LaunchedEffect(series.id, settings.tmdbApiKey) { if (settings.tmdbApiKey.isNotBlank()) meta = runCatching { app.tmdb.search(series.title, settings.tmdbApiKey).firstOrNull() }.getOrNull() }
    LaunchedEffect(series.id) {
        if (currentEpisodes.isEmpty()) {
            loading = true
            error = null
            runCatching { app.catalog.loadSeriesEpisodes(series.sourceId, series.id.substringAfterLast(':'), series.title) }
                .onSuccess { currentEpisodes = it }
                .onFailure { error = it.message }
            loading = false
        }
    }
    val seasons = currentEpisodes.groupBy { it.seasonNumber ?: 0 }.toSortedMap()
    if (selectedSeason !in seasons.keys) selectedSeason = seasons.keys.firstOrNull() ?: 0
    IosDialogFrame("Serie TV", onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.width(190.dp).height(270.dp)) { coil3.compose.AsyncImage(model = meta?.posterUrl ?: series.posterUrl, contentDescription = series.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(meta?.title ?: series.title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(meta?.overview ?: series.plot.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), maxLines = 7)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { seasons.keys.forEach { season -> IosChip("S$season", selectedSeason == season) { selectedSeason = season } } }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = IosRed, fontSize = 11.sp) }
                IosGlassPrimaryButton("Riprendi / Riproduci", Icons.Default.PlayArrow, enabled = currentEpisodes.isNotEmpty(), onClick = { currentEpisodes.firstOrNull { ep -> watch.firstOrNull { w -> w.contentId == ep.id }?.positionMs ?: 0L > 0 }?.let { onPlay(it) } ?: currentEpisodes.firstOrNull()?.let(onPlay) })
                IosGlassIconButton(Icons.Default.Dns, "Altre fonti", onClick = { showAlternateSources = true })
                IosGlassIconButton(if (series.id in favorites.series) Icons.Default.Star else Icons.Default.StarBorder, "Preferito", onClick = { scope.launch { app.favorites.toggle(series) } })
            }
        }
        Spacer(Modifier.height(8.dp))
        IosSectionHeader("Episodi", "Stagione $selectedSeason · ${seasons[selectedSeason].orEmpty().size} episodi")
        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(seasons[selectedSeason].orEmpty(), key = { it.id }) { episode ->
                val position = watch.firstOrNull { it.contentId == episode.id }?.positionMs ?: 0L
                IosGlassCard(padding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        coil3.compose.AsyncImage(model = episode.posterUrl, contentDescription = episode.title, modifier = Modifier.width(74.dp).height(46.dp), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text("E${episode.episodeNumber ?: 0} · ${episode.title}", fontWeight = FontWeight.SemiBold); Text(episode.plot.orEmpty(), maxLines = 2, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                            if (position > 0 && episode.durationSec != null) LinearProgressIndicator(((position.toFloat() / 1000f) / episode.durationSec.toFloat()).coerceIn(0f, 1f), Modifier.fillMaxWidth().padding(top = 4.dp)) }
                        IosGlassIconButton(Icons.Default.PlayArrow, "Riproduci episodio", onClick = { onPlay(episode) }, size = 38)
                        IosGlassIconButton(Icons.Default.Download, "Download", onClick = { app.downloads.enqueue(episode, settings.downloadWifiOnly) }, size = 38)
                    }
                }
            }
        }
    }
    if (showAlternateSources) {
        IosAlternateSourcesDialog(
            title = series.title,
            kind = series.kind,
            excludingSourceId = series.sourceId,
            allItems = allItems,
            onDismiss = { showAlternateSources = false },
            onPick = { onPlay(it); showAlternateSources = false }
        )
    }

}
