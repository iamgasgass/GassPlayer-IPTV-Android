package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.Category
import com.gassplayer.android.data.FavoriteState
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.ParentalState
import com.gassplayer.android.ui.MainViewModel
import com.gassplayer.android.ui.PlayerGlassButton

@Composable
fun IosChannelGridView(
    app: GassPlayerApplication,
    vm: MainViewModel,
    items: List<MediaItem>,
    categories: List<Category>,
    favorites: FavoriteState,
    parental: ParentalState,
    kind: MediaKind,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: ((MediaItem) -> Unit)? = null
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var selectedGroup by remember(kind, items) { mutableStateOf("Tutti") }
    var search by remember { mutableStateOf("") }
    val counts = remember(items, categories) {
        mapOf("Tutti" to items.size) + categories.associate { category -> category.name to items.count { it.categoryId == category.id || it.group == category.name } }
    }
    val filtered = remember(items, selectedGroup, search) {
        items.asSequence()
            .filter { search.isBlank() || it.title.contains(search, ignoreCase = true) }
            .filter { selectedGroup == "Tutti" || it.group.equals(selectedGroup, true) || categories.firstOrNull { c -> c.name == selectedGroup }?.id == it.categoryId }
            .toList()
    }

    IosBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IosSectionHeader(
                    when (kind) { MediaKind.LIVE -> "Live TV"; MediaKind.MOVIE -> "Film"; MediaKind.SERIES -> "Serie TV"; else -> "Libreria" },
                    "${filtered.size} elementi · UI gruppi ${if (settings.groupUIStyle == "espansibile") "espansibile" else "scorrevole"}",
                    Modifier.weight(1f)
                )
                IosGlassIconButton(Icons.Default.Refresh, "Ricarica", onClick = { vm.refresh(true) }, size = 40)
            }
            Spacer(Modifier.height(8.dp))
            IosTextField(search, { search = it }, "Cerca", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            if (settings.groupUIStyle == "espansibile") IosGroupSelector(categories.map { it.name }, selectedGroup, { selectedGroup = it }, expandable = true, counts = counts)
            else androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Tutti") + categories.map { it.name }.distinct()) { group -> IosChip(group, selectedGroup == group, onClick = { selectedGroup = group }) }
            }
            Spacer(Modifier.height(10.dp))
            if (filtered.isEmpty()) {
                IosEmptyState("Nessun contenuto", if (search.isBlank()) "Questa categoria non contiene elementi." else "Nessun risultato per \"$search\".", iconFor(kind))
            } else {
                val columns = when (settings.density) { "compact" -> 7; "poster" -> 4; else -> 5 }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 34.dp)
                ) {
                    items(filtered, key = { it.id }) { item ->
                        IosLibraryTile(item, kind, item.id in favoriteIds(favorites, kind), item.id in parental.lockedIds, onPlay = { if (onOpenDetail != null && kind != MediaKind.LIVE) onOpenDetail(item) else onPlay(item) }, onFavorite = { vm.toggleFavorite(item) }, onLock = { vm.toggleLock(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun IosLibraryTile(item: MediaItem, kind: MediaKind, isFavorite: Boolean, locked: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onLock: () -> Unit) {
    Surface(onClick = onPlay, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.54f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)), shadowElevation = 5.dp) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(if (kind == MediaKind.LIVE) 1.55f else 0.68f)) {
                coil3.compose.AsyncImage(model = item.posterUrl ?: item.logoUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = if (kind == MediaKind.LIVE) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
                if (locked) {
                    Surface(color = Color.Black.copy(alpha = 0.58f), modifier = Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(30.dp)) } }
                }
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (isFavorite) TinyGlassIcon(Icons.Default.Star, "Preferito", IosOrange)
                    if (item.rating != null) TinyBadge("%.1f".format(item.rating))
                }
                if (kind == MediaKind.LIVE && item.number != null) TinyBadge("CH ${item.number}", Modifier.align(Alignment.BottomStart).padding(6.dp))
                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { IosGlassIconButton(if (locked) Icons.Default.LockOpen else Icons.Default.Lock, if (locked) "Sblocca" else "Blocca", onClick = onLock, size = 30) }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(item.group ?: kindLabel(kind), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { IosGlassIconButton(if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder, if (isFavorite) "Rimuovi preferito" else "Aggiungi preferito", onClick = onFavorite, size = 34) }
            }
        }
    }
}

@Composable
private fun TinyGlassIcon(icon: ImageVector, contentDescription: String, tint: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.64f)) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.padding(5.dp).size(13.dp)) }
}

@Composable
private fun TinyBadge(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.66f)) { Text(text, color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) }
}

private fun favoriteIds(state: FavoriteState, kind: MediaKind) = when (kind) { MediaKind.LIVE -> state.live; MediaKind.MOVIE, MediaKind.EPISODE -> state.movies; MediaKind.SERIES -> state.series }
private fun kindLabel(kind: MediaKind) = when (kind) { MediaKind.LIVE -> "Live TV"; MediaKind.MOVIE -> "Film"; MediaKind.SERIES -> "Serie TV"; else -> "Episodio" }
private fun iconFor(kind: MediaKind) = when (kind) { MediaKind.LIVE -> Icons.Default.LiveTv; MediaKind.MOVIE -> Icons.Default.Movie; MediaKind.SERIES -> Icons.Default.Tv; else -> Icons.Default.PlayArrow }
