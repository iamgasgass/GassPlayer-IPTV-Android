package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.MediaSourceConfig
import com.gassplayer.android.ui.MainViewModel
import com.gassplayer.android.ui.PlayerGlassButton
import kotlinx.coroutines.launch

@Composable
fun SourceManagerView(
    app: GassPlayerApplication,
    vm: MainViewModel,
    onBack: (() -> Unit)? = null
) {
    val sources by vm.sources.collectAsState(initial = emptyList())
    val activeId by vm.activeSource.collectAsState(initial = null)
    val currentSource: MediaSourceConfig? = sources.firstOrNull { it.id == activeId }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onBack != null) {
            PlayerGlassButton(onClick = onBack, darkSurface = false) {
                Text("Indietro")
            }
        }

        Text(
            text = currentSource?.name ?: "Nessuna sorgente attiva",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.id }) { source ->
                val active = source.id == activeId
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PlayerGlassButton(
                        icon = if (active) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (active) "Sorgente attiva" else "Imposta attiva",
                        darkSurface = false,
                        onClick = { vm.setActive(source.id) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(source.name, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            source.host,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = source.isEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                app.sources.toggle(source.id, enabled)
                                vm.refresh(false)
                            }
                        }
                    )
                    PlayerGlassButton(
                        icon = Icons.Default.Delete,
                        contentDescription = "Elimina sorgente",
                        darkSurface = false,
                        onClick = { vm.deleteSource(source.id) }
                    )
                }
            }
        }
    }
}
