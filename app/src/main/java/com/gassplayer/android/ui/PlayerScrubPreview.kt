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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import kotlin.math.roundToInt

// Anteprime della timeline per Film / Serie TV (come PlayerScrubBar.swift su iOS): durante lo scrubbing, col dito
// o con SINISTRA/DESTRA del telecomando, sopra il pallino compare il fotogramma del punto scelto in una card con
// angoli da 16 dp e filo chiaro (come "Continua a guardare") e il tempo in una pillola Liquid Glass.

/**
 * Fotogrammi letti dallo stesso flusso del player (stesso indirizzo e header, User-Agent compreso) con
 * [MediaMetadataRetriever], su un thread dedicato. In cache a blocchi di 5 s. HLS non e' supportato dal
 * retriever: nessuna anteprima, resta il tempo.
 *
 * Reattivita': al primo trascinamento si apre il retriever e in background si prepara una griglia di
 * [PREFETCH_COUNT] fotogrammi distribuiti sulla durata. Durante lo scrubbing compare subito il fotogramma in
 * cache piu' vicino e, a ogni fotogramma pronto ([version]), l'anteprima si aggiorna da sola; le richieste
 * superate dal dito vengono saltate (resta solo l'ultima). Le richieste dell'utente passano sempre davanti
 * alla preparazione in background.
 */
internal class PlayerThumbnailLoader(val url: String, private val headers: Map<String, String>) {
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gass-thumbnails").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val cache = object : LinkedHashMap<Long, ImageBitmap>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?): Boolean = size > CACHE_SIZE
    }
    private val failedBuckets = HashSet<Long>()
    private var retriever: MediaMetadataRetriever? = null
    private var warmJob: Job? = null
    private var openFailures = 0
    private var consecutiveNullFrames = 0
    @Volatile private var unsupported = isHls(url)
    @Volatile var released = false
        private set
    /** Punto (blocco da 5 s) sotto il dito: la griglia in background parte da li' e si allarga. */
    @Volatile private var focusBucket: Long? = null

    /** Cresce a ogni nuovo fotogramma in cache: l'anteprima mostrata si aggiorna. */
    val version = MutableStateFlow(0)

    /** Apre il flusso e prepara la griglia di anteprime (una volta per sorgente). */
    fun warmUp(durationMs: Long) {
        if (unsupported || released || durationMs <= 0L || warmJob != null) return
        warmJob = scope.launch {
            val step = maxOf(durationMs / PREFETCH_COUNT, BUCKET_MS)
            val grid = generateSequence(step / 2) { it + step }
                .takeWhile { it < durationMs }
                .map { it / BUCKET_MS }
                .toMutableList()
            while (isActive && !released && !unsupported && grid.isNotEmpty()) {
                // Sempre il punto della griglia piu' vicino al dito: le miniature utili arrivano per prime.
                val focus = focusBucket ?: grid.first()
                val bucket = grid.minByOrNull { kotlin.math.abs(it - focus) } ?: break
                grid.remove(bucket)
                if (synchronized(cache) { cache[bucket] } == null) decode(bucket)
                yield() // la richiesta esatta dell'utente, se in coda, passa prima del prossimo punto
            }
        }
    }

    /** Punto sotto il dito (chiamato a ogni movimento, anche prima che parta la richiesta esatta). */
    fun focus(positionMs: Long) {
        focusBucket = positionMs.coerceAtLeast(0L) / BUCKET_MS
    }

    /**
     * Prima comparsa istantanea: il fotogramma che il player sta mostrando adesso (gia' decodificato, nessuna
     * rete) entra in cache al punto attuale. Da chiamare sul thread principale all'inizio dello scrubbing.
     */
    fun seedCurrentFrame(positionMs: Long) {
        if (released) return
        val bucket = positionMs.coerceAtLeast(0L) / BUCKET_MS
        if (synchronized(cache) { cache[bucket] } != null) return
        PlayerThumbnailStore.captureVideoFrame(FRAME_WIDTH) { bitmap ->
            if (released) return@captureVideoFrame
            synchronized(cache) { if (cache[bucket] == null) cache[bucket] = bitmap.asImageBitmap() }
            version.value = version.value + 1
        }
    }

    /** Fotogramma gia' pronto piu' vicino a [positionMs] (immediato, senza rete). */
    fun nearest(positionMs: Long): ImageBitmap? {
        val bucket = positionMs.coerceAtLeast(0L) / BUCKET_MS
        return synchronized(cache) {
            cache[bucket] ?: cache.keys.minByOrNull { kotlin.math.abs(it - bucket) }?.let { cache[it] }
        }
    }

    /**
     * Fotogramma esatto per [positionMs]. Se la coroutine viene annullata mentre e' ancora in coda (il dito si
     * e' gia' spostato) la lettura non parte nemmeno: si decodifica solo l'ultimo punto richiesto.
     */
    suspend fun frameAt(positionMs: Long): ImageBitmap? {
        val bucket = (positionMs.coerceAtLeast(0L) / BUCKET_MS)
        synchronized(cache) { cache[bucket] }?.let { return it }
        if (unsupported || released) return null
        focusBucket = bucket
        return withContext(dispatcher) {
            if (released || unsupported) return@withContext null
            synchronized(cache) { cache[bucket] }?.let { return@withContext it }
            decode(bucket)
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
        val timeUs = (bucket * BUCKET_MS + BUCKET_MS / 2) * 1000L
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
        warmJob?.cancel()
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
    fun captureVideoFrame(width: Int, onFrame: (Bitmap) -> Unit) {
        val view = videoSurface?.get() ?: return
        if (view.width <= 0 || view.height <= 0) return
        val height = (width * view.height / view.width).coerceAtLeast(1)
        when (view) {
            is android.view.TextureView -> view.getBitmap(width, height)?.let(onFrame)
            is android.view.SurfaceView -> {
                if (Build.VERSION.SDK_INT < 24 || view is android.opengl.GLSurfaceView) return
                val surface = view.holder?.surface?.takeIf { it.isValid } ?: return
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                runCatching {
                    android.view.PixelCopy.request(surface, bitmap, { result ->
                        if (result == android.view.PixelCopy.SUCCESS) onFrame(bitmap)
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                }
            }
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
 * Fotogramma per [positionMs] (null senza scrubbing). Il flusso si apre solo al primo trascinamento
 * (nessuna connessione in piu' al server finche' non si usa la timeline).
 */
@Composable
internal fun rememberPlayerThumbnail(
    source: Pair<String, Map<String, String>>?,
    positionMs: Long?,
    durationMs: Long,
    playbackPositionMs: () -> Long,
): ImageBitmap? {
    val loader = remember(source?.first) { source?.let { PlayerThumbnailStore.obtain(it.first, it.second) } }
    val fallbackVersion = remember { MutableStateFlow(0) }
    val version by (loader?.version ?: fallbackVersion).collectAsState()
    val scrubbing = positionMs != null
    // Inizio dello scrubbing: subito il fotogramma a schermo come prima anteprima.
    LaunchedEffect(loader, scrubbing) {
        // Il fotogramma a schermo e' quello della posizione di riproduzione (non del dito).
        if (scrubbing) loader?.seedCurrentFrame(playbackPositionMs())
    }
    LaunchedEffect(loader, positionMs?.div(5_000L)) {
        val target = positionMs ?: return@LaunchedEffect
        val current = loader ?: return@LaunchedEffect
        current.focus(target)
        // Flusso aperto e griglia preparata solo dal primo trascinamento (una volta per sorgente).
        current.warmUp(durationMs)
        current.frameAt(target)
    }
    // Sempre il fotogramma pronto piu' vicino (quello esatto appena arriva), aggiornato a ogni nuovo fotogramma.
    return remember(loader, version, positionMs?.div(5_000L)) {
        if (positionMs == null) null else loader?.nearest(positionMs)
    }
}

/** Card dell'anteprima (fotogramma + tempo). */
@Composable
internal fun PlayerScrubPreview(
    positionMs: Long,
    thumbnail: ImageBitmap?,
    isTelevision: Boolean,
    modifier: Modifier = Modifier,
) {
    val width = if (isTelevision) 240.dp else 176.dp
    val height = if (isTelevision) 135.dp else 99.dp
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width, height)
                    .shadow(10.dp, shape)
                    .clip(shape)
                    .border(.7.dp, Color.White.copy(alpha = .18f), shape)
            )
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

private fun iosScrubTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
