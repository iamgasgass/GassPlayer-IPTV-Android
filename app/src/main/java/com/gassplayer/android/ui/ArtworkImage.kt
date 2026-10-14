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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
    alpha: Float = 1f,
    onSuccess: (() -> Unit)? = null,
    fallback: (@Composable BoxScope.() -> Unit)? = null,
) {
    val url = remember(model) { ArtworkUrls.clean(model) }
    var failed by remember(url) { mutableStateOf(false) }
    if (url == null || failed) {
        if (fallback != null) Box(modifier, contentAlignment = Alignment.Center, content = fallback)
        return
    }
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alpha = alpha,
        onSuccess = { onSuccess?.invoke() },
        onError = { failed = true },
    )
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
