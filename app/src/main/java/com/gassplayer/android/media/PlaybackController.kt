package com.gassplayer.android.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.MediaSession
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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
class PlaybackController(private val context: Context, private val diagnostics: DiagnosticsService) {
    private val trackSelector = DefaultTrackSelector(context)
    private val settings = MutableStateFlow(AppSettings())
    private val scope = MainScope()
    private var sleepJob: Job? = null
    private var sleepTimerAt: Long? = null
    private var session: MediaSession? = null
    private var simpleCache: SimpleCache? = null
    private var currentItem: MediaItem? = null
    private var startPosition = 0L
    private var urlCandidates: List<String> = emptyList()
    private var urlIndex = 0
    private var userAgents: List<String> = emptyList()
    private var userAgentIndex = 0
    private var streamHeaders: Map<String, String> = emptyMap()

    private fun newStreamingClient(protocols: List<okhttp3.Protocol>, legacyTls: Boolean = false) = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        // API 25 devices and older IPTV TLS endpoints benefit from a compatible TLS profile.
        // Keep a modern profile first; use the legacy-compatible profile only after a transport
        // failure so well-configured HTTPS servers retain the stronger default behaviour.
        .connectionSpecs(if (legacyTls) {
            listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT)
        } else {
            listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT)
        })
        .protocols(protocols)
        .build()

    // Prefer HTTP/2, then strict HTTP/1.1, then a legacy-compatible TLS/HTTP1 path for
    // providers/CDNs that mishandle HTTP/2 or expose older TLS stacks.
    private val httpFactories = listOf(
        OkHttpDataSource.Factory(newStreamingClient(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))),
        OkHttpDataSource.Factory(newStreamingClient(listOf(okhttp3.Protocol.HTTP_1_1))),
        OkHttpDataSource.Factory(newStreamingClient(listOf(okhttp3.Protocol.HTTP_1_1), legacyTls = true))
    )

    private val mediaSourceFactory = createMediaSourceFactory()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(createLoadControl())
        .setTrackSelector(trackSelector)
        .setMediaSourceFactory(mediaSourceFactory)
        .build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (tryResolvePlaybackError(error)) return
                    diagnostics.log("ERROR", "player", error.toString())
                    _error.value = error.message ?: "Playback error"
                }
            })
        }

    private val _current = MutableStateFlow<MediaItem?>(null)
    val current: StateFlow<MediaItem?> = _current
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setSettings(value: AppSettings) {
        settings.value = value
        val ts = trackSelector.buildUponParameters()
            .setPreferredAudioLanguage(null)
            .setPreferredTextLanguage(value.subtitleLanguage)
            .setMaxVideoSize(if (value.adaptiveBitrate) Int.MAX_VALUE else 1280, if (value.adaptiveBitrate) Int.MAX_VALUE else 720)
            .build()
        trackSelector.parameters = ts
        applyUserAgent()
        player.repeatMode = if (value.loopPlayback) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun play(item: MediaItem, startPosition: Long = 0L) {
        _error.value = null
        _current.value = item
        currentItem = item
        this.startPosition = startPosition.coerceAtLeast(0L)
        urlCandidates = StreamUrlCandidates.ordered(item.streamUrl)
        urlIndex = 0
        streamHeaders = item.streamHeaders.filterKeys { it.isNotBlank() }
        val playlistUserAgent = streamHeaders["User-Agent"] ?: streamHeaders["user-agent"]
        userAgents = buildList {
            playlistUserAgent?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            settings.value.customUserAgent.trim().takeIf { it.isNotBlank() }?.let(::add)
            addAll(StreamUrlCandidates.userAgentLadder)
        }.distinct()
        userAgentIndex = 0
        applyUserAgent()
        setCurrentUrlAndPrepare(this.startPosition)
        if (session == null) session = MediaSession.Builder(context, player).setId("GassPlayerMediaSession").build()
        runCatching { ContextCompat.startForegroundService(context, Intent(context, GassPlayerMediaService::class.java)) }
    }

    fun retry() { currentItem?.let { play(it, player.currentPosition) } }
    fun skipForward(ms: Long = 10_000) = player.seekTo((player.currentPosition + ms).coerceAtMost(player.duration.coerceAtLeast(0L)))
    fun skipBack(ms: Long = 10_000) = player.seekTo((player.currentPosition - ms).coerceAtLeast(0L))
    fun setSpeed(speed: Float) { player.setPlaybackSpeed(speed) }
    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun setLoop(loop: Boolean) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    fun setQuality(maxHeight: Int?) { trackSelector.parameters = trackSelector.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, maxHeight ?: Int.MAX_VALUE).build() }
    fun selectAudio(language: String?) { trackSelector.parameters = trackSelector.buildUponParameters().setPreferredAudioLanguage(language).build() }
    fun selectSubtitle(language: String?) { trackSelector.parameters = trackSelector.buildUponParameters().setPreferredTextLanguage(language).build() }
    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepTimerAt = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        sleepJob = scope.launch {
            delay(minutes.coerceAtLeast(1) * 60_000L)
            player.pause()
            sleepTimerAt = null
        }
    }
    fun sleepTimerExpired() = sleepTimerAt?.let { System.currentTimeMillis() >= it } == true
    fun stop() { sleepJob?.cancel(); sleepTimerAt = null; player.stop(); _current.value = null; currentItem = null }
    fun release() { scope.cancel(); session?.release(); session = null; player.release(); simpleCache?.release(); simpleCache = null }

    fun handoffExternal(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(url), "video/*"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    private fun effectiveUserAgent(): String = userAgents.getOrNull(userAgentIndex)?.takeIf { it.isNotBlank() }
        ?: settings.value.customUserAgent.trim().takeIf { it.isNotBlank() }
        ?: StreamUrlCandidates.userAgentLadder.first()

    private fun applyUserAgent() {
        val headers = linkedMapOf<String, String>().apply {
            putAll(streamHeaders)
            this["User-Agent"] = effectiveUserAgent()
            this["Accept-Encoding"] = "identity"
        }
        httpFactories.forEach { it.setDefaultRequestProperties(headers) }
    }

    private fun setCurrentUrlAndPrepare(position: Long) {
        val source = urlCandidates.getOrElse(urlIndex) { currentItem?.streamUrl.orEmpty() }
        val builder = androidx.media3.common.MediaItem.Builder()
            .setUri(source)
            .setMediaId(currentItem?.id.orEmpty())
            .setTag(currentItem)
        val explicitMime = currentItem?.streamMimeType.takeIf { source == currentItem?.streamUrl }
        mimeTypeFor(source, explicitMime)?.let(builder::setMimeType)
        player.setMediaItem(builder.build())
        player.prepare()
        if (position > 0) player.seekTo(position)
        player.playWhenReady = true
    }

    private fun mimeTypeFor(url: String, explicit: String?): String? {
        if (!explicit.isNullOrBlank()) return explicit.trim()
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault(url).lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> androidx.media3.common.MimeTypes.APPLICATION_MPD
            path.endsWith(".ts") || path.endsWith(".mts") -> androidx.media3.common.MimeTypes.VIDEO_MP2T
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> androidx.media3.common.MimeTypes.VIDEO_MP4
            path.endsWith(".webm") -> androidx.media3.common.MimeTypes.VIDEO_WEBM
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".avi") -> "video/x-msvideo"
            path.endsWith(".flv") -> "video/x-flv"
            path.endsWith(".mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
            path.endsWith(".aac") -> androidx.media3.common.MimeTypes.AUDIO_AAC
            path.endsWith(".m4a") -> "audio/mp4"
            path.endsWith(".ogg") || path.endsWith(".oga") -> "audio/ogg"
            path.endsWith(".wav") -> "audio/wav"
            else -> null
        }
    }

    private fun tryResolvePlaybackError(error: PlaybackException): Boolean {
        val responseCode = findHttpCode(error)
        if (responseCode in setOf(401, 403, 406, 429) && userAgentIndex + 1 < userAgents.size) {
            userAgentIndex++
            applyUserAgent()
            diagnostics.log("WARN", "player", "Retry User-Agent ${userAgentIndex + 1}/${userAgents.size} (HTTP $responseCode)")
            setCurrentUrlAndPrepare(player.currentPosition)
            return true
        }
        if (urlIndex + 1 < urlCandidates.size) {
            urlIndex++
            diagnostics.log("WARN", "player", "Retry URL candidato ${urlIndex + 1}/${urlCandidates.size}")
            setCurrentUrlAndPrepare(player.currentPosition)
            return true
        }
        if (responseCode in setOf(408, 425, 429, 500, 502, 503, 504)) {
            diagnostics.log("WARN", "player", "Retry transitorio dopo HTTP $responseCode")
            scope.launch {
                delay(1_500L)
                currentItem?.let { setCurrentUrlAndPrepare(player.currentPosition) }
            }
            return true
        }
        return false
    }

    private fun findHttpCode(error: PlaybackException): Int? {
        fun scan(t: Throwable?): Int? {
            if (t == null) return null
            if (t is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return t.responseCode
            return scan(t.cause)
        }
        return scan(error)
    }

    private fun createLoadControl(): LoadControl {
        val minMs = (settings.value.minBufferSec.coerceAtLeast(10) * 1000)
        val maxMs = (settings.value.maxBufferSec.coerceAtLeast((minMs / 1000) + 40).coerceAtLeast(60) * 1000)
        return DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 128 * 1024))
            .setBufferDurationsMs(minMs, maxMs, 5000, 10000)
            .setBackBuffer(30_000, true)
            .build()
    }

    private fun createMediaSourceFactory(): DefaultMediaSourceFactory {
        val upstreamFactories = httpFactories.map { DefaultDataSource.Factory(context, it) }
        val failoverFactory = DataSource.Factory { FailoverDataSource(upstreamFactories, diagnostics) }
        val factory = if (!settings.value.httpCache) {
            DefaultMediaSourceFactory(failoverFactory)
        } else {
            val cache = SimpleCache(File(context.cacheDir, "media3"), LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024), StandaloneDatabaseProvider(context))
            simpleCache = cache
            DefaultMediaSourceFactory(
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(failoverFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            )
        }
        return factory.setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
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
                    val opened = source.open(dataSpec.withUri(Uri.parse(candidate)))
                    current = source
                    if (attempt > 1) {
                        val transport = if (networkIndex == 0) "HTTP/2+HTTP/1.1" else "HTTP/1.1"
                        diagnostics.log("WARN", "stream", "Fallback tentativo ${attempt}: $transport / URL ${urlIndex + 1}/${candidates.size}: $candidate")
                    }
                    return opened
                } catch (t: Throwable) {
                    lastError = when (t) {
                        is IOException -> t
                        else -> IOException(t)
                    }
                    runCatching { source.close() }
                }
            }
        }
        throw lastError ?: IOException("Unable to open stream")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: -1

    override fun close() {
        current?.close()
        current = null
    }

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
}
