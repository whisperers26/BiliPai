package com.android.purebilibili.feature.video.danmaku

/** Geometry shared by every video-danmaku layer; independent of the display-area setting. */
data class DanmakuViewport(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val scale: Float
) {
    init {
        require(widthPx > 0 && heightPx > 0)
        require(density.isFinite() && density > 0f)
        require(scale.isFinite() && scale > 0f && scale <= 1f)
    }
}

private const val DANMAKU_REFERENCE_VIDEO_ASPECT = 16f / 9f

/**
 * Width of a 16:9 video fitted fullscreen into the window; danmaku reach full size there.
 * Tall phones are limited by their short side, near-square foldable screens by their long side.
 */
fun resolveDanmakuReferenceWidthPx(windowWidthPx: Int, windowHeightPx: Int): Float {
    if (windowWidthPx <= 0 || windowHeightPx <= 0) return 0f
    val shortSide = minOf(windowWidthPx, windowHeightPx).toFloat()
    val longSide = maxOf(windowWidthPx, windowHeightPx).toFloat()
    return minOf(longSide, shortSide * DANMAKU_REFERENCE_VIDEO_ASPECT)
}

/** Danmaku scale with the shown video width only, so equal widths always give equal text. */
fun resolveDanmakuViewport(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    referenceWidthPx: Float
): DanmakuViewport? {
    if (widthPx <= 0 || heightPx <= 0 || !density.isFinite() || density <= 0f ||
        !referenceWidthPx.isFinite() || referenceWidthPx <= 0f
    ) return null
    return DanmakuViewport(
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
        scale = (widthPx / referenceWidthPx).coerceAtMost(1f)
    )
}
