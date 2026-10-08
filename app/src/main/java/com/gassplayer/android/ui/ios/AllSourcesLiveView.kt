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
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.MediaSourceConfig
import com.gassplayer.android.ui.MainViewModel

@Composable
fun IosAllSourcesLiveView(app: GassPlayerApplication, sources: List<MediaSourceConfig>, vm: MainViewModel, onPlay: (com.gassplayer.android.data.MediaItem) -> Unit) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val live = catalog?.live.orEmpty().filter { it.kind == MediaKind.LIVE }
    IosScreen("Tutte le sorgenti Live") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(sources, key = { it.id }) { source ->
                val items = live.filter { it.sourceId == source.id }
                IosGlassCard { IosGlassRow(Icons.Default.LiveTv, source.name, "${items.size} canali", sourceTint(source.type), showChevron = false) }
                items.take(5).forEach { media -> IosGlassRow(Icons.Default.PlayArrow, media.title, source.name, IosRed, onClick = { onPlay(media) }) }
            }
        }
    }
}

@Composable
fun IosAlternateSourcesView(item: com.gassplayer.android.data.MediaItem, allItems: List<com.gassplayer.android.data.MediaItem>, onPlay: (com.gassplayer.android.data.MediaItem) -> Unit) {
    IosGlassCard { IosSectionHeader("Altre fonti", "Stesso titolo presente su altre playlist")
        val alternates = allItems.filter { it.id != item.id && it.kind == item.kind && it.title.equals(item.title, true) }
        alternates.forEach { alt -> IosGlassRow(Icons.Default.PlayArrow, alt.title, alt.sourceId, IosBlue, onClick = { onPlay(alt) }) }
        if (alternates.isEmpty()) Text("Nessuna fonte alternativa", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
    }
}
