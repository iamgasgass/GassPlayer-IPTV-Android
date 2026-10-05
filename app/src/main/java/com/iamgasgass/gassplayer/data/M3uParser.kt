package com.iamgasgass.gassplayer.data

object M3uParser {
    private val attr = Regex("([\\w-]+)=\\\"([^\\\"]*)\\\"")
    fun parse(text:String, sourceId:String=""):List<Channel> {
        val lines=text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val result=mutableListOf<Channel>(); var info:String?=null; var number=1
        for(line in lines) when {
            line.startsWith("#EXTINF", true) -> info=line
            !line.startsWith("#") && info!=null -> {
                val meta=attr.findAll(info!!).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                val name=info!!.substringAfterLast(',', meta["tvg-name"] ?: "Canale $number").trim()
                result += Channel(id=meta["tvg-id"].orEmpty().ifBlank { "$sourceId-${line.hashCode()}" }, name=name, streamUrl=line, logo=meta["tvg-logo"].orEmpty(), group=meta["group-title"].orEmpty(), epgId=meta["tvg-id"].orEmpty(), number=number++, catchup=meta.keys.any{it.startsWith("catchup")}, sourceId=sourceId)
                info=null
            }
        }
        return result
    }
}
