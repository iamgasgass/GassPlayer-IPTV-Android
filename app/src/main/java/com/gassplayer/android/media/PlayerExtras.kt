package com.gassplayer.android.media

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The "DNS preferito" setting, for real. Resolves through DNS-over-HTTPS (Cloudflare 1.1.1.1 or
 * Google 8.8.8.8, reached by IP literal so no bootstrap lookup is needed), caches answers for a few
 * minutes (faster reconnects / zapping) and silently falls back to the system resolver on any failure.
 * Many ISPs poison or block IPTV panel hostnames at the DNS level; this routes around that.
 */
object AppDns : Dns {
    /** "1.1.1.1", "8.8.8.8" or anything else = system resolver. */
    @Volatile var server: String = "system"
        set(value) { if (field != value) { field = value; cache.clear() } }

    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private const val TTL_MS = 5 * 60_000L

    override fun lookup(hostname: String): List<InetAddress> {
        val endpoint = when (server) {
            "1.1.1.1" -> "https://1.1.1.1/dns-query"
            "8.8.8.8" -> "https://8.8.8.8/resolve"
            else -> null
        }
        if (endpoint == null || hostname.equals("localhost", true) || looksLikeIp(hostname)) return Dns.SYSTEM.lookup(hostname)
        val now = SystemClock.elapsedRealtime()
        cache[hostname]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        val resolved = runCatching { query(endpoint, hostname) }.getOrNull().orEmpty()
        if (resolved.isNotEmpty()) {
            cache[hostname] = Entry(resolved, now + TTL_MS)
            return resolved
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun looksLikeIp(host: String): Boolean = host.contains(':') || host.all { it.isDigit() || it == '.' }

    private fun query(endpoint: String, host: String): List<InetAddress> {
        val url = endpoint.toHttpUrl().newBuilder().addQueryParameter("name", host).addQueryParameter("type", "A").build()
        val request = Request.Builder().url(url).header("accept", "application/dns-json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val answers: JsonArray = json.parseToJsonElement(response.body.string()).jsonObject["Answer"]?.jsonArray ?: return emptyList()
            return answers.mapNotNull { element ->
                val obj: JsonObject = element.jsonObject
                if (obj["type"]?.jsonPrimitive?.int != 1) return@mapNotNull null // A records only (CNAME chain is skipped)
                val parts = obj["data"]?.jsonPrimitive?.content?.split('.') ?: return@mapNotNull null
                if (parts.size != 4) return@mapNotNull null
                val bytes = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return@mapNotNull null }.map { it.toByte() }.toByteArray()
                InetAddress.getByAddress(host, bytes) // keeps the hostname for TLS SNI/certificate checks
            }
        }
    }
}

/**
 * Real "A/V delay (ms)": positive values delay the audio (silence is inserted at start and after each
 * seek), negative values advance it (the first samples are dropped). Works on 16-bit PCM, which is
 * what the default audio sink uses; passthrough formats bypass audio processors.
 */
@OptIn(UnstableApi::class)
class AudioDelayProcessor(private val delayMs: Int) : BaseAudioProcessor() {
    private var silenceBytes = 0
    private var skipBytes = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush() {
        val frames = (kotlin.math.abs(delayMs).toLong() * inputAudioFormat.sampleRate / 1000L).toInt()
        val bytes = frames * inputAudioFormat.bytesPerFrame
        silenceBytes = if (delayMs > 0) bytes else 0
        skipBytes = if (delayMs < 0) bytes else 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        var remaining = inputBuffer.remaining()
        if (skipBytes > 0) {
            val drop = minOf(skipBytes, remaining)
            inputBuffer.position(inputBuffer.position() + drop)
            skipBytes -= drop
            remaining -= drop
        }
        if (remaining == 0 && silenceBytes == 0) return
        val out = replaceOutputBuffer(silenceBytes + remaining)
        if (silenceBytes > 0) {
            out.put(ByteArray(silenceBytes))
            silenceBytes = 0
        }
        out.put(inputBuffer)
        out.flip()
    }
}
