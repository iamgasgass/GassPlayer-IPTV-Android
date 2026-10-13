package com.gassplayer.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Stile del tema scelto in Impostazioni → Aspetto. */
enum class GassThemeStyle { DARK, MEDIUM, LIGHT }

/** Stile attivo: lo leggono sfondo, superfici vetro e colori "leggibili" ([readable]). */
val LocalGassThemeStyle = staticCompositionLocalOf { GassThemeStyle.DARK }

private val GassShapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))

/** True black (AMOLED-style) background; accents stay blue but the dark canvas has no blue tint. */
private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF78A9FF), onPrimary = Color.Black,
    secondary = Color(0xFFD0D0D0), onSecondary = Color(0xFF171717),
    background = Color.Black, onBackground = Color(0xFFF4F4F4),
    surface = Color.Black, onSurface = Color(0xFFF4F4F4),
    surfaceVariant = Color(0xFF121212), onSurfaceVariant = Color(0xFFD0D0D0),
    outline = Color.White.copy(alpha = .18f)
)

/**
 * "Medio": blu notte delle foto di riferimento. Sfondo con alone blu (#0B0C15 in alto, #1A2434 al centro,
 * #06080C in basso), superfici grafite-blu (#262A34 → #10141E) con filo chiaro, testi quasi bianchi.
 */
private val MediumScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF8CB7FF), onPrimary = Color(0xFF0B1220),
    secondary = Color(0xFFC9CED9), onSecondary = Color(0xFF151A24),
    background = Color(0xFF111724), onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF171D2A), onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF1E2431), onSurfaceVariant = Color(0xFFC3C9D6),
    surfaceContainer = Color(0xFF1A202D), surfaceContainerHigh = Color(0xFF222837),
    surfaceContainerHighest = Color(0xFF2A3140), surfaceContainerLow = Color(0xFF151A26),
    outline = Color.White.copy(alpha = .16f), outlineVariant = Color.White.copy(alpha = .09f)
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF245DC7), onPrimary = Color.White,
    secondary = Color(0xFF526987),
    background = Color(0xFFF4F6FA), onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE6EAF1), onSurfaceVariant = Color(0xFF3D4656),
    outline = Color(0xFF737B89)
)

@Composable
fun GassPlayerTheme(theme: String = "system", content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val style = when (theme) {
        "dark" -> GassThemeStyle.DARK
        "medium" -> GassThemeStyle.MEDIUM
        "light" -> GassThemeStyle.LIGHT
        else -> if (systemDark) GassThemeStyle.DARK else GassThemeStyle.LIGHT
    }
    CompositionLocalProvider(LocalGassThemeStyle provides style) {
        MaterialTheme(
            colorScheme = when (style) {
                GassThemeStyle.DARK -> DarkScheme
                GassThemeStyle.MEDIUM -> MediumScheme
                GassThemeStyle.LIGHT -> LightScheme
            },
            shapes = GassShapes,
            content = content
        )
    }
}

/**
 * Superfici "da TV/video" (player, guida EPG, schede dettaglio TV): come su iOS restano scure anche col tema
 * Chiaro, con testi chiari. Con Scuro e Medio non cambia nulla (Medio mantiene i suoi blu).
 */
@Composable
fun DarkContentTheme(content: @Composable () -> Unit) {
    if (LocalGassThemeStyle.current != GassThemeStyle.LIGHT) {
        content()
    } else {
        CompositionLocalProvider(LocalGassThemeStyle provides GassThemeStyle.DARK) {
            MaterialTheme(colorScheme = DarkScheme, shapes = GassShapes, content = content)
        }
    }
}

/** Colore pieno di pagina (dove serve un fondo opaco, es. intestazione della guida). */
@Composable
@ReadOnlyComposable
fun gassPageBase(): Color = when (LocalGassThemeStyle.current) {
    GassThemeStyle.DARK -> Color.Black
    GassThemeStyle.MEDIUM -> Color(0xFF0F1521)
    GassThemeStyle.LIGHT -> Color(0xFFF4F6FA)
}

/** Vero col tema Chiaro attivo nel punto della composizione. */
@Composable
@ReadOnlyComposable
fun isGassLight(): Boolean = LocalGassThemeStyle.current == GassThemeStyle.LIGHT

/**
 * Accenti e stati pensati per fondo scuro (#9ABEFF, verdi, arancioni, rosa pastello…): sul tema Chiaro
 * diventano illeggibili. Qui vengono scuriti (stessa tinta, ~50% di luminosità) per un contrasto pieno su
 * bianco; sui temi scuri restano identici.
 */
@Composable
@ReadOnlyComposable
fun Color.readable(): Color = if (LocalGassThemeStyle.current != GassThemeStyle.LIGHT) this else Color(
    red = red * .50f,
    green = green * .50f,
    blue = blue * .50f,
    alpha = alpha
)
