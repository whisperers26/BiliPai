package com.android.purebilibili.feature.video.ui.components

import com.android.purebilibili.core.store.RelatedVideoCardHeight
import com.android.purebilibili.core.ui.VIDEO_CARD_TRUNCATED_TITLE_MAX_LINES
import com.android.purebilibili.feature.home.components.cards.HorizontalVideoCoverSizing

/**
 * 更多推荐卡片高度：并排卡片的封面高度固定，不随卡片宽度（旋转、分栏）变化，封面宽度按比例跟随。
 * 低档封面与文字等高，统计下方不留空白；上下排列的窄卡封面占满卡片宽度，不受影响。
 */
internal fun resolveRelatedVideoCoverSizing(height: RelatedVideoCardHeight): HorizontalVideoCoverSizing =
    when (height) {
        RelatedVideoCardHeight.HIGH -> HorizontalVideoCoverSizing.FixedHeight(RELATED_VIDEO_HIGH_COVER_HEIGHT_DP)
        RelatedVideoCardHeight.MEDIUM -> HorizontalVideoCoverSizing.FixedHeight(RELATED_VIDEO_MEDIUM_COVER_HEIGHT_DP)
        RelatedVideoCardHeight.LOW -> HorizontalVideoCoverSizing.FitInfo
    }

/** 竖屏手机上原有的封面高度。 */
internal const val RELATED_VIDEO_HIGH_COVER_HEIGHT_DP = 120f
internal const val RELATED_VIDEO_MEDIUM_COVER_HEIGHT_DP = 106f

/** 低档按标题最大行数预留高度，单行标题的卡片与双行标题等高。 */
internal fun resolveRelatedVideoTitleMinLines(
    height: RelatedVideoCardHeight,
    titleMaxLines: Int,
): Int = if (height == RelatedVideoCardHeight.LOW) {
    titleMaxLines.coerceIn(1, VIDEO_CARD_TRUNCATED_TITLE_MAX_LINES)
} else {
    1
}

/**
 * 高档保持原样：作者与统计贴在封面底部。中、低档封面变矮后，作者紧跟标题，
 * 不再用空白把元信息推到底部。
 */
internal fun shouldPinRelatedVideoMetaToBottom(height: RelatedVideoCardHeight): Boolean =
    height == RelatedVideoCardHeight.HIGH

/** The narrow half-opened pane cannot leave enough room for title and stats beside a cover. */
internal const val RELATED_VIDEO_HORIZONTAL_MIN_WIDTH_DP = 320

internal enum class RelatedVideoCardLayout {
    HORIZONTAL,
    STACKED,
}

internal fun resolveRelatedVideoCardLayout(availableWidthDp: Int): RelatedVideoCardLayout =
    if (availableWidthDp < RELATED_VIDEO_HORIZONTAL_MIN_WIDTH_DP) {
        RelatedVideoCardLayout.STACKED
    } else {
        RelatedVideoCardLayout.HORIZONTAL
    }

internal fun resolveRelatedVideoListCardLayout(
    viewportWidthPx: Int,
    horizontalContentPaddingPx: Int,
    cardHorizontalPaddingPx: Int,
    density: Float,
): RelatedVideoCardLayout {
    val cardWidthPx = (viewportWidthPx - horizontalContentPaddingPx - 2 * cardHorizontalPaddingPx)
        .coerceAtLeast(0)
    return resolveRelatedVideoCardLayout((cardWidthPx / density).toInt())
}
