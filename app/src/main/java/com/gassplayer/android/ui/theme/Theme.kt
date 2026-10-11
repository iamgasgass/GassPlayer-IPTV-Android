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
    // True black (AMOLED-style) background; keep accents blue but remove blue tint
    // from the dark canvas and neutral surfaces.
    val darkScheme = darkColorScheme(
        primary = Color(0xFF78A9FF), onPrimary = Color.Black,
        secondary = Color(0xFFD0D0D0), onSecondary = Color(0xFF171717),
        background = Color.Black, onBackground = Color(0xFFF4F4F4),
        surface = Color.Black, onSurface = Color(0xFFF4F4F4),
        surfaceVariant = Color(0xFF121212), onSurfaceVariant = Color(0xFFD0D0D0),
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
