package com.gassplayer.android.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import com.gassplayer.android.ui.MainViewModel

@Composable
fun ChannelsView(app: GassPlayerApplication, vm: MainViewModel, kind: MediaKind, onPlay: (MediaItem) -> Unit) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val items: List<MediaItem> = when(kind) { MediaKind.LIVE -> catalog?.live.orEmpty(); MediaKind.MOVIE -> catalog?.movies.orEmpty(); MediaKind.SERIES -> catalog?.series.orEmpty(); else -> catalog?.episodes.orEmpty() }
    val categories: List<Category> = when(kind) { MediaKind.LIVE -> catalog?.liveCategories.orEmpty(); MediaKind.MOVIE -> catalog?.vodCategories.orEmpty(); MediaKind.SERIES -> catalog?.seriesCategories.orEmpty(); else -> emptyList() }
    IosChannelGridView(app, vm, items, categories, favorites, parental, kind, onPlay)
}
