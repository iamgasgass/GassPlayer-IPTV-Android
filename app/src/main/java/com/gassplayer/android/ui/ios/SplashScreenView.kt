package com.gassplayer.android.ui.ios

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gassplayer.android.R

@Composable
fun SplashScreenView(onFinished: () -> Unit) {
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1600); onFinished() }
    val pulse = rememberInfiniteTransition(label = "splash").animateFloat(initialValue = 0.78f, targetValue = 1.05f, animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "glow")
    IosBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(150.dp * pulse.value).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.gassplayer_icon), "GassPlayer", Modifier.size(120.dp).clip(RoundedCornerShape(27.dp)))
            }
            Spacer(Modifier.height(18.dp))
            Text("GassPlayer", color = Color.White, fontSize = 32.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("IPTV · Xtream Codes · VPN integrata", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
            Spacer(Modifier.height(14.dp)); CircularProgressIndicator(color = Color.White)
        }
    }
}
