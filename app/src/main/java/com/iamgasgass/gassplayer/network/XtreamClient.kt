package com.iamgasgass.gassplayer.network

import com.iamgasgass.gassplayer.data.*
import kotlinx.serialization.json.*
import java.net.URLEncoder

class XtreamClient(private val http:HttpClient) {
    private fun api(s:MediaSource, action:String)="${s.url.trimEnd('/')}/player_api.php?username=${enc(s.username)}&password=${enc(s.password)}&action=$action"
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
    suspend fun authenticate(s:MediaSource):Boolean { val o=Json.parseToJsonElement(http.text("${s.url.trimEnd('/')}/player_api.php?username=${enc(s.username)}&password=${enc(s.password)}")).jsonObject; return o["user_info"]?.jsonObject?.get("auth")?.jsonPrimitive?.intOrNull==1 }
    suspend fun catalog(s:MediaSource):Catalog {
        val liveCats=categories(s,"get_live_categories"); val vodCats=categories(s,"get_vod_categories"); val seriesCats=categories(s,"get_series_categories")
        val channels=Json.parseToJsonElement(http.text(api(s,"get_live_streams"))).jsonArray.mapIndexed { i,e-> val o=e.jsonObject; val id=o.str("stream_id"); Channel(id,o.str("name"),"${s.url.trimEnd('/')}/live/${s.username}/${s.password}/$id.ts",o.str("stream_icon"),liveCats.firstOrNull{it.id==o.str("category_id")}?.name.orEmpty(),o.str("epg_channel_id"),o.int("num",i+1),o.int("tv_archive",0)==1,s.id) }
        val movies=Json.parseToJsonElement(http.text(api(s,"get_vod_streams"))).jsonArray.map { e->val o=e.jsonObject;val id=o.str("stream_id");val ext=o.str("container_extension").ifBlank{"mp4"};Movie(id,o.str("name"),"${s.url.trimEnd('/')}/movie/${s.username}/${s.password}/$id.$ext",o.str("stream_icon"),categoryId=o.str("category_id"),rating=o.double("rating"),year=o.str("year"),extension=ext,sourceId=s.id) }
        val series=Json.parseToJsonElement(http.text(api(s,"get_series"))).jsonArray.map{e->val o=e.jsonObject;Series(o.str("series_id"),o.str("name"),o.str("cover"),categoryId=o.str("category_id"),rating=o.double("rating"),year=o.str("year"),plot=o.str("plot"),sourceId=s.id)}
        return Catalog(liveCats,vodCats,seriesCats,channels,movies,series)
    }
    suspend fun episodes(s:MediaSource, seriesId:String):List<Episode> {
        val root=Json.parseToJsonElement(http.text(api(s,"get_series_info")+"&series_id=${enc(seriesId)}")).jsonObject
        return root["episodes"]?.jsonObject?.flatMap { (season,list)-> list.jsonArray.mapIndexed { i,e->val o=e.jsonObject; val id=o.str("id");val ext=o.str("container_extension").ifBlank{"mp4"}; Episode(id,o.str("title").ifBlank{"Episodio ${i+1}"},season.toIntOrNull()?:1,o.int("episode_num",i+1),"${s.url.trimEnd('/')}/series/${s.username}/${s.password}/$id.$ext",o["info"]?.jsonObject?.str("movie_image").orEmpty(),o["info"]?.jsonObject?.str("plot").orEmpty(),o["info"]?.jsonObject?.str("duration").orEmpty(),ext) } } ?: emptyList()
    }
    suspend fun shortEpg(s:MediaSource, streamId:String, limit:Int=20):List<EpgProgramme> {
        val url=api(s,"get_short_epg")+"&stream_id=${enc(streamId)}&limit=$limit"; val arr=Json.parseToJsonElement(http.text(url)).jsonObject["epg_listings"]?.jsonArray?:return emptyList()
        return arr.map { e->val o=e.jsonObject;EpgProgramme(streamId,decode64(o.str("title")),decode64(o.str("description")),o.long("start_timestamp")*1000,o.long("stop_timestamp")*1000) }
    }
    private suspend fun categories(s:MediaSource,a:String)=Json.parseToJsonElement(http.text(api(s,a))).jsonArray.map{val o=it.jsonObject;Category(o.str("category_id"),o.str("category_name"),o.int("parent_id"))}
    private fun decode64(v:String)=try{String(android.util.Base64.decode(v,android.util.Base64.DEFAULT))}catch(_:Exception){v}
}
private fun JsonObject.str(k:String)=this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.int(k:String,d:Int=0)=this[k]?.jsonPrimitive?.intOrNull?:d
private fun JsonObject.long(k:String)=this[k]?.jsonPrimitive?.longOrNull?:0L
private fun JsonObject.double(k:String)=this[k]?.jsonPrimitive?.doubleOrNull?:0.0
