package com.android.purebilibili.feature.video.screen

import com.android.purebilibili.core.store.TabletSecondaryDefaultTab

/**
 * Large-screen video geometry for the current application window.
 * Landscape uses a left player column and a 280–425dp side pane.
 */
internal const val LARGE_SCREEN_VIDEO_LANDSCAPE_RATIO = 1.2f
internal const val LARGE_SCREEN_VIDEO_ASPECT_16_9 = 16f / 9f
internal const val LARGE_SCREEN_VIDEO_MIN_SIDE_PANE_DP = 280f
internal const val LARGE_SCREEN_VIDEO_MAX_SIDE_PANE_DP = 425f
internal const val LARGE_SCREEN_VIDEO_SIDE_PANE_BREAKPOINT_DP = 560f
internal const val LARGE_SCREEN_VIDEO_SQUARE_MIN_HEIGHT_FRACTION = 0.39f
internal const val FOLDABLE_COVER_COMPACT_PLAYER_HEIGHT_FRACTION = 0.5f
internal const val FOLDABLE_COVER_COMPACT_HEIGHT_MAX_DP = 480f

internal enum class LargeScreenVideoLayoutMode {
    Phone,
    Landscape,
    Split,
    VerticalThreePane,
    AlmostSquare,
}

internal data class LargeScreenVideoMetrics(
    val mode: LargeScreenVideoLayoutMode,
    val playerWidthDp: Float,
    val playerHeightDp: Float,
    val sidePaneWidthDp: Float,
    val introBelowPlayer: Boolean,
)

/**
 * Phone-layout player height for the current window.
 *
 * Landscape-natural foldable cover displays can be physically held in portrait while Android
 * still reports a wide, compact-height window. A full-width 16:9 player leaves almost no measured
 * height for the detail pager, so the comment LazyColumn cannot receive a useful scroll viewport.
 * Keep the single-column cover layout, but reserve half of a compact-height window for detail UI.
 */
internal fun resolvePhoneInlineVideoViewportHeightDp(
    windowWidthDp: Float,
    windowHeightDp: Float,
    isFoldableCoverWindow: Boolean,
): Float {
    val aspectHeight = (windowWidthDp.coerceAtLeast(0f) / LARGE_SCREEN_VIDEO_ASPECT_16_9)
    if (!isFoldableCoverWindow || windowHeightDp <= 0f) return aspectHeight
    if (windowHeightDp >= FOLDABLE_COVER_COMPACT_HEIGHT_MAX_DP) return aspectHeight
    return minOf(
        aspectHeight,
        windowHeightDp * FOLDABLE_COVER_COMPACT_PLAYER_HEIGHT_FRACTION,
    )
}

internal fun shouldUseLargeScreenVideoLayout(
    windowWidthDp: Float,
    windowHeightDp: Float,
    horizontalAdaptationEnabled: Boolean,
    isFoldableCoverWindow: Boolean = false,
): Boolean {
    if (!horizontalAdaptationEnabled) return false
    if (isFoldableCoverWindow) return false
    if (windowWidthDp <= 0f || windowHeightDp <= 0f) return false
    if (windowWidthDp / windowHeightDp >= LARGE_SCREEN_VIDEO_LANDSCAPE_RATIO) return true
    val fullWidthPlayerHeight = windowWidthDp / LARGE_SCREEN_VIDEO_ASPECT_16_9
    return fullWidthPlayerHeight >= LARGE_SCREEN_VIDEO_SQUARE_MIN_HEIGHT_FRACTION * windowHeightDp
}

internal fun shouldUseDedicatedCollectionColumn(
    availableWidthDp: Float,
    hasCollection: Boolean,
): Boolean {
    if (!hasCollection || availableWidthDp <= 0f) return false
    return availableWidthDp / 3f >= LARGE_SCREEN_VIDEO_MIN_SIDE_PANE_DP
}

internal fun resolveLargeScreenLandscapePlayerWidthDp(
    windowWidthDp: Float,
    windowHeightDp: Float,
): Float {
    var width = (windowHeightDp / windowWidthDp * 1.08f).coerceIn(0.5f, 0.7f) * windowWidthDp
    if (windowWidthDp >= LARGE_SCREEN_VIDEO_SIDE_PANE_BREAKPOINT_DP) {
        val side = (windowWidthDp - width).coerceIn(
            LARGE_SCREEN_VIDEO_MIN_SIDE_PANE_DP,
            LARGE_SCREEN_VIDEO_MAX_SIDE_PANE_DP,
        )
        width = windowWidthDp - side
    }
    return width
}

internal fun resolveLargeScreenVideoMetrics(
    windowWidthDp: Float,
    windowHeightDp: Float,
    isVerticalVideo: Boolean,
    enableVerticalExpand: Boolean = false,
): LargeScreenVideoMetrics {
    if (windowWidthDp <= 0f || windowHeightDp <= 0f) {
        return LargeScreenVideoMetrics(
            mode = LargeScreenVideoLayoutMode.Phone,
            playerWidthDp = windowWidthDp,
            playerHeightDp = windowHeightDp,
            sidePaneWidthDp = 0f,
            introBelowPlayer = true,
        )
    }
    val landscape = windowWidthDp / windowHeightDp >= LARGE_SCREEN_VIDEO_LANDSCAPE_RATIO
    if (enableVerticalExpand && isVerticalVideo && landscape) {
        val playerHeight = windowHeightDp
        val playerWidth = playerHeight / LARGE_SCREEN_VIDEO_ASPECT_16_9
        val side = ((windowWidthDp - playerWidth) / 2f).coerceAtLeast(0f)
        return LargeScreenVideoMetrics(
            mode = LargeScreenVideoLayoutMode.VerticalThreePane,
            playerWidthDp = playerWidth,
            playerHeightDp = playerHeight,
            sidePaneWidthDp = side,
            introBelowPlayer = false,
        )
    }
    if (landscape) {
        val playerWidth = resolveLargeScreenLandscapePlayerWidthDp(windowWidthDp, windowHeightDp)
        val playerHeight = playerWidth / LARGE_SCREEN_VIDEO_ASPECT_16_9
        if (playerHeight > windowHeightDp) {
            val splitHeight = windowHeightDp
            val splitWidth = splitHeight * LARGE_SCREEN_VIDEO_ASPECT_16_9
            return LargeScreenVideoMetrics(
                mode = LargeScreenVideoLayoutMode.Split,
                playerWidthDp = splitWidth.coerceAtMost(windowWidthDp),
                playerHeightDp = splitHeight,
                sidePaneWidthDp = (windowWidthDp - splitWidth).coerceAtLeast(0f),
                introBelowPlayer = false,
            )
        }
        return LargeScreenVideoMetrics(
            mode = LargeScreenVideoLayoutMode.Landscape,
            playerWidthDp = playerWidth,
            playerHeightDp = playerHeight,
            sidePaneWidthDp = (windowWidthDp - playerWidth).coerceAtLeast(0f),
            introBelowPlayer = true,
        )
    }
    val fullWidthPlayerHeight = windowWidthDp / LARGE_SCREEN_VIDEO_ASPECT_16_9
    if (fullWidthPlayerHeight < LARGE_SCREEN_VIDEO_SQUARE_MIN_HEIGHT_FRACTION * windowHeightDp) {
        return LargeScreenVideoMetrics(
            mode = LargeScreenVideoLayoutMode.Phone,
            playerWidthDp = windowWidthDp,
            playerHeightDp = fullWidthPlayerHeight,
            sidePaneWidthDp = 0f,
            introBelowPlayer = true,
        )
    }
    // The player takes the full-width 16:9 frame first; the detail panes share what is left.
    return LargeScreenVideoMetrics(
        mode = LargeScreenVideoLayoutMode.AlmostSquare,
        playerWidthDp = windowWidthDp,
        playerHeightDp = fullWidthPlayerHeight,
        sidePaneWidthDp = 0f,
        introBelowPlayer = true,
    )
}

/**
 * The near-square layout stacks the player at the top of the window, so it starts below the
 * status bar; the other modes keep the player column edge-to-edge and pad the side pane instead.
 */
internal fun shouldReserveStatusBarAbovePlayer(mode: LargeScreenVideoLayoutMode): Boolean {
    return mode == LargeScreenVideoLayoutMode.AlmostSquare
}

/**
 * The near-square layout leaves a short, wide area under the full-width player. A vertical rail on
 * its left holds the page toggle and that page's buttons, and the rest shows one page at a time.
 * The player already offers danmaku actions, so the rail leaves them out.
 */
internal fun shouldUseLargeScreenDetailRail(mode: LargeScreenVideoLayoutMode): Boolean {
    return mode == LargeScreenVideoLayoutMode.AlmostSquare
}

internal const val LARGE_SCREEN_DETAIL_RAIL_WIDTH_DP = 104f
internal const val LARGE_SCREEN_DETAIL_RAIL_ACTION_COLUMNS = 2

/**
 * Most of the info page's height the video info header may take when its details are open, so the
 * recommendations under it stay in view; the open details scroll inside the header.
 */
internal const val LARGE_SCREEN_DETAIL_RAIL_HEADER_MAX_HEIGHT_FRACTION = 0.5f

/**
 * Width of the owner block beside the title in the header info layout: it takes the compact owner
 * layout (name over follow and stats) and leaves the title the rest of the row.
 */
internal const val VIDEO_INFO_HEADER_OWNER_WIDTH_DP = 280f

/** What the area beside the detail rail shows, switched by the rail's toggle. */
internal enum class LargeScreenDetailRailPage(val label: String) {
    /** Video info beside the recommendations; the rail holds the like/coin/favorite actions. */
    INFO("简介"),

    /** Comments across the whole area; the rail holds sort, search and compose. */
    COMMENTS("评论"),
}

internal val DEFAULT_LARGE_SCREEN_DETAIL_RAIL_PAGE = LargeScreenDetailRailPage.INFO

internal fun resolveIncludeRelatedTabInSecondary(mode: LargeScreenVideoLayoutMode): Boolean {
    return mode != LargeScreenVideoLayoutMode.AlmostSquare
}

internal fun resolveRelatedTabFirstInSecondary(
    mode: LargeScreenVideoLayoutMode,
    defaultTab: TabletSecondaryDefaultTab,
): Boolean = resolveIncludeRelatedTabInSecondary(mode) &&
    defaultTab == TabletSecondaryDefaultTab.RELATED
