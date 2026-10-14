package com.gassplayer.android.data

import kotlinx.serialization.Serializable
import java.util.Locale

/** A name advertised by one XMLTV `<channel>` element. Several display-name aliases are allowed. */
@Serializable
data class XmlTvChannelAlias(val epgId: String, val displayName: String)

/** Parsed guide payload. Programme IDs stay provider-defined; aliases bridge those IDs to stream names. */
@Serializable
data class XmlTvGuide(
    val programs: List<EpgProgram> = emptyList(),
    val channels: List<XmlTvChannelAlias> = emptyList(),
)

/** Normalisation shared by guide matching, modelled on the working GassTV normaliser. */
object EpgChannelNormalizer {
    private val countryPrefix = Regex(
        """^\s*(?:[\[(]([A-Z]{2,3})[\])]\s*[|:\-–—]?|([A-Z]{2,3})\s*[|:\-–—])\s*"""
    )
    private val decoration = Regex("""[^\p{L}\p{Nd} &+.\-/:|()']+""")
    private val nonAlnum = Regex("""[^a-zA-Z0-9]+""")
    private val camelBoundary = Regex("""([a-z])([A-Z])""")
    private val multiSpace = Regex("""\s+""")
    private val tokenSeparator = Regex("[\\s/]+")
    private val numberWords = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    )
    private val qualityTokens = setOf(
        "8K", "4K", "UHD", "FUHD", "2160P", "FHD", "1080", "1080P", "FULLHD", "HD", "720P", "720",
        "SD", "LQ", "480P", "576P",
    )
    private val streamMarkers = setOf(
        "RAW", "HEVC", "H265", "H.265", "H264", "H.264", "AV1", "50FPS", "60FPS", "25FPS", "30FPS",
        "FPS", "HDR", "DOLBY", "VISION", "PLUS",
    )
    private val superscripts: Map<Char, Char> = buildMap {
        val mappings = listOf(
            "ᴬ:A,ᴮ:B,ꟲ:C,ᴰ:D,ᴱ:E,ꟳ:F,ᴳ:G,ᴴ:H,ᴵ:I,ᴶ:J,ᴷ:K,ᴸ:L,ᴹ:M,ᴺ:N,ᴼ:O,ᴾ:P,ꟴ:Q,ᴿ:R,ᵀ:T,ᵁ:U,ⱽ:V,ᵂ:W,ᔆ:S",
            "ᵃ:a,ᵇ:b,ᶜ:c,ᵈ:d,ᵉ:e,ᶠ:f,ᵍ:g,ʰ:h,ᶦ:i,ʲ:j,ᵏ:k,ˡ:l,ᵐ:m,ⁿ:n,ᵒ:o,ᵖ:p,ʳ:r,ˢ:s,ᵗ:t,ᵘ:u,ᵛ:v,ʷ:w,ˣ:x,ʸ:y,ᶻ:z",
            "⁰:0,¹:1,²:2,³:3,⁴:4,⁵:5,⁶:6,⁷:7,⁸:8,⁹:9",
        )
        mappings.flatMap { it.split(',') }.forEach { entry ->
            val parts = entry.split(':')
            if (parts.size == 2 && parts[0].length == 1 && parts[1].length == 1) put(parts[0][0], parts[1][0])
        }
    }

    fun normalize(raw: String): String {
        var working = if (raw.any { it in superscripts }) buildString(raw.length) {
            raw.forEach { append(superscripts[it] ?: it) }
        } else raw
        working = countryPrefix.replace(working.trim(), "")
        working = decoration.replace(working, " ")
        val kept = working.split(tokenSeparator).mapNotNull { token ->
            val bare = token.trim('.', ',', ':', ';', '|', '-', '(', ')', '[', ']')
            if (bare.isEmpty()) return@mapNotNull null
            val upper = bare.uppercase(Locale.ROOT)
            if (upper in qualityTokens || upper in streamMarkers) null else bare
        }.joinToString(" ")
        return groupKeyOf(multiSpace.replace(kept, " ").trim())
    }

    fun groupKeyOf(raw: String): String {
        val spaced = camelBoundary.replace(raw, "$1 $2")
        return spaced.lowercase(Locale.ROOT)
            .split(nonAlnum)
            .filter(String::isNotEmpty)
            .joinToString("") { numberWords[it] ?: it }
    }

    private const val MIN_KEY_LENGTH = 2
}

/**
 * Joins XMLTV IDs to IPTV channels, preferring the same Xtream source and exact IDs, then names.
 * Prefix matching is only accepted if it identifies one distinct guide-name key.
 */
class EpgGuideIndex private constructor(
    private val idsBySource: Map<String, Map<String, List<EpgProgram>>>,
    private val idsGlobal: Map<String, List<EpgProgram>>,
    private val namesBySource: Map<String, Map<String, List<EpgProgram>>>,
    private val namesGlobal: Map<String, List<EpgProgram>>,
) {
    fun find(sourceId: String, metadataTag: String?, streamId: String, channelTitle: String): List<EpgProgram> {
        val ids = listOfNotNull(metadataTag?.trim()?.takeIf(String::isNotEmpty), streamId.trim().takeIf(String::isNotEmpty))
            .map { it.lowercase(Locale.ROOT) }.distinct()
        val localIds = idsBySource[sourceId].orEmpty()
        val localNames = namesBySource[sourceId].orEmpty()
        val localIdMatch = ids.firstNotNullOfOrNull { key -> localIds[key]?.takeIf(List<EpgProgram>::isNotEmpty) }
        val globalIdMatch = ids.firstNotNullOfOrNull { key -> idsGlobal[key]?.takeIf(List<EpgProgram>::isNotEmpty) }
        val titleKey = EpgChannelNormalizer.normalize(channelTitle)

        // Build one timeline from every usable source. Name matches prevent a reused generic
        // provider ID from pulling in programmes for a different channel; the local source wins
        // whenever two feeds describe the same start time.
        val localNameMatch = if (titleKey.length >= MIN_KEY_LENGTH) findName(localNames, titleKey) else null
        val globalNameMatch = if (titleKey.length >= MIN_KEY_LENGTH) findName(namesGlobal, titleKey) else null
        val hasNameMatch = !localNameMatch.isNullOrEmpty() || !globalNameMatch.isNullOrEmpty()
        val mergedByStart = LinkedHashMap<Long, EpgProgram>()
        fun add(programs: List<EpgProgram>?) {
            programs.orEmpty().forEach { mergedByStart[it.startMs] = it }
        }

        if (hasNameMatch) {
            add(globalNameMatch)
            add(localNameMatch)
            add(localIdMatch)
        } else {
            // Without a name alias, trust the source-local ID first; a global ID is a last resort.
            add(localIdMatch ?: globalIdMatch)
        }
        return mergedByStart.values.sortedBy { it.startMs }
    }

    private fun findName(index: Map<String, List<EpgProgram>>, key: String): List<EpgProgram>? {
        index[key]?.let { return it }
        val candidates = index.keys.filter { candidate ->
            candidate.length >= MIN_KEY_LENGTH && (candidate.startsWith(key) || key.startsWith(candidate))
        }.distinct()
        return candidates.singleOrNull()?.let(index::get)
    }

    companion object {
        private const val MIN_KEY_LENGTH = 2

        fun build(guides: List<Pair<String, XmlTvGuide>>): EpgGuideIndex {
            val idsBySource = HashMap<String, Map<String, List<EpgProgram>>>()
            val namesBySource = HashMap<String, Map<String, List<EpgProgram>>>()
            val globalIds = HashMap<String, MutableList<EpgProgram>>()
            val globalNames = HashMap<String, MutableList<EpgProgram>>()

            fun add(target: MutableMap<String, MutableList<EpgProgram>>, key: String, programs: List<EpgProgram>) {
                if (key.isNotBlank() && programs.isNotEmpty()) target.getOrPut(key) { ArrayList() }.addAll(programs)
            }

            guides.forEach { (sourceId, guide) ->
                val programsByChannel = guide.programs.groupBy { it.streamId.lowercase(Locale.ROOT) }
                val sourceIds = HashMap<String, List<EpgProgram>>()
                programsByChannel.forEach { (id, programs) ->
                    sourceIds[id] = deduplicate(programs)
                    add(globalIds, id, programs)
                }

                val sourceNames = HashMap<String, MutableList<EpgProgram>>()
                val indexedChannelNames = HashSet<Pair<String, String>>()
                guide.channels.forEach aliasLoop@ { alias ->
                    val id = alias.epgId.lowercase(Locale.ROOT)
                    val programs = programsByChannel[id].orEmpty()
                    if (programs.isEmpty()) return@aliasLoop
                    val nameKey = EpgChannelNormalizer.normalize(alias.displayName)
                    if (nameKey.length < MIN_KEY_LENGTH || !nameKey.any(Char::isLetter)) return@aliasLoop
                    if (indexedChannelNames.add(id to nameKey)) {
                        add(sourceNames, nameKey, programs)
                        add(globalNames, nameKey, programs)
                    }
                }
                // Piu' guide per la stessa sorgente (es. piu' URL XMLTV nella stessa playlist M3U): si uniscono.
                idsBySource[sourceId] = idsBySource[sourceId]?.let { it + sourceIds } ?: sourceIds
                val names = sourceNames.mapValues { (_, values) -> deduplicate(values) }
                namesBySource[sourceId] = namesBySource[sourceId]?.let { it + names } ?: names
            }

            return EpgGuideIndex(
                idsBySource = idsBySource,
                idsGlobal = globalIds.mapValues { (_, values) -> deduplicate(values) },
                namesBySource = namesBySource,
                namesGlobal = globalNames.mapValues { (_, values) -> deduplicate(values) },
            )
        }

        private fun deduplicate(programs: List<EpgProgram>): List<EpgProgram> = programs
            .distinctBy { Triple(it.startMs, it.endMs, it.title.trim()) }
            .sortedBy { it.startMs }
    }
}
