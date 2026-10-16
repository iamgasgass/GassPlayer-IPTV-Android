package com.gassplayer.android.data

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Path.Companion.toOkioPath
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Image loader for channel icons and posters (port of iOS `PosterDownloader`). IPTV image servers are far
 * less forgiving than CDNs; the default Coil client failed silently on many of them, leaving cells empty:
 *  - browser User-Agent + image Accept header (several logo hosts answer 403 to the default okhttp UA);
 *  - retries with backoff for transient errors (timeouts, 408/429/5xx caused by many parallel requests);
 *  - https -> http fallback (lots of provider image hosts only really serve plain http);
 *  - invalid certificates (self-signed, expired, wrong host) accepted ONLY by this client, which downloads
 *    public images and never sends credentials — the same choice as the iOS app;
 *  - SVG logos decoded (common for channel picons), 6 connections per host, 300 MB disk cache.
 */
object ArtworkImageLoader {
    // Header IDENTICI a iOS PosterDownloader (httpAdditionalHeaders): alcuni server immagini decidono in base a
    // questi se servire l'immagine o un sostituto ("This image was hotlinked"); con lo User-Agent di Chrome Android
    // rispondevano diversamente da iOS. Nessun Referer al primo tentativo, come iOS (salvo i server noti che lo
    // richiedono e i tentativi dopo un 401/403: vedi RetryAndFallbackInterceptor).
    private const val USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private const val ACCEPT = "image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5"
    private const val ACCEPT_LANGUAGE = "it-IT,it;q=0.9"

    fun create(context: PlatformContext): ImageLoader {
        val appContext: Context = context.applicationContext
        return ImageLoader.Builder(appContext)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                add(SvgDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(appContext, 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("artwork_cache").toOkioPath())
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    private val client: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 6 })
            // Timeouts PER TENTATIVO, come iOS (timeoutIntervalForRequest 30 s per richiesta). Prima un callTimeout
            // globale di 90 s copriva TUTTI i tentativi: su un server https lento la chiamata veniva chiusa
            // ("InterruptedIOException: timeout <- Socket closed") prima di arrivare al ripiego http.
            // 30 s come iOS (timeoutIntervalForRequest copre anche la connessione): alcuni server immagini dei provider
            // accettano la connessione lentamente e con 10 s davano "connect timed out" dove iOS carica.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            // iOS (ATS aperto) scarica anche da server immagini con TLS 1.0/1.1 o cifrari datati; OkHttp di default
            // (MODERN_TLS) rifiutava l'handshake e l'icona restava vuota.
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .addInterceptor(HeadersInterceptor)
            .addInterceptor(RetryAndFallbackInterceptor)
            .build()
    }

    private object HeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val builder = request.newBuilder()
            builder.header("User-Agent", USER_AGENT)
            builder.header("Accept", ACCEPT)
            if (request.header("Accept-Language") == null) builder.header("Accept-Language", ACCEPT_LANGUAGE)
            builder.removeHeader("Referer")
            return chain.proceed(builder.build())
        }
    }

    /**
     * Server immagini (host:porta) che non accettano connessioni: messi in pausa per qualche minuto, cosi' le
     * altre immagini dello stesso server falliscono subito (icona sostitutiva) invece di attendere 30 s ciascuna,
     * e gli slot di download restano liberi per gli altri server.
     */
    private object UnreachableHosts {
        private const val PAUSE_MS = 3 * 60_000L
        private val until = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private fun key(url: okhttp3.HttpUrl) = "${url.host.lowercase()}:${url.port}"
        fun isDown(url: okhttp3.HttpUrl): Boolean {
            val deadline = until[key(url)] ?: return false
            if (System.currentTimeMillis() < deadline) return true
            until.remove(key(url))
            return false
        }
        fun markDown(url: okhttp3.HttpUrl) {
            until[key(url)] = System.currentTimeMillis() + PAUSE_MS
        }
    }

    /** User-Agent di Chrome desktop: secondo tentativo per i server che rifiutano i browser mobili. */
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Un tentativo di download: indirizzo + Referer/User-Agent da usare (null = quelli standard, senza Referer). */
    private data class Attempt(val url: okhttp3.HttpUrl, val referer: String? = null, val userAgent: String? = null) {
        val key: String get() = "$url|$referer|$userAgent"
    }

    /** Origine del sito (`https://host/`): Referer accettato dalla protezione anti-hotlink della maggior parte dei server. */
    private fun originOf(url: okhttp3.HttpUrl): String = "${url.scheme}://${url.host}/"

    /**
     * Referer da usare SUBITO per server immagini noti:
     *  - LyngSat (`lyngsat-logo.com`, loghi di moltissime playlist): blocca le richieste senza un Referer del sito (403);
     *  - Wikimedia: senza Referer le app non-browser ricevono piu' facilmente 429 (phabricator T413570).
     */
    private fun defaultReferer(url: okhttp3.HttpUrl): String? {
        val host = url.host.lowercase()
        return when {
            host.endsWith("lyngsat-logo.com") || host.endsWith("lyngsat.com") -> "https://www.lyngsat.com/"
            host == "upload.wikimedia.org" -> "https://commons.wikimedia.org/"
            else -> null
        }
    }

    /** Dopo un 401/403 (protezione anti-hotlink / blocco del client): Referer del sito, poi browser desktop. */
    private fun hotlinkVariants(failed: Attempt): List<Attempt> {
        val origin = originOf(failed.url)
        return buildList {
            if (failed.referer != origin) add(Attempt(failed.url, origin))
            defaultReferer(failed.url)?.let { if (it != failed.referer && it != origin) add(Attempt(failed.url, it)) }
            add(Attempt(failed.url, origin, DESKTOP_USER_AGENT))
            add(Attempt(failed.url, null, DESKTOP_USER_AGENT))
        }
    }

    /**
     * iOS PosterDownloader.fetch: up to 3 attempts per URL for transient failures, then the same resource over
     * plain http. Difference: when https does not even answer in time (connect/handshake timeout, connection
     * refused/reset) the same https address is NOT retried — that server is not serving https — and the http
     * fallback runs immediately instead of after ~1 minute of identical failed attempts.
     * In piu':
     *  - 401/403 (protezione anti-hotlink, es. LyngSat): stessa immagine con il Referer del sito e poi con un
     *    browser desktop, invece di arrendersi;
     *  - miniature Wikimedia di misura non standard (400/429): misura standard e poi immagine originale;
     *  - ridimensionatori "thumbor" che rifiutano la misura: immagine originale contenuta nell'indirizzo.
     */
    private object RetryAndFallbackInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val queue = ArrayDeque<Attempt>()
            val seen = HashSet<String>()
            fun offer(attempt: Attempt, first: Boolean = false) {
                if (!seen.add(attempt.key)) return
                if (first) queue.addFirst(attempt) else queue.addLast(attempt)
            }
            val start = ArtworkUrls.knownHostFixes(original.url.toString()).toHttpUrlOrNull() ?: original.url
            offer(Attempt(start, defaultReferer(start)))
            if (start.isHttps) start.newBuilder().scheme("http").build().let { offer(Attempt(it, defaultReferer(it))) }
            // Ridimensionatori "thumbor" (…/unsafe/<misura>/filters:…/<host>/<percorso>): se rifiutano la richiesta
            // (misura non ammessa, 404) si scarica direttamente l'immagine originale contenuta nell'indirizzo.
            thumborOriginals(start).forEach { offer(Attempt(it, defaultReferer(it))) }
            // Miniatura Wikimedia rifiutata: l'immagine originale.
            ArtworkUrls.wikimediaOriginal(start.toString())?.toHttpUrlOrNull()?.let { offer(Attempt(it, defaultReferer(it))) }

            var lastError: IOException? = null
            var lastResponse: Response? = null
            while (queue.isNotEmpty()) {
                val spec = queue.removeFirst()
                val hasFallback = queue.isNotEmpty()
                // Server che non risponde affatto (connessione scaduta / rifiutata) da poco: non si riprova ora.
                // Prima ogni immagine di quel server teneva occupato uno slot di download per 30 s, rallentando
                // tutte le altre copertine e loghi della schermata.
                if (UnreachableHosts.isDown(spec.url)) {
                    lastError = lastError ?: IOException("Server immagini non raggiungibile: ${spec.url.host}:${spec.url.port} (in pausa per qualche minuto)")
                    continue
                }
                var attempt = 0
                while (true) {
                    if (chain.call().isCanceled()) throw lastError ?: IOException("Canceled")
                    lastResponse?.close()
                    lastResponse = null
                    try {
                        val request = original.newBuilder().url(spec.url).apply {
                            if (spec.referer != null) header("Referer", spec.referer) else removeHeader("Referer")
                            spec.userAgent?.let { header("User-Agent", it) }
                        }.build()
                        val response = chain.proceed(request)
                        // 2xx, 304 (cache revalidation) and 3xx are handed back as they are.
                        if (response.code < 400) return response
                        if (response.code.isTransient() && attempt < 2 &&
                            // Wikimedia risponde 429 alle misure non ammesse: non e' un errore temporaneo.
                            !(response.code == 429 && spec.url.host.equals("upload.wikimedia.org", ignoreCase = true))
                        ) {
                            response.close(); backoff(attempt++); continue
                        }
                        // Protezione anti-hotlink o client rifiutato: prima di passare oltre, la stessa immagine con
                        // Referer del sito / browser desktop.
                        if (response.code == 401 || response.code == 403) {
                            hotlinkVariants(spec).asReversed().forEach { offer(it, first = true) }
                        }
                        lastResponse = response
                    } catch (e: IOException) {
                        if (chain.call().isCanceled()) throw e
                        lastError = e
                        if (e.isUnreachable()) {
                            UnreachableHosts.markDown(spec.url)
                            break
                        }
                        if (hasFallback && e.isConnectionLevel()) break // straight to http
                        if (e.isTransient() && attempt < 2) { backoff(attempt++); continue }
                    }
                    break
                }
            }
            return lastResponse ?: throw (lastError ?: IOException("Immagine non disponibile: ${original.url}"))
        }

        private fun thumborOriginals(url: okhttp3.HttpUrl): List<okhttp3.HttpUrl> {
            val segments = url.pathSegments
            val unsafe = segments.indexOf("unsafe")
            if (unsafe < 0) return emptyList()
            // Dopo "unsafe": misura opzionale ("600x0", "fit-in", "smart"...), filtri opzionali, poi host/percorso.
            var i = unsafe + 1
            while (i < segments.size && (segments[i].matches(Regex("-?\\d*x-?\\d*")) || segments[i] in setOf("fit-in", "full-fit-in", "adaptive-fit-in", "smart", "trim") ||
                    segments[i].startsWith("filters:") || segments[i].matches(Regex("\\d+x\\d+:\\d+x\\d+")))) i++
            if (i >= segments.size - 1) return emptyList()
            val host = segments[i]
            if (!host.contains('.')) return emptyList()
            val path = segments.subList(i + 1, segments.size).joinToString("/")
            // "/http/unsafe/..." indica lo schema dell'originale; si prova comunque anche l'altro.
            val preferHttp = unsafe > 0 && segments[unsafe - 1].equals("http", ignoreCase = true)
            val schemes = if (preferHttp) listOf("http", "https") else listOf("https", "http")
            return schemes.mapNotNull { "$it://$host/$path".toHttpUrlOrNull() }
        }

        // Same retryable statuses as iOS PosterDownloader (408, 425, 429, 5xx incl. 509/512/52x).
        private fun Int.isTransient() = this == 408 || this == 425 || this == 429 || this in 500..599

        // Like iOS (timedOut, networkConnectionLost, cannotConnectToHost, dnsLookupFailed, cannotFindHost): retried.
        // "Socket closed" is a cancellation, never retried.
        private fun IOException.isTransient() =
            this is SocketTimeoutException || this is java.net.ConnectException ||
                this is java.net.UnknownHostException || this is java.net.NoRouteToHostException ||
                (this is InterruptedIOException && message?.contains("timeout", true) == true) ||
                message?.contains("unexpected end of stream", true) == true ||
                message?.contains("connection reset", true) == true

        /** Nessuna connessione possibile con il server (scaduta, rifiutata, nessun percorso). */
        private fun IOException.isUnreachable() =
            this is java.net.ConnectException || this is java.net.NoRouteToHostException ||
                (this is SocketTimeoutException && message?.contains("connect", true) == true)

        /** The https endpoint itself is not usable (no answer / refused / TLS failure): try http right away. */
        private fun IOException.isConnectionLevel() =
            this is java.net.ConnectException || this is java.net.NoRouteToHostException ||
                this is javax.net.ssl.SSLException ||
                (this is SocketTimeoutException && (message?.contains("connect", true) == true || message?.contains("handshake", true) == true)) ||
                message?.contains("connection reset", true) == true

        private fun backoff(attempt: Int) {
            try { Thread.sleep(300L shl attempt) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
}
