package com.iamgasgass.gassplayer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamgasgass.gassplayer.ui.AppState

@Composable
fun FavoritesScreen(
    state: AppState,
    onBack: () -> Unit,
    toggle: (String) -> Unit,
    play: (String) -> Unit,
    openSeries: (String) -> Unit,
) {
    val channels = state.catalog.channels.filter { it.id in state.favorites }
    val movies = state.catalog.movies.filter { it.id in state.favorites }
    val series = state.catalog.series.filter { it.id in state.favorites }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Preferiti", onBack)

        if (state.favorites.isEmpty()) {
            EmptyState("Nessun preferito", "Aggiungi canali, film o serie dalla scheda di ciascun contenuto")
            return
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (state.compact) 160.dp else 200.dp),
            contentPadding = PaddingValues(28.dp),
        ) {
            items(channels, key = { "c-${it.id}" }) { channel ->
                ChannelCard(
                    number = channel.number,
                    name = channel.name,
                    logo = channel.logo,
                    group = channel.group,
                    showNumber = state.showNumbers,
                    favorite = true,
                    onClick = { play(channel.id) },
                    onFavorite = { toggle(channel.id) },
                )
            }
            items(movies, key = { "m-${it.id}" }) { movie ->
                PosterCard(
                    title = movie.name,
                    image = movie.poster,
                    subtitle = movie.year,
                    favorite = true,
                    compact = state.compact,
                    onClick = { play(movie.id) },
                    onFavorite = { toggle(movie.id) },
                )
            }
            items(series, key = { "s-${it.id}" }) { seriesItem ->
                PosterCard(
                    title = seriesItem.name,
                    image = seriesItem.poster,
                    subtitle = seriesItem.year,
                    favorite = true,
                    compact = state.compact,
                    onClick = { openSeries(seriesItem.id) },
                    onFavorite = { toggle(seriesItem.id) },
                )
            }
        }
    }
}
