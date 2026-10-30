package com.android.purebilibili.feature.video.ui.components

import com.android.purebilibili.core.store.RelatedVideoCardHeight
import com.android.purebilibili.core.ui.VIDEO_CARD_TRUNCATED_TITLE_MAX_LINES

/**
 * 更多推荐卡片高度：缩小并排卡片的封面宽度，封面高度随比例一起变低；高档即原有尺寸。
 * 上下排列的窄卡封面占满卡片宽度，不受影响。
 */
internal fun resolveRelatedVideoCoverWidthScale(height: RelatedVideoCardHeight): Float =
    when (height) {
        RelatedVideoCardHeight.HIGH -> 1f
        RelatedVideoCardHeight.MEDIUM -> 0.85f
        RelatedVideoCardHeight.LOW -> 0.7f
    }

/**
 * 低档把标题收为一行，否则两行标题会把卡片撑回中档高度。
 * 「标题完整显示」开启时仍以完整标题为准。
 */
internal fun resolveRelatedVideoTitleTruncatedMaxLines(height: RelatedVideoCardHeight): Int =
    if (height == RelatedVideoCardHeight.LOW) 1 else VIDEO_CARD_TRUNCATED_TITLE_MAX_LINES

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
