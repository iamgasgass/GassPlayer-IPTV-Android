package com.gassplayer.android.media

import java.util.Locale

/**
 * Quale motore apre per primo un flusso. Su iOS KSPlayer usa FFmpeg come motore primario e apre praticamente ogni
 * protocollo/contenitore; su Android ExoPlayer resta il motore principale (HLS/DASH, TS, MP4, MKV...) e libVLC
 * (stesso FFmpeg/live555 di VLC) apre subito cio' che ExoPlayer non sa gestire:
 *  - protocolli: RTMP/RTMPS/RTMPT, MMS/MMSH, RTP, SRT, UDP (multicast), RTSP (VLC/live555 e' piu' compatibile;
 *    se VLC fallisce si riprova con il modulo RTSP di ExoPlayer);
 *  - contenitori: AVI/DivX/Xvid, WMV/ASF/WMA, RealMedia, OGM.
 * Tutto il resto parte con ExoPlayer e passa a VLC solo se ExoPlayer non riesce a decodificarlo.
 */
object VlcRouting {
    private val vlcSchemes = setOf(
        "rtmp", "rtmps", "rtmpe", "rtmpt", "rtmpte", "rtmpts",
        "mms", "mmsh", "mmst", "mmsu",
        "rtp", "srt", "udp", "rtsp", "rtsps",
    )
    private val vlcExtensions = setOf("avi", "divx", "xvid", "wmv", "asf", "wma", "rm", "rmvb", "ra", "ram", "ogm")
    private val vlcMimeTypes = setOf(
        "video/x-msvideo", "video/avi", "video/x-ms-wmv", "video/x-ms-asf", "audio/x-ms-wma",
        "application/vnd.rn-realmedia", "application/vnd.rn-realmedia-vbr", "video/x-flv-vp6",
    )

    /** Protocolli che ExoPlayer apre da solo: se VLC fallisce su questi si riprova con ExoPlayer. */
    private val exoSchemes = setOf("http", "https", "rtsp", "udp", "file", "content", "asset", "data")

    fun schemeOf(url: String): String =
        url.substringBefore("://", "").trim().lowercase(Locale.ROOT)

    fun extensionOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#').substringBefore('|')
        val last = path.substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        return if (dot < 0) "" else last.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** true = aprire con VLC senza passare da ExoPlayer. */
    fun prefersVlc(url: String, mimeType: String? = null): Boolean {
        if (url.isBlank()) return false
        if (schemeOf(url) in vlcSchemes) return true
        if (mimeType?.trim()?.lowercase(Locale.ROOT) in vlcMimeTypes) return true
        return extensionOf(url) in vlcExtensions
    }

    /** true = ExoPlayer puo' tentare questo indirizzo (riserva quando VLC non riesce). */
    fun exoCanOpen(url: String): Boolean = schemeOf(url) in exoSchemes
}
