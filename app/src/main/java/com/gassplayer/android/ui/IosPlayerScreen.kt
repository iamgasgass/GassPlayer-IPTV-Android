package com.gassplayer.android.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
    val position: Long = 0L,
    val duration: Long = 0L,
    val buffered: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val state: Int = Player.STATE_IDLE,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 0f,
    val audioChannels: Int = 0
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

    var showControls by remember(item.id) { mutableStateOf(true) }
    var locked by remember(item.id) { mutableStateOf(false) }
    var toast by remember(item.id) { mutableStateOf<String?>(null) }
    var dialog by remember(item.id) { mutableStateOf<IosPlayerDialog?>(null) }
    var currentSpeed by remember(item.id) { mutableFloatStateOf(settings.preferredPlaybackSpeed) }
    var aspect by remember(item.id) { mutableStateOf(settings.aspectRatio) }
    var resumePosition by remember(item.id) { mutableStateOf<Long?>(null) }
    var explicitResume by remember(item.id) { mutableStateOf(false) }
    var snapshot by remember(item.id) { mutableStateOf(PlayerSnapshot()) }
    var currentLiveProgram by remember(item.id) { mutableStateOf<EpgProgram?>(null) }
    var localSettings by remember(settings) { mutableStateOf(settings) }

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

    LaunchedEffect(item.id) {
        while (true) {
            val p = app.playback.player
            val video = selectedVideoFormat(p)
            val audioChannels = selectedAudioChannels(p)
            snapshot = PlayerSnapshot(
                position = p.currentPosition.coerceAtLeast(0L),
                duration = p.duration.coerceAtLeast(0L),
                buffered = p.bufferedPosition.coerceAtLeast(0L),
                isPlaying = p.isPlaying,
                isBuffering = p.isLoading,
                state = p.playbackState,
                videoWidth = video?.width ?: 0,
                videoHeight = video?.height ?: 0,
                frameRate = video?.frameRate ?: 0f,
                audioChannels = audioChannels
            )
            delay(500L)
        }
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
            app.playback.stop()
        }
    }

    val applySettings: (AppSettings) -> Unit = { updated ->
        localSettings = updated
        aspect = updated.aspectRatio
        scope.launch { app.prefs.saveSettings(updated) }
        app.playback.setSettings(updated)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    useController = false
                    player = app.playback.player
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = resizeModeForAspect(aspect)
                }
            },
            update = {
                it.player = app.playback.player
                it.keepScreenOn = true
                it.resizeMode = resizeModeForAspect(aspect)
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
        )

        AnimatedVisibility(
            visible = snapshot.isBuffering,
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
                    text = "Caricamento…",
                    color = Color.White.copy(.85f),
                    fontSize = 13.sp
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
                    settings = localSettings,
                    snapshot = snapshot,
                    currentLiveProgram = currentLiveProgram,
                    currentSpeed = currentSpeed,
                    previousItem = previousItem,
                    nextItem = nextItem,
                    onBack = onBack,
                    onPip = onPip,
                    supportsPip = supportsPip,
                    onExternal = { app.playback.handoffExternal(context, item.streamUrl) },
                    onShowDialog = { dialog = it },
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

        app.playback.error.collectAsStateWithLifecycle().value?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(.82f)
                    .padding(bottom = 96.dp),
                shape = RoundedCornerShape(22.dp),
                color = Color.Black.copy(.82f),
                border = BorderStroke(1.dp, Color.White.copy(.13f))
            ) {
                Row(
                    Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Riproduzione non riuscita", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(error, color = Color.White.copy(.72f), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({ app.playback.retry() }) { Text("Riprova") }
                    TextButton({ app.playback.handoffExternal(context, item.streamUrl) }) { Text("Esterno") }
                }
            }
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

        if (!explicitResume && nextItem != null && localSettings.autoplayNextEpisode &&
            snapshot.duration > 0L &&
            snapshot.duration - snapshot.position <= 60_000L
        ) {
            Surface(
                onClick = {
                    nextItem?.let(onNavigateToItem)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 104.dp),
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
                app.playback.setSpeed(it)
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
                .mapNotNull { entry -> catalog?.live?.firstOrNull { it.id == entry.contentId } ?: entry.toMediaItemFallback() }
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
    settings: AppSettings,
    snapshot: PlayerSnapshot,
    currentLiveProgram: EpgProgram?,
    currentSpeed: Float,
    previousItem: MediaItem?,
    nextItem: MediaItem?,
    onBack: () -> Unit,
    onPip: () -> Unit,
    supportsPip: Boolean,
    onExternal: () -> Unit,
    onShowDialog: (IosPlayerDialog) -> Unit,
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
                item.sourceId.takeIf { it.isNotBlank() }?.let { add(it.take(18)) }
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
                    value = snapshot.position.coerceIn(0L, snapshot.duration).toFloat(),
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
                        "${formatTime(snapshot.position)} / ${formatTime(snapshot.duration)}"
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
                    Button(onClick = onResume) { Text("Riprendi da…") }
                    OutlinedButton(onClick = onRestart) { Text("Ricomincia da capo") }
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
            Text("Il flusso non espone tracce video selezionabili.", color = Color.White.copy(.72f))
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
        Text("Audio", color = Color.White, fontWeight = FontWeight.Bold)
        if (audioChoices.isEmpty()) {
            Text("Nessuna traccia audio alternativa.", color = Color.White.copy(.62f), fontSize = 13.sp)
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
        Text("Sottotitoli", color = Color.White, fontWeight = FontWeight.Bold)
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
            Text("Nessun canale live recente.", color = Color.White.copy(.68f))
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
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
            SettingSwitch("Decompressione asincrona", settings.asyncDecode) {
                // Preserviamo la preferenza iOS anche se Media3 gestisce internamente
                // il pipeline decoding: non esiste un toggle FFmpeg equivalente 1:1.
                onChange(settings.copy(asyncDecode = it))
            }
            SettingSwitch("Decodifica hardware preferita", settings.hardwareDecode) {
                // Media3 seleziona il decoder disponibile; questa preferenza viene
                // persistita senza fingere un forcing software non supportato dall'API.
                onChange(settings.copy(hardwareDecode = it, softwareDecode = !it))
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
        }
    }
}

@Composable
private fun SettingStepper(title: String, value: Int, options: List<Int>, onChange: (Int) -> Unit) {
    Surface(
        color = Color.White.copy(.06f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(.1f))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton({ onChange(options.filter { it < value }.lastOrNull() ?: value) }) { Text("−") }
                Text("$value s", color = Color.White, modifier = Modifier.weight(1f))
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
        Text(title, color = Color.White, modifier = Modifier.weight(1f))
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
        color = Color.White.copy(.055f),
        border = BorderStroke(1.dp, Color.White.copy(.075f))
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Color.White.copy(.88f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Medium)
                subtitle?.let {
                    Text(it, color = Color.White.copy(.55f), fontSize = 12.sp)
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(.42f))
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
            color = Color(0xFF15171D),
            tonalElevation = 12.dp,
            border = BorderStroke(1.dp, Color.White.copy(.12f))
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null, tint = Color.White)
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

private fun CatalogState.nextFor(item: MediaItem): MediaItem? {
    val sequence = when (item.kind) {
        MediaKind.EPISODE -> episodes
            .filter { it.seriesId == item.seriesId && it.sourceId == item.sourceId }
            .sortedWith(compareBy<MediaItem> { it.seasonNumber ?: Int.MAX_VALUE }.thenBy { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.title })
        MediaKind.LIVE -> live
            .filter { it.sourceId == item.sourceId }
            .sortedWith(compareBy<MediaItem> { it.number ?: Int.MAX_VALUE }.thenBy { it.title })
        else -> emptyList()
    }
    val index = sequence.indexOfFirst { it.id == item.id }
    return sequence.getOrNull(index + 1)
}

private fun CatalogState.previousFor(item: MediaItem): MediaItem? {
    val sequence = when (item.kind) {
        MediaKind.EPISODE -> episodes
            .filter { it.seriesId == item.seriesId && it.sourceId == item.sourceId }
            .sortedWith(compareBy<MediaItem> { it.seasonNumber ?: Int.MAX_VALUE }.thenBy { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.title })
        MediaKind.LIVE -> live
            .filter { it.sourceId == item.sourceId }
            .sortedWith(compareBy<MediaItem> { it.number ?: Int.MAX_VALUE }.thenBy { it.title })
        else -> emptyList()
    }
    val index = sequence.indexOfFirst { it.id == item.id }
    return if (index > 0) sequence[index - 1] else null
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
