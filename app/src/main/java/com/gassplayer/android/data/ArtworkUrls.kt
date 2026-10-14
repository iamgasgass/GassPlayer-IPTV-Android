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

    /**
     * First candidate that is a usable image URL (cleaned). Unlike `a ?: b ?: c`, a non-empty but unusable
     * value (`"none"`, `"0"`, a broken path) does not hide a valid fallback field.
     */
    fun firstValid(base: String?, vararg candidates: String?): String? {
        for (candidate in candidates) clean(candidate, base)?.let { return it }
        return null
    }

    /**
     * Cleans the artwork of a catalog item and fills the field each view reads:
     *  - LIVE views read `logoUrl` -> falls back to the poster when the provider only sent that;
     *  - VOD/series/episode views read `posterUrl` -> falls back to `logoUrl` (M3U `tvg-logo`, which the
     *    parser stores as logo for every kind: movies/series from M3U playlists had no poster at all).
     * Returns the same instance when nothing changes (no allocation on the hot path).
     */
    fun normalize(item: MediaItem): MediaItem {
        val base = bases[item.sourceId]
        val logo = clean(item.logoUrl, base)
        val poster = clean(item.posterUrl, base)
        val backdrop = clean(item.backdropUrl, base)
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
        // Fast path: an already valid absolute URL (the vast majority) is returned untouched.
        if (isCleanAbsolute(text)) return text

        text = text.replace("\\/", "/")
        if (!text.contains("://")) text = text.replace('\\', '/')

        val lower = text.lowercase(Locale.ROOT)
        text = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> text
            text.startsWith("//") -> "${schemeOf(base) ?: "http"}:$text"
            lower.startsWith("data:") || lower.startsWith("file:") || lower.startsWith("content:") -> return null
            looksLikeHost(text) -> "http://$text"
            else -> resolveRelative(text, base) ?: return null
        }

        val encoded = encode(text)
        val uri = runCatching { java.net.URI(encoded) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) return null
        return encoded
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
        return runCatching { !java.net.URI(text).host.isNullOrEmpty() }.getOrDefault(false)
    }

    /** `cdn.example.com/logo.png` (no scheme): first segment is a hostname with a TLD. */
    private fun looksLikeHost(text: String): Boolean {
        val slash = text.indexOf('/')
        if (slash <= 0) return false
        val host = text.substring(0, slash).substringBefore(':')
        if (!host.contains('.')) return false
        val tld = host.substringAfterLast('.')
        return tld.length in 2..12 && tld.all { it.isLetter() } && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    private fun schemeOf(base: String?): String? =
        base?.substringBefore("://", "")?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" }

    /** `/x.png` -> server origin + path; `x.png` -> resolved against the playlist/server URL. */
    private fun resolveRelative(path: String, base: String?): String? {
        if (base.isNullOrBlank()) return null
        val baseUri = runCatching { java.net.URI(encode(base)) }.getOrNull() ?: return null
        val scheme = baseUri.scheme ?: return null
        val host = baseUri.host ?: return null
        val origin = buildString {
            append(scheme).append("://").append(host)
            if (baseUri.port > 0) append(':').append(baseUri.port)
        }
        if (path.startsWith("/")) return origin + path
        return runCatching { baseUri.resolve(java.net.URI(encode(path))).toString() }.getOrNull()
            ?: "$origin/$path"
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
