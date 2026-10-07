package com.gassplayer.android.media

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
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
    private var urlCandidates: List<StreamUrlCandidates.StreamCandidate> = emptyList()
    private var urlIndex = 0
    private var userAgents: List<String> = emptyList()
    private var userAgentIndex = 0
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(12_000)
        .setReadTimeoutMs(30_000)
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
        httpFactory.setDefaultRequestProperties(mapOf("User-Agent" to effectiveUserAgent()))
        player.repeatMode = if (value.loopPlayback) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun play(item: MediaItem, startPosition: Long = 0L) {
        _error.value = null
        _current.value = item
        currentItem = item
        this.startPosition = startPosition.coerceAtLeast(0L)
        urlCandidates = StreamUrlCandidates.playbackCandidates(item.streamUrl, item.kind)
        urlIndex = 0
        userAgents = buildList {
            settings.value.customUserAgent.trim().takeIf { it.isNotBlank() }?.let(::add)
            addAll(StreamUrlCandidates.userAgentLadder.filterNot { it == firstOrNull() })
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
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(android.net.Uri.parse(url), "video/*"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    private fun effectiveUserAgent(): String = settings.value.customUserAgent.trim().takeIf { it.isNotBlank() } ?: StreamUrlCandidates.userAgentLadder.first()
    private fun applyUserAgent() { httpFactory.setDefaultRequestProperties(mapOf("User-Agent" to userAgents.getOrNull(userAgentIndex).orEmpty().ifBlank { effectiveUserAgent() })) }

    private fun setCurrentUrlAndPrepare(position: Long) {
        val candidate = urlCandidates.getOrNull(urlIndex)
            ?: StreamUrlCandidates.StreamCandidate(currentItem?.streamUrl.orEmpty())
        val dataBuilder = androidx.media3.common.MediaItem.Builder()
            .setUri(candidate.url)
            .setMediaId(currentItem?.id.orEmpty())
            .setTag(currentItem)
        candidate.mimeType?.let(dataBuilder::setMimeType)
        player.setMediaItem(dataBuilder.build())
        player.prepare()
        if (position > 0) player.seekTo(position)
        player.playWhenReady = true
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
            val retryCandidate = urlCandidates[urlIndex]
            diagnostics.log("WARN", "player", "Retry stream candidate ${urlIndex + 1}/${urlCandidates.size}: ${retryCandidate.url} ${retryCandidate.mimeType.orEmpty()}")
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

    private fun createLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setAllocator(DefaultAllocator(true, 64 * 1024))
        .setBufferDurationsMs(settings.value.minBufferSec * 1000, settings.value.maxBufferSec * 1000, 1500, 3000)
        .build()

    private fun createMediaSourceFactory(): DefaultMediaSourceFactory {
        val upstream = DefaultDataSource.Factory(context, httpFactory)
        if (!settings.value.httpCache) return DefaultMediaSourceFactory(upstream)
        val cache = SimpleCache(File(context.cacheDir, "media3"), LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024), StandaloneDatabaseProvider(context))
        simpleCache = cache
        return DefaultMediaSourceFactory(CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream))
    }
}
