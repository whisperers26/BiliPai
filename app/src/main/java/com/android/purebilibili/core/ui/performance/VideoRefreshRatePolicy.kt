package com.android.purebilibili.core.ui.performance

import androidx.media3.common.C
import kotlin.math.abs
import kotlin.math.round

/** 只在当前分辨率内选择档位，优先使用视频播放帧率的整数倍。 */
internal fun resolveIdleVideoDisplayMode(
    modes: List<AppDisplayMode>,
    width: Int,
    height: Int,
    frameRate: Float,
    speed: Float,
): AppDisplayMode? {
    if (!frameRate.isFinite() || !speed.isFinite() || frameRate <= 0f || speed <= 0f) return null
    val playbackRate = frameRate * speed
    if (!playbackRate.isFinite()) return null
    val candidates = modes.filter {
        it.width == width && it.height == height && it.refreshRate.isFinite() &&
            it.refreshRate >= 48f && it.refreshRate + 0.1f >= playbackRate
    }.sortedBy { it.refreshRate }
    // 容忍 23.976/29.97/59.94 与面板标称档位的细小差异。
    return candidates.firstOrNull {
        val multiple = it.refreshRate / playbackRate
        abs(multiple - round(multiple)) <= 0.015f
    } ?: candidates.firstOrNull()
}

/**
 * Media3 默认让视频 Surface 按 帧率×倍速 投票刷新率；30fps 视频会把 LTPO 面板压到 30–60Hz，
 * 滚动弹幕随之掉帧发虚，只有触摸提升（如长按倍速）时才恢复。开弹幕时关闭该投票，
 * 关弹幕时保留默认行为以便省电。
 */
internal fun resolveVideoChangeFrameRateStrategy(danmakuEnabled: Boolean): Int =
    if (danmakuEnabled) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
    else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
