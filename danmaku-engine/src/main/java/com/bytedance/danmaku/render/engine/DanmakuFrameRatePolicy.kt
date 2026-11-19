package com.bytedance.danmaku.render.engine

internal data class DanmakuDisplayMode(val width: Int, val height: Int, val refreshRate: Float)

/**
 * Scrolling text smears visibly at 60Hz on high-refresh panels. Without an explicit vote, Android 15+
 * files an invalidating View under the NORMAL category (60Hz) and only touch boosts lift the panel
 * higher, so ask for the fastest rate at the current resolution while danmaku animates.
 */
internal fun resolveDanmakuFrameRate(modes: List<DanmakuDisplayMode>, width: Int, height: Int): Float? =
    modes.filter { it.width == width && it.height == height && it.refreshRate.isFinite() && it.refreshRate > 0f }
        .maxOfOrNull { it.refreshRate }
