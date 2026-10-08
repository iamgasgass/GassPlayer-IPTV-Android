package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.SourceType
import com.gassplayer.android.ui.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun IosSourceManagerView(app: GassPlayerApplication, vm: MainViewModel) {
    val sources by app.sources.sources.collectAsState(initial = emptyList())
    val active by app.sources.activeSource.collectAsState(initial = null)
    var selected by remember(active, sources) { mutableStateOf(sources.firstOrNull { it.id == active } ?: sources.firstOrNull()) }
    var showEdit by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val source = selected
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IosSectionHeader("Gestisci sorgente", "Dettagli server, contenuti e stato della playlist")
        if (source == null) {
            IosEmptyState("Nessuna sorgente", "Aggiungi una playlist dalle sorgenti.", Icons.Default.SettingsInputAntenna)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            item {
                IosGlassCard {
                    IosSectionHeader(source.name, source.host)
                    Spacer(Modifier.height(8.dp))
                    IosGlassRow(Icons.Default.Wifi, "Stato", if (source.lastVerificationSucceeded) "Verificata" else "Non verificata", if (source.lastVerificationSucceeded) IosGreen else IosOrange, showChevron = false)
                    IosGlassRow(Icons.Default.Category, "Tipo", source.type.name, sourceTint(source.type), showChevron = false)
                    IosGlassRow(Icons.Default.LiveTv, "Elementi", "${source.lastKnownChannelCount}", IosBlue, showChevron = false)
                    IosGlassRow(Icons.Default.PushPin, "Fissata", if (source.isPinned) "Sì" else "No", IosOrange, showChevron = false)
                }
            }
            item { IosGlassRow(Icons.Default.Refresh, "Ricarica / verifica", "Controlla credenziali e playlist", onClick = { scope.launch { val r = app.sources.verify(source); r.onSuccess { count -> app.sources.addOrUpdate(source.copy(lastVerifiedAt = System.currentTimeMillis(), lastVerificationSucceeded = true, lastKnownChannelCount = count)); status = "Playlist verificata: $count elementi" }.onFailure { status = it.message ?: "Verifica non riuscita" } } }) }
            item { IosGlassRow(Icons.Default.Edit, "Modifica dettagli", "Nome, URL e credenziali", onClick = { showEdit = true }) }
            item { IosGlassRow(Icons.Default.FolderSpecial, "Gestisci contenuto", "Attiva/disattiva contenuti importati", onClick = { status = "La gestione del contenuto è integrata nel catalogo Android." }) }
            item { IosGlassRow(Icons.Default.CalendarMonth, "Gestisci EPG", "Fonti EPG e aggiornamento guida", onClick = { status = "Apri Fonti EPG dalla barra superiore." }) }
            item { IosGlassRow(Icons.Default.ContentCopy, "Duplica", "Crea una copia della sorgente", onClick = { scope.launch { app.sources.duplicate(source.id) } }) }
            item { IosGlassRow(Icons.Default.Delete, "Cancella", "Rimuove sorgente e credenziali", IosRed, onClick = { scope.launch { app.sources.delete(source.id); selected = null } }) }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)) }
    }
    if (showEdit) IosEditSourceDialogPublic(app, source) { showEdit = false }
}

@Composable
private fun IosEditSourceDialogPublic(app: GassPlayerApplication, source: com.gassplayer.android.data.MediaSourceConfig, onDismiss: () -> Unit) {
    var name by remember(source) { mutableStateOf(source.name) }
    var host by remember(source) { mutableStateOf(source.host) }
    var username by remember(source) { mutableStateOf(source.username.orEmpty()) }
    var password by remember(source) { mutableStateOf(source.password.orEmpty()) }
    val scope = rememberCoroutineScope()
    IosDialogFrame("Modifica dettagli", onDismiss) {
        IosTextField(name, { name = it }, "Nome")
        IosTextField(host, { host = it }, "URL")
        if (source.type != SourceType.M3U8) { IosTextField(username, { username = it }, "Username"); IosTextField(password, { password = it }, "Password", isPassword = true) }
        IosGlassPrimaryButton("Salva", Icons.Default.Check) { scope.launch { app.sources.addOrUpdate(source.copy(name = name.trim().ifBlank { source.name }, host = host.trim().ifBlank { source.host }, username = username.takeIf { source.type != SourceType.M3U8 }, password = password.takeIf { source.type != SourceType.M3U8 })); onDismiss() } }
    }
}
