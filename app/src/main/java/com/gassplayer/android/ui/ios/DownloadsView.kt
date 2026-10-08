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

@Composable
fun IosDownloadsView(app: GassPlayerApplication) {
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IosSectionHeader("Download", "Contenuti salvati e operazioni in background")
        IosEmptyState("Download", "I download vengono eseguiti tramite WorkManager e rimangono persistenti anche dopo la chiusura dell'app.", Icons.Default.Download)
        message?.let { Text(it) }
        IosGlassPrimaryButton("Apri cartella download", Icons.Default.FolderOpen, onClick = { message = "I download sono gestiti dal sistema Android." })
    }
}
