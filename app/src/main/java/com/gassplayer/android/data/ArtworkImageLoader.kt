package com.gassplayer.android.data

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import okhttp3.ConnectionSpec
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
    // rispondevano diversamente da iOS. Nessun Referer, come iOS.
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
     * iOS PosterDownloader.fetch: up to 3 attempts per URL for transient failures, then the same resource over
     * plain http. Difference: when https does not even answer in time (connect/handshake timeout, connection
     * refused/reset) the same https address is NOT retried — that server is not serving https — and the http
     * fallback runs immediately instead of after ~1 minute of identical failed attempts.
     */
    private object RetryAndFallbackInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val candidates = buildList {
                add(original.url)
                if (original.url.isHttps) add(original.url.newBuilder().scheme("http").build())
            }
            var lastError: IOException? = null
            var lastResponse: Response? = null
            for ((index, url) in candidates.withIndex()) {
                val hasFallback = index < candidates.lastIndex
                var attempt = 0
                while (true) {
                    if (chain.call().isCanceled()) throw lastError ?: IOException("Canceled")
                    lastResponse?.close()
                    lastResponse = null
                    try {
                        val response = chain.proceed(original.newBuilder().url(url).build())
                        // 2xx, 304 (cache revalidation) and 3xx are handed back as they are.
                        if (response.code < 400) return response
                        if (response.code.isTransient() && attempt < 2) {
                            response.close(); backoff(attempt++); continue
                        }
                        lastResponse = response
                    } catch (e: IOException) {
                        if (chain.call().isCanceled()) throw e
                        lastError = e
                        if (hasFallback && e.isConnectionLevel()) break // straight to http
                        if (e.isTransient() && attempt < 2) { backoff(attempt++); continue }
                    }
                    break
                }
            }
            return lastResponse ?: throw (lastError ?: IOException("Immagine non disponibile: ${original.url}"))
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
