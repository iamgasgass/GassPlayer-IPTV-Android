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
    private val streamExtensions = listOf("m3u8", "ts", "mp4", "mkv", "avi", "mov", "m4v", "webm", "flv", "wmv", "mpg", "mpeg")
    private const val maxAlternatives = 12

    fun ordered(url: String): List<String> {
        val baseUrls = protocolAlternatives(url)
        val expanded = linkedSetOf<String>()
        for (base in baseUrls) {
            expanded += base
            val uri = runCatching { URI(base) }.getOrNull() ?: continue
            val path = uri.path ?: continue
            val filename = path.substringAfterLast('/')
            val dot = filename.lastIndexOf('.')
            if (dot > 0) {
                val current = filename.substring(dot + 1).lowercase()
                val folder = base.substringBeforeLast('/')
                val stem = filename.substring(0, dot)
                streamExtensions.filter { it != current }.take(maxAlternatives).forEach { ext ->
                    expanded += "$folder/$stem.$ext"
                }
            }
            if (expanded.size >= maxAlternatives + baseUrls.size) break
        }
        return expanded.take(maxAlternatives + baseUrls.size)
    }

    private fun protocolAlternatives(url: String): List<String> {
        val normalized = url.trim()
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return listOf(normalized)
        val scheme = uri.scheme?.lowercase() ?: return listOf(normalized)
        val opposite = when (scheme) {
            "https" -> "http"
            "http" -> "https"
            else -> return listOf(normalized)
        }
        val swappedPort = when {
            scheme == "https" && (uri.port == -1 || uri.port == 443) -> 80
            scheme == "http" && uri.port == 80 -> 443
            else -> uri.port
        }
        val swapped = runCatching {
            URI(opposite, uri.userInfo, uri.host, swappedPort, uri.path, uri.query, uri.fragment).toString()
        }.getOrNull()
        return listOfNotNull(normalized, swapped).distinct()
    }
}
