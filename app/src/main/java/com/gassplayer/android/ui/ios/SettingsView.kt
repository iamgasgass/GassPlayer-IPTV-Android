package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.AppSettings
import com.gassplayer.android.ui.MainViewModel

@Composable
fun IosSettingsDialog(app: GassPlayerApplication, vm: MainViewModel, settings: AppSettings, onDismiss: () -> Unit, onNavigate: (String) -> Unit) {
    var page by remember { mutableStateOf("hub") }
    var local by remember(settings) { mutableStateOf(settings) }
    fun save(next: AppSettings) { local = next; vm.updateSettings(next) }

    IosDialogFrame(if (page == "hub") "Impostazioni" else settingsPageTitle(page), onDismiss = { if (page == "hub") onDismiss() else page = "hub" }) {
        if (page == "hub") {
            LazyColumn(Modifier.heightIn(max = 650.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SettingsHubCard("Playlist", "Sorgenti IPTV, sorgente attiva e gestione contenuti", Icons.Default.SettingsInputAntenna) { onNavigate("sources") } }
                item { SettingsHubCard("Generali", "Aggiornamento catalogo, cronologia, lingua, User-Agent", Icons.Default.Tune) { page = "general" } }
                item { SettingsHubCard("Interfaccia", "Tema, densità griglia, gruppi playlist e Home", Icons.Default.Palette) { page = "interface" } }
                item { SettingsHubCard("Lettore video", "Buffer, decodifica, seek, tracce e aspetto video", Icons.Default.PlayCircle) { page = "player" } }
                item { SettingsHubCard("Guida EPG", "Aspetto griglia, colori, sorgenti esterne", Icons.Default.CalendarMonth) { onNavigate("epg-manage") } }
                item { SettingsHubCard("Rete", "DNS preferito e opzioni di rete", Icons.Default.NetworkCheck) { page = "network" } }
                item { SettingsHubCard("Integrazioni", "TMDB, OMDb, Trakt, OpenSubtitles", Icons.Default.Extension) { page = "integrations" } }
                item { SettingsHubCard("Backup e dispositivi", "Backup JSON, diagnostica dati e backup Android", Icons.Default.CloudUpload) { page = "backup" } }
                item { SettingsHubCard("VPN", "VPN personale integrata", Icons.Default.VpnKey) { onNavigate("vpn") } }
                item { SettingsHubCard("Controllo genitori", "PIN e contenuti protetti", Icons.Default.Lock) { onNavigate("parental") } }
                item { SettingsHubCard("Diagnostica", "Console debug e log", Icons.Default.BugReport) { onNavigate("diagnostics") } }
                item { SettingsHubCard("Informazioni", "Versione, identificativo e supporto", Icons.Default.Info) { page = "about" } }
            }
        } else when (page) {
            "general" -> GeneralSettings(local, ::save)
            "interface" -> InterfaceSettings(local, ::save, onNavigate)
            "player" -> PlayerSettings(local, ::save)
            "network" -> NetworkSettings(local, ::save)
            "integrations" -> IntegrationsSettings(local, ::save, onNavigate)
            "backup" -> BackupSettings(app)
            "about" -> AboutSettings()
        }
    }
}

@Composable
private fun SettingsHubCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    IosGlassCard(padding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)) {
        IosGlassRow(icon, title, subtitle, IosBlue, onClick = onClick)
    }
}

@Composable
private fun GeneralSettings(settings: AppSettings, save: (AppSettings) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { IosSettingsToggleRow(Icons.Default.Refresh, "Aggiorna al lancio", "Ricarica il catalogo quando l'app parte", checked = settings.catalogRefreshOnLaunch, onCheckedChange = { save(settings.copy(catalogRefreshOnLaunch = it)) }) }
        item { IosChoiceCard("Intervallo aggiornamento", intervalLabel(settings.catalogRefreshInterval), listOf("manual" to "Manuale", "fifteenMinutes" to "Ogni 15 minuti", "thirtyMinutes" to "Ogni 30 minuti", "oneHour" to "Ogni ora", "threeHours" to "Ogni 3 ore", "sixHours" to "Ogni 6 ore", "twelveHours" to "Ogni 12 ore", "daily" to "Ogni giorno")) { save(settings.copy(catalogRefreshInterval = it)) } }
        item { IosChoiceCard("Lingua", settings.language, listOf("system" to "Sistema", "it" to "Italiano", "en" to "English", "es" to "Español")) { save(settings.copy(language = it)) } }
        item { IosChoiceCard("Cronologia", "${settings.historyLimit} elementi", listOf("10" to "10 elementi", "20" to "20 elementi", "50" to "50 elementi", "100" to "100 elementi")) { save(settings.copy(historyLimit = it.toInt())) } }
        item { IosTextField(settings.customUserAgent, { save(settings.copy(customUserAgent = it)) }, "User-Agent personalizzato", supportingText = "Lascia vuoto per usare il valore predefinito VLC/LibVLC.") }
        item { IosSettingsToggleRow(Icons.Default.Eye, "Mostra numeri canale", checked = settings.showChannelNumbers, onCheckedChange = { save(settings.copy(showChannelNumbers = it)) }) }
        item { IosSettingsToggleRow(Icons.Default.Subtitles, "Precarica serie", checked = settings.preloadSeries, onCheckedChange = { save(settings.copy(preloadSeries = it)) }) }
    }
}

@Composable
private fun InterfaceSettings(settings: AppSettings, save: (AppSettings) -> Unit, onNavigate: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { IosChoiceCard("Tema", themeLabel(settings.theme), listOf("system" to "Sistema", "light" to "Chiaro", "dark" to "Scuro")) { save(settings.copy(theme = it)) } }
        item { IosChoiceCard("Densità griglia", densityLabel(settings.density), listOf("compact" to "Compatta", "comfortable" to "Comoda", "poster" to "Poster")) { save(settings.copy(density = it)) } }
        item { IosChoiceCard("UI gruppi playlist", if (settings.groupUIStyle == "espansibile") "Espansibile" else "Scorrevole", listOf("scorrevole" to "Scorrevole", "espansibile" to "Espansibile")) { save(settings.copy(groupUIStyle = it)) } }
        item { IosChoiceCard("Layout EPG", if (settings.epgLayoutDensity == "compatta") "Compatto" else "Comodo", listOf("compatta" to "Compatto", "comoda" to "Comodo")) { save(settings.copy(epgLayoutDensity = it)) } }
        item { IosChoiceCard("Scheda canale EPG", if (settings.epgChannelCardStyle == "scheda") "Scheda" else "Griglia", listOf("griglia" to "Griglia", "scheda" to "Scheda")) { save(settings.copy(epgChannelCardStyle = it)) } }
        item { IosChoiceCard("Colori EPG", if (settings.epgTileColor == "dark") "Dark" else "Dinamico", listOf("dynamic" to "Dinamico", "dark" to "Dark")) { save(settings.copy(epgTileColor = it)) } }
        item { IosGlassRow(Icons.Default.Dashboard, "Personalizza Home", "Ordine e visibilità di tutte le sezioni Home", onClick = { onNavigate("home-customize") }) }
    }
}

@Composable
private fun PlayerSettings(settings: AppSettings, save: (AppSettings) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { IosSettingsToggleRow(Icons.Default.PlayArrow, "Riproduzione automatica episodio successivo", checked = settings.autoplayNextEpisode, onCheckedChange = { save(settings.copy(autoplayNextEpisode = it)) }) }
        item { IosSettingsToggleRow(Icons.Default.RestartAlt, "Riprendi la visione", checked = settings.resumePlayback, onCheckedChange = { save(settings.copy(resumePlayback = it)) }) }
        item { IosChoiceCard("Velocità predefinita", "${settings.preferredPlaybackSpeed}×", listOf("1.0" to "1.0×", "1.25" to "1.25×", "1.5" to "1.5×", "2.0" to "2.0×")) { save(settings.copy(preferredPlaybackSpeed = it.toFloat())) } }
        item { IosSettingsToggleRow(Icons.Default.Memory, "Decodifica hardware", checked = settings.hardwareDecode, onCheckedChange = { save(settings.copy(hardwareDecode = it, softwareDecode = !it)) }) }
        item { IosSettingsToggleRow(Icons.Default.Sync, "Decompressione asincrona", checked = settings.asyncDecode, onCheckedChange = { save(settings.copy(asyncDecode = it)) }) }
        item { IosChoiceCard("Buffer iniziale", "${settings.playerStartBufferSec} secondi", listOf("1" to "1 secondo", "3" to "3 secondi", "5" to "5 secondi", "8" to "8 secondi", "15" to "15 secondi")) { save(settings.copy(playerStartBufferSec = it.toInt().coerceAtMost(settings.maxBufferSec))) } }
        item { IosChoiceCard("Buffer massimo", "${settings.maxBufferSec} secondi", listOf("15" to "15 secondi", "30" to "30 secondi", "60" to "60 secondi", "90" to "90 secondi", "120" to "120 secondi")) { save(settings.copy(maxBufferSec = it.toInt().coerceAtLeast(settings.playerStartBufferSec))) } }
        item { IosSettingsToggleRow(Icons.Default.MyLocation, "Seek accurato", checked = settings.accurateSeek, onCheckedChange = { save(settings.copy(accurateSeek = it)) }) }
        item { IosSettingsToggleRow(Icons.Default.Tune, "Deinterlacciamento automatico", checked = settings.deinterlace, onCheckedChange = { save(settings.copy(deinterlace = it)) }) }
        item { IosChoiceCard("Adattamento video", aspectLabel(settings.aspectRatio), listOf("fit" to "Adatta", "fill" to "Riempi", "stretch" to "Stira")) { save(settings.copy(aspectRatio = it)) } }
        item { IosSettingsToggleRow(Icons.Default.Loop, "Loop riproduzione", checked = settings.loopPlayback, onCheckedChange = { save(settings.copy(loopPlayback = it)) }) }
        item { IosSettingsToggleRow(Icons.Default.NetworkCheck, "Adaptive bitrate", checked = settings.adaptiveBitrate, onCheckedChange = { save(settings.copy(adaptiveBitrate = it)) }) }
        item { IosSettingsToggleRow(Icons.Default.Storage, "Cache HTTP", checked = settings.httpCache, onCheckedChange = { save(settings.copy(httpCache = it)) }) }
        item { IosSettingsToggleRow(Icons.Default.Headset, "Solo audio", checked = settings.audioOnly, onCheckedChange = { save(settings.copy(audioOnly = it)) }) }
        item { IosTextField(settings.ffmpegOptions, { save(settings.copy(ffmpegOptions = it)) }, "Opzioni FFmpeg") }
        item { IosTextField(settings.ffmpegFilters, { save(settings.copy(ffmpegFilters = it)) }, "Filtri FFmpeg") }
    }
}

@Composable
private fun NetworkSettings(settings: AppSettings, save: (AppSettings) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { IosChoiceCard("DNS preferito", dnsLabel(settings.preferredDns), listOf("1.1.1.1" to "1.1.1.1 · Cloudflare", "8.8.8.8" to "8.8.8.8 · Google", "system" to "Automatico di sistema")) { save(settings.copy(preferredDns = it)) } }
        item { IosSettingsToggleRow(Icons.Default.Wifi, "Download solo Wi‑Fi", checked = settings.downloadWifiOnly, onCheckedChange = { save(settings.copy(downloadWifiOnly = it)) }) }
    }
}

@Composable
private fun IntegrationsSettings(settings: AppSettings, save: (AppSettings) -> Unit, onNavigate: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { IosGlassCard { IosSectionHeader("Metadati", "API usate per locandine, rating e dettagli") } }
        item { IosTextField(settings.tmdbApiKey, { save(settings.copy(tmdbApiKey = it)) }, "TMDB API key") }
        item { IosTextField(settings.omdbApiKey, { save(settings.copy(omdbApiKey = it)) }, "OMDb API key") }
        item { IosGlassRow(Icons.Default.TheaterComedy, "Trakt", "Connessione account e scrobbling", onClick = { onNavigate("trakt") }) }
        item { IosTextField(settings.traktClientId, { save(settings.copy(traktClientId = it)) }, "Trakt client ID") }
        item { IosTextField(settings.traktClientSecret, { save(settings.copy(traktClientSecret = it)) }, "Trakt client secret", isPassword = true) }
        item { IosTextField(settings.openSubtitlesApiKey, { save(settings.copy(openSubtitlesApiKey = it)) }, "OpenSubtitles API key") }
        item { IosGlassRow(Icons.Default.Subtitles, "OpenSubtitles", "Lingua: ${settings.subtitleLanguage}") }
    }
}

@Composable
private fun BackupSettings(app: GassPlayerApplication) {
    var text by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IosGlassPrimaryButton("Esporta sorgenti + preferenze", Icons.Default.FileUpload, onClick = { scope.launch { text = app.backup.fullExport() } })
        IosGlassPrimaryButton("Sincronizza backup Android", Icons.Default.CloudUpload, onClick = { scope.launch { app.cloud.requestBackup(); text = "Backup Android richiesto" } })
        IosGlassRow(Icons.Default.Info, "Importazione", "Per importare un JSON usa la sezione Sorgenti → Backup / importazione", showChevron = false)
        if (text != null) IosTextField(text.orEmpty(), { }, "Output", singleLine = false)
    }
}

@Composable
private fun AboutSettings() {
    IosGlassCard { IosSectionHeader("GassPlayer", "Port Android fedele alle viste SwiftUI originali")
        Spacer(Modifier.height(10.dp))
        Text("Versione Android: ${com.gassplayer.android.BuildConfig.VERSION_NAME}", fontSize = 13.sp)
        Text("Package: ${com.gassplayer.android.BuildConfig.APPLICATION_ID}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
        Spacer(Modifier.height(8.dp))
        Text("UI portata con Compose, Material3 e un design system glass condiviso.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f))
    }
}

@Composable
private fun IosChoiceCard(title: String, current: String, options: List<Pair<String, String>>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IosGlassCard(padding = PaddingValues(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium); Text(current, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)) }
            IosGlassIconButton(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Seleziona", onClick = { expanded = !expanded }, size = 38)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { options.forEach { (value, label) -> Surface(onClick = { onSelected(value); expanded = false }, color = if (value == current) IosBlue.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, shape = RoundedCornerShape(10.dp)) { Text(label, modifier = Modifier.fillMaxWidth().padding(11.dp), fontSize = 13.sp) } } }
        }
    }
}

private fun settingsPageTitle(page: String) = when (page) {
    "general" -> "Impostazioni generali"
    "interface" -> "Impostazioni interfaccia"
    "player" -> "Impostazioni player"
    "network" -> "Impostazioni rete"
    "integrations" -> "Integrazioni"
    "backup" -> "Backup e dispositivi"
    "about" -> "Informazioni"
    else -> "Impostazioni"
}
private fun themeLabel(v: String) = when (v) { "light" -> "Chiaro"; "dark" -> "Scuro"; else -> "Sistema" }
private fun densityLabel(v: String) = when (v) { "compact" -> "Compatta"; "poster" -> "Poster"; else -> "Comoda" }
private fun intervalLabel(v: String) = when (v) { "manual" -> "Manuale"; "fifteenMinutes" -> "Ogni 15 minuti"; "thirtyMinutes" -> "Ogni 30 minuti"; "oneHour" -> "Ogni ora"; "threeHours" -> "Ogni 3 ore"; "sixHours" -> "Ogni 6 ore"; "twelveHours" -> "Ogni 12 ore"; "daily" -> "Ogni giorno"; else -> v }
private fun dnsLabel(v: String) = when (v) { "1.1.1.1" -> "1.1.1.1 · Cloudflare"; "8.8.8.8" -> "8.8.8.8 · Google"; "system" -> "Automatico di sistema"; else -> v }
private fun aspectLabel(v: String) = when (v) { "fill" -> "Riempi"; "stretch" -> "Stira"; else -> "Adatta" }
