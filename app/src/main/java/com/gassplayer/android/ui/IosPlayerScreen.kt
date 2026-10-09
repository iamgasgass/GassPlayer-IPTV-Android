package com.gassplayer.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.content.res.Configuration
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    val position: Long = 0L
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
    val scope = rememberCoroutineScope()
    val supportsPip = android.os.Build.VERSION.SDK_INT >= 26 &&
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    val watch by app.watch.flow.collectAsStateWithLifecycle(emptyList())
    val sources by app.sources.sources.collectAsStateWithLifecycle(emptyList())
    val playbackError by app.playback.error.collectAsStateWithLifecycle()
    val recoveryStatus by app.playback.recoveryStatus.collectAsStateWithLifecycle()
    val playerEpoch by app.playback.playerEpoch.collectAsStateWithLifecycle()

    var showControls by remember(item.id) { mutableStateOf(true) }
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
    var showBrightnessHud by remember(item.id) { mutableStateOf(false) }
    var showVolumeHud by remember(item.id) { mutableStateOf(false) }
    val brightnessLevel = remember(item.id) { mutableFloatStateOf(0.5f) }
    val volumeLevel = remember(item.id) { mutableFloatStateOf(0.5f) }
    // Keep the host activity brightness intact after leaving the player.
    val originalWindowBrightness = remember { context.findActivity()?.window?.attributes?.screenBrightness ?: -1f }
    var hudTimerKey by remember(item.id) { mutableIntStateOf(0) }
    val playerFocusRequester = remember(item.id) { FocusRequester() }

    LaunchedEffect(item.id) {
        runCatching { playerFocusRequester.requestFocus() }
    }

    val source = remember(sources, item.sourceId) {
        sources.firstOrNull { it.id == item.sourceId }
    }
    val nextItem = remember(catalog, item.id) {
        catalog?.nextFor(item)
    }
    val previousItem = remember(catalog, item.id) {
        catalog?.previousFor(item)
    }

    LaunchedEffect(item.id) {
        val savedEntries = app.watch.flow.first()
        val saved = savedEntries.firstOrNull { it.contentId == item.id }
        val explicit = item.metadataTag
            ?.removePrefix("resume:")
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
        val savedResume = if (
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
    }

    LaunchedEffect(item.id, showControls, nextItem?.id, item.kind, localSettings.autoplayNextEpisode) {
        while (true) {
            val p = app.playback.player
            val video = selectedVideoFormat(p)
            val nextSnapshot = PlayerSnapshot(
                duration = p.duration.coerceAtLeast(0L),
                isPlaying = p.isPlaying,
                isBuffering = p.isLoading,
                state = p.playbackState,
                videoWidth = video?.width ?: 0,
                videoHeight = video?.height ?: 0,
                frameRate = video?.frameRate ?: 0f,
                audioChannels = selectedAudioChannels(p)
            )
            // Metadata changes infrequently; don't invalidate the whole player every 500 ms.
            if (snapshot != nextSnapshot) snapshot = nextSnapshot

            // The timeline is only sampled at interactive rate while visible. When hidden,
            // keep a slower clock only if we need the next-episode affordance/autoplay timing.
            val needsHiddenProgress = item.kind == MediaKind.EPISODE &&
                nextItem != null && localSettings.autoplayNextEpisode
            if (showControls || needsHiddenProgress) {
                val nextProgress = PlaybackProgress(
                    position = p.currentPosition.coerceAtLeast(0L)
                )
                if (progressState.value != nextProgress) progressState.value = nextProgress
            }
            delay(if (showControls) 250L else 1_200L)
        }
    }

    LaunchedEffect(hudTimerKey) {
        if (hudTimerKey == 0) return@LaunchedEffect
        delay(900L)
        showBrightnessHud = false
        showVolumeHud = false
    }

    LaunchedEffect(showControls, dialog, locked, snapshot.isBuffering) {
        if (!showControls || dialog != null || locked) return@LaunchedEffect
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
            item.kind == MediaKind.EPISODE &&
            localSettings.autoplayNextEpisode &&
            snapshot.state == Player.STATE_ENDED &&
            nextItem != null
        ) {
            onNavigateToItem(nextItem)
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            val p = app.playback.player
            val position = p.currentPosition.coerceAtLeast(0L)
            val duration = p.duration.coerceAtLeast(0L)
            scope.launch {
                app.watch.upsert(
                    WatchEntry(
                        item.id,
                        item.title,
                        item.kind,
                        item.streamUrl,
                        position,
                        duration,
                        System.currentTimeMillis(),
                        item.seasonNumber,
                        item.episodeNumber
                    )
                )
            }
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
        localSettings = updated
        aspect = updated.aspectRatio
        scope.launch { app.prefs.saveSettings(updated) }
        app.playback.setSettings(updated)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || locked || dialog != null) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause, Key.Spacebar -> {
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
                    Key.DirectionLeft -> {
                        if (showControls) false else {
                            showControls = true
                            if (item.kind != MediaKind.LIVE) app.playback.skipBack(10_000L)
                            true
                        }
                    }
                    Key.DirectionRight -> {
                        if (showControls) false else {
                            showControls = true
                            if (item.kind != MediaKind.LIVE) app.playback.skipForward(10_000L)
                            true
                        }
                    }
                    Key.DirectionUp -> {
                        if (showControls) false else if (item.kind == MediaKind.LIVE && nextItem != null) {
                            onNavigateToItem(nextItem)
                            true
                        } else {
                            showControls = true
                            true
                        }
                    }
                    Key.DirectionDown -> {
                        if (showControls) false else if (item.kind == MediaKind.LIVE && previousItem != null) {
                            onNavigateToItem(previousItem)
                            true
                        } else {
                            showControls = true
                            true
                        }
                    }
                    else -> false
                }
            }
    ) {
        // Recreated when "Panorama 360°" changes, because the surface type is fixed at inflation.
        key(localSettings.panorama360) {
        AndroidView(
            factory = {
                (if (localSettings.panorama360) android.view.LayoutInflater.from(context).inflate(com.gassplayer.android.R.layout.gass_player_view_spherical, null) as PlayerView else PlayerView(context)).apply {
                    useController = false
                    player = app.playback.player
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = resizeModeForAspect(aspect)
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
                                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                baseValue = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
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
                                runCatching {
                                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt().coerceIn(0, max), 0)
                                }
                            }
                        }
                    )
                }
        )
        }

        AnimatedVisibility(
            visible = (snapshot.isBuffering || recoveryStatus != null) && playbackError == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    text = recoveryStatus ?: "Caricamento…",
                    color = Color.White.copy(.85f),
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
                    Text("Impossibile riprodurre il flusso", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        playbackError.orEmpty(),
                        color = Color.White.copy(.72f),
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
                    .padding(24.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(.58f),
                border = BorderStroke(1.dp, Color.White.copy(.14f))
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LockOpen, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Sblocca", color = Color.White, fontWeight = FontWeight.SemiBold)
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
                    color = Color.White,
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
                    resumePosition = null
                    explicitResume = false
                    app.playback.seekTo(0L)
                    app.playback.playNow()
                    showControls = true
                }
            )
        }

        if (!explicitResume && nextItem != null && localSettings.autoplayNextEpisode && snapshot.duration > 0L) {
            NextEpisodePrompt(
                duration = snapshot.duration,
                progressState = progressState,
                onClick = { nextItem?.let(onNavigateToItem) }
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
    onRequestDialog: (IosPlayerDialog) -> Unit
) {
    val progress = progressState.value
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

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 26.dp, vertical = 88.dp)
        ) {
            if (item.kind == MediaKind.LIVE && currentLiveProgram != null) {
                item.logoUrl?.takeIf { it.isNotBlank() }?.let { logo ->
                    AsyncImage(
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

            Text(
                smallLine.orEmpty(),
                color = Color.White.copy(.9f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                if (item.kind == MediaKind.LIVE && currentLiveProgram != null) currentLiveProgram.title else item.title,
                color = Color.White,
                fontSize = 29.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (item.kind == MediaKind.LIVE) {
                currentLiveProgram?.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = Color.White.copy(.9f),
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

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(.88f))
                    )
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            if (snapshot.duration > 0L) {
                Slider(
                    value = progress.position.coerceIn(0L, snapshot.duration).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..snapshot.duration.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerGlassButton(Icons.Default.SkipPrevious, "Precedente", enabled = previousItem != null, onClick = onPrevious)
                PlayerGlassButton(Icons.Default.Replay10, "-15s", onClick = onSkipBack)
                PlayerGlassButton(
                    if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (snapshot.isPlaying) "Pausa" else "Riproduci",
                    size = 58.dp,
                    onClick = onPlayPause
                )
                PlayerGlassButton(Icons.Default.Forward10, "+15s", onClick = onSkipForward)
                PlayerGlassButton(Icons.Default.SkipNext, "Successivo", enabled = nextItem != null, onClick = onNext)
                Spacer(Modifier.weight(1f))
                Text(
                    if (snapshot.duration > 0L) {
                        "${formatTime(progress.position)} / ${formatTime(snapshot.duration)}"
                    } else {
                        "LIVE"
                    },
                    color = Color.White.copy(.88f),
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun NextEpisodePrompt(
    duration: Long,
    progressState: State<PlaybackProgress>,
    onClick: () -> Unit
) {
    val progress = progressState.value
    if (duration - progress.position > 60_000L) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Surface(
            onClick = onClick,
            modifier = Modifier.padding(end = 24.dp, bottom = 104.dp),
            color = Color.Black.copy(.75f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color.White.copy(.14f))
        ) {
            Text(
                "Prossimo episodio  ›",
                color = Color.White,
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
            Icon(icon, contentDescription = label, tint = Color.White)
            androidx.compose.foundation.layout.Box(
                Modifier.height(120.dp).width(5.dp).background(Color.White.copy(.22f), RoundedCornerShape(50))
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f))
                        .align(Alignment.BottomCenter).background(Color.White, RoundedCornerShape(50))
                )
            }
            Text("${(value.coerceIn(0f, 1f) * 100).roundToInt()}%", color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BadgeChip(text: String) {
    Surface(
        color = Color.White.copy(.28f),
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
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(.62f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 300.dp, max = 520.dp),
            color = Color(0xFF17191F).copy(.96f),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color.White.copy(.14f))
        ) {
            Column(
                Modifier.padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Riprendi la visione?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Ti eri fermato a ${formatTime(position)}",
                    color = Color.White.copy(.74f),
                    fontSize = 14.sp
                )
                Text(title, color = Color.White.copy(.92f), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlayerGlassButton(onClick = onResume, darkSurface = true) { Text("Riprendi da…") }
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
    PlayerDialogFrame("Impostazioni avanzate", onDismiss) {
        // PlayerDialogFrame owns the only verticalScroll modifier. A second scroll container
        // here nested the same content in two unbounded vertical scrollers and crashed the
        // player dialog on opening on some devices.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingStepper("Buffer minimo", settings.minBufferSec, listOf(1, 3, 5, 10, 15, 20, 30, 60)) {
                onChange(settings.copy(minBufferSec = it.coerceAtMost(settings.maxBufferSec)))
            }
            SettingStepper("Buffer massimo", settings.maxBufferSec, listOf(15, 30, 60, 90, 120)) {
                onChange(settings.copy(maxBufferSec = it.coerceAtLeast(settings.minBufferSec)))
            }
            SettingStepper("Buffer di partenza", settings.playerStartBufferSec, listOf(1, 3, 5, 8, 15, 30)) {
                onChange(settings.copy(playerStartBufferSec = it.coerceIn(1, settings.maxBufferSec)))
            }
            SettingSwitch("Seek accurato", settings.accurateSeek) {
                onChange(settings.copy(accurateSeek = it))
            }
            SettingSwitch("Adaptive bitrate", settings.adaptiveBitrate) {
                onChange(settings.copy(adaptiveBitrate = it))
            }
            SettingSwitch("Cache HTTP", settings.httpCache) {
                onChange(settings.copy(httpCache = it))
            }
            SettingSwitch("Solo audio", settings.audioOnly) {
                onChange(settings.copy(audioOnly = it))
            }
            SettingSwitch("Decodifica hardware", settings.hardwareDecode) {
                onChange(settings.copy(hardwareDecode = it, softwareDecode = !it))
            }
            SettingSwitch("Decodifica software", settings.softwareDecode) {
                onChange(settings.copy(softwareDecode = it, hardwareDecode = !it))
            }
            SettingSwitch("Decodifica asincrona", settings.asyncDecode) {
                onChange(settings.copy(asyncDecode = it))
            }
            SettingSwitch("Mantieni sottotitoli immagine", settings.preserveImageSubtitles) {
                onChange(settings.copy(preserveImageSubtitles = it))
            }
            SettingSwitch("Deinterlacciamento", settings.deinterlace) {
                onChange(settings.copy(deinterlace = it))
            }
            SettingSwitch("Panorama 360°", settings.panorama360) {
                onChange(settings.copy(panorama360 = it))
            }
            SettingSwitch("Rotazione automatica 360°", settings.autoRotate360) {
                onChange(settings.copy(autoRotate360 = it))
            }
            SettingSwitch("Prossimo episodio automatico", settings.autoplayNextEpisode) {
                onChange(settings.copy(autoplayNextEpisode = it))
            }
            SettingSwitch("Riprendi la visione", settings.resumePlayback) {
                onChange(settings.copy(resumePlayback = it))
            }
            SettingSwitch("Ripetizione", settings.loopPlayback) {
                onChange(settings.copy(loopPlayback = it))
            }
            SettingDelay("Ritardo A/V (ms)", settings.videoDelayMs, -2_000..2_000, step = 50) {
                onChange(settings.copy(videoDelayMs = it.coerceIn(-2_000, 2_000)))
            }
            SettingOption(
                title = "Risoluzione massima",
                current = when (settings.ffmpegLowResolution) {
                    "half" -> "Ridotta (max 720p)"
                    "quarter" -> "Bassa (max 480p)"
                    else -> "Originale"
                },
                options = listOf("Originale", "Ridotta (max 720p)", "Bassa (max 480p)"),
                onSelected = { selected ->
                    onChange(settings.copy(ffmpegLowResolution = when (selected) {
                        "Ridotta (max 720p)" -> "half"
                        "Bassa (max 480p)" -> "quarter"
                        else -> "full"
                    }))
                }
            )
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
                onValueChangeFinished = { onChange(draft.roundToInt().coerceIn(range.first, range.last)) }
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
            modifier = Modifier.fillMaxWidth(),
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
                    trailingIcon = { if (option == current) Icon(Icons.Default.Check, null) }
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
        Switch(checked = checked, onCheckedChange = onChange)
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
        modifier = Modifier.fillMaxWidth(),
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
    return if (forward) sequence.getOrNull(index + 1) else if (index > 0) sequence[index - 1] else null
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
