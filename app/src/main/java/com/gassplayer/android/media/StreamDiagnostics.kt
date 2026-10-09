package com.gassplayer.android.media

import android.content.Context
import android.content.SharedPreferences
import com.gassplayer.android.data.StreamUrlCandidates
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/*
 * Porting 1:1 di `StreamDiagnostics.swift` (app iOS originale).
 *
 * Perche' esiste: quando un flusso non parte, il motore video da solo dice solo "errore generico" e
 * maschera la causa reale (403 per User-Agent, limite connessioni, container_extension sbagliata,
 * backend che risponde 5xx, pagina HTML con HTTP 200...). Qui si SONDA il provider con una GET
 * `Range: bytes=0-1023` (max 512 byte letti, poi la connessione viene chiusa), si provano formati e
 * User-Agent alternativi e si ricorda cosa ha funzionato per host.
 */

// MARK: - User-Agent

object StreamUserAgents {
    const val DEFAULT_VLC = "VLC/3.0.20 LibVLC/3.0.20"

    /** User-Agent scelto dall'utente (Impostazioni), null se vuoto. */
    @Volatile var custom: String? = null

    /** Primo UA tentato: quello dell'utente se impostato, altrimenti VLC (il piu' tollerato dai pannelli). */
    val vlc: String get() = custom ?: DEFAULT_VLC

    /** Ordine di tentativo quando il provider rifiuta l'accesso. */
    val ladder: List<String>
        get() = listOf(
            vlc,
            "Lavf/60.16.100",
            "IPTVSmartersPro",
            "okhttp/4.12.0",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        ).distinct()
}

// MARK: - Memoria per host (UA + estensione appresa)

/** Ricorda, per host, l'UA e l'estensione che hanno funzionato, cosi' i tentativi successivi partono giusti. */
object PlaybackProfileStore {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("gassplayer_playback_profile", Context.MODE_PRIVATE)
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    fun userAgent(url: String): String? = hostOf(url)?.let { prefs?.getString("ua|$it", null) }

    fun rememberUserAgent(userAgent: String, url: String) {
        val host = hostOf(url) ?: return
        val editor = prefs?.edit() ?: return
        // L'UA di default (VLC/custom) non si salva: e' gia' quello usato per primo.
        if (userAgent == StreamUserAgents.vlc) editor.remove("ua|$host") else editor.putString("ua|$host", userAgent)
        editor.apply()
    }

    private fun extensionKey(url: String): String? {
        val host = hostOf(url) ?: return null
        val kind = StreamDiagnostics.xtreamKind(url) ?: return null
        return "ext|$host|$kind"
    }

    /** Estensione che il provider ha davvero servito quando quella del catalogo falliva. */
    fun learnedExtension(url: String): String? = extensionKey(url)?.let { prefs?.getString(it, null) }

    fun rememberExtension(ext: String, url: String) {
        val key = extensionKey(url) ?: return
        if (ext.isBlank()) return
        prefs?.edit()?.putString(key, ext.lowercase())?.apply()
    }

    /** Il provider e' tornato a servire il formato del catalogo: quello appreso e' obsoleto. */
    fun forgetExtension(url: String) {
        val key = extensionKey(url) ?: return
        if (prefs?.contains(key) == true) prefs?.edit()?.remove(key)?.apply()
    }
}

// MARK: - Risultati

data class StreamResolution(
    val requestedUrl: String,
    /** URL da dare al motore (originale del candidato vincente, NON dopo i redirect: i token CDN possono essere monouso). */
    val playUrl: String,
    val userAgent: String
)

/** Cache in memoria delle risoluzioni riuscite: riaprire lo stesso film parte subito, senza nuova verifica. */
object ResolutionCache {
    private const val TTL_MS = 600_000L
    private val entries = ConcurrentHashMap<String, Pair<StreamResolution, Long>>()

    fun fresh(url: String): StreamResolution? {
        val entry = entries[url] ?: return null
        if (System.currentTimeMillis() - entry.second > TTL_MS) { entries.remove(url); return null }
        return entry.first
    }

    fun store(resolution: StreamResolution, url: String) {
        if (entries.size > 200) entries.clear()
        entries[url] = resolution to System.currentTimeMillis()
    }

    fun invalidate(url: String) { entries.remove(url) }
}

enum class NetFailure { TIMEOUT, CONNECTION_LOST, NO_INTERNET, HOST_UNREACHABLE, TLS, CANCELLED, OTHER }

data class StreamProbeResult(
    val requestedUrl: String,
    val finalUrl: String?,
    val userAgent: String,
    /** null = errore di rete prima di ricevere una risposta HTTP. */
    val statusCode: Int?,
    val contentType: String?,
    val failure: NetFailure?,
    /** HTTP 200 ma il corpo e' una pagina HTML/JSON di errore, non video. */
    val looksLikeErrorPage: Boolean
) {
    val isPlayable: Boolean get() = (statusCode == 200 || statusCode == 206) && !looksLikeErrorPage
    val extensionLabel: String get() = StreamDiagnostics.extensionOf(requestedUrl).uppercase().ifEmpty { "?" }
}

sealed interface StreamDiagnosis {
    data class Playable(val resolution: StreamResolution) : StreamDiagnosis
    /** Il provider ha rifiutato/fallito in modo verificato su ogni tentativo. */
    data class Unplayable(val message: String) : StreamDiagnosis
    /** La sonda non ha potuto stabilire nulla: il motore video puo' comunque riuscire. */
    data class Inconclusive(val message: String) : StreamDiagnosis
}

// MARK: - Diagnostica

object StreamDiagnostics {

    /** Codici con cui i pannelli IPTV segnalano il limite di connessioni simultanee (429 + 458/509/884 non standard). */
    val connectionLimitCodes: Set<Int> = setOf(429, 458, 509, 884)

    /** Impostato dal controller (ConnectivityManager): distingue "nessuna rete" da "host irraggiungibile". */
    @Volatile var hasInternet: () -> Boolean = { true }

    /** Client di sonda: nessuna connessione inattiva trattenuta, cosi' non resta uno slot occupato sul provider. */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(AppDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    // ---- URL helpers (funzioni pure, testabili) ----

    private fun pathOnly(url: String): String {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd >= 0) url.indexOf('/', schemeEnd + 3).let { if (it < 0) return "" else it } else 0
        var end = url.length
        val q = url.indexOf('?', start); if (q in 0 until end) end = q
        val h = url.indexOf('#', start); if (h in 0 until end) end = h
        return url.substring(start, end)
    }

    /** Estensione (minuscola) dell'ultimo segmento del path, "" se assente. */
    fun extensionOf(url: String): String {
        val last = pathOnly(url).substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        return if (dot > 0) last.substring(dot + 1).lowercase() else ""
    }

    /** `movie` / `series` / `live` se l'URL ha forma Xtream `/{kind}/user/pass/{id}.{ext}`, altrimenti null. */
    fun xtreamKind(url: String): String? {
        val segments = pathOnly(url).split('/').filter { it.isNotEmpty() }
        if (segments.size < 4) return null
        val kind = segments[segments.size - 4].lowercase()
        return kind.takeIf { it == "movie" || it == "series" || it == "live" }
    }

    /** Stesso URL con un'altra estensione sull'ultimo segmento (null se non c'e' un segmento con estensione). */
    fun replaceExtension(url: String, ext: String): String? {
        val path = pathOnly(url)
        val last = path.substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        if (dot <= 0) return null
        val schemeEnd = url.indexOf("://")
        val pathStart = if (schemeEnd >= 0) url.indexOf('/', schemeEnd + 3) else 0
        if (pathStart < 0) return null
        val pathEnd = pathStart + path.length
        val newLast = last.substring(0, dot + 1) + ext
        val newPath = path.substring(0, path.length - last.length) + newLast
        return url.substring(0, pathStart) + newPath + url.substring(pathEnd)
    }

    /** URL richiesto + varianti (trasporto/porta/contenitore); in testa l'estensione gia' appresa per questo provider. */
    fun candidatesFor(url: String): List<String> {
        val list = StreamUrlCandidates.ordered(url).toMutableList()
        val learned = PlaybackProfileStore.learnedExtension(url) ?: return list
        if (learned == extensionOf(url)) return list
        val learnedUrl = replaceExtension(url, learned) ?: return list
        list.remove(learnedUrl)
        list.add(0, learnedUrl)
        return list
    }

    // ---- API principale ----

    /**
     * Trova un URL/UA che il provider serve davvero, il piu' in fretta possibile.
     *  A. URL richiesto + varianti in parallelo scaglionato (max 2 connessioni, la seconda dopo 0,7 s o subito se la prima fallisce).
     *  B. Accesso negato / pagina d'errore: ladder di User-Agent.
     *  C. Errore transitorio / limite connessioni: al massimo 2 ripetizioni (dopo 1,5 s e 3 s).
     */
    suspend fun diagnose(
        url: String,
        preferredUserAgent: String,
        headers: Map<String, String> = emptyMap(),
        useCache: Boolean = false,
        overallMs: Long = 40_000L
    ): StreamDiagnosis {
        if (useCache) ResolutionCache.fresh(url)?.let { return StreamDiagnosis.Playable(it) }

        val deadline = System.currentTimeMillis() + overallMs
        var userAgent = preferredUserAgent
        val log = ArrayList<StreamProbeResult>()
        var primary: StreamProbeResult? = null

        fun note(results: List<StreamProbeResult>) {
            log += results
            if (primary == null) primary = results.firstOrNull { it.requestedUrl == url } ?: results.firstOrNull()
        }
        fun canContinue() = System.currentTimeMillis() < deadline

        val candidates = candidatesFor(url)

        // A.
        var outcome = race(candidates.map { it to userAgent }, headers, deadline = deadline)
        note(outcome.second)
        outcome.first?.let { return StreamDiagnosis.Playable(finish(it, userAgent, url)) }

        // B.
        if (canContinue() && shouldTryOtherUserAgents(primary)) {
            val others = StreamUserAgents.ladder.filter { it != userAgent }.map { url to it }
            outcome = race(others, headers, stagger = 500L, deadline = deadline)
            note(outcome.second)
            outcome.first?.let { winner ->
                userAgent = winner.userAgent
                return StreamDiagnosis.Playable(finish(winner, userAgent, url))
            }
        }

        // C.
        if (isTransient(primary) || isConnectionLimit(primary)) {
            for (attempt in 1..2) {
                if (!canContinue()) break
                kotlinx.coroutines.delay(attempt * 1_500L)
                if (!canContinue()) break
                outcome = race(candidates.map { it to userAgent }, headers, deadline = deadline)
                note(outcome.second)
                outcome.first?.let { return StreamDiagnosis.Playable(finish(it, userAgent, url)) }
            }
        }

        val message = describe(primary, log)
        return if (isInconclusive(primary)) StreamDiagnosis.Inconclusive(message) else StreamDiagnosis.Unplayable(message)
    }

    /** Sonda le coppie (URL, UA) con concorrenza limitata e ritorna alla prima valida. */
    private suspend fun race(
        pairs: List<Pair<String, String>>,
        headers: Map<String, String>,
        stagger: Long = 700L,
        maxConcurrent: Int = 2,
        probeTimeoutMs: Long = 8_000L,
        deadline: Long
    ): Pair<StreamProbeResult?, List<StreamProbeResult>> = coroutineScope {
        if (pairs.isEmpty()) return@coroutineScope null to emptyList()

        val results = ArrayList<StreamProbeResult>()
        val channel = Channel<StreamProbeResult>(Channel.UNLIMITED)
        val jobs = ArrayList<Job>()
        var next = 0
        var inFlight = 0
        var winner: StreamProbeResult? = null

        fun launchNext() {
            val (candidate, ua) = pairs[next++]
            inFlight++
            // Mai oltre la scadenza globale.
            val timeout = maxOf(1_000L, minOf(probeTimeoutMs, deadline - System.currentTimeMillis()))
            jobs += launch { channel.send(probe(candidate, ua, headers, timeout)) }
        }

        launchNext()
        while (System.currentTimeMillis() < deadline) {
            val more = next < pairs.size
            val wait = if (more) stagger else maxOf(1L, deadline - System.currentTimeMillis())
            val received = withTimeoutOrNull(wait) { channel.receive() }
            if (received == null) {
                // Tick: lancia la sonda successiva se c'e' posto, altrimenti si aspetta ancora.
                if (more && inFlight < maxConcurrent) launchNext()
                if (!more) break
                continue
            }
            inFlight--
            results += received
            if (received.isPlayable) { winner = received; break }
            // Fallimento: rimpiazza subito la sonda persa.
            while (next < pairs.size && inFlight < maxConcurrent) launchNext()
            if (inFlight == 0 && next >= pairs.size) break
        }
        jobs.forEach { it.cancel() }
        winner to results
    }

    /**
     * Registra l'esito: memorizza UA/estensione che funzionano e mette in cache la risoluzione.
     * Si riproduce l'URL *originale* del candidato, non quello dopo i redirect.
     */
    private fun finish(winner: StreamProbeResult, userAgent: String, requested: String): StreamResolution {
        val resolution = StreamResolution(requested, winner.requestedUrl, userAgent)
        ResolutionCache.store(resolution, requested)
        val ext = extensionOf(winner.requestedUrl)
        val requestedExt = extensionOf(requested)
        if (ext != requestedExt) {
            PlaybackProfileStore.rememberExtension(ext, requested)
        } else {
            val learned = PlaybackProfileStore.learnedExtension(requested)
            // Ha vinto il formato del catalogo: quello appreso e' obsoleto.
            if (learned != null && learned != ext) PlaybackProfileStore.forgetExtension(requested)
        }
        return resolution
    }

    // ---- Probe ----

    /** GET con `Range: bytes=0-1023`: legge al massimo 512 byte, poi chiude la connessione (non scarica il film). */
    suspend fun probe(url: String, userAgent: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 8_000L): StreamProbeResult {
        val builder = try {
            Request.Builder().url(url).get()
        } catch (_: IllegalArgumentException) {
            return StreamProbeResult(url, null, userAgent, null, null, NetFailure.OTHER, false)
        }
        builder.header("Range", "bytes=0-1023").header("User-Agent", userAgent).header("Accept", "*/*").header("Accept-Encoding", "identity")
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && !k.equals("User-Agent", true) && !k.equals("Range", true) && !k.equals("Accept-Encoding", true)) runCatching { builder.header(k, v) }
        }
        val client = probeClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(timeoutMs, 8_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(builder.build())

        return suspendCancellableCoroutine { cont: CancellableContinuation<StreamProbeResult> ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(StreamProbeResult(url, null, userAgent, null, null, classifyFailure(e), false))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val code = r.code
                        var head = ByteArray(0)
                        if (code == 200 || code == 206) {
                            // Lettura interrotta: conta solo cio' che e' arrivato.
                            runCatching {
                                val source = r.body.source()
                                source.request(512)
                                head = source.buffer.snapshot(minOf(512L, source.buffer.size).toInt()).toByteArray()
                            }
                        }
                        val contentType = r.header("Content-Type")?.lowercase()
                        val result = StreamProbeResult(url, r.request.url.toString(), userAgent, code, contentType, null, looksLikeErrorPage(head, contentType))
                        if (cont.isActive) cont.resume(result)
                    }
                }
            })
        }
    }

    internal fun classifyFailure(e: IOException): NetFailure = when {
        e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ->
            if (!hasInternet()) NetFailure.NO_INTERNET else NetFailure.HOST_UNREACHABLE
        e is SSLException -> NetFailure.TLS
        e is SocketTimeoutException || e is InterruptedIOException || e.message?.contains("timeout", true) == true -> NetFailure.TIMEOUT
        e.message?.contains("canceled", true) == true -> NetFailure.CANCELLED
        else -> NetFailure.CONNECTION_LOST
    }

    /** Riconosce le risposte "200 OK" che in realta' sono pagine di errore del pannello (HTML o JSON) e non un flusso/playlist. */
    internal fun looksLikeErrorPage(head: ByteArray, contentType: String?): Boolean {
        val text = String(head, 0, minOf(head.size, 96), Charsets.UTF_8).trim().lowercase()
        // Una playlist HLS e' valida anche se servita come text/plain.
        if (text.startsWith("#extm3u")) return false
        if (contentType != null && (contentType.contains("text/html") || contentType.contains("application/json"))) return true
        if (text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<head")) return true
        if ((text.startsWith("{") || text.startsWith("[")) && text.contains('"') && head.size < 512) return true
        return false
    }

    // ---- Classificazione ----

    /** Errori che spesso passano da soli (backend sotto carico, rete lenta). */
    internal fun isTransient(r: StreamProbeResult?): Boolean {
        if (r == null) return false
        r.statusCode?.let { return it == 408 || it in 500..599 }
        return r.failure == NetFailure.TIMEOUT || r.failure == NetFailure.CONNECTION_LOST
    }

    internal fun isConnectionLimit(r: StreamProbeResult?): Boolean = r?.statusCode?.let { it in connectionLimitCodes } == true

    internal fun shouldTryOtherUserAgents(r: StreamProbeResult?): Boolean {
        if (r == null) return false
        if (r.looksLikeErrorPage) return true
        val code = r.statusCode ?: return false
        return code in intArrayOf(401, 403, 406, 451)
    }

    /** Casi in cui la sonda non e' affidabile ma il motore potrebbe riuscire: metodo/Range non gestiti, errori TLS. */
    internal fun isInconclusive(r: StreamProbeResult?): Boolean {
        if (r == null) return false
        r.statusCode?.let { return it in intArrayOf(400, 405, 416, 501) }
        val failure = r.failure ?: return true
        return failure == NetFailure.TLS || failure == NetFailure.CANCELLED
    }

    // ---- Messaggi ----

    internal fun describe(base: StreamProbeResult?, log: List<StreamProbeResult>): String {
        val b = base ?: log.firstOrNull() ?: return "Il provider non ha risposto in tempo."

        // Riepilogo per formato (solo se e' stato provato piu' di un formato).
        val perFormat = ArrayList<String>()
        val seen = HashSet<String>()
        for (r in log) {
            val label = r.extensionLabel
            if (!seen.add(label)) continue
            val outcome = when {
                r.statusCode != null -> "HTTP ${r.statusCode}"
                r.looksLikeErrorPage -> "pagina errore"
                else -> "no risposta"
            }
            perFormat += "$label: $outcome"
        }
        val formats = if (perFormat.size > 1) "\n\nFormati provati → " + perFormat.joinToString(", ") else ""

        if (b.looksLikeErrorPage) {
            return "Il provider risponde con una pagina di errore invece del video (abbonamento scaduto, credenziali non valide o limite di connessioni)." + formats
        }

        val code = b.statusCode ?: return when (b.failure) {
            NetFailure.NO_INTERNET -> "Nessuna connessione a Internet."
            NetFailure.TIMEOUT -> "Il server del provider non risponde (timeout). Potrebbe essere offline o sovraccarico."
            NetFailure.HOST_UNREACHABLE -> "Server del provider non raggiungibile (host inesistente o spento)."
            NetFailure.TLS -> "Errore di connessione sicura (HTTPS/certificato) verso il provider."
            else -> "Impossibile contattare il provider (errore di rete)."
        }

        return when (code) {
            401, 403 -> "Il provider ha rifiutato l'accesso (HTTP $code). Controlla che l'abbonamento non sia scaduto e che le credenziali siano corrette; alcuni provider bloccano anche certi IP/VPN." + formats
            404, 410 -> "Il provider non ha questo contenuto (HTTP $code) in nessun formato provato: il file è stato rimosso o non è più caricato sul server. Prova «Altre fonti»." + formats
            429, 458, 509, 884 -> "Limite di connessioni simultanee raggiunto (HTTP $code). Chiudi altri dispositivi/player collegati allo stesso account e riprova tra qualche secondo."
            in 500..599 -> "Il backend del provider sta fallendo nel servire questo contenuto (HTTP $code). Non è un problema di formato o dell'app: riprova più tardi o usa «Altre fonti»." + formats
            else -> "Risposta inattesa dal provider (HTTP $code)." + formats
        }
    }
}
