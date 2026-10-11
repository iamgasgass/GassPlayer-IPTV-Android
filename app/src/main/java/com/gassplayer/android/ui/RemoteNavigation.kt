package com.gassplayer.android.ui

import android.content.Context
import android.content.res.Configuration
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Detect regular Android TV and Android-based Amazon Fire TV devices. */
internal fun Context.isTvOrFireTvDevice(): Boolean {
    // Match GassTV's feature-based detection first. Some Fire TV and smart-TV builds do not
    // report UI_MODE_TYPE_TELEVISION consistently, while still advertising Leanback/television.
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val managerUiModeType = uiModeManager?.currentModeType ?: Configuration.UI_MODE_TYPE_NORMAL
    val configurationUiModeType = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val hasTelevisionHardware = packageManager.hasSystemFeature("android.hardware.type.television")
    val isFireTv = isAmazonFireTvBuild(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        product = Build.PRODUCT,
    )
    return isTelevisionEnvironment(
        managerUiModeType = managerUiModeType,
        configurationUiModeType = configurationUiModeType,
        hasLeanbackFeature = hasLeanback,
        hasTelevisionHardware = hasTelevisionHardware,
        isFireTvBuild = isFireTv,
    )
}

/** Fire TV fingerprint helper for per-device defaults outside the DataStore migration. */
internal fun Context.isAmazonFireTvDevice(): Boolean = isAmazonFireTvBuild(
    manufacturer = Build.MANUFACTURER,
    model = Build.MODEL,
    device = Build.DEVICE,
    product = Build.PRODUCT,
)

/** Same runtime feature detection as GassTV, plus the resource mode and AFT fingerprint fallback. */
internal fun isTelevisionEnvironment(
    managerUiModeType: Int,
    configurationUiModeType: Int,
    hasLeanbackFeature: Boolean,
    hasTelevisionHardware: Boolean,
    isFireTvBuild: Boolean,
): Boolean = managerUiModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
    configurationUiModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
    hasLeanbackFeature || hasTelevisionHardware || isFireTvBuild

/** Pure build fingerprint check so Smart TVs/Stick variants can be regression-tested. */
internal fun isAmazonFireTvBuild(
    manufacturer: String,
    model: String,
    device: String,
    product: String,
): Boolean {
    if (!manufacturer.equals("Amazon", ignoreCase = true)) return false
    return model.startsWith("AFT", ignoreCase = true) ||
        device.startsWith("AFT", ignoreCase = true) ||
        product.startsWith("AFT", ignoreCase = true) ||
        model.contains("Fire TV", ignoreCase = true)
}

/**
 * Consistent, high-contrast focus feedback for D-pad/keyboard users. This is deliberately a
 * draw-only outline: it does not change measurement, card positions or the LiquidGlass artwork.
 */
internal fun Modifier.remoteFocusOutline(
    cornerRadius: Dp = 16.dp,
    enabled: Boolean = true,
    color: Color = Color(0xFF8CB7FF),
    strokeWidth: Dp = 2.dp,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .drawWithContent {
            drawContent()
            if (enabled && focused && size.width > 0f && size.height > 0f) {
                drawRemoteFocusRings(color, cornerRadius, strokeWidth)
            }
        }
}

/** Draws the same shared focus halo when focus belongs to a parent but only part is visible. */
internal fun Modifier.remoteFocusHalo(
    focused: Boolean,
    cornerRadius: Dp = 16.dp,
    enabled: Boolean = true,
    color: Color = Color(0xFF8CB7FF),
    strokeWidth: Dp = 2.dp,
): Modifier = this.drawWithContent {
    drawContent()
    if (enabled && focused && size.width > 0f && size.height > 0f) {
        drawRemoteFocusRings(color, cornerRadius, strokeWidth)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRemoteFocusRings(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp,
) {
    val core = strokeWidth.toPx().coerceAtLeast(1f)
    val corner = cornerRadius.toPx()
    fun ring(inset: Float, width: Float, alpha: Float) {
        val left = inset.coerceAtLeast(0f)
        val ringWidth = (size.width - 2f * left).coerceAtLeast(0f)
        val ringHeight = (size.height - 2f * left).coerceAtLeast(0f)
        if (ringWidth > 0f && ringHeight > 0f) {
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(left, left),
                size = Size(ringWidth, ringHeight),
                cornerRadius = CornerRadius((corner - left).coerceAtLeast(0f)),
                style = Stroke(width = width.coerceAtLeast(0.5f)),
            )
        }
    }
    ring(core * 1.9f, core * 3.2f, 0.22f)
    ring(core * 1.05f, core * 2.0f, 0.48f)
    ring(core * 0.42f, core, 1f)
}

/**
 * Request focus only after lazy content and focus nodes have had a chance to attach.
 * Fire TV sticks can compose/measure a row later than an emulator; a single requestFocus()
 * silently failing leaves DPAD navigation without a focused item.
 */
internal suspend fun FocusRequester.requestFocusWithRetry(
    attempts: Int = 5,
    delayMillis: Long = 45L,
): Boolean {
    val totalAttempts = attempts.coerceAtLeast(1)
    for (attempt in 0 until totalAttempts) {
        val focused = runCatching { requestFocus() }.getOrDefault(false)
        if (focused) return true
        if (attempt < totalAttempts - 1) delay(delayMillis.coerceAtLeast(0L))
    }
    return false
}

/** Resolve nested pages to the main rail item that owns them. */
internal fun navigationRailDestination(route: String): String = when (route) {
    "home-customize" -> "home"
    "live-customize" -> "live"
    "movies-customize" -> "movies"
    "series-customize" -> "series"
    "source-manager" -> "sources"
    "epg-manage" -> "epg"
    "vpn", "parental", "diagnostics", "backup", "trakt", "downloads", "merged" -> "settings"
    else -> route
}

/** Prefer the currently selected rail item, falling back to its first entry if stale. */
internal fun selectedFocusIndex(ids: List<String>, selectedId: String): Int =
    ids.indexOf(selectedId).takeIf { it >= 0 } ?: 0

/** Returns the sensible parent destination for Back, or null at the root. */
internal fun destinationAfterRemoteBack(route: String, epgManageReturnRoute: String): String? = when (route) {
    "home" -> null
    "home-customize" -> "home"
    "live-customize" -> "live"
    "movies-customize" -> "movies"
    "series-customize" -> "series"
    "source-manager" -> "sources"
    "epg-manage" -> epgManageReturnRoute
    "vpn", "parental", "diagnostics", "backup", "trakt", "downloads", "merged" -> "settings"
    else -> "home"
}
