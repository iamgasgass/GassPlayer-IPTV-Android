package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication
import kotlinx.coroutines.launch

@Composable
fun IosEpgManageView(app: GassPlayerApplication) {
    val sources by app.sources.sources.collectAsState(initial = emptyList())
    val external by app.externalEpg.flow.collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IosSectionHeader("Gestisci EPG", "Fonti esterne XMLTV e guida associata alle playlist")
        IosGlassCard { IosGlassRow(Icons.Default.Sync, "Aggiornamento EPG", "La guida viene aggiornata dalla sorgente attiva", showChevron = false) }
        IosGlassPrimaryButton("Aggiungi fonte EPG", Icons.Default.Add, onClick = { showAdd = true })
        IosGlassCard {
            IosSectionHeader("Fonti esterne", "XMLTV personalizzate")
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(external, key = { it.id }) { source ->
                    IosGlassRow(Icons.Default.CalendarMonth, source.name, source.urlString, IosGreen, showChevron = false, trailing = {
                        Switch(source.isEnabled, { enabled -> scope.launch { app.externalEpg.toggle(source.id, enabled) } })
                        IosGlassIconButton(Icons.Default.Delete, "Elimina", onClick = { scope.launch { app.externalEpg.remove(source.id) } }, size = 36)
                    })
                }
            }
        }
        IosGlassCard {
            IosSectionHeader("Sorgenti disponibili", "EPG ricavato automaticamente dalle playlist")
            sources.forEach { source ->
                IosGlassRow(Icons.Default.SettingsInputAntenna, source.name, source.type.name, sourceTint(source.type), showChevron = false)
            }
        }
        status?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)) }
    }
    if (showAdd) IosAddEpgDialog(app, onDismiss = { showAdd = false })
}

@Composable
private fun IosAddEpgDialog(app: GassPlayerApplication, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    IosDialogFrame("Aggiungi fonte EPG", onDismiss) {
        IosTextField(name, { name = it }, "Nome")
        IosTextField(url, { url = it }, "URL XMLTV")
        IosGlassPrimaryButton("Salva", Icons.Default.Check, enabled = name.isNotBlank() && url.isNotBlank() && !saving) {
            scope.launch { saving = true; if (app.externalEpg.add(name, url)) onDismiss(); else saving = false }
        }
    }
}
