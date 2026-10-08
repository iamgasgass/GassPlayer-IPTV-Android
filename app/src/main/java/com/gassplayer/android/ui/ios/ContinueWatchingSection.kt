package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.data.CatalogState
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.WatchEntry

@Composable
fun IosContinueWatchingSection(watch: List<WatchEntry>, catalog: CatalogState?, kindFilter: MediaKind? = null, onPlay: (MediaItem) -> Unit) {
    val entries = watch.filter { kindFilter == null || it.kind == kindFilter }.take(30)
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IosSectionHeader("Continua a guardare", "Riprendi dalla posizione salvata")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.contentId }) { entry ->
                val item = catalog?.allItems2.orEmpty().firstOrNull { it.id == entry.contentId }
                if (item != null) IosMediaCard(item, onClick = { onPlay(item) })
                else IosGlassCard(modifier = Modifier.width(210.dp)) { Icon(Icons.Default.PlayCircle, null); Text(entry.title, fontSize = 13.sp); Text(formatMillis(entry.positionMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) }
            }
        }
    }
}

private val CatalogState.allItems2: List<MediaItem> get() = live + movies + series + episodes
private fun formatMillis(v: Long): String { val s = v / 1000; return "%02d:%02d".format(s / 60, s % 60) }
