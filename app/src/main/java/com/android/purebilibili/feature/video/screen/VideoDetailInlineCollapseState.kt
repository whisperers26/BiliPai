package com.android.purebilibili.feature.video.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.android.purebilibili.feature.video.policy.VideoDetailScrollUpdate
import com.android.purebilibili.feature.video.policy.reduceVideoDetailPostScroll
import com.android.purebilibili.feature.video.policy.reduceVideoDetailPreScroll


@Stable
internal class InlinePortraitPlayerCollapseState(
    initialOffsetPx: Float = 0f,
    initialRestoreRequested: Boolean = false
) {
    var offsetPx by mutableFloatStateOf(initialOffsetPx)
        private set

    var restoreRequested by mutableStateOf(initialRestoreRequested)
        private set

    /**
     * 切页可能让手势范围变小（暂停时评论页可把播放器隐藏到 0）。共享位移因此被收回
     * 到新范围内；这属于布局校正而不是跟手拖动，高度需要用动画过渡而不是瞬间跳。
     */
    var layoutReconciled by mutableStateOf(false)
        private set

    fun updateOffset(value: Float) {
        offsetPx = value
    }

    fun reset() {
        offsetPx = 0f
        restoreRequested = false
        layoutReconciled = false
    }

    /**
     * 简介与评论共享同一段折叠位移。切页只在折叠被关闭时请求动画展开；
     * 新范围更小时仅收回超界位移，避免因为另一个页签的折叠终点不同而整段弹回。
     */
    fun reconcileLayout(collapseEnabled: Boolean, collapseRangePx: Float) {
        if (offsetPx >= 0f) return
        if (!collapseEnabled) {
            restore()
            return
        }
        val minOffsetPx = -collapseRangePx.coerceAtLeast(0f)
        if (offsetPx < minOffsetPx) {
            offsetPx = minOffsetPx
            layoutReconciled = true
        }
    }

    fun consumeScroll(update: VideoDetailScrollUpdate?): Float {
        if (update == null || update.consumedDeltaPx == 0f) return 0f
        restoreRequested = false
        layoutReconciled = false
        offsetPx = update.nextOffsetPx
        return update.consumedDeltaPx
    }

    fun restore() {
        offsetPx = 0f
        restoreRequested = true
        layoutReconciled = false
    }

    companion object {
        val Saver = listSaver<InlinePortraitPlayerCollapseState, Any>(
            save = { listOf(it.offsetPx, it.restoreRequested) },
            restore = {
                InlinePortraitPlayerCollapseState(
                    initialOffsetPx = it[0] as Float,
                    initialRestoreRequested = it[1] as Boolean
                )
            }
        )
    }
}

@Composable
internal fun rememberInlinePortraitPlayerCollapseState(videoBvid: String) =
    rememberSaveable(videoBvid, saver = InlinePortraitPlayerCollapseState.Saver) {
        InlinePortraitPlayerCollapseState()
    }

/** Upward scrolls collapse the player before the list moves; downward leftovers expand it. */
internal fun InlinePortraitPlayerCollapseState.createScrollConnection(
    scrollEnabled: Boolean,
    isPortraitFullscreen: Boolean,
    layoutAlreadyCollapsed: Boolean,
    collapseRangePx: Float,
): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val scrollUpdate = reduceVideoDetailPreScroll(
            currentOffsetPx = offsetPx,
            deltaPx = available.y,
            // 切页后位移可能暂时超出新范围；先允许它从当前位置
            // 平滑回到合法范围，避免手势第一帧被硬夹到新终点。
            minOffsetPx = minOf(-collapseRangePx, offsetPx),
            inlinePortraitScrollEnabled = scrollEnabled,
            isPortraitFullscreen = isPortraitFullscreen,
            layoutAlreadyCollapsed = layoutAlreadyCollapsed,
        )
        return Offset(0f, consumeScroll(scrollUpdate))
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val scrollUpdate = reduceVideoDetailPostScroll(
            currentOffsetPx = offsetPx,
            deltaPx = available.y,
            minOffsetPx = minOf(-collapseRangePx, offsetPx),
            inlinePortraitScrollEnabled = scrollEnabled,
            isPortraitFullscreen = isPortraitFullscreen,
            layoutAlreadyCollapsed = layoutAlreadyCollapsed,
        )
        return Offset(0f, consumeScroll(scrollUpdate))
    }
}
