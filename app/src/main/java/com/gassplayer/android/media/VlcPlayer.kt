package com.gassplayer.android.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Secondo motore di riproduzione basato su libVLC (FFmpeg + live555), esposto come un normale [Player] di Media3:
 * la schermata del player, la [androidx.media3.ui.PlayerView], la sessione multimediale e i controlli funzionano
 * senza modifiche, qualunque sia il motore attivo.
 *
 * Equivalente Android del motore FFmpeg di iOS (KSPlayer/KSMEPlayer): apre i protocolli e i contenitori che
 * ExoPlayer non gestisce (RTMP, MMS, RTP, SRT, UDP multicast, AVI/DivX/Xvid, WMV/ASF/VC-1, RealMedia...) e
 * decodifica in software i codec che mancano al dispositivo.
 */
@UnstableApi
class VlcPlayer private constructor(private val libVlc: LibVLC) : SimpleBasePlayer(Looper.getMainLooper()) {

    companion object {
        /** null se le librerie native di VLC non si caricano su questo dispositivo (ABI non supportata). */
        fun create(context: Context): VlcPlayer? = runCatching {
            val options = arrayListOf(
                "--http-reconnect",
                "--audio-time-stretch",
                "--avcodec-skiploopfilter=1",
                "--no-stats",
                "--sub-autodetect-fuzzy=1",
            )
            VlcPlayer(LibVLC(context.applicationContext, options))
        }.getOrNull()

        private val languageNames: Map<String, String> by lazy {
            val map = HashMap<String, String>()
            for (locale in Locale.getAvailableLocales()) {
                val code = locale.language.takeIf { it.length in 2..3 } ?: continue
                for (display in listOf(locale.getDisplayLanguage(Locale.ENGLISH), locale.getDisplayLanguage(Locale.ITALIAN), locale.getDisplayLanguage(locale))) {
                    val key = display.trim().lowercase(Locale.ROOT)
                    if (key.length >= 3) map.putIfAbsent(key, code)
                }
            }
            map
        }

        /** Lingua ISO dal nome della traccia VLC ("Traccia 1 - [Italiano]", "English", "ita"...). */
        private fun languageFromName(name: String?): String? {
            if (name.isNullOrBlank()) return null
            val tokens = name.lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+")).filter { it.length >= 3 }
            for (token in tokens) languageNames[token]?.let { return it }
            for (token in tokens) if (token.length == 3) runCatching { Locale.forLanguageTag(token).isO3Language }.getOrNull()?.let { if (it == token) return token }
            return null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaPlayer = MediaPlayer(libVlc)

    private var currentItem: androidx.media3.common.MediaItem? = null
    private var itemUid = 0L
    private var isLive = false
    private var playbackStateValue = Player.STATE_IDLE
    private var playWhenReadyValue = false
    private var errorValue: PlaybackException? = null
    private var durationMs = C.TIME_UNSET
    private var seekable = false
    private var volumeValue = 1f
    private var speedValue = 1f
    private var videoSizeValue = VideoSize.UNKNOWN
    private var firstFramePending = false
    private var tracksValue = Tracks.EMPTY
    private var lastPositionMs = 0L
    private var released = false

    /**
     * VLC 3 crea l'uscita video quando parte il flusso video: se la superficie non c'e' ancora (la PlayerView si
     * ricollega al nuovo motore un istante dopo) si resterebbe con il solo audio. L'avvio aspetta la superficie
     * per al massimo [OUTPUT_WAIT_MS] (contenuti solo audio o player in background partono comunque).
     */
    private var pendingStart = false
    private val startRunnable = Runnable { startIfPending() }
    private val OUTPUT_WAIT_MS = 1_500L

    private var attachedOutput: Any? = null
    private var outputView: View? = null
    private var layoutListener: View.OnLayoutChangeListener? = null

    init {
        mediaPlayer.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                if (Looper.myLooper() == Looper.getMainLooper()) handleEvent(event) else mainHandler.post { handleEvent(event) }
            }
        })
    }

    /**
     * Apre [url]. Gli header supportati da VLC (User-Agent, Referer) vengono passati come opzioni del media;
     * [startPositionMs] riprende un film/episodio, [autoPlay] = false apre in pausa ("Riprendi la visione?").
     */
    fun open(
        url: String,
        mediaId: String,
        title: String,
        userAgent: String,
        headers: Map<String, String>,
        live: Boolean,
        startPositionMs: Long,
        autoPlay: Boolean,
        hardwareDecode: Boolean,
        networkCachingMs: Int,
    ) {
        if (released) return
        runCatching { mediaPlayer.stop() }
        val media = Media(libVlc, Uri.parse(url))
        media.addOption(":network-caching=${networkCachingMs.coerceIn(300, 60_000)}")
        media.addOption(":http-reconnect")
        if (userAgent.isNotBlank()) media.addOption(":http-user-agent=$userAgent")
        headers.entries.firstOrNull { it.key.equals("Referer", true) || it.key.equals("Referrer", true) }
            ?.value?.takeIf { it.isNotBlank() }?.let { media.addOption(":http-referrer=$it") }
        if (!hardwareDecode) media.setHWDecoderEnabled(false, false)
        if (!live && startPositionMs > 0L) media.addOption(":start-time=${startPositionMs / 1000.0}")
        if (!autoPlay) media.addOption(":start-paused")
        mediaPlayer.setMedia(media)
        media.release()
        mediaPlayer.setAspectRatio(null)
        mediaPlayer.setScale(0f)
        mediaPlayer.setVolume((volumeValue * 100).roundToInt())
        if (!live && speedValue != 1f) mediaPlayer.setRate(speedValue)

        currentItem = androidx.media3.common.MediaItem.Builder()
            .setUri(url)
            .setMediaId(mediaId)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
            .build()
        itemUid++
        isLive = live
        playbackStateValue = Player.STATE_BUFFERING
        playWhenReadyValue = autoPlay
        errorValue = null
        durationMs = C.TIME_UNSET
        seekable = false
        videoSizeValue = VideoSize.UNKNOWN
        tracksValue = Tracks.EMPTY
        lastPositionMs = if (live) 0L else startPositionMs.coerceAtLeast(0L)
        mainHandler.removeCallbacks(startRunnable)
        pendingStart = true
        if (attachedOutput != null) {
            attachOutput()
            startIfPending()
        } else {
            mainHandler.postDelayed(startRunnable, OUTPUT_WAIT_MS)
        }
        invalidateState()
    }

    private fun startIfPending() {
        mainHandler.removeCallbacks(startRunnable)
        if (!pendingStart || released || currentItem == null) return
        pendingStart = false
        runCatching {
            mediaPlayer.play()
            // Messo in pausa mentre si aspettava la superficie ("Riprendi la visione?"): resta in pausa.
            if (!playWhenReadyValue) mediaPlayer.pause()
        }
    }

    /** Chiude il flusso (libera la connessione con il provider) senza distruggere il motore. */
    fun stopPlayback() {
        if (released) return
        mainHandler.removeCallbacks(startRunnable)
        pendingStart = false
        runCatching { mediaPlayer.stop() }
        currentItem = null
        playbackStateValue = Player.STATE_IDLE
        errorValue = null
        durationMs = C.TIME_UNSET
        tracksValue = Tracks.EMPTY
        videoSizeValue = VideoSize.UNKNOWN
        invalidateState()
    }

    val hasMedia: Boolean get() = currentItem != null

    // ---- Tracce (dialogo "Audio e sottotitoli") ----

    fun selectAudioToken(token: String?): Boolean {
        val id = token?.removePrefix("vlc:a:")?.toIntOrNull() ?: return false
        val ok = runCatching { mediaPlayer.setAudioTrack(id) }.getOrDefault(false)
        rebuildTracks(); invalidateState()
        return ok
    }

    /** Sottotitolo per lingua ("it") o per traccia ("vlc:s:3"); null/vuoto = disattivati. */
    fun selectSubtitle(language: String?) {
        val target = when {
            language.isNullOrBlank() -> -1
            language.startsWith("vlc:s:") -> language.removePrefix("vlc:s:").toIntOrNull() ?: -1
            else -> subtitleTracks().firstOrNull { it.second.equals(language, true) || it.second?.startsWith(language, true) == true }?.first ?: return
        }
        runCatching { mediaPlayer.setSpuTrack(target) }
        rebuildTracks(); invalidateState()
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        if (!enabled) runCatching { mediaPlayer.setSpuTrack(-1) }
        else if (runCatching { mediaPlayer.getSpuTrack() }.getOrDefault(-1) < 0) {
            subtitleTracks().firstOrNull()?.let { runCatching { mediaPlayer.setSpuTrack(it.first) } }
        }
        rebuildTracks(); invalidateState()
    }

    fun selectAudioLanguage(language: String?) {
        if (language.isNullOrBlank()) return
        if (language.startsWith("vlc:a:")) { selectAudioToken(language); return }
        val languages = mediaTrackLanguages()
        val tracks = runCatching { mediaPlayer.getAudioTracks() }.getOrNull().orEmpty().filter { it.id >= 0 }
        val match = tracks.firstOrNull { (languages[it.id] ?: languageFromName(it.name)).equals(language, true) } ?: return
        runCatching { mediaPlayer.setAudioTrack(match.id) }
        rebuildTracks(); invalidateState()
    }

    private fun subtitleTracks(): List<Pair<Int, String?>> {
        val languages = mediaTrackLanguages()
        return runCatching { mediaPlayer.getSpuTracks() }.getOrNull().orEmpty()
            .filter { it.id >= 0 }
            .map { it.id to (languages[it.id] ?: languageFromName(it.name)) }
    }

    private fun mediaTrackLanguages(): Map<Int, String?> {
        val media = runCatching { mediaPlayer.getMedia() }.getOrNull() ?: return emptyMap()
        return try {
            val out = HashMap<Int, String?>()
            for (i in 0 until media.getTrackCount()) {
                val track = media.getTrack(i) ?: continue
                out[track.id] = track.language?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "und" }
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        } finally {
            runCatching { media.release() }
        }
    }

    private fun rebuildTracks() {
        if (currentItem == null) { tracksValue = Tracks.EMPTY; return }
        val groups = ArrayList<Tracks.Group>()
        val languages = mediaTrackLanguages()
        runCatching {
            mediaPlayer.getCurrentVideoTrack()?.let { video ->
                val fps = if (video.frameRateDen > 0) video.frameRateNum.toFloat() / video.frameRateDen else Format.NO_VALUE.toFloat()
                val format = Format.Builder()
                    .setId("vlc:v:0")
                    .setSampleMimeType(MimeTypes.VIDEO_UNKNOWN)
                    .setWidth(video.width)
                    .setHeight(video.height)
                    .setFrameRate(fps)
                    .build()
                groups += Tracks.Group(TrackGroup("vlc-video", format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
            }
        }
        runCatching {
            val selected = mediaPlayer.getAudioTrack()
            mediaPlayer.getAudioTracks()?.filter { it.id >= 0 }?.forEach { track ->
                val format = Format.Builder()
                    .setId("vlc:a:${track.id}")
                    .setSampleMimeType(MimeTypes.AUDIO_UNKNOWN)
                    .setLabel(track.name)
                    // Token di selezione: il dialogo passa `language` al controller (l'etichetta mostrata e' il nome).
                    .setLanguage("vlc:a:${track.id}")
                    .build()
                groups += Tracks.Group(TrackGroup("vlc-audio-${track.id}", format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(track.id == selected))
            }
        }
        runCatching {
            val selected = mediaPlayer.getSpuTrack()
            mediaPlayer.getSpuTracks()?.filter { it.id >= 0 }?.forEach { track ->
                val format = Format.Builder()
                    .setId("vlc:s:${track.id}")
                    .setSampleMimeType(MimeTypes.TEXT_UNKNOWN)
                    .setLabel(track.name)
                    // Lingua reale quando nota (viene ricordata come preferenza), altrimenti la traccia.
                    .setLanguage(languages[track.id] ?: languageFromName(track.name) ?: "vlc:s:${track.id}")
                    .build()
                groups += Tracks.Group(TrackGroup("vlc-text-${track.id}", format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(track.id == selected))
            }
        }
        tracksValue = Tracks(groups)
    }

    private fun updateVideoSize() {
        val video = runCatching { mediaPlayer.getCurrentVideoTrack() }.getOrNull() ?: return
        if (video.width <= 0 || video.height <= 0) return
        val ratio = if (video.sarNum > 0 && video.sarDen > 0) video.sarNum.toFloat() / video.sarDen else 1f
        val rotated = video.orientation == 5 || video.orientation == 6 || video.orientation == 7 || video.orientation == 8
        videoSizeValue = if (rotated) VideoSize(video.height, video.width, 1f / ratio) else VideoSize(video.width, video.height, ratio)
        applyWindowSize()
    }

    private fun currentPositionMs(): Long {
        if (currentItem == null) return 0L
        val time = runCatching { mediaPlayer.getTime() }.getOrDefault(-1L)
        if (time >= 0L) lastPositionMs = time
        return lastPositionMs
    }

    private fun handleEvent(event: MediaPlayer.Event) {
        if (released || currentItem == null) return
        when (event.type) {
            MediaPlayer.Event.Opening -> playbackStateValue = Player.STATE_BUFFERING
            MediaPlayer.Event.Buffering -> {
                val buffering = event.getBuffering()
                if (playbackStateValue != Player.STATE_ENDED) {
                    playbackStateValue = if (buffering < 100f) Player.STATE_BUFFERING else Player.STATE_READY
                }
            }
            MediaPlayer.Event.Playing, MediaPlayer.Event.Paused -> {
                playbackStateValue = Player.STATE_READY
                // Pausa chiesta mentre VLC stava ancora aprendo il flusso: applicata appena parte.
                if (event.type == MediaPlayer.Event.Playing && !playWhenReadyValue) runCatching { mediaPlayer.pause() }
                rebuildTracks()
            }
            MediaPlayer.Event.Vout -> {
                if (event.getVoutCount() > 0) {
                    firstFramePending = true
                    playbackStateValue = Player.STATE_READY
                    updateVideoSize()
                }
                rebuildTracks()
            }
            MediaPlayer.Event.LengthChanged -> {
                val length = event.getLengthChanged()
                durationMs = if (length > 0L) length else C.TIME_UNSET
            }
            MediaPlayer.Event.SeekableChanged -> seekable = runCatching { mediaPlayer.isSeekable() }.getOrDefault(false)
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                rebuildTracks()
                updateVideoSize()
            }
            MediaPlayer.Event.TimeChanged -> {
                lastPositionMs = event.getTimeChanged().coerceAtLeast(0L)
                return
            }
            MediaPlayer.Event.EndReached -> playbackStateValue = Player.STATE_ENDED
            MediaPlayer.Event.EncounteredError -> {
                playbackStateValue = Player.STATE_IDLE
                errorValue = PlaybackException(
                    "VLC non riesce ad aprire o decodificare il flusso",
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED
                )
            }
            MediaPlayer.Event.Stopped -> if (playbackStateValue != Player.STATE_ENDED) playbackStateValue = Player.STATE_IDLE
            else -> return
        }
        invalidateState()
    }

    // ---- SimpleBasePlayer ----

    override fun getState(): SimpleBasePlayer.State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_VOLUME,
            Player.COMMAND_SET_VOLUME,
            Player.COMMAND_SET_VIDEO_SURFACE,
            Player.COMMAND_GET_TRACKS,
        ).build()
        val builder = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReadyValue, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlayerError(errorValue)
            .setVolume(volumeValue)
            .setPlaybackParameters(PlaybackParameters(speedValue))
            .setVideoSize(videoSizeValue)
        val item = currentItem
        if (item == null) {
            builder.setPlaybackState(if (playbackStateValue == Player.STATE_ENDED) Player.STATE_ENDED else Player.STATE_IDLE)
        } else {
            val data = SimpleBasePlayer.MediaItemData.Builder("vlc-$itemUid")
                .setMediaItem(item)
                .setTracks(tracksValue)
                .setIsSeekable(seekable && !isLive)
                .setIsDynamic(isLive)
                .setLiveConfiguration(if (isLive) androidx.media3.common.MediaItem.LiveConfiguration.UNSET else null)
                .setDurationUs(if (!isLive && durationMs > 0L) durationMs * 1000L else C.TIME_UNSET)
                .build()
            builder.setPlaylist(listOf(data))
                .setCurrentMediaItemIndex(0)
                .setPlaybackState(playbackStateValue)
                .setIsLoading(playbackStateValue == Player.STATE_BUFFERING)
                .setContentPositionMs(SimpleBasePlayer.PositionSupplier { currentPositionMs() })
                .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier { currentPositionMs() })
        }
        if (firstFramePending) {
            builder.setNewlyRenderedFirstFrame(true)
            firstFramePending = false
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playWhenReadyValue = playWhenReady
        if (currentItem != null && !pendingStart) runCatching { if (playWhenReady) mediaPlayer.play() else mediaPlayer.pause() }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (currentItem != null && (playbackStateValue == Player.STATE_IDLE || playbackStateValue == Player.STATE_ENDED)) {
            errorValue = null
            playbackStateValue = Player.STATE_BUFFERING
            runCatching { mediaPlayer.play() }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        runCatching { mediaPlayer.stop() }
        playbackStateValue = Player.STATE_IDLE
        errorValue = null
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        releaseNative()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (currentItem != null && !isLive) {
            val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
            runCatching {
                if (playbackStateValue == Player.STATE_ENDED) {
                    playbackStateValue = Player.STATE_BUFFERING
                    mediaPlayer.play()
                }
                mediaPlayer.setTime(target)
            }
            lastPositionMs = target
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        volumeValue = volume.coerceIn(0f, 1f)
        runCatching { mediaPlayer.setVolume((volumeValue * 100).roundToInt()) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speedValue = playbackParameters.speed.coerceIn(0.25f, 4f)
        if (!isLive) runCatching { mediaPlayer.setRate(speedValue) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        attachedOutput = videoOutput
        attachOutput()
        if (pendingStart) startIfPending()
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput == null || videoOutput === attachedOutput) {
            detachOutput()
            attachedOutput = null
        }
        return Futures.immediateVoidFuture()
    }

    // ---- Uscita video ----

    private fun attachOutput() {
        val output = attachedOutput ?: return
        if (released) return
        runCatching {
            val vout = mediaPlayer.getVLCVout()
            if (vout.areViewsAttached()) vout.detachViews()
            removeLayoutListener()
            when (output) {
                is SurfaceView -> { vout.setVideoView(output); trackSize(output) }
                is TextureView -> { vout.setVideoView(output); trackSize(output) }
                is SurfaceHolder -> vout.setVideoSurface(output.surface, output)
                is Surface -> vout.setVideoSurface(output, null)
                else -> return
            }
            vout.attachViews()
            applyWindowSize()
        }
    }

    private fun detachOutput() {
        removeLayoutListener()
        runCatching {
            val vout = mediaPlayer.getVLCVout()
            if (vout.areViewsAttached()) vout.detachViews()
        }
    }

    private fun trackSize(view: View) {
        outputView = view
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyWindowSize()
        }
        view.addOnLayoutChangeListener(listener)
        layoutListener = listener
    }

    private fun removeLayoutListener() {
        val view = outputView
        val listener = layoutListener
        if (view != null && listener != null) view.removeOnLayoutChangeListener(listener)
        outputView = null
        layoutListener = null
    }

    /** VLC disegna adattando il video alla finestra: la finestra e' la superficie dimensionata dalla PlayerView. */
    private fun applyWindowSize() {
        if (released) return
        val view = outputView
        val width = view?.width?.takeIf { it > 0 } ?: videoSizeValue.width.takeIf { it > 0 } ?: 1920
        val height = view?.height?.takeIf { it > 0 } ?: videoSizeValue.height.takeIf { it > 0 } ?: 1080
        runCatching { mediaPlayer.getVLCVout().setWindowSize(width, height) }
    }

    private fun releaseNative() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(startRunnable)
        pendingStart = false
        detachOutput()
        attachedOutput = null
        currentItem = null
        runCatching { mediaPlayer.setEventListener(null as MediaPlayer.EventListener?) }
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }
}
