package com.gassplayer.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

@Composable
fun GassPlayerTheme(theme: String = "system", content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> systemDark }
    val darkScheme = darkColorScheme(
        primary = Color(0xFF78A9FF), onPrimary = Color(0xFF06152F),
        secondary = Color(0xFFB8C7E6), onSecondary = Color(0xFF17243A),
        background = Color(0xFF080A0F), onBackground = Color(0xFFF4F6FC),
        surface = Color(0xFF11141B), onSurface = Color(0xFFF4F6FC),
        surfaceVariant = Color(0xFF202531), onSurfaceVariant = Color(0xFFD0D6E2),
        outline = Color.White.copy(alpha = .18f)
    )
    val lightScheme = lightColorScheme(
        primary = Color(0xFF245DC7), secondary = Color(0xFF526987),
        background = Color(0xFFF4F6FA), surface = Color(0xFFFFFFFF),
        outline = Color(0xFF737B89)
    )
    MaterialTheme(
        colorScheme = if (dark) darkScheme else lightScheme,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = content
    )
}
