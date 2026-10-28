package com.gassplayer.android.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min

/**
 * Matching di precisione titolo provider → TMDB, port 1:1 di TMDBService.swift (parseTitle, normalize,
 * titleSimilarity, yearScore, score, searchAttempts, castOverlap, isTrustworthy).
 *
 * Prima Android prendeva il PRIMO risultato di `search/movie` sul titolo grezzo ("IT - Blow [4K]"): ora,
 * come su iOS, se non c'è una corrispondenza abbastanza sicura non si usa nessun risultato (meglio il
 * poster Xtream che il poster di un altro film, es. "Blow Out" per "Blow").
 */
object TmdbTitleMatcher {
    data class ParsedTitle(
        /** Titolo ripulito da tag di qualità/lingua/prefissi del provider. */
        val query: String,
        /** Anno esplicito `(2001)` / `[2001]`: indizio forte. */
        val year: String?,
        /** Anno "nudo" in coda (`Titolo 2001`): indizio debole, il titolo resta intatto (es. "1917"). */
        val trailingYear: String?,
    )

    data class Scored(val result: MetadataResult, val titleScore: Double, val total: Double)

    const val MINIMUM_TITLE_SCORE = 0.6
    const val MINIMUM_TOTAL_SCORE = 0.62
    const val CONFIDENT_TOTAL_SCORE = 0.88

    private val noiseWords = listOf(
        "4K", "UHD", "HD", "FHD", "SD", "HDR", "HDR10", "DV", "ITA", "ENG", "SUB", "SUBITA",
        "DUAL", "MULTI", "BLURAY", "BDRIP", "WEBRIP", "WEB-DL", "WEBDL", "H264", "H265", "HEVC", "X264", "X265"
    )
    private val noiseRegexes = noiseWords.map { Regex("(?<![A-Za-z0-9])${Regex.escape(it)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE) }
    private val providerPrefixCodes = setOf(
        "IT", "ITA", "EN", "ENG", "FR", "DE", "ES", "UK", "US", "PT", "NL", "TR", "AR",
        "MULTI", "SUB", "VOD", "4K", "UHD", "NF", "AMZN", "DSNP", "SKY", "NOW"
    )
    private val explicitYearRegex = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val prefixPatterns = listOf(
        Regex("""^\s*[|\[]\s*([A-Za-z0-9]{2,5})\s*[|\]]\s*"""),
        Regex("""^\s*([A-Za-z0-9]{2,5})\s+[-–—]\s+""")
    )
    private val bracketPatterns = listOf(Regex("""\(.*?\)"""), Regex("""\[.*?]"""), Regex("""\{.*?}"""))
    private val trailingYearRegex = Regex("""\s+((?:19|20)\d{2})$""")
    private val trimChars = "-–—|:._"

    /** Rimozione dei tag solo come PAROLE intere: "Suburbicon", "Italian Job", "Multiverse" restano intatti. */
    fun parseTitle(rawTitle: String): ParsedTitle {
        var working = rawTitle
        val explicitYear = explicitYearRegex.find(working)?.groupValues?.getOrNull(1)

        for (pattern in prefixPatterns) {
            val match = pattern.find(working) ?: continue
            val code = match.value.trim { it in "|[]-–— \t" }.uppercase(Locale.ROOT)
            if (code in providerPrefixCodes) {
                working = working.removeRange(match.range)
                break
            }
        }

        for (pattern in bracketPatterns) working = working.replace(pattern, " ")
        for (regex in noiseRegexes) working = working.replace(regex, " ")

        working = working.replace(Regex("""\s+"""), " ").trim { it.isWhitespace() || it in trimChars }

        var trailingYear: String? = null
        trailingYearRegex.find(working)?.let { match ->
            if (working.substring(0, match.range.first).trim().length >= 2) trailingYear = match.groupValues[1]
        }
        return ParsedTitle(working, explicitYear, trailingYear)
    }

    /** Minuscolo, senza accenti, solo lettere/numeri separati da singoli spazi ("Spider-Man" → "spider man"). */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
        val out = StringBuilder(folded.length)
        var lastWasSpace = true
        for (ch in folded) {
            if (ch.isLetterOrDigit()) {
                out.append(ch); lastWasSpace = false
            } else if (!lastWasSpace) {
                out.append(' '); lastWasSpace = true
            }
        }
        return out.toString().trim()
    }

    /** 1 solo se identici; altrimenti coefficiente di Dice sui token, mai oltre 0.95 ("blow" vs "blow out" ≈ 0.67). */
    fun titleSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val tokensA = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val tokensB = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val common = tokensA.intersect(tokensB).size.toDouble()
        return min(2 * common / (tokensA.size + tokensB.size), 0.95)
    }

    /** (punteggio, penalità) dell'anno candidato rispetto all'indizio. */
    fun yearScore(candidate: String?, hint: String?): Pair<Double, Double> {
        val hintValue = hint?.toIntOrNull() ?: return 0.5 to 0.0
        val candidateValue = candidate?.toIntOrNull() ?: return 0.35 to 0.0
        return when (abs(candidateValue - hintValue)) {
            0 -> 1.0 to 0.0
            1 -> 0.7 to 0.0
            else -> 0.0 to 0.2
        }
    }

    fun score(result: MetadataResult, query: String, yearHint: String?): Scored {
        val normalizedQuery = normalize(query)
        val names = listOfNotNull(result.title, result.originalTitle).map(::normalize).filter { it.isNotEmpty() }
        val titleScore = names.maxOfOrNull { titleSimilarity(normalizedQuery, it) } ?: 0.0
        val (yearValue, penalty) = yearScore(result.year, yearHint)
        // Notorietà: solo spareggio fra omonimi (remake).
        val popularity = min(log10(1.0 + (result.voteCount ?: 0)) / 4.0, 1.0)
        val total = titleScore * 0.7 + yearValue * 0.2 + popularity * 0.1 - penalty
        return Scored(result, titleScore, total)
    }

    /** Tentativi di ricerca, dal più specifico al più permissivo. */
    fun searchAttempts(parsed: ParsedTitle, yearOverride: String?): List<Pair<String, String?>> {
        val hint = parsed.year ?: yearOverride
        val attempts = ArrayList<Pair<String, String?>>()
        if (hint != null) attempts += parsed.query to hint
        attempts += parsed.query to null
        parsed.trailingYear?.let { trailing ->
            val withoutYear = parsed.query.replace(Regex("""\s+(?:19|20)\d{2}$"""), "")
            attempts += withoutYear to (hint ?: trailing)
            attempts += withoutYear to null
        }
        // "Titolo: sottotitolo" / "Titolo - sottotitolo": ultimo tentativo sulla sola parte principale.
        for (separator in listOf(":", " - ")) {
            val head = parsed.query.split(separator).first()
            if (head.length >= 3 && head != parsed.query) attempts += head.trim() to hint
        }
        val seen = HashSet<String>()
        return attempts.filter { seen.add("${it.first.lowercase(Locale.ROOT)}|${it.second.orEmpty()}") }
    }

    fun castOverlap(names: List<String>, credits: List<String>): Int {
        val wanted = names.map(::normalize).filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return 0
        return credits.take(15).count { normalize(it) in wanted }
    }

    /** L'id del pannello è attendibile se il titolo coincide abbastanza o se almeno un attore del provider è nel cast TMDB. */
    fun isTrustworthy(details: MetadataResult, rawTitle: String, castNames: List<String>): Boolean {
        val query = normalize(parseTitle(rawTitle).query)
        val similarity = listOfNotNull(details.title, details.originalTitle).map(::normalize)
            .maxOfOrNull { titleSimilarity(query, it) } ?: 0.0
        if (similarity >= 0.6) return true
        return castOverlap(castNames, details.cast) > 0
    }

    fun cacheKey(isSeries: Boolean, title: String, year: String?): String {
        val parsed = parseTitle(title)
        return "${if (isSeries) "tv" else "movie"}::${normalize(parsed.query)}::${year ?: parsed.year.orEmpty()}"
    }
}
