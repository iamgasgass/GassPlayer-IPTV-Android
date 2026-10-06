package com.iamgasgass.gassplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.data.WatchProgress
import com.iamgasgass.gassplayer.ui.AppState
import com.iamgasgass.gassplayer.ui.Routes
import com.iamgasgass.gassplayer.ui.theme.GlassCard
import com.iamgasgass.gassplayer.ui.theme.Muted
import com.iamgasgass.gassplayer.ui.theme.Purple

@Composable
fun HomeScreen(
    state: AppState,
    navigate: (String) -> Unit,
    continuePlaying: (WatchProgress) -> Unit,
) {
    if (state.loading) {
        LoadingView()
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        "GassPlayer",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        state.selectedSource?.name ?: "Nessuna sorgente",
                        color = Muted,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { navigate(Routes.SEARCH) }) {
                    Icon(Icons.Default.Search, "Ricerca")
                }
                IconButton(onClick = { navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Default.Settings, "Impostazioni")
                }
            }
        }

        item {
            GlassCard(
                Modifier
                    .fillMaxWidth()
                    .height(210.dp),
                onClick = { navigate(Routes.LIVE) },
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF28164E), Color.Transparent),
                            ),
                        ),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Guarda ora", color = Color(0xFFBFA7FF))
                        Text(
                            "La tua TV, senza limiti",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${state.catalog.channels.size} canali • " +
                                "${state.catalog.movies.size} film • " +
                                "${state.catalog.series.size} serie",
                            color = Muted,
                        )
                        Button(
                            onClick = { navigate(Routes.LIVE) },
                            colors = ButtonDefaults.buttonColors(containerColor = Purple),
                        ) {
                            Icon(Icons.Default.LiveTv, null)
                            Text(" Live TV")
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HomeTile(
                    "Live TV",
                    "${state.catalog.channels.size} canali",
                    Icons.Default.LiveTv,
                    Modifier.weight(1f),
                ) { navigate(Routes.LIVE) }
                HomeTile(
                    "Film",
                    "${state.catalog.movies.size} titoli",
                    Icons.Default.Movie,
                    Modifier.weight(1f),
                ) { navigate(Routes.VOD) }
                HomeTile(
                    "Serie TV",
                    "${state.catalog.series.size} serie",
                    Icons.Default.Tv,
                    Modifier.weight(1f),
                ) { navigate(Routes.SERIES) }
                HomeTile(
                    "Guida TV",
                    "EPG",
                    Icons.Default.CalendarMonth,
                    Modifier.weight(1f),
                ) { navigate(Routes.EPG) }
            }
        }

        if (state.progress.isNotEmpty()) {
            item {
                Text(
                    "Continua a guardare",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.progress, key = { it.mediaId }) { progress ->
                        PosterCard(
                            progress.title,
                            progress.poster,
                            "${(progress.fraction * 100).toInt()}%",
                            onClick = {
                                if (progress.streamUrl.isNotBlank()) continuePlaying(progress)
                            },
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HomeTile(
                    "Sorgenti",
                    "Gestisci playlist",
                    Icons.Default.Dns,
                    Modifier.weight(1f),
                ) { navigate(Routes.SOURCES) }
                HomeTile(
                    "Preferiti",
                    "${state.favorites.size} elementi",
                    Icons.Default.Favorite,
                    Modifier.weight(1f),
                ) { navigate(Routes.FAVORITES) }
                HomeTile(
                    "Ricerca",
                    "Tutto il catalogo",
                    Icons.Default.Search,
                    Modifier.weight(1f),
                ) { navigate(Routes.SEARCH) }
            }
        }
    }
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    GlassCard(modifier.height(125.dp), onClick) {
        Icon(icon, null, tint = Purple, modifier = Modifier.weight(0f))
        Spacer(Modifier.weight(1f))
        Text(title, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
