package com.gassplayer.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.rememberTextMeasurer
import coil3.compose.AsyncImage
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Time-grid EPG, a faithful Android port of the iOS `EPGGridView` geometry:
 *  - densities "compatta" (row 66, banner 80x58, header 40) / "comoda" (row 96, banner 86x76, header 44)
 *  - 160 dp every 30 minutes (5.333 dp/min), window = 30 min past .. 180 min future (day views centred on 12:00)
 *  - fixed channel column + horizontally scrolling programme tiles, live axis, quality badge pill, catch-up/favourite marks
 *  - "…" menu: Aspetto EPG, Assetti EPG, Colori EPG, Aggiorna guida, Solo preferiti, Ieri/Oggi/Domani
 *  - 32 channels per page with "Carica altri N canali" (hard cap 250), 24 concurrent short-EPG requests
 */
private const val PAGE = 32
private const val HARD_CAP = 250
private const val MAX_CONCURRENT = 24
private const val SHORT_LIMIT = 24
private val PAST_MS = 30 * 60_000L
private val FUTURE_MS = 180 * 60_000L

private val DarkTileBase = Color(0.13f, 0.13f, 0.14f)
private val DarkBanner = Color(0.09f, 0.09f, 0.10f)
private val PastelPalette = listOf(
    Color(0.86f, 0.32f, 0.36f), Color(0.88f, 0.45f, 0.32f), Color(0.85f, 0.65f, 0.26f), Color(0.32f, 0.70f, 0.52f),
    Color(0.28f, 0.64f, 0.78f), Color(0.35f, 0.52f, 0.88f), Color(0.58f, 0.42f, 0.78f), Color(0.78f, 0.38f, 0.66f)
)
private val QUALITY_TOKENS = listOf("FULL HD", "4K", "FHD", "HD", "SD")

private fun pastelFor(item: MediaItem): Color {
    val n = item.title.lowercase(Locale.ROOT)
    return when {
        "rai 1" in n || "rai1" in n -> Color(0.88f, 0.28f, 0.34f)
        "rai 2" in n || "rai2" in n -> Color(0.89f, 0.38f, 0.28f)
        "rai 3" in n || "rai3" in n -> Color(0.28f, 0.68f, 0.48f)
        "rai 4" in n || "rai4" in n -> Color(0.58f, 0.36f, 0.72f)
        "rai news" in n || "rainews" in n -> Color(0.24f, 0.54f, 0.82f)
        "rai sport" in n -> Color(0.86f, 0.62f, 0.22f)
        "canale 5" in n || "mediaset" in n || "italia 1" in n -> Color(0.22f, 0.58f, 0.86f)
        "sky" in n -> Color(0.26f, 0.52f, 0.88f)
        "dazn" in n || "sport" in n -> Color(0.82f, 0.74f, 0.28f)
        "cinema" in n || "film" in n || "movie" in n -> Color(0.72f, 0.32f, 0.58f)
        "private" in n -> Color(0.85f, 0.30f, 0.36f)
        else -> PastelPalette[Math.abs(item.title.hashCode() xor item.id.hashCode()) % PastelPalette.size]
    }
}

/** Splits "Rai 1 HD" into ("Rai 1", "HD"); unknown suffixes (RAW, HEVC) stay in the name. */
private fun splitQuality(name: String): Pair<String, String?> {
    val trimmed = name.trim()
    val upper = trimmed.uppercase(Locale.ROOT)
    for (token in QUALITY_TOKENS) {
        if (upper.endsWith(" $token") || upper == token) {
            val base = trimmed.dropLast(token.length).trim()
            if (base.isNotEmpty()) return base to (if (token == "FULL HD") "FHD" else token)
        }
    }
    return trimmed to null
}

private class EpgStore {
    val programs: SnapshotStateMap<String, List<EpgProgram>> = mutableStateMapOf()
    val loading: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val failed: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpgGridScreen(
    app: GassPlayerApplication,
    vm: MainViewModel,
    live: List<MediaItem>,
    categories: List<Category>,
    favorites: FavoriteState,
    settings: AppSettings,
    onPlay: (MediaItem) -> Unit
) {
    val scope = rememberCoroutineScope()
    val store = remember { EpgStore() }
    val sources by app.sources.sources.collectAsState(emptyList())
    val external by app.externalEpg.flow.collectAsState(emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dayOffset by remember { mutableIntStateOf(0) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearch by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var renderLimit by remember { mutableIntStateOf(PAGE) }
    var selectedProgram by remember { mutableStateOf<Pair<MediaItem, EpgProgram>?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val compact = settings.epgLayoutDensity == "compatta"
    val cardStyleGrid = settings.epgChannelCardStyle != "scheda"
    val darkTiles = settings.epgTileColor == "dark"
    val bannerInset = if (compact) 10.dp else 12.dp
    val bannerW = if (compact) 80.dp else 86.dp
    val bannerH = if (compact) 58.dp else 76.dp
    val rowH = if (compact) 66.dp else 96.dp
    val headerH = if (compact) 40.dp else 44.dp
    val bannerColumnW = if (cardStyleGrid) bannerInset + bannerW + bannerInset else bannerInset + bannerW
    val ppm = 160.dp / 30f
    val tileGap = 4.dp
    val cornerRadius = if (compact) 12.dp else 18.dp

    // Keep the live arrow / progress fill moving like iOS regardless of whether
    // automatic guide refresh is enabled.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(settings.epgAutoUpdateEnabled) {
        if (settings.epgAutoUpdateEnabled) {
            while (true) {
                delay(5 * 60_000L)
                refreshTick++
            }
        }
    }

    val isToday = dayOffset == 0
    val windowCenter = remember(now, dayOffset) {
        if (isToday) now else Calendar.getInstance().apply {
            timeInMillis = now; add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val windowStart = windowCenter - PAST_MS
    val windowEnd = windowCenter + FUTURE_MS
    val gridOrigin = remember(windowStart) {
        Calendar.getInstance().apply {
            timeInMillis = windowStart
            set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    fun xFor(ms: Long): Dp = ppm * ((ms - gridOrigin) / 60_000f)
    // iOS gives each half-hour tick a full 160 pt segment, including the last tick's
    // trailing segment. Keep that width discrete (rather than shrinking/growing each minute)
    // so the horizontal scroll range and all row offsets match the SwiftUI grid.
    val halfHourTickCount = ((windowEnd - gridOrigin) / (30 * 60_000L)).toInt() + 1
    val canvasW = maxOf(160.dp * halfHourTickCount.toFloat(), 500.dp)
    val liveAxisX = xFor(windowCenter)

    // --- group filter -----------------------------------------------------------------------
    fun keyOf(i: MediaItem) = i.categoryId ?: i.group
    val counts = remember(live) { live.categoryCounts() }
    val groups = remember(categories, live) {
        val present = counts.keys.filterNotNull().toSet()
        categories.filter { it.id in present }.distinctBy { it.id }
    }
    val uncategorized = counts[null] ?: 0
    val groupName = when (groupId) {
        null -> "Tutti i canali"
        "__none__" -> "Senza categoria"
        else -> groups.firstOrNull { it.id == groupId }?.name ?: "Tutti i canali"
    }
    LaunchedEffect(searchQuery) {
        delay(120)
        debouncedSearch = searchQuery.trim()
    }
    val normalizedSearch = debouncedSearch
    val channels = remember(live, groupId, favoritesOnly, favorites, normalizedSearch) {
        // The iOS grid filters channel names before paging (the placeholder mentions programmes,
        // but the original implementation actually matches the stream/channel name).
        val grouped = if (favoritesOnly) {
            live.onlyIds(favorites.live).filter {
                groupId == null || (groupId == "__none__" && keyOf(it) == null) || keyOf(it) == groupId
            }
        } else live.inCategory(groupId)
        if (normalizedSearch.isEmpty()) grouped
        else grouped.matchingTitle(normalizedSearch)
    }
    LaunchedEffect(groupId, favoritesOnly, normalizedSearch, dayOffset) { renderLimit = PAGE }
    val visible = channels.take(minOf(renderLimit, HARD_CAP))

    // --- XMLTV fallback index (external sources + each Xtream source's xmltv.php), loaded lazily ----
    val xmltvIndex by produceState<Map<String, List<EpgProgram>>>(emptyMap(), external, sources, refreshTick) {
        value = withContext(Dispatchers.IO) {
            val all = ArrayList<EpgProgram>()
            for (e in external.filter { it.isEnabled }) runCatching { app.epg.xmltv(e.urlString, e.id) }.getOrNull()?.let(all::addAll)
            if (all.isEmpty()) {
                val used = live.sourceIds()
                for (s in sources.filter { it.type == SourceType.XTREAM && it.id in used && it.isEnabled }) {
                    runCatching { app.epg.xtreamXmltv(s) }.getOrNull()?.let(all::addAll)
                }
            }
            all.groupBy { it.streamId.lowercase(Locale.ROOT) }
        }
    }

    // --- per-channel loading ----------------------------------------------------------------------
    LaunchedEffect(visible.map { it.id }, refreshTick, xmltvIndex, dayOffset) {
        val sem = Semaphore(MAX_CONCURRENT)
        coroutineScope {
            visible.filter { store.programs[it.id] == null }.map { ch ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        store.loading[ch.id] = true
                        val source = sources.firstOrNull { it.id == ch.sourceId }
                        var found = emptyList<EpgProgram>()
                        var requestFailed = false
                        if (source?.type == SourceType.XTREAM) {
                            val result = runCatching { app.epg.shortEpg(source, ch.id.substringAfterLast(':'), SHORT_LIMIT) }
                            found = result.getOrDefault(emptyList())
                            requestFailed = result.isFailure
                        }
                        if (found.isEmpty()) {
                            val key = (ch.metadataTag?.takeIf { it.isNotBlank() } ?: ch.id.substringAfterLast(':')).lowercase(Locale.ROOT)
                            found = xmltvIndex[key]
                                ?: xmltvIndex[ch.title.lowercase(Locale.ROOT)]
                                ?: emptyList()
                        }
                        store.programs[ch.id] = found.sortedBy { it.startMs }
                        if (requestFailed && found.isEmpty()) store.failed[ch.id] = true else store.failed.remove(ch.id)
                        store.loading.remove(ch.id)
                    }
                }
            }.awaitAll()
        }
    }

    // One shared horizontal position synchronises the time axis and every channel row.
    // The iOS guide starts at the left edge of its -30 min window, so we intentionally do not
    // auto-jump to the live marker when the screen opens.
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val scrollOffsetDp = with(density) { hScroll.value.toDp() }
    val bleed = if (cardStyleGrid) 0.dp else bannerW / 2f
    val dayTitle = when (dayOffset) {
        0 -> "Oggi"
        1 -> "Domani"
        -1 -> "Ieri"
        else -> SimpleDateFormat("EEEE d MMM", Locale.ITALIAN).format(Date(windowCenter))
    }

    Column(Modifier.fillMaxSize()) {
        EpgTopBar(
            groupName = groupName, groups = groups, counts = counts, uncategorized = uncategorized,
            onGroup = { groupId = it; searchQuery = ""; favoritesOnly = false },
            settings = settings, favoritesOnly = favoritesOnly,
            onSettings = { vm.updateSettings(it) },
            onRefresh = {
                toast = "Aggiornamento guida in corso…"
                store.programs.clear()
                store.loading.clear()
                store.failed.clear()
                refreshTick++
            },
            onFavoritesOnly = { favoritesOnly = !favoritesOnly },
            onDay = {
                if (dayOffset != it) {
                    store.programs.clear()
                    store.loading.clear()
                    store.failed.clear()
                    dayOffset = it
                }
                if (it == 0) now = System.currentTimeMillis()
            }
        )
        toast?.let { msg ->
            LaunchedEffect(msg) { delay(2500); toast = null }
            Text(msg, color = glassForeground().copy(.85f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        if (live.isEmpty()) {
            Text("Nessun canale live disponibile. Aggiungi una sorgente e attendi il caricamento.", color = glassForeground().copy(.6f), modifier = Modifier.padding(16.dp))
            return@Column
        }

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (compact) 24.dp else 32.dp)
        ) {
            item(key = "epg-search") {
                EpgSearchHeader(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    compact = compact,
                    horizontalInset = bannerInset
                )
            }
            // Like iOS, the day + time header is part of the vertical scroll content (not sticky).
            item(key = "epg-timeline-header") {
                Row(Modifier.fillMaxWidth().background(Color.Black).height(headerH)) {
                    Box(
                        Modifier.width(bannerColumnW).fillMaxHeight().background(Color.Black)
                            .combinedClickable(onClick = {
                                store.programs.clear()
                                store.loading.clear()
                                store.failed.clear()
                                dayOffset = (dayOffset - 1).coerceAtLeast(-7)
                            }),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            dayTitle,
                            color = glassForeground(),
                            fontSize = if (compact) 24.sp else 28.sp,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = bannerInset)
                        )
                    }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().offset(x = -bleed).clipToBounds().horizontalScroll(hScroll)
                    ) {
                        Box(Modifier.width(canvasW + bleed).fillMaxHeight()) {
                            val fmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
                            var tick = gridOrigin
                            while (tick <= windowEnd) {
                                Text(
                                    fmt.format(Date(tick)),
                                    color = glassForeground().copy(if (compact) .60f else .58f),
                                    fontSize = if (compact) 20.sp else 22.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = if (compact) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    modifier = Modifier.offset(x = xFor(tick)).align(Alignment.CenterStart)
                                )
                                tick += 30 * 60_000L
                            }
                            if (isToday) {
                                val arrowSize = if (compact) 15.dp else 20.dp
                                Canvas(
                                    modifier = Modifier.offset(x = (liveAxisX - arrowSize / 2f).coerceAtLeast(0.dp))
                                        .align(Alignment.CenterStart).size(arrowSize)
                                ) {
                                    val triangle = Path().apply {
                                        moveTo(0f, 0f)
                                        lineTo(size.width, 0f)
                                        lineTo(size.width / 2f, size.height)
                                        close()
                                    }
                                    drawPath(triangle, Color.White)
                                }
                            }
                        }
                    }
                }
            }
            itemsIndexed(visible, key = { _, c -> c.id }) { _, ch ->
                val programs = store.programs[ch.id].orEmpty().sortedBy { it.startMs }
                Row(Modifier.fillMaxWidth().height(rowH)) {
                    ChannelBanner(
                        ch,
                        favorite = ch.id in favorites.live,
                        hasCatchup = programs.any { it.hasArchive && it.endMs > windowStart && it.startMs < windowEnd },
                        width = bannerColumnW,
                        bannerW = bannerW,
                        bannerH = bannerH,
                        rowHeight = rowH,
                        inset = bannerInset,
                        centered = cardStyleGrid,
                        dark = darkTiles,
                        compact = compact,
                        onClick = { onPlay(ch) },
                        onLongClick = { vm.toggleFavorite(ch) }
                    )
                    Box(
                        Modifier.weight(1f).fillMaxHeight().offset(x = -bleed).clipToBounds().horizontalScroll(hScroll)
                    ) {
                        Box(Modifier.width(canvasW + bleed).fillMaxHeight()) {
                            val inWindow = programs.filter { it.endMs > windowStart && it.startMs < windowEnd }
                            if (inWindow.isEmpty()) {
                                val label = when {
                                    store.loading[ch.id] == true -> "Caricamento EPG"
                                    store.failed[ch.id] == true -> "⚠ EPG non disponibile"
                                    else -> "Dati non disponibili"
                                }
                                Box(
                                    modifier = Modifier.offset(x = 4.dp).width((canvasW - 8.dp).coerceAtLeast(0.dp))
                                        .height(bannerH).align(Alignment.CenterStart)
                                        .clip(RoundedCornerShape(cornerRadius))
                                        .background(if (darkTiles) DarkTileBase else Color(0.10f, 0.10f, 0.10f)),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(label, color = glassForeground().copy(.65f), fontSize = if (compact) 14.sp else 15.sp,
                                        fontFamily = FontFamily.SansSerif,
                                        modifier = Modifier.padding(horizontal = if (compact) 14.dp else 18.dp))
                                }
                            } else {
                                inWindow.forEachIndexed { index, program ->
                                    val startX = xFor(maxOf(program.startMs, windowStart))
                                    val calculatedEndX = xFor(minOf(program.endMs, windowEnd))
                                    val nextStart = inWindow.getOrNull(index + 1)?.startMs
                                    val endX = if (nextStart != null) {
                                        minOf(calculatedEndX, xFor(maxOf(nextStart, windowStart)))
                                    } else calculatedEndX
                                    val fullWidth = (endX - startX).coerceAtLeast(32.dp)
                                    val visibleWidth = (fullWidth - tileGap).coerceAtLeast(0.dp)
                                    val tileScreenX = bannerColumnW - bleed + startX - scrollOffsetDp
                                    val pinnedNameX = bannerColumnW - tileScreenX
                                    val stickyContentX = pinnedNameX.coerceAtLeast(0.dp).coerceAtMost(visibleWidth)
                                    val keepsNaturalCurrentPosition = index == 0 && tileScreenX > bannerColumnW
                                    val brightWidth = if (isToday) (liveAxisX - startX).coerceIn(0.dp, visibleWidth) else 0.dp
                                    val base = pastelFor(ch)

                                    EpgProgramTile(
                                        channel = ch,
                                        program = program,
                                        compact = compact,
                                        fullWidth = fullWidth,
                                        visibleWidth = visibleWidth,
                                        height = bannerH,
                                        startX = startX,
                                        pinnedNameX = pinnedNameX,
                                        stickyContentX = stickyContentX,
                                        keepsNaturalCurrentPosition = keepsNaturalCurrentPosition,
                                        brightWidth = brightWidth,
                                        color = base,
                                        dark = darkTiles,
                                        onClick = { selectedProgram = ch to program }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (channels.size > visible.size && visible.size < HARD_CAP) item(key = "epg-load-more") {
                val remaining = channels.size - visible.size
                TextButton({ renderLimit = minOf(renderLimit + PAGE, channels.size, HARD_CAP) }, Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(Icons.Default.ArrowCircleDown, null); Spacer(Modifier.width(8.dp)); Text("Carica altri ${minOf(PAGE, remaining)} canali")
                }
            }
        }
    }

    selectedProgram?.let { (ch, p) ->
        val tf = remember { SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { selectedProgram = null },
            title = { Text(p.title.ifBlank { ch.title }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${ch.title} • ${tf.format(Date(p.startMs))} – ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(p.endMs))}", fontSize = 13.sp)
                    p.description?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 8, overflow = TextOverflow.Ellipsis) }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.startMs > now) TextButton({ app.reminders.schedule(p); toast = "Promemoria impostato"; selectedProgram = null }) { Text("Ricordami") }
                    if (p.hasArchive && p.endMs < now) TextButton({
                        scope.launch {
                            val src = sources.firstOrNull { it.id == ch.sourceId }
                            if (src != null) {
                                val url = app.epg.catchUpUrl(src, ch.id.substringAfterLast(':'), p.startMs, p.endMs)
                                onPlay(ch.copy(streamUrl = url, title = "${ch.title} · ${p.title}", kind = MediaKind.EPISODE))
                            }
                            selectedProgram = null
                        }
                    }) { Text("Catch-up") }
                    Button({ onPlay(ch); selectedProgram = null }) { Text("Guarda") }
                }
            },
            dismissButton = { TextButton({ selectedProgram = null }) { Text("Chiudi") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelBanner(
    ch: MediaItem,
    favorite: Boolean,
    hasCatchup: Boolean,
    width: Dp,
    bannerW: Dp,
    bannerH: Dp,
    rowHeight: Dp,
    inset: Dp,
    centered: Boolean,
    dark: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val cardLeft = if (centered) (width - bannerW) / 2 else inset
    val badgeSize = if (compact) 26.dp else 30.dp
    Box(Modifier.width(width).height(rowHeight).zIndex(10f)) {
        Box(
            Modifier.offset(x = cardLeft, y = (rowHeight - bannerH) / 2f).width(bannerW).height(bannerH)
                .clip(RoundedCornerShape(if (compact) 14.dp else 16.dp))
                .background(if (dark) DarkBanner else pastelFor(ch))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            if (!ch.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ch.logoUrl,
                    contentDescription = ch.title,
                    modifier = Modifier.fillMaxSize().padding(if (compact) 8.dp else 12.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Icon(
                    Icons.Default.LiveTv,
                    contentDescription = "Canale TV",
                    tint = glassForeground(),
                    modifier = Modifier.size(if (compact) 22.dp else 26.dp).align(Alignment.Center)
                )
            }
            if (favorite) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(if (compact) 18.dp else 20.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(.34f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Star, null, tint = Color(0xFFFFD60A), modifier = Modifier.size(if (compact) 11.dp else 12.dp))
                }
            }
        }
        if (hasCatchup) {
            Box(
                Modifier.offset(x = cardLeft + bannerW - badgeSize / 2f + bannerW * .14f, y = (rowHeight - badgeSize) / 2f)
                    .size(badgeSize).clip(RoundedCornerShape(50)).background(Color(0xFF4D4D4D)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.History, null, tint = glassForeground(), modifier = Modifier.size(if (compact) 14.dp else 16.dp))
            }
        }
    }
}

@Composable
private fun EpgSearchHeader(
    value: String,
    onValueChange: (String) -> Unit,
    compact: Boolean,
    horizontalInset: Dp
) {
    EpgStyleSearchField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalInset).padding(top = 10.dp, bottom = if (compact) 16.dp else 18.dp),
        placeholder = "Cerca per nome del programma"
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpgProgramTile(
    channel: MediaItem,
    program: EpgProgram,
    compact: Boolean,
    fullWidth: Dp,
    visibleWidth: Dp,
    height: Dp,
    startX: Dp,
    pinnedNameX: Dp,
    stickyContentX: Dp,
    keepsNaturalCurrentPosition: Boolean,
    brightWidth: Dp,
    color: Color,
    dark: Boolean,
    onClick: () -> Unit
) {
    val (baseName, quality) = remember(channel.title) { splitQuality(channel.title) }
    val textMeasurer = rememberTextMeasurer()
    val nameStyle = TextStyle(fontSize = if (compact) 13.sp else 11.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
    val badgeStyle = TextStyle(fontSize = if (compact) 11.sp else 10.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
    // Text widths are invariant during a horizontal drag; memoising them avoids expensive
    // remeasurement on every scroll frame and keeps the sticky-name animation smooth.
    val measuredNamePx = remember(textMeasurer, baseName, nameStyle) {
        textMeasurer.measure(AnnotatedString(baseName), style = nameStyle, maxLines = 1).size.width
    }
    val measuredBadgePx = remember(textMeasurer, quality, badgeStyle) {
        quality?.let { textMeasurer.measure(AnnotatedString(it), style = badgeStyle, maxLines = 1).size.width } ?: 0
    }
    val labelWidth = with(LocalDensity.current) { measuredNamePx.toDp() } +
        (if (quality != null) with(LocalDensity.current) { measuredBadgePx.toDp() } + 10.dp else 0.dp)
    val finalTimeX = labelWidth + 6.dp
    val timeOffset = when {
        pinnedNameX < 0.dp && keepsNaturalCurrentPosition -> finalTimeX
        pinnedNameX < 0.dp -> (finalTimeX + pinnedNameX).coerceIn(0.dp, finalTimeX)
        else -> pinnedNameX + finalTimeX
    }
    val startLabel = remember(program.startMs) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(program.startMs)) }
    val radius = if (compact) 12.dp else 18.dp

    Box(Modifier.offset(x = startX).width(fullWidth).height(height).combinedClickable(onClick = onClick, onLongClick = onClick)) {
        Box(Modifier.width(visibleWidth).height(height).clip(RoundedCornerShape(radius))) {
            Box(Modifier.fillMaxSize().background(if (dark) DarkTileBase else color.copy(alpha = if (compact) .18f else .22f).compositeOver(Color(0.10f, 0.10f, 0.10f))))
            if (brightWidth > 0.dp) {
                Box(Modifier.width(brightWidth).fillMaxHeight().background(if (dark) Color(0.20f, 0.20f, 0.22f) else color.copy(alpha = if (compact) .38f else .45f)))
            }
            if (compact) {
                Column(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.fillMaxWidth().height(18.dp).clipToBounds()) {
                        Row(
                            Modifier.offset(x = if (keepsNaturalCurrentPosition) 0.dp else pinnedNameX),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(baseName, color = glassForeground(), fontSize = 13.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            if (quality != null) QualityBadge(quality, compact = true)
                        }
                        Text(startLabel, color = glassForeground().copy(.60f), fontSize = 13.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
                            maxLines = 1, softWrap = false, modifier = Modifier.offset(x = timeOffset))
                    }
                    Box(Modifier.fillMaxWidth().height(20.dp).clipToBounds()) {
                        Text(program.title.ifBlank { "—" }, color = glassForeground().copy(.92f), fontSize = 14.sp, fontFamily = FontFamily.SansSerif,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.offset(x = stickyContentX))
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 8.dp)) {
                    Row(
                        Modifier.offset(x = if (keepsNaturalCurrentPosition) 0.dp else pinnedNameX),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(baseName, color = glassForeground().copy(.70f), fontSize = 11.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        if (quality != null) QualityBadge(quality, compact = false)
                    }
                    Text(startLabel, color = glassForeground().copy(.55f), fontSize = 12.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
                        maxLines = 1, softWrap = false, modifier = Modifier.offset(x = stickyContentX).padding(top = 16.dp))
                    Text(program.title.ifBlank { "—" }, color = glassForeground(), fontSize = 16.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.offset(x = stickyContentX).padding(top = 33.dp))
                }
            }
        }
    }
}

@Composable
private fun QualityBadge(text: String, compact: Boolean) {
    Text(
        text,
        color = glassForeground().copy(.65f),
        fontSize = if (compact) 11.sp else 10.sp,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            .border(BorderStroke(1.dp, Color.White.copy(.45f)), RoundedCornerShape(5.dp))
    )
}

@Composable
private fun EpgTopBar(
    groupName: String, groups: List<Category>, counts: Map<String?, Int>, uncategorized: Int, onGroup: (String?) -> Unit,
    settings: AppSettings, favoritesOnly: Boolean,
    onSettings: (AppSettings) -> Unit, onRefresh: () -> Unit, onFavoritesOnly: () -> Unit, onDay: (Int) -> Unit
) {
    var groupOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant.copy(.65f)).combinedClickableSafe { groupOpen = true }.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GridView, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                Text(groupName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
                Spacer(Modifier.width(6.dp)); Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(groupOpen, { groupOpen = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                DropdownMenuItem({ Text("Tutti i canali") }, { onGroup(null); groupOpen = false }, leadingIcon = { Icon(Icons.Default.GridView, null) })
                if (uncategorized > 0) DropdownMenuItem({ Text("Senza categoria ($uncategorized)") }, { onGroup("__none__"); groupOpen = false })
                HorizontalDivider()
                groups.forEach { g -> DropdownMenuItem({ Text("${g.name} (${counts[g.id] ?: 0})") }, { onGroup(g.id); groupOpen = false }) }
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton({ page = null; menuOpen = true }) { Icon(Icons.Default.MoreHoriz, "Altre opzioni") }
            DropdownMenu(menuOpen, { menuOpen = false; page = null }) {
                when (page) {
                    null -> {
                        DropdownMenuItem({ Text("Aspetto EPG") }, { page = "aspetto" }, leadingIcon = { Icon(Icons.Default.AspectRatio, null) }, trailingIcon = { Icon(Icons.Default.ChevronRight, null) })
                        DropdownMenuItem({ Text("Assetti EPG") }, { page = "assetti" }, leadingIcon = { Icon(Icons.Default.ViewAgenda, null) }, trailingIcon = { Icon(Icons.Default.ChevronRight, null) })
                        DropdownMenuItem({ Text("Colori EPG") }, { page = "colori" }, leadingIcon = { Icon(Icons.Default.Palette, null) }, trailingIcon = { Icon(Icons.Default.ChevronRight, null) })
                        HorizontalDivider()
                        DropdownMenuItem({ Text("Aggiorna guida") }, { onRefresh(); menuOpen = false }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                        DropdownMenuItem({ Text(if (favoritesOnly) "Mostra tutti" else "Solo preferiti") }, { onFavoritesOnly(); menuOpen = false },
                            leadingIcon = { Icon(if (favoritesOnly) Icons.Default.Star else Icons.Default.StarBorder, null) })
                        HorizontalDivider()
                        DropdownMenuItem({ Text("Ieri") }, { onDay(-1); menuOpen = false }, leadingIcon = { Icon(Icons.Default.ChevronLeft, null) })
                        DropdownMenuItem({ Text("Oggi") }, { onDay(0); menuOpen = false }, leadingIcon = { Icon(Icons.Default.CalendarToday, null) })
                        DropdownMenuItem({ Text("Domani") }, { onDay(1); menuOpen = false }, leadingIcon = { Icon(Icons.Default.ChevronRight, null) })
                    }
                    "aspetto" -> {
                        DropdownMenuItem({ Text("Aspetto EPG") }, { page = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) })
                        HorizontalDivider()
                        CheckItem("Compatta", settings.epgLayoutDensity == "compatta") { onSettings(settings.copy(epgLayoutDensity = "compatta")); menuOpen = false; page = null }
                        CheckItem("Comoda", settings.epgLayoutDensity != "compatta") { onSettings(settings.copy(epgLayoutDensity = "comoda")); menuOpen = false; page = null }
                    }
                    "assetti" -> {
                        DropdownMenuItem({ Text("Assetti EPG") }, { page = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) })
                        HorizontalDivider()
                        CheckItem("Griglia", settings.epgChannelCardStyle != "scheda") { onSettings(settings.copy(epgChannelCardStyle = "griglia")); menuOpen = false; page = null }
                        CheckItem("Scheda", settings.epgChannelCardStyle == "scheda") { onSettings(settings.copy(epgChannelCardStyle = "scheda")); menuOpen = false; page = null }
                    }
                    "colori" -> {
                        DropdownMenuItem({ Text("Colori EPG") }, { page = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) })
                        HorizontalDivider()
                        CheckItem("Dinamico", settings.epgTileColor != "dark") { onSettings(settings.copy(epgTileColor = "dynamic")); menuOpen = false; page = null }
                        CheckItem("Dark", settings.epgTileColor == "dark") { onSettings(settings.copy(epgTileColor = "dark")); menuOpen = false; page = null }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CheckItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem({ Text(label) }, onClick, trailingIcon = { if (selected) Icon(Icons.Default.Check, null) })
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSafe(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

private fun Color.compositeOver(background: Color): Color {
    val a = this.alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}
