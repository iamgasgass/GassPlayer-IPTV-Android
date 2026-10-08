package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gassplayer.android.data.AppSettings
import com.gassplayer.android.ui.MainViewModel

@Composable
fun IosMetadataSettingsView(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        IosSectionHeader("Metadati", "TMDB, OMDb, Trakt e OpenSubtitles")
        IosTextField(settings.tmdbApiKey, { vm.updateSettings(settings.copy(tmdbApiKey = it)) }, "TMDB API key")
        IosTextField(settings.omdbApiKey, { vm.updateSettings(settings.copy(omdbApiKey = it)) }, "OMDb API key")
        IosTextField(settings.traktClientId, { vm.updateSettings(settings.copy(traktClientId = it)) }, "Trakt client ID")
        IosTextField(settings.traktClientSecret, { vm.updateSettings(settings.copy(traktClientSecret = it)) }, "Trakt client secret", isPassword = true)
        IosTextField(settings.openSubtitlesApiKey, { vm.updateSettings(settings.copy(openSubtitlesApiKey = it)) }, "OpenSubtitles API key")
        IosGlassCard { IosGlassRow(Icons.Default.Info, "Uso delle API", "Le chiavi vengono salvate nelle preferenze protette dell'app.", showChevron = false) }
    }
}
