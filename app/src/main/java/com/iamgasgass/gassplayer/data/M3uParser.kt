package com.iamgasgass.gassplayer.data

object M3uParser {
    private val attr = Regex("""([\w-]+)=["']([^"']*)["']""")

    fun parse(text: String, sourceId: String = ""): List<Channel> {
        val cleaned = text.removePrefix("\uFEFF")
        val lines = cleaned.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val result = mutableListOf<Channel>()
        var info: String? = null
        var number = 1

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> info = line
                !line.startsWith("#") && info != null -> {
                    val header = info.orEmpty()
                    val meta = attr.findAll(header)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }

                    val displayName = header.substringAfter(',', "").trim()
                    val name = displayName
                        .ifBlank { meta["tvg-name"].orEmpty() }
                        .ifBlank { "Canale $number" }

                    val id = meta["tvg-id"]
                        ?.takeIf(String::isNotBlank)
                        ?: "$sourceId-${line.hashCode()}-$number"

                    val catchup = meta.keys.any { it.startsWith("catchup") } ||
                        !meta["catchup-source"].isNullOrBlank()

                    result += Channel(
                        id = id,
                        name = name,
                        streamUrl = line,
                        logo = meta["tvg-logo"].orEmpty(),
                        group = meta["group-title"] ?: meta["group"].orEmpty(),
                        epgId = meta["tvg-id"].orEmpty(),
                        number = number++,
                        catchup = catchup,
                        sourceId = sourceId,
                    )
                    info = null
                }
            }
        }
        return result
    }
}
