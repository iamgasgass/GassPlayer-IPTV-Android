package com.iamgasgass.gassplayer.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class HttpClient {
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).followRedirects(true).build()
    suspend fun text(url:String, headers:Map<String,String> = emptyMap()):String=withContext(Dispatchers.IO){
        val b=Request.Builder().url(url).header("User-Agent","GassPlayer/1.0 Android")
        headers.forEach { (k,v)->b.header(k,v) }
        client.newCall(b.build()).execute().use { if(!it.isSuccessful) error("HTTP ${it.code}"); it.body?.string()?:error("Risposta vuota") }
    }
    suspend fun bytes(url:String):ByteArray=withContext(Dispatchers.IO){client.newCall(Request.Builder().url(url).build()).execute().use{if(!it.isSuccessful) error("HTTP ${it.code}");it.body?.bytes()?:byteArrayOf()}}
}
