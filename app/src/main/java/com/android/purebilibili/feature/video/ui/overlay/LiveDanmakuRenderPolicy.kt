package com.android.purebilibili.feature.video.ui.overlay

import com.android.purebilibili.core.store.DanmakuSettings
import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.android.purebilibili.feature.video.danmaku.DanmakuConfig
import com.android.purebilibili.feature.video.danmaku.DanmakuViewport
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuTextSizePx

internal inline fun <T> appendLiveDanmakuBatch(
    batch: List<T>,
    append: (List<T>) -> Unit
) {
    if (batch.isNotEmpty()) append(batch)
}

internal fun shouldRenderLiveDanmakuAsBitmap(
    isSuperChat: Boolean,
    emoticonUrl: String?
): Boolean = isSuperChat || !emoticonUrl.isNullOrBlank()

/** Resolves live danmaku through the video player's config so both draw the same settings alike. */
internal fun resolveLiveDanmakuRenderConfig(
    settings: DanmakuSettings,
    viewport: DanmakuViewport
): DanmakuRenderConfig = DanmakuConfig().apply {
    opacity = settings.opacity
    fontScale = settings.fontScale
    fontWeight = settings.fontWeight
    speedFactor = settings.speed
    scrollDurationSeconds = settings.scrollDurationSeconds
    displayAreaRatio = settings.displayArea
    strokeWidth = settings.strokeWidth
    lineHeight = settings.lineHeight
    staticDurationSeconds = settings.staticDurationSeconds
    scrollFixedVelocity = settings.scrollFixedVelocity
    staticDanmakuToScroll = settings.staticDanmakuToScroll
    massiveMode = settings.massiveMode
}.resolveRenderConfig(viewport)

/** Bitmap danmaku are drawn at full size; the engine scales them by the viewport scale. */
internal fun resolveLiveDanmakuBitmapTextSizePx(viewport: DanmakuViewport, fontScale: Float): Float =
    resolveDanmakuTextSizePx(viewport, fontScale) / viewport.scale
