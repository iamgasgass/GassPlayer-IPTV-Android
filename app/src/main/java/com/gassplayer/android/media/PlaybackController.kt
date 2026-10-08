package com.gassplayer.android.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem.Builder
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.gassplayer.android.data.AppSettings
import com.gassplayer.android.data.DiagnosticsService
import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.StreamUrlCandidates
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Android equivalent of iOS KSPlaybackController.
 *
 * The transport is Media3/ExoPlayer rather than KSPlayer/FFmpeg, but the
 * lifecycle and player-facing behavior deliberately mirrors the iOS flow:
 * old item is torn down before a new one is prepared, resume is explicit,
 * stream candidates are retried, headers are preserved, playback settings are
 * applied to the real engine, and the load-control start buffer is no longer
 * a dead setting.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    private val context: Context,
    private val diagnostics: DiagnosticsService
) {
    private val trackSelector = DefaultTrackSelector(context)
    private val settings = MutableStateFlow(AppSettings())
    private val scope = MainScope()

    private var sleepJob: Job? = null
    private var sleepTimerAt: Long? = null
    private var simpleCache: SimpleCache? = null
    private var playerInstance: ExoPlayer? = null

    private var currentItem: MediaItem? = null
    private var urlCandidates: List<String> = emptyList()
    private var urlIndex = 0
    private var userAgents: List<String> = emptyList()
    private var userAgentIndex = 0
    private var streamHeaders: Map<String, String> = emptyMap()
    private var requestedAutoplay = true
    private var lastLoadControlSignature: String = ""
    private var httpFactories: List<OkHttpDataSource.Factory> = emptyList()
    private var zapJob: Job? = null
    private var networkRetries = 0

    val player: ExoPlayer
        get() = ensurePlayer()

    private val _current = MutableStateFlow<MediaItem?>(null)
    val current: StateFlow<MediaItem?> = _current

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _sleepRemainingMinutes = MutableStateFlow<Int?>(null)
    val sleepRemainingMinutes: StateFlow<Int?> = _sleepRemainingMinutes

    init {
        trackSelector.parameters = buildTrackParameters(settings.value)
    }

    /**
     * Called whenever DataStore changes. Settings are applied to the real
     * selector/player, and settings that require player creation are picked up
     * before the next playback.
     */
    fun setSettings(value: AppSettings) {
        val previous = settings.value
        settings.value = value

        scope.launch {
            trackSelector.parameters = buildTrackParameters(value)

            val rebuildRequired = previous.minBufferSec != value.minBufferSec ||
                previous.maxBufferSec != value.maxBufferSec ||
                previous.playerStartBufferSec != value.playerStartBufferSec ||
                previous.httpCache != value.httpCache ||
                previous.asyncDecode != value.asyncDecode ||
                previous.hardwareDecode != value.hardwareDecode ||
                previous.softwareDecode != value.softwareDecode ||
                previous.customUserAgent != value.customUserAgent

            val existing = playerInstance
            val activeItem = currentItem
            if (existing != null && activeItem != null && rebuildRequired) {
                val position = existing.currentPosition.coerceAtLeast(0L)
                requestedAutoplay = existing.playWhenReady
                releasePlayerOnly()
                ensurePlayer()
                setCurrentUrlAndPrepare(position)
                return@launch
            }

            existing?.let { player ->
                player.repeatMode = if (value.loopPlayback) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.setSeekParameters(
                    if (value.accurateSeek) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC
                )
                if (player.playbackState != Player.STATE_IDLE) {
                    player.setPlaybackSpeed(value.preferredPlaybackSpeed.coerceIn(0.25f, 3f))
                }
            }

            val signature = loadControlSignature(value)
            if (playerInstance != null && currentItem == null && signature != lastLoadControlSignature) {
                releasePlayerOnly()
            }
        }
    }

    /**
     * Mirrors the iOS controller's explicit load: auto-play can be disabled
     * for the "Riprendi la visione?" confirmation UI.
     */
    fun play(item: MediaItem, startPosition: Long = 0L, autoPlay: Boolean = true) {
        val previousKind = currentItem?.kind
        zapJob?.cancel()
        networkRetries = 0
        stopCurrentForNewItem()
        _error.value = null
        _current.value = item
        currentItem = item

        val positiveStart = startPosition.coerceAtLeast(0L)
        requestedAutoplay = autoPlay
        urlCandidates = StreamUrlCandidates.ordered(item.streamUrl)
        urlIndex = 0
        streamHeaders = item.streamHeaders.filterKeys { it.isNotBlank() }

        val playlistUserAgent = streamHeaders["User-Agent"]
            ?: streamHeaders["user-agent"]
        userAgents = buildList {
            playlistUserAgent?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            settings.value.customUserAgent.trim()
                .takeIf { it.isNotBlank() }
                ?.let(::add)
            addAll(StreamUrlCandidates.userAgentLadder)
        }.distinct()
        userAgentIndex = 0

        ensurePlayer()
        if (item.kind == MediaKind.LIVE && previousKind == MediaKind.LIVE) {
            // Channel surfing: collapse rapid zaps into the one the viewer stops on, so we never
            // stack prepares (and codec re-inits) on a low-end box.
            zapJob = scope.launch {
                delay(ZAP_DEBOUNCE_MS)
                setCurrentUrlAndPrepare(positiveStart)
            }
        } else {
            setCurrentUrlAndPrepare(positiveStart)
        }

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GassPlayerMediaService::class.java)
            )
        }
    }

    fun retry() {
        currentItem?.let { play(it, player.currentPosition, autoPlay = true) }
    }

    fun pause() = player.pause()
    fun playNow() = player.play()

    fun skipForward(ms: Long = 10_000) {
        val duration = player.duration
        val target = player.currentPosition + ms
        player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target.coerceAtLeast(0L))
    }

    fun skipBack(ms: Long = 10_000) {
        player.seekTo((player.currentPosition - ms).coerceAtLeast(0L))
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setSpeed(speed: Float) {
        val safe = speed.coerceIn(0.25f, 3f)
        player.setPlaybackSpeed(safe)
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun setLoop(loop: Boolean) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setQuality(maxHeight: Int?) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setMaxVideoSize(Int.MAX_VALUE, maxHeight ?: Int.MAX_VALUE)
            .build()
    }

    fun selectTrack(group: Tracks.Group, trackIndex: Int) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .addOverride(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
            )
            .build()
    }

    fun selectAudio(language: String?) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredAudioLanguage(language?.takeIf { it.isNotBlank() })
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
    }

    fun selectSubtitle(language: String?) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredTextLanguage(language?.takeIf { it.isNotBlank() })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, language.isNullOrBlank())
            .build()
    }

    fun setSubtitleEnabled(enabled: Boolean) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }

    fun setAccurateSeek(enabled: Boolean) {
        player.setSeekParameters(if (enabled) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
    }

    fun startSleepTimer(minutes: Int) {
        val safe = minutes.coerceIn(1, 240)
        sleepJob?.cancel()
        _sleepRemainingMinutes.value = safe
        sleepTimerAt = System.currentTimeMillis() + safe * 60_000L
        sleepJob = scope.launch {
            while (true) {
                val remainingMs = (sleepTimerAt ?: return@launch) - System.currentTimeMillis()
                if (remainingMs <= 0L) break
                _sleepRemainingMinutes.value =
                    ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                delay(15_000L)
            }
            player.pause()
            sleepTimerAt = null
            _sleepRemainingMinutes.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepTimerAt = null
        _sleepRemainingMinutes.value = null
    }

    fun stop() {
        zapJob?.cancel()
        sleepJob?.cancel()
        sleepJob = null
        sleepTimerAt = null
        _sleepRemainingMinutes.value = null
        playerInstance?.stop()
        _current.value = null
        currentItem = null
        if (playerInstance != null && loadControlSignature(settings.value) != lastLoadControlSignature) {
            releasePlayerOnly()
        }
    }

    fun release() {
        zapJob?.cancel()
        scope.cancel()
        cancelSleepTimer()
        _current.value = null
        currentItem = null
        releasePlayerOnly()
    }

    fun handoffExternal(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "Apri con un altro player"))
            true
        }.getOrDefault(false)
    }

    private fun ensurePlayer(): ExoPlayer {
        val existing = playerInstance
        if (existing != null) return existing

        releasePlayerOnly()
        val created = ExoPlayer.Builder(context)
            .setRenderersFactory(createRenderersFactory())
            .setLoadControl(createLoadControl())
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(createMediaSourceFactory())
            .build()
            .apply {
                repeatMode = if (settings.value.loopPlayback) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
                setSeekParameters(
                    if (settings.value.accurateSeek) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC
                )
                setPlaybackSpeed(settings.value.preferredPlaybackSpeed.coerceIn(0.25f, 3f))
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) networkRetries = 0
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (tryResolvePlaybackError(error)) return
                        diagnostics.log("ERROR", "player", error.toString())
                        _error.value = describeError(error)
                    }
                })
            }

        playerInstance = created
        lastLoadControlSignature = loadControlSignature(settings.value)
        return created
    }

    private fun stopCurrentForNewItem() {
        playerInstance?.apply {
            stop()
            clearMediaItems()
        }
        _error.value = null
    }

    private fun releasePlayerOnly() {
        playerInstance?.let { runCatching { it.release() } }
        playerInstance = null
        simpleCache?.let { runCatching { it.release() } }
        simpleCache = null
    }

    private fun buildTrackParameters(value: AppSettings): DefaultTrackSelector.Parameters =
        trackSelector.buildUponParameters()
            .setPreferredAudioLanguage(null)
            .setPreferredTextLanguage(value.subtitleLanguage.takeIf { it.isNotBlank() })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, value.subtitleLanguage.isBlank())
            // "Solo audio" is now a real player behavior: Media3 is instructed
            // not to select any video track instead of merely storing the switch.
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, value.audioOnly)
            .setMaxVideoSize(
                if (value.adaptiveBitrate) Int.MAX_VALUE else 1280,
                if (value.adaptiveBitrate) Int.MAX_VALUE else 720
            )
            .build()

    private fun loadControlSignature(value: AppSettings): String =
        "${value.minBufferSec}|${value.maxBufferSec}|${value.playerStartBufferSec}|${value.httpCache}|${value.asyncDecode}|${value.hardwareDecode}|${value.softwareDecode}"

    private fun newStreamingClient(
        protocols: List<okhttp3.Protocol>,
        legacyTls: Boolean = false
    ): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            // A dead live connection must surface in seconds (so we reconnect), not after 2 minutes.
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectionSpecs(
                if (legacyTls) {
                    listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT)
                } else {
                    listOf(
                        ConnectionSpec.MODERN_TLS,
                        ConnectionSpec.COMPATIBLE_TLS,
                        ConnectionSpec.CLEARTEXT
                    )
                }
            )
            .protocols(protocols)
            .build()

    private fun makeHttpFactories(): List<OkHttpDataSource.Factory> = listOf(
        OkHttpDataSource.Factory(
            newStreamingClient(
                listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1)
            )
        ),
        OkHttpDataSource.Factory(
            newStreamingClient(listOf(okhttp3.Protocol.HTTP_1_1))
        ),
        OkHttpDataSource.Factory(
            newStreamingClient(
                listOf(okhttp3.Protocol.HTTP_1_1),
                legacyTls = true
            )
        )
    )

    private fun effectiveUserAgent(): String =
        userAgents.getOrNull(userAgentIndex)?.takeIf { it.isNotBlank() }
            ?: settings.value.customUserAgent.trim().takeIf { it.isNotBlank() }
            ?: StreamUrlCandidates.userAgentLadder.first()

    private fun configureFactories(factories: List<OkHttpDataSource.Factory>) {
        val headers = linkedMapOf<String, String>().apply {
            putAll(streamHeaders)
            this["User-Agent"] = effectiveUserAgent()
            this["Accept-Encoding"] = "identity"
        }
        factories.forEach { it.setDefaultRequestProperties(headers) }
    }

    private fun setCurrentUrlAndPrepare(position: Long) {
        val source = urlCandidates.getOrElse(urlIndex) {
            currentItem?.streamUrl.orEmpty()
        }
        val isLive = currentItem?.kind == MediaKind.LIVE

        // Headers/User-Agent were only applied when the player was first built, so a channel with its
        // own Referer/UA (or a User-Agent retry) silently kept the previous channel's headers.
        // Data sources are created per request, so updating the shared factories takes effect now.
        ensurePlayer()
        if (httpFactories.isNotEmpty()) configureFactories(httpFactories)

        val builder = Builder()
            .setUri(source)
            .setMediaId(currentItem?.id.orEmpty())
            .setTag(currentItem)

        if (isLive) {
            builder.setLiveConfiguration(
                androidx.media3.common.MediaItem.LiveConfiguration.Builder()
                    .setMinOffsetMs(LIVE_MIN_OFFSET_MS)
                    .setMaxOffsetMs(LIVE_MAX_OFFSET_MS)
                    // Gentle catch-up/slow-down keeps the live edge without visible speed jumps.
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
        }

        val explicitMime = currentItem?.streamMimeType
            ?.takeIf { source == currentItem?.streamUrl }
        mimeTypeFor(source, explicitMime)?.let(builder::setMimeType)

        val p = ensurePlayer()
        p.setMediaItem(builder.build())
        p.prepare()
        if (position > 0L && !isLive) p.seekTo(position)
        p.playWhenReady = requestedAutoplay
    }

    private fun mimeTypeFor(url: String, explicit: String?): String? {
        if (!explicit.isNullOrBlank()) return explicit.trim()

        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault(url).lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ts") || path.endsWith(".mts") -> MimeTypes.VIDEO_MP2T
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> MimeTypes.VIDEO_MP4
            path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            path.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
            path.endsWith(".aac") -> MimeTypes.AUDIO_AAC
            else -> null
        }
    }

    private fun tryResolvePlaybackError(error: PlaybackException): Boolean {
        val responseCode = findHttpCode(error)

        // Fell behind the live window (long stall/pause): jump back to the live edge, no error shown.
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            playerInstance?.let { it.seekToDefaultPosition(); it.prepare() }
            return true
        }

        if (responseCode in setOf(401, 403, 406, 429)
            && userAgentIndex + 1 < userAgents.size
        ) {
            userAgentIndex++
            diagnostics.log(
                "WARN",
                "player",
                "Retry User-Agent ${userAgentIndex + 1}/${userAgents.size} (HTTP $responseCode)"
            )
            reprepareAtCurrentPosition()
            return true
        }

        if (urlIndex + 1 < urlCandidates.size) {
            urlIndex++
            diagnostics.log(
                "WARN",
                "player",
                "Retry URL candidato ${urlIndex + 1}/${urlCandidates.size}"
            )
            reprepareAtCurrentPosition()
            return true
        }

        val transientHttp = responseCode in setOf(408, 425, 429, 500, 502, 503, 504)
        val networkLike = responseCode == null && error.errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
        if ((transientHttp || networkLike) && currentItem != null && networkRetries < MAX_NETWORK_RETRIES) {
            // Exponential backoff (0.5s, 1s, 2s, 4s, 8s): providers under load recover in seconds.
            val wait = minOf(500L shl networkRetries, 8_000L)
            networkRetries++
            diagnostics.log("WARN", "player", "Retry $networkRetries/$MAX_NETWORK_RETRIES tra ${wait}ms (${responseCode ?: error.errorCodeName})")
            scope.launch {
                delay(wait)
                if (currentItem != null) reprepareAtCurrentPosition()
            }
            return true
        }

        return false
    }

    private fun describeError(error: PlaybackException): String {
        findHttpCode(error)?.let { code ->
            return when (code) {
                401 -> "Il server ha rifiutato le credenziali. Controlla utente e password."
                403 -> "Il server ha rifiutato il flusso (403): spesso l'account è già in uso su un altro dispositivo, oppure il provider blocca lo User-Agent."
                404 -> "Il canale non esiste più sul server. Aggiorna la lista."
                429 -> "Troppe richieste: il provider sta limitando questo dispositivo."
                in 500..599 -> "Il server del provider ha problemi ($code). Riprova tra poco."
                else -> "Il server ha risposto con HTTP $code."
            }
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connessione al server persa o troppo lenta."
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "Il server ha inviato una pagina web invece di un flusso: il link potrebbe essere scaduto."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Flusso non valido o danneggiato."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "Questo dispositivo non ha un decoder per il formato."
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Formato video non supportato da questo dispositivo."
            else -> error.message ?: "Errore di riproduzione"
        }
    }

    private fun reprepareAtCurrentPosition() {
        val position = player.currentPosition.coerceAtLeast(0L)
        setCurrentUrlAndPrepare(position)
    }

    private fun findHttpCode(error: PlaybackException): Int? {
        fun scan(t: Throwable?): Int? {
            if (t == null) return null
            if (t is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return t.responseCode
            }
            return scan(t.cause)
        }
        return scan(error)
    }

    private fun createLoadControl(): LoadControl {
        val s = settings.value
        val userMinMs = s.minBufferSec.coerceIn(1, 600) * 1000
        val userMaxMs = s.maxBufferSec.coerceIn(1, 600) * 1000
        // Instant start: the first frame is shown as soon as ~1.5 s of media is queued, whatever the
        // (deeper) user setting; after a stall we refill to 3 s so playback does not flap.
        val startMs = minOf(s.playerStartBufferSec.coerceIn(1, 600) * 1000, FAST_START_MS)
        val rebufferMs = maxOf(startMs, REBUFFER_MS)
        // Behind that fast start we still keep a deep reservoir to ride out twitchy IPTV sources.
        val minMs = maxOf(userMinMs, rebufferMs)
        val maxMs = maxOf(userMaxMs, minMs)

        // Byte ceiling derived from the device heap, so a high-bitrate stream (4K / remux) can never
        // grow the buffer until the process runs out of memory.
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val heapMb = (am?.let { if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0) it.largeMemoryClass else it.memoryClass } ?: 128)
        val bufferBytes = (heapMb / 4).coerceIn(16, 96) * 1024 * 1024

        return DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs)
            .setTargetBufferBytes(bufferBytes)
            .setBackBuffer(15_000, false)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    /** Decoder policy: honours the hardware/software/async settings (they used to be dead switches). */
    private fun createRenderersFactory(): androidx.media3.exoplayer.DefaultRenderersFactory {
        val s = settings.value
        val factory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            // If the preferred decoder fails to initialise, try the next one instead of erroring out.
            .setEnableDecoderFallback(true)
        if (s.asyncDecode) factory.forceEnableMediaCodecAsynchronousQueueing()
        if (s.softwareDecode && !s.hardwareDecode) {
            factory.setMediaCodecSelector(androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mime, secure, tunneling)
                    .sortedBy { it.hardwareAccelerated }
            })
        }
        return factory
    }

    /** MPEG-TS tuned for live IPTV: faster lock on streams without AUDs / with non-IDR keyframes. */
    private fun createExtractorsFactory(): androidx.media3.extractor.ExtractorsFactory =
        androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)

    private fun createMediaSourceFactory(): DefaultMediaSourceFactory {
        val factories = makeHttpFactories()
        httpFactories = factories
        configureFactories(factories)
        val upstream = factories.map { DefaultDataSource.Factory(context, it) }
        val failoverFactory = DataSource.Factory {
            FailoverDataSource(upstream, diagnostics)
        }

        val mediaFactory = if (!settings.value.httpCache) {
            DefaultMediaSourceFactory(failoverFactory, createExtractorsFactory())
        } else {
            val cache = SimpleCache(
                File(context.cacheDir, "media3"),
                LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                StandaloneDatabaseProvider(context)
            )
            simpleCache = cache
            DefaultMediaSourceFactory(
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(failoverFactory)
                    .setFlags(
                        CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                            CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS
                    ),
                createExtractorsFactory()
            )
        }

        return mediaFactory.setLoadErrorHandlingPolicy(iptvLoadErrorPolicy)
    }

    /**
     * Retry policy tuned for IPTV rather than CDNs: 403/429/5xx and network blips usually clear in a
     * few seconds, so they are retried with backoff; 401/404/410 will not fix themselves.
     */
    private val iptvLoadErrorPolicy = object : DefaultLoadErrorHandlingPolicy(MAX_LOAD_RETRIES) {
        override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val code = (info.exception as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            return when (code) {
                401, 404, 410 -> C.TIME_UNSET
                else -> if (info.errorCount > MAX_LOAD_RETRIES) C.TIME_UNSET
                else minOf(500L shl (info.errorCount - 1).coerceAtLeast(0), 6_000L)
            }
        }
    }

    private companion object {
        const val ZAP_DEBOUNCE_MS = 250L
        const val FAST_START_MS = 1_500
        const val REBUFFER_MS = 3_000
        const val MAX_LOAD_RETRIES = 5
        const val MAX_NETWORK_RETRIES = 6
        const val LIVE_MIN_OFFSET_MS = 3_000L
        const val LIVE_MAX_OFFSET_MS = 30_000L
    }
}

@OptIn(UnstableApi::class)
private class FailoverDataSource(
    private val factories: List<DataSource.Factory>,
    private val diagnostics: DiagnosticsService
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        current?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        var lastError: IOException? = null
        val original = dataSpec.uri.toString()
        val candidates = StreamUrlCandidates.ordered(original)
        val hostKey = FailoverMemory.keyOf(original)
        val remembered = FailoverMemory.get(hostKey)

        // Build the attempt ladder (candidate URL x transport). The route that worked last time for
        // this host goes first, so after the first successful open every HLS segment/playlist request
        // goes straight to the working route instead of re-walking a 40+ step ladder.
        val attempts = LinkedHashSet<Pair<Int, Int>>()
        if (remembered != null) {
            val ci = candidates.indexOfFirst { FailoverMemory.baseOf(it) == remembered.first }
            if (ci >= 0 && remembered.second in factories.indices) attempts += ci to remembered.second
        }
        for (ci in candidates.indices) for (fi in factories.indices) attempts += ci to fi

        val deadline = android.os.SystemClock.elapsedRealtime() + FALLBACK_BUDGET_MS
        var attempt = 0
        for ((urlIndex, networkIndex) in attempts) {
            // Never start a new fallback after the time budget: a dead channel must fail in seconds.
            if (attempt > 0 && android.os.SystemClock.elapsedRealtime() > deadline) break
            attempt++
            val candidate = candidates[urlIndex]
            val source = factories[networkIndex].createDataSource()
            listeners.forEach(source::addTransferListener)

            try {
                val opened = source.open(dataSpec.withUri(Uri.parse(candidate)))
                current = source
                FailoverMemory.put(hostKey, FailoverMemory.baseOf(candidate), networkIndex)
                if (attempt > 1) {
                    diagnostics.log(
                        "WARN",
                        "stream",
                        "Fallback tentativo $attempt: trasporto ${networkIndex + 1}/${factories.size} / URL ${urlIndex + 1}/${candidates.size}: $candidate"
                    )
                }
                return opened
            } catch (t: Throwable) {
                runCatching { source.close() }
                val io = if (t is IOException) t else IOException(t)
                lastError = io
                val code = (t as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                // Auth/rate-limit answers are definitive for this request: let the player's User-Agent
                // ladder handle them immediately instead of burning through every URL variant.
                if (code == 401 || code == 403 || code == 406 || code == 429) throw io
                // A definitive "gone" from a route that is known to work (e.g. live-edge segment already
                // expired) is not a transport problem: fail now, the player will refresh the playlist.
                if ((code == 404 || code == 410) && remembered != null && FailoverMemory.baseOf(candidate) == remembered.first) throw io
            }
        }

        throw lastError ?: IOException("Unable to open stream")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: -1

    override fun close() {
        current?.close()
        current = null
    }

    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> =
        current?.responseHeaders ?: emptyMap()
}

/** Remembers, per host, which base URL and transport last opened successfully. */
private object FailoverMemory {
    private val good = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()

    fun keyOf(url: String): String = baseOf(url).substringAfter("://").lowercase()
    fun baseOf(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return url
        val end = url.indexOf('/', i + 3).let { if (it < 0) url.length else it }
        return url.substring(0, end)
    }
    fun get(key: String): Pair<String, Int>? = good[key]
    fun put(key: String, base: String, transport: Int) { good[key] = base to transport }
}

private const val FALLBACK_BUDGET_MS = 20_000L
