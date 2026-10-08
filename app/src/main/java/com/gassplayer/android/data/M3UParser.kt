package com.gassplayer.android.data

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import kotlinx.serialization.json.jsonObject

/**
 * Defensive M3U/M3U8 parser for IPTV lists found in the wild.
 *
 * Supported patterns include:
 * - UTF-8 BOM / CRLF / blank lines / comments
 * - EXTINF attributes (quoted or unquoted)
 * - EXTGRP, EXTVLCOPT, EXTHTTP and common KODIPROP directives
 * - per-entry User-Agent / Referer / Origin / Cookie headers
 * - inline pipe headers: url|User-Agent=...&Referer=...|Origin=...
 * - relative and protocol-relative stream URLs resolved against the playlist URL
 * - HLS master/media manifests without EXTINF (kept as a single HLS item so Media3 can
 *   perform adaptive variant selection itself)
 */
object M3UParser {
    private val seasonEpisode = Regex("(?i)\\bS(\\d{1,3})[\\s._-]*E(\\d{1,3})\\b")
    private val seasonXEpisode = Regex("(?i)\\b(\\d{1,3})\\s*x\\s*(\\d{1,3})\\b")
    private val seasonEpisodeWords = Regex("(?i)\\bSeason\\s*(\\d{1,3})\\s*(?:Episode|Ep|E)\\s*(\\d{1,3})\\b")
    private val episodeOnly = Regex("(?i)^(?:Episode|Ep)\\s*[-._#: ]*?(\\d{1,3})\\b")
    private val headerNamePattern = Regex("(?i)^(user-agent|referer|referrer|origin|cookie|authorization|accept|accept-language)$")

    private data class EpisodeMarker(val season: Int?, val episode: Int?)

    fun parse(
        sourceId: String,
        text: String,
        baseUrl: String? = null,
        defaultHeaders: Map<String, String> = emptyMap()
    ): List<MediaItem> {
        val cleaned = text.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimStart()
        val lines = cleaned.lines()

        // An HLS .m3u8 is itself a media/multivariant manifest. Its segment URIs must not be
        // mistaken for IPTV channels. Feed the manifest URL unchanged to Media3 so HLS can
        // resolve variants, segments, keys and live refreshes according to the HLS rules.
        if (baseUrl != null && lines.any { it.trim().startsWith("#EXT-X-", true) }) {
            val manifestUrl = resolveUrl(baseUrl, null)
            val title = displayNameFromUrl(manifestUrl)
            return listOf(
                MediaItem(
                    id = stableId("$sourceId|$manifestUrl|hls-manifest"),
                    sourceId = sourceId,
                    kind = MediaKind.LIVE,
                    title = title,
                    streamUrl = manifestUrl,
                    number = 1,
                    streamHeaders = defaultHeaders,
                    streamMimeType = "application/vnd.apple.mpegurl"
                )
            )
        }
        val result = ArrayList<MediaItem>()
        var pendingAttrs: Map<String, String> = emptyMap()
        var pendingHeaders = linkedMapOf<String, String>().apply { putAll(defaultHeaders) }
        var pendingTitle = ""
        var pendingGroup: String? = null
        var pendingMime: String? = null
        var index = 0

        for (lineRaw in lines) {
            val line = lineRaw.trim()
            if (line.isBlank()) continue
            when {
                line.startsWith("#EXTINF", true) -> {
                    val payload = line.substringAfter(':', "")
                    val (attributePart, titlePart) = splitExtInf(payload)
                    pendingAttrs = parseAttributes(attributePart)
                    pendingTitle = cleanText(titlePart).ifBlank { pendingAttrs["tvg-name"].orEmpty() }
                    pendingGroup = pendingAttrs["group-title"]?.trim()?.ifBlank { null }
                    pendingHeaders.putAll(headersFromAttributes(pendingAttrs))
                    pendingMime = pendingAttrs["mimetype"]?.trim()?.takeIf { it.isNotBlank() }
                        ?: pendingAttrs["mime-type"]?.trim()?.takeIf { it.isNotBlank() }
                    continue
                }

                line.startsWith("#EXTGRP:", true) -> {
                    pendingGroup = line.substringAfter(':', "").trim().ifBlank { pendingGroup }
                    continue
                }

                line.startsWith("#EXTVLCOPT:", true) -> {
                    val payload = line.substringAfter(':', "")
                    parseDirective(payload, pendingHeaders)?.let { directive ->
                        if (directive.mimeType != null) pendingMime = directive.mimeType
                    }
                    continue
                }

                line.startsWith("#EXTHTTP:", true) -> {
                    val payload = line.substringAfter(':', "").trim()
                    pendingHeaders.putAll(parseExtHttpHeaders(payload))
                    continue
                }

                line.startsWith("#KODIPROP:", true) -> {
                    val payload = line.substringAfter(':', "")
                    parseKodiProp(payload, pendingHeaders)?.let { directive ->
                        if (directive.mimeType != null) pendingMime = directive.mimeType
                    }
                    continue
                }

                line.startsWith("#EXT-X-", true) || line.startsWith("#", true) -> continue

                else -> {
                    val parsedUrl = parseInlineHeaders(line)
                    val rawUrl = parsedUrl.first
                    if (rawUrl.isBlank()) continue

                    val url = resolveUrl(rawUrl, baseUrl)
                    val headers = linkedMapOf<String, String>().apply {
                        putAll(pendingHeaders)
                        putAll(parsedUrl.second)
                    }
                    val title = pendingTitle.ifBlank { displayNameFromUrl(url) }
                    val rawGroup = pendingGroup ?: pendingAttrs["group-title"]?.trim()?.ifBlank { null }
                    val seriesHint = listOf(
                        pendingAttrs["series-title"], pendingAttrs["series_name"], pendingAttrs["series"],
                        pendingAttrs["show"], pendingAttrs["show-title"], pendingAttrs["parent-title"]
                    ).firstOrNull { !it.isNullOrBlank() }?.trim()
                    val group = seriesHint ?: rawGroup
                    val logo = pendingAttrs["tvg-logo"]?.trim()?.ifBlank { null }
                    val tvgId = pendingAttrs["tvg-id"]?.trim()?.ifBlank { null }
                    val explicitType = pendingAttrs["tvg-type"]?.lowercase(Locale.ROOT)
                    val marker = episodeMarker(title, pendingAttrs, url)
                    val pathLower = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
                    val seriesPath = "/series/" in pathLower
                    val explicitEpisode = explicitType?.contains("episode") == true || explicitType?.contains("series_episode") == true
                    val isEpisode = explicitEpisode || marker != null || (seriesPath && episodeOnly.containsMatchIn(title))
                    val kind = when {
                        // Episode semantics must win over broad group names such as "Movies & Series".
                        isEpisode -> MediaKind.EPISODE
                        explicitType?.contains("movie") == true || group.containsAny("movie", "film") -> MediaKind.MOVIE
                        explicitType?.contains("series") == true || seriesPath -> MediaKind.SERIES
                        else -> MediaKind.LIVE
                    }
                    val id = stableId("$sourceId|$url|${title.lowercase(Locale.ROOT)}")
                    val seriesBase = seriesHint ?: normalizeSeriesTitle(title).ifBlank { group.orEmpty().trim().ifBlank { "Serie" } }
                    val explicitSeriesId = listOf("series-id", "series_id", "parent-id", "parent_id")
                        .asSequence().mapNotNull { pendingAttrs[it] }.firstOrNull { it.isNotBlank() }
                    val seriesKey = if (isEpisode) {
                        explicitSeriesId?.let { stableId("$sourceId|series-id|$it") }
                            ?: stableId("$sourceId|series|${normalizeSeriesTitle(seriesBase)}|${group.orEmpty().lowercase(Locale.ROOT)}")
                    } else null
                    val inferredMime = pendingMime ?: mimeTypeHintFrom(url, pendingAttrs)
                    result += MediaItem(
                        id = id,
                        sourceId = sourceId,
                        kind = kind,
                        title = title,
                        streamUrl = url,
                        logoUrl = logo,
                        group = group,
                        categoryId = group,
                        metadataTag = tvgId,
                        seasonNumber = marker?.season,
                        episodeNumber = marker?.episode,
                        seriesId = seriesKey,
                        number = index + 1,
                        streamHeaders = headers,
                        streamMimeType = inferredMime
                    )
                    index++
                    pendingAttrs = emptyMap()
                    pendingHeaders = linkedMapOf<String, String>().apply { putAll(defaultHeaders) }
                    pendingTitle = ""
                    pendingGroup = null
                    pendingMime = null
                }
            }
        }

        val existingSeries = result.filter { it.kind == MediaKind.SERIES }
        val normalizedSeriesKeys = existingSeries.associateBy { normalizeSeriesTitle(it.title) }
        val remapped = result.map { item ->
            if (item.kind != MediaKind.EPISODE) item else {
                val derivedTitle = normalizeSeriesTitle(item.title)
                val groupTitle = normalizeSeriesTitle(item.group.orEmpty())
                val explicit = normalizedSeriesKeys[derivedTitle] ?: normalizedSeriesKeys[groupTitle]
                if (explicit != null) item.copy(seriesId = explicit.id.substringAfterLast(':')) else item
            }
        }
        result.clear()
        result.addAll(remapped)

        val episodes = result.filter { it.kind == MediaKind.EPISODE && !it.seriesId.isNullOrBlank() }
        if (episodes.isNotEmpty()) {
            val existingTitles = result.filter { it.kind == MediaKind.SERIES }.mapTo(HashSet()) { normalizeSeriesTitle(it.title) }
            val synthetic = episodes.groupBy { it.seriesId!! }.mapNotNull { (key, eps) ->
                val first = eps.minWithOrNull(compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE })) ?: return@mapNotNull null
                val baseTitle = displaySeriesTitle(first.title, first.group ?: "Serie")
                if (normalizeSeriesTitle(baseTitle) in existingTitles) return@mapNotNull null
                MediaItem(
                    id = "$sourceId:series:$key", sourceId = sourceId, kind = MediaKind.SERIES, title = baseTitle, streamUrl = "",
                    logoUrl = first.logoUrl, posterUrl = first.logoUrl, group = first.group, categoryId = first.categoryId,
                    number = first.number, metadataTag = first.metadataTag
                )
            }
            result.addAll(synthetic)
        }
        return result.distinctBy { it.id }
    }

    private data class DirectiveResult(val mimeType: String? = null)

    private fun splitExtInf(payload: String): Pair<String, String> {
        var quoted = false
        var quoteChar = '\u0000'
        for (i in payload.indices) {
            val c = payload[i]
            if (c == '"' || c == '\'') {
                if (!quoted) {
                    quoted = true
                    quoteChar = c
                } else if (quoteChar == c) {
                    quoted = false
                }
            } else if (c == ',' && !quoted) {
                return payload.substring(0, i).trim() to payload.substring(i + 1).trim()
            }
        }
        return payload.trim() to ""
    }

    private fun parseAttributes(value: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val r = Regex("([\\w:-]+)\\s*=\\s*\\\"([^\\\"]*)\\\"|([\\w:-]+)\\s*=\\s*'([^']*)'|([\\w:-]+)\\s*=\\s*([^\\s,]+)")
        for (m in r.findAll(value)) {
            val key = m.groupValues[1].ifBlank { m.groupValues[3].ifBlank { m.groupValues[5] } }
            val raw = m.groupValues[2].ifBlank { m.groupValues[4].ifBlank { m.groupValues[6] } }
            out[key.lowercase(Locale.ROOT)] = decodeHeaderValue(raw)
        }
        return out
    }

    private fun headersFromAttributes(attrs: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        attrs.forEach { (keyRaw, value) ->
            val key = keyRaw.lowercase(Locale.ROOT)
            val header = when {
                key == "http-user-agent" || key == "user-agent" -> "User-Agent"
                key == "http-referrer" || key == "http-referer" || key == "referrer" || key == "referer" -> "Referer"
                key == "http-origin" || key == "origin" -> "Origin"
                key == "http-cookie" || key == "cookie" -> "Cookie"
                key == "authorization" -> "Authorization"
                else -> null
            }
            if (header != null && value.isNotBlank()) out[header] = value
        }
        return out
    }

    private fun parseDirective(payload: String, headers: MutableMap<String, String>): DirectiveResult? {
        val key = payload.substringBefore('=', "").trim().lowercase(Locale.ROOT)
        val value = payload.substringAfter('=', "").trim().trim('"')
        if (value.isBlank()) return DirectiveResult()
        when (key) {
            "http-user-agent" -> headers["User-Agent"] = value
            "http-referrer", "http-referer" -> headers["Referer"] = value
            "http-origin" -> headers["Origin"] = value
            "http-cookie" -> headers["Cookie"] = value
        }
        return DirectiveResult()
    }

    private fun parseKodiProp(payload: String, headers: MutableMap<String, String>): DirectiveResult? {
        val key = payload.substringBefore('=', "").trim().lowercase(Locale.ROOT)
        val value = payload.substringAfter('=', "").trim().trim('"')
        if (value.isBlank()) return DirectiveResult()
        return when {
            key.endsWith("stream_headers") || key.endsWith("manifest_headers") || key.endsWith("http_headers") -> {
                headers.putAll(parseHeaderAssignments(value))
                DirectiveResult()
            }
            key.endsWith("http-referrer") || key.endsWith("http-referer") -> {
                headers["Referer"] = value
                DirectiveResult()
            }
            key.endsWith("http-user-agent") -> {
                headers["User-Agent"] = value
                DirectiveResult()
            }
            key.contains("mimetype") -> DirectiveResult(value)
            key.endsWith("manifest_type") -> DirectiveResult(
                when (value.lowercase(Locale.ROOT)) {
                    "hls", "m3u8", "m3u" -> "application/vnd.apple.mpegurl"
                    "dash", "mpd" -> "application/dash+xml"
                    else -> null
                }
            )
            else -> DirectiveResult()
        }
    }

    private fun parseExtHttpHeaders(payload: String): Map<String, String> {
        val text = payload.trim()
        if (text.startsWith("{")) {
            val parsed = runCatching { JsonStore.json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (parsed != null) {
                return parsed.mapNotNull { (k, v) ->
                    val value = v.toString().trim().trim('"')
                    normalizeHeaderName(k)?.let { it to value }.takeIf { value.isNotBlank() }
                }.toMap()
            }
        }
        return parseHeaderAssignments(text)
    }

    private fun parseHeaderAssignments(value: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val names = "user-agent|referer|referrer|origin|cookie|authorization|accept|accept-language|http-user-agent|http-referrer|http-referer|http-origin|http-cookie"
        val pattern = Regex("(?i)(?:^|[&|])\\s*($names)\\s*=\\s*(\"[^\"]*\"|'[^']*'|.*?)(?=(?:&(?:$names)\\s*=)|$)")
        for (match in pattern.findAll(value)) {
            val normalized = normalizeHeaderName(match.groupValues[1]) ?: continue
            val rawValue = match.groupValues[2].trim().trim('"', '\'')
            if (rawValue.isNotBlank()) out[normalized] = decodeHeaderValue(rawValue)
        }
        if (out.isEmpty() && value.contains('=')) {
            val key = value.substringBefore('=', "").trim()
            val rawValue = value.substringAfter('=', "").trim().trim('"', '\'')
            normalizeHeaderName(key)?.let { out[it] = decodeHeaderValue(rawValue) }
        }
        return out
    }

    private fun parseInlineHeaders(line: String): Pair<String, Map<String, String>> {
        val parts = line.split('|')
        val url = cleanText(parts.firstOrNull().orEmpty())
        if (parts.size == 1) return url to emptyMap()
        val headers = linkedMapOf<String, String>()
        parts.drop(1).forEach { part -> headers.putAll(parseHeaderAssignments(part)) }
        return url to headers
    }

    private fun normalizeHeaderName(raw: String): String? {
        val compact = raw.trim().removePrefix("http-").lowercase(Locale.ROOT)
        return when {
            headerNamePattern.matches(raw.trim()) -> when (compact) {
                "user-agent" -> "User-Agent"
                "referer", "referrer" -> "Referer"
                else -> raw.trim().replaceFirstChar { it.uppercase(Locale.ROOT) }
            }
            compact == "user-agent" -> "User-Agent"
            compact == "referer" || compact == "referrer" -> "Referer"
            compact == "origin" -> "Origin"
            compact == "cookie" -> "Cookie"
            compact == "authorization" -> "Authorization"
            compact == "accept" -> "Accept"
            compact == "accept-language" -> "Accept-Language"
            else -> null
        }
    }

    private fun resolveUrl(raw: String, baseUrl: String?): String {
        val normalized = cleanText(raw).replace("&amp;", "&")
        if (normalized.isBlank()) return normalized
        val absolute = runCatching { URI(normalized) }.getOrNull()
        if (absolute?.scheme != null) return normalized
        if (normalized.startsWith("//")) {
            val scheme = runCatching { URI(baseUrl.orEmpty()).scheme }.getOrNull()?.takeIf { it.isNotBlank() } ?: "http"
            return "$scheme:$normalized"
        }
        if (baseUrl.isNullOrBlank()) return normalized
        return runCatching { URI(baseUrl).resolve(URI(normalized)).toString() }.getOrDefault(normalized)
    }

    private fun cleanText(value: String): String = value.trim().trim('\uFEFF', '"', '\'')

    private fun decodeHeaderValue(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    private fun mimeTypeHintFrom(url: String, attrs: Map<String, String>): String? {
        val explicit = attrs.entries.firstOrNull { it.key.lowercase(Locale.ROOT) in setOf("mimetype", "mime-type", "stream-type", "format", "output", "manifest_type") }?.value?.lowercase(Locale.ROOT)
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(url.lowercase(Locale.ROOT))
        val query = runCatching { URI(url).rawQuery.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val hint = listOf(explicit.orEmpty(), path, query).joinToString(" ")
        return when {
            "m3u8" in hint || "hls" in hint || "manifest" in hint && ("hls" in hint || "m3u" in hint) -> "application/vnd.apple.mpegurl"
            "mpd" in hint || "dash" in hint -> "application/dash+xml"
            path.endsWith(".ts") || path.endsWith(".mts") -> "video/mp2t"
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> "video/mp4"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".aac") -> "audio/aac"
            else -> null
        }
    }

    private fun displayNameFromUrl(url: String): String = runCatching {
        val uri = URI(url)
        val name = uri.path.substringAfterLast('/').substringBeforeLast('?').ifBlank { uri.host.orEmpty() }
        name.removeSuffix(".m3u8").removeSuffix(".m3u").ifBlank { uri.host.orEmpty() }.ifBlank { url }
    }.getOrDefault(url)

    private fun episodeMarker(title: String, attrs: Map<String, String>, url: String? = null): EpisodeMarker? {
        val explicitSeason = listOf("season", "season-number", "season_num", "season-numbering")
            .asSequence().mapNotNull { attrs[it]?.toIntOrNull() }.firstOrNull()
        val explicitEpisode = listOf("episode", "episode-num", "episode_num", "episode-number", "episode_number")
            .asSequence().mapNotNull { attrs[it]?.toIntOrNull() }.firstOrNull()
        if (explicitSeason != null || explicitEpisode != null) return EpisodeMarker(explicitSeason, explicitEpisode)

        seasonEpisode.find(title)?.let { return EpisodeMarker(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull()) }
        seasonEpisodeWords.find(title)?.let { return EpisodeMarker(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull()) }
        seasonXEpisode.find(title)?.let { return EpisodeMarker(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull()) }
        url?.let { stream ->
            val file = runCatching { URI(stream).path.orEmpty().substringAfterLast('/') }.getOrDefault("")
            seasonEpisode.find(file)?.let { return EpisodeMarker(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull()) }
            seasonEpisodeWords.find(file)?.let { return EpisodeMarker(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull()) }
            seasonXEpisode.find(file)?.let { return EpisodeMarker(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull()) }
        }
        return null
    }

    private fun stripEpisodeMarker(title: String): String {
        val matches = listOfNotNull(seasonEpisode.find(title), seasonEpisodeWords.find(title), seasonXEpisode.find(title))
        val marker = matches.minByOrNull { it.range.first }
        return marker?.let { title.substring(0, it.range.first) } ?: title
    }

    private fun displaySeriesTitle(title: String, fallback: String): String {
        return stripEpisodeMarker(title).replace(Regex("[\\s._:-]+$"), "").trim().ifBlank { fallback }
    }

    private fun normalizeSeriesTitle(title: String): String {
        return stripEpisodeMarker(title).replace(Regex("[\\s._:-]+$"), "").trim().lowercase(Locale.ROOT)
    }

    private fun String?.containsAny(vararg terms: String): Boolean = this?.let { s -> terms.any { s.contains(it, true) } } == true
    private fun stableId(text: String): String = text.fold(1125899906842597L) { hash, c -> 31L * hash + c.code }.toString()
}
