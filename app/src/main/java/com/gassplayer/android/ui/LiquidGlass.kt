package com.gassplayer.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
    suppressTopHighlight: Boolean = false,
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
                        if (light) listOf(
                            Color(0xFF52647F).copy(alpha = if (suppressTopHighlight) .08f else borderAlpha * .8f),
                            Color.White.copy(alpha = .60f),
                            Color(0xFF6C91C9).copy(alpha = .14f)
                        ) else listOf(
                            // Do not paint a separate bright specular stripe along the top edge.
                            // Sidebar navigation opts out of that extra line entirely.
                            Color.White.copy(alpha = if (suppressTopHighlight) .025f else borderAlpha),
                            Color.White.copy(alpha = .055f),
                            Color.White.copy(alpha = .035f)
                        )
                    )
                ),
                shape
            )
            .padding(contentPadding),
        content = { content() }
    )
}

/** Focus-responsive material used for DPAD surfaces and touch cards. */
@Composable
fun LiquidGlassFocusableSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    contentPadding: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.018f else 1f, label = "liquid-glass-focus-scale")
    val glow by animateFloatAsState(if (focused) 1f else 0f, label = "liquid-glass-focus-glow")
    LiquidGlassSurface(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .remoteFocusOutline(cornerRadius = cornerRadius)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier.focusable())
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


/**
 * Shared search field used throughout the app. Its shape, fill, border, spacing,
 * typography and clear affordance intentionally match the EPG search bar.
 */
@Composable
fun EpgStyleSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Cerca per nome del programma",
    onSubmit: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onMoveDown: (() -> Boolean)? = null,
    inputFocusRequester: FocusRequester? = null,
    onActivate: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val remoteFirstDevice = remember(context) { context.isTvOrFireTvDevice() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val localContainerFocusRequester = remember { FocusRequester() }
    val localInputFocusRequester = remember { FocusRequester() }
    val containerTarget = focusRequester ?: localContainerFocusRequester
    val inputTarget = inputFocusRequester ?: localInputFocusRequester
    var containerFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var inputPreviouslyFocused by remember { mutableStateOf(false) }
    val searchScope = rememberCoroutineScope()

    // Leave text-entry mode and hand DPAD focus back to the search bar itself. Focus is moved to
    // the container BEFORE the editable node becomes non-focusable, otherwise Compose would clear
    // focus and the remote would "lose" the cursor until the next key press.
    fun finishEditing() {
        if (!remoteFirstDevice) return
        searchScope.launch {
            containerTarget.requestFocusWithRetry(attempts = 3, delayMillis = 30L)
            editing = false
            keyboardController?.hide()
        }
    }

    // First Back closes the IME (handled by the system); the next Back returns to the DPAD state.
    BackHandler(enabled = remoteFirstDevice && editing) { finishEditing() }

    // On TV, the search bar itself is the first focus target. Only OK makes the editable
    // text node focusable, which prevents Android/Fire TV IME from appearing while browsing.
    // Retrying after recomposition also handles slower Fire TV focus-node attachment.
    LaunchedEffect(remoteFirstDevice, editing, inputTarget) {
        if (remoteFirstDevice && editing) {
            kotlinx.coroutines.delay(40L)
            val acquired = inputTarget.requestFocusWithRetry(attempts = 6, delayMillis = 45L)
            if (acquired) {
                keyboardController?.show()
            } else {
                editing = false
                containerTarget.requestFocusWithRetry(attempts = 3, delayMillis = 45L)
            }
        }
    }

    Row(
        modifier = modifier
            .height(50.dp)
            // IMPORTANT: onFocusChanged / outline must sit BEFORE focusable(): a focus-event
            // modifier only observes the focus target that follows it in the chain. Previously
            // they were placed after it, so on TV the bar showed no focus until editing started.
            .onFocusChanged {
                containerFocused = it.isFocused || it.hasFocus
                if (containerFocused) onFocus?.invoke()
            }
            .remoteFocusOutline(cornerRadius = 25.dp, enabled = remoteFirstDevice, includeDescendants = true)
            .then(
                when {
                    remoteFirstDevice -> Modifier.focusRequester(containerTarget).focusable()
                    focusRequester != null -> Modifier.focusRequester(focusRequester).focusable()
                    else -> Modifier
                }
            )
            .clip(RoundedCornerShape(50))
            .background(if (containerFocused) Color(0xFF8CB7FF).copy(.14f) else Color.White.copy(.09f))
            .border(
                BorderStroke(
                    if (containerFocused) 1.8.dp else 1.dp,
                    if (containerFocused) Color(0xFF8CB7FF) else Color.White.copy(.12f)
                ),
                RoundedCornerShape(50)
            )
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else when {
                    event.key == Key.DirectionDown && onMoveDown != null -> onMoveDown.invoke()
                    (event.key == Key.DirectionCenter || event.key == Key.Enter) && remoteFirstDevice && !editing -> {
                        editing = true
                        // Keep optional caller hooks for compatibility, but the input requester
                        // below owns the actual focus hand-off after the editable node is enabled.
                        onActivate?.let { runCatching { it() } }
                        true
                    }
                    (event.key == Key.DirectionCenter || event.key == Key.Enter) && onActivate != null && !remoteFirstDevice -> {
                        onActivate.invoke()
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = "Cerca", tint = glassForeground().copy(.6f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = glassForeground(), fontSize = 16.sp, fontFamily = FontFamily.SansSerif),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = if (onSubmit == null) ImeAction.Done else ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { onSubmit?.invoke(); finishEditing() },
                onDone = { onSubmit?.invoke(); finishEditing() }
            ),
            modifier = Modifier.weight(1f)
                .focusProperties { canFocus = !remoteFirstDevice || editing }
                .focusRequester(inputTarget)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onMoveDown != null) {
                        onMoveDown.invoke()
                    } else false
                }
                .onFocusChanged {
                    if (it.isFocused) {
                        inputPreviouslyFocused = true
                        onFocus?.invoke()
                    } else if (remoteFirstDevice && inputPreviouslyFocused) {
                        inputPreviouslyFocused = false
                        editing = false
                        keyboardController?.hide()
                    }
                },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = glassForeground().copy(.55f), fontSize = 16.sp, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp).remoteFocusOutline(cornerRadius = 50.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Cancella ricerca", tint = glassForeground().copy(.6f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Foreground that follows the same theme preference as the iOS app. */
@Composable
fun glassForeground(alpha: Float = 1f): Color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)
