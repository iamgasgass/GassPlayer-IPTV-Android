package com.gassplayer.android.ui.ios

import androidx.compose.runtime.Composable
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.AppSettings
import com.gassplayer.android.data.CatalogState
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.ui.IosPlayerScreen

@Composable
fun PlayerView(app: GassPlayerApplication, item: MediaItem, settings: AppSettings, catalog: CatalogState?, onBack: () -> Unit, onPlayNext: (MediaItem) -> Unit) {
    IosPlayerScreen(app, item, settings, catalog, onBack = onBack, onPip = {}, onNavigateToItem = onPlayNext, onOpenSearch = onBack)
}
