package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gassplayer.android.data.AppSettings
import com.gassplayer.android.ui.MainViewModel

@Composable
fun IosBufferSettingsView(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        IosSectionHeader("Buffer", "Regola latenza e stabilità dello stream")
        IosGlassCard { IosChoiceCardInlineBuffer("Buffer iniziale", settings.playerStartBufferSec, listOf(1, 3, 5, 8, 15)) { vm.updateSettings(settings.copy(playerStartBufferSec = it.coerceAtMost(settings.maxBufferSec))) } }
        IosGlassCard { IosChoiceCardInlineBuffer("Buffer massimo", settings.maxBufferSec, listOf(15, 30, 60, 90, 120)) { vm.updateSettings(settings.copy(maxBufferSec = it.coerceAtLeast(settings.playerStartBufferSec))) } }
    }
}

@Composable
private fun IosChoiceCardInlineBuffer(title: String, current: Int, values: List<Int>, onChange: (Int) -> Unit) {
    IosSectionHeader(title, "Valore attuale: $current s")
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { values.forEach { value -> IosChip("${value}s", current == value) { onChange(value) } } }
}
