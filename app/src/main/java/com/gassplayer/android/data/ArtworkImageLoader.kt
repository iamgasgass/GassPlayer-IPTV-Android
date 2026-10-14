package com.gassplayer.android.data

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
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
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val ACCEPT = "image/webp,image/avif,image/png,image/jpeg,image/svg+xml,image/*;q=0.8,*/*;q=0.5"

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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(HeadersInterceptor)
            .addInterceptor(RetryAndFallbackInterceptor)
            .build()
    }

    private object HeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.header("User-Agent") == null) builder.header("User-Agent", USER_AGENT)
            builder.header("Accept", ACCEPT)
            return chain.proceed(builder.build())
        }
    }

    /** Up to 3 attempts per URL for transient failures, then the same resource over plain http. */
    private object RetryAndFallbackInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val candidates = buildList {
                add(original.url)
                if (original.url.isHttps) add(original.url.newBuilder().scheme("http").build())
            }
            var lastError: IOException? = null
            var lastResponse: Response? = null
            for (url in candidates) {
                var attempt = 0
                while (true) {
                    if (chain.call().isCanceled()) throw IOException("Canceled")
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
                        if (e.isTransient() && attempt < 2) { backoff(attempt++); continue }
                    }
                    break
                }
            }
            return lastResponse ?: throw (lastError ?: IOException("Immagine non disponibile: ${original.url}"))
        }

        private fun Int.isTransient() = this == 408 || this == 429 || this in 500..599

        // DNS failures and TLS errors are not retried on the same URL (the http fallback still runs).
        private fun IOException.isTransient() =
            this is SocketTimeoutException || this is java.net.ConnectException ||
                (this is InterruptedIOException && message?.contains("timeout", true) == true)

        private fun backoff(attempt: Int) {
            try { Thread.sleep(300L shl attempt) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
}
