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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.CatalogState
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.ui.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun IosGlobalSearchView(app: GassPlayerApplication, vm: MainViewModel, catalog: CatalogState?, onPlay: (MediaItem) -> Unit, onRoute: (String) -> Unit, onOpenDetail: (MediaItem) -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Tutti") }
    var searching by remember { mutableStateOf(false) }
    val history by app.search.flow.collectAsState(initial = com.gassplayer.android.data.SearchHistory())
    val scope = rememberCoroutineScope()
    val items = remember(catalog, query, filter) {
        val all = catalog?.allItems.orEmpty()
        val kindFiltered = when (filter) { "Live TV" -> all.filter { it.kind == MediaKind.LIVE }; "Film" -> all.filter { it.kind == MediaKind.MOVIE }; "Serie TV" -> all.filter { it.kind == MediaKind.SERIES }; else -> all }
        if (query.isBlank()) emptyList() else kindFiltered.filter { it.title.contains(query, true) || it.genre.orEmpty().contains(query, true) || it.cast.orEmpty().contains(query, true) }.take(200)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IosTextField(query, { query = it }, "Cerca film, serie TV o canali", modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("Tutti", "Live TV", "Film", "Serie TV").forEach { IosChip(it, filter == it) { filter = it } } }
        if (query.isBlank()) {
            IosGlassCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = IosBlue)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text("Ricerca globale", fontWeight = FontWeight.SemiBold); Text("Tocca una ricerca recente oppure digita un titolo.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)) }
                }
                if (history.terms.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Recenti", fontWeight = FontWeight.SemiBold)
                    LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(history.terms, key = { it }) { term ->
                            IosGlassRow(Icons.Default.History, term, onClick = { query = term })
                        }
                    }
                    IosGlassPrimaryButton("Cancella cronologia", Icons.Default.DeleteSweep, onClick = { scope.launch { app.search.clear() } })
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (searching) "Cerco…" else "${items.size} risultati", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), modifier = Modifier.weight(1f))
                IosGlassIconButton(Icons.Default.Clear, "Svuota", onClick = { query = "" }, size = 38)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                items(items, key = { it.id }) { item ->
                    IosSearchResultRow(item, onClick = {
                        searching = true
                        scope.launch { app.search.add(query); searching = false }
                        when (item.kind) { MediaKind.MOVIE, MediaKind.SERIES -> onOpenDetail(item); else -> onPlay(item) }
                    })
                }
            }
        }
    }
}

@Composable
private fun IosSearchResultRow(item: MediaItem, onClick: () -> Unit) {
    IosGlassCard(padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(if (item.kind == MediaKind.LIVE) 58.dp else 48.dp).height(if (item.kind == MediaKind.LIVE) 42.dp else 66.dp)) {
                    coil3.compose.AsyncImage(model = item.posterUrl ?: item.logoUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = if (item.kind == MediaKind.LIVE) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(kindLabel(item.kind) + (item.group?.let { " · $it" } ?: ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), maxLines = 1)
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f))
            }
        }
    }
}

private val CatalogState.allItems: List<MediaItem> get() = live + movies + series + episodes
private fun kindLabel(kind: MediaKind) = when (kind) { MediaKind.LIVE -> "Live TV"; MediaKind.MOVIE -> "Film"; MediaKind.SERIES -> "Serie TV"; MediaKind.EPISODE -> "Episodio" }
