package com.gassplayer.android.data

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns the raw `stream_icon` / `cover` / `tvg-logo` values of IPTV providers into URLs an image loader
 * can actually download (port of iOS `PosterImageStore.url(from:baseHost:)`). Panels often return:
 *  - paths relative to the server (`/images/x.png`, `images/x.png`) or to the playlist;
 *  - protocol-relative URLs (`//cdn.host/x.png`) and hosts without scheme (`cdn.host.com/x.png`);
 *  - escaped slashes (`http:\/\/host\/x.png`) and Windows backslashes;
 *  - spaces / non-ASCII characters that are not percent-encoded (file names with spaces, accents);
 *  - text placeholders (`null`, `none`, `n/a`, `0`, `false`…).
 * Each of these used to leave the channel/poster cell empty.
 *
 * Catalog rows are normalized when they are read from SQLite ([normalize]), so playlists already in the
 * cache are fixed too, without downloading them again.
 */
object ArtworkUrls {
    private val placeholders = setOf("null", "none", "n/a", "na", "nil", "0", "false", "undefined", "-", "about:blank")

    /** Base used to resolve relative artwork paths, per source id (Xtream server / M3U playlist URL). */
    private val bases = ConcurrentHashMap<String, String>()

    fun registerSources(sources: List<MediaSourceConfig>) {
        for (source in sources) {
            val base = when (source.type) {
                SourceType.XTREAM -> runCatching { XtreamRepository.serverBase(source.host) }.getOrNull()
                SourceType.M3U8 -> NetworkApi.stripInlineHeaders(source.playlistUrl ?: source.host)
                else -> source.host
            }?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            if (base != null) bases[source.id] = base else bases.remove(source.id)
        }
    }

    fun baseFor(sourceId: String): String? = bases[sourceId]

    /** Diagnostics hook: a non-empty provider value dropped as unusable (title, raw value). Set by the Application. */
    @Volatile var onDiscarded: ((String, String) -> Unit)? = null

    private fun cleanReporting(item: MediaItem, raw: String?, base: String?): String? {
        val cleaned = clean(raw, base)
        if (cleaned == null && !raw.isNullOrBlank() && raw.trim().lowercase(Locale.ROOT) !in placeholders) {
            onDiscarded?.let { report -> runCatching { report(item.title, raw + if (base == null) " (base sorgente non registrata)" else "") } }
        }
        return cleaned
    }

    /**
     * First candidate that is a usable image URL (cleaned). Unlike `a ?: b ?: c`, a non-empty but unusable
     * value (`"none"`, `"0"`, a broken path) does not hide a valid fallback field.
     */
    fun firstValid(base: String?, vararg candidates: String?): String? {
        for (candidate in candidates) clean(candidate, base)?.let { return it }
        return null
    }

    /**
     * Like [firstValid] but returns the provider's ORIGINAL value (trimmed): what is stored in the catalog stays
     * raw and is cleaned on read, so a future fix of [clean] applies to cached playlists without a re-download.
     */
    fun firstUsable(base: String?, vararg candidates: String?): String? =
        candidates.firstOrNull { clean(it, base) != null }?.trim()

    /**
     * Cleans the artwork of a catalog item and fills the field each view reads:
     *  - LIVE views read `logoUrl` -> falls back to the poster when the provider only sent that;
     *  - VOD/series/episode views read `posterUrl` -> falls back to `logoUrl` (M3U `tvg-logo`, which the
     *    parser stores as logo for every kind: movies/series from M3U playlists had no poster at all).
     * Returns the same instance when nothing changes (no allocation on the hot path).
     */
    fun normalize(item: MediaItem): MediaItem {
        val base = bases[item.sourceId]
        val logo = cleanReporting(item, item.logoUrl, base)
        val poster = cleanReporting(item, item.posterUrl, base)
        val backdrop = cleanReporting(item, item.backdropUrl, base)
        val finalLogo = if (item.kind == MediaKind.LIVE) logo ?: poster else logo
        val finalPoster = if (item.kind == MediaKind.LIVE) poster else poster ?: logo
        if (finalLogo == item.logoUrl && finalPoster == item.posterUrl && backdrop == item.backdropUrl) return item
        return item.copy(logoUrl = finalLogo, posterUrl = finalPoster, backdropUrl = backdrop)
    }

    /** Downloadable http(s) URL for [raw], or null when it is empty, a placeholder or unusable. */
    fun clean(raw: String?, base: String? = null): String? {
        if (raw == null) return null
        var text = raw.trim().trim('"', '\'').trim()
        if (text.isEmpty() || text.lowercase(Locale.ROOT) in placeholders) return null
        // Poster incorporato dal provider (`data:image/jpeg;base64,...`): Coil lo decodifica senza rete.
        if (text.regionMatches(0, "data:image/", 0, 11, ignoreCase = true)) {
            return text.filterNot { it.isWhitespace() }.takeIf { it.contains(',') && it.length > 32 }
        }
        // Fast path: an already valid absolute URL (the vast majority) is returned untouched.
        if (isCleanAbsolute(text)) return text

        text = text.replace("\\/", "/")
        if (!text.contains("://")) text = text.replace('\\', '/')
        // Kodi-style headers appended to the image (`logo.png|User-Agent=...`) are not part of the address.
        if ('|' in text && text.substringAfter('|').contains('=')) text = text.substringBefore('|').trim()
        if (text.isEmpty()) return null

        val lower = text.lowercase(Locale.ROOT)
        text = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> text
            text.startsWith("//") -> "${schemeOf(base) ?: "http"}:$text"
            lower.startsWith("data:") || lower.startsWith("file:") || lower.startsWith("content:") -> return null
            looksLikeHost(text) -> "http://$text"
            else -> resolveRelative(text, base) ?: return null
        }
        return rebuild(text)
    }

    /**
     * Pieces of an absolute http(s) URL. Parsed by hand on purpose: `java.net.URI` rejects host names that
     * OkHttp/Coil (and iOS `URL`) download without problems, e.g. `img_cdn.provider.tv` (underscore) or
     * non-ASCII (IDN) hosts, and those channel logos / posters were silently dropped.
     */
    private class UrlParts(val scheme: String, val userInfo: String?, val host: String, val port: String?, val rest: String) {
        val origin: String get() = buildString {
            append(scheme).append("://")
            if (userInfo != null) append(userInfo).append('@')
            append(host)
            if (port != null) append(':').append(port)
        }
    }

    private fun split(url: String): UrlParts? {
        val sep = url.indexOf("://")
        if (sep <= 0) return null
        val scheme = url.substring(0, sep).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val authorityStart = sep + 3
        var authorityEnd = url.length
        for (i in authorityStart until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') { authorityEnd = i; break }
        }
        val authority = url.substring(authorityStart, authorityEnd)
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else null
        val hostPort = authority.substring(at + 1)
        val rawHost: String
        val port: String?
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            rawHost = hostPort.substring(0, close + 1)
            port = hostPort.substring(close + 1).removePrefix(":").ifEmpty { null }
        } else {
            val colon = hostPort.lastIndexOf(':')
            rawHost = if (colon >= 0) hostPort.substring(0, colon) else hostPort
            port = if (colon >= 0) hostPort.substring(colon + 1).ifEmpty { null } else null
        }
        if (port != null && (port.length > 5 || !port.all { it in '0'..'9' })) return null
        val host = if (rawHost.startsWith("[")) rawHost else {
            if (rawHost.isEmpty()) return null
            val ascii = if (rawHost.all { it.code < 128 }) rawHost
                else runCatching { java.net.IDN.toASCII(rawHost, java.net.IDN.ALLOW_UNASSIGNED) }.getOrNull() ?: return null
            // Same host characters OkHttp accepts for a request (underscore included).
            if (ascii.isEmpty() || !ascii.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '.' || it == '-' || it == '_' }) return null
            ascii
        }
        return UrlParts(scheme, userInfo?.let { encode(it) }, host, port, url.substring(authorityEnd))
    }

    /** Absolute URL -> canonical downloadable form (host checked/IDN-converted, the rest percent-encoded once). */
    private fun rebuild(url: String): String? {
        val parts = split(url) ?: return null
        return parts.origin + encode(parts.rest)
    }

    private fun isCleanAbsolute(text: String): Boolean {
        val lower = text.regionMatches(0, "http://", 0, 7, ignoreCase = true) || text.regionMatches(0, "https://", 0, 8, ignoreCase = true)
        if (!lower) return false
        for (c in text) if (!isAllowed(c)) return false
        // Stray '%' not followed by two hex digits would be rejected by the loader.
        var i = text.indexOf('%')
        while (i >= 0) {
            if (i + 2 >= text.length || !text[i + 1].isHex() || !text[i + 2].isHex()) return false
            i = text.indexOf('%', i + 1)
        }
        val parts = split(text) ?: return false
        // Untouched only when nothing would change (scheme case, IDN host...).
        return text.startsWith(parts.origin)
    }

    /** `cdn.example.com/logo.png` (no scheme): first segment is a hostname with a TLD. */
    private fun looksLikeHost(text: String): Boolean {
        val slash = text.indexOf('/')
        if (slash <= 0) return false
        val host = text.substring(0, slash).substringBefore(':')
        if (!host.contains('.')) return false
        val tld = host.substringAfterLast('.')
        return tld.length in 2..12 && tld.all { it.isLetter() } && host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }

    private fun schemeOf(base: String?): String? =
        base?.substringBefore("://", "")?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" }

    /**
     * `/x.png` -> server origin + path (iOS `PosterImageStore.url`); `x.png` -> next to the playlist file
     * (for an Xtream server, whose base has no path, that is the server root as on iOS).
     */
    private fun resolveRelative(path: String, base: String?): String? {
        if (base.isNullOrBlank()) return null
        val parts = split(base.trim()) ?: return null
        val origin = parts.scheme + "://" + parts.host + (parts.port?.let { ":$it" } ?: "")
        if (path.startsWith("/")) return origin + path
        val basePath = parts.rest.substringBefore('#').substringBefore('?')
        val dir = if (basePath.isEmpty()) "/" else basePath.substringBeforeLast('/', "") + "/"
        val segments = ArrayList<String>()
        for (segment in (dir + path).split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments += segment
            }
        }
        return origin + "/" + segments.joinToString("/") + if (path.endsWith("/")) "/" else ""
    }

    private fun isAllowed(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in "!#$&'()*+,/:;=?@%-._~"

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Percent-encodes what is not URL-safe, never re-encoding existing `%XX` escapes. */
    private fun encode(text: String): String {
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '%' && i + 2 < text.length && text[i + 1].isHex() && text[i + 2].isHex() -> out.append(c)
                c == '%' -> out.append("%25")
                isAllowed(c) -> out.append(c)
                else -> {
                    val cp = text.codePointAt(i)
                    for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                        out.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF]).append("0123456789ABCDEF"[b.toInt() and 0xF])
                    }
                    i += Character.charCount(cp)
                    continue
                }
            }
            i++
        }
        return out.toString()
    }
}
