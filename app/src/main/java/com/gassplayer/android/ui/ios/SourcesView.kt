package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.MediaSourceConfig
import com.gassplayer.android.data.SourceType
import com.gassplayer.android.ui.MainViewModel
import com.gassplayer.android.ui.PlayerGlassButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.UUID

@Composable
fun IosSourcesDialog(app: GassPlayerApplication, vm: MainViewModel, onDismiss: () -> Unit, onNavigate: (String) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MediaSourceConfig?>(null) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Personalizzato") }
    var message by remember { mutableStateOf<String?>(null) }
    var showMerge by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    val sources by app.sources.sources.collectAsState(initial = emptyList())
    val active by app.sources.activeSource.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val displayed = remember(sources, search, sort) {
        val filtered = sources.filter { search.isBlank() || it.name.contains(search, true) || it.host.contains(search, true) || it.type.name.contains(search, true) }
        val sorted = when (sort) { "Nome (A-Z)" -> filtered.sortedBy { it.name.lowercase() }; "Tipo" -> filtered.sortedWith(compareBy({ it.type.name }, { it.name.lowercase() })); else -> filtered }
        sorted.sortedByDescending { it.isPinned }
    }
    IosDialogFrame("Sorgenti", onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IosGlassPrimaryButton("Aggiungi playlist", Icons.Default.Add, modifier = Modifier.weight(1f), onClick = { showAdd = true })
            IosGlassIconButton(Icons.Default.Refresh, "Verifica tutte", onClick = { scope.launch { sources.filter { it.type == SourceType.XTREAM }.forEach { src -> runCatching { app.sources.verify(src) }.onSuccess { count -> app.sources.addOrUpdate(src.copy(lastVerifiedAt = System.currentTimeMillis(), lastVerificationSucceeded = true, lastKnownChannelCount = count)) }.onFailure { app.sources.addOrUpdate(src.copy(lastVerifiedAt = System.currentTimeMillis(), lastVerificationSucceeded = false)) } }; message = "Verifica completata" } }, size = 44)
            IosGlassIconButton(Icons.Default.Merge, "Unifica playlist", onClick = { showMerge = true }, size = 44)
        }
        IosTextField(search, { search = it }, "Cerca sorgenti")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Personalizzato", "Nome (A-Z)", "Tipo").forEach { option -> IosChip(option, sort == option) { sort = option } }
        }
        if (displayed.isEmpty()) {
            IosEmptyState("Nessuna sorgente", "Aggiungi una playlist per iniziare.", Icons.Default.SettingsInputAntenna, "Aggiungi playlist") { showAdd = true }
        } else {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(displayed, key = { it.id }) { source ->
                    IosSourceRow(source, source.id == active,
                        onActivate = { scope.launch { app.sources.setActive(source.id); message = "\"${source.name}\" è ora attiva" } },
                        onEdit = { editing = source },
                        onDuplicate = { scope.launch { app.sources.duplicate(source.id) } },
                        onDelete = { scope.launch { app.sources.delete(source.id); if (active == source.id) app.sources.setActive(sources.firstOrNull { it.id != source.id }?.id) } },
                        onPin = { scope.launch { app.sources.pin(source.id, !source.isPinned) } },
                        onToggle = { scope.launch { app.sources.toggle(source.id, !source.isEnabled) } },
                        onVerify = { scope.launch { val r = app.sources.verify(source); r.onSuccess { count -> app.sources.addOrUpdate(source.copy(lastVerifiedAt = System.currentTimeMillis(), lastVerificationSucceeded = true, lastKnownChannelCount = count)); message = "Connessione verificata: $count elementi" }.onFailure { app.sources.addOrUpdate(source.copy(lastVerifiedAt = System.currentTimeMillis(), lastVerificationSucceeded = false)); message = it.message ?: "Verifica non riuscita" } } }
                    )
                }
            }
        }
        message?.let { Text(it, fontSize = 12.sp, color = if (it.contains("non", true) || it.contains("riusc", true)) IosRed else IosGreen) }
        IosGlassPrimaryButton("Gestisci contenuti della sorgente", Icons.Default.FolderSpecial, onClick = { onNavigate("source-manager") })
        IosGlassPrimaryButton("Fonti EPG", Icons.Default.CalendarMonth, onClick = { onNavigate("epg-manage") })
        IosGlassPrimaryButton("Backup / importazione", Icons.Default.ImportExport, onClick = { showImport = true })
    }

    if (showAdd) IosAddPlaylistDialog(app, onDismiss = { showAdd = false })
    if (editing != null) IosEditSourceDialog(app, editing!!, onDismiss = { editing = null })
    if (showMerge) IosMergePlaylistDialog(app, onDismiss = { showMerge = false })
    if (showImport) IosSourceImportDialog(app, onDismiss = { showImport = false })
}

@Composable
private fun IosSourceRow(source: MediaSourceConfig, active: Boolean, onActivate: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, onPin: () -> Unit, onToggle: () -> Unit, onVerify: () -> Unit) {
    IosGlassCard(padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.SettingsInputAntenna, null, tint = sourceTint(source.type), modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(source.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold); if (active) { Spacer(Modifier.width(7.dp)); Text("ATTIVA", color = IosGreen, fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) } }
                Text(source.host, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(source.type.name, fontSize = 10.sp, color = sourceTint(source.type))
                    Text(if (source.isEnabled) "Abilitata" else "Disabilitata", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.54f))
                    if (source.lastVerifiedAt != null) Text(if (source.lastVerificationSucceeded) "Verificata" else "Errore verifica", fontSize = 10.sp, color = if (source.lastVerificationSucceeded) IosGreen else IosRed)
                }
            }
            IosGlassIconButton(if (active) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, if (active) "Sorgente attiva" else "Attiva", onClick = onActivate, size = 38)
            IosGlassIconButton(Icons.Default.MoreVert, "Azioni", onClick = { onEdit() }, size = 38)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionPill("Verifica", Icons.Default.Verified, onVerify)
            ActionPill(if (source.isPinned) "Sblissa" else "Fissa", if (source.isPinned) Icons.Default.PushPin else Icons.Default.PushPin, onPin)
            ActionPill(if (source.isEnabled) "Disabilita" else "Abilita", if (source.isEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility, onToggle)
            ActionPill("Modifica", Icons.Default.Edit, onEdit)
            ActionPill("Duplica", Icons.Default.ContentCopy, onDuplicate)
            ActionPill("Elimina", Icons.Default.Delete, onDelete, destructive = true)
        }
    }
}

@Composable
private fun ActionPill(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, destructive: Boolean = false) {
    PlayerGlassButton(modifier = Modifier.height(36.dp), onClick = onClick) { Icon(icon, null, tint = if (destructive) IosRed else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)); Text(title, fontSize = 10.sp) }
}

@Composable
fun IosAddPlaylistDialog(app: GassPlayerApplication, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf<SourceType?>(null) }
    var expanded by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canSave = type != null && name.isNotBlank() && host.isNotBlank() && (type == SourceType.M3U8 || (username.isNotBlank() && password.isNotBlank()))
    IosDialogFrame("Aggiungi playlist", onDismiss) {
        IosGlassCard(padding = PaddingValues(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Seleziona il tipo di playlist", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (expanded) IosGlassIconButton(Icons.Default.ExpandLess, "Chiudi tipi", onClick = { expanded = false }, size = 38)
                else IosGlassIconButton(Icons.Default.ExpandMore, "Apri tipi", onClick = { expanded = true }, size = 38)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                listOf(SourceType.M3U8, SourceType.XTREAM, SourceType.PLEX, SourceType.JELLYFIN, SourceType.EMBY).forEach { candidate ->
                    Surface(onClick = { type = candidate }, color = if (type == candidate) sourceTint(candidate).copy(alpha = 0.13f) else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlaylistPlay, null, tint = sourceTint(candidate)); Spacer(Modifier.width(10.dp)); Text(candidateName(candidate), modifier = Modifier.weight(1f)); if (type == candidate) Icon(Icons.Default.Check, null, tint = sourceTint(candidate))
                        }
                    }
                }
            } else if (type != null) Text(candidateName(type!!), color = sourceTint(type!!), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
        }
        if (type != null) {
            IosTextField(name, { name = it }, "Nome playlist")
            IosTextField(host, { host = it }, when (type) { SourceType.M3U8 -> "URL playlist M3U"; SourceType.XTREAM -> "URL server Xtream"; SourceType.PLEX -> "URL server Plex"; SourceType.JELLYFIN -> "URL server Jellyfin"; SourceType.EMBY -> "URL server Emby"; else -> "URL server" })
            if (type != SourceType.M3U8) {
                IosTextField(username, { username = it }, "Username")
                IosTextField(password, { password = it }, "Password", isPassword = true)
            }
            IosGlassPrimaryButton("Salva", Icons.Default.Check, enabled = canSave && !saving) {
                scope.launch {
                    saving = true
                    val source = MediaSourceConfig(UUID.randomUUID().toString(), name.trim(), type!!, host.trim(), username.trim().takeIf { type != SourceType.M3U8 }, password.takeIf { type != SourceType.M3U8 }, playlistUrl = host.trim().takeIf { type == SourceType.M3U8 })
                    app.sources.addOrUpdate(source)
                    if (app.sources.activeSource.first() == null) app.sources.setActive(source.id)
                    saving = false
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun IosEditSourceDialog(app: GassPlayerApplication, source: MediaSourceConfig, onDismiss: () -> Unit) {
    var name by remember(source) { mutableStateOf(source.name) }
    var host by remember(source) { mutableStateOf(source.host) }
    var username by remember(source) { mutableStateOf(source.username.orEmpty()) }
    var password by remember(source) { mutableStateOf(source.password.orEmpty()) }
    val scope = rememberCoroutineScope()
    IosDialogFrame("Modifica dettagli", onDismiss) {
        IosTextField(name, { name = it }, "Nome")
        IosTextField(host, { host = it }, "URL")
        if (source.type != SourceType.M3U8) { IosTextField(username, { username = it }, "Username"); IosTextField(password, { password = it }, "Password", isPassword = true) }
        IosGlassPrimaryButton("Salva", Icons.Default.Check, onClick = { scope.launch { app.sources.addOrUpdate(source.copy(name = name.trim().ifBlank { source.name }, host = host.trim().ifBlank { source.host }, username = username.takeIf { source.type != SourceType.M3U8 }, password = password.takeIf { source.type != SourceType.M3U8 }, playlistUrl = host.trim().takeIf { source.type == SourceType.M3U8 })); onDismiss() } })
    }
}

@Composable
private fun IosMergePlaylistDialog(app: GassPlayerApplication, onDismiss: () -> Unit) {
    val sources by app.sources.sources.collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    IosDialogFrame("Unifica playlist", onDismiss) {
        IosTextField(name, { name = it }, "Nome raccolta")
        LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sources, key = { it.id }) { source ->
                IosGlassRow(Icons.Default.SettingsInputAntenna, source.name, source.host, sourceTint(source.type), showChevron = false, trailing = { Checkbox(selected.contains(source.id), { selected = if (it) selected + source.id else selected - source.id }) })
            }
        }
        IosGlassPrimaryButton("Crea playlist unificata", Icons.Default.Merge, enabled = name.isNotBlank() && selected.isNotEmpty()) { scope.launch { app.mergedPlaylists.create(name, selected.toList()); onDismiss() } }
    }
}

@Composable
private fun IosSourceImportDialog(app: GassPlayerApplication, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    IosDialogFrame("Backup sorgenti", onDismiss) {
        IosTextField(text, { text = it }, "JSON backup", singleLine = false)
        IosGlassPrimaryButton("Importa", Icons.Default.FileDownload, enabled = text.isNotBlank()) { scope.launch { runCatching { app.backup.importAny(text) }.onSuccess { status = "Import completato" }.onFailure { status = it.message ?: "Import non riuscito" } } }
        IosGlassPrimaryButton("Esporta", Icons.Default.FileUpload, onClick = { scope.launch { status = app.backup.fullExport() } })
        status?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 5) }
    }
}

private fun candidateName(type: SourceType) = when (type) { SourceType.XTREAM -> "Xtream Codes"; SourceType.M3U8 -> "M3U / M3U8"; SourceType.PLEX -> "Plex"; SourceType.JELLYFIN -> "Jellyfin"; SourceType.EMBY -> "Emby" }
