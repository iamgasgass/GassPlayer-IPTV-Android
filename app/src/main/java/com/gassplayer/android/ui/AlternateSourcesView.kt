package com.gassplayer.android.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.MediaSourceConfig
import com.gassplayer.android.data.SourceType
import com.gassplayer.android.data.excludingSource
import com.gassplayer.android.data.fromSourceIds
import com.gassplayer.android.data.matchingText

/**
 * Android port of iOS AlternateSourcesView. The active catalog is disk-backed and
 * already contains all enabled sources, so searching stays local and does not
 * open parallel network requests against every provider whenever the dialog opens.
 * Selecting a movie starts that source; selecting a series opens its own details.
 */
@Composable
internal fun AlternateSourcesView(
    title: String,
    kind: MediaKind,
    excludingSourceId: String,
    candidates: List<MediaItem>,
    configuredSources: List<MediaSourceConfig>,
    onDismiss: () -> Unit,
    onSelect: (MediaItem) -> Unit
) {
    var query by remember(title, kind, excludingSourceId) { mutableStateOf(title) }
    val eligibleSources = remember(configuredSources, excludingSourceId) {
        configuredSources.asSequence()
            .filter { it.isEnabled && it.type == SourceType.XTREAM && it.id != excludingSourceId }
            .sortedBy { it.sortOrder }
            .toList()
    }
    val candidateSources = remember(candidates, eligibleSources) {
        candidates.fromSourceIds(eligibleSources.map { it.id }).excludingSource(excludingSourceId)
    }
    val results = remember(candidateSources, query) {
        if (query.isBlank()) emptyList() else candidateSources.matchingText(query)
    }
    val sourceNames = remember(configuredSources) { configuredSources.associate { it.id to it.name } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.88f),
            cornerRadius = 28.dp,
            contentPadding = 16.dp
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Altre fonti", color = glassForeground(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Cerca ‘$title’ sulle altre sorgenti Xtream abilitate",
                            color = glassForeground(.62f),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Chiudi", tint = glassForeground()) }
                }

                EpgStyleSearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Cerca lo stesso titolo nelle altre sorgenti"
                )

                if (eligibleSources.isEmpty()) {
                    AlternateSourcesEmptyState(
                        title = "Nessun'altra sorgente Xtream",
                        message = "Aggiungi e abilita un'altra sorgente Xtream per cercare questo contenuto."
                    )
                } else if (results.isEmpty()) {
                    AlternateSourcesEmptyState(
                        title = "Nessuna altra fonte trovata",
                        message = if (query.isBlank()) "Inserisci il titolo da cercare." else "‘${query.trim()}’ non è presente nelle altre sorgenti Xtream abilitate."
                    )
                } else {
                    Text(
                        "${results.size} ${if (results.size == 1) "risultato" else "risultati"}",
                        color = glassForeground(.56f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(results, key = { it.id }) { result ->
                            val sourceName = sourceNames[result.sourceId] ?: result.sourceId
                            Surface(
                                onClick = { onSelect(result); onDismiss() },
                                modifier = Modifier.fillMaxWidth().focusable(),
                                shape = RoundedCornerShape(16.dp),
                                color = Color.Transparent
                            ) {
                                LiquidGlassSurface(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 16.dp,
                                    contentPadding = 12.dp,
                                    highlighted = false
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (kind == MediaKind.SERIES) Icons.Default.Tv else Icons.Default.LocalMovies,
                                            contentDescription = null,
                                            tint = glassForeground(.68f),
                                            modifier = Modifier.size(23.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(result.title, color = glassForeground(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(sourceName, color = glassForeground(.62f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            result.year?.takeIf { it.isNotBlank() }?.let { Text(it, color = glassForeground(.45f), fontSize = 11.sp) }
                                        }
                                        Icon(Icons.Default.Search, contentDescription = "Apri questa sorgente", tint = glassForeground(.42f), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlternateSourcesEmptyState(title: String, message: String) {
    Box(Modifier.fillMaxWidth().height(260.dp).padding(22.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Default.Search, null, tint = glassForeground(.48f), modifier = Modifier.size(30.dp))
            Text(title, color = glassForeground(), fontWeight = FontWeight.SemiBold)
            Text(message, color = glassForeground(.62f), fontSize = 13.sp)
        }
    }
}
