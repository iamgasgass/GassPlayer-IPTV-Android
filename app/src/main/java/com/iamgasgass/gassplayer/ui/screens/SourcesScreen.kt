package com.iamgasgass.gassplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.data.MediaSource
import com.iamgasgass.gassplayer.data.SourceType
import com.iamgasgass.gassplayer.ui.AppState
import com.iamgasgass.gassplayer.ui.theme.GlassCard
import com.iamgasgass.gassplayer.ui.theme.Muted
import com.iamgasgass.gassplayer.ui.theme.Purple

@Composable
fun SourcesScreen(
    state: AppState,
    onBack: () -> Unit,
    add: (String, SourceType, String, String, String, String) -> Unit,
    select: (MediaSource) -> Unit,
    delete: (String) -> Unit,
) {
    var dialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(
            "Sorgenti",
            if (state.sources.isEmpty()) null else onBack,
        ) {
            Button(onClick = { dialog = true }) {
                Icon(Icons.Default.Add, null)
                Text(" Aggiungi")
            }
        }

        if (state.error != null) {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            ) {
                Text("Problema sorgente", fontWeight = FontWeight.Bold)
                Text(state.error, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.size(10.dp))
        }

        if (state.sources.isEmpty()) {
            EmptyState(
                "Nessuna sorgente",
                "Aggiungi un account Xtream Codes o una playlist M3U",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.sources, key = { it.id }) { source ->
                    GlassCard(
                        Modifier.fillMaxWidth(),
                        onClick = { select(source) },
                    ) {
                        Row {
                            Icon(
                                imageVector = if (source.type == SourceType.XTREAM) {
                                    Icons.Default.Dns
                                } else {
                                    Icons.Default.PlaylistPlay
                                },
                                contentDescription = null,
                                tint = Purple,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(source.name, fontWeight = FontWeight.Bold)
                                Text(
                                    "${source.type} • ${source.url}",
                                    color = Muted,
                                    maxLines = 1,
                                )
                                if (source.type == SourceType.M3U && source.epgUrl.isNotBlank()) {
                                    Text("XMLTV configurato", color = Muted)
                                }
                                if (source.id == state.selectedSource?.id) {
                                    Text("ATTIVA", color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            IconButton(onClick = { delete(source.id) }) {
                                Icon(Icons.Default.Delete, "Elimina")
                            }
                        }
                    }
                }
            }
        }
    }

    if (dialog) {
        AddSourceDialog(
            close = { dialog = false },
            done = { name, type, url, user, pass, epg ->
                add(name, type, url, user, pass, epg)
                dialog = false
            },
        )
    }
}

@Composable
private fun AddSourceDialog(
    close: () -> Unit,
    done: (String, SourceType, String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SourceType.XTREAM) }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Aggiungi sorgente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    FilterChip(
                        selected = type == SourceType.XTREAM,
                        onClick = { type = SourceType.XTREAM },
                        label = { Text("Xtream") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = type == SourceType.M3U,
                        onClick = { type = SourceType.M3U },
                        label = { Text("M3U") },
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = {
                        Text(if (type == SourceType.XTREAM) "URL server" else "URL playlist")
                    },
                    singleLine = true,
                )
                if (type == SourceType.XTREAM) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("Username") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Password") },
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = epg,
                        onValueChange = { epg = it },
                        label = { Text("URL XMLTV (facoltativo)") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { done(name, type, url, user, pass, epg) },
                enabled = url.isNotBlank() &&
                    (type == SourceType.M3U || user.isNotBlank() && pass.isNotBlank()),
            ) {
                Text("Salva e verifica")
            }
        },
        dismissButton = {
            TextButton(onClick = close) { Text("Annulla") }
        },
    )
}
