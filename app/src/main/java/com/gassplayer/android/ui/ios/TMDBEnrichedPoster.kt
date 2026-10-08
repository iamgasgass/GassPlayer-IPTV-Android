package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.data.MediaItem

@Composable
fun TMDBEnrichedPoster(item: MediaItem, modifier: Modifier = Modifier, badge: String? = null) {
    Box(modifier) {
        coil3.compose.AsyncImage(model = item.posterUrl ?: item.logoUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        badge?.let { Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.66f), modifier = Modifier.align(Alignment.BottomStart).padding(7.dp)) { Text(it, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) } }
    }
}
