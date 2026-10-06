package com.iamgasgass.gassplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.data.EpgProgramme
import com.iamgasgass.gassplayer.ui.AppState
import com.iamgasgass.gassplayer.ui.MainViewModel
import com.iamgasgass.gassplayer.ui.theme.GlassCard
import com.iamgasgass.gassplayer.ui.theme.Muted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EpgScreen(
    state: AppState,
    vm: MainViewModel,
    onBack: () -> Unit,
    play: (String) -> Unit,
) {
    var selectedId by remember(state.catalog.channels) {
        mutableStateOf(state.catalog.channels.firstOrNull()?.id.orEmpty())
    }
    val selected = state.catalog.channels.firstOrNull { it.id == selectedId }
    val programmes = state.epg[selectedId].orEmpty()

    LaunchedEffect(selectedId) {
        if (selectedId.isNotBlank()) vm.epg(selectedId)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Guida TV", onBack)

        if (state.catalog.channels.isEmpty()) {
            EmptyState(
                "EPG non disponibile",
                "Aggiungi una sorgente Xtream oppure una playlist M3U con URL XMLTV",
            )
            return
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LazyColumn(
                Modifier.weight(0.36f),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.catalog.channels.take(200), key = { it.id }) { channel ->
                    GlassCard(
                        Modifier.fillMaxWidth(),
                        onClick = {
                            selectedId = channel.id
                        },
                    ) {
                        Text(channel.name, fontWeight = FontWeight.Bold)
                        Text(channel.group, color = Muted, maxLines = 1)
                    }
                }
            }

            Column(
                Modifier
                    .weight(0.64f)
                    .padding(bottom = 28.dp),
            ) {
                Text(
                    selected?.name ?: "Seleziona un canale",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))

                if (selected != null && programmes.isEmpty()) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Nessuna programmazione disponibile", fontWeight = FontWeight.Bold)
                        Text(
                            "Il provider potrebbe non esporre l'EPG per questo canale.",
                            color = Muted,
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 28.dp),
                    ) {
                        items(programmes, key = { "${it.channelId}-${it.startMillis}" }) {
                            ProgrammeCard(it, onClick = { selected?.let { channel -> play(channel.id) } })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCard(programme: EpgProgramme, onClick: () -> Unit) {
    val formatter = remember {
        SimpleDateFormat("HH:mm", Locale.ITALIAN)
    }
    GlassCard(Modifier.fillMaxWidth(), onClick) {
        Row {
            Text(
                "${formatter.format(Date(programme.startMillis))} - " +
                    formatter.format(Date(programme.endMillis)),
                modifier = Modifier.padding(end = 12.dp),
                fontWeight = FontWeight.Bold,
            )
            Column {
                Text(programme.title, fontWeight = FontWeight.SemiBold)
                if (programme.description.isNotBlank()) {
                    Text(programme.description, color = Muted, maxLines = 3)
                }
            }
        }
    }
}
