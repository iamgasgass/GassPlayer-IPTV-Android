package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.data.MediaItem

@Composable
fun MediaHeroHeader(item: MediaItem, onPlay: () -> Unit, onFavorite: () -> Unit, favorite: Boolean, onAlternate: (() -> Unit)? = null) {
    IosGlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            IosPoster(item, width = 190, height = 280)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(item.title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                item.year?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)) }
                item.plot?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), maxLines = 8) }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    item.rating?.let { DetailBadge("★ %.1f".format(it), IosOrange) }
                    item.genre?.takeIf { it.isNotBlank() }?.split(',')?.take(3)?.forEach { DetailBadge(it.trim(), IosBlue) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IosGlassPrimaryButton("Riproduci", Icons.Default.PlayArrow, modifier = Modifier.widthIn(min = 180.dp), onClick = onPlay)
                    IosGlassIconButton(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Preferito", onClick = onFavorite)
                    if (onAlternate != null) IosGlassPrimaryButton("Altre fonti", Icons.Default.SwapHoriz, modifier = Modifier.widthIn(min = 150.dp), onClick = onAlternate)
                }
            }
        }
    }
}

@Composable
fun MediaMetaRow(label: String, value: String, tint: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = tint, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun MediaRatingsSection(item: MediaItem, imdb: String? = null, rottenTomatoes: String? = null, metascore: Int? = null, trakt: Double? = null) {
    IosGlassCard {
        IosSectionHeader("Valutazioni")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.rating?.let { DetailBadge("TMDB %.1f".format(it), IosBlue) }
            imdb?.takeIf { it.isNotBlank() }?.let { DetailBadge("IMDb $it", Color(0xFFF5C518)) }
            rottenTomatoes?.takeIf { it.isNotBlank() }?.let { DetailBadge("RT $it", IosRed) }
            metascore?.let { DetailBadge("Meta $it", IosGreen) }
            trakt?.let { DetailBadge("Trakt %.1f".format(it), IosPurple) }
        }
    }
}

@Composable
private fun DetailBadge(text: String, tint: Color) {
    Surface(shape = RoundedCornerShape(9.dp), color = tint.copy(alpha = 0.14f), border = BorderStroke(1.dp, tint.copy(alpha = 0.20f))) {
        Text(text, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
fun MediaCastSection(cast: List<String>) {
    if (cast.isEmpty()) return
    IosGlassCard {
        IosSectionHeader("Cast")
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            items(cast.take(12)) { member ->
                Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = IosBlue.copy(alpha = 0.12f), modifier = Modifier.size(52.dp)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(member.trim().take(1).uppercase(), fontWeight = FontWeight.Bold, color = IosBlue) } }
                    Spacer(Modifier.height(4.dp)); Text(member.trim(), fontSize = 10.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
