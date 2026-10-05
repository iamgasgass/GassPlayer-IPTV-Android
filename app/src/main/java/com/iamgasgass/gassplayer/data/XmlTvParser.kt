package com.iamgasgass.gassplayer.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

object XmlTvParser {
    private val formats=listOf("yyyyMMddHHmmss Z","yyyyMMddHHmmssZ","yyyyMMddHHmmss")
    fun parse(input:InputStream):List<EpgProgramme> {
        val parser=Xml.newPullParser().apply { setInput(input,null) }; val out=mutableListOf<EpgProgramme>()
        var event=parser.eventType; var channel=""; var start=0L; var stop=0L; var title=""; var desc=""; var tag=""
        while(event!=XmlPullParser.END_DOCUMENT){
            when(event){
                XmlPullParser.START_TAG -> { tag=parser.name; if(tag=="programme"){channel=parser.getAttributeValue(null,"channel").orEmpty(); start=date(parser.getAttributeValue(null,"start")); stop=date(parser.getAttributeValue(null,"stop")); title=""; desc=""} }
                XmlPullParser.TEXT -> when(tag){"title"->title+=parser.text; "desc"->desc+=parser.text}
                XmlPullParser.END_TAG -> { if(parser.name=="programme" && channel.isNotBlank() && title.isNotBlank()) out+=EpgProgramme(channel,title.trim(),desc.trim(),start,stop); tag="" }
            }; event=parser.next()
        }; return out
    }
    private fun date(value:String?):Long { val clean=value.orEmpty().trim(); for(f in formats) try{return SimpleDateFormat(f,Locale.US).parse(clean)?.time?:0}catch(_:Exception){}; return 0 }
}
