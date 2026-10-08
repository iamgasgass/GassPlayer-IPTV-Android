package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
fun IosMovieDetailView(app: GassPlayerApplication, vm: MainViewModel, item: MediaItem, allItems: List<MediaItem>, favorites: FavoriteState, onDismiss: () -> Unit, onPlay: (MediaItem) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    val current = watch.firstOrNull { it.contentId == item.id }
    var meta by remember(item.id) { mutableStateOf<com.gassplayer.android.data.MetadataResult?>(null) }
    var ratings by remember(item.id) { mutableStateOf<com.gassplayer.android.data.Ratings?>(null) }
    var trakt by remember(item.id) { mutableStateOf<Double?>(null) }
    val scope = rememberCoroutineScope()
    var showAlternateSources by remember { mutableStateOf(false) }
    LaunchedEffect(item.id, settings.tmdbApiKey, settings.omdbApiKey, settings.traktClientId) {
        meta = if (settings.tmdbApiKey.isNotBlank()) runCatching { if (!item.tmdbId.isNullOrBlank()) app.tmdb.details(item.tmdbId, settings.tmdbApiKey, false) else app.tmdb.search(item.title, settings.tmdbApiKey).firstOrNull() }.getOrNull() else null
        ratings = runCatching { app.omdb.lookup(item.title, settings.omdbApiKey) }.getOrNull()
        trakt = runCatching { app.trakt.ratings(item.title, settings.traktClientId) }.getOrNull()
    }
    IosDialogFrame("Dettaglio film", onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.width(210.dp).height(310.dp)) { coil3.compose.AsyncImage(model = meta?.posterUrl ?: item.posterUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(meta?.title ?: item.title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(meta?.overview ?: item.plot.orEmpty(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f), maxLines = 8)
                if (!meta?.genres.isNullOrEmpty()) Text(meta!!.genres.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                Text("Rating ${meta?.rating ?: item.rating ?: "—"} · IMDb ${ratings?.imdb ?: "—"} · RT ${ratings?.rottenTomatoes ?: "—"} · Trakt ${trakt ?: "—"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IosGlassPrimaryButton(if ((current?.positionMs ?: 0L) > 0) "Riprendi" else "Riproduci", Icons.Default.PlayArrow, modifier = Modifier.weight(1f)) { onPlay(item) }
                    IosGlassIconButton(Icons.Default.Dns, "Altre fonti", onClick = { showAlternateSources = true })
                    IosGlassIconButton(if (item.id in favorites.movies) Icons.Default.Star else Icons.Default.StarBorder, "Preferito", onClick = { scope.launch { app.favorites.toggle(item) } })
                    IosGlassIconButton(Icons.Default.Download, "Download", onClick = { app.downloads.enqueue(item, settings.downloadWifiOnly) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        IosSectionHeader("Altre fonti", "Versioni e sorgenti alternative dello stesso titolo")
        val alternates = allItems.filter { it.id != item.id && it.kind == item.kind && it.title.equals(item.title, true) }
        if (alternates.isEmpty()) Text("Nessuna sorgente alternativa rilevata.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f))
        else LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { items(alternates) { alt -> IosGlassRow(Icons.Default.PlayArrow, alt.title, alt.sourceId, sourceTint(when (alt.kind) { com.gassplayer.android.data.MediaKind.MOVIE -> com.gassplayer.android.data.SourceType.XTREAM; else -> com.gassplayer.android.data.SourceType.M3U8 }), onClick = { onPlay(alt) }) } }
    }
    if (showAlternateSources) {
        IosAlternateSourcesDialog(
            title = item.title,
            kind = item.kind,
            excludingSourceId = item.sourceId,
            allItems = allItems,
            onDismiss = { showAlternateSources = false },
            onPick = { onPlay(it); showAlternateSources = false }
        )
    }
}
