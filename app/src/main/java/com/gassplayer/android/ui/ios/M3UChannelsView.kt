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
import androidx.compose.ui.unit.dp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.ui.MainViewModel

@Composable
fun IosM3UChannelsView(app: GassPlayerApplication, vm: MainViewModel, kind: MediaKind, onPlay: (MediaItem) -> Unit) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val items = catalog?.live.orEmpty().filter { if (kind == MediaKind.LIVE) it.kind == MediaKind.LIVE else it.kind == MediaKind.MOVIE }
    var group by remember { mutableStateOf("Tutti") }
    val groups = items.mapNotNull { it.group }.distinct().sorted()
    val filtered = items.filter { group == "Tutti" || it.group == group }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IosSectionHeader(if (kind == MediaKind.LIVE) "M3U Live TV" else "M3U Film", "Playlist M3U/M3U8 con gruppi e guida")
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf("Tutti") + groups) { IosChip(it, group == it) { group = it } } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(filtered, key = { it.id }) { item ->
                IosGlassCard(padding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)) {
                    IosGlassRow(Icons.Default.PlayArrow, item.title, item.group ?: "M3U", IosBlue, onClick = { onPlay(item) })
                }
            }
        }
    }
}

@Composable
fun IosM3UGroupChannelsView(app: GassPlayerApplication, vm: MainViewModel, groupName: String, onPlay: (MediaItem) -> Unit) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val items = catalog?.live.orEmpty().filter { it.group == groupName }
    IosScreen("$groupName · ${items.size}") { items.forEach { item -> IosGlassRow(Icons.Default.PlayArrow, item.title, item.streamUrl, IosBlue, onClick = { onPlay(item) }) } }
}
