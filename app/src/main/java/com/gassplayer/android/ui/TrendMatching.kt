package com.gassplayer.android.ui

import com.gassplayer.android.data.MediaItem
import com.gassplayer.android.data.MediaKind
import com.gassplayer.android.data.MetadataResult
import com.gassplayer.android.data.matchingTitle
import com.gassplayer.android.data.sameTitleAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * Matches a TMDB trending title to the playlist catalogue, quickly and conservatively.
 *
 * Speed: candidates are fetched with a title keyword filter (SQL LIKE for the disk-backed catalogue),
 * off the main thread, instead of decoding the whole catalogue row by row on the UI thread.
 * Accuracy: TMDB id first, then the exact cleaned title (playlist prefixes/suffixes such as "IT:",
 * "[ITA]", "HD", "4K" removed), then the year; a partial title match is accepted only when it is
 * almost identical or the year confirms it, and a different year rejects remakes. When nothing is
 * reliable the caller shows the "not in catalogue" notice instead of opening a wrong title.
 */
internal data class TrendQuery(val tmdbId: String, val titles: List<String>, val year: String?)

internal fun MetadataResult.toTrendQuery(): TrendQuery =
    TrendQuery(
        tmdbId = id,
        titles = listOfNotNull(title, originalTitle).map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        year = year,
    )

private val bracketed = Regex("""[\[(｛{][^\])｝}]*[\])｝}]""")
private val leadingTag = Regex("""^\s*(?:[a-z]{2,3}|[a-z]{2,3}[\s-]?(?:hd|fhd|uhd|4k))\s*[:|]\s*""")
private val yearToken = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
private val noiseTokens = setOf(
    "ita", "eng", "multi", "sub", "subita", "hd", "fhd", "uhd", "sd", "4k", "8k", "1080p", "720p", "2160p",
    "hevc", "h264", "h265", "x264", "x265", "hdr", "dv", "remux", "web", "webdl", "bluray", "vod", "film", "serie",
)

/** Comparable key: accents folded, playlist tags and quality words removed, letters and digits only. */
internal fun trendTitleKey(raw: String): String {
    var text = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")
    text = bracketed.replace(text, " ")
    text = leadingTag.replace(text, "")
    text = text.replace('&', ' ').replace("'", "").replace("’", "")
    val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() && it !in noiseTokens }
    // A trailing year is part of the release name, not of the title.
    val trimmed = if (words.size > 1 && yearToken.matches(words.last())) words.dropLast(1) else words
    return trimmed.joinToString("")
}

/** Year of a catalogue item: its metadata year, else a year written in its title. */
internal fun MediaItem.trendYear(): String? {
    val maxYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1
    fun String.plausibleYear(): Boolean = length == 4 && all { it.isDigit() } && toInt() in 1900..maxYear
    // A year-like number beyond next year is part of the title ("Blade Runner 2049"), not a release year;
    // and a title made only of digits ("1917", "2012") IS the title, not a year.
    val metadataYear = year?.trim()?.take(4)?.takeIf { it.plausibleYear() }
    if (metadataYear != null || title.none { it.isLetter() }) return metadataYear
    return yearToken.findAll(title).map { it.value }.lastOrNull { it.plausibleYear() }
}

/** Score of one candidate; 0 means "not the same title". */
internal fun trendMatchScore(item: MediaItem, query: TrendQuery): Int {
    if (!item.tmdbId.isNullOrBlank() && item.tmdbId == query.tmdbId) return 1_000
    val itemKey = trendTitleKey(item.title)
    if (itemKey.length < 2) return 0
    val itemYear = item.trendYear()
    val yearKnown = itemYear != null && query.year != null
    val yearDelta = if (yearKnown) kotlin.math.abs(itemYear!!.toInt() - query.year!!.toInt()) else 0
    // Same title but a clearly different year is a remake / another work: never a match.
    if (yearKnown && yearDelta > 1) return 0
    val yearBonus = if (yearKnown) 50 else 0
    var best = 0
    for (title in query.titles) {
        val key = trendTitleKey(title)
        if (key.length < 2) continue
        val score = when {
            key == itemKey -> 500
            minOf(key.length, itemKey.length) >= 5 && (itemKey.contains(key) || key.contains(itemKey)) -> {
                val extra = kotlin.math.abs(itemKey.length - key.length)
                // Partial matches only when nearly identical, or confirmed by the year.
                if (extra <= 4 || yearKnown) 200 - extra.coerceAtMost(150) else 0
            }
            else -> 0
        }
        if (score > 0) best = maxOf(best, score + yearBonus)
    }
    return best
}

/** Best reliable candidate, or null when the title is not in the catalogue. */
internal fun bestTrendMatch(candidates: List<MediaItem>, query: TrendQuery): MediaItem? {
    var bestItem: MediaItem? = null
    var bestScore = 0
    for (item in candidates) {
        val score = trendMatchScore(item, query)
        if (score > bestScore) { bestScore = score; bestItem = item }
        if (score >= 1_000) break
    }
    return bestItem
}

/** Keywords for the candidate prefilter: the longest plain word of each title (SQL LIKE friendly). */
internal fun trendKeywords(query: TrendQuery): List<String> =
    query.titles.mapNotNull { title ->
        val words = title.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it.lowercase(Locale.ROOT) !in noiseTokens }
        // Prefer a plain ASCII word: SQLite LIKE matches accented letters only literally.
        words.filter { word -> word.all { it.code < 128 } }.maxByOrNull { it.length }
            ?: words.maxByOrNull { it.length }
            ?: title.trim().takeIf { it.length >= 2 }
    }.distinctBy { it.lowercase(Locale.ROOT) }

/**
 * Finds [trend] in [catalogue] off the main thread. Disk-backed lists are filtered in SQL by keyword,
 * on a separate DbList instance, so the list the UI is reading is never touched from this thread.
 */
internal suspend fun findTrendInCatalogue(catalogue: List<MediaItem>, trend: MetadataResult, kind: MediaKind): MediaItem? =
    withContext(Dispatchers.IO) {
        val query = trend.toTrendQuery()
        if (query.titles.isEmpty()) return@withContext null
        val candidates = LinkedHashMap<String, MediaItem>()
        // 1. Exact title (case-insensitive, SQL for the disk-backed catalogue): precise even for short titles.
        for (title in query.titles) {
            for (item in catalogue.sameTitleAs(MediaItem(id = "", sourceId = "", kind = kind, title = title, streamUrl = ""))) {
                candidates.putIfAbsent(item.id, item)
            }
        }
        // 2. Keyword prefilter for playlist naming variants ("IT: Title [4K]", "Title (2024)").
        for (keyword in trendKeywords(query)) {
            for (item in catalogue.matchingTitle(keyword).take(MAX_TREND_CANDIDATES)) candidates.putIfAbsent(item.id, item)
        }
        bestTrendMatch(candidates.values.toList(), query)
            // The detail opens with the exact TMDB title the user picked: one direct details request
            // instead of a title search plus details, and the right metadata for same-name titles.
            ?.let { match -> if (match.tmdbId.isNullOrBlank()) match.copy(tmdbId = trend.id) else match }
    }

private const val MAX_TREND_CANDIDATES = 400
