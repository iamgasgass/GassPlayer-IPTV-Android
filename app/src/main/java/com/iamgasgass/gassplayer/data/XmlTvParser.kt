package com.iamgasgass.gassplayer.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

object XmlTvParser {
    private val formats = listOf(
        "yyyyMMddHHmmss Z",
        "yyyyMMddHHmmssZ",
        "yyyyMMddHHmmss",
    )

    fun parse(input: InputStream): List<EpgProgramme> {
        input.use { stream ->
            val parser = Xml.newPullParser().apply { setInput(stream, null) }
            val out = mutableListOf<EpgProgramme>()
            var event = parser.eventType
            var channel = ""
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var tag = ""
            var icon = ""

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name.orEmpty()
                        when (tag) {
                            "programme" -> {
                                channel = parser.getAttributeValue(null, "channel").orEmpty()
                                start = date(parser.getAttributeValue(null, "start"))
                                stop = date(parser.getAttributeValue(null, "stop"))
                                title = ""
                                desc = ""
                                icon = ""
                            }
                            "icon" -> icon = parser.getAttributeValue(null, "src").orEmpty()
                        }
                    }
                    XmlPullParser.TEXT -> when (tag) {
                        "title" -> title += parser.text.orEmpty()
                        "desc" -> desc += parser.text.orEmpty()
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme" && channel.isNotBlank() && title.isNotBlank()) {
                            out += EpgProgramme(
                                channelId = channel,
                                title = title.trim(),
                                description = desc.trim(),
                                startMillis = start,
                                endMillis = stop,
                                icon = icon,
                            )
                        }
                        tag = ""
                    }
                }
                event = parser.next()
            }
            return out.sortedWith(compareBy<EpgProgramme> { it.channelId }.thenBy { it.startMillis })
        }
    }

    private fun date(value: String?): Long {
        val clean = value.orEmpty().trim()
        for (format in formats) {
            try {
                return SimpleDateFormat(format, Locale.US).apply {
                    isLenient = false
                }.parse(clean)?.time ?: 0L
            } catch (_: Exception) {
                // Try the next XMLTV variant.
            }
        }
        return 0L
    }
}
