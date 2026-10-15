package com.gassplayer.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private enum class IosPlayerDialog {
    OPTIONS, SPEED, ASPECT, QUALITY, TRACKS, SLEEP, ADVANCED, CHANNEL_HISTORY
}

private data class PlayerSnapshot(
    val duration: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val state: Int = Player.STATE_IDLE,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 0f,
    val audioChannels: Int = 0
)

/** Fast-changing timeline state is isolated from the player metadata so progress ticks
 * recompose only the controls that render the timeline, not the whole video surface. */
private data class PlaybackProgress(
    val position: Long = 0L,
    /** Fin dove il contenuto e' gia' scaricato (bufferedPosition): parte chiara della timeline. */
    val buffered: Long = 0L
)

private data class PlayerTrackChoice(
    val group: Tracks.Group,
    val index: Int,
    val label: String,
    val type: Int
)

@Composable
fun IosPlayerScreen(
    app: GassPlayerApplication,
    item: MediaItem,
    settings: AppSettings,
    catalog: CatalogState?,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onNavigateToItem: (MediaItem) -> Unit,
    onOpenSearch: () -> Unit
) {
    val context = LocalContext.current
    val remoteFirstDevice = remember(context) { context.isTvOrFireTvDevice() }
    val scope = rememberCoroutineScope()
    val supportsPip = android.os.Build.VERSION.SDK_INT >= 26 &&
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    val sources by app.sources.sources.collectAsStateWithLifecycle(emptyList())
    val playbackError by app.playback.error.collectAsStateWithLifecycle()
    val recoveryStatus by app.playback.recoveryStatus.collectAsStateWithLifecycle()
    val playerEpoch by app.playback.playerEpoch.collectAsStateWithLifecycle()

    var showControls by remember(item.id) { mutableStateOf(true) }
    // Un solo avanzamento per episodio (tasto "Prossimo episodio" + fine naturale non devono scattare entrambi).
    var advancedToNext by remember(item.id) { mutableStateOf(false) }
    // "Prossimo episodio" esiste SOLO per le serie TV (mai per canali live, film o altro).
    val isSeriesEpisode = item.kind == MediaKind.EPISODE
    var locked by remember(item.id) { mutableStateOf(false) }
    var toast by remember(item.id) { mutableStateOf<String?>(null) }
    var dialog by remember(item.id) { mutableStateOf<IosPlayerDialog?>(null) }
    var currentSpeed by remember(item.id) { mutableFloatStateOf(settings.preferredPlaybackSpeed) }
    var aspect by remember(item.id) { mutableStateOf(settings.aspectRatio) }
    var resumePosition by remember(item.id) { mutableStateOf<Long?>(null) }
    var explicitResume by remember(item.id) { mutableStateOf(false) }
    var snapshot by remember(item.id) { mutableStateOf(PlayerSnapshot()) }
    val progressState = remember(item.id) { mutableStateOf(PlaybackProgress()) }
    var currentLiveProgram by remember(item.id) { mutableStateOf<EpgProgram?>(null) }
    var localSettings by remember(settings) { mutableStateOf(settings) }
    var renderPlayerSurface by remember(item.id) { mutableStateOf(true) }
    var showBrightnessHud by remember(item.id) { mutableStateOf(false) }
    var showVolumeHud by remember(item.id) { mutableStateOf(false) }
    val brightnessLevel = remember(item.id) { mutableFloatStateOf(0.5f) }
    val volumeLevel = remember(item.id) { mutableFloatStateOf(0.5f) }
    // Keep the host activity brightness intact after leaving the player.
    val originalWindowBrightness = remember { context.findActivity()?.window?.attributes?.screenBrightness ?: -1f }
    var hudTimerKey by remember(item.id) { mutableIntStateOf(0) }
    // Bumped on every remote key while the controls are visible, so the 4s auto-hide timer
    // restarts instead of hiding the controls under the user's focus mid-navigation.
    var controlsActivity by remember(item.id) { mutableIntStateOf(0) }
    // Durante lo scrubbing i controlli non si nascondono; al rilascio il timer riparte da capo.
    var controlsScrubbing by remember(item.id) { mutableStateOf(false) }
    // Anteprime della timeline: chiuse (flusso e cache) uscendo dal player.
    DisposableEffect(Unit) { onDispose { PlayerThumbnailStore.releaseAll() } }
    val playerFocusRequester = remember(item.id) { FocusRequester() }
    // Like GassTV, LEFT opens the live channel browser while immersive. Keep its state
    // independent from item.id so zapping channels does not collapse the rail on every tune.
    var showLiveCategoryRail by remember { mutableStateOf(false) }
    var playerLiveGroupId by remember { mutableStateOf(TV_CATEGORY_ALL) }
    val liveFavorites by app.favorites.flow.collectAsStateWithLifecycle(FavoriteState())
    val liveChannels = catalog?.live.orEmpty()
    val liveGroupOptions = rememberTvCategoryRailOptions(
        catalog?.liveCategories.orEmpty(), liveChannels, liveFavorites.live
    )
    val playerLiveChannels by produceState<List<MediaItem>>(
        initialValue = emptyList(), catalog?.updatedAt, playerLiveGroupId, liveFavorites.live
    ) {
        value = withContext(Dispatchers.IO) {
            when (playerLiveGroupId) {
                TV_CATEGORY_ALL -> liveChannels
                TV_CATEGORY_FAVORITES -> liveChannels.onlyIds(liveFavorites.live)
                TV_CATEGORY_NONE -> liveChannels.inCategory(CATEGORY_NONE)
                else -> liveChannels.inCategory(playerLiveGroupId)
            }
        }
    }

    // Play/Pausa dei controlli: riceve il focus appena i controlli compaiono, cosi' UN solo tasto del
    // telecomando sposta gia' il focus fra i pulsanti. Prima il focus restava sul contenitore del player
    // (che copre tutto lo schermo): la ricerca spaziale non trovava pulsanti "oltre" i suoi bordi e
    // servivano piu' pressioni per entrare nei controlli.
    val controlsFocusRequester = remember(item.id) { FocusRequester() }
    LaunchedEffect(item.id, showLiveCategoryRail, remoteFirstDevice, showControls, dialog, locked, resumePosition != null) {
        // Touch-first phones retain their native touch flow; deterministic DPAD focus applies to TV.
        // Also runs when the controls fade out or a dialog closes: the focused node is removed from
        // the tree then, and without this the remote would stop reaching the key handler.
        // L'avviso "Riprendi la visione?" gestisce da se' il focus (su "Riprendi da…").
        if (!remoteFirstDevice || showLiveCategoryRail || dialog != null || resumePosition != null) return@LaunchedEffect
        if (showControls && !locked) {
            // Primo frame dei controlli composto, poi focus su Play/Pausa (riprova finche' il nodo e' pronto).
            withFrameNanos { }
            if (controlsFocusRequester.requestFocusWithRetry(attempts = 8, delayMillis = 16L)) return@LaunchedEffect
        }
        playerFocusRequester.requestFocusWithRetry(attempts = 5, delayMillis = 30L)
    }

    // Il tasto Indietro di sistema/telecomando chiude SOLO il player (prima non era gestito e
    // poteva chiudere l'intera app): torna alla schermata da cui e' stato aperto.
    BackHandler(enabled = true) {
        when {
            showLiveCategoryRail && item.kind == MediaKind.LIVE -> {
                showLiveCategoryRail = false
                showControls = false
                if (remoteFirstDevice) runCatching { playerFocusRequester.requestFocus() }
            }
            dialog != null -> dialog = null
            locked -> { toast = "Sblocca per uscire"; showControls = true }
            else -> onBack()
        }
    }

    val source = remember(sources, item.sourceId) {
        sources.firstOrNull { it.id == item.sourceId }
    }
    // Episodi della serie caricati in background quando il catalogo non li ha ancora (ripresa da "Continua a
    // guardare" senza aver mai aperto la scheda della serie): come SeriesResumePlayer.loadSeriesInfo su iOS, il
    // player parte subito e, appena le info arrivano, abilita precedente/successivo e "Prossimo episodio".
    var seriesEpisodes by remember(item.sourceId, item.seriesId) { mutableStateOf<List<MediaItem>?>(null) }
    val nextItem = remember(catalog, item.id, seriesEpisodes) {
        catalog?.nextFor(item) ?: seriesEpisodes?.episodeNeighbor(item, forward = true)
    }
    val previousItem = remember(catalog, item.id, seriesEpisodes) {
        catalog?.previousFor(item) ?: seriesEpisodes?.episodeNeighbor(item, forward = false)
    }
    LaunchedEffect(item.id, item.seriesId, item.sourceId) {
        val seriesId = item.seriesId
        if (!isSeriesEpisode || seriesId.isNullOrBlank()) return@LaunchedEffect
        val known = seriesEpisodes?.any { it.id == item.id } == true
        if (known || catalog?.nextFor(item) != null || catalog?.previousFor(item) != null) return@LaunchedEffect
        val loaded = runCatching { app.catalog.loadSeriesEpisodes(item.sourceId, seriesId) }.getOrNull().orEmpty()
        if (loaded.any { it.id == item.id }) seriesEpisodes = loaded
    }

    // "Prossimo episodio" (come PlayerView.showNextEpisodeButton su iOS): visibilita' calcolata qui, cosi' il
    // telecomando sa quando il pulsante e' a schermo. Al primo tasto premuto mentre compare, il focus va
    // DIRETTAMENTE sul pulsante (un secondo OK lo attiva), invece di aprire i controlli del player.
    val nextPromptVisible by remember(item.id, nextItem) {
        derivedStateOf {
            isSeriesEpisode && nextItem != null && localSettings.autoplayNextEpisode && !advancedToNext &&
                nextEpisodePromptInWindow(snapshot.duration, progressState.value.position)
        }
    }
    val nextPromptFocusRequester = remember(item.id) { FocusRequester() }
    var nextPromptFocused by remember(item.id) { mutableStateOf(false) }
    // Il primo tasto dopo la comparsa e' "consumato" per portare il focus sul pulsante: una volta per comparsa
    // a controlli visibili (per non bloccare la navigazione nei controlli), sempre a controlli nascosti.
    var nextPromptFocusGranted by remember(item.id) { mutableStateOf(false) }
    var nextPromptHadFocus by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, nextPromptVisible) {
        if (!nextPromptVisible) {
            nextPromptFocusGranted = false
            // Il pulsante sparisce mentre ha il focus: il telecomando non deve restare senza un nodo focalizzato.
            if (nextPromptHadFocus && remoteFirstDevice) {
                nextPromptHadFocus = false
                delay(60L)
                playerFocusRequester.requestFocusWithRetry(attempts = 5, delayMillis = 45L)
            }
        }
    }

    LaunchedEffect(item.id) {
        val savedEntries = app.watch.flow.first()
        val saved = savedEntries.firstOrNull { it.contentId == item.id }
        // "Riprendi da" is only for VOD and series: live TV has no meaningful position.
        val resumeAllowed = item.kind != MediaKind.LIVE
        val explicit = if (!resumeAllowed) null else item.metadataTag
            ?.removePrefix("resume:")
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
        val savedResume = if (
            resumeAllowed &&
            settings.resumePlayback &&
            explicit == null &&
            saved != null &&
            saved.positionMs > 5_000L &&
            (saved.durationMs <= 0L || saved.positionMs < saved.durationMs - 45_000L)
        ) saved.positionMs else null

        explicitResume = explicit != null
        resumePosition = savedResume
        localSettings = settings
        app.playback.setSettings(settings)
        app.playback.play(
            item,
            startPosition = explicit ?: 0L,
            autoPlay = savedResume == null
        )
        if (savedResume != null) app.playback.pause()
        // "Continua a guardare" come iOS (RecentlyWatchedStore.record all'apertura): il titolo compare SUBITO,
        // anche se non si arriva alla fine. La posizione gia' salvata non viene toccata.
        app.appScope.launch { recordWatchEntry(app, item) }
    }

    // Posizione salvata ogni 15 s durante la riproduzione (KSPlaybackController.persistPosition iOS): se l'app
    // viene chiusa o il dispositivo si spegne, la scheda dettaglio e "Continua a guardare" sanno fin dove si e' visto.
    LaunchedEffect(item.id) {
        if (item.kind == MediaKind.LIVE) return@LaunchedEffect
        while (true) {
            delay(15_000L)
            val p = app.playback.player
            if (p.isPlaying) {
                val position = p.currentPosition.coerceAtLeast(0L)
                val duration = p.duration.coerceAtLeast(0L)
                app.appScope.launch { recordWatchEntry(app, item, position, duration) }
            }
        }
    }

    // Fine naturale: segnato come visto fino in fondo (barra piena, niente "Riprendi"), come markCompleted iOS.
    LaunchedEffect(item.id, snapshot.state) {
        if (item.kind != MediaKind.LIVE && snapshot.state == Player.STATE_ENDED) {
            val duration = app.playback.player.duration.coerceAtLeast(0L)
            app.appScope.launch { recordWatchEntry(app, item, duration, duration, ended = true) }
        }
    }

    LaunchedEffect(item.id, showControls, nextItem?.id, item.kind, localSettings.autoplayNextEpisode) {
        // Le tracce cambiano raramente: i formati video/audio si rileggono solo quando cambia
        // l'istanza Tracks, non a ogni tick (prima si scorrevano tutti i gruppi 4 volte al secondo).
        var lastTracks: Tracks? = null
        var video: Format? = null
        var audioChannels = 0
        while (true) {
            val p = app.playback.player
            val tracks = p.currentTracks
            if (tracks !== lastTracks) {
                lastTracks = tracks
                video = selectedVideoFormat(p)
                audioChannels = selectedAudioChannels(p)
            }
            val nextSnapshot = PlayerSnapshot(
                duration = p.duration.coerceAtLeast(0L),
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady,
                state = p.playbackState,
                videoWidth = video?.width ?: 0,
                videoHeight = video?.height ?: 0,
                frameRate = video?.frameRate ?: 0f,
                audioChannels = audioChannels
            )
            // Metadata changes infrequently; don't invalidate the whole player every tick.
            if (snapshot != nextSnapshot) snapshot = nextSnapshot

            // The timeline is only sampled at interactive rate while visible. When hidden,
            // keep a slower clock only if we need the next-episode affordance/autoplay timing.
            val needsHiddenProgress = isSeriesEpisode &&
                nextItem != null && localSettings.autoplayNextEpisode
            if (showControls || needsHiddenProgress) {
                val nextProgress = PlaybackProgress(
                    position = p.currentPosition.coerceAtLeast(0L),
                    buffered = p.bufferedPosition.coerceAtLeast(0L)
                )
                if (progressState.value != nextProgress) progressState.value = nextProgress
            }
            delay(
                when {
                    showControls -> 250L
                    // 500 ms: il pulsante compare entro mezzo secondo dal minuto finale.
                    needsHiddenProgress -> 500L
                    else -> 1_200L
                }
            )
        }
    }

    LaunchedEffect(hudTimerKey) {
        if (hudTimerKey == 0) return@LaunchedEffect
        delay(900L)
        showBrightnessHud = false
        showVolumeHud = false
    }

    LaunchedEffect(showControls, dialog, locked, snapshot.isBuffering, controlsActivity, controlsScrubbing) {
        if (!showControls || dialog != null || locked || controlsScrubbing) return@LaunchedEffect
        delay(4_000L)
        showControls = false
    }

    LaunchedEffect(item.id, source?.id, item.kind, settings.epgAutoUpdateEnabled) {
        currentLiveProgram = null
        if (item.kind != MediaKind.LIVE || source?.type != SourceType.XTREAM) return@LaunchedEffect

        val streamId = extractStreamId(item)
        suspend fun refreshLiveProgram() {
            val programs = runCatching {
                app.epg.shortEpg(source, streamId, limit = 8)
            }.getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            currentLiveProgram = programs.firstOrNull { it.isCurrent(now) }
                ?: programs.firstOrNull { it.startMs > now }
        }

        // A first read is always performed; the setting controls only the
        // continuous refresh loop.
        refreshLiveProgram()
        if (!settings.epgAutoUpdateEnabled) return@LaunchedEffect

        while (true) {
            delay(60_000L)
            refreshLiveProgram()
        }
    }

    LaunchedEffect(item.id, snapshot.state, localSettings.autoplayNextEpisode, nextItem?.id) {
        if (
            isSeriesEpisode &&
            localSettings.autoplayNextEpisode &&
            snapshot.state == Player.STATE_ENDED &&
            nextItem != null &&
            !advancedToNext
        ) {
            advancedToNext = true
            onNavigateToItem(nextItem)
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            val p = app.playback.player
            val position = p.currentPosition.coerceAtLeast(0L)
            val duration = p.duration.coerceAtLeast(0L)
            // Scope dell'APP, non della schermata: alla chiusura del player lo scope della composizione e' gia'
            // cancellato e il salvataggio andava perso (per questo "Continua a guardare" compariva solo a episodio
            // finito, quando il player restava aperto passando al successivo).
            app.appScope.launch { recordWatchEntry(app, item, position, duration) }
            // Brightness changes are window-scoped on Android; restore the value that
            // was active before playback so the gesture does not leak into other screens.
            context.findActivity()?.window?.let { window ->
                val params = window.attributes
                if (params.screenBrightness != originalWindowBrightness) {
                    params.screenBrightness = originalWindowBrightness
                    window.attributes = params
                }
            }
            app.playback.stop()
        }
    }

    val applySettings: (AppSettings) -> Unit = { updated ->
        val before = localSettings
        val rebuildRequired = before.minBufferSec != updated.minBufferSec ||
            before.maxBufferSec != updated.maxBufferSec ||
            before.playerStartBufferSec != updated.playerStartBufferSec ||
            before.httpCache != updated.httpCache ||
            before.asyncDecode != updated.asyncDecode ||
            before.hardwareDecode != updated.hardwareDecode ||
            before.softwareDecode != updated.softwareDecode ||
            before.videoDelayMs != updated.videoDelayMs ||
            before.customUserAgent != updated.customUserAgent
        scope.launch {
            val epochBefore = app.playback.playerEpoch.value
            if (rebuildRequired) {
                // Detach Media3 PlayerView first. Releasing a player while its SurfaceView
                // is still bound can crash on some Android GPU/decoder combinations.
                renderPlayerSurface = false
                withFrameNanos { }
                withFrameNanos { }
            }
            localSettings = updated
            aspect = updated.aspectRatio
            app.prefs.saveSettings(updated)
            app.playback.setSettings(updated)
            if (rebuildRequired) {
                // Wait for PlaybackController to create the replacement instance before
                // mounting a new PlayerView, with a timeout to avoid an infinite blank view.
                withTimeoutOrNull(10_000L) { app.playback.playerEpoch.first { it > epochBefore } }
                renderPlayerSurface = true
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || locked || dialog != null) return@onPreviewKeyEvent false
                // Avviso di ripresa aperto: frecce e OK vanno ai suoi pulsanti.
                if (resumePosition != null) return@onPreviewKeyEvent false
                if (showControls) controlsActivity++
                // When the channel browser is visible, let its focused lists consume the DPAD,
                // but keep hardware/media play-pause available on remotes that expose those keys.
                if (showLiveCategoryRail && item.kind == MediaKind.LIVE) {
                    when (event.key) {
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.Spacebar -> {
                            app.playback.togglePlayPause(); true
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                } else if (
                    remoteFirstDevice && nextPromptVisible && !nextPromptFocused &&
                    (!showControls || !nextPromptFocusGranted) &&
                    event.key in nextPromptFocusKeys
                ) {
                    // Primo tasto mentre "Prossimo episodio" e' visibile: focus sul pulsante, nient'altro.
                    nextPromptFocusGranted = true
                    runCatching { nextPromptFocusRequester.requestFocus() }
                    true
                } else if (nextPromptFocused && (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    // OK sul pulsante focalizzato: lo attiva (non deve aprire i controlli).
                    false
                } else when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.Spacebar -> {
                        app.playback.togglePlayPause()
                        showControls = true
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (!showControls) {
                            showControls = true
                            true
                        } else false
                    }
                    Key.DirectionLeft, Key.MediaRewind -> {
                        // Live TV: SINISTRA apre il rail dei canali SOLO a controlli nascosti; con i tasti del player
                        // visibili SINISTRA naviga fra i tasti.
                        if (event.key == Key.DirectionLeft && item.kind == MediaKind.LIVE && liveChannels.isNotEmpty() && !showControls) {
                            val currentGroup = item.categoryId ?: item.group?.takeIf { it.isNotBlank() }
                            playerLiveGroupId = liveGroupOptions.firstOrNull { it.id == currentGroup }?.id ?: TV_CATEGORY_ALL
                            showLiveCategoryRail = true
                            showControls = false
                            true
                        } else if (showControls && event.key == Key.DirectionLeft) false else {
                            showControls = true
                            if (item.kind != MediaKind.LIVE) app.playback.skipBack(10_000L)
                            true
                        }
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        if (showControls && event.key == Key.DirectionRight) false else {
                            showControls = true
                            if (item.kind != MediaKind.LIVE) app.playback.skipForward(10_000L)
                            true
                        }
                    }
                    Key.DirectionUp, Key.ChannelUp, Key.MediaNext -> {
                        if (showControls && event.key == Key.DirectionUp) false else if (item.kind == MediaKind.LIVE && nextItem != null) {
                            onNavigateToItem(nextItem)
                            true
                        } else {
                            showControls = true
                            true
                        }
                    }
                    Key.DirectionDown, Key.ChannelDown, Key.MediaPrevious -> {
                        if (showControls && event.key == Key.DirectionDown) false else if (item.kind == MediaKind.LIVE && previousItem != null) {
                            onNavigateToItem(previousItem)
                            true
                        } else {
                            showControls = true
                            true
                        }
                    }
                    Key.Menu, Key.Info -> {
                        showControls = true
                        true
                    }
                    else -> false
                }
            }
    ) {
        // PlayerView is detached before ExoPlayer recreation and keyed to each player instance.
        key(localSettings.panorama360, playerEpoch) {
        if (renderPlayerSurface) {
        AndroidView(
            factory = {
                val isTelevision = context.isTvOrFireTvDevice()
                val inflater = android.view.LayoutInflater.from(context)
                val playerView = when {
                    localSettings.panorama360 -> inflater.inflate(com.gassplayer.android.R.layout.gass_player_view_spherical, null, false) as PlayerView
                    isTelevision == false -> inflater.inflate(com.gassplayer.android.R.layout.gass_player_view_texture, null, false) as PlayerView
                    else -> PlayerView(context) // SurfaceView remains the lower-cost default on Android TV.
                }
                playerView.apply {
                    useController = false
                    player = app.playback.player
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    resizeMode = resizeModeForAspect(aspect)
                    // Superficie video per la prima anteprima istantanea della timeline (fotogramma attuale).
                    PlayerThumbnailStore.videoSurface = videoSurfaceView?.let { java.lang.ref.WeakReference(it) }
                }
            },
            update = {
                // playerEpoch changes whenever the ExoPlayer is recreated (decoder fallback, settings): re-bind.
                val activePlayer = app.playback.player
                if (playerEpoch >= 0 && it.player !== activePlayer) it.player = activePlayer
                if (!it.keepScreenOn) it.keepScreenOn = true
                val requestedResizeMode = resizeModeForAspect(aspect)
                if (it.resizeMode != requestedResizeMode) it.resizeMode = requestedResizeMode
                // "Rotazione automatica 360°": follow the device orientation sensor on spherical video.
                (it.videoSurfaceView as? androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView)
                    ?.setUseSensorRotation(localSettings.autoRotate360)
            },
            onRelease = { view ->
                view.player = null
                view.keepScreenOn = false
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.id, locked, snapshot.duration) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            if (locked || snapshot.duration <= 0L) return@detectTapGestures
                            val forward = offset.x >= size.width / 2f
                            if (forward) app.playback.skipForward(10_000L)
                            else app.playback.skipBack(10_000L)
                            toast = if (forward) "+10s" else "-10s"
                            showControls = true
                        },
                        onTap = {
                            if (!locked) showControls = !showControls
                        }
                    )
                }
                // Port of iOS PlayerView's vertical gesture: left half = brightness,
                // right half = media volume. Horizontal swipes remain available to controls.
                .pointerInput(item.id, locked) {
                    if (locked) return@pointerInput
                    var baseValue = 0f
                    var adjustBrightness = true
                    var totalX = 0f
                    var totalY = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            adjustBrightness = offset.x < size.width / 2f
                            totalX = 0f
                            totalY = 0f
                            if (adjustBrightness) {
                                val activity = context.findActivity()
                                val windowBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
                                baseValue = if (windowBrightness >= 0f) windowBrightness else runCatching {
                                    android.provider.Settings.System.getInt(
                                        context.contentResolver,
                                        android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                        128
                                    ) / 255f
                                }.getOrDefault(0.5f).coerceIn(0f, 1f)
                                brightnessLevel.floatValue = baseValue
                                showBrightnessHud = true
                                showVolumeHud = false
                            } else {
                                // Per-player volume keeps Fire TV/system-wide audio state untouched.
                                baseValue = app.playback.player.volume.coerceIn(0f, 1f)
                                volumeLevel.floatValue = baseValue
                                showVolumeHud = true
                                showBrightnessHud = false
                            }
                            hudTimerKey++
                        },
                        onDragEnd = { hudTimerKey++ },
                        onDragCancel = { hudTimerKey++ },
                        onDrag = { change, dragAmount ->
                            totalX += dragAmount.x
                            totalY += dragAmount.y
                            if (kotlin.math.abs(totalY) <= kotlin.math.abs(totalX)) return@detectDragGestures
                            change.consume()
                            val value = (baseValue - totalY / (size.height * 0.35f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            if (adjustBrightness) {
                                brightnessLevel.floatValue = value
                                showBrightnessHud = true
                                showVolumeHud = false
                                context.findActivity()?.window?.let { window ->
                                    val params = window.attributes
                                    if (params.screenBrightness < 0f || kotlin.math.abs(params.screenBrightness - value) >= 0.01f) {
                                        params.screenBrightness = value
                                        window.attributes = params
                                    }
                                }
                            } else {
                                volumeLevel.floatValue = value
                                showVolumeHud = true
                                showBrightnessHud = false
                                app.playback.player.volume = value
                            }
                        }
                    )
                }
        )
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(.75f))
            }
        }
        }

        AnimatedVisibility(
            visible = renderPlayerSurface && (snapshot.isBuffering || recoveryStatus != null) && playbackError == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = glassForeground())
                Text(
                    text = recoveryStatus ?: "Caricamento…",
                    color = glassForeground().copy(.85f),
                    fontSize = 13.sp
                )
            }
        }

        AnimatedVisibility(
            visible = playbackError != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color.Black.copy(.80f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White.copy(.14f))
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFFB4AB), modifier = Modifier.size(28.dp))
                    Text("Impossibile riprodurre il flusso", color = glassForeground(), fontWeight = FontWeight.SemiBold)
                    Text(
                        playbackError.orEmpty(),
                        color = glassForeground().copy(.72f),
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    PlayerGlassButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = "Riprova",
                        onClick = { app.playback.retry(); showControls = true },
                        darkSurface = true
                    )
                }
            }
        }

        if (locked) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PlayerGlassButton(
                    icon = Icons.Default.LockOpen,
                    contentDescription = "Sblocca controlli",
                    size = 58.dp,
                    onClick = { locked = false; showControls = true },
                    darkSurface = true
                )
            }
        }

        if (!locked) {
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                PlayerControlsOverlay(
                    app = app,
                    item = item,
                    sourceName = source?.name,
                    settings = localSettings,
                    snapshot = snapshot,
                    progressState = progressState,
                    currentLiveProgram = currentLiveProgram,
                    currentSpeed = currentSpeed,
                    previousItem = previousItem,
                    nextItem = nextItem,
                    onBack = onBack,
                    onPip = onPip,
                    supportsPip = supportsPip,
                    onExternal = { app.playback.handoffExternal(context, item.streamUrl) },
                    onShowDialog = { dialog = it },
                    onToggleDecoder = {
                        val wasHardware = localSettings.hardwareDecode
                        applySettings(localSettings.copy(hardwareDecode = !wasHardware, softwareDecode = wasHardware))
                        toast = if (wasHardware) "Decodifica software" else "Decodifica hardware"
                        showControls = true
                    },
                    onLock = { locked = true },
                    onSeek = { app.playback.seekTo(it) },
                    onPlayPause = { app.playback.togglePlayPause() },
                    playPauseFocusRequester = controlsFocusRequester,
                    isTelevision = remoteFirstDevice,
                    onUserActivity = { controlsActivity++ },
                    onScrubbingChanged = { scrubbing ->
                        controlsScrubbing = scrubbing
                        if (!scrubbing) controlsActivity++
                    },
                    onSkipBack = { app.playback.skipBack(15_000L) },
                    onSkipForward = { app.playback.skipForward(15_000L) },
                    onPrevious = {
                        previousItem?.let(onNavigateToItem)
                    },
                    onNext = {
                        nextItem?.let(onNavigateToItem)
                    },
                    onCloseWithoutSave = onBack,
                    onRequestDialog = { dialog = it }
                )
            }
        } else {
            Surface(
                onClick = { locked = false; showControls = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp)
                    .remoteFocusOutline(cornerRadius = 18.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(.58f),
                border = BorderStroke(1.dp, Color.White.copy(.14f))
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LockOpen, null, tint = glassForeground())
                    Spacer(Modifier.width(8.dp))
                    Text("Sblocca", color = glassForeground(), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        AnimatedVisibility(
            visible = showBrightnessHud,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)
        ) {
            PlayerLevelHud(Icons.Default.Brightness6, brightnessLevel, "Luminosità")
        }
        AnimatedVisibility(
            visible = showVolumeHud,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp)
        ) {
            PlayerLevelHud(Icons.Default.VolumeUp, volumeLevel, "Volume")
        }

        toast?.let { message ->
            LaunchedEffect(message) {
                delay(900L)
                toast = null
            }
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(.72f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(.12f))
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    color = glassForeground(),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        resumePosition?.let { resume ->
            ResumeOverlay(
                position = resume,
                title = item.title,
                onResume = {
                    resumePosition = null
                    explicitResume = false
                    app.playback.seekTo(resume)
                    app.playback.playNow()
                    showControls = true
                },
                onRestart = {
                    // "Ricomincia da capo": cancella la posizione salvata (PlaybackPositionStore.clear iOS).
                    app.appScope.launch { recordWatchEntry(app, item, 0L, 0L, reset = true) }
                    resumePosition = null
                    explicitResume = false
                    app.playback.seekTo(0L)
                    app.playback.playNow()
                    showControls = true
                }
            )
        }

        // Come iOS: solo serie TV, preferenza attiva, e solo nell'ultimo minuto dell'episodio.
        if (nextPromptVisible && nextItem != null) {
            NextEpisodePrompt(
                focusRequester = nextPromptFocusRequester,
                onFocusChange = { focused ->
                    nextPromptFocused = focused
                    nextPromptHadFocus = focused
                },
                onClick = {
                    advancedToNext = true
                    app.playback.pause()
                    onNavigateToItem(nextItem)
                }
            )
        }

        if (showLiveCategoryRail && item.kind == MediaKind.LIVE) {
            PlayerLiveCategoryRail(
                options = liveGroupOptions,
                selectedGroupId = playerLiveGroupId,
                channels = playerLiveChannels,
                currentChannelId = item.id,
                currentProgramTitle = currentLiveProgram?.title,
                favoriteIds = liveFavorites.live,
                onGroupSelected = { playerLiveGroupId = it },
                onChannelSelected = { selected ->
                    // Keep the glass browser and selected group open while zapping, as GassTV does.
                    onNavigateToItem(selected)
                    showControls = false
                },
                onDismiss = {
                    showLiveCategoryRail = false
                    showControls = false
                    runCatching { playerFocusRequester.requestFocus() }
                },
                modifier = Modifier.align(Alignment.CenterStart).fillMaxSize().zIndex(50f)
            )
        }
    }

    when (dialog) {
        IosPlayerDialog.OPTIONS -> PlayerOptionsDialog(
            settings = localSettings,
            currentSpeed = currentSpeed,
            onDismiss = { dialog = null },
            onAspect = { dialog = IosPlayerDialog.ASPECT },
            onSpeed = { dialog = IosPlayerDialog.SPEED },
            onQuality = { dialog = IosPlayerDialog.QUALITY },
            onTracks = { dialog = IosPlayerDialog.TRACKS },
            onSleep = { dialog = IosPlayerDialog.SLEEP },
            onAdvanced = { dialog = IosPlayerDialog.ADVANCED },
            onHistory = if (item.kind == MediaKind.LIVE) {
                {
                    dialog = IosPlayerDialog.CHANNEL_HISTORY
                }
            } else null,
            onSearch = if (item.kind == MediaKind.LIVE) onOpenSearch else null,
            onExternal = {
                dialog = null
                app.playback.handoffExternal(context, item.streamUrl)
            },
            onLock = {
                dialog = null
                locked = true
                showControls = false
            }
        )
        IosPlayerDialog.SPEED -> SpeedDialog(
            current = currentSpeed,
            onDismiss = { dialog = null },
            onSelected = {
                currentSpeed = it
                applySettings(localSettings.copy(preferredPlaybackSpeed = it))
                dialog = null
            }
        )
        IosPlayerDialog.ASPECT -> AspectDialog(
            current = aspect,
            onDismiss = { dialog = null },
            onSelected = {
                aspect = it
                val updated = localSettings.copy(aspectRatio = it)
                applySettings(updated)
                dialog = null
            }
        )
        IosPlayerDialog.QUALITY -> QualityDialog(
            player = app.playback.player,
            onDismiss = { dialog = null },
            onSelected = { group, index ->
                app.playback.selectTrack(group, index)
                dialog = null
            }
        )
        IosPlayerDialog.TRACKS -> TrackDialog(
            player = app.playback.player,
            currentLanguage = localSettings.subtitleLanguage,
            onDismiss = { dialog = null },
            onAudio = { lang ->
                app.playback.selectAudio(lang)
            },
            onSubtitle = { lang ->
                app.playback.selectSubtitle(lang)
                if (!lang.isNullOrBlank()) {
                    applySettings(localSettings.copy(subtitleLanguage = lang))
                }
            },
            onSubtitleEnabled = { enabled ->
                app.playback.setSubtitleEnabled(enabled)
            }
        )
        IosPlayerDialog.SLEEP -> SleepDialog(
            current = app.playback.sleepRemainingMinutes.collectAsStateWithLifecycle().value,
            onDismiss = { dialog = null },
            onSelect = {
                if (it == null) app.playback.cancelSleepTimer() else app.playback.startSleepTimer(it)
                dialog = null
            }
        )
        IosPlayerDialog.ADVANCED -> AdvancedDialog(
            settings = localSettings,
            onDismiss = { dialog = null },
            onChange = applySettings
        )
        IosPlayerDialog.CHANNEL_HISTORY -> ChannelHistoryDialog(
            history = watch.filter { it.kind == MediaKind.LIVE }
                .mapNotNull { entry -> catalog?.findById(entry.contentId) ?: entry.toMediaItemFallback() }
                .distinctBy { it.id }
                .take(50),
            onDismiss = { dialog = null },
            onSelect = { selected ->
                dialog = null
                onNavigateToItem(selected)
            }
        )
        null -> Unit
    }
}

@Composable
private fun PlayerLiveCategoryRail(
    options: List<TvCategoryRailOption>,
    selectedGroupId: String,
    channels: List<MediaItem>,
    currentChannelId: String,
    currentProgramTitle: String?,
    favoriteIds: Set<String>,
    onGroupSelected: (String) -> Unit,
    onChannelSelected: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedGroupFocus = remember { FocusRequester() }
    val firstChannelFocus = remember { FocusRequester() }
    val focusScope = rememberCoroutineScope()
    val groupListState = rememberLazyListState()
    val channelListState = rememberLazyListState()
    val optionIds = remember(options) { options.map { it.id } }
    LaunchedEffect(optionIds, selectedGroupId) {
        if (optionIds.isNotEmpty()) {
            val selectedIndex = selectedFocusIndex(optionIds, selectedGroupId).coerceIn(0, optionIds.lastIndex)
            runCatching { groupListState.scrollToItem(selectedIndex) }
            delay(70L)
            selectedGroupFocus.requestFocusWithRetry(attempts = 5, delayMillis = 45L)
        }
    }
    val focusChannelId = channels.firstOrNull { it.id == currentChannelId }?.id ?: channels.firstOrNull()?.id
    var previousPlayingChannelId by remember { mutableStateOf(currentChannelId) }
    LaunchedEffect(currentChannelId, channels) {
        val channelChangedWhileRailOpen = currentChannelId != previousPlayingChannelId
        previousPlayingChannelId = currentChannelId
        val activeIndex = channels.indexOfFirst { it.id == currentChannelId }
        runCatching { channelListState.scrollToItem(activeIndex.coerceAtLeast(0)) }
        // Initial rail opening starts at the selected group; after a real zap/channel selection,
        // keep focus on the newly playing channel so the next DPAD action is predictable.
        if (channelChangedWhileRailOpen && channels.isNotEmpty()) {
            delay(65L)
            firstChannelFocus.requestFocusWithRetry(attempts = 5, delayMillis = 45L)
        }
    }

    BoxWithConstraints(modifier) {
        // Capture the constraint here: BoxWithConstraintsScope's implicit receiver is not
        // available inside every nested Material content lambda on all Compose compiler versions.
        val compactLayout = maxWidth < 700.dp
        // A light scrim keeps the moving video visible through the LiquidGlass surface.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(if (compactLayout) .30f else .12f)))
        val panelWidth = if (compactLayout) maxWidth * .96f else (maxWidth * .72f).coerceAtMost(760.dp)
        val groupWidth = if (compactLayout) 126.dp else 224.dp
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = if (compactLayout) 6.dp else 22.dp, top = 12.dp, bottom = 12.dp)
                .width(panelWidth)
                .fillMaxHeight(.94f)
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Semi-transparent glass base keeps live video visible through the rail; its
            // rows still use the app's shared LiquidGlass surfaces and focus treatment.
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(25.dp),
                color = Color.Black.copy(alpha = if (compactLayout) .58f else .46f),
                border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(.20f), Color.White.copy(.055f)))),
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(if (compactLayout) 8.dp else 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Icon(Icons.Default.LiveTv, contentDescription = null, tint = Color(0xFFBBD0FF), modifier = Modifier.size(23.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Categorie TV", color = glassForeground(), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(currentProgramTitle?.takeIf { it.isNotBlank() } ?: "Gruppi e canali", color = glassForeground(.62f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PlayerGlassButton(Icons.Default.Close, "Chiudi categorie", size = 38.dp, onClick = onDismiss)
                    }
                    HorizontalDivider(color = glassForeground(.13f), modifier = Modifier.padding(vertical = 5.dp))
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(
                            Modifier.width(groupWidth).fillMaxHeight().focusGroup()
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                        focusScope.launch { firstChannelFocus.requestFocusWithRetry(attempts = 5, delayMillis = 45L) }
                                        true
                                    } else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                        onDismiss(); true
                                    } else false
                                }
                        ) {
                            Text("GRUPPI", color = glassForeground(.55f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp, bottom = 5.dp))
                            LazyColumn(
                                state = groupListState,
                                modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                itemsIndexed(options, key = { _, opt -> opt.id }) { index, option ->
                                    val selected = option.id == selectedGroupId
                                    Surface(
                                        onClick = { onGroupSelected(option.id) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (option.id == selectedGroupId) Modifier.focusRequester(selectedGroupFocus) else Modifier)
                                            .remoteFocusOutline(cornerRadius = 12.dp)
                                            .onPreviewKeyEvent { event ->
                                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                                    focusScope.launch { firstChannelFocus.requestFocusWithRetry(attempts = 5, delayMillis = 45L) }; true
                                                } else false
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(if (selected) 1.2.dp else .45.dp, if (selected) Color(0xFF8CB7FF).copy(.85f) else glassForeground(.12f)),
                                    ) {
                                        LiquidGlassSurface(
                                            modifier = Modifier.fillMaxWidth(),
                                            cornerRadius = 12.dp,
                                            contentPadding = 0.dp,
                                            highlighted = selected,
                                            suppressTopHighlight = true,
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().height(if (compactLayout) 40.dp else 44.dp).padding(horizontal = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(option.icon ?: Icons.Default.FolderOpen, null, tint = if (selected) Color(0xFFBBD0FF) else glassForeground(.62f), modifier = Modifier.size(15.dp))
                                                Text(option.label, color = glassForeground(if (selected) 1f else .82f), fontSize = if (compactLayout) 10.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                                Text(option.count.toString(), color = glassForeground(.48f), fontSize = 9.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        VerticalDivider(color = glassForeground(.12f), modifier = Modifier.fillMaxHeight())
                        Column(
                            Modifier.weight(1f).fillMaxHeight().focusGroup()
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                        focusScope.launch { selectedGroupFocus.requestFocusWithRetry(attempts = 5, delayMillis = 45L) }; true
                                    } else false
                                }
                        ) {
                            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("CANALI", color = glassForeground(.55f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(channels.size.toString(), color = glassForeground(.48f), fontSize = 10.sp)
                            }
                            if (channels.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Nessun canale nel gruppo", color = glassForeground(.62f), fontSize = 12.sp)
                                }
                            } else {
                                LazyColumn(
                                    state = channelListState,
                                    modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                                        val playing = channel.id == currentChannelId
                                        Surface(
                                            onClick = { onChannelSelected(channel) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .then(if (channel.id == focusChannelId) Modifier.focusRequester(firstChannelFocus) else Modifier)
                                                .remoteFocusOutline(cornerRadius = 13.dp),
                                            shape = RoundedCornerShape(13.dp),
                                            color = Color.Transparent,
                                            border = BorderStroke(if (playing) 1.2.dp else .45.dp, if (playing) Color(0xFF8CB7FF).copy(.9f) else glassForeground(.12f)),
                                        ) {
                                            LiquidGlassSurface(
                                                modifier = Modifier.fillMaxWidth(),
                                                cornerRadius = 13.dp,
                                                contentPadding = 0.dp,
                                                highlighted = playing,
                                                suppressTopHighlight = true,
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth().height(if (compactLayout) 48.dp else 54.dp).padding(horizontal = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                                                        Text(channel.number?.toString() ?: (index + 1).toString(), color = glassForeground(.50f), fontSize = 10.sp, maxLines = 1)
                                                    }
                                                    ArtworkImage(
                                                        model = channel.logoUrl,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(if (compactLayout) 28.dp else 38.dp),
                                                        contentScale = ContentScale.Fit,
                                                        fallback = { Icon(Icons.Default.LiveTv, contentDescription = null, tint = glassForeground(.55f), modifier = Modifier.size(22.dp)) },
                                                    )
                                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                                        Text(channel.title, color = glassForeground(if (playing) 1f else .86f), fontSize = if (compactLayout) 11.sp else 13.sp, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        if (playing) Text("IN RIPRODUZIONE", color = Color(0xFFBBD0FF), fontSize = 8.sp, maxLines = 1)
                                                    }
                                                    if (channel.id in favoriteIds) Icon(Icons.Default.Star, null, tint = Color(0xFFFFD166), modifier = Modifier.size(13.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    currentProgramTitle?.takeIf { it.isNotBlank() }?.let { program ->
                        HorizontalDivider(color = glassForeground(.13f), modifier = Modifier.padding(vertical = 6.dp))
                        Text("IN ONDA", color = Color(0xFFBBD0FF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(program, color = glassForeground(.86f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControlsOverlay(
    app: GassPlayerApplication,
    item: MediaItem,
    sourceName: String?,
    settings: AppSettings,
    snapshot: PlayerSnapshot,
    progressState: State<PlaybackProgress>,
    currentLiveProgram: EpgProgram?,
    currentSpeed: Float,
    previousItem: MediaItem?,
    nextItem: MediaItem?,
    onBack: () -> Unit,
    onPip: () -> Unit,
    supportsPip: Boolean,
    onExternal: () -> Unit,
    onShowDialog: (IosPlayerDialog) -> Unit,
    onToggleDecoder: () -> Unit,
    onLock: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCloseWithoutSave: () -> Unit,
    onRequestDialog: (IosPlayerDialog) -> Unit,
    playPauseFocusRequester: FocusRequester? = null,
    isTelevision: Boolean = false,
    onUserActivity: () -> Unit = {},
    onScrubbingChanged: (Boolean) -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(.80f), Color.Transparent)
                    )
                )
        )

        Row(
            Modifier
                .fillMaxWidth()
                .focusGroup()
                .padding(start = 20.dp, end = 16.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerGlassButton(Icons.Default.Close, "Chiudi", onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (supportsPip) {
                PlayerGlassButton(Icons.Default.PictureInPictureAlt, "PiP", onClick = onPip)
            }
            PlayerGlassButton(Icons.Default.OpenInNew, "Esterno", onClick = onExternal)
            PlayerGlassButton(
                Icons.Default.Memory,
                if (settings.hardwareDecode) "Decodifica hardware" else "Decodifica software",
                onClick = onToggleDecoder
            )
            PlayerGlassButton(Icons.Default.Lock, "Blocca", onClick = onLock)
            PlayerGlassButton(Icons.Default.MoreVert, "Opzioni") {
                onRequestDialog(IosPlayerDialog.OPTIONS)
            }
        }

        // Info media + barra di avanzamento IMPILATE in un'unica colonna (come il VStack di PlayerView.swift:
        // infoBlock sopra, progressBar sotto): non possono piu' sovrapporsi, qualunque sia l'altezza del titolo,
        // della descrizione del programma o dei badge.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        .35f to Color.Black.copy(.38f),
                        1f to Color.Black.copy(.78f)
                    )
                )
                .padding(top = 40.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp)
                    .padding(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (item.kind == MediaKind.LIVE && currentLiveProgram != null) {
                    item.logoUrl?.takeIf { it.isNotBlank() }?.let { logo ->
                        ArtworkImage(
                            model = logo,
                            contentDescription = null,
                            modifier = Modifier
                                .size(width = 83.dp, height = 46.dp)
                                .padding(bottom = 4.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                val smallLine = when {
                    item.kind == MediaKind.EPISODE -> {
                        buildString {
                            item.seasonNumber?.let { append("Stagione $it") }
                            item.episodeNumber?.let {
                                if (isNotEmpty()) append(" · ")
                                append("Episodio $it")
                            }
                        }.takeIf { it.isNotBlank() }
                    }
                    item.kind == MediaKind.LIVE && currentLiveProgram != null -> item.title.uppercase()
                    else -> null
                }

                smallLine?.let {
                    Text(
                        it,
                        color = glassForeground().copy(.9f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    if (item.kind == MediaKind.LIVE && currentLiveProgram != null) currentLiveProgram.title else item.title,
                    color = glassForeground(),
                    fontSize = 29.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.kind == MediaKind.LIVE) {
                    currentLiveProgram?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            color = glassForeground().copy(.9f),
                            fontSize = 13.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                val resolution = if (snapshot.videoWidth > 0 && snapshot.videoHeight > 0) {
                    if (item.kind == MediaKind.LIVE) {
                        when {
                            snapshot.videoHeight >= 2000 -> "UHD"
                            snapshot.videoHeight >= 1000 -> "FHD"
                            snapshot.videoHeight >= 700 -> "HD"
                            else -> "SD"
                        }
                    } else {
                        "${snapshot.videoWidth}X${snapshot.videoHeight}"
                    }
                } else null
                val fps = snapshot.frameRate.takeIf { it > 0f }?.roundToInt()?.let { "${it} FPS" }
                val audio = audioLabel(snapshot.audioChannels)
                val badgeItems = buildList {
                    add("Media3")
                    (sourceName?.takeIf { it.isNotBlank() } ?: item.sourceId.takeIf { it.isNotBlank() })
                        ?.let { add(it.take(18)) }
                    resolution?.let(::add)
                    fps?.let(::add)
                    audio?.let(::add)
                }.distinct()

                if (badgeItems.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        badgeItems.forEach { BadgeChip(it) }
                    }
                }
            }

            IosPlayerProgressSection(
                app = app,
                item = item,
                snapshot = snapshot,
                progressState = progressState,
                previousItem = previousItem,
                nextItem = nextItem,
                isTelevision = isTelevision,
                playPauseFocusRequester = playPauseFocusRequester,
                onSeek = onSeek,
                onPlayPause = onPlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onPrevious = onPrevious,
                onNext = onNext,
                onUserActivity = onUserActivity,
                onScrubbingChanged = onScrubbingChanged,
            )
        }
    }
}

/**
 * `progressBar` di PlayerView.swift: VStack(spacing 8) con lo Slider bianco, la riga di trasporto centrata
 * (HStack spacing 22: precedente 30, -15 34, play/pausa 44, +15 34, successivo 30, in proporzione su TV) e
 * il tempo "m:ss / m:ss" centrato (o "Live"); padding orizzontale 16 e inferiore 8.
 */
@Composable
private fun IosPlayerProgressSection(
    app: GassPlayerApplication,
    item: MediaItem,
    snapshot: PlayerSnapshot,
    progressState: State<PlaybackProgress>,
    previousItem: MediaItem?,
    nextItem: MediaItem?,
    isTelevision: Boolean,
    playPauseFocusRequester: FocusRequester?,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onUserActivity: () -> Unit,
    onScrubbingChanged: (Boolean) -> Unit = {},
) {
    val duration = snapshot.duration
    // Posizione mostrata durante lo scrubbing col telecomando (anteprima), null = posizione reale.
    var scrubPosition by remember(item.id) { mutableStateOf<Long?>(null) }
    // Al rilascio anteprima e tempo spariscono (scrubPosition = null) e il timer dei controlli riparte.
    val scrubbingNow = scrubPosition != null
    LaunchedEffect(scrubbingNow) { onScrubbingChanged(scrubbingNow) }
    DisposableEffect(Unit) { onDispose { onScrubbingChanged(false) } }
    val position = scrubPosition ?: progressState.value.position
    // Proporzioni iOS (30/34/44) portate alla scala TV, con il minimo di 48 dp dei pulsanti del player.
    val scale = if (isTelevision) 1.45f else 1.2f
    Column(
        Modifier
            .zIndex(1f)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = if (isTelevision) 18.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (duration > 0L) {
            // Film / Serie TV: anteprima del punto scelto sopra la timeline durante lo scrubbing.
            val thumbnailSource = remember(item.id, duration > 0L) { app.playback.thumbnailSource() }
            val thumbnail = rememberPlayerThumbnail(thumbnailSource, scrubPosition, duration) { progressState.value.position }
            val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.roundToPx() }
            // zIndex: l'anteprima, che sale sopra la barra, resta disegnata davanti a tutto.
            BoxWithConstraints(Modifier.fillMaxWidth().zIndex(1f)) {
                val containerWidthPx = constraints.maxWidth
                IosPlayerSlider(
                    position = position.coerceIn(0L, duration),
                    buffered = progressState.value.buffered.coerceIn(0L, duration),
                    duration = duration,
                    isTelevision = isTelevision,
                    onScrub = { scrubPosition = it; onUserActivity() },
                    onCommit = { target ->
                        onSeek(target)
                        scrubPosition = null
                    },
                )
                scrubPosition?.let { scrub ->
                    PlayerScrubPreview(
                        positionMs = scrub,
                        thumbnail = if (thumbnailSource != null) thumbnail else null,
                        isTelevision = isTelevision,
                        modifier = Modifier.zIndex(2f).scrubPreviewPlacement(
                            fraction = scrub.toFloat() / duration.toFloat(),
                            containerWidthPx = containerWidthPx,
                            gapPx = gapPx,
                        ),
                    )
                }
            }
        }

        Row(
            Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Come iOS: Precedente/Successivo solo se esistono, -15/+15 solo con una durata (non live).
            if (previousItem != null) PlayerGlassButton(Icons.Default.SkipPrevious, "Precedente", size = (30 * scale).dp, onClick = onPrevious)
            if (duration > 0L) PlayerGlassButton(Icons.Default.Replay10, "-15s", size = (34 * scale).dp, onClick = onSkipBack)
            Box(playPauseFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier) {
                PlayerGlassButton(
                    if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (snapshot.isPlaying) "Pausa" else "Riproduci",
                    size = (44 * scale).dp,
                    onClick = onPlayPause
                )
            }
            if (duration > 0L) PlayerGlassButton(Icons.Default.Forward10, "+15s", size = (34 * scale).dp, onClick = onSkipForward)
            if (nextItem != null) PlayerGlassButton(Icons.Default.SkipNext, "Successivo", size = (30 * scale).dp, onClick = onNext)
        }

        Text(
            if (duration > 0L) "${iosPlayerTime(position)} / ${iosPlayerTime(duration)}" else "Live",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = if (duration > 0L) FontWeight.Normal else FontWeight.SemiBold,
            fontFamily = if (duration > 0L) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            maxLines = 1
        )
    }
}

/** `formatted(_:)` di PlayerView.swift: "h:mm:ss" oltre l'ora, altrimenti "m:ss". */
private fun iosPlayerTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Lo `Slider` bianco di iOS (`.tint(.white)`): traccia sottile arrotondata, parte trascorsa bianca, resto grigio
 * traslucido, pallino bianco con ombra. Tocco/trascinamento per cercare (telefono). Col telecomando: il
 * focus ingrandisce pallino e traccia; SINISTRA/DESTRA spostano un'anteprima di 10 s (piu' veloce tenendo
 * premuto) e la ricerca parte una sola volta, a tasto rilasciato o con OK, invece di una seek per ogni pressione.
 */
@Composable
private fun IosPlayerSlider(
    position: Long,
    buffered: Long,
    duration: Long,
    isTelevision: Boolean,
    onScrub: (Long) -> Unit,
    onCommit: (Long) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Long?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val latestPosition by rememberUpdatedState(position)
    val latestDuration by rememberUpdatedState(duration)
    val scope = rememberCoroutineScope()
    var commitJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val fraction = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    // Parte gia' scaricata (mai prima della posizione): ci si sposta avanti/indietro sapendo cosa e' pronto.
    val bufferedFraction = if (duration > 0L) (maxOf(buffered, position).toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val trackHeight = if (focused) 6.dp else 4.dp
    val thumbSize = if (focused) 30.dp else if (isTelevision) 22.dp else 28.dp

    fun scheduleCommit(target: Long) {
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(650L)
            pending = null
            onCommit(target)
        }
    }

    fun positionAt(x: Float): Long =
        ((x / widthPx).coerceIn(0f, 1f) * latestDuration).toLong()

    Box(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .onFocusChanged { state ->
                focused = state.isFocused
                // Uscendo dalla barra con un'anteprima in sospeso, la ricerca parte subito.
                if (!state.isFocused) pending?.let { target -> commitJob?.cancel(); pending = null; onCommit(target) }
            }
            .onKeyEvent { event ->
                // Tasto rilasciato: la ricerca parte subito e anteprima/tempo spariscono.
                if (event.type == KeyEventType.KeyUp) {
                    val released = event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                        event.key == Key.MediaRewind || event.key == Key.MediaFastForward
                    val target = pending
                    if (released && target != null) {
                        commitJob?.cancel()
                        pending = null
                        onCommit(target)
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight, Key.MediaRewind, Key.MediaFastForward -> {
                        val forward = event.key == Key.DirectionRight || event.key == Key.MediaFastForward
                        // Tenendo premuto il passo cresce: 10 s, poi 30 s, poi 60 s.
                        val repeat = event.nativeKeyEvent.repeatCount
                        val step = when {
                            repeat >= 20 -> 60_000L
                            repeat >= 6 -> 30_000L
                            else -> 10_000L
                        }
                        val base = pending ?: latestPosition
                        val target = (base + if (forward) step else -step).coerceIn(0L, latestDuration)
                        pending = target
                        onScrub(target)
                        scheduleCommit(target)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        val target = pending ?: return@onKeyEvent false
                        commitJob?.cancel()
                        pending = null
                        onCommit(target)
                        true
                    }
                    else -> false
                }
            }
            .pageFocusGuard()
            .focusable()
            .pointerInput(duration) {
                detectTapGestures { offset -> onCommit(positionAt(offset.x)) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragEnd = { pending?.let { target -> pending = null; onCommit(target) } },
                    onDragCancel = { pending?.let { target -> pending = null; onCommit(target) } },
                ) { change, _ ->
                    val target = positionAt(change.position.x)
                    pending = target
                    onScrub(target)
                }
            }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) },
        contentAlignment = Alignment.CenterStart
    ) {
        // Traccia: trascorso bianco, restante grigio traslucido (maximumTrackTint di sistema su video).
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = .30f))
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(bufferedFraction).background(Color.White.copy(alpha = .45f)))
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Color.White))
        }
        // Pallino bianco con ombra; col focus del telecomando anello d'accento.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val offset = (maxWidth - thumbSize) * fraction
            Box(
                Modifier
                    .offset(x = offset)
                    .size(thumbSize)
                    .shadow(if (focused) 6.dp else 3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .then(
                        if (focused) Modifier.border(3.dp, Color(0xFF8CB7FF), CircleShape) else Modifier
                    )
            )
        }
    }
}

/** Tasti del telecomando che, alla comparsa di "Prossimo episodio", portano il focus sul pulsante. */
private val nextPromptFocusKeys = setOf(
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight
)

/**
 * Stessa logica di iOS (PlayerView.showNextEpisodeButton): durata > 60 s, riproduzione iniziata, e compare
 * quando mancano esattamente 60 s (fino a 0,4 s dalla fine).
 */
private fun nextEpisodePromptInWindow(duration: Long, position: Long): Boolean {
    if (duration <= 60_000L || position <= 0L) return false
    val remaining = duration - position
    return remaining in 401L..60_000L
}

@Composable
private fun NextEpisodePrompt(
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, label = "next-episode-focus")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .padding(end = 24.dp, bottom = 104.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChange(it.isFocused)
                }
                .remoteFocusOutline(cornerRadius = 18.dp),
            // Focalizzato: pulsante chiaro con testo scuro, ben visibile sul video (come i pulsanti tvOS).
            color = if (focused) Color.White else Color.Black.copy(.75f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color.White.copy(if (focused) .9f else .14f))
        ) {
            Text(
                "Prossimo episodio  ›",
                color = if (focused) Color(0xFF111111) else glassForeground(),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PlayerLevelHud(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueState: State<Float>,
    label: String
) {
    val value = valueState.value
    Surface(
        color = Color.Black.copy(.72f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(.16f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp).width(42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = label, tint = glassForeground())
            androidx.compose.foundation.layout.Box(
                Modifier.height(120.dp).width(5.dp).background(Color.White.copy(.22f), RoundedCornerShape(50))
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f))
                        .align(Alignment.BottomCenter).background(Color.White, RoundedCornerShape(50))
                )
            }
            Text("${(value.coerceIn(0f, 1f) * 100).roundToInt()}%", color = glassForeground(), fontSize = 11.sp)
        }
    }
}

@Composable
private fun BadgeChip(text: String) {
    Surface(
        color = glassForeground().copy(.28f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Color.White.copy(.16f))
    ) {
        Text(
            text,
            color = Color.Black.copy(.68f),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ResumeOverlay(position: Long, title: String, onResume: () -> Unit, onRestart: () -> Unit) {
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        resumeFocus.requestFocusWithRetry(attempts = 8, delayMillis = 30L)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(.62f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 300.dp, max = 520.dp),
            color = Color(0xFF151515).copy(.96f),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color.White.copy(.14f))
        ) {
            Column(
                Modifier.padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Riprendi la visione?", color = glassForeground(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Ti eri fermato a ${formatTime(position)}",
                    color = glassForeground().copy(.74f),
                    fontSize = 14.sp
                )
                Text(title, color = glassForeground().copy(.92f), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlayerGlassButton(onClick = onResume, darkSurface = true, modifier = Modifier.focusRequester(resumeFocus)) { Text("Riprendi da…") }
                    PlayerGlassButton(onClick = onRestart, darkSurface = true) { Text("Ricomincia da capo") }
                }
            }
        }
    }
}

@Composable
private fun PlayerOptionsDialog(
    settings: AppSettings,
    currentSpeed: Float,
    onDismiss: () -> Unit,
    onAspect: () -> Unit,
    onSpeed: () -> Unit,
    onQuality: () -> Unit,
    onTracks: () -> Unit,
    onSleep: () -> Unit,
    onAdvanced: () -> Unit,
    onExternal: () -> Unit,
    onHistory: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    onLock: () -> Unit
) {
    PlayerDialogFrame("Opzioni", onDismiss) {
        DialogRow("Rapporto di aspetto", aspectLabel(settings.aspectRatio), Icons.Default.AspectRatio, onAspect)
        DialogRow("Velocità", "${currentSpeed}×", Icons.Default.Speed, onSpeed)
        DialogRow("Qualità", if (settings.adaptiveBitrate) "Automatica" else "Limitata", Icons.Default.HighQuality, onQuality)
        DialogRow("Audio e sottotitoli", settings.subtitleLanguage.uppercase(), Icons.Default.Subtitles, onTracks)
        DialogRow("Timer di spegnimento", "Media3", Icons.Default.Bedtime, onSleep)
        if (onHistory != null) {
            DialogRow("Cronologia canali", "Ultimi canali live", Icons.Default.History, onHistory)
        }
        if (onSearch != null) {
            DialogRow("Cerca canale", "Ricerca globale", Icons.Default.Search, onSearch)
        }
        DialogRow("Impostazioni avanzate", "Buffer, seek e decoder", Icons.Default.Tune, onAdvanced)
        DialogRow("Apri con un altro player", "Lettore esterno", Icons.Default.OpenInNew, onExternal)
        DialogRow("Blocca controlli", "Protegge dagli input accidentali", Icons.Default.Lock, onLock)
    }
}

@Composable
private fun SpeedDialog(
    current: Float,
    onDismiss: () -> Unit,
    onSelected: (Float) -> Unit
) {
    PlayerDialogFrame("Velocità di riproduzione", onDismiss) {
        listOf(0.5f, 1f, 1.5f, 2f).forEach { rate ->
            DialogRow(
                if (rate == 1f) "Normale (1×)" else "${rate}×",
                if (rate == current) "✓ Selezionata" else null,
                Icons.Default.Speed
            ) { onSelected(rate) }
        }
    }
}

@Composable
private fun AspectDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    PlayerDialogFrame("Rapporto di aspetto", onDismiss) {
        listOf(
            "fit" to "Adatta",
            "fill" to "Riempi",
            "stretch" to "Stira"
        ).forEach { (value, label) ->
            DialogRow(
                label,
                if (value == current) "✓ Selezionato" else null,
                Icons.Default.AspectRatio
            ) { onSelected(value) }
        }
    }
}

@Composable
private fun QualityDialog(
    player: Player,
    onDismiss: () -> Unit,
    onSelected: (Tracks.Group, Int) -> Unit
) {
    val choices = remember(player.currentTracks) {
        player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { i ->
                    val f = group.getTrackFormat(i)
                    if (f.width <= 0 || f.height <= 0) null
                    else PlayerTrackChoice(group, i, qualityLabel(f), C.TRACK_TYPE_VIDEO)
                }
            }
            .distinctBy { it.label }
            .sortedByDescending { it.group.getTrackFormat(it.index).height }
    }

    PlayerDialogFrame("Qualità", onDismiss) {
        if (choices.isEmpty()) {
            Text("Il flusso non espone tracce video selezionabili.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            choices.forEach { choice ->
                DialogRow(
                    choice.label,
                    if (choice.group.isTrackSelected(choice.index)) "✓ Attiva" else null,
                    Icons.Default.HighQuality
                ) { onSelected(choice.group, choice.index) }
            }
        }
    }
}

@Composable
private fun TrackDialog(
    player: Player,
    currentLanguage: String,
    onDismiss: () -> Unit,
    onAudio: (String?) -> Unit,
    onSubtitle: (String?) -> Unit,
    onSubtitleEnabled: (Boolean) -> Unit
) {
    val audioChoices = remember(player.currentTracks) {
        player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group ->
                (0 until group.length).map { i ->
                    val f = group.getTrackFormat(i)
                    f to group.isTrackSelected(i)
                }
            }
    }
    val subtitleChoices = remember(player.currentTracks) {
        player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group ->
                (0 until group.length).map { i ->
                    val f = group.getTrackFormat(i)
                    f to group.isTrackSelected(i)
                }
            }
    }

    PlayerDialogFrame("Audio e sottotitoli", onDismiss) {
        Text("Audio", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        if (audioChoices.isEmpty()) {
            Text("Nessuna traccia audio alternativa.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(.80f), fontSize = 13.sp)
        } else {
            audioChoices.forEach { (format, selected) ->
                DialogRow(
                    trackLanguageLabel(format, "Audio"),
                    if (selected) "✓ Attivo" else null,
                    Icons.Default.VolumeUp
                ) {
                    onAudio(format.language)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Sottotitoli", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        DialogRow(
            "Disattivati",
            if (subtitleChoices.none { it.second }) "✓" else null,
            Icons.Default.SubtitlesOff
        ) {
            onSubtitleEnabled(false)
        }
        subtitleChoices.forEach { (format, selected) ->
            DialogRow(
                trackLanguageLabel(format, "Sottotitoli"),
                if (selected) "✓ Attivi" else null,
                Icons.Default.Subtitles
            ) {
                onSubtitleEnabled(true)
                onSubtitle(format.language ?: currentLanguage)
            }
        }
    }
}

@Composable
private fun SleepDialog(
    current: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit
) {
    PlayerDialogFrame("Timer di spegnimento", onDismiss) {
        listOf(15, 30, 45, 60).forEach { minutes ->
            DialogRow(
                "$minutes minuti",
                if (current == minutes) "✓ Attivo" else null,
                Icons.Default.Bedtime
            ) { onSelect(minutes) }
        }
        if (current != null) {
            DialogRow("Disattiva timer", "Timer attivo: ${current} min", Icons.Default.BedtimeOff) {
                onSelect(null)
            }
        }
    }
}

@Composable
private fun ChannelHistoryDialog(
    history: List<MediaItem>,
    onDismiss: () -> Unit,
    onSelect: (MediaItem) -> Unit
) {
    PlayerDialogFrame("Cronologia canali", onDismiss) {
        if (history.isEmpty()) {
            Text("Nessun canale live recente.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            history.forEach { media ->
                DialogRow(
                    media.title,
                    media.group ?: "Canale live",
                    Icons.Default.LiveTv
                ) { onSelect(media) }
            }
        }
    }
}

private fun WatchEntry.toMediaItemFallback(): MediaItem = MediaItem(
    id = contentId,
    sourceId = "",
    kind = MediaKind.LIVE,
    title = title,
    streamUrl = url
)

@Composable
private fun AdvancedDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onChange: (AppSettings) -> Unit
) {
    // Old backups/preferences may contain values from pre-validation releases.
    // Normalize before any Slider/Stepper receives the settings, so opening the
    // advanced panel itself can never crash because of an out-of-range value.
    val safeMin = settings.minBufferSec.coerceIn(3, 30)
    val safeMax = settings.maxBufferSec.coerceIn(safeMin, 30)
    var draft by remember(settings) {
        mutableStateOf(
            settings.copy(
                minBufferSec = safeMin,
                maxBufferSec = safeMax,
                playerStartBufferSec = settings.playerStartBufferSec.coerceIn(safeMin, safeMax),
                videoDelayMs = settings.videoDelayMs.coerceIn(-2_000, 2_000)
            )
        )
    }
    PlayerDialogFrame("Impostazioni avanzate", onDismiss) {
        // The controls edit a draft only. ExoPlayer is never stopped/recreated from a
        // Slider/Switch callback while this dialog is still attached to the player surface.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingStepper("Buffer minimo", draft.minBufferSec, listOf(3, 5, 10, 15, 20, 30)) { selected ->
                val min = selected.coerceIn(3, draft.maxBufferSec.coerceAtMost(30))
                draft = draft.copy(minBufferSec = min, playerStartBufferSec = draft.playerStartBufferSec.coerceIn(min, draft.maxBufferSec.coerceAtMost(30)))
            }
            SettingStepper("Buffer massimo", draft.maxBufferSec, listOf(3, 5, 10, 15, 20, 30)) { selected ->
                val max = selected.coerceIn(draft.minBufferSec.coerceAtLeast(3), 30)
                draft = draft.copy(maxBufferSec = max, playerStartBufferSec = draft.playerStartBufferSec.coerceIn(draft.minBufferSec, max))
            }
            SettingStepper("Buffer di partenza", draft.playerStartBufferSec, listOf(3, 5, 8, 10, 15, 20, 30)) { selected ->
                draft = draft.copy(playerStartBufferSec = selected.coerceIn(draft.minBufferSec, draft.maxBufferSec))
            }
            SettingSwitch("Seek accurato", draft.accurateSeek) { draft = draft.copy(accurateSeek = it) }
            SettingSwitch("Adaptive bitrate", draft.adaptiveBitrate) { draft = draft.copy(adaptiveBitrate = it) }
            SettingSwitch("Cache HTTP", draft.httpCache) { draft = draft.copy(httpCache = it) }
            SettingSwitch("Solo audio", draft.audioOnly) { draft = draft.copy(audioOnly = it) }
            SettingSwitch("Decodifica hardware", draft.hardwareDecode) { draft = draft.copy(hardwareDecode = it, softwareDecode = !it) }
            SettingSwitch("Decodifica software", draft.softwareDecode) { draft = draft.copy(softwareDecode = it, hardwareDecode = !it) }
            SettingSwitch("Decodifica asincrona", draft.asyncDecode) { draft = draft.copy(asyncDecode = it) }
            SettingSwitch("Mantieni sottotitoli immagine", draft.preserveImageSubtitles) { draft = draft.copy(preserveImageSubtitles = it) }
            SettingSwitch("Deinterlacciamento", draft.deinterlace) { draft = draft.copy(deinterlace = it) }
            SettingSwitch("Panorama 360°", draft.panorama360) { draft = draft.copy(panorama360 = it) }
            SettingSwitch("Rotazione automatica 360°", draft.autoRotate360) { draft = draft.copy(autoRotate360 = it) }
            SettingSwitch("Prossimo episodio automatico", draft.autoplayNextEpisode) { draft = draft.copy(autoplayNextEpisode = it) }
            SettingSwitch("Riprendi la visione", draft.resumePlayback) { draft = draft.copy(resumePlayback = it) }
            SettingSwitch("Ripetizione", draft.loopPlayback) { draft = draft.copy(loopPlayback = it) }
            SettingDelay("Ritardo A/V (ms)", draft.videoDelayMs, -2_000..2_000, step = 50) { draft = draft.copy(videoDelayMs = it.coerceIn(-2_000, 2_000)) }
            SettingOption(
                title = "Risoluzione massima",
                current = when (draft.ffmpegLowResolution) {
                    "half" -> "Ridotta (max 720p)"
                    "quarter" -> "Bassa (max 480p)"
                    else -> "Originale"
                },
                options = listOf("Originale", "Ridotta (max 720p)", "Bassa (max 480p)"),
                onSelected = { selected -> draft = draft.copy(ffmpegLowResolution = when (selected) {
                    "Ridotta (max 720p)" -> "half"
                    "Bassa (max 480p)" -> "quarter"
                    else -> "full"
                }) }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Annulla") }
                Button(
                    onClick = {
                        val finalMin = draft.minBufferSec.coerceIn(3, 30)
                        val finalMax = draft.maxBufferSec.coerceIn(finalMin, 30)
                        onChange(
                            draft.copy(
                                minBufferSec = finalMin,
                                maxBufferSec = finalMax,
                                playerStartBufferSec = draft.playerStartBufferSec.coerceIn(finalMin, finalMax),
                                videoDelayMs = draft.videoDelayMs.coerceIn(-2_000, 2_000)
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Applica") }
            }
        }
    }
}

/** Slider that commits on release. Changing A/V delay rebuilds the renderers, so
 * saving each intermediate drag value would repeatedly destroy/recreate ExoPlayer. */
@Composable
private fun SettingDelay(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit
) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(.60f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(.20f))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("$title: ${draft.roundToInt()}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Slider(
                value = draft.coerceIn(range.first.toFloat(), range.last.toFloat()),
                onValueChange = { draft = it },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
                onValueChangeFinished = { onChange(draft.roundToInt().coerceIn(range.first, range.last)) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp, includeDescendants = true)
            )
        }
    }
}

@Composable
private fun SettingOption(
    title: String,
    current: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(.60f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(.20f))
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(current, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Icon(Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { expanded = false; onSelected(option) },
                    trailingIcon = { if (option == current) Icon(Icons.Default.Check, null) }, modifier = Modifier.remoteFocusOutline(cornerRadius = 8.dp, includeDescendants = true)
                )
            }
        }
    }
}

@Composable
private fun SettingStepper(title: String, value: Int, options: List<Int>, onChange: (Int) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(.60f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(.20f))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton({ onChange(options.filter { it < value }.lastOrNull() ?: value) }) { Text("−") }
                Text("$value s", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                TextButton({ onChange(options.firstOrNull { it > value } ?: value) }) { Text("+") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.remoteFocusOutline(cornerRadius = 50.dp, includeDescendants = true))
    }
}

@Composable
private fun DialogRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().remoteFocusOutline(cornerRadius = 14.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(.60f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(.20f))
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                subtitle?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(.72f), fontSize = 12.sp)
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(.65f))
        }
    }
}

@Composable
private fun PlayerDialogFrame(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(.28f))
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, darkSurface = false) {
                        Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}

private fun CatalogState.nextFor(item: MediaItem): MediaItem? = neighborOf(item, forward = true)

private fun CatalogState.previousFor(item: MediaItem): MediaItem? = neighborOf(item, forward = false)

/**
 * Channel up/down and next/previous episode. Live zapping is an indexed SQL lookup (it used to filter
 * and sort the entire channel list on the UI thread at every channel change).
 */
private fun CatalogState.neighborOf(item: MediaItem, forward: Boolean): MediaItem? {
    val db = store
    if (item.kind == MediaKind.LIVE && db != null) return db.liveNeighbor(item.sourceId, item.id, forward)
    val seriesKey = item.seriesId
    val sequence = when (item.kind) {
        MediaKind.EPISODE -> (if (db != null && seriesKey != null) db.episodesFor(item.sourceId, seriesKey)
            else episodes.filter { it.seriesId == item.seriesId && it.sourceId == item.sourceId })
            .sortedWith(compareBy<MediaItem> { it.seasonNumber ?: Int.MAX_VALUE }.thenBy { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.title })
        MediaKind.LIVE -> live
            .filter { it.sourceId == item.sourceId }
            .sortedWith(compareBy<MediaItem> { it.number ?: Int.MAX_VALUE }.thenBy { it.title })
        else -> emptyList()
    }
    val index = sequence.indexOfFirst { it.id == item.id }
    // Elemento non presente (episodi della serie non ancora in cache): nessun vicino. Prima restituiva il
    // PRIMO episodio della serie come "successivo".
    if (index < 0) return null
    return if (forward) sequence.getOrNull(index + 1) else if (index > 0) sequence[index - 1] else null
}

/** Episodio precedente/successivo in una lista di episodi della stessa serie (stagione, episodio, titolo). */
private fun List<MediaItem>.episodeNeighbor(item: MediaItem, forward: Boolean): MediaItem? {
    val sequence = filter { it.kind == MediaKind.EPISODE && it.seriesId == item.seriesId && it.sourceId == item.sourceId }
        .distinctBy { it.id }
        .sortedWith(compareBy<MediaItem> { it.seasonNumber ?: Int.MAX_VALUE }.thenBy { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.title })
    val index = sequence.indexOfFirst { it.id == item.id }
    if (index < 0) return null
    return if (forward) sequence.getOrNull(index + 1) else sequence.getOrNull(index - 1)
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private fun selectedVideoFormat(player: Player): Format? =
    player.currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && (0 until it.length).any(it::isTrackSelected) }
        ?.let { group ->
            (0 until group.length)
                .firstOrNull(group::isTrackSelected)
                ?.let(group::getTrackFormat)
        }

private fun selectedAudioChannels(player: Player): Int {
    val group = player.currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && (0 until it.length).any(it::isTrackSelected) }
        ?: return 0
    return (0 until group.length)
        .firstOrNull(group::isTrackSelected)
        ?.let { group.getTrackFormat(it).channelCount }
        ?.coerceAtLeast(0) ?: 0
}

private fun qualityLabel(format: Format): String {
    val base = "${format.width}×${format.height}"
    return if (format.frameRate > 0f) "$base · ${format.frameRate.roundToInt()} FPS" else base
}

private fun trackLanguageLabel(format: Format, fallback: String): String =
    format.label?.takeIf { it.isNotBlank() }
        ?: format.language?.uppercase()
        ?: fallback

private fun audioLabel(channels: Int): String? = when (channels) {
    1 -> "MONO"
    2 -> "STEREO"
    6 -> "5.1"
    8 -> "7.1"
    in 3..5 -> "${channels}CH"
    7 -> "7CH"
    else -> null
}

private fun extractStreamId(item: MediaItem): String {
    // Xtream ids commonly end in :live:<streamId>, but providers also emit
    // the raw stream id. Prefer the explicit metadata/id suffix when present.
    item.id.substringAfterLast(":live:", item.id.substringAfterLast(":"))
        .takeIf { it.isNotBlank() }
        ?.let { return it }
    return item.id
}

private fun resizeModeForAspect(value: String): Int = when (value.lowercase()) {
    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

private fun aspectLabel(value: String): String = when (value.lowercase()) {
    "fill" -> "Riempi"
    "stretch" -> "Stira"
    else -> "Adatta"
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}


/**
 * RecentlyWatchedStore.record + PlaybackPositionStore.record di iOS in un'unica scrittura:
 * - registra/porta in cima il titolo (Continua a guardare), mantenendo immagine/categoria gia' note;
 * - posizione salvata solo se ha senso (durata > 2 min, oltre i primi 15 s); negli ultimi 45 s, o a fine
 *   riproduzione, il titolo e' segnato come completato (posizione = durata): barra piena e niente "Riprendi";
 * - altrimenti resta la posizione precedente. [reset] la azzera ("Ricomincia da capo").
 */
private suspend fun recordWatchEntry(
    app: GassPlayerApplication,
    item: MediaItem,
    position: Long? = null,
    duration: Long? = null,
    ended: Boolean = false,
    reset: Boolean = false,
) {
    val live = item.kind == MediaKind.LIVE
    val entries = app.watch.flow.first()
    val previous = entries.firstOrNull { it.contentId == item.id }
    // Nuovo episodio di una serie gia' in "Continua a guardare": eredita immagine e categoria dell'episodio
    // precedente (iOS SeriesResumePlayer: imageURLString: request.item.imageURLString), cosi' la card non
    // perde il poster quando si passa al prossimo episodio.
    val seriesSibling = if (previous == null && item.kind == MediaKind.EPISODE && item.seriesId != null)
        entries.firstOrNull { it.kind == MediaKind.EPISODE && it.seriesId == item.seriesId && it.sourceId == item.sourceId }
    else null
    var savedPosition = previous?.positionMs ?: 0L
    var savedDuration = previous?.durationMs ?: 0L
    if (reset) {
        savedPosition = 0L
    } else if (!live && position != null && duration != null && duration > 0L) {
        when {
            ended || (duration > 120_000L && position >= duration - 45_000L) -> {
                savedPosition = duration
                savedDuration = duration
            }
            duration > 120_000L && position > 15_000L -> {
                savedPosition = position
                savedDuration = duration
            }
            else -> if (savedDuration <= 0L) savedDuration = duration
        }
    }
    app.watch.upsert(
        WatchEntry(
            contentId = item.id,
            title = item.title,
            kind = item.kind,
            url = item.streamUrl,
            positionMs = if (live) 0L else savedPosition,
            durationMs = if (live) 0L else savedDuration,
            lastWatchedMs = System.currentTimeMillis(),
            season = item.seasonNumber,
            episode = item.episodeNumber,
            seriesId = item.seriesId,
            sourceId = item.sourceId,
            imageUrl = seriesSibling?.imageUrl,
            subtitle = seriesSibling?.subtitle
        )
    )
}
