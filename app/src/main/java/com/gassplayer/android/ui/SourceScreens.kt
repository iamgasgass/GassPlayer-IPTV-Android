package com.gassplayer.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SourcesMuted = Color(0xFFB5BBC9)
private val SourcesBlue = Color(0xFF9ABEFF)
private val SourcesGreen = Color(0xFF63D995)

private data class SourceItemCounts(
    val live: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val episodes: Int = 0
) { val total: Int get() = live + movies + series + episodes }

private data class SourceFavoriteCounts(
    val live: Int = 0,
    val movies: Int = 0,
    val series: Int = 0
)

/** Android/Android TV translation of SourcesView.swift: source aggregation, sort/search,
 * merge playlists, favourites, backup/import and batch verification remain actionable. */
@Composable
fun SourcesView(app: GassPlayerApplication, vm: MainViewModel, onRoute: (String) -> Unit, onManage: (MediaSourceConfig) -> Unit) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val activeId by vm.activeSource.collectAsStateWithLifecycle()
    val fav by vm.favorites.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val merged by app.mergedPlaylists.flow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf("Personalizzato") }
    var sortExpanded by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var mergeDialog by remember { mutableStateOf(false) }
    var mergedName by remember { mutableStateOf("") }
    var selectedMergeIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importDialog by remember { mutableStateOf(false) }
    var importJson by remember { mutableStateOf("") }
    var exportJson by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var verifyingId by remember { mutableStateOf<String?>(null) }
    var verifyingAll by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<MediaSourceConfig?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<MediaSourceConfig?>(null) }
    var sourceMenuTarget by remember { mutableStateOf<MediaSourceConfig?>(null) }

    val displayedSources = remember(sources, query, sortMode) {
        val filtered = sources.filter { s -> query.isBlank() || s.name.contains(query, true) || s.host.contains(query, true) || s.type.name.contains(query, true) }
        val sorted = when (sortMode) {
            "Nome (A–Z)" -> filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
            "Tipo" -> filtered.sortedWith(compareBy<MediaSourceConfig> { it.type.name }.thenBy { it.name.lowercase(Locale.ROOT) })
            else -> filtered.sortedBy { it.sortOrder }
        }
        sorted.sortedWith(compareByDescending<MediaSourceConfig> { it.isPinned }.thenBy { sorted.indexOf(it) })
    }
    // Query the disk-backed catalog by its id index instead of scanning every playlist item on recomposition.
    val favouriteItems = remember(catalog, fav) {
        catalog?.let { state ->
            (state.byIds(MediaKind.LIVE, fav.live) + state.byIds(MediaKind.MOVIE, fav.movies) + state.byIds(MediaKind.SERIES, fav.series)).take(12)
        }.orEmpty()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SourceSectionHeader("Live TV", "Aggregazione multi-sorgente")
        SourceActionRow(
            icon = Icons.Default.Tv,
            title = "Guarda tutte le liste insieme",
            subtitle = if (sources.count { it.type == SourceType.XTREAM && it.isEnabled } > 0) "Apri il catalogo Live TV delle sorgenti abilitate" else "Aggiungi e abilita una sorgente Xtream per unire i canali",
            tint = Color(0xFFFF667C),
            enabled = sources.any { it.type == SourceType.XTREAM && it.isEnabled },
            onClick = { onRoute("live") }
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Le mie sorgenti", color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${displayedSources.size} sorgenti", color = SourcesMuted, fontSize = 11.sp)
            }
            Box {
                TextButton(onClick = { sortExpanded = true }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)) {
                    Text(sortMode, color = SourcesBlue, fontSize = 11.sp, maxLines = 1)
                    Icon(Icons.Default.ExpandMore, null, tint = SourcesBlue, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    listOf("Personalizzato", "Nome (A–Z)", "Tipo").forEach { mode ->
                        DropdownMenuItem(text = { Text(mode) }, onClick = { sortMode = mode; sortExpanded = false }, trailingIcon = { if (sortMode == mode) Icon(Icons.Default.CheckCircle, null) })
                    }
                }
            }
        }
        EpgStyleSearchField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Cerca sorgenti"
        )
        SourceActionRow(Icons.Default.Add, "Aggiungi playlist", "Collega una playlist M3U o un account Xtream", Color(0xFF73A7FF)) { showAdd = true }
        SourceActionRow(Icons.Default.Settings, "Gestisci sorgenti", "Modifica dettagli, contenuto ed EPG di ogni sorgente", Color(0xFF9E9BFF), enabled = sources.isNotEmpty()) { onRoute("source-manager") }

        if (displayedSources.isEmpty()) {
            SourceEmptyState(if (sources.isEmpty()) "Nessuna sorgente configurata" else "Nessun risultato", if (sources.isEmpty()) "Tocca Aggiungi playlist per iniziare." else "Prova con un altro nome, host o tipo.")
        } else displayedSources.forEach { source ->
            LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 19.dp, contentPadding = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(sourceTint(source).copy(.2f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.SettingsInputAntenna, null, tint = sourceTint(source), modifier = Modifier.size(21.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (source.isPinned) Icon(Icons.Default.PushPin, null, tint = Color(0xFFFFB45E), modifier = Modifier.size(13.dp))
                                Text(source.name, color = glassForeground(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(source.host, color = SourcesMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(source.type.name, color = glassForeground(.55f), fontSize = 10.sp)
                                Text("•", color = glassForeground(.4f), fontSize = 10.sp)
                                Text(if (source.lastVerificationSucceeded) "Verificata · ${source.lastKnownChannelCount} elementi" else if (source.lastVerifiedAt != null) "Verifica non riuscita" else "Non verificata", color = if (source.lastVerificationSucceeded) SourcesGreen else Color(0xFFFFBD70), fontSize = 10.sp)
                            }
                        }
                        if (activeId == source.id) Icon(Icons.Default.CheckCircle, "Sorgente attiva", tint = SourcesGreen, modifier = Modifier.size(20.dp))
                        Box {
                            IconButton(onClick = { sourceMenuTarget = source }) { Icon(Icons.Default.MoreVert, "Azioni sorgente", tint = glassForeground(.78f)) }
                            DropdownMenu(expanded = sourceMenuTarget?.id == source.id, onDismissRequest = { sourceMenuTarget = null }) {
                                DropdownMenuItem(text = { Text("Imposta attiva") }, leadingIcon = { Icon(Icons.Default.CheckCircle, null) }, onClick = { sourceMenuTarget = null; vm.setActive(source.id) })
                                DropdownMenuItem(text = { Text(if (source.isPinned) "Rimuovi pin" else "Fissa in alto") }, leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = { sourceMenuTarget = null; scope.launch { app.sources.pin(source.id, !source.isPinned); vm.refresh(false) } })
                                DropdownMenuItem(text = { Text("Rinomina") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { renameName = source.name; renameTarget = source; sourceMenuTarget = null })
                                DropdownMenuItem(text = { Text("Duplica") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { sourceMenuTarget = null; scope.launch { app.sources.duplicate(source.id); vm.refresh(false) } })
                                DropdownMenuItem(text = { Text("Verifica connessione") }, leadingIcon = { Icon(Icons.Default.Shield, null) }, onClick = { sourceMenuTarget = null; verifyingId = source.id; scope.launch { val result = app.sources.verify(source); vm.refresh(false); feedback = result.fold({ "${source.name}: connessione riuscita (${it} elementi)." }, { "${source.name}: ${it.message ?: "verifica non riuscita"}" }); verifyingId = null } })
                                DropdownMenuItem(text = { Text("Gestisci…") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { sourceMenuTarget = null; onManage(source) })
                                DropdownMenuItem(text = { Text("Elimina") }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFFF8791)) }, onClick = { deleteTarget = source; sourceMenuTarget = null })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (source.isEnabled) SourcesGreen else Color.Gray))
                            Text(if (source.isEnabled) "Abilitata" else "Disabilitata", color = SourcesMuted, fontSize = 11.sp)
                        }
                        Text("Attiva", color = SourcesMuted, fontSize = 11.sp)
                        Switch(checked = source.isEnabled, onCheckedChange = { enabled ->
                            scope.launch {
                                app.sources.toggle(source.id, enabled)
                                if (!enabled && activeId == source.id) {
                                    val next = sources.firstOrNull { it.id != source.id && it.isEnabled }
                                    app.sources.setActive(next?.id)
                                }
                                vm.refresh(true)
                            }
                        })
                        TextButton(onClick = { vm.setActive(source.id) }) { Text(if (activeId == source.id) "Attiva" else "Usa", color = if (activeId == source.id) SourcesGreen else SourcesBlue) }
                    }
                    if (verifyingId == source.id) LinearProgressIndicator(Modifier.fillMaxWidth(), color = SourcesBlue, trackColor = Color.White.copy(.1f))
                }
            }
        }

        SourceSectionHeader("Playlist unite", "Combina più sorgenti")
        if (merged.isEmpty()) Text("Unisci più sorgenti in una sola playlist per gestire un catalogo aggregato.", color = SourcesMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
        merged.sortedBy { it.sortOrder }.forEach { list ->
            LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 16.dp, contentPadding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.SettingsInputAntenna, null, tint = Color(0xFFB59AFF))
                    Column(Modifier.weight(1f)) {
                        Text(list.name, color = glassForeground(), fontWeight = FontWeight.SemiBold)
                        Text("${list.memberSourceIds.size} sorgenti unite", color = SourcesMuted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { scope.launch { app.mergedPlaylists.remove(list.id) } }) { Icon(Icons.Default.Delete, "Elimina playlist unita", tint = Color(0xFFFF8791)) }
                }
            }
        }
        SourceActionRow(Icons.Default.Add, "Crea playlist unita", "Seleziona almeno due sorgenti", Color(0xFFB59AFF), enabled = sources.size >= 2) { selectedMergeIds = emptySet(); mergedName = ""; mergeDialog = true }

        SourceSectionHeader("Preferiti", "I contenuti salvati")
        if (favouriteItems.isEmpty()) Text("I contenuti preferiti appariranno qui dopo il caricamento del catalogo.", color = SourcesMuted, fontSize = 13.sp)
        else favouriteItems.forEach { item ->
            LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 14.dp, contentPadding = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Favorite, null, tint = Color(0xFFFF8AA6), modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = glassForeground(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text("${item.kind.name} · ${item.sourceId}", color = SourcesMuted, fontSize = 11.sp)
                    }
                }
            }
        }

        SourceSectionHeader("Backup", "Esporta o importa sorgenti")
        SourceActionRow(Icons.Default.FileDownload, "Esporta sorgenti (JSON)", "Il file contiene le impostazioni delle sorgenti e può includere credenziali", Color(0xFF73A7FF)) {
            scope.launch { exportJson = runCatching { app.backup.exportSources() }.getOrElse { "Errore: ${it.message}" } }
        }
        SourceActionRow(Icons.Default.FileUpload, "Importa sorgenti (JSON)", "Importa un backup GassPlayer compatibile", Color(0xFF73A7FF)) { importJson = ""; importDialog = true }
        SourceActionRow(Icons.Default.Shield, "Verifica tutte le sorgenti Xtream", if (verifyingAll) "Verifica in corso…" else "Controlla connessione e catalogo", SourcesGreen, enabled = !verifyingAll && sources.any { it.type == SourceType.XTREAM }) {
            scope.launch {
                verifyingAll = true
                var ok = 0
                val xtream = sources.filter { it.type == SourceType.XTREAM }
                xtream.forEach { source -> if (runCatching { app.sources.verify(source) }.getOrNull()?.isSuccess == true) ok++ }
                vm.refresh(false)
                feedback = "Verifica completata: $ok/${xtream.size} sorgenti Xtream riuscite."
                verifyingAll = false
            }
        }
        Text("Il backup JSON può includere credenziali e token: condividilo solo tramite servizi affidabili.", color = SourcesMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 18.dp))
    }

    if (showAdd) AddSourceDialog(app, vm, onDismiss = { showAdd = false })
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rinomina sorgente") },
            text = { OutlinedTextField(renameName, { renameName = it }, singleLine = true, label = { Text("Nome") }) },
            confirmButton = { TextButton(onClick = { val s = renameTarget ?: return@TextButton; scope.launch { app.sources.rename(s.id, renameName); vm.refresh(false); renameTarget = null } }, enabled = renameName.isNotBlank()) { Text("Salva") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Annulla") } }
        )
    }
    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Eliminare ${target.name}?") },
            text = { Text("La sorgente e le sue credenziali verranno rimosse. I preferiti salvati non vengono cancellati automaticamente.") },
            confirmButton = { TextButton(onClick = { vm.deleteSource(target.id); deleteTarget = null }) { Text("Elimina", color = Color(0xFFFF8791)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annulla") } }
        )
    }
    if (mergeDialog) {
        AlertDialog(
            onDismissRequest = { mergeDialog = false },
            title = { Text("Crea playlist unita") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mergedName, { mergedName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nome playlist") })
                    sources.forEach { source ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = source.id in selectedMergeIds, onCheckedChange = { checked -> selectedMergeIds = if (checked) selectedMergeIds + source.id else selectedMergeIds - source.id })
                            Column(Modifier.weight(1f)) { Text(source.name); Text(source.type.name, color = SourcesMuted, fontSize = 11.sp) }
                        }
                    }
                    if (selectedMergeIds.size < 2) Text("Seleziona almeno due sorgenti.", color = SourcesMuted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(enabled = mergedName.isNotBlank() && selectedMergeIds.size >= 2, onClick = { scope.launch { app.mergedPlaylists.create(mergedName, selectedMergeIds.toList()); mergeDialog = false } }) { Text("Crea") } },
            dismissButton = { TextButton(onClick = { mergeDialog = false }) { Text("Annulla") } }
        )
    }
    if (importDialog) {
        AlertDialog(
            onDismissRequest = { importDialog = false },
            title = { Text("Importa backup JSON") },
            text = { OutlinedTextField(importJson, { importJson = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 360.dp), label = { Text("JSON") }, placeholder = { Text("Incolla qui il backup") }) },
            confirmButton = { TextButton(enabled = importJson.isNotBlank(), onClick = { scope.launch { val result = runCatching { app.backup.importAny(importJson) }; if (result.isSuccess) { vm.refresh(true); feedback = "Backup importato correttamente."; importDialog = false } else feedback = "Importazione non riuscita: ${result.exceptionOrNull()?.message}" } }) { Text("Importa") } },
            dismissButton = { TextButton(onClick = { importDialog = false }) { Text("Annulla") } }
        )
    }
    exportJson?.let { json ->
        AlertDialog(
            onDismissRequest = { exportJson = null },
            title = { Text("Backup sorgenti") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Copia il contenuto JSON per salvarlo in un luogo sicuro.", fontSize = 12.sp); OutlinedTextField(json, {}, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp), readOnly = true) } },
            confirmButton = { TextButton(onClick = { exportJson = null }) { Text("Chiudi") } },
            dismissButton = { TextButton(onClick = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(json)); feedback = "Backup copiato negli appunti." }) { Text("Copia") } }
        )
    }
    feedback?.let { message ->
        AlertDialog(onDismissRequest = { feedback = null }, title = { Text("Sorgenti") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { feedback = null }) { Text("OK") } })
    }
}

/** iOS SourceManagerView + SourceManageView translated to a DPAD-friendly Android screen. */
@Composable
fun SourceManagerView(
    app: GassPlayerApplication,
    vm: MainViewModel,
    initialSourceId: String? = null,
    onBack: () -> Unit,
    onRoute: (String) -> Unit
) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val activeId by vm.activeSource.collectAsStateWithLifecycle()
    var selectedId by remember(initialSourceId) { mutableStateOf(initialSourceId) }
    val selected = sources.firstOrNull { it.id == selectedId }

    if (selected != null) {
        SourceManageView(
            app = app,
            vm = vm,
            source = selected,
            active = activeId == selected.id,
            onBack = { selectedId = null },
            onRoute = onRoute
        )
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.focusable()) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(5.dp)); Text("Indietro") }
                Column {
                    Text("Gestisci sorgenti", color = glassForeground(), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("Apri una sorgente per ricaricarla, modificarla o gestirne contenuto ed EPG.", color = SourcesMuted, fontSize = 12.sp)
                }
            }
            if (sources.isEmpty()) SourceEmptyState("Nessuna sorgente configurata", "Torna alla schermata Sorgenti e aggiungi una playlist.")
            sources.sortedWith(compareByDescending<MediaSourceConfig> { it.isPinned }.thenBy { it.sortOrder }).forEach { source ->
                Surface(
                    onClick = { selectedId = source.id },
                    modifier = Modifier.fillMaxWidth().focusable(),
                    color = Color.Transparent,
                    shape = RoundedCornerShape(19.dp),
                    border = BorderStroke(.7.dp, Color.White.copy(.14f))
                ) {
                    LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 19.dp, contentPadding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(sourceTint(source).copy(.20f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.SettingsInputAntenna, null, tint = sourceTint(source)) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(source.name, color = glassForeground(), fontWeight = FontWeight.SemiBold)
                                Text(source.host, color = SourcesMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${source.type.name} · ${if (source.isEnabled) "Abilitata" else "Disabilitata"}", color = glassForeground(.56f), fontSize = 11.sp)
                            }
                            if (activeId == source.id) Icon(Icons.Default.CheckCircle, "Sorgente attiva", tint = SourcesGreen)
                            Icon(Icons.Default.ExpandMore, "Gestisci", tint = glassForeground(.58f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceManageView(app: GassPlayerApplication, vm: MainViewModel, source: MediaSourceConfig, active: Boolean, onBack: () -> Unit, onRoute: (String) -> Unit) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val current = sources.firstOrNull { it.id == source.id } ?: source
    val scope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var account by remember(current.id) { mutableStateOf<XtreamAccountInfo?>(null) }
    var accountLoading by remember(current.id) { mutableStateOf(false) }
    var accountError by remember(current.id) { mutableStateOf<String?>(null) }
    var reloadLoading by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var editDialog by remember { mutableStateOf(false) }
    var contentDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var mergeSourceDialog by remember { mutableStateOf(false) }
    var mergedSourceName by remember(current.id) { mutableStateOf("${current.name} + altra sorgente") }
    var selectedMergeSources by remember(current.id) { mutableStateOf(setOf(current.id)) }
    var sourceExportJson by remember { mutableStateOf<String?>(null) }
    var sourceItemCounts by remember(current.id) { mutableStateOf(SourceItemCounts()) }
    var sourceFavoriteCounts by remember(current.id) { mutableStateOf(SourceFavoriteCounts()) }

    // Source management must not decode every item on the UI thread just to show counts.
    LaunchedEffect(current.id, catalog?.updatedAt) {
        val snapshot = catalog
        sourceItemCounts = withContext(Dispatchers.IO) {
            val db = snapshot?.store
            if (db != null) {
                val counts = db.sourceKindCounts(current.id)
                SourceItemCounts(
                    live = counts[MediaKind.LIVE] ?: 0,
                    movies = counts[MediaKind.MOVIE] ?: 0,
                    series = counts[MediaKind.SERIES] ?: 0,
                    episodes = counts[MediaKind.EPISODE] ?: 0
                )
            } else {
                val counts = snapshot?.allItems?.asSequence()?.filter { it.sourceId == current.id }?.groupingBy { it.kind }?.eachCount().orEmpty()
                SourceItemCounts(counts[MediaKind.LIVE] ?: 0, counts[MediaKind.MOVIE] ?: 0, counts[MediaKind.SERIES] ?: 0, counts[MediaKind.EPISODE] ?: 0)
            }
        }
    }
    LaunchedEffect(current.id, catalog?.updatedAt, favorites.live, favorites.movies, favorites.series) {
        val snapshot = catalog
        val liveIds = favorites.live
        val movieIds = favorites.movies
        val seriesIds = favorites.series
        sourceFavoriteCounts = withContext(Dispatchers.IO) {
            val db = snapshot?.store
            if (db != null) SourceFavoriteCounts(
                live = db.countIdsOfSource(current.id, MediaKind.LIVE, liveIds),
                movies = db.countIdsOfSource(current.id, MediaKind.MOVIE, movieIds),
                series = db.countIdsOfSource(current.id, MediaKind.SERIES, seriesIds)
            ) else {
                val matching = snapshot?.allItems?.asSequence()?.filter { it.sourceId == current.id }?.toList().orEmpty()
                SourceFavoriteCounts(
                    live = matching.count { it.kind == MediaKind.LIVE && it.id in liveIds },
                    movies = matching.count { it.kind == MediaKind.MOVIE && it.id in movieIds },
                    series = matching.count { it.kind == MediaKind.SERIES && it.id in seriesIds }
                )
            }
        }
    }
    val expirationText = account?.expDate?.let { exp ->
        if (exp <= 0L) "Senza scadenza" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(if (exp < 1_000_000_000_000L) exp * 1000 else exp))
    } ?: "—"

    LaunchedEffect(current.id, current.host, current.username, current.password) {
        if (current.type == SourceType.XTREAM && !current.username.isNullOrBlank() && !current.password.isNullOrBlank()) {
            accountLoading = true
            accountError = null
            val result = runCatching { app.xtream.authenticate(XtreamCredentials(current.host, current.username.orEmpty(), current.password.orEmpty())) }
            result.onSuccess { account = it }.onFailure { accountError = it.message ?: "Errore account Xtream" }
            accountLoading = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.focusable()) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(4.dp)); Text("Indietro") }
            Column(Modifier.weight(1f)) {
                Text(current.name, color = glassForeground(), fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (active) "Sorgente attiva" else current.type.name, color = if (active) SourcesGreen else SourcesMuted, fontSize = 12.sp)
            }
            if (!active) TextButton(onClick = { vm.setActive(current.id) }) { Text("Attiva") }
        }
        if (current.type == SourceType.XTREAM) {
            SourceSectionHeader("Informazioni sul server", "Account Xtream")
            LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, contentPadding = 14.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    SourceInfoRow(Icons.Default.Wifi, "Stato", if (accountLoading) "Verifica…" else account?.status?.uppercase(Locale.ROOT) ?: if (accountError != null) "SCONOSCIUTO" else "—", if (account?.status.equals("active", true)) SourcesGreen else if (accountError != null) Color(0xFFFF8C94) else glassForeground())
                    SourceInfoRow(Icons.Default.Tv, "Connessioni", if (account == null) "—" else "${account?.activeConnections ?: 0} / ${account?.maxConnections ?: 0}")
                    SourceInfoRow(Icons.Default.Shield, "Scadenza", expirationText)
                    if (!accountError.isNullOrBlank()) Text(accountError!!, color = Color(0xFFFF9A9A), fontSize = 12.sp)
                    if (accountLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = SourcesBlue)
                }
            }
        }
        SourceSectionHeader("Impostazioni", "Gestione della sorgente")
        SourceActionRow(Icons.Default.Refresh, "Ricarica", if (reloadLoading) "Aggiornamento in corso…" else "Verifica e aggiorna il catalogo", SourcesBlue, enabled = !reloadLoading) {
            scope.launch {
                reloadLoading = true
                val result = runCatching {
                    if (active) vm.refresh(true).join() else app.sources.verify(current).getOrThrow()
                }
                if (!active) vm.refresh(false).join()
                feedback = result.fold({ if (active) "Catalogo aggiornato." else "Sorgente verificata." }, { "Aggiornamento non riuscito: ${it.message ?: "errore sconosciuto"}" })
                reloadLoading = false
            }
        }
        SourceActionRow(Icons.Default.Edit, "Modifica dettagli", "Nome, server e credenziali", Color(0xFF9DABFF)) { editDialog = true }
        SourceActionRow(Icons.Default.Settings, "Gestisci contenuto", "${sourceItemCounts.total} elementi nel catalogo · Live TV, film, serie ed episodi", Color(0xFF9DABFF)) { contentDialog = true }
        SourceActionRow(Icons.Default.Tv, "Gestisci EPG", "Configura e verifica la guida TV", Color(0xFFB59AFF)) { onRoute("epg-manage") }
        SourceActionRow(Icons.Default.Delete, "Cancella", "Rimuove la sorgente e le credenziali salvate", Color(0xFFFF8791)) { deleteDialog = true }
        Spacer(Modifier.height(12.dp))
    }

    if (editDialog) SourceEditDialog(current, onDismiss = { editDialog = false }) { updated -> scope.launch { app.sources.addOrUpdate(updated); vm.refresh(false); editDialog = false } }
    if (contentDialog) {
        val live = sourceItemCounts.live
        val movies = sourceItemCounts.movies
        val series = sourceItemCounts.series
        val episodes = sourceItemCounts.episodes
        val liveFavorites = sourceFavoriteCounts.live
        val movieFavorites = sourceFavoriteCounts.movies
        val seriesFavorites = sourceFavoriteCounts.series
        AlertDialog(
            onDismissRequest = { contentDialog = false },
            title = { Text("Contenuto di ${current.name}") },
            text = {
                Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SourceInfoRow(Icons.Default.Tv, "Live TV", live.toString())
                    SourceInfoRow(Icons.Default.SettingsInputAntenna, "Film", movies.toString())
                    SourceInfoRow(Icons.Default.Tv, "Serie TV", series.toString())
                    SourceInfoRow(Icons.Default.Tv, "Episodi", episodes.toString())
                    HorizontalDivider(color = Color.White.copy(.12f))
                    Text("Preferiti di questa sorgente", color = glassForeground(), fontWeight = FontWeight.SemiBold)
                    SourceInfoRow(Icons.Default.Tv, "Live TV", liveFavorites.toString())
                    SourceInfoRow(Icons.Default.SettingsInputAntenna, "VOD", movieFavorites.toString())
                    SourceInfoRow(Icons.Default.Tv, "Serie TV", seriesFavorites.toString())
                    Text("Le statistiche sono relative al catalogo attualmente caricato. Premi Ricarica per aggiornarle.", color = SourcesMuted, fontSize = 12.sp)
                    SourceActionRow(Icons.Default.ContentCopy, "Duplica sorgente", "Crea una copia delle impostazioni", SourcesBlue) {
                        scope.launch { app.sources.duplicate(current.id); vm.refresh(false); contentDialog = false; feedback = "Sorgente duplicata." }
                    }
                    SourceActionRow(Icons.Default.SettingsInputAntenna, "Unisci con un'altra sorgente", "Crea una playlist aggregata", Color(0xFFB59AFF), enabled = sources.size > 1) {
                        selectedMergeSources = setOf(current.id)
                        mergedSourceName = "${current.name} + altra sorgente"
                        mergeSourceDialog = true
                        contentDialog = false
                    }
                    SourceActionRow(Icons.Default.FileDownload, "Esporta questa sorgente (JSON)", "Copia un backup contenente solo questa sorgente", SourcesBlue) {
                        scope.launch { sourceExportJson = runCatching { app.backup.exportSources(listOf(current)) }.getOrElse { "Errore: ${it.message}" }; contentDialog = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { contentDialog = false }) { Text("Chiudi") } }
        )
    }
    if (mergeSourceDialog) AlertDialog(
        onDismissRequest = { mergeSourceDialog = false },
        title = { Text("Unisci sorgenti") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(mergedSourceName, { mergedSourceName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nome playlist unita") })
                sources.forEach { other ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = other.id in selectedMergeSources, onCheckedChange = { checked -> selectedMergeSources = if (checked) selectedMergeSources + other.id else selectedMergeSources - other.id })
                        Column(Modifier.weight(1f)) { Text(other.name, color = glassForeground()); Text(other.type.name, color = SourcesMuted, fontSize = 11.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = mergedSourceName.isNotBlank() && selectedMergeSources.size >= 2, onClick = { scope.launch { app.mergedPlaylists.create(mergedSourceName, selectedMergeSources.toList()); mergeSourceDialog = false; feedback = "Playlist unita creata." } }) { Text("Crea") } },
        dismissButton = { TextButton(onClick = { mergeSourceDialog = false }) { Text("Annulla") } }
    )
    sourceExportJson?.let { json -> AlertDialog(
        onDismissRequest = { sourceExportJson = null },
        title = { Text("Backup ${current.name}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Il JSON può contenere credenziali. Conservalo in un luogo sicuro.", color = SourcesMuted, fontSize = 12.sp); OutlinedTextField(json, {}, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp), readOnly = true) } },
        confirmButton = { TextButton(onClick = { sourceExportJson = null }) { Text("Chiudi") } },
        dismissButton = { TextButton(onClick = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(json)); feedback = "Backup copiato negli appunti." }) { Text("Copia") } }
    ) }
    if (deleteDialog) AlertDialog(
        onDismissRequest = { deleteDialog = false },
        title = { Text("Eliminare ${current.name}?") },
        text = { Text("La sorgente e le credenziali verranno rimosse. I preferiti già salvati restano nelle preferenze.") },
        confirmButton = { TextButton(onClick = { vm.deleteSource(current.id); deleteDialog = false; onBack() }) { Text("Elimina", color = Color(0xFFFF8791)) } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Annulla") } }
    )
    feedback?.let { msg -> AlertDialog(onDismissRequest = { feedback = null }, title = { Text("Sorgente") }, text = { Text(msg) }, confirmButton = { TextButton(onClick = { feedback = null }) { Text("OK") } }) }
}

@Composable
private fun SourceEditDialog(source: MediaSourceConfig, onDismiss: () -> Unit, onSave: (MediaSourceConfig) -> Unit) {
    var name by remember(source.id) { mutableStateOf(source.name) }
    var host by remember(source.id) { mutableStateOf(source.playlistUrl ?: source.host) }
    var username by remember(source.id) { mutableStateOf(source.username.orEmpty()) }
    var password by remember(source.id) { mutableStateOf(source.password.orEmpty()) }
    var enabled by remember(source.id) { mutableStateOf(source.isEnabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica dettagli") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nome") })
                OutlinedTextField(host, { host = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(if (source.type == SourceType.M3U8) "URL playlist" else "Server / URL") })
                if (source.type == SourceType.XTREAM) {
                    OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username") })
                    OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Password") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Sorgente abilitata", modifier = Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && host.isNotBlank(), onClick = { onSave(source.copy(name = name.trim(), host = host.trim(), playlistUrl = if (source.type == SourceType.M3U8) host.trim() else source.playlistUrl, username = username.trim().takeIf { it.isNotBlank() }, password = password.takeIf { it.isNotBlank() }, isEnabled = enabled)) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
private fun SourceActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().focusable(),
        shape = RoundedCornerShape(17.dp),
        color = Color.Transparent,
        border = BorderStroke(.65.dp, Color.White.copy(if (enabled) .14f else .06f))
    ) {
        LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 17.dp, contentPadding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(.17f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(19.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = glassForeground(if (enabled) 1f else .45f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(subtitle, color = glassForeground(if (enabled) .62f else .3f), fontSize = 11.sp, lineHeight = 15.sp)
                }
                Text("›", color = glassForeground(if (enabled) .48f else .22f), fontSize = 24.sp)
            }
        }
    }
}

@Composable
private fun SourceSectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 9.dp, bottom = 1.dp)) {
        Text(title, color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, color = SourcesMuted, fontSize = 11.sp)
    }
}

@Composable
private fun SourceEmptyState(title: String, message: String) {
    LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, contentPadding = 22.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.SettingsInputAntenna, null, tint = SourcesBlue, modifier = Modifier.size(27.dp))
            Text(title, color = glassForeground(), fontWeight = FontWeight.Bold)
            Text(message, color = SourcesMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SourceInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, valueColor: Color = glassForeground()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = SourcesMuted, modifier = Modifier.size(19.dp))
        Text(label, color = SourcesMuted, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = valueColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun sourceTint(source: MediaSourceConfig): Color = when (source.type) {
    SourceType.XTREAM -> Color(0xFF7EA5FF)
    SourceType.M3U8 -> Color(0xFF68D8B8)
    SourceType.PLEX -> Color(0xFFFFC45F)
    SourceType.JELLYFIN -> Color(0xFFB8A0FF)
    SourceType.EMBY -> Color(0xFF70C8FF)
}
