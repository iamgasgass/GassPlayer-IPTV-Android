package com.gassplayer.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gassplayer.android.GassPlayerApplication
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import com.gassplayer.android.data.ArtworkUrls
import com.gassplayer.android.data.MediaKind

/**
 * Drop-in replacement of Coil's AsyncImage for catalog artwork (channel icons, posters, backdrops):
 *  - the raw provider value is cleaned first ([ArtworkUrls.clean]: placeholders, escaped slashes,
 *    protocol-relative URLs, spaces/accents...), so values that used to fail silently now load;
 *  - when there is no usable URL, or the download fails, [fallback] is shown (inside a Box with the same
 *    [modifier], centred) instead of an empty cell.
 */
@Composable
internal fun ArtworkImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    alpha: Float = 1f,
    onSuccess: (() -> Unit)? = null,
    fallback: (@Composable BoxScope.() -> Unit)? = null,
) {
    val url = remember(model) { ArtworkUrls.clean(model) }
    val context = LocalContext.current
    // Diagnostica: un valore del provider presente ma scartato come indirizzo non valido.
    LaunchedEffect(model, url) {
        if (url == null && !model.isNullOrBlank()) ArtworkDiagnostics.report(context, model, null, "indirizzo scartato (non valido)")
    }
    var failed by remember(url) { mutableStateOf(false) }
    var retries by remember(url) { mutableIntStateOf(0) }
    // iOS PosterDownloader: a failure is never permanent. After a short pause (15 s) the image is requested
    // again, also in views that stay on screen (EPG rows, player channel list), instead of keeping the
    // placeholder until the view is rebuilt.
    LaunchedEffect(url, failed) {
        if (failed && url != null && retries < MAX_ARTWORK_RETRIES) {
            delay(15_000L)
            retries++
            failed = false
        }
    }
    if (url == null || failed) {
        if (fallback != null) Box(modifier, contentAlignment = Alignment.Center, content = fallback)
        return
    }
    // Inline `data:image/...;base64,` posters: decoded here and handed to Coil as bytes (no network).
    val imageModel: Any? = remember(url) { if (url.startsWith("data:", ignoreCase = true)) decodeDataImage(url) else url }
    if (imageModel == null) {
        if (fallback != null) Box(modifier, contentAlignment = Alignment.Center, content = fallback)
        return
    }
    AsyncImage(
        model = imageModel,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        alpha = alpha,
        onSuccess = { onSuccess?.invoke() },
        onError = { state ->
            ArtworkDiagnostics.report(context, model, url, state.result.throwable.describe())
            failed = true
        },
    )
}

private const val MAX_ARTWORK_RETRIES = 3

private fun decodeDataImage(uri: String): ByteArray? = runCatching {
    val comma = uri.indexOf(',')
    if (comma < 0) return null
    val meta = uri.substring(0, comma)
    val payload = uri.substring(comma + 1)
    if (meta.contains(";base64", ignoreCase = true)) android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
    else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.ISO_8859_1)
}.getOrNull()?.takeIf { it.isNotEmpty() }

/**
 * Logs every icon/poster that cannot be shown to Impostazioni -> Diagnostica (tag ARTWORK), once per address:
 * raw provider value, address requested and the exact reason (HTTP status, DNS, TLS, undecodable image...).
 */
internal object ArtworkDiagnostics {
    private val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private const val MAX_PER_SESSION = 300

    fun report(context: android.content.Context, raw: String?, url: String?, reason: String) {
        val key = url ?: raw ?: return
        if (seen.size >= MAX_PER_SESSION || !seen.add(key)) return
        val app = context.applicationContext as? GassPlayerApplication ?: return
        val line = buildString {
            append("Immagine non caricata: ").append(reason)
            append(" | valore provider=\"").append(raw.orEmpty().take(400)).append('"')
            if (url != null && url != raw) append(" | richiesto=").append(url.take(400))
        }
        app.appScope.launch { runCatching { app.diagnostics.log("WARN", "ARTWORK", line) } }
    }
}

private fun Throwable.describe(): String {
    var t: Throwable? = this
    val parts = ArrayList<String>()
    while (t != null && parts.size < 3) {
        parts += (t.javaClass.simpleName + (t.message?.let { ": " + it.take(200) } ?: ""))
        t = t.cause?.takeIf { it !== t }
    }
    return parts.joinToString(" <- ")
}

/** Neutral icon shown when a channel/poster has no image or it cannot be downloaded. */
@Composable
internal fun ArtworkPlaceholderIcon(kind: MediaKind, size: Dp = 26.dp, alpha: Float = .8f) {
    Icon(
        when (kind) {
            MediaKind.LIVE -> Icons.Default.LiveTv
            MediaKind.MOVIE -> Icons.Default.Movie
            else -> Icons.Default.Tv
        },
        contentDescription = null,
        tint = glassForeground(alpha),
        modifier = Modifier.size(size)
    )
}
