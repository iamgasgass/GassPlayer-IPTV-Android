package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlayerInfoOverlay(title: String, subtitle: String? = null, badges: List<String> = emptyList(), modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp) }
        if (badges.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { badges.forEach { PlayerBadgeChip(it) } }
    }
}

@Composable
fun PlayerBadgeChip(text: String) {
    Surface(shape = RoundedCornerShape(7.dp), color = Color.Black.copy(alpha = 0.60f)) { Text(text, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) }
}
