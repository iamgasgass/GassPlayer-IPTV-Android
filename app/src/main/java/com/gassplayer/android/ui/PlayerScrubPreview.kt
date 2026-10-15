package com.gassplayer.android.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.Executors
import kotlin.math.roundToInt

// Anteprime della timeline per Film / Serie TV (come PlayerScrubBar.swift su iOS): durante lo scrubbing, col dito
// o con SINISTRA/DESTRA del telecomando, sopra il pallino compare il fotogramma del punto scelto in una card con
// angoli da 16 dp e filo chiaro (come "Continua a guardare") e il tempo in una pillola Liquid Glass.

/**
 * Fotogrammi letti dallo stesso flusso del player (stesso indirizzo e header, User-Agent compreso) con
 * [MediaMetadataRetriever], su un thread dedicato. HLS non e' supportato dal retriever: resta il tempo.
 *
 * Aggiornamento continuo mentre si trascina: un unico lavoratore decodifica, appena e' libero, SEMPRE
 * l'ultimo punto sotto il dito (le posizioni gia' superate non vengono mai lette); nei momenti liberi
 * riempie una griglia di [PREFETCH_COUNT] punti partendo da quelli vicini al dito. Ogni fotogramma pronto
 * aggiorna subito l'anteprima ([version]), che mostra quello esatto o il piu' vicino disponibile.
 * I blocchi sono di 5 s, piu' larghi sui contenuti lunghi (durata / [MAX_BUCKETS]): meno letture, piu'
 * miniature gia' pronte mentre il dito si muove.
 */
internal class PlayerThumbnailLoader(val url: String, private val headers: Map<String, String>) {
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gass-thumbnails").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val cache = object : LinkedHashMap<Long, ImageBitmap>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?): Boolean = size > CACHE_SIZE
    }
    private val failedBuckets = HashSet<Long>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private var retriever: MediaMetadataRetriever? = null
    private var worker: Job? = null
    private var grid: MutableList<Long> = mutableListOf()
    private var openFailures = 0
    private var consecutiveNullFrames = 0
    @Volatile private var unsupported = isHls(url)
    @Volatile var released = false
        private set
    @Volatile private var bucketMs = BUCKET_MS
    /** Ultimo punto sotto il dito (null = nessuno scrubbing). */
    @Volatile private var wantedBucket: Long? = null
    @Volatile private var lastFocus: Long? = null

    /** Cresce a ogni nuovo fotogramma in cache: l'anteprima mostrata si aggiorna. */
    val version = MutableStateFlow(0)

    private fun bucketOf(positionMs: Long): Long = positionMs.coerceAtLeast(0L) / bucketMs

    /** Avvia il lavoratore (una volta per sorgente, solo dal primo trascinamento). */
    fun start(durationMs: Long) {
        if (worker != null || released || durationMs <= 0L) return
        bucketMs = maxOf(BUCKET_MS, durationMs / MAX_BUCKETS)
        val step = maxOf(durationMs / PREFETCH_COUNT, bucketMs)
        grid = generateSequence(step / 2) { it + step }.takeWhile { it < durationMs }.map { it / bucketMs }.toMutableList()
        if (unsupported) return
        worker = scope.launch {
            while (isActive && !released && !unsupported) {
                val target = nextTarget()
                if (target == null) {
                    wakeUp.receive() // niente da fare: si attende il prossimo movimento del dito
                    continue
                }
                decode(target)
            }
        }
    }

    /** Punto sotto il dito, a ogni movimento. */
    fun request(positionMs: Long) {
        val bucket = bucketOf(positionMs)
        wantedBucket = bucket
        lastFocus = bucket
        wakeUp.trySend(Unit)
    }

    /** Fine dello scrubbing: il lavoratore smette di inseguire il dito (la griglia continua). */
    fun finish() {
        wantedBucket = null
    }

    /** Solo sul thread delle anteprime: prima il punto sotto il dito, poi la griglia vicina al dito. */
    private fun nextTarget(): Long? {
        wantedBucket?.let { want ->
            val cached = synchronized(cache) { cache.containsKey(want) }
            if (!cached && want !in failedBuckets) return want
        }
        while (grid.isNotEmpty()) {
            val focus = lastFocus ?: grid.first()
            val next = grid.minByOrNull { kotlin.math.abs(it - focus) } ?: return null
            grid.remove(next)
            val cached = synchronized(cache) { cache.containsKey(next) }
            if (!cached && next !in failedBuckets) return next
        }
        return null
    }

    /**
     * Prima comparsa istantanea: il fotogramma che il player sta mostrando adesso (gia' decodificato, nessuna
     * rete) entra in cache al punto attuale. Da chiamare sul thread principale all'inizio dello scrubbing.
     */
    fun seedCurrentFrame(positionMs: Long) {
        if (released) return
        val bucket = bucketOf(positionMs)
        if (synchronized(cache) { cache[bucket] } != null) return
        PlayerThumbnailStore.captureVideoFrame(FRAME_WIDTH) { bitmap ->
            if (released) return@captureVideoFrame
            synchronized(cache) { if (cache[bucket] == null) cache[bucket] = bitmap.asImageBitmap() }
            version.value = version.value + 1
        }
    }

    /** Fotogramma gia' pronto piu' vicino a [positionMs] (immediato, senza rete). */
    fun nearest(positionMs: Long): ImageBitmap? {
        val bucket = bucketOf(positionMs)
        return synchronized(cache) {
            cache[bucket] ?: cache.keys.minByOrNull { kotlin.math.abs(it - bucket) }?.let { cache[it] }
        }
    }

    /** Solo sul thread delle anteprime. Un errore di rete non spegne subito le anteprime: si riprova. */
    private fun openRetriever(): MediaMetadataRetriever? {
        retriever?.let { return it }
        return runCatching {
            MediaMetadataRetriever().apply { setDataSource(url, headers) }
        }.onFailure {
            openFailures++
            if (openFailures >= MAX_OPEN_FAILURES) unsupported = true
        }.getOrNull()?.also { retriever = it; openFailures = 0 }
    }

    /** Solo sul thread delle anteprime. */
    private fun decode(bucket: Long): ImageBitmap? {
        if (bucket in failedBuckets) return null
        val r = openRetriever() ?: return null
        val timeUs = (bucket * bucketMs + bucketMs / 2) * 1000L
        // Fotogramma chiave piu' vicino; se il contenitore non lo trova, quello precedente o successivo.
        val frame: Bitmap? = grab(r, timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: grab(r, timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
            ?: grab(r, timeUs, MediaMetadataRetriever.OPTION_NEXT_SYNC)
        if (frame == null) {
            failedBuckets += bucket
            // Piu' letture vuote di fila: connessione caduta o retriever bloccato, si riapre il flusso.
            if (++consecutiveNullFrames >= 2) {
                runCatching { retriever?.release() }
                retriever = null
                consecutiveNullFrames = 0
                failedBuckets.clear()
            }
            return null
        }
        consecutiveNullFrames = 0
        val image = frame.asImageBitmap()
        synchronized(cache) { cache[bucket] = image }
        version.value = version.value + 1
        return image
    }

    private fun grab(r: MediaMetadataRetriever, timeUs: Long, option: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(timeUs, option, FRAME_WIDTH, FRAME_HEIGHT)
        } else {
            r.getFrameAtTime(timeUs, option)?.let { full ->
                val scaled = Bitmap.createScaledBitmap(full, FRAME_WIDTH, (FRAME_WIDTH * full.height / full.width.coerceAtLeast(1)), true)
                if (scaled !== full) full.recycle()
                scaled
            }
        }
    }.getOrNull()

    fun release() {
        if (released) return
        released = true
        worker?.cancel()
        wakeUp.close()
        // Chiusura sullo stesso thread delle letture: mai un release() durante getFrameAtTime().
        dispatcher.executor.execute {
            runCatching { retriever?.release() }
            retriever = null
            synchronized(cache) { cache.clear() }
        }
        scope.cancel()
        dispatcher.close()
    }

    private companion object {
        const val BUCKET_MS = 5_000L
        const val MAX_BUCKETS = 240L
        const val FRAME_WIDTH = 320
        const val FRAME_HEIGHT = 180
        const val CACHE_SIZE = 100
        const val PREFETCH_COUNT = 40L
        const val MAX_OPEN_FAILURES = 3
        fun isHls(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('|').lowercase()
            return path.endsWith(".m3u8") || path.endsWith(".m3u")
        }
    }
}

/**
 * Un loader per contenuto, tenuto fuori dai controlli del player: quando i controlli si nascondono e
 * ricompaiono, flusso aperto e fotogrammi gia' pronti restano (prima si perdeva tutto e la prima anteprima
 * successiva spesso non faceva in tempo a comparire). Si chiude cambiando contenuto o uscendo dal player.
 */
internal object PlayerThumbnailStore {
    private var current: PlayerThumbnailLoader? = null
    /** Superficie video del player (registrata da IosPlayerScreen) per catturare il fotogramma attuale. */
    @Volatile var videoSurface: java.lang.ref.WeakReference<android.view.View>? = null

    /** Fotogramma attualmente a schermo, ridotto a [width] px di larghezza. Thread principale. */
    fun captureVideoFrame(width: Int, onFailure: () -> Unit = {}, onFrame: (Bitmap) -> Unit) {
        val view = videoSurface?.get() ?: return onFailure()
        if (view.width <= 0 || view.height <= 0) return onFailure()
        val height = (width * view.height / view.width).coerceAtLeast(1)
        when (view) {
            is android.view.TextureView -> view.getBitmap(width, height)?.let(onFrame) ?: onFailure()
            is android.view.SurfaceView -> {
                if (Build.VERSION.SDK_INT < 24 || view is android.opengl.GLSurfaceView) return onFailure()
                val surface = view.holder?.surface?.takeIf { it.isValid } ?: return onFailure()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                runCatching {
                    android.view.PixelCopy.request(surface, bitmap, { result ->
                        if (result == android.view.PixelCopy.SUCCESS) onFrame(bitmap) else onFailure()
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                }.onFailure { onFailure() }
            }
            else -> onFailure()
        }
    }

    @Synchronized
    fun obtain(url: String, headers: Map<String, String>): PlayerThumbnailLoader {
        current?.takeIf { it.url == url && !it.released }?.let { return it }
        current?.release()
        return PlayerThumbnailLoader(url, headers).also { current = it }
    }

    @Synchronized
    fun releaseAll() {
        current?.release()
        current = null
    }
}

/**
 * Anteprima per [positionMs] (null senza scrubbing), in tempo reale.
 *
 * Durante lo scrubbing il player stesso si sposta sul punto scelto ([com.gassplayer.android.media.PlaybackController.scrubTo]):
 * qui si cattura ~10 volte al secondo il fotogramma che il video sta mostrando, quindi la miniatura segue il
 * dito / il telecomando istante per istante, senza seconda connessione al server. Solo se la superficie
 * video non si puo' catturare (video 360°, dispositivi che lo negano) si usa la lettura separata del flusso.
 */
@Composable
internal fun rememberPlayerThumbnail(
    source: Pair<String, Map<String, String>>?,
    positionMs: Long?,
    durationMs: Long,
    playbackPositionMs: () -> Long,
): ImageBitmap? {
    val scrubbing = positionMs != null
    var liveFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var liveUnavailable by remember(source?.first) { mutableStateOf(false) }

    LaunchedEffect(scrubbing, liveUnavailable) {
        if (!scrubbing || liveUnavailable) {
            if (!scrubbing) liveFrame = null
            return@LaunchedEffect
        }
        var failures = 0
        while (true) {
            PlayerThumbnailStore.captureVideoFrame(
                CAPTURE_WIDTH,
                onFailure = { if (++failures >= 4) liveUnavailable = true },
            ) { bitmap ->
                failures = 0
                liveFrame = bitmap.asImageBitmap()
            }
            delay(CAPTURE_INTERVAL_MS)
        }
    }

    // Ripiego: lettura separata del flusso (solo se la cattura dal video non e' possibile).
    val loader = remember(source?.first, liveUnavailable) {
        if (liveUnavailable) source?.let { PlayerThumbnailStore.obtain(it.first, it.second) } else null
    }
    val fallbackVersion = remember { MutableStateFlow(0) }
    val version by (loader?.version ?: fallbackVersion).collectAsState()
    LaunchedEffect(loader, scrubbing) {
        val current = loader ?: return@LaunchedEffect
        if (scrubbing) current.start(durationMs) else current.finish()
    }
    LaunchedEffect(loader, positionMs) {
        val target = positionMs ?: return@LaunchedEffect
        loader?.start(durationMs)
        loader?.request(target)
    }
    val fallback = remember(loader, version, positionMs) {
        if (positionMs == null) null else loader?.nearest(positionMs)
    }
    return if (!scrubbing) null else if (liveUnavailable) fallback else liveFrame
}

/** Fotogramma a schermo catturato all'inizio del trascinamento (prima anteprima immediata). */
@Composable
internal fun rememberScrubSeedFrame(scrubbing: Boolean): ImageBitmap? {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(scrubbing) {
        frame = null
        if (scrubbing) PlayerThumbnailStore.captureVideoFrame(CAPTURE_WIDTH) { frame = it.asImageBitmap() }
    }
    return frame
}

private const val CAPTURE_WIDTH = 320
private const val CAPTURE_INTERVAL_MS = 90L

/** Card dell'anteprima (fotogramma + tempo). */
@Composable
internal fun PlayerScrubPreview(
    positionMs: Long,
    thumbnail: ImageBitmap?,
    isTelevision: Boolean,
    modifier: Modifier = Modifier,
    previewPlayer: ExoPlayer? = null,
) {
    val width = if (isTelevision) 240.dp else 176.dp
    val height = if (isTelevision) 135.dp else 99.dp
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (thumbnail != null || previewPlayer != null) {
            Box(
                Modifier
                    .size(width, height)
                    .shadow(10.dp, shape)
                    .clip(shape)
                    .background(Color.Black)
            ) {
                // Sotto: il fotogramma a schermo all'inizio del trascinamento (comparsa immediata).
                thumbnail?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }
                // Sopra: il player nascosto dell'anteprima, che segue il dito / il telecomando in tempo reale.
                previewPlayer?.let { player -> PreviewPlayerSurface(player, Modifier.matchParentSize()) }
                Box(Modifier.matchParentSize().border(.7.dp, Color.White.copy(alpha = .18f), shape))
            }
        }
        Box(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .35f))) {
            LiquidGlassPill {
                Text(
                    iosScrubTime(positionMs),
                    color = Color.White,
                    fontSize = if (isTelevision) 16.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Posiziona l'anteprima SOPRA la timeline, centrata sul punto scelto e tenuta dentro i bordi, senza cambiare
 * l'altezza dei controlli (occupa zero spazio nel layout).
 */
internal fun Modifier.scrubPreviewPlacement(fraction: Float, containerWidthPx: Int, gapPx: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxWidth.coerceAtLeast(1), maxHeight = Constraints.Infinity))
    layout(0, 0) {
        val center = (containerWidthPx * fraction.coerceIn(0f, 1f)).roundToInt()
        val x = (center - placeable.width / 2).coerceIn(0, (containerWidthPx - placeable.width).coerceAtLeast(0))
        placeable.place(x, -placeable.height - gapPx)
    }
}

/**
 * Superficie del player dell'anteprima dentro la card (TextureView: rispetta gli angoli arrotondati).
 * Resta invisibile finche' non ha disegnato un fotogramma su questa superficie, cosi' non compare mai
 * un'immagine vecchia o vuota: sotto si vede il fotogramma iniziale.
 */
@Composable
private fun PreviewPlayerSurface(player: ExoPlayer, modifier: Modifier) {
    var rendered by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { rendered = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    AndroidView(
        factory = { context ->
            android.view.TextureView(context).also { view ->
                rendered = false
                player.setVideoTextureView(view)
            }
        },
        onRelease = { view -> player.clearVideoTextureView(view) },
        modifier = modifier.graphicsLayer { alpha = if (rendered) 1f else 0f },
    )
}

private fun iosScrubTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
