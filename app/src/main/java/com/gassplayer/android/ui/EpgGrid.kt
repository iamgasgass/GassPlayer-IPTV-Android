package com.gassplayer.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
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
import android.view.ViewGroup
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.CancellationException
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

internal class EpgStore {
    val programs: SnapshotStateMap<String, List<EpgProgram>> = mutableStateMapOf()
    val loading: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val failed: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    /** Guide revision used to avoid resolving the same visible rows again after section switches. */
    val resolvedRevision: SnapshotStateMap<String, Int> = mutableStateMapOf()
}

/** Shared by Live TV and Guida so navigation does not rebuild the large XMLTV index each time. */
internal class EpgGridSession {
    val store = EpgStore()
    private var inputKey: String? = null
    // Observable so an EPG refresh in the standalone Guide immediately updates the warm Live TV grid.
    private var index by mutableStateOf<EpgGuideIndex?>(null)
    private var guidePayloads: Map<String, XmlTvGuide> = emptyMap()
    var revision by mutableIntStateOf(0)
        private set

    @Synchronized
    fun cachedIndex(key: String): EpgGuideIndex? = if (inputKey == key) index else null

    @Synchronized
    fun reusableIndex(key: String, payloads: Map<String, XmlTvGuide>): EpgGuideIndex? {
        if (inputKey != key || index == null || !samePayloads(guidePayloads, payloads)) return null
        return index
    }

    @Synchronized
    fun publishIndex(key: String, payloads: Map<String, XmlTvGuide>, built: EpgGuideIndex): EpgGuideIndex {
        if (inputKey != key || !samePayloads(guidePayloads, payloads) || index == null) {
            inputKey = key
            guidePayloads = payloads.toMap()
            index = built
            revision++
        } else {
            index = built
        }
        return index ?: built
    }

    private fun samePayloads(a: Map<String, XmlTvGuide>, b: Map<String, XmlTvGuide>): Boolean =
        a.size == b.size && a.all { (id, guide) -> b[id] === guide }
}

@OptIn(ExperimentalFoundationApi::class, UnstableApi::class)
@Composable
internal fun EpgGridScreen(
    app: GassPlayerApplication,
    vm: MainViewModel,
    session: EpgGridSession,
    live: List<MediaItem>,
    categories: List<Category>,
    favorites: FavoriteState,
    settings: AppSettings,
    onPlay: (MediaItem) -> Unit,
    tvCategoryMode: Boolean = false,
    playerActive: Boolean = false,
    screenActive: Boolean = true,
    entryFocusRequester: FocusRequester? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var categoryRailExpanded by remember(tvCategoryMode) { mutableStateOf(true) }
    BackHandler(enabled = screenActive && tvCategoryMode && !categoryRailExpanded) { categoryRailExpanded = true }
    LaunchedEffect(playerActive, tvCategoryMode) {
        if (tvCategoryMode && !playerActive) categoryRailExpanded = true
    }
    var previewChannelId by remember { mutableStateOf<String?>(null) }
    val previewHttpFactory = remember(context) { DefaultHttpDataSource.Factory().setUserAgent(app.network.userAgent) }
    val previewPlayer = remember(context, tvCategoryMode) {
        if (!tvCategoryMode) null else ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(previewHttpFactory))
            .build().apply { volume = 0f }
    }
    fun stopPreviewPlayback() {
        previewPlayer?.apply {
            playWhenReady = false
            stop()
            clearMediaItems()
        }
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer?.release() }
    }
    val store = session.store
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
    var manualGuideRefreshTick by remember { mutableIntStateOf(0) }
    // Stable DPAD path on TV: preview/player or the ellipsis menu -> search -> first channel.
    val guideListState = rememberLazyListState()
    val searchFocusRequester = remember { FocusRequester() }
    val searchInputFocusRequester = remember { FocusRequester() }
    val firstChannelFocusRequester = remember { FocusRequester() }
    // Plain remembered holder avoids triggering composition just to remember the last manual refresh.
    val lastManualGuideRefresh = remember { intArrayOf(0) }

    val compact = settings.epgLayoutDensity == "compatta"
    val compactCategoryLayout = tvCategoryMode && LocalConfiguration.current.screenWidthDp < 700
    val mobileRailWidth = (LocalConfiguration.current.screenWidthDp - 136).dp.coerceIn(160.dp, 236.dp)
    val mobileContentWidth = (LocalConfiguration.current.screenWidthDp - 136).coerceAtLeast(180).dp
    val mobileBannerColumnW = (mobileContentWidth.value * .48f).dp.coerceIn(112.dp, 170.dp)
    val cardStyleGrid = settings.epgChannelCardStyle != "scheda"
    val darkTiles = settings.epgTileColor == "dark"
    val bannerInset = if (compact) 10.dp else 12.dp
    // The shared Guide stays compact. Categorie TV expands the channel banner on large
    // screens and uses a responsive banner column on phones so the timeline remains visible.
    val bannerW = if (tvCategoryMode) {
        if (compactCategoryLayout) (mobileBannerColumnW - 4.dp).coerceAtLeast(100.dp) else 324.dp
    } else if (compact) 80.dp else 86.dp
    val bannerH = if (compact) 58.dp else 76.dp
    val rowH = if (compact) 66.dp else 96.dp
    val headerH = if (compact) 40.dp else 44.dp
    val bannerColumnW = if (tvCategoryMode) {
        if (compactCategoryLayout) mobileBannerColumnW else 348.dp
    } else if (cardStyleGrid) bannerInset + bannerW + bannerInset else bannerInset + bannerW
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
                // Re-resolve visible rows as well as the feed index; changing only the index
                // left already-populated rows permanently stale until the user changed screens.
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
    fun keyOf(i: MediaItem) = (i.categoryId ?: i.group)?.takeIf { it.isNotBlank() }
    // A playlist backed by SQLite can contain hundreds of thousands of rows. Keep even the small
    // GROUP BY query off the main thread so tapping between libraries never waits on SQLite.
    val counts by produceState(initialValue = emptyMap<String?, Int>(), live) {
        value = withContext(Dispatchers.IO) { live.categoryCounts() }
    }
    val groups = remember(categories, live, counts) {
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
    val channels by produceState<List<MediaItem>>(
        initialValue = emptyList(), live, groupId, favoritesOnly, favorites.live, normalizedSearch,
    ) {
        value = withContext(Dispatchers.IO) {
            // The iOS grid filters channel names before paging. For in-memory lists this can scan
            // the full playlist, so do it away from the UI thread as well.
            val grouped = if (favoritesOnly) {
                live.onlyIds(favorites.live).filter {
                    groupId == null || (groupId == "__none__" && keyOf(it) == null) || keyOf(it) == groupId
                }
            } else live.inCategory(groupId)
            if (normalizedSearch.isEmpty()) grouped else grouped.matchingTitle(normalizedSearch)
        }
    }
    LaunchedEffect(groupId, favoritesOnly, normalizedSearch, dayOffset) {
        renderLimit = PAGE
        previewChannelId = null
    }
    val visible by produceState<List<MediaItem>>(
        initialValue = emptyList(), channels, renderLimit,
    ) {
        value = withContext(Dispatchers.IO) { channels.take(minOf(renderLimit, HARD_CAP)) }
    }
    LaunchedEffect(visible) {
        if (previewChannelId == null) previewChannelId = visible.firstOrNull()?.id
    }
    val previewChannel = remember(visible, previewChannelId) {
        visible.firstOrNull { it.id == previewChannelId } ?: visible.firstOrNull()
    }
    LaunchedEffect(previewPlayer, previewChannel?.id, tvCategoryMode, playerActive, screenActive) {
        val player = previewPlayer
        val channel = previewChannel
        if (player == null || channel == null || !tvCategoryMode || !screenActive) {
            player?.stop()
            return@LaunchedEffect
        }
        if (playerActive) {
            stopPreviewPlayback()
            return@LaunchedEffect
        }
        // Debounce rapid DPAD movement so only the finally focused channel opens a stream.
        player.stop()
        player.clearMediaItems()
        delay(180L)
        runCatching {
            val headers = LinkedHashMap(channel.streamHeaders)
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) headers["User-Agent"] = app.network.userAgent
            previewHttpFactory.setDefaultRequestProperties(headers)
            player.stop()
            player.clearMediaItems()
            val media = ExoMediaItem.Builder().setUri(channel.streamUrl).apply {
                channel.streamMimeType?.let { setMimeType(it) }
            }.build()
            player.setMediaItem(media)
            player.prepare()
            player.volume = 0f
            player.playWhenReady = true
        }.onFailure { player.stop() }
    }
    val previewPrograms = previewChannel?.let { store.programs[it.id].orEmpty() }.orEmpty()
    val previewCurrent = previewPrograms.firstOrNull { it.isCurrent(now) }
    val previewNext = previewPrograms.firstOrNull { it.startMs >= (previewCurrent?.endMs ?: now) && it.startMs > now }

    val tvRailOptions = rememberTvCategoryRailOptions(categories, live, favorites.live, counts)
    val selectedTvRailId = when {
        favoritesOnly -> TV_CATEGORY_FAVORITES
        groupId == null -> TV_CATEGORY_ALL
        else -> groupId!!
    }

    // --- XMLTV index: keep the parsed index across Live/VOD/Series/Guide navigation. ----------
    // The signature changes only when an enabled feed or its credentials/URL change. The shared
    // session supplies its last good index synchronously; building a large XMLTV index is always
    // moved off Main, and Live TV does not force a network refresh on every tab return.
    val guideInputKey = remember(external, sources, live) {
        val usedSourceIds = live.sourceIds()
        buildList {
            external.filter { it.isEnabled }.forEach { add("external:${it.id}:${it.urlString.hashCode()}") }
            sources.filter { it.type == SourceType.XTREAM && it.id in usedSourceIds && it.isEnabled }.forEach {
                add("xtream:${it.id}:${it.host.hashCode()}:${it.username.orEmpty().hashCode()}:${it.password.orEmpty().hashCode()}")
            }
        }.sorted().joinToString("|")
    }
    val lastGuideRefresh = remember { intArrayOf(-1, -1) }
    val entryRefreshedFeedKeys = remember { mutableSetOf<String>() }
    val producedXmltvIndex by produceState<EpgGuideIndex?>(
        initialValue = session.cachedIndex(guideInputKey),
        session, guideInputKey, external, sources, live, refreshTick, manualGuideRefreshTick, tvCategoryMode,
    ) {
        // A return to Live TV with the same feed set reuses the already indexed XMLTV in memory.
        // The dedicated Guide section still revalidates automatically when opened, as requested.
        val inMemoryIndex = session.cachedIndex(guideInputKey)
        if (tvCategoryMode && inMemoryIndex != null && refreshTick == 0 && manualGuideRefreshTick == 0) {
            value = inMemoryIndex
            return@produceState
        }

        val firstRun = lastGuideRefresh[0] < 0 && lastGuideRefresh[1] < 0
        val refreshAllFeeds = if (firstRun && tvCategoryMode) false
            else refreshTick != lastGuideRefresh[0] || manualGuideRefreshTick != lastGuideRefresh[1]
        lastGuideRefresh[0] = refreshTick
        lastGuideRefresh[1] = manualGuideRefreshTick

        val usedSourceIds = live.sourceIds()
        val externalFeeds = external.filter { it.isEnabled }
        val providerFeeds = sources.filter {
            it.type == SourceType.XTREAM && it.id in usedSourceIds && it.isEnabled
        }
        fun externalFeedKey(feed: ExternalEpgSource) = "external:${feed.id}:${feed.urlString.hashCode()}"
        fun providerFeedKey(source: MediaSourceConfig) =
            "xtream:${source.id}:${source.host.hashCode()}:${source.username.orEmpty().hashCode()}:${source.password.orEmpty().hashCode()}"
        val activeFeedKeys = externalFeeds.map { externalFeedKey(it) } + providerFeeds.map { providerFeedKey(it) }
        entryRefreshedFeedKeys.retainAll(activeFeedKeys.toSet())
        val downloadSemaphore = Semaphore(3)

        val cachedGuides = withContext(Dispatchers.IO) {
            coroutineScope {
                val externalJobs = externalFeeds.map { feed ->
                    async(Dispatchers.IO) {
                        downloadSemaphore.withPermit {
                            try {
                                feed.id to app.epg.cachedXmltvGuide(feed.urlString, feed.id)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }
                val providerJobs = providerFeeds.map { source ->
                    async(Dispatchers.IO) {
                        downloadSemaphore.withPermit {
                            try {
                                source.id to app.epg.cachedXtreamXmltvGuide(source)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }
                (externalJobs + providerJobs).awaitAll().filterNotNull()
            }
        }
        val cachedForIndex = cachedGuides.filter { it.second.programs.isNotEmpty() }
        val cachedPayloads = cachedForIndex.associate { it.first to it.second }
        if (cachedForIndex.isNotEmpty()) {
            val reusable = session.reusableIndex(guideInputKey, cachedPayloads)
            val cachedIndex = reusable ?: withContext(Dispatchers.Default) {
                EpgGuideIndex.build(cachedForIndex)
            }.let { session.publishIndex(guideInputKey, cachedPayloads, it) }
            value = cachedIndex
        } else if (activeFeedKeys.isEmpty() && session.cachedIndex(guideInputKey) == null) {
            val emptyIndex = withContext(Dispatchers.Default) { EpgGuideIndex.build(emptyList()) }
            value = session.publishIndex(guideInputKey, emptyMap(), emptyIndex)
        }

        val cachedById = cachedGuides.associate { it.first to it.second }
        val loadedGuides = withContext(Dispatchers.IO) {
            coroutineScope {
                val externalJobs = externalFeeds.map { feed ->
                    async(Dispatchers.IO) {
                        downloadSemaphore.withPermit {
                            try {
                                val forceThisFeed = refreshAllFeeds || (!tvCategoryMode && externalFeedKey(feed) !in entryRefreshedFeedKeys)
                                val staleFallback = if (forceThisFeed) cachedById[feed.id] ?: XmlTvGuide() else null
                                feed.id to app.epg.xmltvGuide(feed.urlString, feed.id, forceThisFeed, staleFallback)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }
                val providerJobs = providerFeeds.map { source ->
                    async(Dispatchers.IO) {
                        downloadSemaphore.withPermit {
                            try {
                                val forceThisFeed = refreshAllFeeds || (!tvCategoryMode && providerFeedKey(source) !in entryRefreshedFeedKeys)
                                val staleFallback = if (forceThisFeed) cachedById[source.id] ?: XmlTvGuide() else null
                                source.id to app.epg.xtreamXmltvGuide(source, forceThisFeed, staleFallback)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }
                (externalJobs + providerJobs).awaitAll().filterNotNull()
            }
        }
        // Mark only after all jobs finish, so a cancelled producer retries newly arrived feeds.
        entryRefreshedFeedKeys.addAll(activeFeedKeys)
        val loadedForIndex = loadedGuides.filter { it.second.programs.isNotEmpty() }
        if (loadedForIndex.isNotEmpty()) {
            val loadedPayloads = loadedForIndex.associate { it.first to it.second }
            val reusable = session.reusableIndex(guideInputKey, loadedPayloads)
            val loadedIndex = reusable ?: withContext(Dispatchers.Default) {
                EpgGuideIndex.build(loadedForIndex)
            }.let { session.publishIndex(guideInputKey, loadedPayloads, it) }
            value = loadedIndex
        } else if (activeFeedKeys.isEmpty()) {
            val emptyIndex = withContext(Dispatchers.Default) { EpgGuideIndex.build(emptyList()) }
            value = session.publishIndex(guideInputKey, emptyMap(), emptyIndex)
        }
    }

    // Read the observable shared index after the loader. If Guida refreshed XMLTV while Live TV
    // was hidden but retained in composition, this immediately exposes the same new index here.
    val xmltvIndex = session.cachedIndex(guideInputKey) ?: producedXmltvIndex

    // Resolve only rows that have no data yet or whose guide revision actually changed. Cached rows
    // remain visible during navigation, so the first frame after returning to Live TV is populated.
    val guideRevision = session.revision
    LaunchedEffect(visible.map { it.id }, xmltvIndex, dayOffset, guideRevision, screenActive) {
        if (!screenActive) return@LaunchedEffect
        val sem = Semaphore(MAX_CONCURRENT)
        coroutineScope {
            visible.filter { channel ->
                val alreadyResolved = xmltvIndex != null && store.resolvedRevision[channel.id] == guideRevision
                !alreadyResolved && (store.programs[channel.id] == null || xmltvIndex != null)
            }.map { ch ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        store.loading[ch.id] = true
                        try {
                            val source = sources.firstOrNull { it.id == ch.sourceId }
                            var found = store.programs[ch.id].orEmpty()
                            var requestFailed = false
                            val streamId = ch.id.substringAfterLast(':')
                            val fullGuidePrograms = xmltvIndex?.find(ch.sourceId, ch.metadataTag, streamId, ch.title).orEmpty()
                            if (found.isEmpty() && fullGuidePrograms.isEmpty() && source?.type == SourceType.XTREAM) {
                                val result = try {
                                    Result.success(app.epg.shortEpg(source, streamId, SHORT_LIMIT))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    Result.failure(error)
                                }
                                found = result.getOrDefault(emptyList())
                                requestFailed = result.isFailure
                            }
                            if (fullGuidePrograms.isNotEmpty()) {
                                val mergedByStart = LinkedHashMap<Long, EpgProgram>()
                                found.forEach { mergedByStart[it.startMs] = it }
                                fullGuidePrograms.forEach { mergedByStart[it.startMs] = it }
                                found = mergedByStart.values.sortedBy { it.startMs }
                            }
                            store.programs[ch.id] = found.sortedBy { it.startMs }
                            store.resolvedRevision[ch.id] = guideRevision
                            if (requestFailed && found.isEmpty()) store.failed[ch.id] = true else store.failed.remove(ch.id)
                        } finally {
                            store.loading.remove(ch.id)
                        }
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
    val bleed = if (cardStyleGrid || tvCategoryMode) 0.dp else bannerW / 2f
    val dayTitle = when (dayOffset) {
        0 -> "Oggi"
        1 -> "Domani"
        -1 -> "Ieri"
        else -> SimpleDateFormat("EEEE d MMM", Locale.ITALIAN).format(Date(windowCenter))
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (tvCategoryMode && categoryRailExpanded && !compactCategoryLayout) 12.dp else 0.dp)) {
        if (tvCategoryMode && !compactCategoryLayout) {
            TvCategoryRail(
                options = tvRailOptions,
                selectedId = selectedTvRailId,
                expanded = categoryRailExpanded,
                entryFocusRequester = entryFocusRequester,
                onSelect = { id ->
                    favoritesOnly = id == TV_CATEGORY_FAVORITES
                    groupId = when (id) {
                        TV_CATEGORY_ALL, TV_CATEGORY_FAVORITES -> null
                        else -> id
                    }
                    searchQuery = ""
                    debouncedSearch = ""
                    renderLimit = PAGE
                    previewChannelId = null
                },
                onMoveRight = {
                    scope.launch {
                        var attempts = 0
                        while (visible.isEmpty() && attempts < 20) {
                            delay(40L)
                            attempts++
                        }
                        if (visible.isNotEmpty()) {
                            runCatching { guideListState.scrollToItem(2) } // search + timeline header
                            delay(70L)
                            if (!firstChannelFocusRequester.requestFocusWithRetry(attempts = 5, delayMillis = 45L)) {
                                searchFocusRequester.requestFocusWithRetry()
                            }
                        } else {
                            searchFocusRequester.requestFocusWithRetry()
                        }
                    }
                    true
                }
            )
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().onPreviewKeyEvent { event ->
                if (tvCategoryMode && !categoryRailExpanded && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    categoryRailExpanded = true
                    true
                } else false
            }
        ) {
        if (!tvCategoryMode) {
            EpgTopBar(
                groupName = groupName, groups = groups, counts = counts, uncategorized = uncategorized,
                onGroup = { groupId = it; searchQuery = ""; favoritesOnly = false },
                settings = settings, favoritesOnly = favoritesOnly,
                onSettings = { vm.updateSettings(it) },
                onRefresh = {
                    toast = "Aggiornamento guida in corso…"
                    manualGuideRefreshTick++
                    refreshTick++
                },
                onFavoritesOnly = { favoritesOnly = !favoritesOnly },
                tvCategoryMode = false,
                onDay = {
                    if (dayOffset != it) {
                        // XMLTV covers the previous/current/next-day window; changing the date
                        // does not require discarding valid cached channel programmes.
                        dayOffset = it
                    }
                    if (it == 0) now = System.currentTimeMillis()
                }
            )
        }
        if (tvCategoryMode) {
            TvEpgPreviewPanel(
                channel = previewChannel,
                current = previewCurrent,
                next = previewNext,
                now = now,
                player = previewPlayer,
                groupName = groupName,
                groups = groups,
                counts = counts,
                uncategorized = uncategorized,
                settings = settings,
                favoritesOnly = favoritesOnly,
                onGroup = { groupId = it; searchQuery = ""; favoritesOnly = false },
                onSettings = { vm.updateSettings(it) },
                onRefresh = {
                    toast = "Aggiornamento guida in corso…"
                    manualGuideRefreshTick++
                    refreshTick++
                },
                onFavoritesOnly = { favoritesOnly = !favoritesOnly },
                onDay = {
                    if (dayOffset != it) {
                        // XMLTV covers the previous/current/next-day window; changing the date
                        // does not require discarding valid cached channel programmes.
                        dayOffset = it
                    }
                    if (it == 0) now = System.currentTimeMillis()
                },
                onWatch = {
                    stopPreviewPlayback()
                    previewChannel?.let(onPlay)
                },
                compactLayout = compactCategoryLayout,
                onMoveDown = {
                    scope.launch {
                        // The preview panel sits above the scrolling guide; always begin from
                        // the search row so DPAD movement is deterministic, even after scrolling.
                        guideListState.scrollToItem(0)
                        delay(60L)
                        runCatching { searchFocusRequester.requestFocus() }
                    }
                    true
                },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) categoryRailExpanded = false }
            )
        }
        toast?.let { msg ->
            LaunchedEffect(msg) { delay(2500); toast = null }
            Text(msg, color = glassForeground().copy(.85f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        if (live.isEmpty()) {
            Text(
                "Nessun canale live disponibile. Aggiungi una sorgente e attendi il caricamento.",
                color = glassForeground().copy(.6f),
                modifier = Modifier.padding(16.dp)
                    .then(if (!tvCategoryMode) entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier else Modifier)
                    .remoteFocusOutline(cornerRadius = 12.dp)
                    .focusable(),
            )
        } else {
        LazyColumn(
            state = guideListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (compact) 24.dp else 32.dp)
        ) {
            item(key = "epg-search") {
                EpgSearchHeader(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    compact = compact,
                    horizontalInset = bannerInset,
                    onFocus = { if (tvCategoryMode) categoryRailExpanded = false },
                    // Category mode receives focus through its rail. Otherwise the route's entry
                    // point is this persistent row, so Guide stays navigable while EPG is loading.
                    focusRequester = if (tvCategoryMode) searchFocusRequester else entryFocusRequester,
                    inputFocusRequester = searchInputFocusRequester,
                    onMoveDown = {
                        scope.launch {
                            var attempts = 0
                            while (visible.isEmpty() && attempts < 20) {
                                delay(40L)
                                attempts++
                            }
                            if (visible.isNotEmpty()) {
                                runCatching { guideListState.animateScrollToItem(2) }
                                delay(70L)
                                firstChannelFocusRequester.requestFocusWithRetry(attempts = 5, delayMillis = 45L)
                            }
                        }
                        true
                    }
                )
            }
            // Like iOS, the day + time header is part of the vertical scroll content (not sticky).
            item(key = "epg-timeline-header") {
                Row(Modifier.fillMaxWidth().background(Color.Black).height(headerH)) {
                    Box(
                        Modifier.width(bannerColumnW).fillMaxHeight().background(Color.Black)
                            .combinedClickable(onClick = {
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
            itemsIndexed(visible, key = { _, c -> c.id }) { visibleIndex, ch ->
                val programs = store.programs[ch.id].orEmpty().sortedBy { it.startMs }
                Row(Modifier.fillMaxWidth().height(rowH)) {
                    ChannelBanner(
                        ch,
                        favorite = ch.id in favorites.live,
                        selected = tvCategoryMode && ch.id == previewChannel?.id,
                        hasCatchup = programs.any { it.hasArchive && it.endMs > windowStart && it.startMs < windowEnd },
                        width = bannerColumnW,
                        bannerW = bannerW,
                        bannerH = bannerH,
                        rowHeight = rowH,
                        inset = bannerInset,
                        centered = if (tvCategoryMode) true else cardStyleGrid,
                        dark = darkTiles,
                        compact = compact,
                        showChannelDetails = tvCategoryMode,
                        compactDetails = compactCategoryLayout,
                        displayNumber = ch.number ?: (visibleIndex + 1),
                        onClick = { stopPreviewPlayback(); onPlay(ch) },
                        onLongClick = { vm.toggleFavorite(ch) },
                        onFocus = { if (tvCategoryMode) { previewChannelId = ch.id; categoryRailExpanded = false } },
                        onExitLeft = { if (tvCategoryMode && !categoryRailExpanded) { categoryRailExpanded = true; true } else false },
                        focusRequester = if (visibleIndex == 0) firstChannelFocusRequester else null,
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
                                        verticalInset = (rowH - bannerH) / 2f,
                                        brightWidth = brightWidth,
                                        color = base,
                                        dark = darkTiles,
                                        selected = tvCategoryMode && ch.id == previewChannel?.id && program.isCurrent(now),
                                        onClick = { selectedProgram = ch to program; if (tvCategoryMode) previewChannelId = ch.id },
                                        onFocus = { if (tvCategoryMode) { previewChannelId = ch.id; categoryRailExpanded = false } },
                                        onExitLeft = { if (tvCategoryMode && !categoryRailExpanded) { categoryRailExpanded = true; true } else false }
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
        }
    }
    if (tvCategoryMode && compactCategoryLayout) {
        TvCategoryRail(
            options = tvRailOptions,
            selectedId = selectedTvRailId,
            expanded = categoryRailExpanded,
            expandedWidth = mobileRailWidth,
            modifier = Modifier.align(Alignment.CenterStart).zIndex(30f),
            onSelect = { id ->
                favoritesOnly = id == TV_CATEGORY_FAVORITES
                groupId = when (id) {
                    TV_CATEGORY_ALL, TV_CATEGORY_FAVORITES -> null
                    else -> id
                }
                searchQuery = ""
                debouncedSearch = ""
                renderLimit = PAGE
                previewChannelId = null
                categoryRailExpanded = false
            }
        )
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
                                stopPreviewPlayback()
                                onPlay(ch.copy(streamUrl = url, title = "${ch.title} · ${p.title}", kind = MediaKind.EPISODE))
                            }
                            selectedProgram = null
                        }
                    }) { Text("Catch-up") }
                    Button({ stopPreviewPlayback(); onPlay(ch); selectedProgram = null }) { Text("Guarda") }
                }
            },
            dismissButton = { TextButton({ selectedProgram = null }) { Text("Chiudi") } }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TvEpgPreviewPanel(
    channel: MediaItem?,
    current: EpgProgram?,
    next: EpgProgram?,
    now: Long,
    player: ExoPlayer?,
    groupName: String,
    groups: List<Category>,
    counts: Map<String?, Int>,
    uncategorized: Int,
    settings: AppSettings,
    favoritesOnly: Boolean,
    onGroup: (String?) -> Unit,
    onSettings: (AppSettings) -> Unit,
    onRefresh: () -> Unit,
    onFavoritesOnly: () -> Unit,
    onDay: (Int) -> Unit,
    onWatch: () -> Unit,
    compactLayout: Boolean = false,
    onMoveDown: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onMoveDown != null) {
                onMoveDown.invoke()
                true
            } else false
        }
            .height(if (compactLayout) 154.dp else 212.dp)
            .padding(horizontal = if (compactLayout) 4.dp else 16.dp, vertical = if (compactLayout) 6.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compactLayout) 8.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onWatch,
            modifier = (if (compactLayout) Modifier.width(108.dp).aspectRatio(16f / 9f) else Modifier.fillMaxHeight().aspectRatio(16f / 9f)).remoteFocusOutline(cornerRadius = 14.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color.Black,
            border = BorderStroke(.8.dp, Color.White.copy(.16f)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!channel?.logoUrl.isNullOrBlank()) {
                    AsyncImage(channel?.logoUrl, contentDescription = channel?.title, modifier = Modifier.fillMaxSize(.60f), contentScale = ContentScale.Fit)
                } else Icon(Icons.Default.LiveTv, null, tint = Color.White.copy(.75f), modifier = Modifier.size(36.dp))
                if (player != null && channel != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            this.player = player
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        } },
                        update = { view -> view.player = player },
                    )
                }
                Row(
                    Modifier.align(Alignment.BottomStart).padding(9.dp)
                        .background(Color.Black.copy(.58f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Guarda", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (compactLayout) 1.dp else 4.dp)) {
            EpgTopBar(
                groupName = groupName,
                groups = groups,
                counts = counts,
                uncategorized = uncategorized,
                onGroup = onGroup,
                settings = settings,
                favoritesOnly = favoritesOnly,
                onSettings = onSettings,
                onRefresh = onRefresh,
                onFavoritesOnly = onFavoritesOnly,
                tvCategoryMode = true,
                onDay = onDay,
            )
            Text(channel?.title ?: "Seleziona un canale", color = glassForeground(), fontSize = if (compactLayout) 13.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (current != null) {
                Text(
                    "${timeFormat.format(Date(current.startMs))}–${timeFormat.format(Date(current.endMs))}   ${current.title}",
                    color = glassForeground(), fontSize = if (compactLayout) 10.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { current.progress(now).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF89A8FF), trackColor = Color.White.copy(.18f),
                )
                Text(current.description?.takeIf { it.isNotBlank() } ?: "Descrizione del programma non disponibile.", color = glassForeground(.76f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Programma in onda non disponibile", color = glassForeground(.74f), fontSize = if (compactLayout) 10.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                next?.let { "Prossimo ${timeFormat.format(Date(it.startMs))}  ${it.title}" } ?: "Prossimo programma non disponibile",
                color = glassForeground(.6f), fontSize = if (compactLayout) 9.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelBanner(
    ch: MediaItem,
    favorite: Boolean,
    selected: Boolean,
    hasCatchup: Boolean,
    width: Dp,
    bannerW: Dp,
    bannerH: Dp,
    rowHeight: Dp,
    inset: Dp,
    centered: Boolean,
    dark: Boolean,
    compact: Boolean,
    showChannelDetails: Boolean = false,
    compactDetails: Boolean = false,
    displayNumber: Int? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit = {},
    onExitLeft: () -> Boolean = { false },
    focusRequester: FocusRequester? = null,
) {
    val cardLeft = if (centered) (width - bannerW) / 2 else inset
    val badgeSize = if (compact) 26.dp else 30.dp
    var focused by remember(ch.id) { mutableStateOf(false) }
    Box(Modifier.width(width).height(rowHeight).zIndex(10f)) {
        Box(
            Modifier.offset(x = cardLeft, y = (rowHeight - bannerH) / 2f).width(bannerW).height(bannerH)
                .clip(RoundedCornerShape(if (compact) 14.dp else 16.dp))
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(when {
                    focused -> Modifier.border(BorderStroke(2.dp, Color(0xFF8CB7FF)), RoundedCornerShape(if (compact) 14.dp else 16.dp))
                    selected -> Modifier.border(BorderStroke(1.2.dp, Color(0xFF8FB3FF)), RoundedCornerShape(if (compact) 14.dp else 16.dp))
                    else -> Modifier
                })
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && onExitLeft() }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            LiquidGlassSurface(
                modifier = Modifier.fillMaxSize(),
                cornerRadius = if (compact) 14.dp else 16.dp,
                contentPadding = 0.dp,
                highlighted = selected || focused,
                suppressTopHighlight = true,
            ) {}
            Box(
                Modifier.fillMaxSize().background(
                    if (selected) Color(0xFF5265BA).copy(alpha = .48f)
                    else if (dark) DarkBanner.copy(alpha = .30f)
                    else pastelFor(ch).copy(alpha = .33f)
                )
            )
            if (showChannelDetails && compactDetails) {
                val (baseName, quality) = remember(ch.title) { splitQuality(ch.title) }
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(displayNumber?.toString().orEmpty(), color = glassForeground(.68f), fontSize = 10.sp, maxLines = 1)
                        Box(Modifier.size(23.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(.10f)), contentAlignment = Alignment.Center) {
                            if (!ch.logoUrl.isNullOrBlank()) {
                                AsyncImage(model = ch.logoUrl, contentDescription = ch.title, modifier = Modifier.fillMaxSize().padding(1.dp), contentScale = ContentScale.Fit)
                            } else Icon(Icons.Default.LiveTv, contentDescription = "Canale TV", tint = glassForeground(.8f), modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = if (favorite) "Canale preferito" else "Aggiungi ai preferiti con pressione prolungata", tint = if (favorite) Color(0xFFFFD60A) else glassForeground(.58f), modifier = Modifier.size(13.dp))
                    }
                    Text(
                        baseName + (quality?.let { " $it" } ?: ""),
                        color = glassForeground(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (showChannelDetails) {
                val (baseName, quality) = remember(ch.title) { splitQuality(ch.title) }
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        displayNumber?.toString().orEmpty(),
                        color = glassForeground(.68f),
                        fontSize = if (compact) 12.sp else 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.widthIn(min = 25.dp),
                    )
                    Box(
                        Modifier.size(if (compact) 36.dp else 44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(.10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!ch.logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ch.logoUrl,
                                contentDescription = ch.title,
                                modifier = Modifier.fillMaxSize().padding(2.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Icon(Icons.Default.LiveTv, contentDescription = "Canale TV", tint = glassForeground(.8f), modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            baseName,
                            color = glassForeground(),
                            fontSize = if (compact) 13.sp else 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (quality != null) QualityBadge(quality, compact = compact)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(
                            if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (favorite) "Canale preferito" else "Aggiungi ai preferiti con pressione prolungata",
                            tint = if (favorite) Color(0xFFFFD60A) else glassForeground(.58f),
                            modifier = Modifier.size(if (compact) 18.dp else 22.dp),
                        )
                        if (hasCatchup) Icon(Icons.Default.History, contentDescription = "Catch-up disponibile", tint = glassForeground(.7f), modifier = Modifier.size(14.dp))
                    }
                }
            } else {
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
        }
        if (hasCatchup && !showChannelDetails) {
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
    horizontalInset: Dp,
    onFocus: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onMoveDown: (() -> Boolean)? = null,
    inputFocusRequester: FocusRequester? = null,
    onActivate: (() -> Unit)? = null,
) {
    EpgStyleSearchField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalInset).padding(top = 10.dp, bottom = if (compact) 16.dp else 18.dp),
        placeholder = "Cerca per nome del programma",
        onFocus = onFocus,
        focusRequester = focusRequester,
        onMoveDown = onMoveDown,
        inputFocusRequester = inputFocusRequester,
        onActivate = onActivate
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
    verticalInset: Dp,
    brightWidth: Dp,
    color: Color,
    dark: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    onExitLeft: () -> Boolean = { false },
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
    var focused by remember(program.id) { mutableStateOf(false) }

    Box(
        Modifier.offset(x = startX, y = verticalInset).width(fullWidth).height(height)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && onExitLeft() }
            .combinedClickable(onClick = onClick, onLongClick = onClick)
    ) {
        Box(
            Modifier.width(visibleWidth).height(height)
                .clip(RoundedCornerShape(radius))
                .then(when {
                    focused -> Modifier.border(BorderStroke(2.dp, Color(0xFF8CB7FF)), RoundedCornerShape(radius))
                    selected -> Modifier.border(BorderStroke(1.2.dp, Color(0xFF8FB3FF)), RoundedCornerShape(radius))
                    else -> Modifier
                })
        ) {
            LiquidGlassSurface(
                modifier = Modifier.fillMaxSize(),
                cornerRadius = radius,
                contentPadding = 0.dp,
                highlighted = selected || focused,
                suppressTopHighlight = true,
            ) {}
            Box(
                Modifier.fillMaxSize().background(
                    if (selected) Color(0xFF5265BA).copy(alpha = .48f)
                    else if (dark) DarkTileBase.copy(alpha = .26f)
                    else color.copy(alpha = if (compact) .16f else .18f).compositeOver(Color(0.10f, 0.10f, 0.10f))
                )
            )
            if (brightWidth > 0.dp) {
                Box(Modifier.width(brightWidth).fillMaxHeight().background(if (selected) Color(0xFF7487DF).copy(alpha = .32f) else if (dark) Color(0.20f, 0.20f, 0.22f).copy(alpha = .46f) else color.copy(alpha = if (compact) .30f else .34f)))
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
    onSettings: (AppSettings) -> Unit, onRefresh: () -> Unit, onFavoritesOnly: () -> Unit, onDay: (Int) -> Unit,
    tvCategoryMode: Boolean = false,
) {
    var groupOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!tvCategoryMode) Box {
            Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant.copy(.65f)).combinedClickableSafe { groupOpen = true }.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GridView, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                Text(groupName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
                Spacer(Modifier.width(6.dp)); Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(groupOpen, { groupOpen = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                DropdownMenuItem({ Text("Tutti i canali") }, { onGroup(null); groupOpen = false }, leadingIcon = { Icon(Icons.Default.GridView, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                if (uncategorized > 0) DropdownMenuItem({ Text("Senza categoria ($uncategorized)") }, { onGroup("__none__"); groupOpen = false }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                HorizontalDivider()
                groups.forEach { g -> DropdownMenuItem({ Text("${g.name} (${counts[g.id] ?: 0})") }, { onGroup(g.id); groupOpen = false }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true)) }
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton({ page = null; menuOpen = true }) { Icon(Icons.Default.MoreHoriz, "Altre opzioni") }
            DropdownMenu(menuOpen, { menuOpen = false; page = null }) {
                when (page) {
                    null -> {
                        if (tvCategoryMode) {
                            DropdownMenuItem({ Text("UI Gruppi") }, { page = "gruppi" }, leadingIcon = { Icon(Icons.Default.ViewAgenda, null) }, trailingIcon = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                            HorizontalDivider()
                        }
                        DropdownMenuItem({ Text("Aspetto EPG") }, { page = "aspetto" }, leadingIcon = { Icon(Icons.Default.AspectRatio, null) }, trailingIcon = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                        DropdownMenuItem({ Text("Assetti EPG") }, { page = "assetti" }, leadingIcon = { Icon(Icons.Default.ViewAgenda, null) }, trailingIcon = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                        DropdownMenuItem({ Text("Colori EPG") }, { page = "colori" }, leadingIcon = { Icon(Icons.Default.Palette, null) }, trailingIcon = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                        HorizontalDivider()
                        DropdownMenuItem({ Text("Aggiorna guida") }, { onRefresh(); menuOpen = false }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                        DropdownMenuItem({ Text(if (favoritesOnly) "Mostra tutti" else "Solo preferiti") }, { onFavoritesOnly(); menuOpen = false },
                            leadingIcon = { Icon(if (favoritesOnly) Icons.Default.Star else Icons.Default.StarBorder, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                        HorizontalDivider()
                        DropdownMenuItem({ Text("Ieri") }, { onDay(-1); menuOpen = false }, leadingIcon = { Icon(Icons.Default.ChevronLeft, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                        DropdownMenuItem({ Text("Oggi") }, { onDay(0); menuOpen = false }, leadingIcon = { Icon(Icons.Default.CalendarToday, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                        DropdownMenuItem({ Text("Domani") }, { onDay(1); menuOpen = false }, leadingIcon = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                    }
                    "gruppi" -> {
                        DropdownMenuItem({ Text("UI Gruppi") }, { page = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                        HorizontalDivider()
                        CheckItem("Categorie TV", settings.groupUIStyle == "categorie_tv") { onSettings(settings.copy(groupUIStyle = "categorie_tv")); menuOpen = false; page = null }
                        CheckItem("Scorrevole", settings.groupUIStyle == "scorrevole") { onSettings(settings.copy(groupUIStyle = "scorrevole")); menuOpen = false; page = null }
                        CheckItem("Espansibile", settings.groupUIStyle == "espansibile") { onSettings(settings.copy(groupUIStyle = "espansibile")); menuOpen = false; page = null }
                    }
                    "aspetto" -> {
                        DropdownMenuItem({ Text("Aspetto EPG") }, { page = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                        HorizontalDivider()
                        CheckItem("Compatta", settings.epgLayoutDensity == "compatta") { onSettings(settings.copy(epgLayoutDensity = "compatta")); menuOpen = false; page = null }
                        CheckItem("Comoda", settings.epgLayoutDensity != "compatta") { onSettings(settings.copy(epgLayoutDensity = "comoda")); menuOpen = false; page = null }
                    }
                    "assetti" -> {
                        DropdownMenuItem({ Text("Assetti EPG") }, { page = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                        HorizontalDivider()
                        CheckItem("Griglia", settings.epgChannelCardStyle != "scheda") { onSettings(settings.copy(epgChannelCardStyle = "griglia")); menuOpen = false; page = null }
                        CheckItem("Scheda", settings.epgChannelCardStyle == "scheda") { onSettings(settings.copy(epgChannelCardStyle = "scheda")); menuOpen = false; page = null }
                    }
                    "colori" -> {
                        DropdownMenuItem({ Text("Colori EPG") }, { page = null }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
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
    DropdownMenuItem({ Text(label) }, onClick, trailingIcon = { if (selected) Icon(Icons.Default.Check, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSafe(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

private fun Color.compositeOver(background: Color): Color {
    val a = this.alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}
