package com.gassplayer.android.ui

import android.content.Context
import android.content.res.Configuration
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.Modifier
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
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (enabled && focused && size.width > 0f && size.height > 0f) {
                val stroke = strokeWidth.toPx()
                val inset = stroke / 2f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset, inset),
                    size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                    style = Stroke(width = stroke),
                )
            }
        }
}

/** Returns the sensible parent destination for Back, or null at the root. */
internal fun destinationAfterRemoteBack(route: String, epgManageReturnRoute: String): String? = when (route) {
    "home" -> null
    "home-customize" -> "home"
    "live-customize" -> "live"
    "movies-customize" -> "movies"
    "series-customize" -> "series"
    "source-manager" -> "sources"
    "epg-manage" -> epgManageReturnRoute
    "vpn", "parental", "diagnostics", "backup", "trakt" -> "settings"
    else -> "home"
}
