package com.iamgasgass.gassplayer.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.iamgasgass.gassplayer.network.HttpClient
import com.iamgasgass.gassplayer.network.XtreamClient
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val Context.dataStore by preferencesDataStore("gassplayer")
class AppStore(private val context:Context, private val http:HttpClient) {
    private val json=Json { ignoreUnknownKeys=true; prettyPrint=true }; private val xtream=XtreamClient(http)
    private val sourcesKey=stringPreferencesKey("sources"); private val favKey=stringPreferencesKey("favorites"); private val progressKey=stringPreferencesKey("progress")
    suspend fun sources():List<MediaSource> = decode(context.dataStore.data.first()[sourcesKey], emptyList())
    suspend fun saveSources(v:List<MediaSource>)=edit(sourcesKey,json.encodeToString(v))
    suspend fun addSource(name:String,type:SourceType,url:String,user:String="",pass:String=""):MediaSource { val s=MediaSource(UUID.randomUUID().toString(),name,type,url.trim(),user.trim(),pass);saveSources(sources()+s);return s }
    suspend fun removeSource(id:String)=saveSources(sources().filterNot{it.id==id})
    suspend fun favorites():Set<String> = decode<List<String>>(context.dataStore.data.first()[favKey],emptyList()).toSet()
    suspend fun toggleFavorite(id:String){val v=favorites().toMutableSet();if(!v.add(id))v.remove(id);edit(favKey,json.encodeToString(v.toList()))}
    suspend fun progress():List<WatchProgress> = decode(context.dataStore.data.first()[progressKey],emptyList())
    suspend fun saveProgress(p:WatchProgress){val v=(progress().filterNot{it.mediaId==p.mediaId}+p).sortedByDescending{it.updatedAt}.take(100);edit(progressKey,json.encodeToString(v))}
    suspend fun loadCatalog(source:MediaSource,force:Boolean=false):Catalog {
        val file=File(context.cacheDir,"catalog-${source.id}.json")
        if(!force && file.exists() && System.currentTimeMillis()-file.lastModified()<15*60*1000) return decode(file.readText(),Catalog())
        val catalog=when(source.type){SourceType.XTREAM->xtream.catalog(source);SourceType.M3U->{val channels=M3uParser.parse(http.text(source.url),source.id);Catalog(liveCategories=channels.map{it.group}.filter{it.isNotBlank()}.distinct().map{Category(it,it)},channels=channels)}}
        file.writeText(json.encodeToString(catalog)); return catalog
    }
    suspend fun verify(source:MediaSource)=when(source.type){SourceType.XTREAM->xtream.authenticate(source);SourceType.M3U->http.text(source.url).contains("#EXTM3U",true)}
    suspend fun episodes(source:MediaSource,id:String)=xtream.episodes(source,id)
    suspend fun export():String=json.encodeToString(AppBackup(sources=sources(),favorites=favorites(),history=progress()))
    suspend fun import(value:String){val b=json.decodeFromString<AppBackup>(value);saveSources(b.sources);edit(favKey,json.encodeToString(b.favorites.toList()));edit(progressKey,json.encodeToString(b.history))}
    private suspend fun edit(key:androidx.datastore.preferences.core.Preferences.Key<String>,value:String){ context.dataStore.edit { it[key]=value } }
    private inline fun <reified T> decode(v:String?,fallback:T):T=try{if(v==null)fallback else json.decodeFromString(v)}catch(_:Exception){fallback}
}
