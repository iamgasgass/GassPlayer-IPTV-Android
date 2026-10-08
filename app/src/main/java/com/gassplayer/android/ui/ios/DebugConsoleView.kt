package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication

@Composable
fun IosDebugConsoleView(app: GassPlayerApplication) {
    var log by remember { mutableStateOf(app.diagnostics.read()) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        IosSectionHeader("Console di debug", "Log tecnici e diagnostica")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IosGlassPrimaryButton("Aggiorna", Icons.Default.Refresh, onClick = { log = app.diagnostics.read() }, modifier = Modifier.weight(1f))
            IosGlassPrimaryButton("Svuota", Icons.Default.DeleteSweep, onClick = { app.diagnostics.clear(); log = "" }, modifier = Modifier.weight(1f))
        }
        IosGlassCard(modifier = Modifier.fillMaxSize()) {
            LazyColumn { item { Text(log.ifBlank { "Nessun log disponibile." }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)) } }
        }
    }
}

@Composable
fun IosATSDiagnosticView(app: GassPlayerApplication) {
    IosGlassCard {
        IosSectionHeader("ATS / rete", "Stato dei servizi e delle connessioni")
        IosGlassRow(Icons.Default.Wifi, "Rete", "Gestita da NetworkApi", IosGreen, showChevron = false)
        IosGlassRow(Icons.Default.Storage, "Cache", "${app.diagnostics.cacheCount()} file", IosBlue, showChevron = false)
        IosGlassRow(Icons.Default.BugReport, "Log", "${app.diagnostics.read().lines().size} righe", IosOrange, showChevron = false)
    }
}
