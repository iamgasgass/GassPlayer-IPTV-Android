package com.gassplayer.android.ui

import com.gassplayer.android.ui.theme.gassPageBase
import com.gassplayer.android.ui.theme.isGassLight
import com.gassplayer.android.ui.theme.DarkContentTheme
import com.gassplayer.android.ui.theme.readable
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.UUID

/** Passes a resume position to the player without changing the shared playback callback. */
private fun onPlayWithPosition(
    onPlay: (MediaItem) -> Unit,
    item: MediaItem,
    position: Long
) {
    // "Riprendi da" exists only for VOD/Serie: a live channel always starts from the live edge.
    if (item.kind == MediaKind.LIVE) onPlay(item) else onPlay(item.copy(metadataTag = "resume:${position.coerceAtLeast(0L)}"))
}

private val Blue = Color(0xFF3478F6)
private val Dark = Color.Black

@Composable
fun GassPlayerNavHost(vm: MainViewModel, app: GassPlayerApplication) {
    var route by remember { mutableStateOf("home") }
    // Reused by Live TV and Guida so category/EPG switches keep the parsed timeline warm.
    val epgSession = remember(app) { EpgGridSession() }
    var playerItem by remember { mutableStateOf<MediaItem?>(null) }
    // A Home trending title to open inside its own section (Film / Serie TV), consumed by that section.
    var pendingSectionDetail by remember { mutableStateOf<MediaItem?>(null) }
    var pendingSectionMeta by remember { mutableStateOf<MetadataResult?>(null) }
    var managerSourceId by remember { mutableStateOf<String?>(null) }
    var epgManageReturnRoute by remember { mutableStateOf("settings") }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    // "Guarda tutte le liste insieme" (Sorgenti): canali di tutte le sorgenti abilitate, con una sessione EPG
    // propria cosi' non disturba la guida della sorgente attiva.
    val allSourcesLive by vm.allSourcesLive.collectAsStateWithLifecycle()
    val allSourcesEpgSession = remember(app) { EpgGridSession() }
    LaunchedEffect(route) { if (route != "live-all") vm.closeAllSourcesLive() }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val favorite by vm.favorites.collectAsStateWithLifecycle()
    val watch by vm.watch.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tv = context.isTvOrFireTvDevice()
    DisposableEffect(playerItem != null, context) {
        val activity = context.closestMainActivity()
        if (playerItem != null) activity?.enterPlayerMode()
        onDispose { if (playerItem != null) activity?.exitPlayerMode() }
    }

    // Il player NON sostituisce piu' la schermata: la libreria resta in composizione sotto di lui
    // (invisibile e non focusabile). Prima un `return` rimuoveva tutto l'albero, quindi gruppo,
    // playlist, canale selezionato, ricerca, scroll e dettaglio aperto andavano persi e alla
    // chiusura si tornava alla vista di default. Cosi' all'uscita si riprende esattamente da dove
    // il player era stato aperto.
    val playerActive = playerItem != null
    val backTarget = destinationAfterRemoteBack(route, epgManageReturnRoute)
    // Fire TV's physical Back key must unwind in-app routes before exiting the activity. Nested
    // dialogs, the player, and Categorie TV rails keep precedence through their own BackHandlers.
    BackHandler(enabled = !playerActive && backTarget != null) {
        if (route == "source-manager") managerSourceId = null
        route = backTarget ?: "home"
    }

    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    // Personalizzazione come sheet sopra la vista che si sta modificando, come
    // HomeCustomizeSheet presentata con .sheet in SwiftUI. La libreria resta
    // montata sullo sfondo, così scroll, filtro e posizione non vengono persi.
    val customizationScope = when (route) {
        "home-customize" -> "home"
        "live-customize" -> "live"
        "movies-customize" -> "movies"
        "series-customize" -> "series"
        else -> null
    }
    val contentRoute = when (route) {
        "home-customize" -> "home"
        "live-customize" -> "live"
        "movies-customize" -> "movies"
        "series-customize" -> "series"
        else -> route
    }
    // Live TV / Film / Serie TV own the large title on the same row as
    // "Modifica". Do not render a shell/system heading or even a placeholder
    // row above it, otherwise the page appears to have a duplicate title.
    // "In evidenza" nella Home: la shell non aggiunge margini ne' il titolo, l'immagine arriva ai bordi (come iOS).
    val homeFeaturedEnabled = "featured" in normalizedHomeSectionOrder(settings.homeSectionOrder) &&
        "featured" !in settings.hiddenHomeSections
    val shellTitle = when (contentRoute) {
        "live", "movies", "series" -> ""
        "home" -> if (homeFeaturedEnabled) "" else "GassPlayer"
        "epg" -> "Guida TV"
        "search" -> "Cerca"
        "sources" -> "Sorgenti"
        "source-manager" -> "Gestisci sorgenti"
        "downloads" -> "Download"
        "settings" -> "Impostazioni"
        "vpn" -> "VPN"
        "parental" -> "Controllo genitori"
        "diagnostics" -> "Diagnostica"
        "backup" -> "Backup e migrazione"
        "epg-manage" -> "Fonti EPG"
        "merged" -> "Playlist unificate"
        "live-all" -> "Tutte le liste"
        else -> "GassPlayer"
    }
    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier
            .fillMaxSize()
            // alpha 0 = il layer sotto il player non viene nemmeno disegnato (nessun overdraw).
            .graphicsLayer { alpha = if (playerActive) 0f else 1f }
            .then(if (playerActive) Modifier.focusProperties { canFocus = false } else Modifier)
    ) {
    AdaptiveShell(
        route = contentRoute,
        tv = tv,
        playerActive = playerActive,
        allowNavReturnFocus = customizationScope == null,
        loading = loading,
        message = message,
        onRetry = { vm.refresh(true) },
        onRoute = { route = it },
        title = shellTitle,
        edgeToEdge = contentRoute == "home" && homeFeaturedEnabled,
        content = { activeRoute, entryFocusRequester ->
            when (activeRoute) {
                "home" -> {
                    // Home links to a main section open it AND enter it (rail group / first element), verified by
                    // the shell, instead of only changing the route and inferring the origin afterwards.
                    val openSection = LocalOpenSection.current
                    val openFromHome: (String) -> Unit = { target ->
                        if (openSection != null && target in homeLinkSections) openSection(target) else route = target
                    }
                    HomeScreen(vm, catalog, favorite, watch, sources, onRoute = openFromHome, onPlay = { playerItem = it }, entryFocusRequester = entryFocusRequester,
                        edgeToEdge = homeFeaturedEnabled,
                        onOpenInSection = { item, meta ->
                            pendingSectionDetail = item; pendingSectionMeta = meta
                            openFromHome(if (item.kind == MediaKind.SERIES) "series" else "movies")
                        })
                }
                "live" -> if (settings.groupUIStyle == "categorie_tv") {
                    // The same session/index powers both Live TV and the standalone Guida screen.
                    EpgGridScreen(app, vm, epgSession, catalog?.live.orEmpty(), catalog?.liveCategories.orEmpty(), favorite, settings,
                        onPlay = { playerItem = it }, tvCategoryMode = true, playerActive = playerActive,
                        screenActive = activeRoute == contentRoute, entryFocusRequester = entryFocusRequester)
                } else CatalogScreen("Live TV", catalog?.live.orEmpty(), catalog?.liveCategories.orEmpty(), favorite, parental, vm,
                    onPlay = { playerItem = it }, showNumbers = settings.showChannelNumbers, scopeKey = "live",
                    onCustomize = { route = "live-customize" }, isTelevision = tv, screenActive = activeRoute == contentRoute,
                    entryFocusRequester = entryFocusRequester)
                "movies" -> CatalogScreen("Film (VOD)", catalog?.movies.orEmpty(), catalog?.vodCategories.orEmpty(), favorite, parental, vm,
                    onPlay = { playerItem = it }, detail = true, scopeKey = "movies", onCustomize = { route = "movies-customize" }, isTelevision = tv,
                    screenActive = activeRoute == contentRoute, entryFocusRequester = entryFocusRequester,
                    openRequest = pendingSectionDetail?.takeIf { it.kind == MediaKind.MOVIE }, openRequestMeta = pendingSectionMeta,
                    onOpenRequestHandled = { pendingSectionDetail = null; pendingSectionMeta = null })
                "series" -> SeriesScreen(catalog?.series.orEmpty(), catalog?.episodes.orEmpty(), catalog?.seriesCategories.orEmpty(), favorite, parental, vm,
                    onPlay = { playerItem = it }, onCustomize = { route = "series-customize" }, isTelevision = tv,
                    screenActive = activeRoute == contentRoute, entryFocusRequester = entryFocusRequester,
                    openRequest = pendingSectionDetail?.takeIf { it.kind == MediaKind.SERIES }, openRequestMeta = pendingSectionMeta,
                    onOpenRequestHandled = { pendingSectionDetail = null; pendingSectionMeta = null })
                "epg" -> EpgGridScreen(app, vm, epgSession, catalog?.live.orEmpty(), catalog?.liveCategories.orEmpty(), favorite, settings,
                    onPlay = { playerItem = it }, playerActive = playerActive, screenActive = activeRoute == contentRoute,
                    entryFocusRequester = entryFocusRequester)
                "live-all" -> if (settings.groupUIStyle == "categorie_tv") {
                    EpgGridScreen(app, vm, allSourcesEpgSession, allSourcesLive?.live.orEmpty(), allSourcesLive?.liveCategories.orEmpty(), favorite, settings,
                        onPlay = { playerItem = it }, tvCategoryMode = true, playerActive = playerActive,
                        screenActive = activeRoute == contentRoute, entryFocusRequester = entryFocusRequester)
                } else CatalogScreen("Tutte le liste", allSourcesLive?.live.orEmpty(), allSourcesLive?.liveCategories.orEmpty(), favorite, parental, vm,
                    onPlay = { playerItem = it }, showNumbers = settings.showChannelNumbers, scopeKey = "live",
                    onCustomize = { route = "live-customize" }, isTelevision = tv, screenActive = activeRoute == contentRoute,
                    entryFocusRequester = entryFocusRequester)
                "epg-manage" -> ExternalEpgManageScreen(app, onBack = { route = epgManageReturnRoute }, entryFocusRequester = entryFocusRequester)
                "merged" -> MergedPlaylistScreen(app, entryFocusRequester = entryFocusRequester)
                "trakt" -> TraktScreen(app, settings, vm, entryFocusRequester = entryFocusRequester)
                "search" -> SearchScreen(app, catalog, onPlay = { playerItem = it }, onRoute = { route = it }, entryFocusRequester = entryFocusRequester)
                "sources" -> SourcesView(app, vm, onRoute = { route = it }, onManage = { managerSourceId = it.id; route = "source-manager" }, entryFocusRequester = entryFocusRequester)
                "source-manager" -> SourceManagerView(app, vm, initialSourceId = managerSourceId, entryFocusRequester = entryFocusRequester, onBack = { managerSourceId = null; route = "sources" }, onRoute = { next ->
                    if (next == "epg-manage") {
                        epgManageReturnRoute = "source-manager"
                        route = next
                    } else {
                        managerSourceId = null
                        route = next
                    }
                })
                "downloads" -> DownloadsScreen(app, entryFocusRequester = entryFocusRequester)
                "settings" -> SettingsHub(app, settings, vm, onOpen = { next -> if (next == "epg-manage") epgManageReturnRoute = "settings"; route = next }, entryFocusRequester = entryFocusRequester)
                "vpn" -> VpnScreen(app, entryFocusRequester = entryFocusRequester)
                "parental" -> ParentalScreen(vm, parental, entryFocusRequester = entryFocusRequester)
                "diagnostics" -> DiagnosticsScreen(app, entryFocusRequester = entryFocusRequester)
                "backup" -> BackupScreen(app, entryFocusRequester = entryFocusRequester)
            }
        },
        overlay = {
            if (customizationScope != null) {
                HomeCustomizationSheet(
                    vm = vm,
                    scopeKey = customizationScope,
                    onBack = { route = contentRoute },
                    isTv = tv
                )
            }
        }
    )
    }
    playerItem?.let { current ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Assorbe i tocchi: niente click "attraverso" il player sulla libreria sottostante.
                .pointerInput(Unit) {}
        ) {
            // Il player resta scuro anche col tema Chiaro (testi chiari sul video), come su iOS.
            DarkContentTheme {
            IosPlayerScreen(
                app, current, settings, catalog,
                onBack = { playerItem = null },
                onPip = { (context as? MainActivity)?.enterPlayerPip() },
                onNavigateToItem = { playerItem = it },
                onOpenSearch = { playerItem = null; route = "search" }
            )
            }
        }
    }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun AdaptiveShell(
    route: String,
    tv: Boolean,
    playerActive: Boolean,
    allowNavReturnFocus: Boolean,
    loading: Boolean,
    message: String?,
    onRetry: () -> Unit,
    onRoute: (String) -> Unit,
    title: String,
    /** Pagina senza margini (Home con "In evidenza"): la pagina gestisce da se' i propri margini. */
    edgeToEdge: Boolean = false,
    content: @Composable (String, FocusRequester?) -> Unit,
    overlay: @Composable () -> Unit,
) {
    val destinations = listOf("home" to "Home", "live" to "Live TV", "movies" to "Film", "series" to "Serie", "epg" to "Guida", "search" to "Cerca", "sources" to "Sorgenti", "settings" to "Impostazioni")
    val saveableStateHolder = rememberSaveableStateHolder()
    val railScrollState = rememberScrollState()
    val railScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // Pages that are composed but not the one on screen (and everything behind the player or the
    // customization sheet) must never be reachable by DPAD: see LocalFocusScopeEnabled.
    val pageFocusAllowed = allowNavReturnFocus && !playerActive
    // The tab that owns the current (possibly nested) route: used for selection visuals and Back/Left.
    val selectedTab = navigationRailDestination(route)
    var shellHasFocus by remember { mutableStateOf(true) }
    var railHasFocus by remember { mutableStateOf(false) }
    val navFocusRequesters = remember {
        destinations.associate { (id, _) -> id to FocusRequester() }
    }
    // Start with a predictable, visible focus target on remote-first devices. The Home rail node
    // doubles as the initial target, avoiding two FocusRequester nodes on the same control.
    val initialHomeFocusRequester = navFocusRequesters.getValue("home")
    var previousPlayerActive by remember { mutableStateOf(false) }
    // Elemento che aveva il focus quando si e' aperto il player (e la sezione in cui si trovava).
    val playerReturnFocus = remember { arrayOfNulls<FocusRequester>(1) }
    val playerReturnRoute = remember { arrayOfNulls<String>(1) }
    val playerSnapshotTaken = remember { booleanArrayOf(false) }
    if (playerActive) {
        // Copia presa nello stesso passaggio in cui il player compare, prima che i suoi pulsanti
        // prendano il focus.
        if (!playerSnapshotTaken[0]) {
            playerReturnFocus[0] = RemoteFocusMemory.current()
            playerReturnRoute[0] = route
            playerSnapshotTaken[0] = true
        }
    } else {
        playerSnapshotTaken[0] = false
    }
    // Route-specific entry points make DPAD RIGHT from the selected navigation item predictable.
    // Pages without a registered entry point keep native Compose spatial focus traversal.
    val routeEntryFocusRequesters = remember {
        listOf(
            "home", "live", "movies", "series", "epg", "search", "sources", "settings",
            "downloads", "merged", "trakt", "vpn", "parental", "diagnostics", "backup", "epg-manage", "source-manager"
        ).associateWith { FocusRequester() }
    }
    val railEntryRoutes = remember { setOf("live", "movies", "series") }
    // One controller per shell: Categorie TV rails register their "enter this section" handler here.
    val sectionEntry = remember { SectionEntryController() }
    // Number of side-navigation entries running (a counter, so a cancelled entry can never clear the
    // flag of the one that replaced it).
    var sectionEntriesInProgress by remember { mutableIntStateOf(0) }
    // The automatic focus move currently running (side-navigation OK/RIGHT or a Home link). Any key press
    // cancels it first, so a slow page can never pull focus away from the user afterwards.
    var autoEntryJob by remember { mutableStateOf<Job?>(null) }
    // Route currently being entered by openSectionFromContent: the generic route-change effect skips it.
    var pendingContentEntryRoute by remember { mutableStateOf<String?>(null) }
    // One focus-group requester per page route. requestFocus() on a group moves focus to its first
    // VISIBLE focusable child (top-left), so it always works, also after the page list was scrolled.
    val pageGroupRequesters = remember { HashMap<String, FocusRequester>() }
    fun pageGroupRequester(pageRoute: String): FocusRequester = pageGroupRequesters.getOrPut(pageRoute) { FocusRequester() }
    // Which page currently holds DPAD focus (null = side navigation / nothing).
    var focusedPageRoute by remember { mutableStateOf<String?>(null) }
    // Counts DPAD/remote key presses inside the shell: a pending automatic focus move is abandoned as
    // soon as the user presses a key, so it can never fight the user.
    var userKeyNonce by remember { mutableIntStateOf(0) }
    // Whether the side navigation held focus when the LAST key was pressed. A route change must be judged
    // by where the user was when pressing OK, not afterwards: when a Home link opens Live TV / Film /
    // Serie TV, the Home page (and its focused card) is removed in that same frame and Compose gives focus
    // to the first focusable element, the "Home" tab. Reading railHasFocus afterwards wrongly looked like a
    // side-navigation move, so the entry into the section was skipped and the blue outline stayed on Home.
    var keyPressedOnRail by remember { mutableStateOf(false) }
    val currentShellRoute by rememberUpdatedState(route)

    // Puts DPAD focus INSIDE the page of [target], in one step. Order:
    //  1. Live TV / Film / Serie TV in "Categorie TV" mode: the selected group of the rail (opened if
    //     collapsed). A just-opened page needs a frame or two to register its handler.
    //  2. From the side navigation only: spatial move RIGHT, where focusRestorer brings back the last
    //     focused element of that page. Accepted only if focus really landed in [target].
    //  3. The page focus group itself: first visible element of the page.
    //  4. The explicit route entry requester.
    suspend fun focusIntoPage(target: String, fromRail: Boolean): Boolean {
        if (target in railEntryRoutes) {
            // The rail's groups are built in the background; on a large catalogue (first visit, slow
            // Fire TV Stick) that can take several seconds. Keep waiting for the rail instead of giving up
            // after a fixed time: the old 3 s fallback focused the first content element, which collapses
            // the rail, so the section opened without its selected group. Stop only when focus is in the
            // section, the user presses a key, the section changes, or after a 15 s safety cap.
            val startKeys = userKeyNonce
            val startedAt = System.nanoTime()
            var lastHandlerAt = -1L
            while (true) {
                val handler = sectionEntry.handlerFor(target)
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                if (handler != null) {
                    lastHandlerAt = elapsedMs
                    if (handler()) return true
                }
                if (focusedPageRoute == target) return true // the page focused its own element (e.g. Riproduci)
                // The user pressed a key meanwhile: they are in control, do nothing more (true = stop here).
                if (userKeyNonce != startKeys) return true
                if (navigationRailDestination(currentShellRoute) != navigationRailDestination(target)) return false
                val nowMs = (System.nanoTime() - startedAt) / 1_000_000L
                // No rail on this page (another "UI Gruppi" style), or it went away (a detail sheet opened):
                // continue with the generic entry below.
                if (lastHandlerAt < 0L && nowMs >= 320L) break
                if (lastHandlerAt >= 0L && handler == null && nowMs - lastHandlerAt >= 400L) break
                if (nowMs >= 15_000L) break
                delay(if (handler != null) 80L else 16L)
            }
        }
        if (focusedPageRoute == target) return true // the page already focused its own element (e.g. Riproduci)
        if (fromRail && focusManager.moveFocus(FocusDirection.Right)) {
            delay(34L) // focus callbacks are delivered at the end of the frame
            if (focusedPageRoute == target) return true
        }
        for (attempt in 0 until 12) {
            if (focusedPageRoute == target) return true
            runCatching { pageGroupRequester(target).requestFocus() }
            delay(34L)
        }
        if (focusedPageRoute == target) return true
        return routeEntryFocusRequesters[target]?.requestFocusWithRetry(attempts = 4, delayMillis = 40L) ?: false
    }

    // OK / RIGHT on a side-navigation tab: open it if needed, then enter its page with ONE key press.
    suspend fun enterSection(id: String) {
        sectionEntriesInProgress++
        try {
            val owner = selectedTab == id
            if (!owner) {
                onRoute(id)
                // Wait for the shell to apply the new route (not a fixed delay: a slow frame made the entry
                // see the previous route and stop).
                var waited = 0
                do { delay(16L); waited++ } while (navigationRailDestination(currentShellRoute) != navigationRailDestination(id) && waited < 40)
            }
            focusIntoPage(if (owner) route else id, fromRail = true)
        } finally {
            sectionEntriesInProgress--
        }
    }
    // Home link / trending title -> open [target] and put focus INSIDE it. Explicit (no guessing where the
    // route change came from) and verified: shortly after entering, focus must really be in the page; if
    // something moved it (the system's automatic initial focus on the first tab, "Home", or a late page
    // effect) it enters again, up to 3 times. Any key press stops it (the user is in control).
    fun openSectionFromContent(target: String) {
        if (!tv) { onRoute(target); return }
        autoEntryJob?.cancel()
        pendingContentEntryRoute = target
        onRoute(target)
        val job = railScope.launch {
            val startKeys = userKeyNonce
            // Wait until the shell has really switched to [target] (next recomposition). Entering before that
            // made focusIntoPage see the OLD route ("home") for Live TV / Film / Serie TV and give up at once:
            // that is why only those links left the blue outline on "Home".
            var waited = 0
            while (navigationRailDestination(currentShellRoute) != navigationRailDestination(target) && waited < 40) {
                delay(16L)
                waited++
            }
            for (round in 0 until 3) {
                focusIntoPage(target, fromRail = false)
                if (userKeyNonce != startKeys) return@launch
                // Re-check after the frames in which late focus changes can happen.
                var stable = true
                for (check in 0 until 6) {
                    delay(70L)
                    if (userKeyNonce != startKeys) return@launch
                    if (focusedPageRoute != target) { stable = false; break }
                }
                if (stable) return@launch
            }
            // Could not enter (page without focusable content): at least select the section's tab.
            if (userKeyNonce == startKeys && focusedPageRoute != target) {
                navFocusRequesters[navigationRailDestination(target)]?.requestFocusWithRetry(attempts = 6, delayMillis = 40L)
            }
        }
        job.invokeOnCompletion { if (pendingContentEntryRoute == target) pendingContentEntryRoute = null }
        autoEntryJob = job
    }
    val openSectionState = rememberUpdatedState<(String) -> Unit> { target -> openSectionFromContent(target) }
    val stableOpenSection = remember { { target: String -> openSectionState.value(target) } }
    LaunchedEffect(tv) {
        if (tv) {
            delay(90L)
            initialHomeFocusRequester.requestFocusWithRetry(attempts = 6, delayMillis = 65L)
        }
    }
    val mainNavigationRoutes = remember { setOf("home", "live", "movies", "series", "epg", "search", "sources", "settings") }
    var previousFocusRoute by remember { mutableStateOf(route) }
    LaunchedEffect(route) {
        val previous = previousFocusRoute
        previousFocusRoute = route
        // Main-tab clicks keep focus on the selected rail item; entering/exiting a nested page
        // transfers focus to its first meaningful control, instead of leaving it on a disposed node.
        if (tv && !playerActive && route != previous) {
            if (route !in mainNavigationRoutes || previous !in mainNavigationRoutes) {
                delay(105L)
                routeEntryFocusRequesters[route]?.requestFocusWithRetry(attempts = 6, delayMillis = 45L)
            } else if (!keyPressedOnRail && sectionEntriesInProgress == 0 && pendingContentEntryRoute != route) {
                // A main section was opened from page content (any Home link): the page that owned focus
                // is now hidden. Move focus straight INTO the new section (its rail group or first visible
                // element); the side tab is highlighted by the route. Fall back to the tab, never nowhere.
                // Runs as its own job so a key press can cancel it (see the shell's onPreviewKeyEvent).
                val targetRoute = route
                autoEntryJob?.cancel()
                autoEntryJob = railScope.launch {
                    if (!focusIntoPage(targetRoute, fromRail = false) &&
                        navigationRailDestination(currentShellRoute) == navigationRailDestination(targetRoute)
                    ) {
                        navFocusRequesters[navigationRailDestination(targetRoute)]
                            ?.requestFocusWithRetry(attempts = 6, delayMillis = 40L)
                    }
                }
            }
        }
    }
    LaunchedEffect(playerActive, route) {
        val returnedFromPlayer = previousPlayerActive && !playerActive
        previousPlayerActive = playerActive
        if (tv && returnedFromPlayer) {
            // The player temporarily makes the page tree non-focusable. Restore to the current
            // section's semantic entry after the page is visible again, rather than the first
            // arbitrary node reported by Compose's spatial-search algorithm.
            delay(180L)
            // Prima di tutto: l'elemento da cui si e' aperto il player (canale, locandina, "Riproduci"...).
            val origin = playerReturnFocus[0]?.takeIf { playerReturnRoute[0] == route }
            playerReturnFocus[0] = null
            if (origin != null && origin.requestFocusWithRetry(attempts = 10, delayMillis = 50L)) return@LaunchedEffect
            val navRoute = navigationRailDestination(route)
            val entry = routeEntryFocusRequesters[route] ?: routeEntryFocusRequesters[navRoute]
            val restored = entry?.requestFocusWithRetry(attempts = 8, delayMillis = 55L) ?: false
            // The entry node may be absent (collapsed group rail, scrolled list): enter the page's first
            // visible element instead, and only as a last resort park on the side tab. Never leave focus lost.
            if (!restored && !focusIntoPage(route, fromRail = false)) {
                navFocusRequesters[navRoute]?.requestFocusWithRetry(attempts = 6, delayMillis = 45L)
            }
        }
    }
    // Focus recovery after ANY route change. When the focused node belongs to a page that has just
    // been hidden (Back from Live to Home, a tab switched by code, a nested page closed) Compose
    // clears focus, and the next DPAD press would pick an arbitrary node. If nothing in the shell
    // holds focus shortly after the change, park it on the owning tab, which is always visible.
    LaunchedEffect(route) {
        if (!tv || playerActive) return@LaunchedEffect
        delay(320L)
        if (!shellHasFocus) {
            navFocusRequesters[navigationRailDestination(route)]?.requestFocusWithRetry(attempts = 6, delayMillis = 45L)
        }
    }
    CompositionLocalProvider(
        LocalSectionEntryController provides sectionEntry,
        LocalOpenSection provides stableOpenSection,
        LocalNavRailFocusRequester provides (if (tv) navFocusRequesters[selectedTab] ?: navFocusRequesters.getValue("settings") else null),
    ) {
    LiquidGlassBackdrop(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { event ->
                    // Runs before any child handler: a new key press cancels a pending automatic focus
                    // move first; a side-navigation OK/RIGHT then starts its own entry.
                    if (event.type == KeyEventType.KeyDown) {
                        userKeyNonce++
                        keyPressedOnRail = railHasFocus
                        autoEntryJob?.cancel()
                        autoEntryJob = null
                    }
                    false
                }
                .onFocusChanged { shellHasFocus = it.hasFocus }
                .focusGroup()
        ) {
            CompositionLocalProvider(LocalFocusScopeEnabled provides pageFocusAllowed) {
            LiquidGlassSurface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(if (tv) 142.dp else 104.dp),
                cornerRadius = 26.dp,
                contentPadding = 7.dp
            ) {
                Column(
                    Modifier.fillMaxSize()
                        // The tab column is a closed focus box: UP/DOWN/LEFT never leave it by spatial
                        // search (that used to land on content tiles); RIGHT is handled by each tab.
                        .focusProperties {
                            if (!pageFocusAllowed) canFocus = false
                            exit = { direction ->
                                if (tv && (direction == FocusDirection.Up || direction == FocusDirection.Down ||
                                        direction == FocusDirection.Left)
                                ) FocusRequester.Cancel else FocusRequester.Default
                            }
                        }
                        .onFocusChanged { railHasFocus = it.hasFocus }
                        .focusGroup().verticalScroll(railScrollState).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    destinations.forEach { (id, label) ->
                        val bringIntoView = remember(id) { BringIntoViewRequester() }
                        var focused by remember(id) { mutableStateOf(false) }
                        Surface(
                            onClick = { onRoute(id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(navFocusRequesters.getValue(id))
                                .bringIntoViewRequester(bringIntoView)
                                .onPreviewKeyEvent { event ->
                                    val enterKey = event.key == Key.DirectionRight || event.key == Key.DirectionCenter ||
                                        event.key == Key.Enter || event.key == Key.NumPadEnter
                                    if (tv && event.type == KeyEventType.KeyDown && enterKey) {
                                        // RIGHT and OK both enter the section THIS tab stands for (opening it if needed),
                                        // landing on the last focused element of that page, never on a stray page.
                                        if (event.nativeKeyEvent.repeatCount == 0) autoEntryJob = railScope.launch { enterSection(id) }
                                        true
                                    } else false
                                }
                                .remoteFocusOutline(cornerRadius = 18.dp, enabled = tv)
                                .onFocusChanged { state ->
                                    focused = state.isFocused
                                    if (state.isFocused) railScope.launch { runCatching { bringIntoView.bringIntoView() } }
                                },
                            shape = RoundedCornerShape(18.dp),
                            color = Color.Transparent
                        ) {
                            LiquidGlassSurface(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 18.dp,
                                contentPadding = if (tv) 13.dp else 9.dp,
                                highlighted = selectedTab == id || focused,
                                suppressTopHighlight = true
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(navIcon(id), null, tint = if (selectedTab == id) Color(0xFF9ABEFF).readable() else glassForeground(.75f), modifier = Modifier.size(if (tv) 25.dp else 22.dp))
                                    Text(label, color = if (selectedTab == id) glassForeground() else glassForeground(.72f), fontSize = if (tv) 12.sp else 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = if (selectedTab == id) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
            } // end tab-column focus scope
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (edgeToEdge) Modifier else Modifier.padding(horizontal = if (tv) 28.dp else 16.dp, vertical = 18.dp))
            ) {
                if (title.isNotBlank()) {
                    Text(
                        title,
                        color = glassForeground(),
                        fontSize = if (tv) 32.sp else 26.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                }
                val showStatusBanner = loading || (!message.isNullOrBlank() && route != "sources" && route != "settings")
                Column(if (edgeToEdge && showStatusBanner) Modifier.padding(horizontal = if (tv) 28.dp else 16.dp).padding(top = 12.dp) else Modifier) {
                if (loading) {
                    Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF8CB7FF).readable(), trackColor = glassForeground(.08f))
                    Text("Caricamento playlist…", color = glassForeground().copy(.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                } else if (!message.isNullOrBlank() && route != "sources" && route != "settings") {
                    LiquidGlassSurface(Modifier.fillMaxWidth().padding(top = 8.dp), cornerRadius = 15.dp, contentPadding = 10.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFFFFB340).readable()); Spacer(Modifier.width(8.dp))
                            Text(message, color = Color(0xFFFFD08A).readable(), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            TextButton(onRetry) { Text("Riprova") }
                        }
                    }
                }
                }
                if (showStatusBanner) Spacer(Modifier.height(12.dp))
                val libraryRoutes = remember { setOf("live", "movies", "series") }
                var visitedLibraryRoutes by remember { mutableStateOf(emptySet<String>()) }
                // Keep a library page composed after its first visit. GassTV's section navigation
                // does not rebuild the whole list for every tab tap; doing this here preserves
                // LazyListState, selected category, search and EPG/player state across Live/VOD/Series.
                val renderedLibraryRoutes = if (route in libraryRoutes) visitedLibraryRoutes + route else visitedLibraryRoutes
                LaunchedEffect(route) {
                    if (route in libraryRoutes && route !in visitedLibraryRoutes) {
                        visitedLibraryRoutes = visitedLibraryRoutes + route
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        // DPAD LEFT must first move focus *inside* the page (previous grid column,
                        // category rail, etc.). Only when focus would leave the page area to the left
                        // do we redirect it to the selected app tab. The previous onKeyEvent handler
                        // consumed LEFT before Compose's spatial search, so it jumped to the rail
                        // from any column of a grid or any control of a row.
                        .focusProperties {
                            // The page is a closed focus box too: LEFT leaves only to the owning tab, and
                            // UP/DOWN/RIGHT can never jump out to the tab column or anywhere else.
                            exit = { direction ->
                                when {
                                    !tv -> FocusRequester.Default
                                    direction == FocusDirection.Left && pageFocusAllowed ->
                                        navFocusRequesters[selectedTab] ?: navFocusRequesters.getValue("settings")
                                    direction == FocusDirection.Left || direction == FocusDirection.Right ||
                                        direction == FocusDirection.Up || direction == FocusDirection.Down -> FocusRequester.Cancel
                                    else -> FocusRequester.Default
                                }
                            }
                        }
                        .focusGroup()
                ) {
                    renderedLibraryRoutes.forEach { section ->
                        key(section) {
                            val active = route == section
                            // A hidden page can no longer hold focus: never let a stale marker make an entry
                            // into another section look successful.
                            LaunchedEffect(active) { if (!active && focusedPageRoute == section) focusedPageRoute = null }
                            val pageAlpha by animateFloatAsState(
                                targetValue = if (active) 1f else 0f,
                                animationSpec = tween(if (active || renderedLibraryRoutes.size > 1) 110 else 0),
                                label = "${section}-section-alpha",
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = pageAlpha }
                                    .zIndex(if (active) 1f else 0f)
                                    // Only ever set canFocus = false: assigning true here used to make the inner
                                    // lists/rail containers themselves focusable, so the first OK/RIGHT landed on
                                    // an invisible container and a second press was needed.
                                    .focusProperties { if (!(active && pageFocusAllowed)) canFocus = false }
                                    .onFocusChanged { state ->
                                        if (state.hasFocus) focusedPageRoute = section
                                        else if (focusedPageRoute == section) focusedPageRoute = null
                                    }
                                    .focusRequester(pageGroupRequester(section))
                                    // No focusRestorer on these warm pages: Live TV / Film / Serie TV are entered
                                    // on their rail's selected group. A restorer redirected that request to the last
                                    // focused card, whose onFocus collapses the rail, and focus was lost.
                                    .focusGroup()
                                    .pointerInput(active) {
                                        if (!active) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                                }
                                            }
                                        }
                                    }
                            ) {
                                // Library screens remain warm while another library or a secondary
                                // page is open; screenActive stops Live TV's mini-player off-route.
                                CompositionLocalProvider(LocalFocusScopeEnabled provides (active && pageFocusAllowed)) {
                                    content(section, routeEntryFocusRequesters[section])
                                }
                            }
                        }
                    }
                    if (route !in libraryRoutes) {
                        AnimatedContent(
                            targetState = route,
                            modifier = Modifier.fillMaxSize().zIndex(2f)
                                .then(if (!pageFocusAllowed) Modifier.focusProperties { canFocus = false } else Modifier),
                            transitionSpec = {
                                EnterTransition.None togetherWith ExitTransition.None
                            },
                            label = "secondary-route-content",
                        ) { activeRoute ->
                            saveableStateHolder.SaveableStateProvider(activeRoute) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .onFocusChanged { state ->
                                            if (state.hasFocus) focusedPageRoute = activeRoute
                                            else if (focusedPageRoute == activeRoute) focusedPageRoute = null
                                        }
                                        .focusRequester(pageGroupRequester(activeRoute))
                                        .focusRestorer()
                                        .focusGroup()
                                ) {
                                    CompositionLocalProvider(LocalFocusScopeEnabled provides pageFocusAllowed) {
                                        content(activeRoute, routeEntryFocusRequesters[activeRoute])
                                    }
                                }
                            }
                        }
                    }
                    // Keep customization as a single overlay rather than animating two copies of it.
                    overlay()
                }
            }
        }
    }
    } // LocalSectionEntryController
}

/** Main sections reachable from Home links: they open with focus inside (see LocalOpenSection). */
private val homeLinkSections = setOf("live", "movies", "series", "epg", "search", "sources", "settings")

private fun navIcon(id: String) = when(id) { "home" -> Icons.Default.Home; "live" -> Icons.Default.LiveTv; "movies" -> Icons.Default.Movie; "series" -> Icons.Default.Tv; "epg" -> Icons.Default.CalendarMonth; "search" -> Icons.Default.Search; "sources" -> Icons.Default.SettingsInputAntenna; else -> Icons.Default.Settings }

private val homeSectionTitles = linkedMapOf(
    "heading" to "Intestazione",
    "search" to "Ricerca",
    "featured" to "In evidenza",
    "categories" to "Categorie",
    "continueWatching" to "Continua a guardare",
    "sourceCard" to "Sorgente",
    "sources" to "Sorgenti",
    "liveTV" to "Live TV",
    "guidaTV" to "Guida TV",
    "onDemand" to "On demand",
    "favoriteChannels" to "Canali preferiti",
    "favoriteSeries" to "Serie TV preferite",
    "favoriteMovies" to "Film preferiti",
    "trendingSeries" to "Serie di tendenza",
    "trendingMovies" to "Film di tendenza"
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeScreen(
    vm: MainViewModel,
    catalog: CatalogState?,
    fav: FavoriteState,
    watch: List<WatchEntry>,
    sources: List<MediaSourceConfig>,
    onRoute: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    entryFocusRequester: FocusRequester? = null,
    onOpenInSection: ((MediaItem, MetadataResult?) -> Unit)? = null,
    /** La shell non applica margini: la Home disegna "In evidenza" a tutta larghezza e i propri margini. */
    edgeToEdge: Boolean = false,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val remoteFirstDevice = LocalContext.current.isTvOrFireTvDevice()
    val activeSource by vm.activeSource.collectAsStateWithLifecycle()
    val allSectionOrder = remember(settings.homeSectionOrder) {
        // Ignore stale library-only sections from older Android preferences.
        normalizedHomeSectionOrder(settings.homeSectionOrder)
    }
    val visibleOrder = allSectionOrder.filterNot { settings.hiddenHomeSections.contains(it) }
    var homeMode by remember { mutableStateOf("overview") }
    var homeMenuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var selectedDetail by remember { mutableStateOf<MediaItem?>(null) }
    var trendingUnavailable by remember { mutableStateOf<String?>(null) }
    // "Continua a guardare" identico a iOS (IosHomeRails.kt): card calcolate fuori dal main thread.
    val continueCards = rememberContinueWatchingCards(
        watch, catalog, settings.homeContinueKind,
        enabled = "continueWatching" in visibleOrder || "featured" in visibleOrder
    )
    // "In evidenza": continua a guardare, per te, di tendenza (HomeFeatured.kt).
    val featuredEntries = rememberHomeFeaturedEntries(
        enabled = "featured" in visibleOrder && homeMode == "overview",
        app = vm.app,
        catalog = catalog,
        continueCards = continueCards,
        apiKey = settings.tmdbApiKey,
    )
    // Telecomando: primo puntino di "In evidenza" (GIU' dal pulsante, SU dalle sezioni sotto l'intestazione).
    val featuredFirstDotFocus = remember { FocusRequester() }
    val featuredAboveHeading = visibleOrder.indexOf("featured").let { f -> f >= 0 && f < visibleOrder.indexOf("heading") }
    val detailFocusScope = rememberCoroutineScope()
    val closeHomeDetail: () -> Unit = {
        selectedDetail = null
        if (remoteFirstDevice) detailFocusScope.launch {
            delay(90L)
            entryFocusRequester?.requestFocusWithRetry(attempts = 6, delayMillis = 45L)
        }
    }

    if (selectedDetail != null) {
        val detail = selectedDetail!!
        // In modalita' a tutta larghezza la shell non applica margini: la scheda li riprende qui.
        Box(if (edgeToEdge) Modifier.fillMaxSize().padding(horizontal = if (remoteFirstDevice) 28.dp else 16.dp, vertical = 18.dp) else Modifier.fillMaxSize()) {
        when (detail.kind) {
            MediaKind.MOVIE -> MovieDetailScreen(vm.app, detail, catalog?.movies.orEmpty(), fav, onBack = closeHomeDetail, onPlay = { item, pos -> onPlayWithPosition(onPlay, item, pos) }, entryFocusRequester = entryFocusRequester)
            MediaKind.SERIES -> SeriesDetailScreen(vm.app, detail, catalog?.episodes.orEmpty(), fav, onBack = closeHomeDetail, onPlay = { item, pos -> onPlayWithPosition(onPlay, item, pos) }, allSeries = catalog?.series.orEmpty(), onOpenAlternateSeries = { selectedDetail = it }, entryFocusRequester = entryFocusRequester)
            else -> onPlay(detail)
        }
        }
        return
    }

    fun openLibraryItem(item: MediaItem) {
        when (item.kind) {
            MediaKind.MOVIE, MediaKind.SERIES -> selectedDetail = item
            MediaKind.LIVE, MediaKind.EPISODE -> onPlay(item)
        }
    }

    // "Continua a guardare" riprende come la card della sezione omonima (il player chiede "Riprendi?");
    // "Maggiori informazioni" apre il titolo nella sua sezione, come le card di tendenza.
    fun openFeatured(entry: HomeFeaturedEntry) {
        when {
            entry.isResume -> onPlay(entry.item)
            onOpenInSection != null -> onOpenInSection(entry.item, entry.meta)
            else -> openLibraryItem(entry.item)
        }
    }

    // Trending card -> catalogue title, looked up off the main thread with the accurate matcher
    // (TrendMatching.kt). Not found: the "not in catalogue" notice. A newer press replaces an older one.
    val trendScope = rememberCoroutineScope()
    var trendLookup by remember { mutableStateOf<Job?>(null) }
    fun openTrend(trend: MetadataResult, isSeries: Boolean) {
        trendLookup?.cancel()
        trendLookup = trendScope.launch {
            val catalogue = if (isSeries) catalog?.series.orEmpty() else catalog?.movies.orEmpty()
            val match = findTrendInCatalogue(catalogue, trend, if (isSeries) MediaKind.SERIES else MediaKind.MOVIE)
            when {
                match == null -> trendingUnavailable = trend.title
                // Open the title INSIDE its section (Film / Serie TV), focus on its play button.
                onOpenInSection != null -> onOpenInSection(match, trend)
                else -> openLibraryItem(match)
            }
        }
    }

    LaunchedEffect(query) {
        kotlinx.coroutines.delay(250)
        debouncedQuery = query.trim()
    }
    val searching = query.trim().isNotEmpty()
    val renderedOrder = if (searching) visibleOrder.filter { it == "heading" || it == "search" } else visibleOrder
    val homeSearchResults = remember(debouncedQuery, catalog) { if (debouncedQuery.length < 2) emptyList() else catalog?.search(debouncedQuery, 100).orEmpty() }
    val menuItems = listOf("overview" to "Home", "live" to "Preferiti Live TV", "movies" to "Preferiti Film", "series" to "Preferiti Serie TV")
    val favoriteItems = remember(homeMode, catalog, fav) {
        when (homeMode) {
            "live" -> catalog?.byIds(MediaKind.LIVE, fav.live).orEmpty()
            "movies" -> catalog?.byIds(MediaKind.MOVIE, fav.movies).orEmpty()
            "series" -> catalog?.byIds(MediaKind.SERIES, fav.series).orEmpty()
            else -> emptyList()
        }
    }

    val homeHorizontalPadding = if (remoteFirstDevice) 28.dp else 16.dp
    // The iOS Home menu is always available, even if the user hides the heading section.
    val homeHeader: @Composable () -> Unit = {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Surface(
                onClick = { homeMenuOpen = true },
                modifier = Modifier
                    .then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .remoteFocusOutline(cornerRadius = 50.dp),
                shape = RoundedCornerShape(50),
                color = Color.Transparent,
            ) {
                LiquidGlassPill(selected = true) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(when (homeMode) { "live" -> Icons.Default.LiveTv; "movies" -> Icons.Default.Movie; "series" -> Icons.Default.Tv; else -> Icons.Default.Home }, null, tint = Color(0xFF9ABEFF).readable(), modifier = Modifier.size(17.dp))
                        Text(menuItems.first { it.first == homeMode }.second, color = glassForeground(), fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Icon(Icons.Default.ExpandMore, null, tint = glassForeground().copy(.75f), modifier = Modifier.size(17.dp))
                    }
                }
            }
            DropdownMenu(homeMenuOpen, { homeMenuOpen = false }) {
                menuItems.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { homeMode = id; homeMenuOpen = false }, leadingIcon = { Icon(when (id) { "live" -> Icons.Default.LiveTv; "movies" -> Icons.Default.Movie; "series" -> Icons.Default.Tv; else -> Icons.Default.Home }, null) }, trailingIcon = { if (homeMode == id) Icon(Icons.Default.Check, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(onClick = { onRoute("home-customize") }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp), color = Color.Transparent, shape = RoundedCornerShape(50)) {
            LiquidGlassPill {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Tune, null, tint = glassForeground().copy(.85f), modifier = Modifier.size(16.dp))
                    Text("Personalizza", color = glassForeground(), fontSize = 13.sp)
                }
            }
        }
    }
    }
    val renderSection: @Composable (String) -> Unit = { section ->
        when (section) {
            "heading" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Tutto il tuo intrattenimento", color = glassForeground(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(if (activeSource != null) "Scegli cosa guardare dalla sorgente attiva." else "Configura una sorgente dalle Impostazioni quando vuoi.", color = glassForeground().copy(.64f), fontSize = 14.sp)
                }
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.onPreviewKeyEvent { event ->
                        // SU dai tasti Live TV / Film / Serie / Guida TV: il primo puntino di "In evidenza".
                        if (remoteFirstDevice && featuredAboveHeading && featuredEntries.size > 1 &&
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                        ) {
                            runCatching { featuredFirstDotFocus.requestFocus(); true }.getOrDefault(false)
                        } else false
                    }
                ) {
                    item { HomeActionChip("Live TV", Icons.Default.LiveTv) { onRoute("live") } }
                    item { HomeActionChip("Film", Icons.Default.Movie) { onRoute("movies") } }
                    item { HomeActionChip("Serie", Icons.Default.Tv) { onRoute("series") } }
                    item { HomeActionChip("Guida TV", Icons.Default.CalendarMonth) { onRoute("epg") } }
                }
            }
            "search" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EpgStyleSearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Cerca Live TV, film e serie…"
                )
                if (!searching) {
                    Text("Ricerca globale nella sorgente attiva", color = glassForeground().copy(.5f), fontSize = 12.sp)
                } else if (query.trim().length < 2) {
                    Text("Digita almeno due caratteri", color = glassForeground().copy(.55f), fontSize = 12.sp)
                } else if (debouncedQuery != query.trim()) {
                    Text("Ricerca in corso…", color = glassForeground().copy(.55f), fontSize = 12.sp)
                } else if (activeSource == null) {
                    EmptyHint("Attiva una sorgente dalle Impostazioni per cercare nei suoi titoli.")
                } else if (homeSearchResults.isEmpty()) {
                    EmptyHint("Nessun risultato per “${query.trim()}”")
                } else {
                    Text("${homeSearchResults.size} risultati", color = glassForeground().copy(.62f), fontSize = 12.sp)
                    val grouped = listOf(
                        MediaKind.LIVE to "Live TV",
                        MediaKind.MOVIE to "Film",
                        MediaKind.SERIES to "Serie TV",
                        MediaKind.EPISODE to "Episodi"
                    )
                    grouped.forEach { (kind, label) ->
                        val resultGroup = homeSearchResults.filter { it.kind == kind }
                        if (resultGroup.isNotEmpty()) Section(label, null) {
                            androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(resultGroup.take(16), key = { it.id }) { item -> MediaCard(item, vm.appFavorite(fav, item), onClick = { openLibraryItem(item) }, onToggleFavorite = { vm.toggleFavorite(item) }) }
                            }
                        }
                    }
                }
            }
            "continueWatching" -> ContinueWatchingSection(
                app = vm.app,
                cards = continueCards,
                kindFilter = settings.homeContinueKind,
                // Come iOS: il player chiede "Riprendi la visione?" con la posizione salvata.
                        onResume = { card -> onPlay(card.playItem) },
            )
            "sourceCard" -> {
                val activeName = sources.firstOrNull { it.id == activeSource }?.name
                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 18.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background((if (activeName == null) Color(0xFF9ABEFF) else Color(0xFF54D6A0)).copy(.16f)), contentAlignment = Alignment.Center) {
                                Icon(if (activeName == null) Icons.Default.AutoAwesome else Icons.Default.CheckCircle, null, tint = if (activeName == null) Color(0xFF9ABEFF).readable() else Color(0xFF54D6A0).readable(), modifier = Modifier.size(25.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(if (activeName == null) "Inizia quando vuoi" else "Sorgente pronta", color = glassForeground(), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(activeName ?: "Nessuna sorgente configurata", color = glassForeground().copy(.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${sources.size} sorgenti · ${catalog?.allItems?.size ?: 0} elementi", color = glassForeground().copy(.48f), fontSize = 12.sp)
                            }
                            if (sources.size > 1 && activeSource != null) {
                                Box {
                                    Surface(onClick = { sourceMenuOpen = true }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp), color = Color.Transparent, shape = RoundedCornerShape(50)) {
                                        LiquidGlassPill {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("Cambia", color = glassForeground(), fontSize = 12.sp)
                                                Icon(Icons.Default.ExpandMore, null, tint = glassForeground().copy(.7f), modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                    DropdownMenu(sourceMenuOpen, { sourceMenuOpen = false }) {
                                        sources.forEach { source ->
                                            DropdownMenuItem(
                                                text = { Text(source.name) },
                                                leadingIcon = { Icon(Icons.Default.Storage, null) },
                                                trailingIcon = { if (source.id == activeSource) Icon(Icons.Default.Check, null) },
                                                onClick = { vm.setActive(source.id); sourceMenuOpen = false }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true)
                                            )
                                        }
                                    }
                                }
                            }
                            OutlinedButton(onClick = { onRoute("sources") }) { Text(if (activeName == null) "Aggiungi" else "Gestisci") }
                        }
                        if (activeName == null) Text("Aggiungi una playlist M3U o un account supportato dalle Impostazioni. Live TV, film e serie appariranno qui dopo il caricamento.", color = glassForeground().copy(.62f), fontSize = 13.sp)
                    }
                }
            }
            "sources" -> Section("Sorgenti", null) {
                Surface(onClick = { onRoute("sources") }, color = Color.Transparent, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 20.dp)) {
                    LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, contentPadding = 16.dp) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF3478F6).copy(.15f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Layers, null, tint = Color(0xFF9ABEFF).readable(), modifier = Modifier.size(23.dp)) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Sorgenti", color = glassForeground(), fontWeight = FontWeight.SemiBold)
                                Text(if (sources.isEmpty()) "Nessuna sorgente configurata" else "${sources.size} sorgenti configurate", color = glassForeground().copy(.58f), fontSize = 12.sp)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = glassForeground().copy(.42f))
                        }
                    }
                }
            }
            "liveTV" -> HomeDestinationCard("Live TV", if (activeSource == null) "Disponibile con una sorgente" else "${catalog?.live?.size ?: 0} canali disponibili", if (activeSource == null) "I canali appariranno qui" else "Canali in diretta dalla sorgente attiva", Icons.Default.LiveTv, Color(0xFFFF6961).readable(), "Apri Live TV") { onRoute("live") }
            "guidaTV" -> HomeDestinationCard("Guida TV", "EPG e promemoria locali", "Consulta la programmazione e vai al canale che vuoi guardare.", Icons.Default.CalendarMonth, Color(0xFF9ABEFF).readable(), "Apri guida") { onRoute("epg") }
            "favoriteChannels" -> {
                val list = catalog?.byIds(MediaKind.LIVE, fav.live).orEmpty()
                if (list.isNotEmpty()) Section("Canali preferiti", { homeMode = "live" }) {
                    androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(list.take(20), key = { it.id }) { item -> MediaCard(item, true, onClick = { onPlay(item) }, onToggleFavorite = { vm.toggleFavorite(item) }) } }
                }
            }
            "favoriteSeries" -> {
                val list = catalog?.byIds(MediaKind.SERIES, fav.series).orEmpty()
                if (list.isNotEmpty()) Section("Serie TV preferite", { homeMode = "series" }) {
                    androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(list.take(20), key = { it.id }) { item -> MediaCard(item, true, onClick = { openLibraryItem(item) }, onToggleFavorite = { vm.toggleFavorite(item) }) } }
                }
            }
            "favoriteMovies" -> {
                val list = catalog?.byIds(MediaKind.MOVIE, fav.movies).orEmpty()
                if (list.isNotEmpty()) Section("Film preferiti", { homeMode = "movies" }) {
                    androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(list.take(20), key = { it.id }) { item -> MediaCard(item, true, onClick = { openLibraryItem(item) }, onToggleFavorite = { vm.toggleFavorite(item) }) } }
                }
            }
            "onDemand" -> Section("On demand", null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeCompactDestinationCard("VOD", if (activeSource == null) "Disponibile con una sorgente" else "Film e contenuti on demand", Icons.Default.Movie, Color(0xFFB19CFF).readable(), Modifier.weight(1f)) { onRoute("movies") }
                    HomeCompactDestinationCard("Serie TV", if (activeSource == null) "Disponibile con una sorgente" else "Scopri le tue serie", Icons.Default.Tv, Color(0xFF8CB7FF).readable(), Modifier.weight(1f)) { onRoute("series") }
                }
            }
            "trendingSeries" -> HomeTrendingRail(
                title = "Serie di tendenza",
                isSeries = true,
                apiKey = settings.tmdbApiKey,
                tmdb = vm.app.tmdb,
                onSelect = { trend -> openTrend(trend.toMetadata(), isSeries = true) },
            )
            "trendingMovies" -> HomeTrendingRail(
                title = "Film di tendenza",
                isSeries = false,
                apiKey = settings.tmdbApiKey,
                tmdb = vm.app.tmdb,
                onSelect = { trend -> openTrend(trend.toMetadata(), isSeries = false) },
            )
            "featured" -> if (featuredEntries.isNotEmpty()) HomeFeaturedHero(
                entries = featuredEntries,
                isTelevision = remoteFirstDevice,
                edgeToEdge = false,
                onSelect = { openFeatured(it) },
                firstDotFocusRequester = featuredFirstDotFocus,
            )
        }
    }
    // A tutta larghezza (come iOS): ricerca/intestazione iniziali e barra "Home" sopra l'immagine di "In evidenza".
    val leadingTop = if (edgeToEdge) visibleOrder.takeWhile { it == "search" || it == "heading" } else emptyList()
    val heroInTop = edgeToEdge && homeMode == "overview" && !searching && featuredEntries.isNotEmpty() &&
        visibleOrder.getOrNull(leadingTop.size) == "featured"

    Column(
        Modifier
            .fillMaxSize()
            .then(if (edgeToEdge && homeMode != "overview") Modifier.padding(horizontal = homeHorizontalPadding, vertical = 18.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!edgeToEdge || homeMode != "overview") homeHeader()

        if (homeMode != "overview") {
            if (favoriteItems.isEmpty()) {
                LiquidGlassSurface(Modifier.fillMaxWidth(), contentPadding = 22.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.FavoriteBorder, null, tint = Color(0xFF9ABEFF).readable(), modifier = Modifier.size(30.dp))
                        Text("Nessun preferito", color = glassForeground(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Aggiungi contenuti ai preferiti per ritrovarli qui.", color = glassForeground().copy(.68f))
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = libraryGridMin(settings, homeMode == "live")),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    items(favoriteItems, key = { it.id }) { item ->
                        MediaCard(item, true, onClick = { if (homeMode == "live") onPlay(item) else openLibraryItem(item) }, onToggleFavorite = { vm.toggleFavorite(item) })
                    }
                }
            }
        } else if (!edgeToEdge) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 48.dp)
            ) {
                items(renderedOrder, key = { it }) { section ->
                    Box(Modifier.animateItem()) { renderSection(section) }
                }
            }
        } else {
            // Telecomando: finche' il focus e' dentro "In evidenza" (pulsante, puntini) la sezione resta
            // interamente visibile in cima; la Home scorre verso il basso solo quando il focus passa alle
            // sezioni sotto l'intestazione (Live TV / Film / Serie TV...).
            val edgeListState = rememberLazyListState()
            val edgeScrollScope = rememberCoroutineScope()
            LazyColumn(
                state = edgeListState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 48.dp)
            ) {
                item(key = "home-top") {
                    val top: @Composable () -> Unit = {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = homeHorizontalPadding).padding(top = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            homeHeader()
                            leadingTop.forEach { section -> if (section in renderedOrder) renderSection(section) }
                        }
                    }
                    if (heroInTop) {
                        HomeFeaturedHero(
                            entries = featuredEntries,
                            isTelevision = remoteFirstDevice,
                            edgeToEdge = true,
                            onSelect = { openFeatured(it) },
                            horizontalPadding = homeHorizontalPadding,
                            topContent = top,
                            firstDotFocusRequester = featuredFirstDotFocus,
                            onFocusInside = {
                                edgeScrollScope.launch {
                                    // Dopo lo scorrimento automatico verso l'elemento con il focus.
                                    kotlinx.coroutines.delay(40L)
                                    edgeListState.animateScrollToItem(0)
                                }
                            },
                        )
                    } else {
                        top()
                    }
                }
                items(renderedOrder.filter { it !in leadingTop && !(heroInTop && it == "featured") }, key = { it }) { section ->
                    Box(Modifier.animateItem().padding(horizontal = homeHorizontalPadding)) { renderSection(section) }
                }
            }
        }
    }


    trendingUnavailable?.let {
        // Stesso avviso di HomeView.swift.
        AlertDialog(
            onDismissRequest = { trendingUnavailable = null },
            title = { Text("Non disponibile nella tua playlist") },
            text = { Text("Questo titolo di tendenza non è presente nella sorgente attiva.") },
            confirmButton = { TextButton(onClick = { trendingUnavailable = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun HomeActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp), shape = RoundedCornerShape(50), color = Color.Transparent) {
        LiquidGlassPill {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(icon, null, tint = Color(0xFF9ABEFF).readable(), modifier = Modifier.size(16.dp))
                Text(label, color = glassForeground(), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HomeCompactDestinationCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 150.dp).remoteFocusOutline(cornerRadius = 22.dp), color = Color.Transparent, shape = RoundedCornerShape(22.dp)) {
        LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 16.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(.16f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(title, color = glassForeground(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(subtitle, color = glassForeground().copy(.58f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeDestinationCard(title: String, metric: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, buttonLabel: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(buttonLabel) }
        }
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 22.dp), shape = RoundedCornerShape(22.dp), color = Color.Transparent) {
            LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 22.dp, contentPadding = 18.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(.16f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(27.dp)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, color = glassForeground(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(metric, color = glassForeground().copy(.76f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(subtitle, color = glassForeground().copy(.56f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = glassForeground().copy(.42f))
                }
            }
        }
    }
}

/** Which cover holds DPAD focus in VOD / Serie TV and whether it sits on the left edge of its row. */
@Stable
private class LeftEdgeCoverFocus {
    var key by mutableStateOf<String?>(null)
    var atEdge by mutableStateOf(false)
    val active: Boolean get() = key != null && atEdge
    /** A cover NOT on the left edge holds focus: LEFT must navigate to the previous cover. */
    val blocksRail: Boolean get() = key != null && !atEdge
}

/** Order-safe: a cover clears the state only if it still owns it when it loses focus. */
private fun Modifier.trackLeftEdgeCover(state: LeftEdgeCoverFocus, key: String, atEdge: Boolean): Modifier =
    onFocusChanged {
        if (it.hasFocus) {
            state.key = key
            state.atEdge = atEdge
        } else if (state.key == key) {
            state.key = null
            state.atEdge = false
        }
    }

@Composable private fun Section(title: String, onSeeAll: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (onSeeAll != null) TextButton(onClick = onSeeAll, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp)) { Text("Vedi tutto", color = Color(0xFF9ABEFF).readable()) }
        }
        content()
    }
}

@Composable private fun EmptyHint(text: String) { Text(text, color = glassForeground().copy(.52f), modifier = Modifier.padding(vertical = 12.dp), fontSize = 13.sp) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(
    item: MediaItem,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    widthOverride: Dp? = null,
    onFocus: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    additionalFocusRequester: FocusRequester? = null,
) {
    val w = widthOverride ?: if (item.kind == MediaKind.LIVE) 190.dp else 160.dp
    val h = w * if (item.kind == MediaKind.LIVE) (108f / 190f) else (220f / 160f)
    var focused by remember(item.id) { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.025f else 1f, label = "media-card-focus")
    Surface(
        modifier = Modifier.width(w)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(additionalFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus?.invoke() }
            .remoteFocusOutline(cornerRadius = 18.dp)
            .combinedClickable(
                interactionSource = null,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
                onLongClick = { onToggleFavorite?.invoke() }
            ),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(if (focused) 1.25.dp else .55.dp, glassForeground(if (focused) .42f else .15f))
    ) {
        LiquidGlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 0.dp, highlighted = focused) {
            Column {
                Box(Modifier.fillMaxWidth().height(h).background(Color(0xFF0C0C0C))) {
                    if (item.kind != MediaKind.LIVE) {
                        item.backdropUrl?.let { ArtworkImage(it, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        // M3U movies/series only carry tvg-logo: use it as poster instead of an empty cell.
                        ArtworkImage(item.posterUrl ?: item.logoUrl, item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                            fallback = { if (item.backdropUrl.isNullOrBlank()) ArtworkPlaceholderIcon(item.kind, size = 34.dp, alpha = .45f) })
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.30f)))))
                    if (item.kind == MediaKind.LIVE) ArtworkImage(item.logoUrl ?: item.posterUrl, item.title, modifier = Modifier.fillMaxSize().padding(18.dp), contentScale = ContentScale.Fit,
                        fallback = { ArtworkPlaceholderIcon(MediaKind.LIVE, size = 34.dp, alpha = .55f) })
                    if (onToggleFavorite != null) {
                        Surface(
                            onClick = onToggleFavorite,
                            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(34.dp).remoteFocusOutline(cornerRadius = 50.dp),
                            shape = RoundedCornerShape(50),
                            color = Color.Black.copy(.35f),
                            border = BorderStroke(.7.dp, Color.White.copy(.28f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (favorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
                                    tint = if (favorite) Color(0xFFFF91AD) else Color.White.copy(.92f),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, color = glassForeground(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    item.group?.takeIf { it.isNotBlank() }?.let { Text(it, color = glassForeground().copy(.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private data class LibraryCategoryEntry(val filterId: String, val name: String)

/**
 * LazyColumn item index of the first catalogue result for TV rail -> content DPAD movement.
 * In Categorie TV mode the standalone category-tile section is hidden, so every counted entry
 * here maps one-to-one to an item emitted before the first media row.
 */
internal fun libraryFirstResultItemIndex(
    visibleSections: List<String>,
    groupAfterSearch: Boolean,
    showContinue: Boolean,
    showTrending: Boolean,
): Int {
    var index = if (groupAfterSearch) 0 else 1 // group/header row before sections
    visibleSections.forEach { section ->
        when (section) {
            "search" -> {
                index++
                if (groupAfterSearch) index++ // group selector below search
            }
            "continueWatching" -> if (showContinue) index++
            "trendingMovies", "trendingSeries" -> if (showTrending) index++
        }
    }
    return index.coerceAtLeast(0)
}

/** Category tiles from the independently configurable iOS "Categorie" section. */
private fun buildLibraryCategoryEntries(categories: List<Category>, items: List<MediaItem>): List<LibraryCategoryEntry> {
    val counts = items.categoryCounts()
    val visible = categories.distinctBy { it.id }
        .filter { (counts[it.id] ?: 0) > 0 }
        .map { LibraryCategoryEntry(it.id, it.name) }
    val knownIds = visible.mapTo(HashSet()) { it.filterId }
    val other = counts.keys.filterNotNull()
        .filter { (counts[it] ?: 0) > 0 && it !in knownIds }
        .map { LibraryCategoryEntry(it, it) }
    val uncategorized = if ((counts[null] ?: 0) > 0) listOf(LibraryCategoryEntry(GROUP_NONE, "Senza categoria")) else emptyList()
    return visible + other + uncategorized
}

@Composable
private fun LibraryScreenTitle(
    title: String,
    onCustomize: () -> Unit,
    onFocus: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    entryFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = glassForeground(),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Surface(
            onClick = onCustomize,
            modifier = Modifier
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { if (it.isFocused) onFocus?.invoke() }
                .onPreviewKeyEvent { event ->
                    // DOWN from "Modifica" goes straight to the "…" menu button of the header (when it
                    // is composed); otherwise the normal spatial move applies.
                    if (downFocusRequester != null && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        runCatching { downFocusRequester.requestFocus() }.getOrDefault(false)
                    } else false
                }
                .remoteFocusOutline(cornerRadius = 50.dp),
            shape = RoundedCornerShape(50),
            color = Color.Transparent,
        ) {
            LiquidGlassPill {
                Row(
                    Modifier.height(26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Tune, null, tint = glassForeground().copy(.9f), modifier = Modifier.size(16.dp))
                    Text("Modifica", color = glassForeground(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun LibraryCategoryTile(
    name: String,
    selected: Boolean,
    width: Dp,
    onClick: () -> Unit
) {
    var focused by remember(name) { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.width(width).height(68.dp)
            .onFocusChanged { focused = it.isFocused }
            .remoteFocusOutline(cornerRadius = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(
            when { focused -> 2.dp; selected -> 1.4.dp; else -> .5.dp },
            when { focused -> Color(0xFF8CB7FF); selected -> Color(0xFF82ACFF).copy(.9f); else -> glassForeground().copy(.16f) }
        )
    ) {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 16.dp,
            contentPadding = 0.dp,
            highlighted = selected || focused,
            suppressTopHighlight = true
        ) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text(
                    name,
                    color = glassForeground(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CatalogScreen(
    title: String,
    items: List<MediaItem>,
    categories: List<Category>,
    favorite: FavoriteState,
    parental: ParentalState,
    vm: MainViewModel,
    onPlay: (MediaItem) -> Unit,
    showNumbers: Boolean = false,
    detail: Boolean = false,
    scopeKey: String = if (detail) "movies" else "live",
    onCustomize: () -> Unit = {},
    isTelevision: Boolean = false,
    screenActive: Boolean = true,
    entryFocusRequester: FocusRequester? = null,
    openRequest: MediaItem? = null,
    openRequestMeta: MetadataResult? = null,
    onOpenRequestHandled: () -> Unit = {},
) {
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    // TMDB metadata already known for the opened title (trending): the detail shows it at once.
    var openedMeta by remember { mutableStateOf<Pair<String, MetadataResult>?>(null) }
    // A title opened from Home (trending) is shown here, inside its own section, as a detail sheet.
    LaunchedEffect(openRequest, screenActive) {
        val request = openRequest
        if (request != null && screenActive) {
            openedMeta = openRequestMeta?.let { request.id to it }
            selected = request
            onOpenRequestHandled()
        }
    }
    val trendScope = rememberCoroutineScope()
    var trendLookup by remember { mutableStateOf<Job?>(null) }
    var filter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember(scopeKey) { mutableStateOf("") }
    var showTrendUnavailable by remember { mutableStateOf(false) }
    // "Categorie TV": the collapsed rail opens with LEFT ONLY from a cover on the left edge (first card
    // of a row / first grid column); everywhere else LEFT keeps navigating.
    val leftEdgeCover = remember { LeftEdgeCoverFocus() }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val libSettings by vm.settings.collectAsStateWithLifecycle()
    val watch by vm.watch.collectAsStateWithLifecycle()
    val tvCategoryMode = libSettings.groupUIStyle == "categorie_tv"
    val compactCategoryLayout = tvCategoryMode && LocalConfiguration.current.screenWidthDp < 700
    val mobileRailWidth = (LocalConfiguration.current.screenWidthDp - 136).dp.coerceIn(160.dp, 236.dp)
    var categoryRailExpanded by remember(scopeKey) { mutableStateOf(true) }
    val favoriteIds = if (scopeKey == "live") favorite.live else favorite.movies
    LaunchedEffect(screenActive) {
        if (!screenActive) {
            // A hidden VOD tab must not reopen a stale detail sheet when the tab is tapped again.
            selected = null
            showTrendUnavailable = false
        }
    }
    if (detail && selected != null && screenActive) {
        MovieDetailScreen(
            vm.app, selected!!, vm.catalog.value?.movies.orEmpty(), favorite,
            onBack = {
                selected = null
                if (tvCategoryMode) categoryRailExpanded = true
                if (isTelevision) scrollScope.launch { delay(90L); entryFocusRequester?.requestFocusWithRetry(attempts = 6, delayMillis = 45L) }
            },
            onPlay = { i, pos -> onPlayWithPosition(onPlay, i, pos) },
            entryFocusRequester = entryFocusRequester,
            initialMeta = openedMeta?.takeIf { it.first == selected?.id }?.second,
        )
        return
    }
    BackHandler(enabled = screenActive && tvCategoryMode && !categoryRailExpanded) { categoryRailExpanded = true }
    val defaults = if (scopeKey == "live") defaultLiveSections else defaultMovieSections
    val rawOrder = if (scopeKey == "live") libSettings.liveSectionOrder else libSettings.movieSectionOrder
    val hiddenSections = if (scopeKey == "live") libSettings.hiddenLiveSections else libSettings.hiddenMovieSections
    val sectionOrder = remember(rawOrder, defaults) { (rawOrder + defaults).distinct() }
    val visibleSections = sectionOrder.filterNot { it in hiddenSections || ((isTelevision || tvCategoryMode) && it == "categories") }
    // The "Categorie" tile grid is a configurable content section, separate from
    // the playlist group selector in LibraryHeader (like iOS ChannelGridView).
    // Hiding/reordering that grid must not reset the currently selected group.
    LaunchedEffect(visibleSections.contains("search")) { if (!visibleSections.contains("search")) searchQuery = "" }

    val categoryEntries by produceState(
        initialValue = emptyList<LibraryCategoryEntry>(), categories, items, visibleSections.contains("categories"),
    ) {
        value = if (visibleSections.contains("categories")) {
            withContext(Dispatchers.IO) { buildLibraryCategoryEntries(categories, items) }
        } else emptyList()
    }
    // Searching spans the whole library, independently of the selected group, as in iOS.
    val filtered by produceState<List<MediaItem>>(
        initialValue = if (filter == null && searchQuery.isBlank() && parental.lockedIds.isEmpty()) items else emptyList(),
        items, filter, parental.lockedIds, searchQuery, favoriteIds,
    ) {
        value = withContext(Dispatchers.IO) {
            val base = when {
                searchQuery.isNotBlank() -> items
                filter == TV_CATEGORY_FAVORITES -> items.onlyIds(favoriteIds)
                else -> items.inCategory(filter)
            }
            base.withoutIds(parental.lockedIds).matchingText(searchQuery).also { it.size }
        }
    }
    // "Continua a guardare" di VOD: solo film (kindFilter "movie"), identico a ChannelGridView.swift.
    val continueCards = rememberContinueWatchingCards(
        watch, vm.catalog.collectAsStateWithLifecycle().value, "movie",
        enabled = detail && "continueWatching" in visibleSections
    )
    val firstMediaFocusRequester = remember(scopeKey) { FocusRequester() }
    val libraryFallbackFocusRequester = remember(scopeKey) { FocusRequester() }
    val libraryMenuFocusRequester = remember(scopeKey) { FocusRequester() }
    val showContinue = detail && searchQuery.isBlank() && continueCards.isNotEmpty()
    val showTrending = detail && searchQuery.isBlank()
    val visibleContentSections = if (searchQuery.isNotBlank()) visibleSections.filter { it == "search" } else visibleSections
    val groupAfterSearch = libSettings.groupUIStyle == "scorrevole" && visibleSections.firstOrNull() == "search" && !tvCategoryMode
    val firstResultItemIndex = remember(visibleContentSections, groupAfterSearch, showContinue, showTrending) {
        libraryFirstResultItemIndex(visibleContentSections, groupAfterSearch, showContinue, showTrending)
    }
    val focusFirstLibraryResult: () -> Unit = {
        scrollScope.launch {
            // Filter results may still be produced on Dispatchers.IO after the user changes a group.
            var attempts = 0
            while (filtered.isEmpty() && searchQuery.isBlank() && items.isNotEmpty() && attempts < 6) {
                delay(35L)
                attempts++
            }
            if (filtered.isNotEmpty()) {
                runCatching { listState.scrollToItem(firstResultItemIndex) }
                delay(70L)
                if (!firstMediaFocusRequester.requestFocusWithRetry(attempts = 5, delayMillis = 45L)) {
                    libraryFallbackFocusRequester.requestFocusWithRetry()
                }
            } else {
                libraryFallbackFocusRequester.requestFocusWithRetry()
            }
        }
    }
    val railOptions = rememberTvCategoryRailOptions(categories, items, favoriteIds)
    val selectedRailId = when {
        filter == TV_CATEGORY_FAVORITES -> TV_CATEGORY_FAVORITES
        filter == null -> TV_CATEGORY_ALL
        else -> filter!!
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (tvCategoryMode && categoryRailExpanded && !compactCategoryLayout) 12.dp else 0.dp)) {
        if (tvCategoryMode && !compactCategoryLayout) {
            TvCategoryRail(
                options = railOptions,
                selectedId = selectedRailId,
                expanded = categoryRailExpanded,
                entryRoute = scopeKey,
                onRequestExpand = { categoryRailExpanded = true },
                onRequestCollapse = { categoryRailExpanded = false },
                onSelect = { id ->
                    filter = when (id) { TV_CATEGORY_ALL -> null; TV_CATEGORY_FAVORITES -> TV_CATEGORY_FAVORITES; else -> id }
                    searchQuery = ""
                    scrollScope.launch { listState.scrollToItem(0) }
                },
                entryFocusRequester = if (tvCategoryMode) entryFocusRequester else null,
                onMoveRight = { focusFirstLibraryResult(); true },
            )
        }
        Column(
            Modifier.weight(1f).fillMaxHeight()
                // Telefono: toccando i contenuti la rail si chiude (come il focus che entra nei contenuti su TV).
                .collapseRailOnTouch(enabled = tvCategoryMode && categoryRailExpanded) { categoryRailExpanded = false }
                .onPreviewKeyEvent { event ->
                if (tvCategoryMode && !categoryRailExpanded && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft &&
                    // Every element opens the rail with LEFT, except covers that are not on the left edge
                    // (they navigate to the previous cover).
                    !leftEdgeCover.blocksRail
                ) {
                    categoryRailExpanded = true
                    true
                } else false
            },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        LibraryScreenTitle(
            title,
            onCustomize,
            onFocus = { if (tvCategoryMode) categoryRailExpanded = false },
            focusRequester = libraryFallbackFocusRequester,
            entryFocusRequester = if (!tvCategoryMode && filtered.isEmpty()) entryFocusRequester else null,
            downFocusRequester = libraryMenuFocusRequester,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tileColumns = ((maxWidth.value + 9f) / 159f).toInt().coerceAtLeast(1)
            val tileWidth = ((maxWidth.value - 9f * (tileColumns - 1)) / tileColumns).dp.coerceAtMost(220.dp)
            val categoryRowCount = if (categoryEntries.isEmpty()) 0 else (categoryEntries.size + tileColumns - 1) / tileColumns
            val gridMin = libraryGridMin(libSettings, !detail)
            val gridColumns = ((maxWidth.value + 14f) / (gridMin.value + 14f)).toInt().coerceAtLeast(1)
            val cellWidth = ((maxWidth.value - 14f * (gridColumns - 1)) / gridColumns).dp
            val preferredCardWidth = when {
                scopeKey == "live" -> 190.dp
                libSettings.density == "poster" -> 200.dp
                libSettings.density == "compact" || libSettings.density == "compatta" -> 128.dp
                else -> 160.dp
            }
            val cardWidth = cellWidth.coerceAtMost(preferredCardWidth)
            val mediaRowCount = if (filtered.isEmpty()) 0 else (filtered.size + gridColumns - 1) / gridColumns
            val showGroupChips = searchQuery.isBlank()
            val resultStartIndex = remember(
                visibleContentSections, groupAfterSearch, categoryEntries.size, categoryRowCount,
                showContinue, showTrending, searchQuery
            ) {
                var index = if (groupAfterSearch) 0 else 1
                visibleContentSections.forEach { section ->
                    when (section) {
                        "search" -> {
                            index++
                            if (groupAfterSearch) index++
                        }
                        "categories" -> if (categoryEntries.isNotEmpty()) index += 1 + categoryRowCount
                        "continueWatching" -> if (showContinue) index++
                        "trendingMovies" -> if (showTrending) index++
                    }
                }
                index
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                if (!groupAfterSearch) {
                    item(key = "library-groups-$scopeKey-before") {
                        LibraryHeader(title, categories, items, filter, { filter = it }, libSettings, vm, allowPoster = detail, showGroups = showGroupChips && !tvCategoryMode, isTelevision = isTelevision, onContentFocus = { if (tvCategoryMode) categoryRailExpanded = false }, menuFocusRequester = libraryMenuFocusRequester)
                    }
                }

                visibleContentSections.forEach { section ->
                    when (section) {
                        "search" -> {
                            item(key = "library-search-$scopeKey") {
                                EpgStyleSearchField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = if (scopeKey == "live") "Cerca per nome del canale" else "Cerca per nome del film",
                                    onFocus = { if (tvCategoryMode) categoryRailExpanded = false }
                                )
                            }
                            if (groupAfterSearch) {
                                item(key = "library-groups-$scopeKey-after-search") {
                                    LibraryHeader(title, categories, items, filter, { filter = it }, libSettings, vm, allowPoster = detail, showGroups = showGroupChips && !tvCategoryMode, isTelevision = isTelevision, onContentFocus = { if (tvCategoryMode) categoryRailExpanded = false }, menuFocusRequester = libraryMenuFocusRequester)
                                }
                            }
                        }
                        "categories" -> if (categoryEntries.isNotEmpty()) {
                            item(key = "library-category-title-$scopeKey") {
                                Text("Categorie", color = glassForeground().copy(.72f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            }
                            items(count = categoryRowCount, key = { rowIndex -> "library-category-row-$scopeKey-$rowIndex" }) { rowIndex ->
                                Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    val firstIndex = rowIndex * tileColumns
                                    for (index in firstIndex until minOf(categoryEntries.size, firstIndex + tileColumns)) {
                                        val entry = categoryEntries[index]
                                        LibraryCategoryTile(entry.name, filter == entry.filterId, tileWidth) {
                                            filter = if (filter == entry.filterId) null else entry.filterId
                                            scrollScope.launch { listState.animateScrollToItem(resultStartIndex.coerceAtLeast(0)) }
                                        }
                                    }
                                }
                            }
                        }
                        "continueWatching" -> if (showContinue) {
                            item(key = "library-continue-$scopeKey") {
                                ContinueWatchingSection(
                                    app = vm.app,
                                    cards = continueCards,
                                    kindFilter = "movie",
                                    // Come iOS: il player chiede "Riprendi la visione?" con la posizione salvata.
                                    onResume = { card -> onPlay(card.playItem) },
                                    itemModifier = { index, key -> Modifier.trackLeftEdgeCover(leftEdgeCover, key, index == 0) },
                                    onItemFocus = { if (tvCategoryMode) categoryRailExpanded = false },
                                )
                            }
                        }
                        "trendingMovies" -> if (showTrending) {
                            item(key = "library-trending-$scopeKey") {
                                HomeTrendingRail(
                                    title = "Film di tendenza",
                                    isSeries = false,
                                    apiKey = libSettings.tmdbApiKey,
                                    tmdb = vm.app.tmdb,
                                    onSelect = { trend ->
                                        // Ricerca accurata fuori dal main thread; esclusi i titoli bloccati, come nella griglia.
                                        trendLookup?.cancel()
                                        trendLookup = trendScope.launch {
                                            val trendMeta = trend.toMetadata()
                                            val match = findTrendInCatalogue(items.withoutIds(parental.lockedIds), trendMeta, MediaKind.MOVIE)
                                            if (match != null) { openedMeta = match.id to trendMeta; selected = match } else showTrendUnavailable = true
                                        }
                                    },
                                    itemModifier = { index, key -> Modifier.trackLeftEdgeCover(leftEdgeCover, key, index == 0) },
                                    onItemFocus = { if (tvCategoryMode) categoryRailExpanded = false },
                                )
                            }
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    item(key = "library-empty-$scopeKey") {
                        EmptyHint(when {
                            items.isEmpty() -> "Nessun contenuto: controlla la sorgente in Sorgenti e riprova a ricaricare."
                            searchQuery.isNotBlank() -> "Nessun risultato per ‘${searchQuery.trim()}’."
                            else -> "Nessun risultato per questo gruppo."
                        })
                    }
                } else {
                    items(count = mediaRowCount, key = { rowIndex -> "library-media-row-$scopeKey-$rowIndex" }) { rowIndex ->
                        Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            val firstIndex = rowIndex * gridColumns
                            for (index in firstIndex until minOf(filtered.size, firstIndex + gridColumns)) {
                                val item = filtered[index]
                                Box(Modifier.trackLeftEdgeCover(leftEdgeCover, "grid:${item.id}", index == firstIndex)) {
                                    MediaCard(
                                        item,
                                        vm.appFavorite(favorite, item),
                                        onClick = { if (detail) selected = item else onPlay(item) },
                                        onToggleFavorite = { vm.toggleFavorite(item) },
                                        widthOverride = cardWidth,
                                        onFocus = { if (tvCategoryMode) categoryRailExpanded = false },
                                        focusRequester = if (item.id == filtered.firstOrNull()?.id) firstMediaFocusRequester else null,
                                        additionalFocusRequester = if (!tvCategoryMode && item.id == filtered.firstOrNull()?.id) entryFocusRequester else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
    if (tvCategoryMode && compactCategoryLayout) {
        TvCategoryRail(
            options = railOptions,
            selectedId = selectedRailId,
            expanded = categoryRailExpanded,
            entryRoute = scopeKey,
            onRequestExpand = { categoryRailExpanded = true },
                onRequestCollapse = { categoryRailExpanded = false },
            expandedWidth = mobileRailWidth,
            modifier = Modifier.align(Alignment.CenterStart).zIndex(30f),
            entryFocusRequester = entryFocusRequester,
            onSelect = { id ->
                filter = when (id) { TV_CATEGORY_ALL -> null; TV_CATEGORY_FAVORITES -> TV_CATEGORY_FAVORITES; else -> id }
                searchQuery = ""
                categoryRailExpanded = false
                scrollScope.launch { listState.scrollToItem(0) }
            }
        )
    }
    }

    if (showTrendUnavailable) AlertDialog(
        onDismissRequest = { showTrendUnavailable = false },
        confirmButton = { TextButton(onClick = { showTrendUnavailable = false }) { Text("OK") } },
        title = { Text("Non disponibile nella tua playlist") },
        text = { Text("Questo titolo di tendenza non è presente nella sorgente attiva.") }
    )
}

@Composable
private fun SeriesScreen(
    series: List<MediaItem>,
    episodes: List<MediaItem>,
    categories: List<Category>,
    favorite: FavoriteState,
    parental: ParentalState,
    vm: MainViewModel,
    onPlay: (MediaItem) -> Unit,
    onCustomize: () -> Unit = {},
    isTelevision: Boolean = false,
    screenActive: Boolean = true,
    entryFocusRequester: FocusRequester? = null,
    openRequest: MediaItem? = null,
    openRequestMeta: MetadataResult? = null,
    onOpenRequestHandled: () -> Unit = {},
) {
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    // TMDB metadata already known for the opened title (trending): the detail shows it at once.
    var openedMeta by remember { mutableStateOf<Pair<String, MetadataResult>?>(null) }
    // A title opened from Home (trending) is shown here, inside its own section, as a detail sheet.
    LaunchedEffect(openRequest, screenActive) {
        val request = openRequest
        if (request != null && screenActive) {
            openedMeta = openRequestMeta?.let { request.id to it }
            selected = request
            onOpenRequestHandled()
        }
    }
    val trendScope = rememberCoroutineScope()
    var trendLookup by remember { mutableStateOf<Job?>(null) }
    var filter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showTrendUnavailable by remember { mutableStateOf(false) }
    // "Categorie TV": the collapsed rail opens with LEFT ONLY from a cover on the left edge (first card
    // of a row / first grid column); everywhere else LEFT keeps navigating.
    val leftEdgeCover = remember { LeftEdgeCoverFocus() }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val libSettings by vm.settings.collectAsStateWithLifecycle()
    val watch by vm.watch.collectAsStateWithLifecycle()
    val tvCategoryMode = libSettings.groupUIStyle == "categorie_tv"
    val compactCategoryLayout = tvCategoryMode && LocalConfiguration.current.screenWidthDp < 700
    val mobileRailWidth = (LocalConfiguration.current.screenWidthDp - 136).dp.coerceIn(160.dp, 236.dp)
    var categoryRailExpanded by remember { mutableStateOf(true) }
    val favoriteIds = favorite.series
    LaunchedEffect(screenActive) {
        if (!screenActive) {
            // Returning to Serie TV should always start from its library, never a stale detail.
            selected = null
            showTrendUnavailable = false
        }
    }
    if (selected != null && screenActive) {
        SeriesDetailScreen(
            vm.app, selected!!, episodes, favorite,
            onBack = {
                selected = null
                if (tvCategoryMode) categoryRailExpanded = true
                if (isTelevision) scrollScope.launch { delay(90L); entryFocusRequester?.requestFocusWithRetry(attempts = 6, delayMillis = 45L) }
            },
            onPlay = { i, pos -> onPlayWithPosition(onPlay, i, pos) },
            allSeries = series,
            onOpenAlternateSeries = { selected = it },
            entryFocusRequester = entryFocusRequester,
            initialMeta = openedMeta?.takeIf { it.first == selected?.id }?.second,
        )
        return
    }
    BackHandler(enabled = screenActive && tvCategoryMode && !categoryRailExpanded) { categoryRailExpanded = true }
    val defaults = defaultSeriesSections
    val sectionOrder = remember(libSettings.seriesSectionOrder) { (libSettings.seriesSectionOrder + defaults).distinct() }
    val visibleSections = sectionOrder.filterNot { it in libSettings.hiddenSeriesSections || ((isTelevision || tvCategoryMode) && it == "categories") }
    // Keep the group filter independent from the visibility of the configurable
    // category-tile section, matching iOS: the group selector remains usable
    // even when the standalone "Categorie" grid is hidden.
    LaunchedEffect(visibleSections.contains("search")) { if (!visibleSections.contains("search")) searchQuery = "" }

    val categoryEntries by produceState(
        initialValue = emptyList<LibraryCategoryEntry>(), categories, series, visibleSections.contains("categories"),
    ) {
        value = if (visibleSections.contains("categories")) {
            withContext(Dispatchers.IO) { buildLibraryCategoryEntries(categories, series) }
        } else emptyList()
    }
    val filtered by produceState<List<MediaItem>>(
        initialValue = if (filter == null && searchQuery.isBlank() && parental.lockedIds.isEmpty()) series else emptyList(),
        series, filter, parental.lockedIds, searchQuery, favoriteIds,
    ) {
        value = withContext(Dispatchers.IO) {
            val base = when {
                searchQuery.isNotBlank() -> series
                filter == TV_CATEGORY_FAVORITES -> series.onlyIds(favoriteIds)
                else -> series.inCategory(filter)
            }
            base.withoutIds(parental.lockedIds).matchingText(searchQuery).also { it.size }
        }
    }
    // "Continua a guardare" di Serie TV: una card per serie (kindFilter "series"), identico a iOS.
    val continueCards = rememberContinueWatchingCards(
        watch, vm.catalog.collectAsStateWithLifecycle().value, "series",
        enabled = "continueWatching" in visibleSections
    )
    val showContinue = searchQuery.isBlank() && continueCards.isNotEmpty()
    val showTrending = searchQuery.isBlank()
    val visibleContentSections = if (searchQuery.isNotBlank()) visibleSections.filter { it == "search" } else visibleSections
    val groupAfterSearch = libSettings.groupUIStyle == "scorrevole" && visibleSections.firstOrNull() == "search" && !tvCategoryMode
    val firstMediaFocusRequester = remember { FocusRequester() }
    val libraryFallbackFocusRequester = remember { FocusRequester() }
    val libraryMenuFocusRequester = remember { FocusRequester() }
    val firstResultItemIndex = remember(visibleContentSections, groupAfterSearch, showContinue, showTrending) {
        libraryFirstResultItemIndex(visibleContentSections, groupAfterSearch, showContinue, showTrending)
    }
    val focusFirstLibraryResult: () -> Unit = {
        scrollScope.launch {
            var attempts = 0
            while (filtered.isEmpty() && searchQuery.isBlank() && series.isNotEmpty() && attempts < 6) {
                delay(35L)
                attempts++
            }
            if (filtered.isNotEmpty()) {
                runCatching { listState.scrollToItem(firstResultItemIndex) }
                delay(70L)
                if (!firstMediaFocusRequester.requestFocusWithRetry(attempts = 5, delayMillis = 45L)) {
                    libraryFallbackFocusRequester.requestFocusWithRetry()
                }
            } else {
                libraryFallbackFocusRequester.requestFocusWithRetry()
            }
        }
    }
    val railOptions = rememberTvCategoryRailOptions(categories, series, favoriteIds)
    val selectedRailId = when {
        filter == TV_CATEGORY_FAVORITES -> TV_CATEGORY_FAVORITES
        filter == null -> TV_CATEGORY_ALL
        else -> filter!!
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (tvCategoryMode && categoryRailExpanded && !compactCategoryLayout) 12.dp else 0.dp)) {
        if (tvCategoryMode && !compactCategoryLayout) {
            TvCategoryRail(
                options = railOptions,
                selectedId = selectedRailId,
                expanded = categoryRailExpanded,
                entryRoute = "series",
                onRequestExpand = { categoryRailExpanded = true },
                onRequestCollapse = { categoryRailExpanded = false },
                onSelect = { id ->
                    filter = when (id) { TV_CATEGORY_ALL -> null; else -> id }
                    searchQuery = ""
                    scrollScope.launch { listState.scrollToItem(0) }
                },
                entryFocusRequester = if (tvCategoryMode) entryFocusRequester else null,
                onMoveRight = { focusFirstLibraryResult(); true },
            )
        }
        Column(
            Modifier.weight(1f).fillMaxHeight()
                // Telefono: toccando i contenuti la rail si chiude (come il focus che entra nei contenuti su TV).
                .collapseRailOnTouch(enabled = tvCategoryMode && categoryRailExpanded) { categoryRailExpanded = false }
                .onPreviewKeyEvent { event ->
                if (tvCategoryMode && !categoryRailExpanded && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft &&
                    // Every element opens the rail with LEFT, except covers that are not on the left edge
                    // (they navigate to the previous cover).
                    !leftEdgeCover.blocksRail
                ) {
                    categoryRailExpanded = true
                    true
                } else false
            },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        LibraryScreenTitle(
            "Serie TV",
            onCustomize,
            onFocus = { if (tvCategoryMode) categoryRailExpanded = false },
            focusRequester = libraryFallbackFocusRequester,
            entryFocusRequester = if (!tvCategoryMode && filtered.isEmpty()) entryFocusRequester else null,
            downFocusRequester = libraryMenuFocusRequester,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tileColumns = ((maxWidth.value + 9f) / 159f).toInt().coerceAtLeast(1)
            val tileWidth = ((maxWidth.value - 9f * (tileColumns - 1)) / tileColumns).dp.coerceAtMost(220.dp)
            val categoryRowCount = if (categoryEntries.isEmpty()) 0 else (categoryEntries.size + tileColumns - 1) / tileColumns
            val gridMin = libraryGridMin(libSettings, false)
            val gridColumns = ((maxWidth.value + 14f) / (gridMin.value + 14f)).toInt().coerceAtLeast(1)
            val cellWidth = ((maxWidth.value - 14f * (gridColumns - 1)) / gridColumns).dp
            val preferredCardWidth = when {
                libSettings.density == "poster" -> 200.dp
                libSettings.density == "compact" || libSettings.density == "compatta" -> 128.dp
                else -> 160.dp
            }
            val cardWidth = cellWidth.coerceAtMost(preferredCardWidth)
            val mediaRowCount = if (filtered.isEmpty()) 0 else (filtered.size + gridColumns - 1) / gridColumns
            val showGroupChips = searchQuery.isBlank()
            val resultStartIndex = remember(
                visibleContentSections, groupAfterSearch, categoryEntries.size, categoryRowCount,
                showContinue, showTrending, searchQuery
            ) {
                var index = if (groupAfterSearch) 0 else 1
                visibleContentSections.forEach { section ->
                    when (section) {
                        "search" -> { index++; if (groupAfterSearch) index++ }
                        "categories" -> if (categoryEntries.isNotEmpty()) index += 1 + categoryRowCount
                        "continueWatching" -> if (showContinue) index++
                        "trendingSeries" -> if (showTrending) index++
                    }
                }
                index
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                if (!groupAfterSearch) {
                    item(key = "library-groups-series-before") {
                        LibraryHeader("Serie TV", categories, series, filter, { filter = it }, libSettings, vm, allowPoster = true, showGroups = showGroupChips && !tvCategoryMode, isTelevision = isTelevision, onContentFocus = { if (tvCategoryMode) categoryRailExpanded = false }, menuFocusRequester = libraryMenuFocusRequester)
                    }
                }

                visibleContentSections.forEach { section ->
                    when (section) {
                        "search" -> {
                            item(key = "library-search-series") {
                                EpgStyleSearchField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), placeholder = "Cerca per nome della serie", onFocus = { if (tvCategoryMode) categoryRailExpanded = false })
                            }
                            if (groupAfterSearch) {
                                item(key = "library-groups-series-after-search") {
                                    LibraryHeader("Serie TV", categories, series, filter, { filter = it }, libSettings, vm, allowPoster = true, showGroups = showGroupChips && !tvCategoryMode, isTelevision = isTelevision, onContentFocus = { if (tvCategoryMode) categoryRailExpanded = false }, menuFocusRequester = libraryMenuFocusRequester)
                                }
                            }
                        }
                        "categories" -> if (categoryEntries.isNotEmpty()) {
                            item(key = "library-category-title-series") {
                                Text("Categorie", color = glassForeground().copy(.72f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            }
                            items(count = categoryRowCount, key = { rowIndex -> "library-category-row-series-$rowIndex" }) { rowIndex ->
                                Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    val firstIndex = rowIndex * tileColumns
                                    for (index in firstIndex until minOf(categoryEntries.size, firstIndex + tileColumns)) {
                                        val entry = categoryEntries[index]
                                        LibraryCategoryTile(entry.name, filter == entry.filterId, tileWidth) {
                                            filter = if (filter == entry.filterId) null else entry.filterId
                                            scrollScope.launch { listState.animateScrollToItem(resultStartIndex.coerceAtLeast(0)) }
                                        }
                                    }
                                }
                            }
                        }
                        "continueWatching" -> if (showContinue) {
                            item(key = "library-continue-series") {
                                ContinueWatchingSection(
                                    app = vm.app,
                                    cards = continueCards,
                                    kindFilter = "series",
                                    // Come iOS: il player chiede "Riprendi la visione?" con la posizione salvata.
                                    onResume = { card -> onPlay(card.playItem) },
                                    itemModifier = { index, key -> Modifier.trackLeftEdgeCover(leftEdgeCover, key, index == 0) },
                                    onItemFocus = { if (tvCategoryMode) categoryRailExpanded = false },
                                )
                            }
                        }
                        "trendingSeries" -> if (showTrending) {
                            item(key = "library-trending-series") {
                                HomeTrendingRail(
                                    title = "Serie di tendenza",
                                    isSeries = true,
                                    apiKey = libSettings.tmdbApiKey,
                                    tmdb = vm.app.tmdb,
                                    onSelect = { trend ->
                                        // Ricerca accurata fuori dal main thread; esclusi i titoli bloccati, come nella griglia.
                                        trendLookup?.cancel()
                                        trendLookup = trendScope.launch {
                                            val trendMeta = trend.toMetadata()
                                            val match = findTrendInCatalogue(series.withoutIds(parental.lockedIds), trendMeta, MediaKind.SERIES)
                                            if (match != null) { openedMeta = match.id to trendMeta; selected = match } else showTrendUnavailable = true
                                        }
                                    },
                                    itemModifier = { index, key -> Modifier.trackLeftEdgeCover(leftEdgeCover, key, index == 0) },
                                    onItemFocus = { if (tvCategoryMode) categoryRailExpanded = false },
                                )
                            }
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    item(key = "library-empty-series") {
                        EmptyHint(when {
                            series.isEmpty() -> "Nessuna serie: controlla la sorgente e ricarica."
                            searchQuery.isNotBlank() -> "Nessun risultato per ‘${searchQuery.trim()}’."
                            else -> "Nessun risultato per questo gruppo."
                        })
                    }
                } else {
                    items(count = mediaRowCount, key = { rowIndex -> "library-media-row-series-$rowIndex" }) { rowIndex ->
                        Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            val firstIndex = rowIndex * gridColumns
                            for (index in firstIndex until minOf(filtered.size, firstIndex + gridColumns)) {
                                val item = filtered[index]
                                Box(Modifier.trackLeftEdgeCover(leftEdgeCover, "grid:${item.id}", index == firstIndex)) {
                                    MediaCard(
                                        item,
                                        item.id in favorite.series,
                                        onClick = { selected = item },
                                        onToggleFavorite = { vm.toggleFavorite(item) },
                                        widthOverride = cardWidth,
                                        onFocus = { if (tvCategoryMode) categoryRailExpanded = false },
                                        focusRequester = if (item.id == filtered.firstOrNull()?.id) firstMediaFocusRequester else null,
                                        additionalFocusRequester = if (!tvCategoryMode && item.id == filtered.firstOrNull()?.id) entryFocusRequester else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
    if (tvCategoryMode && compactCategoryLayout) {
        TvCategoryRail(
            options = railOptions,
            selectedId = selectedRailId,
            expanded = categoryRailExpanded,
            entryRoute = "series",
            onRequestExpand = { categoryRailExpanded = true },
                onRequestCollapse = { categoryRailExpanded = false },
            expandedWidth = mobileRailWidth,
            modifier = Modifier.align(Alignment.CenterStart).zIndex(30f),
            entryFocusRequester = entryFocusRequester,
            onSelect = { id ->
                filter = when (id) { TV_CATEGORY_ALL -> null; else -> id }
                searchQuery = ""
                categoryRailExpanded = false
                scrollScope.launch { listState.scrollToItem(0) }
            }
        )
    }
    }
    if (showTrendUnavailable) AlertDialog(onDismissRequest = { showTrendUnavailable = false }, confirmButton = { TextButton(onClick = { showTrendUnavailable = false }) { Text("OK") } }, title = { Text("Non disponibile nella sorgente") }, text = { Text("Questa serie di tendenza non è presente nella playlist attiva.") })
}

@Composable
private fun SearchScreen(
    app: GassPlayerApplication,
    catalog: CatalogState?,
    onPlay: (MediaItem) -> Unit,
    onRoute: (String) -> Unit,
    entryFocusRequester: FocusRequester? = null,
) {
    var q by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val history by app.search.flow.collectAsStateWithLifecycle(SearchHistory())
    val scope = rememberCoroutineScope()
    val inputFocusRequester = remember { FocusRequester() }
    // Come iOS GlobalSearchService: la ricerca copre TUTTE le sorgenti abilitate (dalla cache su disco), non solo
    // quella attiva mostrata nelle sezioni. Ripiega sul catalogo attivo finche' quello completo non e' pronto.
    val allSourcesCatalog by produceState<CatalogState?>(initialValue = null, catalog?.updatedAt) {
        value = runCatching { app.catalog.allSourcesLiveState() }.getOrNull()
    }
    val query = submitted.trim()
    val results = remember(query, catalog, allSourcesCatalog) {
        if (query.length < 2) emptyList() else (allSourcesCatalog ?: catalog)?.search(query, 100).orEmpty()
    }
    Column(Modifier.focusGroup()) {
        EpgStyleSearchField(
            value = q,
            onValueChange = { q = it; submitted = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Cerca live, film e serie",
            onSubmit = { submitted = q; scope.launch { app.search.add(q) } },
            focusRequester = entryFocusRequester,
            inputFocusRequester = inputFocusRequester,
        )
        Spacer(Modifier.height(10.dp))
        if (query.length == 1) {
            Text("Inserisci almeno due caratteri per cercare canali, film e serie in tutte le tue sorgenti.", color = glassForeground().copy(.6f), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }
        if (submitted.isBlank() && history.terms.isNotEmpty()) {
            Text("Ricerche recenti", color = glassForeground().copy(.6f))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(history.terms) { term -> AssistChip(onClick = { q = term; submitted = term }, label = { Text(term) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp)) } }
            Spacer(Modifier.height(12.dp))
        }
        LazyVerticalGrid(GridCells.Adaptive(180.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(results) { MediaCard(it, false, onClick = { scope.launch { app.search.add(submitted) }; onPlay(it) }, onToggleFavorite = { scope.launch { app.favorites.toggle(it) } }) }
        }
    }
}

@Composable
private fun ExternalEpgManageScreen(app: GassPlayerApplication, onBack: () -> Unit, entryFocusRequester: FocusRequester? = null) {
    val sources by app.externalEpg.flow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .remoteFocusOutline(cornerRadius = 50.dp),
            ) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(4.dp)); Text("Indietro") }
            Text("Fonti XMLTV esterne", color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true), label = { Text("Nome fonte") })
        OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true), singleLine = true, label = { Text("URL XMLTV") })
        Button({ scope.launch { if (app.externalEpg.add(name, url)) { name = ""; url = "" } } }) { Text("Aggiungi fonte EPG") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.id }) { source ->
                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 0.dp) { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(source.name, color = glassForeground(), fontWeight = FontWeight.Bold); Text(source.urlString, color = glassForeground().copy(.6f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Switch(source.isEnabled, { enabled -> scope.launch { app.externalEpg.toggle(source.id, enabled) } }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp, includeDescendants = true))
                    IconButton({ scope.launch { app.externalEpg.remove(source.id) } }) { Icon(Icons.Default.Delete, null) }
                } }
            }
        }
    }
}

@Composable private fun LegacySourcesScreen(app: GassPlayerApplication, vm: MainViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ showAdd = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Aggiungi sorgente") }
            OutlinedButton({ scope.launch { app.prefs.saveSources(sources.sortedWith(compareBy<MediaSourceConfig> { if (it.isPinned) 0 else 1 }.thenBy { it.sortOrder })) }; vm.refresh(false) }) { Text("Ordina") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sources, key = { it.id }) { s ->
                var renameOpen by remember(s.id) { mutableStateOf(false) }
                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 0.dp) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if(s.isPinned) "★ ${s.name}" else s.name, color = glassForeground(), fontWeight = FontWeight.Bold)
                            Text("${s.type} • ${s.host}", color = glassForeground().copy(.6f))
                            Text(if (s.lastVerificationSucceeded) "Verificata • ${s.lastKnownChannelCount}" else "Non verificata", color = if (s.lastVerificationSucceeded) Color(0xFF6EDC82).readable() else Color(0xFFFFB55E).readable())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = s.isEnabled, onCheckedChange = { value -> scope.launch { app.sources.toggle(s.id, value); vm.refresh(false) } }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp, includeDescendants = true))
                            IconButton({ vm.setActive(s.id) }) { Icon(if (vm.activeSource.value == s.id) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null) }
                            IconButton({ renameOpen = true }) { Icon(Icons.Default.Edit, null) }
                            IconButton({ scope.launch { app.sources.pin(s.id, !s.isPinned); vm.refresh(false) } }) { Icon(Icons.Default.PushPin, null) }
                            IconButton({ scope.launch { app.sources.duplicate(s.id); vm.refresh(false) } }) { Icon(Icons.Default.ContentCopy, null) }
                            IconButton({ scope.launch { app.sources.verify(s); vm.refresh(false) } }) { Icon(Icons.Default.Verified, null) }
                            IconButton({ vm.deleteSource(s.id) }) { Icon(Icons.Default.Delete, null) }
                        }
                    }
                }
                if (renameOpen) RenameSourceDialog(s.name, onDismiss = { renameOpen = false }) { name -> scope.launch { app.sources.rename(s.id, name); vm.refresh(false); renameOpen = false } }
            }
        }
    }
    if (showAdd) AddSourceDialog(app, vm, { showAdd = false })
}

@Composable
private fun SourceDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        LiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 620.dp)
                .heightIn(max = 760.dp),
            cornerRadius = 28.dp,
            contentPadding = 22.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, darkSurface = false) {
                        Icon(Icons.Default.Close, "Chiudi")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun RenameSourceDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    SourceDialogFrame("Rinomina sorgente", onDismiss) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true),
            singleLine = true,
            label = { Text("Nome") }
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerGlassButton(
                onClick = onDismiss,
                darkSurface = false
            ) { Text("Annulla") }
            Spacer(Modifier.width(10.dp))
            PlayerGlassButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name) },
                darkSurface = false
            ) { Text("Salva") }
        }
    }
}

@Composable
fun AddSourceDialog(app: GassPlayerApplication, vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = if (type == SourceType.XTREAM) {
        host.isNotBlank() && ((user.isNotBlank() && pass.isNotBlank()) || XtreamRepository.credentialsFromUrl(host) != null)
    } else {
        url.isNotBlank()
    }

    SourceDialogFrame("Nuova sorgente", { if (!busy) onDismiss() }) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true),
            singleLine = true,
            label = { Text("Nome") }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SourceType.entries.take(2).forEach { sourceType ->
                FilterChip(
                    selected = sourceType == type,
                    onClick = { if (!busy) type = sourceType },
                    label = { Text(sourceType.name) },
                    modifier = Modifier.weight(1f).remoteFocusOutline(cornerRadius = 50.dp, includeDescendants = true)
                )
            }
        }
        if (type == SourceType.XTREAM) {
            OutlinedTextField(host, { host = it }, modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true), singleLine = true, label = { Text("Host Xtream") })
            OutlinedTextField(user, { user = it }, modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true), singleLine = true, label = { Text("Username") })
            OutlinedTextField(pass, { pass = it }, modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true), singleLine = true, label = { Text("Password") })
        } else {
            OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true), singleLine = true, label = { Text("URL M3U/M3U8") })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerGlassButton(
                enabled = !busy,
                onClick = onDismiss,
                darkSurface = false
            ) { Text("Annulla") }
            Spacer(Modifier.width(10.dp))
            PlayerGlassButton(
                enabled = valid && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        val pasted = if (type == SourceType.XTREAM) XtreamRepository.credentialsFromUrl(host) else null
                        val actualHost = if (type == SourceType.XTREAM) {
                            runCatching { XtreamRepository.serverBase(host) }.getOrElse {
                                error = it.message
                                busy = false
                                return@launch
                            }
                        } else url.trim()
                        user = user.ifBlank { pasted?.first.orEmpty() }
                        pass = pass.ifBlank { pasted?.second.orEmpty() }
                        val candidate = MediaSourceConfig(
                            id = UUID.randomUUID().toString(),
                            name = name.trim().ifBlank { "Sorgente" },
                            type = type,
                            host = actualHost,
                            username = user.trim().takeIf { it.isNotBlank() },
                            password = pass.takeIf { it.isNotBlank() },
                            playlistUrl = url.trim().takeIf { type != SourceType.XTREAM }
                        )
                        val result = runCatching {
                            when (type) {
                                SourceType.XTREAM -> app.xtream.probe(candidate)
                                SourceType.M3U8 -> {
                                    val playlistUrl = candidate.playlistUrl ?: candidate.host
                                    val sourceHeaders = NetworkApi.extractInlineHeaders(playlistUrl)
                                    // Streamed validation: counts entries without keeping the playlist in memory.
                                    val count = app.network.readTextStream(
                                        playlistUrl,
                                        mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, */*")
                                    ) { reader, finalUrl ->
                                        var n = 0
                                        M3UParser.parse(candidate.id, reader, NetworkApi.stripInlineHeaders(finalUrl), defaultHeaders = sourceHeaders, keep = { n++; false })
                                        n
                                    }
                                    count.takeIf { it > 0 }
                                        ?: error("Playlist M3U/M3U8 vuota o non riconosciuta")
                                }
                                else -> Unit
                            }
                        }
                        if (result.isSuccess) {
                            vm.addSource(candidate)
                            onDismiss()
                        } else {
                            error = result.exceptionOrNull()?.message ?: "Verifica sorgente fallita"
                        }
                        busy = false
                    }
                },
                darkSurface = false
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "Verifica…" else "Verifica e salva")
            }
        }
    }
}

@Composable private fun DownloadsScreen(app: GassPlayerApplication, entryFocusRequester: FocusRequester? = null) {
    val infos by androidx.work.WorkManager.getInstance(app).getWorkInfosByTagFlow("gass_download").collectAsStateWithLifecycle(emptyList())
    Column {
        Text(
            "Download in background",
            color = glassForeground(),
            modifier = Modifier.then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .remoteFocusOutline(cornerRadius = 8.dp).focusable(),
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(infos) { info ->
                val p = info.progress.getInt("progress", if (info.state == androidx.work.WorkInfo.State.SUCCEEDED) 100 else 0)
                LiquidGlassSurface(
                    Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 18.dp).focusable(),
                    cornerRadius = 18.dp,
                    contentPadding = 14.dp,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(info.outputData.getString("file") ?: info.id.toString(), color = glassForeground())
                        LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF8CB7FF).readable(), trackColor = glassForeground(.08f))
                        Text(info.state.name, color = glassForeground().copy(.6f))
                    }
                }
            }
        }
    }
}

@Composable internal fun SettingInt(label:String, value:Int, range:IntRange, step:Int=1, onChange:(Int)->Unit){ Column(Modifier.fillMaxWidth()){ Text("$label: $value",color = glassForeground()); Slider(value=value.toFloat(),onValueChange={onChange(it.toInt())},valueRange=range.first.toFloat()..range.last.toFloat(),steps=((range.last-range.first)/step-1).coerceAtLeast(0), modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp, includeDescendants = true)) } }
@Composable private fun SettingToggle(label:String, value:Boolean, onChange:(Boolean)->Unit){ Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){ Text(label,color = glassForeground()); Switch(value,onChange, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp, includeDescendants = true)) } }
@Composable private fun SettingRow(label:String, value:String, choices:List<String>, onChange:(String)->Unit){ var expanded by remember{mutableStateOf(false)}; Box{ OutlinedButton({expanded=true},Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 50.dp)){ Text("$label: $value") }; DropdownMenu(expanded,{expanded=false}){choices.forEach{DropdownMenuItem({Text(it)},onClick={onChange(it);expanded=false}, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true))}} } }

@Composable private fun ParentalScreen(vm: MainViewModel, state: ParentalState, entryFocusRequester: FocusRequester? = null) {
    var pin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PIN locale con SHA-256 e blocco per contenuto/categoria", color = glassForeground().copy(.7f))
        OutlinedTextField(
            newPin, { newPin = it },
            modifier = Modifier.fillMaxWidth().then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true),
            label = { Text("Nuovo PIN") },
        )
        Button(onClick = { scope.launch { vm.app.parental.setPin(newPin); msg = "PIN impostato" } }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp)) { Text("Imposta PIN") }
        OutlinedTextField(pin, { pin = it }, label = { Text("Verifica PIN") }, modifier = Modifier.remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true))
        Button(onClick = { scope.launch { msg = if (vm.app.parental.verify(pin)) "PIN corretto" else "PIN non valido" } }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp)) { Text("Verifica") }
        msg?.let { Text(it, color = glassForeground()) }
    }
}

@Composable private fun VpnScreen(app: GassPlayerApplication, entryFocusRequester: FocusRequester? = null) {
    val cfg by app.prefs.vpnFlow.collectAsStateWithLifecycle(VPNConfig())
    var server by remember(cfg){mutableStateOf(cfg.serverEndpoint)}; var user by remember(cfg){mutableStateOf(cfg.username)}; var pass by remember(cfg){mutableStateOf(cfg.password)}; var state by remember{mutableStateOf("Disconnessa")}; var discovery by remember{mutableStateOf<VPNConfig?>(null)}; val scope=rememberCoroutineScope()
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){ result -> if(result.resultCode==Activity.RESULT_OK) runCatching{app.vpn.startIkev2();state="Connessa"} }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("IKEv2 usa lo stack VPN nativo Android; WireGuard usa il tunnel ufficiale del progetto WireGuard.",color = glassForeground().copy(.7f))}
        item { OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth().then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true), label = { Text("Endpoint") }) }
        item{OutlinedTextField(user,{user=it},Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true),label={Text("Username")})}
        item{OutlinedTextField(pass,{pass=it},Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true),label={Text("Password")})}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button({scope.launch{val n=cfg.copy(protocol="ikev2",serverEndpoint=server,username=user,password=pass); app.prefs.saveVpn(n); val intent=runCatching{app.vpn.provisionIkev2(n)}.getOrNull(); if(intent!=null) launcher.launch(intent) else runCatching{app.vpn.startIkev2();state="Connessa"}}}){Text("Configura IKEv2")};
            OutlinedButton({app.vpn.stopIkev2();state="Disconnessa"}){Text("Stop")}
        }}
        item{Button({scope.launch{discovery=app.vpn.providerDiscovery(server,user,pass)}}){Text("Scopri VPN dal provider")}}
        discovery?.let{ d -> item{LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 14.dp){Column(verticalArrangement=Arrangement.spacedBy(5.dp)){Text("Configurazione trovata: ${d.protocol}", color = glassForeground());Text(d.serverEndpoint, color = glassForeground().copy(.78f));Text("DNS: ${d.dns.joinToString()}", color = glassForeground().copy(.65f))}}} }
        item{Text("Stato: $state",color = glassForeground())}
        item{Text("OpenVPN resta rappresentato a livello di configurazione, come nella sorgente iOS: non viene pubblicizzato come motore cifrato integrato.",color = glassForeground().copy(.55f),fontSize=12.sp)}
    }
}

@Composable private fun BackupScreen(app: GassPlayerApplication, entryFocusRequester: FocusRequester? = null) {
    val context=LocalContext.current; val scope=rememberCoroutineScope(); var status by remember{mutableStateOf("")}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri-> if(uri!=null) scope.launch{val text=app.backup.fullExport(); context.contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray())};status="Backup esportato"}}
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri-> if(uri!=null) scope.launch{val text=context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty();runCatching{app.backup.importAny(text)};status="Import completato o parziale"}}
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { create.launch("gassplayer-backup.json") },
            modifier = Modifier.then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).remoteFocusOutline(cornerRadius = 50.dp),
        ) { Text("Esporta sorgenti + preferenze") }
        OutlinedButton(onClick = { pick.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp)) { Text("Importa JSON") }
        Button(onClick = { scope.launch { app.cloud.requestBackup(); status = "Richiesta Auto Backup inviata" } }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp)) { Text("Sincronizza con backup Android") }
        Text(status, color = glassForeground())
    }
}

@Composable
private fun TraktScreen(app: GassPlayerApplication, settings: AppSettings, vm: MainViewModel, entryFocusRequester: FocusRequester? = null) {
    val account by app.prefs.traktFlow.collectAsStateWithLifecycle(TraktAccount())
    var local by remember(settings) { mutableStateOf(settings) }
    var device by remember { mutableStateOf<TraktDeviceCode?>(null) }
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(device?.deviceCode) {
        val code = device ?: return@LaunchedEffect
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < code.expiresInSec * 1000L) {
            kotlinx.coroutines.delay(5000)
            val token = runCatching { app.trakt.pollDevice(local.traktClientId, local.traktClientSecret, code.deviceCode) }.getOrNull()
            if (token != null) { app.prefs.saveTrakt(token); device = null; message = "Trakt collegato"; break }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Text("Collega Trakt.tv", color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(local.traktClientId, { local = local.copy(traktClientId = it); vm.updateSettings(local) }, modifier = Modifier.fillMaxWidth().then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true), singleLine = true, label = { Text("Client ID") }) }
        item { OutlinedTextField(local.traktClientSecret, { local = local.copy(traktClientSecret = it); vm.updateSettings(local) }, modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true), singleLine = true, label = { Text("Client Secret") }) }
        if (account.accessToken.isNotBlank()) {
            item { LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 14.dp) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Connesso a Trakt.tv", color = glassForeground(), fontWeight = FontWeight.Bold); Text("Lo scrobbling di film/episodi usa il token salvato su questo dispositivo.", color = glassForeground().copy(.65f)); OutlinedButton({ scope.launch { app.prefs.saveTrakt(TraktAccount()) } }) { Text("Disconnetti") } } } }
        } else if (device == null) {
            item { Button(enabled = local.traktClientId.isNotBlank() && local.traktClientSecret.isNotBlank(), onClick = { scope.launch { device = app.trakt.deviceCode(local.traktClientId.trim(), local.traktClientSecret.trim()); message = if (device != null) "Autorizza il dispositivo e attendi la conferma." else "Impossibile iniziare il flusso Trakt." } }) { Text("Connetti a Trakt.tv") } }
        } else {
            val code = device!!
            item { LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 0.dp) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Codice dispositivo", color = glassForeground().copy(.7f)); Text(code.userCode, color = glassForeground(), fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(code.verificationUrl, color = glassForeground().copy(.7f)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (code.verificationUrl.startsWith("http")) code.verificationUrl else "https://${code.verificationUrl}"))) }) { Text("Apri Trakt") }; OutlinedButton({ device = null }) { Text("Annulla") } }; Text("In attesa dell'autorizzazione…", color = glassForeground().copy(.65f)) } } }
        }
        if (message.isNotBlank()) item { Text(message, color = glassForeground()) }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
private fun HomeCustomizationSheet(
    vm: MainViewModel,
    scopeKey: String = "home",
    onBack: () -> Unit = {},
    isTv: Boolean = false
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var local by remember(settings, scopeKey) { mutableStateOf(settings) }
    var continueMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var tvMoveMenuFor by remember { mutableStateOf<String?>(null) }
    var dragId by remember(scopeKey) { mutableStateOf<String?>(null) }
    var dragStartIndex by remember(scopeKey) { mutableStateOf(0) }
    var dragTranslationPx by remember(scopeKey) { mutableStateOf(0f) }
    var dragOriginalOrder by remember(scopeKey) { mutableStateOf<List<String>>(emptyList()) }
    var dragOrder by remember(scopeKey) { mutableStateOf<List<String>?>(null) }
    val customizationEntryFocusRequester = remember(scopeKey) { FocusRequester() }

    LaunchedEffect(scopeKey, isTv) {
        if (isTv) {
            delay(120L)
            if (!customizationEntryFocusRequester.requestFocusWithRetry(attempts = 6, delayMillis = 45L)) {
                // Empty/fully-hidden layouts still offer an Add action as a remote entry point.
                customizationEntryFocusRequester.requestFocusWithRetry(attempts = 3, delayMillis = 45L)
            }
        }
    }

    val defaults = when (scopeKey) {
        "live" -> defaultLiveSections
        "movies" -> defaultMovieSections
        "series" -> defaultSeriesSections
        else -> defaultHomeSections
    }.let { sectionIds -> if (isTv && scopeKey in setOf("live", "movies", "series")) sectionIds.filterNot { it == "categories" } else sectionIds }
    fun readOrder(s: AppSettings): List<String> = when (scopeKey) {
        "live" -> s.liveSectionOrder
        "movies" -> s.movieSectionOrder
        "series" -> s.seriesSectionOrder
        // The iOS Home layout has no playlist "Categorie" tile grid. That
        // configurable section belongs only to Live TV / Film / Serie TV.
        else -> normalizedHomeSectionOrder(s.homeSectionOrder)
    }.let { sectionIds -> if (isTv && scopeKey in setOf("live", "movies", "series")) sectionIds.filterNot { it == "categories" } else sectionIds }
    fun readHidden(s: AppSettings): Set<String> = when (scopeKey) {
        "live" -> s.hiddenLiveSections
        "movies" -> s.hiddenMovieSections
        "series" -> s.hiddenSeriesSections
        else -> s.hiddenHomeSections.intersect(defaultHomeSections.toSet())
    }
    fun saveLayout(order: List<String>, hidden: Set<String>, continueKind: String? = local.homeContinueKind) {
        val normalizedOrder = (order + defaults).distinct()
        val normalizedHidden = hidden.intersect(normalizedOrder.toSet())
        val next = when (scopeKey) {
            "live" -> local.copy(liveSectionOrder = normalizedOrder, hiddenLiveSections = normalizedHidden)
            "movies" -> local.copy(movieSectionOrder = normalizedOrder, hiddenMovieSections = normalizedHidden)
            "series" -> local.copy(seriesSectionOrder = normalizedOrder, hiddenSeriesSections = normalizedHidden)
            else -> local.copy(homeSectionOrder = normalizedOrder, hiddenHomeSections = normalizedHidden, homeContinueKind = continueKind)
        }
        local = next
        vm.updateSettings(next)
    }

    val completeOrder = remember(readOrder(local), defaults) { (readOrder(local) + defaults).distinct() }
    val hiddenSet = readHidden(local).intersect(completeOrder.toSet())
    val visible = completeOrder.filterNot { it in hiddenSet }
    val hidden = completeOrder.filter { it in hiddenSet }
    val displayOrder = dragOrder ?: visible
    val title = when (scopeKey) {
        "live" -> "Sezioni Live TV"
        "movies" -> "Sezioni VOD"
        "series" -> "Sezioni Serie TV"
        else -> "Sezioni home"
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowPitchPx = with(density) { 64.dp.toPx() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val rowShape = RoundedCornerShape(50)

    ModalBottomSheet(
        onDismissRequest = onBack,
        sheetState = sheetState,
        sheetMaxWidth = Dp.Unspecified,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
        contentColor = glassForeground(),
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .54f),
        dragHandle = null
    ) {
        // Replica il foglio SwiftUI: titolo centrato, righe a capsula da 55 pt,
        // maniglia drag a sinistra, opzioni contestuali, cestino e + in fondo.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.96f),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)
        ) {
            item(key = "customization-title-$scopeKey") {
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 28.dp),
                    color = glassForeground(),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            items(displayOrder, key = { it }) { id ->
                val index = displayOrder.indexOf(id)
                val isDragging = dragId == id
                val residualOffset = if (isDragging) dragTranslationPx - (index - dragStartIndex) * rowPitchPx else 0f
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 12.dp)
                        .height(55.dp)
                        .animateItem()
                        .graphicsLayer {
                            translationY = residualOffset
                            scaleX = if (isDragging) 1.02f else 1f
                            scaleY = if (isDragging) 1.02f else 1f
                            shadowElevation = if (isDragging) 12.dp.toPx() else 0f
                        }
                        .zIndex(if (isDragging) 1f else 0f)
                        .clip(rowShape)
                        .background(glassForeground().copy(if (isDragging) .13f else .09f)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Trascina per spostare ${homeSectionTitles[id] ?: id}",
                        tint = glassForeground().copy(.58f),
                        modifier = Modifier
                            .width(44.dp)
                            .fillMaxHeight()
                            .pointerInput(scopeKey, id) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragId = id
                                        dragOriginalOrder = visible
                                        dragStartIndex = visible.indexOf(id).coerceAtLeast(0)
                                        dragTranslationPx = 0f
                                        dragOrder = visible
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val nextTranslation = dragTranslationPx + dragAmount.y
                                        dragTranslationPx = nextTranslation
                                        val target = (dragStartIndex + (nextTranslation / rowPitchPx).roundToInt())
                                            .coerceIn(0, (dragOriginalOrder.size - 1).coerceAtLeast(0))
                                        val originalIndex = dragOriginalOrder.indexOf(id)
                                        if (originalIndex >= 0) {
                                            val preview = dragOriginalOrder.toMutableList().apply {
                                                removeAt(originalIndex)
                                                add(target.coerceIn(0, size), id)
                                            }
                                            dragOrder = preview
                                        }
                                    },
                                    onDragEnd = {
                                        val reordered = dragOrder
                                        if (reordered != null && reordered != dragOriginalOrder) {
                                            saveLayout(reordered + hidden, hiddenSet)
                                        }
                                        dragId = null
                                        dragTranslationPx = 0f
                                        dragOriginalOrder = emptyList()
                                        dragOrder = null
                                    },
                                    onDragCancel = {
                                        dragId = null
                                        dragTranslationPx = 0f
                                        dragOriginalOrder = emptyList()
                                        dragOrder = null
                                    }
                                )
                            }
                    )
                    Column(
                        Modifier.weight(1f).padding(start = 5.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            homeSectionTitles[id] ?: id,
                            color = glassForeground(),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (id == "continueWatching" && scopeKey == "home" && local.homeContinueKind != null) {
                            Text(
                                when (local.homeContinueKind) {
                                    "live" -> "Solo Live TV"
                                    "movie" -> "Solo film"
                                    "series" -> "Solo serie TV"
                                    else -> ""
                                },
                                color = glassForeground().copy(.58f),
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (id == "continueWatching" && scopeKey == "home") {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Surface(
                                onClick = { continueMenu = true },
                                modifier = Modifier.size(40.dp).remoteFocusOutline(cornerRadius = 50.dp),
                                shape = RoundedCornerShape(50),
                                color = Color.Transparent,
                                contentColor = glassForeground()
                            ) {
                                LiquidGlassSurface(Modifier.fillMaxSize(), cornerRadius = 50.dp, contentPadding = 0.dp) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Tune, "Opzioni", tint = glassForeground().copy(.78f), modifier = Modifier.size(19.dp))
                                    }
                                }
                            }
                            DropdownMenu(continueMenu, { continueMenu = false }) {
                                listOf(null to "Tutti", "movie" to "Film", "series" to "Serie TV", "live" to "Live TV").forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            saveLayout(completeOrder, hiddenSet, value)
                                            continueMenu = false
                                        }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true)
                                    )
                                }
                            }
                        }
                    }
                    if (isTv) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Surface(
                                onClick = { tvMoveMenuFor = id },
                                modifier = Modifier.size(40.dp)
                                    .then(if (isTv && index == 0) Modifier.focusRequester(customizationEntryFocusRequester) else Modifier)
                                    .remoteFocusOutline(cornerRadius = 50.dp, enabled = isTv),
                                shape = RoundedCornerShape(50),
                                color = Color.Transparent,
                                contentColor = glassForeground()
                            ) {
                                LiquidGlassSurface(Modifier.fillMaxSize(), cornerRadius = 50.dp, contentPadding = 0.dp) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.MoreVert, "Riordina", tint = glassForeground().copy(.75f), modifier = Modifier.size(19.dp))
                                    }
                                }
                            }
                            DropdownMenu(expanded = tvMoveMenuFor == id, onDismissRequest = { tvMoveMenuFor = null }) {
                                DropdownMenuItem(
                                    text = { Text("Sposta su") },
                                    leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                                    enabled = index > 0,
                                    onClick = {
                                        val reordered = displayOrder.toMutableList()
                                        val moved = reordered.removeAt(index)
                                        reordered.add(index - 1, moved)
                                        saveLayout(reordered + hidden, hiddenSet)
                                        tvMoveMenuFor = null
                                    }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true)
                                )
                                DropdownMenuItem(
                                    text = { Text("Sposta giù") },
                                    leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                                    enabled = index < displayOrder.lastIndex,
                                    onClick = {
                                        val reordered = displayOrder.toMutableList()
                                        val moved = reordered.removeAt(index)
                                        reordered.add(index + 1, moved)
                                        saveLayout(reordered + hidden, hiddenSet)
                                        tvMoveMenuFor = null
                                    }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true)
                                )
                            }
                        }
                    }
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Surface(
                            onClick = {
                                val newHidden = hiddenSet + id
                                val newOrder = visible.filterNot { it == id } + hidden + id
                                saveLayout(newOrder, newHidden)
                            },
                            modifier = Modifier.size(40.dp).remoteFocusOutline(cornerRadius = 50.dp, enabled = isTv),
                            shape = RoundedCornerShape(50),
                            color = Color.Transparent,
                            contentColor = glassForeground()
                        ) {
                            LiquidGlassSurface(Modifier.fillMaxSize(), cornerRadius = 50.dp, contentPadding = 0.dp) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.DeleteOutline, "Nascondi ${homeSectionTitles[id] ?: id}", tint = glassForeground().copy(.78f), modifier = Modifier.size(19.dp))
                                }
                            }
                        }
                    }
                }
            }
            item(key = "customization-add-$scopeKey") {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Surface(
                            onClick = { addMenu = true },
                            enabled = hidden.isNotEmpty(),
                            modifier = Modifier.size(44.dp)
                                .then(if (isTv && displayOrder.isEmpty()) Modifier.focusRequester(customizationEntryFocusRequester) else Modifier)
                                .remoteFocusOutline(cornerRadius = 50.dp, enabled = isTv),
                            shape = RoundedCornerShape(50),
                            color = Color.Transparent,
                            contentColor = glassForeground()
                        ) {
                            LiquidGlassSurface(Modifier.fillMaxSize(), cornerRadius = 50.dp, contentPadding = 0.dp) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Add, "Aggiungi sezione", tint = glassForeground().copy(if (hidden.isEmpty()) .28f else .9f), modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            hidden.forEach { id ->
                                DropdownMenuItem(
                                    text = { Text(homeSectionTitles[id] ?: id) },
                                    leadingIcon = { Icon(Icons.Default.Add, null) },
                                    onClick = {
                                        val newVisible = visible + id
                                        val newOrder = newVisible + hidden.filterNot { it == id }
                                        saveLayout(newOrder, hiddenSet - id)
                                        addMenu = false
                                    }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MergedPlaylistScreen(app: GassPlayerApplication, entryFocusRequester: FocusRequester? = null) {
    val sources by app.sources.sources.collectAsStateWithLifecycle(emptyList())
    val playlists by app.mergedPlaylists.flow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Playlist unificate", color = glassForeground(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Raggruppa sorgenti in raccolte logiche, mantenendo l'origine di ogni contenuto.", color = glassForeground().copy(.65f))
        OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth().then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).remoteFocusOutline(cornerRadius = 14.dp, includeDescendants = true), label = { Text("Nome playlist") })
        LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sources, key = { it.id }) { source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected.contains(source.id), { selected = if (it) selected + source.id else selected - source.id }, modifier = Modifier.remoteFocusOutline(cornerRadius = 6.dp, includeDescendants = true))
                    Column { Text(source.name, color = glassForeground()); Text(source.host, color = glassForeground().copy(.5f), fontSize = 12.sp) }
                }
            }
        }
        Button({ scope.launch { app.mergedPlaylists.create(name, selected.toList()); name = ""; selected = emptySet() } }, enabled = name.isNotBlank() && selected.isNotEmpty()) { Text("Crea playlist unificata") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 0.dp) { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(playlist.name, color = glassForeground(), fontWeight = FontWeight.Bold); Text("${playlist.memberSourceIds.size} sorgenti", color = glassForeground().copy(.55f)) }
                    IconButton({ scope.launch { app.mergedPlaylists.remove(playlist.id) } }) { Icon(Icons.Default.Delete, null) }
                } }
            }
        }
    }
}

@Composable private fun DiagnosticsScreen(app: GassPlayerApplication, entryFocusRequester: FocusRequester? = null) {
    var log by remember { mutableStateOf(app.diagnostics.read()) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { log = app.diagnostics.read() }, modifier = Modifier.then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).remoteFocusOutline(cornerRadius = 50.dp)) { Text("Aggiorna") }
            OutlinedButton(onClick = { app.diagnostics.clear(); log = "" }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp)) { Text("Svuota") }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn { item { Text(log, color = glassForeground().copy(.75f), fontSize = 12.sp) } }
    }
}

private fun MainViewModel.appFavorite(state: FavoriteState,item:MediaItem)=when(item.kind){MediaKind.LIVE->item.id in state.live;MediaKind.MOVIE,MediaKind.EPISODE->item.id in state.movies;MediaKind.SERIES->item.id in state.series}

private fun Context.closestMainActivity(): MainActivity? {
    var current: Context = this
    while (current is android.content.ContextWrapper) {
        if (current is MainActivity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? MainActivity
}
