package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.data.AppSettings
import com.gassplayer.android.ui.MainViewModel

@Composable
fun IosHomeCustomizationView(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var local by remember(settings) { mutableStateOf(settings) }
    val labels = mapOf(
        "heading" to "Intestazione",
        "search" to "Ricerca",
        "continueWatching" to "Continua a guardare",
        "sourceCard" to "Scheda sorgente",
        "sources" to "Sorgenti",
        "liveTV" to "Live TV",
        "guidaTV" to "Guida TV",
        "favoriteChannels" to "Canali preferiti",
        "favoriteSeries" to "Serie TV preferite",
        "favoriteMovies" to "Film preferiti",
        "trendingSeries" to "Serie di tendenza",
        "trendingMovies" to "Film di tendenza",
        "onDemand" to "On demand",
        "categories" to "Categorie"
    )
    Column(Modifier.fillMaxSize()) {
        IosSectionHeader("Personalizza Home", "Mostra, nascondi e riordina ogni sezione della Home")
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            itemsIndexed(local.homeSectionOrder, key = { _, id -> id }) { index, id ->
                IosGlassCard(padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = id !in local.hiddenHomeSections,
                            onCheckedChange = { visible ->
                                local = local.copy(hiddenHomeSections = if (visible) local.hiddenHomeSections - id else local.hiddenHomeSections + id)
                                vm.updateSettings(local)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(labels[id] ?: id, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            Text("${index + 1} · ${if (id in local.hiddenHomeSections) "nascosta" else "visibile"}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        IosGlassIconButton(Icons.Default.KeyboardArrowUp, "Sposta su", onClick = {
                            if (index > 0) { val list = local.homeSectionOrder.toMutableList(); val x = list.removeAt(index); list.add(index - 1, x); local = local.copy(homeSectionOrder = list); vm.updateSettings(local) }
                        }, size = 38)
                        Spacer(Modifier.width(4.dp))
                        IosGlassIconButton(Icons.Default.KeyboardArrowDown, "Sposta giù", onClick = {
                            if (index < local.homeSectionOrder.lastIndex) { val list = local.homeSectionOrder.toMutableList(); val x = list.removeAt(index); list.add(index + 1, x); local = local.copy(homeSectionOrder = list); vm.updateSettings(local) }
                        }, size = 38)
                    }
                }
            }
            item {
                IosGlassCard {
                    IosChoiceCardInline("Continua a guardare", listOf("all" to "Tutti i tipi", "live" to "Solo Live", "movie" to "Solo Film", "series" to "Solo Serie"))
                }
            }
        }
    }
}

@Composable
private fun IosChoiceCardInline(title: String, options: List<Pair<String, String>>) {
    Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    var selected by remember { mutableStateOf(options.first().first) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (id, label) -> IosChip(label, selected == id, onClick = { selected = id }) }
    }
}
