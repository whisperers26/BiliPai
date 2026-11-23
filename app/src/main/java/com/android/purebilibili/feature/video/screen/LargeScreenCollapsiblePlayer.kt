package com.android.purebilibili.feature.video.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.purebilibili.feature.video.policy.resolveVideoDetailCollapseProgress
import kotlin.math.roundToInt

private const val LARGE_SCREEN_PLAYER_RESTORE_DURATION_MS = 240

/**
 * The near-square layout's player and detail area. While playback is paused or finished, scrolling
 * the detail lists shrinks the player down to the 立即播放 bar, following the phone layout's rule.
 */
@Composable
internal fun ColumnScope.LargeScreenCollapsiblePlayer(
    expandedPlayerHeightDp: Float,
    collapseState: InlinePortraitPlayerCollapseState,
    isPlaybackPaused: Boolean,
    isPortraitFullscreen: Boolean,
    expandForReturnProvider: () -> Boolean,
    onBack: () -> Unit,
    onHomeClick: () -> Unit,
    onPlay: () -> Unit,
    player: @Composable (Modifier) -> Unit,
    detail: @Composable ColumnScope.(NestedScrollConnection) -> Unit,
) {
    val collapseEnabled = shouldCollapseLargeScreenPlayerOnScroll(
        mode = LargeScreenVideoLayoutMode.AlmostSquare,
        hasTabletopFold = false,
        isPlaybackPaused = isPlaybackPaused,
        isPortraitFullscreen = isPortraitFullscreen,
    )
    val density = LocalDensity.current
    val expandedHeightPx = with(density) { expandedPlayerHeightDp.dp.toPx() }
    val collapsedHeightDp = resolveInlinePlayerCollapsedViewportHeightDp(
        standardCollapsedHeightDp = 0f,
        hidePausedPlayerForComments = true,
        retainPlayToolbar = true,
    )
    val collapseRangePx = (expandedHeightPx - with(density) { collapsedHeightDp.dp.toPx() })
        .coerceAtLeast(0f)
    LaunchedEffect(collapseState, collapseEnabled, collapseRangePx) {
        collapseState.reconcileLayout(
            collapseEnabled = collapseEnabled,
            collapseRangePx = collapseRangePx,
        )
    }
    // The card return measures the player's bounds, so it must fly from the full-size frame.
    val latestExpandForReturn by rememberUpdatedState(expandForReturnProvider)
    val expandForReturn by remember { derivedStateOf { latestExpandForReturn() } }
    LaunchedEffect(collapseState, expandForReturn) {
        if (expandForReturn) collapseState.reset()
    }
    val scrollConnection = remember(collapseState, collapseEnabled, collapseRangePx) {
        collapseState.createScrollConnection(
            scrollEnabled = collapseEnabled,
            isPortraitFullscreen = isPortraitFullscreen,
            layoutAlreadyCollapsed = false,
            collapseRangePx = collapseRangePx,
        )
    }
    val targetOffsetPx = if (expandForReturn) 0f else collapseState.offsetPx
    val animatedOffsetPx by animateFloatAsState(
        targetValue = targetOffsetPx,
        animationSpec = if (collapseState.restoreRequested || collapseState.layoutReconciled) {
            tween(durationMillis = LARGE_SCREEN_PLAYER_RESTORE_DURATION_MS)
        } else {
            snap()
        },
        label = "large_screen_player_collapse",
    )
    val collapseProgress = resolveVideoDetailCollapseProgress(
        playerHeightOffsetPx = animatedOffsetPx,
        collapseRangePx = collapseRangePx,
        isPortraitFullscreen = isPortraitFullscreen,
    )
    val viewportHeightDp = with(density) { (expandedHeightPx + animatedOffsetPx).toDp().value }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val heightPx = (expandedHeightPx + animatedOffsetPx)
                    .roundToInt()
                    .coerceAtLeast(0)
                val placeable = measurable.measure(
                    constraints.copy(minHeight = heightPx, maxHeight = heightPx),
                )
                layout(placeable.width, heightPx) { placeable.place(0, 0) }
            },
    ) {
        player(Modifier.fillMaxSize())
        if (
            shouldShowCollapsedPlayerPlayToolbar(
                isPlaybackPaused = isPlaybackPaused,
                collapseProgress = collapseProgress,
                viewportHeightDp = viewportHeightDp,
                isFullscreen = isPortraitFullscreen,
            )
        ) {
            CollapsedPlayerNavigationBar(
                scrollRatio = collapseProgress,
                // The layout already reserves the status bar above the player.
                topInset = 0.dp,
                onBack = onBack,
                onHomeClick = onHomeClick,
                onPlayClick = {
                    collapseState.restore()
                    onPlay()
                },
                onMoreClick = null,
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(2f),
            )
        }
    }
    detail(scrollConnection)
}
