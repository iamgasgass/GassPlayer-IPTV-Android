package com.gassplayer.android.data

import java.net.URI

/**
 * IPTV playback candidate ladder. It deliberately keeps query strings/tokens untouched,
 * but also knows the common Xtream HTTP/HTTPS port pairs and output-container variants.
 */
object StreamUrlCandidates {
    val userAgentLadder = listOf(
        "VLC/3.0.20 LibVLC/3.0.20",
        "Lavf/60.16.100",
        "IPTVSmartersPro",
        "okhttp/4.12.0",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    )

    fun ordered(url: String): List<String> {
        val baseCandidates = NetworkApi.candidateUrls(url)
        val result = LinkedHashSet<String>()
        // Keep the provider's original URL and all transport/port candidates first.
        // Container variants are added afterwards so a valid protocol is never crowded out.
        baseCandidates.forEach(result::add)
        containerAlternatives(baseCandidates).forEach(result::add)
        return result.toList().take(16)
    }

    private fun containerAlternatives(candidates: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        candidates.forEach { raw ->
            val uri = runCatching { URI(raw) }.getOrNull() ?: return@forEach
            val path = uri.rawPath.orEmpty()
            val lower = path.lowercase()
            val isXtream = listOf("/live/", "/movie/", "/series/").any { lower.contains(it) }
            val dot = path.lastIndexOf('.')
            val hasExtension = dot > path.lastIndexOf('/')
            val currentExt = if (hasExtension) path.substring(dot + 1).lowercase() else ""
            val replacements = when {
                "/live/" in lower -> listOf("m3u8", "ts")
                "/series/" in lower -> listOf("mp4", "mkv", "ts", "m3u8")
                "/movie/" in lower -> listOf("mp4", "mkv", "avi", "ts", "m3u8")
                !hasExtension && ("m3u8" in raw.lowercase() || "hls" in raw.lowercase() || "manifest" in raw.lowercase()) -> listOf("m3u8", "ts")
                !hasExtension -> listOf("m3u8", "ts", "mp4", "mkv")
                else -> emptyList()
            }
            if (!isXtream && hasExtension && replacements.isEmpty()) return@forEach
            replacements.filterNot { it == currentExt }.forEach { ext ->
                val newPath = if (hasExtension) path.substring(0, dot + 1) + ext else "$path.$ext"
                out += buildUrl(uri, newPath)
            }
        }
        return out.toList()
    }

    private fun buildUrl(uri: URI, path: String): String {
        val authority = uri.rawAuthority ?: return uri.toString()
        return buildString {
            append(uri.scheme).append("://").append(authority)
            append(path)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }
}
