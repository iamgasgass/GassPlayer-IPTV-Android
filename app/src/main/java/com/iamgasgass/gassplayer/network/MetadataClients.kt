package com.iamgasgass.gassplayer.network

import com.iamgasgass.gassplayer.data.Metadata
import kotlinx.serialization.json.*
import java.net.URLEncoder

class MetadataClients(private val http:HttpClient) {
    suspend fun tmdbSearch(title:String, apiKey:String, series:Boolean=false):Metadata? { if(apiKey.isBlank())return null;val type=if(series)"tv" else "movie";val root=Json.parseToJsonElement(http.text("https://api.themoviedb.org/3/search/$type?api_key=${enc(apiKey)}&language=it-IT&query=${enc(title)}")).jsonObject;val o=root["results"]?.jsonArray?.firstOrNull()?.jsonObject?:return null;return Metadata(o.s(if(series)"name" else "title"),o.s("overview"),img(o.s("poster_path")),img(o.s("backdrop_path")),o.s(if(series)"first_air_date" else "release_date").take(4),o["vote_average"]?.jsonPrimitive?.doubleOrNull?:0.0) }
    suspend fun omdb(title:String,key:String):Metadata? {if(key.isBlank())return null;val o=Json.parseToJsonElement(http.text("https://www.omdbapi.com/?apikey=${enc(key)}&t=${enc(title)}&plot=full")).jsonObject;if(o["Response"]?.jsonPrimitive?.content!="True")return null;return Metadata(o.s("Title"),o.s("Plot"),o.s("Poster"),year=o.s("Year"),rating=o.s("imdbRating").toDoubleOrNull()?:0.0,genres=o.s("Genre").split(',').map(String::trim))}
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8"); private fun img(v:String)=if(v.isBlank())"" else "https://image.tmdb.org/t/p/w780$v"
}
private fun JsonObject.s(k:String)=this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
