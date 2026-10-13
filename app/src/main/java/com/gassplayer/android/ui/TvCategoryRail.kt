package com.gassplayer.android.ui

import com.gassplayer.android.ui.theme.readable
import com.gassplayer.android.data.categoryCounts
import com.gassplayer.android.data.countIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal const val TV_CATEGORY_ALL = "__tv_all__"
internal const val TV_CATEGORY_FAVORITES = "__tv_favorites__"
internal const val TV_CATEGORY_NONE = "__none__"

internal data class TvCategoryRailOption(
    val id: String,
    val label: String,
    val count: Int,
    val icon: ImageVector? = null,
    val pinned: Boolean = false,
)

/** LiquidGlass vertical group rail shared by Android TV and mobile "Categorie TV" layouts. */
@Composable
internal fun TvCategoryRail(
    options: List<TvCategoryRailOption>,
    selectedId: String,
    expanded: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    expandedWidth: Dp = 236.dp,
    entryFocusRequester: FocusRequester? = null,
    onMoveRight: (() -> Boolean)? = null,
    /** Route whose side-navigation OK/RIGHT must land on this rail's selected group. */
    entryRoute: String? = null,
    /** Asks the owning page to open the rail when it is collapsed. */
    onRequestExpand: (() -> Unit)? = null,
) {
    val width by animateDpAsState(if (expanded) expandedWidth else 0.dp, label = "tv-category-rail-width")
    val state = rememberLazyListState()
    val selectedOptionFocus = remember { FocusRequester() }
    // One focus requester per node: when the shell supplies its route-entry requester, the selected
    // category row serves both purposes instead of stacking competing FocusRequester modifiers.
    val targetFocusRequester = entryFocusRequester ?: selectedOptionFocus
    val remoteFirstDevice = LocalContext.current.isTvOrFireTvDevice()
    val targetFocusId = options.firstOrNull { it.id == selectedId }?.id ?: options.firstOrNull()?.id
    var previousExpanded by remember { mutableStateOf(expanded) }
    var previousTargetId by remember { mutableStateOf(targetFocusId) }
    var railHasFocus by remember { mutableStateOf(false) }
    val currentOptions by rememberUpdatedState(options)
    val currentSelectedId by rememberUpdatedState(selectedId)
    val currentTarget by rememberUpdatedState(targetFocusRequester)
    val currentContentVisible by rememberUpdatedState(width > 1.dp)

    // Scrolls the lazy rail to the selected group (so its row, which owns the requester, is composed)
    // and focuses it. Waits for options that are still being built on Dispatchers.IO on a slow Stick.
    suspend fun focusSelectedOption(): Boolean {
        var waited = 0
        // Up to ~3 s: group counts over a large catalogue can take a while on a Fire TV Stick.
        while ((currentOptions.isEmpty() || !currentContentVisible) && waited < 150) {
            delay(20L)
            waited++
        }
        val opts = currentOptions
        if (opts.isEmpty() || !currentContentVisible) return false
        val index = selectedFocusIndex(opts.map { it.id }, currentSelectedId).coerceIn(0, opts.lastIndex)
        runCatching { state.scrollToItem(index) }
        return currentTarget.requestFocusWithRetry(attempts = 10, delayMillis = 32L)
    }

    // Side navigation OK / RIGHT on this section: open the rail if needed and land on the selected group.
    RegisterSectionEntry(if (remoteFirstDevice) entryRoute else null) {
        onRequestExpand?.invoke() // idempotent: the page only sets its "expanded" flag to true
        focusSelectedOption()
    }

    LaunchedEffect(expanded, targetFocusId, remoteFirstDevice) {
        val wasJustExpanded = expanded && !previousExpanded
        val selectedTargetChanged = targetFocusId != previousTargetId
        previousExpanded = expanded
        previousTargetId = targetFocusId
        // Touch phones keep their native flow; DPAD focus restoration is specific to Android TV / Fire TV.
        if (!expanded || !remoteFirstDevice) return@LaunchedEffect
        // Move focus ONLY when the user is working with this rail: it was just opened from the content
        // (LEFT / Back / closing a detail), or the selected row changed while the rail holds focus.
        // Never on first composition or when the options are rebuilt: that used to steal focus from the
        // side navigation when a section was opened from Home or merely refreshed in the background.
        if (wasJustExpanded || (selectedTargetChanged && railHasFocus)) {
            focusSelectedOption()
        }
    }

    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .clipToBounds()
            .onFocusChanged { railHasFocus = it.hasFocus }
    ) {
        if (width > 1.dp) {
            LiquidGlassSurface(
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 22.dp,
                contentPadding = 10.dp,
            ) {
                Column(Modifier.fillMaxSize().focusGroup()) {
                    Text(
                        "Categorie TV",
                        color = glassForeground(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp, top = 7.dp, end = 6.dp, bottom = 10.dp),
                    )
                    LazyColumn(
                        state = state,
                        modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        itemsIndexed(options, key = { _, item -> item.id }) { index, item ->
                            if (index > 0 && item.pinned.not() && options.getOrNull(index - 1)?.pinned == true) {
                                HorizontalDivider(
                                    color = glassForeground(.14f),
                                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 7.dp),
                                )
                            }
                            val selected = item.id == selectedId
                            var focused by remember(item.id) { mutableStateOf(false) }
                            Surface(
                                onClick = { onSelect(item.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (item.id == targetFocusId) Modifier.focusRequester(targetFocusRequester) else Modifier)
                                    .onFocusChanged { focused = it.isFocused }
                                    .onPreviewKeyEvent { event ->
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionRight &&
                                            onMoveRight?.invoke() == true
                                    }
                                    .remoteFocusOutline(cornerRadius = 14.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Transparent,
                                border = BorderStroke(
                                    when { focused -> 2.dp; selected -> 1.2.dp; else -> .45.dp },
                                    when { focused -> Color(0xFF8CB7FF); selected -> Color(0xFF8FB3FF).copy(.88f); else -> glassForeground(.12f) },
                                ),
                            ) {
                                LiquidGlassSurface(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 14.dp,
                                    contentPadding = 0.dp,
                                    highlighted = selected || focused,
                                    suppressTopHighlight = true,
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                                    ) {
                                        Icon(
                                            item.icon ?: Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            tint = if (selected) Color(0xFFBBD0FF).readable() else glassForeground(.67f),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            item.label,
                                            color = glassForeground(if (selected) 1f else .82f),
                                            fontSize = 14.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            item.count.toString(),
                                            color = glassForeground(.48f),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberTvCategoryRailOptions(
    categories: List<com.gassplayer.android.data.Category>,
    items: List<com.gassplayer.android.data.MediaItem>,
    favoriteIds: Set<String>,
    countsOverride: Map<String?, Int>? = null,
): List<TvCategoryRailOption> {
    val options by produceState(
        initialValue = emptyList<TvCategoryRailOption>(),
        categories, items, favoriteIds, countsOverride,
    ) {
        value = withContext(Dispatchers.IO) {
            buildTvCategoryRailOptions(categories, items, favoriteIds, countsOverride)
        }
    }
    return options
}

internal fun buildTvCategoryRailOptions(
    categories: List<com.gassplayer.android.data.Category>,
    items: List<com.gassplayer.android.data.MediaItem>,
    favoriteIds: Set<String>,
    countsOverride: Map<String?, Int>? = null,
): List<TvCategoryRailOption> {
    val counts = countsOverride ?: items.categoryCounts()
    val visibleCategories = categories.distinctBy { it.id }
        .filter { (counts[it.id] ?: 0) > 0 }
        .map { TvCategoryRailOption(it.id, it.name, counts[it.id] ?: 0) }
    val knownIds = visibleCategories.mapTo(HashSet()) { it.id }
    val other = counts.keys.filterNotNull()
        .filter { (counts[it] ?: 0) > 0 && it !in knownIds }
        .map { TvCategoryRailOption(it, it, counts[it] ?: 0) }
    val uncategorizedCount = counts[null] ?: 0
    return buildList {
        add(TvCategoryRailOption(TV_CATEGORY_FAVORITES, "Preferiti", items.countIds(favoriteIds), Icons.Default.Star, pinned = true))
        add(TvCategoryRailOption(TV_CATEGORY_ALL, "Tutti", items.size, Icons.Default.Apps, pinned = true))
        if (uncategorizedCount > 0) add(TvCategoryRailOption(TV_CATEGORY_NONE, "Senza categoria", uncategorizedCount, Icons.Default.FolderOpen))
        addAll(visibleCategories)
        addAll(other)
    }.distinctBy { it.id }
}
