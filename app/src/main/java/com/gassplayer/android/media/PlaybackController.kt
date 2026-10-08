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
        settings.value = value
        trackSelector.parameters = buildTrackParameters(value)

        playerInstance?.let { player ->
            player.repeatMode = if (value.loopPlayback) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.seekParameters = if (value.accurateSeek) {
                SeekParameters.EXACT
            } else {
                SeekParameters.CLOSEST_SYNC
            }
            if (player.playbackState != Player.STATE_IDLE) {
                player.setPlaybackSpeed(value.preferredPlaybackSpeed.coerceIn(0.25f, 3f))
            }
        }

        val signature = loadControlSignature(value)
        if (playerInstance != null && currentItem == null && signature != lastLoadControlSignature) {
            releasePlayerOnly()
        }
    }

    /**
     * Mirrors the iOS controller's explicit load: auto-play can be disabled
     * for the "Riprendi la visione?" confirmation UI.
     */
    fun play(item: MediaItem, startPosition: Long = 0L, autoPlay: Boolean = true) {
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
        setCurrentUrlAndPrepare(positiveStart)

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
        player.seekParameters = if (enabled) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC
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
                seekParameters = if (settings.value.accurateSeek) {
                    SeekParameters.EXACT
                } else {
                    SeekParameters.CLOSEST_SYNC
                }
                setPlaybackSpeed(settings.value.preferredPlaybackSpeed.coerceIn(0.25f, 3f))
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (tryResolvePlaybackError(error)) return
                        diagnostics.log("ERROR", "player", error.toString())
                        _error.value = error.message ?: "Errore di riproduzione"
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
        "${value.minBufferSec}|${value.maxBufferSec}|${value.playerStartBufferSec}|${value.httpCache}"

    private fun newStreamingClient(
        protocols: List<okhttp3.Protocol>,
        legacyTls: Boolean = false
    ): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
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

        val builder = Builder()
            .setUri(source)
            .setMediaId(currentItem?.id.orEmpty())
            .setTag(currentItem)

        val explicitMime = currentItem?.streamMimeType
            ?.takeIf { source == currentItem?.streamUrl }
        mimeTypeFor(source, explicitMime)?.let(builder::setMimeType)

        val p = ensurePlayer()
        p.setMediaItem(builder.build())
        p.prepare()
        if (position > 0L) p.seekTo(position)
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

        if (responseCode in setOf(408, 425, 429, 500, 502, 503, 504)) {
            diagnostics.log("WARN", "player", "Retry transitorio dopo HTTP $responseCode")
            scope.launch {
                delay(1_500L)
                if (currentItem != null) reprepareAtCurrentPosition()
            }
            return true
        }

        return false
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
        val requestedMaxSec = s.maxBufferSec.coerceIn(1, 600)
        val requestedStartSec = s.playerStartBufferSec.coerceIn(1, 600)
        val requestedMinSec = s.minBufferSec.coerceIn(1, 600)
        val maxSec = maxOf(requestedMaxSec, requestedMinSec, requestedStartSec)
        val effectiveMinSec = maxOf(requestedMinSec, requestedStartSec).coerceAtMost(maxSec)
        val maxMs = (maxSec * 1000L).toInt()
        val minMs = (effectiveMinSec * 1000L).toInt()
        val startMs = (requestedStartSec.coerceAtMost(effectiveMinSec) * 1000L).toInt()

        return DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 128 * 1024))
            .setBufferDurationsMsForStreaming(
                minMs,
                maxMs,
                startMs,
                startMs
            )
            .setBackBuffer(30_000, true)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
            .build()
    }

    private fun createMediaSourceFactory(): DefaultMediaSourceFactory {
        val factories = makeHttpFactories()
        configureFactories(factories)
        val upstream = factories.map { DefaultDataSource.Factory(context, it) }
        val failoverFactory = DataSource.Factory {
            FailoverDataSource(upstream, diagnostics)
        }

        val mediaFactory = if (!settings.value.httpCache) {
            DefaultMediaSourceFactory(failoverFactory)
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
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            )
        }

        return mediaFactory.setLoadErrorHandlingPolicy(
            DefaultLoadErrorHandlingPolicy(6)
        )
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
        val candidates = StreamUrlCandidates.ordered(dataSpec.uri.toString())
        var attempt = 0

        for ((urlIndex, candidate) in candidates.withIndex()) {
            for ((networkIndex, factory) in factories.withIndex()) {
                attempt++
                val source = factory.createDataSource()
                listeners.forEach(source::addTransferListener)

                try {
                    val opened = source.open(
                        dataSpec.withUri(Uri.parse(candidate))
                    )
                    current = source
                    if (attempt > 1) {
                        val transport = if (networkIndex == 0) {
                            "HTTP/2+HTTP/1.1"
                        } else {
                            "HTTP/1.1"
                        }
                        diagnostics.log(
                            "WARN",
                            "stream",
                            "Fallback tentativo $attempt: $transport / URL ${urlIndex + 1}/${candidates.size}: $candidate"
                        )
                    }
                    return opened
                } catch (t: Throwable) {
                    lastError = if (t is IOException) t else IOException(t)
                    runCatching { source.close() }
                }
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
