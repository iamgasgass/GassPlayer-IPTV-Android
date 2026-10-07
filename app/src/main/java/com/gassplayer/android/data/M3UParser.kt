package com.gassplayer.android.data

import java.net.URLDecoder
import java.util.regex.Pattern

object M3UParser {
    private val seasonEpisode = Pattern.compile("(?i)\\bS(\\d{1,3})E(\\d{1,3})\\b")
    fun parse(sourceId: String, text: String): List<MediaItem> {
        val lines = text.replace("\r", "").lines()
        val result = ArrayList<MediaItem>()
        var pending: Map<String, String> = emptyMap()
        var pendingTitle = ""
        var index = 0
        for (lineRaw in lines) {
            val line = lineRaw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#EXTINF", true)) {
                val attrs = parseAttributes(line.substringAfter(":", ""))
                pending = attrs
                pendingTitle = line.substringAfterLast(",", "").trim().ifBlank { attrs["tvg-name"].orEmpty() }
                continue
            }
            if (line.startsWith("#")) continue
            val url = line
            if (pendingTitle.isBlank()) pendingTitle = url
            val group = pending["group-title"]?.trim()?.ifBlank { null }
            val logo = pending["tvg-logo"]?.trim()?.ifBlank { null }
            val tvgId = pending["tvg-id"]?.trim()?.ifBlank { null }
            val explicitType = pending["tvg-type"]?.lowercase()
            val matcher = seasonEpisode.matcher(pendingTitle)
            val match = matcher.takeIf { it.find() }
            val kind = when {
                explicitType?.contains("movie") == true || group?.containsAny("movie", "film") == true -> MediaKind.MOVIE
                explicitType?.contains("series") == true || match != null -> MediaKind.SERIES
                else -> MediaKind.LIVE
            }
            val id = stableId("$sourceId|$url|${pendingTitle.lowercase()}")
            result += MediaItem(id, sourceId, kind, pendingTitle, url, logoUrl = logo, group = group, metadataTag = tvgId, seasonNumber = match?.group(1)?.toIntOrNull(), episodeNumber = match?.group(2)?.toIntOrNull(), number = index + 1)
            index++
            pending = emptyMap(); pendingTitle = ""
        }
        return result.distinctBy { it.id }
    }

    private fun parseAttributes(value: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val r = Regex("([\\w:-]+)=\\\"([^\\\"]*)\\\"|([\\w:-]+)=([^\\s,]+)")
        for (m in r.findAll(value)) {
            val key = m.groupValues[1].ifBlank { m.groupValues[3] }
            val raw = m.groupValues[2].ifBlank { m.groupValues[4] }
            out[key.lowercase()] = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }
        return out
    }

    private fun String?.containsAny(vararg terms: String): Boolean = this?.let { s -> terms.any { s.contains(it, true) } } == true
    private fun stableId(text: String): String = text.fold(1125899906842597L) { hash, c -> 31L * hash + c.code }.toString()
}
