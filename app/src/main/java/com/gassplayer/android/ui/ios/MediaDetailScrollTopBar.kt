package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MediaDetailScrollTopBar(title: String, onBack: () -> Unit, onFavorite: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), tonalElevation = 5.dp, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            IosGlassIconButton(Icons.Default.ArrowBack, "Indietro", onBack, size = 38)
            Spacer(Modifier.width(8.dp))
            Text(title, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            onFavorite?.let { IosGlassIconButton(Icons.Default.StarBorder, "Preferito", it, size = 38) }
        }
    }
}
