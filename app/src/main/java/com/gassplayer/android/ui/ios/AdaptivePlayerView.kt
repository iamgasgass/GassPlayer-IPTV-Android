package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AdaptivePlayerView(url: String, title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(60.dp))
            Spacer(Modifier.height(10.dp)); Text(title, color = Color.White, fontSize = 19.sp); Text(url, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, maxLines = 2)
        }
        IosGlassIconButton(Icons.Default.Close, "Chiudi player", onClick = onClose, size = 44, tint = Color.White)
    }
}
