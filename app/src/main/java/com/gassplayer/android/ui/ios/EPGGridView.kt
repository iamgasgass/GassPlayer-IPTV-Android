package com.gassplayer.android.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import com.gassplayer.android.ui.MainViewModel

@Composable
fun EPGGridView(app: GassPlayerApplication, vm: MainViewModel, onPlay: (MediaItem) -> Unit) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    EpgGridScreenCompat(app, vm, catalog, favorites, settings, onPlay)
}
