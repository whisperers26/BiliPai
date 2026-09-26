package com.android.purebilibili.feature.video.ui.overlay

import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.android.purebilibili.core.store.DanmakuSettings
import com.android.purebilibili.danmaku.engine.DanmakuEngine
import com.android.purebilibili.danmaku.engine.DanmakuItem
import com.android.purebilibili.danmaku.engine.DanmakuRenderView
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.android.purebilibili.feature.video.danmaku.DanmakuTypeFilterSettings
import com.android.purebilibili.feature.video.danmaku.DanmakuViewport
import com.android.purebilibili.feature.video.danmaku.createBitmapDanmaku
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuRenderLayerType
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuTypeface
import com.android.purebilibili.feature.video.danmaku.shouldBlockDanmakuByRules
import com.android.purebilibili.feature.video.danmaku.shouldDisplayStandardDanmaku
import com.android.purebilibili.feature.video.ui.section.DanmakuViewportHost
import java.util.ArrayDeque
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive

private const val LIVE_BATCH_INTERVAL_MS = 100L
private const val LIVE_HISTORY_MS = 20_000L
private const val MAX_ACTIVE_LIVE_DANMAKU = 160
private const val MAX_PENDING_LIVE_DANMAKU = 80
private const val MAX_PENDING_ITEMS_BEFORE_START = 48

/** Live danmaku renderer backed by an append-only, bounded session timeline. */
@Composable
fun LiveDanmakuOverlay(
    danmakuFlow: SharedFlow<LiveDanmakuItem>,
    displayArea: Float = 1f,
    danmakuSettings: DanmakuSettings = DanmakuSettings(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val safeDisplayArea = displayArea.takeIf(Float::isFinite)?.coerceIn(0.25f, 1f) ?: 1f
    val latestDanmakuSettings by rememberUpdatedState(danmakuSettings)
    var renderView by remember { mutableStateOf<DanmakuRenderView?>(null) }
    var engine by remember { mutableStateOf<DanmakuEngine?>(null) }
    var viewport by remember { mutableStateOf<DanmakuViewport?>(null) }
    var startTime by remember { mutableLongStateOf(0L) }
    var isStarted by remember { mutableStateOf(false) }
    val activeItems = remember { ArrayDeque<DanmakuItem>() }
    val pendingItems = remember { ArrayDeque<DanmakuItem>() }
    val pendingItemsBeforeStart = remember { ArrayDeque<LiveDanmakuItem>() }

    // Same measured surface and config resolution as the video player, so equal settings match.
    DanmakuViewportHost(modifier.fillMaxSize()) { hostViewport ->
        SideEffect { viewport = hostViewport }
        AndroidView(
            factory = { viewContext ->
                DanmakuRenderView(viewContext).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    renderView = this
                    engine = this.engine
                    startTime = SystemClock.elapsedRealtime()
                    this.engine.start(0L)
                    isStarted = true
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.engine.updateConfig(
                    resolveLiveDanmakuRenderConfig(
                        settings = danmakuSettings.copy(displayArea = safeDisplayArea),
                        viewport = hostViewport
                    )
                )
            }
        )
    }

    LaunchedEffect(engine, isStarted) {
        while (isActive) {
            val currentEngine = engine
            val currentViewport = viewport
            if (currentEngine != null && currentViewport != null && isStarted) {
                val currentTime = SystemClock.elapsedRealtime() - startTime
                val settings = latestDanmakuSettings
                val textSize = resolveLiveDanmakuBitmapTextSizePx(currentViewport, settings.fontScale)

                while (pendingItemsBeforeStart.isNotEmpty()) {
                    pendingItems.addLast(
                        createLiveDanmakuItem(
                            item = pendingItemsBeforeStart.removeFirst(),
                            currentTime = currentTime,
                            context = context,
                            engine = currentEngine,
                            textSize = textSize,
                            fontWeight = settings.fontWeight,
                            staticDanmakuToScroll = settings.staticDanmakuToScroll
                        )
                    )
                }

                if (pendingItems.isNotEmpty()) {
                    val batch = ArrayList<DanmakuItem>(pendingItems.size)
                    while (pendingItems.isNotEmpty()) {
                        pendingItems.removeFirst().also {
                            batch += it
                            activeItems.addLast(it)
                        }
                    }
                    appendLiveDanmakuBatch(batch, currentEngine::append)
                }

                var trimBefore = currentTime - LIVE_HISTORY_MS
                while (activeItems.isNotEmpty() &&
                    (activeItems.first.showAtTime < trimBefore || activeItems.size > MAX_ACTIVE_LIVE_DANMAKU)
                ) {
                    val removed = activeItems.removeFirst()
                    trimBefore = maxOf(trimBefore, removed.showAtTime + 1L)
                }
                currentEngine.trimBefore(trimBefore)
            }
            delay(LIVE_BATCH_INTERVAL_MS)
        }
    }

    LaunchedEffect(danmakuFlow) {
        danmakuFlow.collect { item ->
            val settings = latestDanmakuSettings
            val typeFilter = DanmakuTypeFilterSettings(
                allowScroll = settings.allowScroll,
                allowTop = settings.allowTop,
                allowBottom = settings.allowBottom,
                allowColorful = settings.allowColorful,
                allowSpecial = settings.allowSpecial
            )
            if (item.isSuperChat && !settings.allowSpecial) {
                return@collect
            }
            if (!item.isSuperChat && !shouldDisplayStandardDanmaku(item.mode, item.color, typeFilter)) {
                return@collect
            }
            val userHash = item.uid.takeIf { it > 0L }?.toString().orEmpty()
            if (shouldBlockDanmakuByRules(item.text, settings.blockRules, userHash)) {
                return@collect
            }
            val currentEngine = engine
            val currentViewport = viewport
            if (!isStarted || currentEngine == null || currentViewport == null || startTime == 0L) {
                if (pendingItemsBeforeStart.size >= MAX_PENDING_ITEMS_BEFORE_START) {
                    pendingItemsBeforeStart.removeFirst()
                }
                pendingItemsBeforeStart.addLast(item)
                return@collect
            }

            val currentTime = SystemClock.elapsedRealtime() - startTime
            val textSize = resolveLiveDanmakuBitmapTextSizePx(currentViewport, settings.fontScale)
            val renderItem = createLiveDanmakuItem(
                item = item,
                currentTime = currentTime,
                context = context,
                engine = currentEngine,
                textSize = textSize,
                fontWeight = settings.fontWeight,
                staticDanmakuToScroll = settings.staticDanmakuToScroll
            )
            if (pendingItems.size >= MAX_PENDING_LIVE_DANMAKU) {
                releaseLiveDanmakuItem(
                    pendingItems.removeFirst(),
                    LiveDanmakuBitmapOwnership.APP_QUEUE_ONLY
                )
            }
            pendingItems.addLast(renderItem)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine?.close()
            renderView?.releaseRenderer()
            activeItems.forEach { item ->
                releaseLiveDanmakuItem(item, LiveDanmakuBitmapOwnership.TIMELINE_DISCARDED)
            }
            pendingItems.forEach { item ->
                releaseLiveDanmakuItem(item, LiveDanmakuBitmapOwnership.APP_QUEUE_ONLY)
            }
            activeItems.clear()
            pendingItems.clear()
            pendingItemsBeforeStart.clear()
            isStarted = false
            engine = null
            renderView = null
        }
    }
}

private fun createLiveDanmakuItem(
    item: LiveDanmakuItem,
    currentTime: Long,
    context: android.content.Context,
    engine: DanmakuEngine,
    textSize: Float,
    fontWeight: Int,
    staticDanmakuToScroll: Boolean
): DanmakuItem {
    val layerType = resolveDanmakuRenderLayerType(item.mode, staticDanmakuToScroll)
    val textColor = if (item.color == 0) AndroidColor.WHITE else (0xFF000000 or item.color.toLong()).toInt()
    val showAtTime = currentTime + 50L

    if (!shouldRenderLiveDanmakuAsBitmap(item.isSuperChat, item.emoticonUrl)) {
        return DanmakuItem().apply {
            text = item.text
            this.textColor = textColor
            this.layerType = layerType
            this.showAtTime = showAtTime
            isSelf = item.isSelf
        }
    }

    return createBitmapDanmaku(
        context = context,
        text = item.text,
        textColor = textColor,
        textSize = textSize,
        layerType = layerType,
        showAtTime = showAtTime,
        enableEmoticon = !item.emoticonUrl.isNullOrBlank(),
        typeface = resolveDanmakuTypeface(fontWeight),
        onUpdate = engine::invalidate
    ).apply { isSelf = item.isSelf }
}

private fun releaseLiveDanmakuItem(
    item: DanmakuItem,
    ownership: LiveDanmakuBitmapOwnership
) {
    if (!shouldManuallyRecycleLiveDanmakuBitmap(ownership)) return
    item.bitmap?.takeUnless { it.isRecycled }?.recycle()
    item.bitmap = null
}
