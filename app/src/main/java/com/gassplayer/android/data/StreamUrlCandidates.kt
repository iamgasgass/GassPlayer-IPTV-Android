package com.gassplayer.android.data

import java.net.URI

object StreamUrlCandidates {
    val userAgentLadder = listOf(
        "VLC/3.0.20 LibVLC/3.0.20",
        "Lavf/60.16.100",
        "IPTVSmartersPro",
        "okhttp/4.12.0",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    )
    private val vodExtensions = listOf("mp4", "mkv", "avi", "m3u8", "ts", "mov", "m4v", "webm", "flv", "wmv", "mpg")
    private val liveExtensions = listOf("m3u8", "ts")
    private const val maxAlternatives = 6

    fun ordered(url: String): List<String> {
        val normalized = url.trim()
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return listOf(normalized)
        val parts = uri.path?.split('/').orEmpty().filter { it.isNotBlank() }
        if (parts.size < 4) return listOf(normalized)
        val kind = parts[parts.size - 4].lowercase()
        if (kind !in setOf("movie", "series", "live")) return listOf(normalized)
        val filename = parts.last()
        val dot = filename.lastIndexOf('.')
        val order = if (kind == "live") liveExtensions else vodExtensions

        // Xtream endpoints are frequently extensionless; the server may expose
        // the same stream as HLS or MPEG-TS depending on the requested suffix.
        if (dot <= 0) {
            val alternatives = order.take(maxAlternatives).map { suffix ->
                "$normalized.$suffix"
            }
            return (listOf(normalized) + alternatives).distinct()
        }

        val base = filename.substring(0, dot)
        val current = filename.substring(dot + 1).lowercase()
        val folder = normalized.substringBeforeLast('/')
        val alternatives = order.filter { it != current }.take(maxAlternatives).map { suffix ->
            "$folder/$base.$suffix"
        }
        return (listOf(normalized) + alternatives).distinct()
    }

    fun playbackCandidates(url: String, kind: MediaKind): List<StreamCandidate> {
        val urls = ordered(url)
        return urls.flatMap { candidateUrl ->
            val uri = runCatching { URI(candidateUrl) }.getOrNull()
            val path = uri?.path?.lowercase().orEmpty()
            val mime = when {
                path.endsWith(".m3u8") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                path.endsWith(".ts") || path.endsWith(".m2ts") -> androidx.media3.common.MimeTypes.VIDEO_MP2T
                else -> null
            }

            // Extensionless live endpoints are most commonly HLS or MPEG-TS.
            // Try HLS explicitly first, then let Media3 sniff a progressive stream.
            if (mime == null && kind == MediaKind.LIVE) {
                listOf(
                    StreamCandidate(candidateUrl, androidx.media3.common.MimeTypes.APPLICATION_M3U8),
                    StreamCandidate(candidateUrl, null)
                )
            } else {
                listOf(StreamCandidate(candidateUrl, mime))
            }
        }.distinctBy { it.url to it.mimeType }
    }

    data class StreamCandidate(
        val url: String,
        val mimeType: String? = null
    )
}
