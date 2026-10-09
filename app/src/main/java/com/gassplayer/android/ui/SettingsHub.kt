package com.gassplayer.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gassplayer.android.BuildConfig
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.launch

/**
 * Settings laid out like the iOS `SettingsView`: a hub with the sections Playlist / Avanzate / Rete /
 * Integrazioni / Supporto, and the sub-pages Impostazioni generali, Interfaccia utente, Lettore video,
 * Backup e dispositivi, Metadati, Diagnostica, Informazioni.
 */
@Composable
internal fun SettingsHub(app: GassPlayerApplication, settings: AppSettings, vm: MainViewModel, onOpen: (String) -> Unit) {
    var page by remember { mutableStateOf<String?>(null) }
    var local by remember(settings) { mutableStateOf(settings) }
    fun save(s: AppSettings) { local = s; vm.updateSettings(s) }
    val scope = rememberCoroutineScope()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val active by vm.activeSource.collectAsStateWithLifecycle()
    val trakt by app.prefs.traktFlow.collectAsStateWithLifecycle(TraktAccount())
    var toast by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    confirm?.let { (text, action) ->
        AlertDialog({ confirm = null }, title = { Text("Conferma") }, text = { Text(text, color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = { TextButton({ action(); confirm = null }) { Text("Conferma", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Annulla") } })
    }

    if (page == null) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item { HubSection("Playlist", "Sorgenti e sorgente attiva", Icons.Default.PlaylistPlay) {
                HubRow("Sorgenti", "${sources.size} configurate", Icons.Default.Storage) { onOpen("sources") }
                if (sources.size > 1) HubChoice("Sorgente attiva", sources.firstOrNull { it.id == active }?.name ?: "Nessuna", sources.map { it.name }) { name ->
                    sources.firstOrNull { it.name == name }?.let { vm.setActive(it.id) }
                } else HubRow("Sorgente attiva", sources.firstOrNull { it.id == active }?.name ?: "Nessuna", Icons.Default.CheckCircle) { onOpen("sources") }
                HubRow("Playlist unificate", "Unisci più sorgenti", Icons.Default.Merge) { onOpen("merged") }
                Text("Per configurare Live TV, VOD e Serie TV, apri Sorgenti e tocca “Aggiungi playlist”.", color = glassForeground().copy(.5f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
            } }
            item { HubSection("Avanzate", "Generale, interfaccia, lettore e backup", Icons.Default.Tune) {
                HubRow("Generale", "Catalogo e cronologia", Icons.Default.Settings) { page = "general" }
                HubRow("Interfaccia utente", "Tema e griglia · ${themeLabel(local.theme)}, ${if (local.density == "compact") "compatta" else "comoda"}", Icons.Default.Tune) { page = "ui" }
                HubRow("Lettore video", "Riproduzione, buffer, decodifica e sottotitoli", Icons.Default.PlayCircle) { page = "player" }
                HubRow("Backup", "Esporta e importa", Icons.Default.Cloud) { onOpen("backup") }
                HubRow("EPG", "Fonti EPG esterne", Icons.Default.MenuBook) { onOpen("epg-manage") }
                HubRow("Parental Lock", "Limiti di visione e codice di protezione", Icons.Default.Lock) { onOpen("parental") }
                HubRow("Personalizza Home", "Ordine e visibilità delle sezioni", Icons.Default.Dashboard) { onOpen("home-customize") }
            } }
            item { HubSection("Rete", "VPN, DNS e download", Icons.Default.Wifi) {
                HubRow("VPN personale", "Gestisci connessione e configurazione", Icons.Default.VpnLock) { onOpen("vpn") }
                HubChoice("DNS preferito", dnsLabel(local.preferredDns), listOf("1.1.1.1 · Cloudflare", "8.8.8.8 · Google", "Automatico di sistema")) {
                    save(local.copy(preferredDns = when { it.startsWith("1.1.1.1") -> "1.1.1.1"; it.startsWith("8.8.8.8") -> "8.8.8.8"; else -> "system" }))
                }
                HubToggle("Download solo Wi-Fi", "Evita l’utilizzo della rete cellulare", local.downloadWifiOnly) { save(local.copy(downloadWifiOnly = it)) }
                HubRow("Download", "Gestisci i download", Icons.Default.Download) { onOpen("downloads") }
            } }
            item { HubSection("Integrazioni", "Trakt, TMDB e OMDb", Icons.Default.Extension) {
                HubRow("Trakt.tv", if (trakt.accessToken.isNotBlank()) "Connesso" else "Non connesso", Icons.Default.Verified) { onOpen("trakt") }
                HubRow("TMDB e OMDb", if (local.tmdbApiKey.isNotBlank()) "TMDB configurato" + (if (local.omdbApiKey.isNotBlank()) " · OMDb configurato" else "") else "Nessuna API key configurata", Icons.Default.Key) { page = "metadata" }
            } }
            item { HubSection("Supporto", "Diagnostica e informazioni", Icons.Default.Support) {
                HubRow("Diagnostica", "Log, rete e cache di sistema", Icons.Default.Build) { page = "diagnostics" }
                HubRow("Informazioni", "Versione ${BuildConfig.VERSION_NAME}", Icons.Default.Info) { page = "about" }
            } }
        }
        return
    }

    Column {
        TextButton({ page = null }) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Impostazioni") }
        toast?.let { Text(it, color = Color(0xFF30D158), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            when (page) {
                "general" -> {
                    item { PageTitle("Impostazioni generali") }
                    item { HubSection("Catalogo", "Aggiornamento canali, VOD e serie", Icons.Default.Refresh) {
                        HubChoice("Aggiornamento automatico", intervalLabel(local.catalogRefreshInterval), RefreshInterval.entries.map { intervalLabel(it.id) }) { label ->
                            save(local.copy(catalogRefreshInterval = RefreshInterval.entries.first { intervalLabel(it.id) == label }.id))
                        }
                        HubToggle("Aggiorna all'avvio", null, local.catalogRefreshOnLaunch) { save(local.copy(catalogRefreshOnLaunch = it)) }
                        HubToggle("Programma in corso nelle celle", null, local.showEpgInChannelTiles) { save(local.copy(showEpgInChannelTiles = it)) }
                        HubToggle("Precarica dettagli serie", null, local.preloadSeries) { save(local.copy(preloadSeries = it)) }
                        HubToggle("Aggiornamento EPG automatico", null, local.epgAutoUpdateEnabled) { save(local.copy(epgAutoUpdateEnabled = it)) }
                        HubRow("Aggiorna catalogo ora", "Ricarica live, film e serie", Icons.Default.Refresh) { vm.refresh(true); toast = "Aggiornamento avviato" }
                        HubRow("Svuota cache catalogo", "${app.catalog.cacheCount()} elementi in cache", Icons.Default.DeleteSweep, destructive = true) {
                            confirm = "Svuotare la cache del catalogo? Verrà riscaricato al prossimo aggiornamento." to { app.catalog.clearCache(); toast = "Cache catalogo svuotata"; vm.refresh(true) }
                        }
                    } }
                    item { HubSection("Cronologia", "\"Continua a guardare\" in Home", Icons.Default.History) {
                        HubChoice("Elementi recenti", "${local.historyLimit}", listOf("10", "20", "50", "100")) { save(local.copy(historyLimit = it.toInt())) }
                        HubRow("Svuota cronologia", "Rimuove Continua a guardare e ricerche", Icons.Default.Delete, destructive = true) {
                            confirm = "Svuotare cronologia di visione e ricerche?" to { scope.launch { app.watch.clear(); app.search.clear(); toast = "Cronologia svuotata" } }
                        }
                    } }
                    item { HubSection("User Agent", "Identificativo delle richieste di rete", Icons.Default.Public) {
                        OutlinedTextField(local.customUserAgent, { save(local.copy(customUserAgent = it)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("User Agent") }, placeholder = { Text(NetworkApi.DEFAULT_USER_AGENT) })
                        Text("Vuoto = ${NetworkApi.DEFAULT_USER_AGENT}. La modifica viene applicata alle nuove richieste di rete.", color = glassForeground().copy(.5f), fontSize = 12.sp)
                    } }
                }
                "ui" -> {
                    item { PageTitle("Impostazioni dell'interfaccia utente") }
                    item { HubSection("Aspetto", "Personalizza l’interfaccia", Icons.Default.Palette) {
                        HubChoice("Tema", themeLabel(local.theme), listOf("Sistema", "Chiaro", "Scuro")) { save(local.copy(theme = when (it) { "Chiaro" -> "light"; "Scuro" -> "dark"; else -> "system" })) }
                        HubChoice("Lingua app", local.language, listOf("system", "it", "en", "es")) { save(local.copy(language = it)) }
                    } }
                    item { HubSection("Libreria", "Griglia di canali, film e serie", Icons.Default.GridView) {
                        HubChoice("Densità griglia", when (local.density) { "compact" -> "Compatta"; "poster" -> "Poster"; else -> "Comoda" }, listOf("Compatta", "Comoda", "Poster")) { save(local.copy(density = when (it) { "Compatta" -> "compact"; "Poster" -> "poster"; else -> "comfortable" })) }
                        HubChoice("UI Gruppi", if (local.groupUIStyle == "espansibile") "Espansibile" else "Scorrevole", listOf("Scorrevole", "Espansibile")) { save(local.copy(groupUIStyle = it.lowercase())) }
                        HubToggle("Mostra numero canale", null, local.showChannelNumbers) { save(local.copy(showChannelNumbers = it)) }
                    } }
                    item { HubSection("Guida EPG", "Aspetto della griglia", Icons.Default.CalendarMonth) {
                        HubChoice("Aspetto EPG", if (local.epgLayoutDensity == "compatta") "Compatta" else "Comoda", listOf("Compatta", "Comoda")) { save(local.copy(epgLayoutDensity = it.lowercase().let { v -> if (v == "compatta") "compatta" else "comoda" })) }
                        HubChoice("Assetti EPG", if (local.epgChannelCardStyle == "scheda") "Scheda" else "Griglia", listOf("Griglia", "Scheda")) { save(local.copy(epgChannelCardStyle = it.lowercase())) }
                        HubChoice("Colori EPG", if (local.epgTileColor == "dark") "Dark" else "Dinamico", listOf("Dinamico", "Dark")) { save(local.copy(epgTileColor = if (it == "Dark") "dark" else "dynamic")) }
                    } }
                    item { HubSection("Sottotitoli", "Lingua preferita", Icons.Default.Subtitles) {
                        HubChoice("Lingua sottotitoli", when (local.subtitleLanguage) { "en" -> "English"; "es" -> "Español"; else -> "Italiano" }, listOf("Italiano", "English", "Español")) { save(local.copy(subtitleLanguage = when (it) { "English" -> "en"; "Español" -> "es"; else -> "it" })) }
                    } }
                    item { HubRow("Personalizza Home", "Ordine e visibilità delle sezioni", Icons.Default.Dashboard) { onOpen("home-customize") } }
                }
                "player" -> {
                    item { PageTitle("Impostazioni del player") }
                    item { HubSection("Riproduzione", "Comportamento del player", Icons.Default.PlayArrow) {
                        HubToggle("Prossimo episodio automatico", null, local.autoplayNextEpisode) { save(local.copy(autoplayNextEpisode = it)) }
                        HubToggle("Riprendi la visione", null, local.resumePlayback) { save(local.copy(resumePlayback = it)) }
                        HubChoice("Velocità predefinita", "${local.preferredPlaybackSpeed}×", listOf("1.0×", "1.25×", "1.5×", "2.0×")) { save(local.copy(preferredPlaybackSpeed = it.removeSuffix("×").toFloat())) }
                        HubToggle("Decodifica hardware", null, local.hardwareDecode) { save(local.copy(hardwareDecode = it, softwareDecode = !it)) }
                        HubToggle("Decompressione asincrona", null, local.asyncDecode) { save(local.copy(asyncDecode = it)) }
                        HubChoice("Buffer minimo", "${local.minBufferSec} secondi", listOf("5 secondi", "10 secondi", "15 secondi", "30 secondi", "60 secondi")) { save(local.copy(minBufferSec = it.substringBefore(' ').toInt().coerceAtMost(local.maxBufferSec))) }
                        HubChoice("Buffer di partenza", "${local.playerStartBufferSec} ${if (local.playerStartBufferSec == 1) "secondo (istantaneo)" else "secondi"}", listOf("1 secondo", "2 secondi", "3 secondi", "5 secondi", "8 secondi", "15 secondi")) { save(local.copy(playerStartBufferSec = it.substringBefore(' ').toInt().coerceAtMost(local.maxBufferSec))) }
                        HubChoice("Buffer massimo", "${local.maxBufferSec} secondi", listOf("15 secondi", "30 secondi", "60 secondi", "90 secondi", "120 secondi")) { save(local.copy(maxBufferSec = it.substringBefore(' ').toInt().coerceAtLeast(local.minBufferSec))) }
                        HubToggle("Seek accurato", null, local.accurateSeek) { save(local.copy(accurateSeek = it)) }
                        HubChoice("Adattamento video predefinito", aspectLabel(local.aspectRatio), listOf("Adatta", "Riempi", "Stira")) { save(local.copy(aspectRatio = when (it) { "Riempi" -> "fill"; "Stira" -> "stretch"; else -> "fit" })) }
                        HubRow("Ripristina impostazioni predefinite del player", "Buffer, decodifica, seek, risoluzione e adattamento video", Icons.Default.RestartAlt, destructive = true) {
                            confirm = "Buffer, decodifica, qualità, cache, seek, A/V delay, sottotitoli immagine e opzioni 360° torneranno ai valori di fabbrica. Autoplay del prossimo episodio e ripresa della visione non vengono modificati." to {
                                val d = AppSettings(); save(local.copy(
                                    minBufferSec = d.minBufferSec,
                                    maxBufferSec = d.maxBufferSec,
                                    playerStartBufferSec = d.playerStartBufferSec,
                                    hardwareDecode = d.hardwareDecode,
                                    softwareDecode = d.softwareDecode,
                                    asyncDecode = d.asyncDecode,
                                    accurateSeek = d.accurateSeek,
                                    deinterlace = d.deinterlace,
                                    aspectRatio = d.aspectRatio,
                                    preferredPlaybackSpeed = d.preferredPlaybackSpeed,
                                    adaptiveBitrate = d.adaptiveBitrate,
                                    httpCache = d.httpCache,
                                    audioOnly = d.audioOnly,
                                    preserveImageSubtitles = d.preserveImageSubtitles,
                                    panorama360 = d.panorama360,
                                    autoRotate360 = d.autoRotate360,
                                    loopPlayback = d.loopPlayback,
                                    videoDelayMs = d.videoDelayMs,
                                    ffmpegLowResolution = d.ffmpegLowResolution
                                )); toast = "Player ripristinato"
                            }
                        }
                    } }
                    item { HubSection("Avanzate", "Opzioni tecniche", Icons.Default.Memory) {
                        HubToggle("Adaptive bitrate", null, local.adaptiveBitrate) { save(local.copy(adaptiveBitrate = it)) }
                        HubToggle("Cache HTTP", null, local.httpCache) { save(local.copy(httpCache = it)) }
                        HubToggle("Solo audio", null, local.audioOnly) { save(local.copy(audioOnly = it)) }
                        HubToggle("Mantieni sottotitoli immagine", null, local.preserveImageSubtitles) { save(local.copy(preserveImageSubtitles = it)) }
                        HubToggle("Panorama 360°", null, local.panorama360) { save(local.copy(panorama360 = it)) }
                        HubToggle("Rotazione automatica 360°", null, local.autoRotate360) { save(local.copy(autoRotate360 = it)) }
                        HubToggle("Loop riproduzione", null, local.loopPlayback) { save(local.copy(loopPlayback = it)) }
                        SettingDelayCommit("A/V delay (ms)", local.videoDelayMs, -2_000..2_000, step = 50) { save(local.copy(videoDelayMs = it)) }
                        HubChoice("Risoluzione massima", when (local.ffmpegLowResolution) { "half" -> "Ridotta (max 720p)"; "quarter" -> "Bassa (max 480p)"; else -> "Originale" }, listOf("Originale", "Ridotta (max 720p)", "Bassa (max 480p)")) { save(local.copy(ffmpegLowResolution = when (it) { "Ridotta (max 720p)" -> "half"; "Bassa (max 480p)" -> "quarter"; else -> "full" })) }
                    } }
                }
                "metadata" -> {
                    item { PageTitle("TMDB e OMDb") }
                    item { HubSection("Chiavi API", "Metadati, locandine e sottotitoli", Icons.Default.Key) {
                        OutlinedTextField(local.tmdbApiKey, { save(local.copy(tmdbApiKey = it)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("TMDB API key") })
                        OutlinedTextField(local.omdbApiKey, { save(local.copy(omdbApiKey = it)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("OMDb API key") })
                        OutlinedTextField(local.traktClientId, { save(local.copy(traktClientId = it)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Trakt client ID") })
                        OutlinedTextField(local.traktClientSecret, { save(local.copy(traktClientSecret = it)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Trakt client secret") })
                        OutlinedTextField(local.openSubtitlesApiKey, { save(local.copy(openSubtitlesApiKey = it)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("OpenSubtitles API key") })
                    } }
                }
                "diagnostics" -> {
                    item { PageTitle("Diagnostica") }
                    item { HubSection("Diagnostica", "Strumenti tecnici", Icons.Default.Build) {
                        HubRow("Debug e log", "Console di debug", Icons.Default.BugReport) { onOpen("diagnostics") }
                        HubRow("Svuota cache di sistema", "Cache catalogo ed EPG", Icons.Default.DeleteSweep, destructive = true) {
                            confirm = "Svuotare la cache di sistema (catalogo ed EPG)?" to {
                                app.catalog.clearCache(); app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                                app.filesDir.listFiles()?.filter { it.name.startsWith("epg_") }?.forEach { it.delete() }; toast = "Cache svuotata"
                            }
                        }
                    } }
                }
                "about" -> {
                    item { PageTitle("Informazioni") }
                    item { HubSection("Informazioni", "GassPlayer", Icons.Default.Info) {
                        HubRow("Versione", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Icons.Default.Info) {}
                        HubRow("Identificativo", BuildConfig.APPLICATION_ID, Icons.Default.Badge) {}
                    } }
                }
            }
        }
    }
}

private fun themeLabel(v: String) = when (v) { "light" -> "Chiaro"; "dark" -> "Scuro"; else -> "Sistema" }
private fun dnsLabel(v: String) = when (v) { "1.1.1.1" -> "1.1.1.1 · Cloudflare"; "8.8.8.8" -> "8.8.8.8 · Google"; "system" -> "Automatico di sistema"; else -> v }
private fun aspectLabel(v: String) = when (v) { "fill" -> "Riempi"; "stretch" -> "Stira"; "fit" -> "Adatta"; "original" -> "Adatta"; else -> v }
private fun intervalLabel(id: String) = when (id) {
    "manual" -> "Manuale"; "fifteenMinutes" -> "Ogni 15 minuti"; "thirtyMinutes" -> "Ogni 30 minuti"; "oneHour" -> "Ogni ora"
    "threeHours" -> "Ogni 3 ore"; "sixHours" -> "Ogni 6 ore"; "twelveHours" -> "Ogni 12 ore"; "daily" -> "Ogni giorno"; else -> id
}

@Composable private fun PageTitle(text: String) { Text(text, color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp)) }

@Composable
private fun HubSection(title: String, subtitle: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 21.dp,
        contentPadding = 16.dp
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF3478F6).copy(.17f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color(0xFF9ABEFF), modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, color = glassForeground(), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(subtitle, color = glassForeground().copy(.56f), fontSize = 12.sp)
                }
            }
            content()
        }
    }
}

@Composable
private fun HubRow(title: String, detail: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    var focused by remember(title) { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
    ) {
        LiquidGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, contentPadding = 0.dp, highlighted = focused) {
            Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.error else glassForeground(.82f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, color = if (destructive) MaterialTheme.colorScheme.error else glassForeground(), fontWeight = FontWeight.Medium)
                    if (detail.isNotBlank()) Text(detail, color = glassForeground().copy(.56f), fontSize = 12.sp, maxLines = 2)
                }
                Icon(Icons.Default.ChevronRight, null, tint = glassForeground().copy(.42f))
            }
        }
    }
}

@Composable
private fun HubToggle(title: String, detail: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 14.dp, contentPadding = 0.dp) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = glassForeground(), fontWeight = FontWeight.Medium)
                if (detail != null) Text(detail, color = glassForeground().copy(.56f), fontSize = 12.sp)
            }
            Switch(value, onChange)
        }
    }
}

@Composable
private fun HubChoice(title: String, current: String, choices: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { open = true }, shape = RoundedCornerShape(14.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
            LiquidGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, contentPadding = 0.dp) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = glassForeground(), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(current, color = glassForeground().copy(.67f), maxLines = 1)
                    Spacer(Modifier.width(5.dp)); Icon(Icons.Default.UnfoldMore, null, tint = glassForeground().copy(.48f))
                }
            }
        }
        DropdownMenu(open, { open = false }) { choices.forEach { c -> CheckItem(c, c == current) { onPick(c); open = false } } }
    }
}

@Composable
private fun SettingDelayCommit(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit
) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 14.dp, contentPadding = 12.dp) {
        Column(Modifier.fillMaxWidth()) {
            Text("$title: ${draft.toInt()}", color = glassForeground())
            Slider(
            value = draft.coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { draft = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
                onValueChangeFinished = { onChange(draft.toInt().coerceIn(range.first, range.last)) }
            )
        }
    }
}
