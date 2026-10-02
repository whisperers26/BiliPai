package com.android.purebilibili.core.util

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState

data class ScrollToTopPlan(
    val preJumpIndex: Int?,
    val animateTargetIndex: Int = 0
)

/**
 * 长列表回顶策略：
 * - 近距离直接平滑到顶部
 * - 已知当前视口容量时，远距离先定位到约两屏外，再平滑到顶部
 * - fast = true 时（如底栏点击/重选回顶/双击回顶），先定位到约一个视口外，
 *   再完成最后一屏动画。窗口按实际可见项数计算，避免大屏多列时固定 2 项落在同一行，
 *   看起来只有一次硬跳和一小段卡顿动画
 * - 未提供视口容量的旧调用继续使用固定分段策略
 */
fun resolveScrollToTopPlan(
    firstVisibleItemIndex: Int,
    visibleItemCount: Int? = null,
    fast: Boolean = false,
): ScrollToTopPlan {
    val index = firstVisibleItemIndex.coerceAtLeast(0)
    val measuredViewportItems = visibleItemCount?.takeIf { it > 0 }
    val preJump = if (fast) {
        val fastWindow = measuredViewportItems?.coerceIn(4, 12) ?: 4
        fastWindow.takeIf { index > fastWindow }
    } else if (measuredViewportItems != null) {
        val viewportItems = measuredViewportItems.coerceIn(4, 16)
        val animatedWindowItems = (viewportItems * 2).coerceIn(8, 32)
        val directAnimationLimit = animatedWindowItems + viewportItems
        animatedWindowItems.takeIf { index > directAnimationLimit }
    } else {
        when {
            index > 180 -> 28
            index > 96 -> 20
            index > 36 -> 12
            index > 14 -> 6
            else -> null
        }
    }
    return ScrollToTopPlan(preJumpIndex = preJump)
}

fun resolveFastScrollToTopPlan(
    firstVisibleItemIndex: Int,
    visibleItemCount: Int? = null,
): ScrollToTopPlan = resolveScrollToTopPlan(
    firstVisibleItemIndex = firstVisibleItemIndex,
    visibleItemCount = visibleItemCount,
    fast = true,
)

fun shouldShowScrollToTop(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    offsetThresholdPx: Int = 600,
): Boolean {
    return firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset >= offsetThresholdPx
}

suspend fun LazyListState.animateScrollToTop(fast: Boolean = false) {
    val plan = resolveScrollToTopPlan(
        firstVisibleItemIndex = firstVisibleItemIndex,
        visibleItemCount = layoutInfo.visibleItemsInfo.size,
        fast = fast,
    )
    plan.preJumpIndex?.let { scrollToItem(it) }
    animateScrollToItem(plan.animateTargetIndex)
}

suspend fun LazyGridState.animateScrollToTop(fast: Boolean = false) {
    val plan = resolveScrollToTopPlan(
        firstVisibleItemIndex = firstVisibleItemIndex,
        visibleItemCount = layoutInfo.visibleItemsInfo.size,
        fast = fast,
    )
    plan.preJumpIndex?.let { scrollToItem(it) }
    animateScrollToItem(plan.animateTargetIndex)
}

/**
 * 估算当前位置到列表顶部的像素距离：已滚出首个可见项的偏移 + 其上方整行的平均高度。
 * 全宽项（轮播、分割条）会让结果略有偏差，由调用方在动画后收尾。
 */
fun estimateDistanceToTopPx(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    columns: Int,
    averageRowHeightPx: Int,
): Int {
    val rowsAbove = firstVisibleItemIndex.coerceAtLeast(0) / columns.coerceAtLeast(1)
    return firstVisibleItemScrollOffset.coerceAtLeast(0) + rowsAbove * averageRowHeightPx.coerceAtLeast(0)
}

/**
 * 瀑布流的 animateScrollToItem 会按估算目标分段滚动，每段结束后重新估算，视觉上是走走停停。
 * 这里用一次像素级动画连续滚到估算的顶部，再对剩余偏差收尾。
 */
suspend fun LazyStaggeredGridState.animateScrollToTopContinuously() {
    val items = layoutInfo.visibleItemsInfo
    if (items.isEmpty() || (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0)) return
    val isVertical = layoutInfo.orientation == Orientation.Vertical
    // 只统计单列项（lane >= 0），全宽项的高度和列数不代表一行卡片
    val laneItems = items.filter { it.lane >= 0 }.ifEmpty { items }
    val averageItemSize = laneItems.map { if (isVertical) it.size.height else it.size.width }.average().toInt()
    val columns = (laneItems.maxOf { it.lane } + 1).coerceAtLeast(1)
    val distance = estimateDistanceToTopPx(
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        columns = columns,
        averageRowHeightPx = averageItemSize + layoutInfo.mainAxisItemSpacing,
    )
    if (distance > 0) {
        val durationMillis = (distance / 6).coerceIn(250, 900)
        animateScrollBy(
            value = -distance.toFloat(),
            animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
        )
    }
    if (firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset != 0) {
        animateScrollToItem(0)
    }
}

suspend fun LazyStaggeredGridState.animateScrollToTop(fast: Boolean = false) {
    val plan = resolveScrollToTopPlan(
        firstVisibleItemIndex = firstVisibleItemIndex,
        visibleItemCount = layoutInfo.visibleItemsInfo.size,
        fast = fast,
    )
    plan.preJumpIndex?.let { scrollToItem(it) }
    animateScrollToItem(plan.animateTargetIndex)
}
