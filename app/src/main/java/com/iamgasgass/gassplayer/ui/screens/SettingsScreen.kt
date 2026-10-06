package com.iamgasgass.gassplayer.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.ui.AppState
import com.iamgasgass.gassplayer.ui.MainViewModel
import com.iamgasgass.gassplayer.ui.theme.GlassCard
import com.iamgasgass.gassplayer.ui.theme.Muted
import com.iamgasgass.gassplayer.ui.theme.Purple
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    state: AppState,
    vm: MainViewModel,
    onBack: () -> Unit,
    compact: (Boolean) -> Unit,
    numbers: (Boolean) -> Unit,
    refresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val content = vm.export()
                context.contentResolver.openOutputStream(uri)?.use {
                    it.writer(Charsets.UTF_8).use { writer -> writer.write(content) }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                context.contentResolver.openInputStream(uri)?.use {
                    vm.import(it.reader(Charsets.UTF_8).readText())
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader("Impostazioni", onBack)
        LazyColumn(
            contentPadding = PaddingValues(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Interfaccia", style = MaterialTheme.typography.headlineSmall) }
            item {
                SettingToggle(
                    "Griglia compatta",
                    "Mostra più contenuti sullo schermo",
                    state.compact,
                    compact,
                )
            }
            item {
                SettingToggle(
                    "Mostra numero canale",
                    "Visualizza la numerazione nella griglia Live TV",
                    state.showNumbers,
                    numbers,
                )
            }

            item { Text("Catalogo", style = MaterialTheme.typography.headlineSmall) }
            item {
                GlassCard(Modifier.fillMaxWidth(), onClick = refresh) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, null, tint = Purple)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Aggiorna catalogo")
                            Text(
                                "Ricarica dati e sostituisce la cache",
                                color = Muted,
                            )
                        }
                    }
                }
            }

            item { Text("Backup", style = MaterialTheme.typography.headlineSmall) }
            item {
                GlassCard(
                    Modifier.fillMaxWidth(),
                    onClick = {
                        exportLauncher.launch("GassPlayer-backup.json")
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Download, null, tint = Purple)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Esporta dati")
                            Text("Sorgenti, preferiti e cronologia in JSON", color = Muted)
                        }
                    }
                }
            }
            item {
                GlassCard(
                    Modifier.fillMaxWidth(),
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FileUpload, null, tint = Purple)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Importa dati")
                            Text("Ripristina un backup GassPlayer", color = Muted)
                        }
                    }
                }
            }

            item { Text("Riproduzione", style = MaterialTheme.typography.headlineSmall) }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Media3 ExoPlayer")
                    Text(
                        "HLS, MPEG-TS, DASH, file progressivi, sottotitoli e tracce audio supportate dal flusso",
                        color = Muted,
                    )
                }
            }

            item { Text("Informazioni", style = MaterialTheme.typography.headlineSmall) }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("GassPlayer IPTV per Android / Android TV")
                    Text("Versione 1.1.0", color = Muted)
                }
            }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    detail: String,
    value: Boolean,
    change: (Boolean) -> Unit,
) {
    GlassCard(
        Modifier.fillMaxWidth(),
        onClick = { change(!value) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(detail, color = Muted)
            }
            Switch(checked = value, onCheckedChange = change)
        }
    }
}
