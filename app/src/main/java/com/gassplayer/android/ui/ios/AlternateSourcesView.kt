package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind

/**
 * Android counterpart of the iOS AlternateSourcesView.
 * It deliberately uses the already-loaded multi-source catalog so selecting
 * an alternate never creates a second network/search implementation just for
 * this sheet.
 */
@Composable
fun IosAlternateSourcesDialog(
    title: String,
    kind: MediaKind,
    excludingSourceId: String,
    allItems: List<MediaItem>,
    onDismiss: () -> Unit,
    onPick: (MediaItem) -> Unit
) {
    val all = allItems
    val results = remember(all, title, kind, excludingSourceId) {
        all.filter {
            it.kind == kind &&
                it.sourceId != excludingSourceId &&
                it.title.equals(title, ignoreCase = true)
        }.distinctBy { "${it.sourceId}|${it.id}" }
    }

    IosDialogFrame("Altre fonti", onDismiss) {
        when {
            results.isEmpty() -> {
                IosEmptyState(
                    title = "Nessun'altra fonte trovata",
                    message = "\"$title\" non è disponibile sulle altre sorgenti configurate.",
                    icon = Icons.Default.Dns
                )
            }
            else -> {
                LazyColumn(
                    Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(results, key = { "${it.sourceId}|${it.id}" }) { result ->
                        IosGlassRow(
                            icon = when (result.kind) {
                                MediaKind.MOVIE -> Icons.Default.Movie
                                MediaKind.SERIES -> Icons.Default.Tv
                                else -> Icons.Default.PlayArrow
                            },
                            title = result.title,
                            subtitle = result.sourceId,
                            tint = when (result.kind) {
                                MediaKind.MOVIE -> IosPurple
                                MediaKind.SERIES -> IosBlue
                                else -> IosGreen
                            },
                            onClick = { onPick(result) }
                        )
                    }
                }
            }
        }
    }
}
