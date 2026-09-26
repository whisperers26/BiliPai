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
 * Short side of a 16:9 video fitted fullscreen into the window, which is what the danmaku
 * surface measures in fullscreen. Near-square screens (foldable inner displays) letterbox it,
 * so their raw short side would shrink every danmaku.
 */
fun resolveDanmakuReferenceShortSidePx(windowWidthPx: Int, windowHeightPx: Int): Float {
    if (windowWidthPx <= 0 || windowHeightPx <= 0) return 0f
    val shortSide = minOf(windowWidthPx, windowHeightPx).toFloat()
    val longSide = maxOf(windowWidthPx, windowHeightPx).toFloat()
    return minOf(shortSide, longSide / DANMAKU_REFERENCE_VIDEO_ASPECT)
}

fun resolveDanmakuViewport(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    referenceShortSidePx: Float
): DanmakuViewport? {
    if (widthPx <= 0 || heightPx <= 0 || !density.isFinite() || density <= 0f ||
        !referenceShortSidePx.isFinite() || referenceShortSidePx <= 0f
    ) return null
    return DanmakuViewport(
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
        scale = (minOf(widthPx, heightPx) / referenceShortSidePx).coerceAtMost(1f)
    )
}
