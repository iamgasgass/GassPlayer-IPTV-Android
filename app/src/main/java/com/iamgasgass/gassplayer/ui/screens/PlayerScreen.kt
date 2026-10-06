package com.iamgasgass.gassplayer.ui.screens

import android.content.ComponentName
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import com.iamgasgass.gassplayer.MainActivity
import com.iamgasgass.gassplayer.data.WatchProgress
import com.iamgasgass.gassplayer.playback.PlaybackService
import com.iamgasgass.gassplayer.ui.AppState

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    state: AppState,
    id: String,
    explicitUrl: String,
    explicitTitle: String,
    onBack: () -> Unit,
    save: (WatchProgress) -> Unit,
) {
    val context = LocalContext.current
    val channel = state.catalog.channels.firstOrNull { it.id == id }
    val movie = state.catalog.movies.firstOrNull { it.id == id }
    val progress = state.progress.firstOrNull { it.mediaId == id }

    val url = explicitUrl.ifBlank {
        channel?.streamUrl ?: movie?.streamUrl.orEmpty()
    }
    val title = explicitTitle.ifBlank {
        channel?.name ?: movie?.name ?: "Riproduzione"
    }
    val poster = movie?.poster ?: channel?.logo.orEmpty()
    val resumePositionMs = progress?.positionMs ?: 0L

    var controller by remember(url) { mutableStateOf<MediaController?>(null) }

    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect

        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { mediaController ->
                        mediaController.setMediaItem(MediaItem.fromUri(url))
                        if (resumePositionMs > 0L) {
                            mediaController.seekTo(resumePositionMs)
                        }
                        mediaController.prepare()
                        mediaController.playWhenReady = true
                        controller = mediaController
                    }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    DisposableEffect(controller) {
        onDispose {
            controller?.let { player ->
                if (player.duration > 0L && player.currentPosition > 0L) {
                    save(
                        WatchProgress(
                            mediaId = id,
                            title = title,
                            positionMs = player.currentPosition,
                            durationMs = player.duration,
                            poster = poster,
                            streamUrl = url,
                        ),
                    )
                }
                player.release()
            }
            controller = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            url.isBlank() -> EmptyState(
                "Stream non disponibile",
                "Controlla la sorgente e aggiorna il catalogo",
            )
            controller == null -> LoadingView("Connessione al player…")
            else -> AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        player = controller
                        useController = true
                        controllerShowTimeoutMs = 5000
                        layoutParams = ViewGroup.LayoutParams(-1, -1)
                    }
                },
                update = { it.player = controller },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "Indietro",
                    tint = Color.White,
                )
            }
            Text(
                title,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { (context as? MainActivity)?.enterPip() },
            ) {
                Icon(
                    Icons.Default.PictureInPictureAlt,
                    "Picture in Picture",
                    tint = Color.White,
                )
            }
        }
    }
}
