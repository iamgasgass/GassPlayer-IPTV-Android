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
        val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url)
        val parts = uri.path?.split('/').orEmpty().filter { it.isNotBlank() }
        if (parts.size < 4) return listOf(url)
        val kind = parts[parts.size - 4].lowercase()
        if (kind !in setOf("movie", "series", "live")) return listOf(url)
        val filename = parts.last()
        val dot = filename.lastIndexOf('.')
        if (dot <= 0) return listOf(url)
        val base = filename.substring(0, dot)
        val current = filename.substring(dot + 1).lowercase()
        val order = if (kind == "live") liveExtensions else vodExtensions
        val folder = url.substringBeforeLast('/')
        val alternatives = order.filter { it != current }.take(maxAlternatives).map { "$folder/$base.$it" }
        return listOf(url) + alternatives
    }
}
