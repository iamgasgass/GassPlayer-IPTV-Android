package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.ui.PlayerGlassButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh

@Composable
fun DebugConsoleView(
    app: GassPlayerApplication,
    onBack: (() -> Unit)? = null
) {
    val initialLog = remember { app.diagnostics.read() }
    val lines = remember(initialLog) { initialLog.lineSequence().filter { it.isNotBlank() }.toList() }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (onBack != null) {
                PlayerGlassButton(onClick = onBack, darkSurface = false) {
                    Text("Indietro")
                }
            }
            PlayerGlassButton(
                icon = Icons.Default.Refresh,
                contentDescription = "Aggiorna log",
                darkSurface = false,
                onClick = { /* il log viene riletto alla riapertura */ }
            )
            PlayerGlassButton(
                icon = Icons.Default.Delete,
                contentDescription = "Cancella log",
                darkSurface = false,
                onClick = app.diagnostics::clear
            )
        }

        if (lines.isEmpty()) {
            Text(
                text = "Nessun log disponibile.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
