package com.gassplayer.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Backdrop for the Android port of iOS Liquid Glass. Android does not expose
 * SwiftUI's system glassEffect API with the same backdrop sampling, so this
 * builds a layered, low-cost glass environment without putting blur work on
 * every scrolling frame.
 */
@Composable
fun LiquidGlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    val base = if (light) Color(0xFFE9EFF8) else Color.Black
    Box(modifier.background(base)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = if (light) listOf(Color(0xFF7198E2).copy(alpha = .18f), Color(0xFF526A9B).copy(alpha = .08f), Color.Transparent)
                    else listOf(Color.Black, Color.Black, Color.Black),
                    radius = 1250f
                )
            )
        )
        Box(
            Modifier.align(Alignment.TopEnd).fillMaxSize(.78f).background(
                Brush.radialGradient(
                    colors = if (light) listOf(Color(0xFF79A7D9).copy(alpha = .12f), Color.Transparent)
                    else listOf(Color.Black, Color.Black),
                    radius = 850f
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    if (light) listOf(Color.White.copy(.18f), Color(0xFFE9EFF8).copy(.28f), Color(0xFFE2E9F4).copy(.65f))
                    else listOf(Color.Black, Color.Black, Color.Black)
                )
            )
        )
        content()
    }
}

/** Layered glass card inspired by Views/LiquidGlass/GlassCard.swift. */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    contentPadding: Dp = 16.dp,
    highlighted: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    val borderAlpha = if (highlighted) .42f else .20f
    val glassStops = if (light) listOf(Color.White.copy(alpha = if (highlighted) .94f else .82f), Color(0xFFF5F8FD).copy(alpha = .88f), Color(0xFFDDE6F3).copy(alpha = .78f))
        else listOf(Color.White.copy(alpha = if (highlighted) .17f else .115f), Color(0xFF171717).copy(alpha = .76f), Color(0xFF050505).copy(alpha = .94f))
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(glassStops))
            .border(
                BorderStroke(
                    if (highlighted) 1.2.dp else .7.dp,
                    Brush.verticalGradient(
                        if (light) listOf(Color(0xFF52647F).copy(alpha = borderAlpha * .8f), Color.White.copy(alpha = .78f), Color(0xFF6C91C9).copy(alpha = .18f))
                        else listOf(Color.White.copy(alpha = borderAlpha), Color.White.copy(alpha = .07f), Color.White.copy(alpha = .04f))
                    )
                ),
                shape
            )
            .drawBehind {
                // A narrow neutral specular rim (no blue cast in dark mode).
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = if (highlighted) .20f else .09f), Color.Transparent)
                    ),
                    size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())
                )
            }
            .padding(contentPadding),
        content = {
            Box(Modifier.fillMaxWidth()) {
                content()
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .19f), Color.Transparent)))
                )
            }
        }
    )
}

/** Focus-responsive material used for DPAD surfaces and touch cards. */
@Composable
fun LiquidGlassFocusableSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    contentPadding: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.018f else 1f, label = "liquid-glass-focus-scale")
    val glow by animateFloatAsState(if (focused) 1f else 0f, label = "liquid-glass-focus-glow")
    LiquidGlassSurface(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        cornerRadius = cornerRadius,
        contentPadding = contentPadding,
        highlighted = glow > .5f,
        content = content
    )
}

@Composable
fun LiquidGlassPill(modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    Box(
        modifier = modifier.clip(shape)
            .background(
                if (selected) Brush.verticalGradient(listOf(Color(0xFF8CB7FF).copy(.30f), Color(0xFF3478F6).copy(.17f)))
                else Brush.verticalGradient(if (light) listOf(Color.White.copy(.92f), Color(0xFFE8EEF7).copy(.70f)) else listOf(Color.White.copy(.105f), Color.White.copy(.035f)))
            )
            .border(
                BorderStroke(.75.dp, Brush.verticalGradient(if (light) listOf(Color(0xFF596B86).copy(if (selected) .30f else .18f), Color.White.copy(.85f)) else listOf(Color.White.copy(if (selected) .38f else .22f), Color.White.copy(.07f)))) ,
                shape
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        content = content
    )
}

/** Foreground that follows the same theme preference as the iOS app. */
@Composable
fun glassForeground(alpha: Float = 1f): Color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)
