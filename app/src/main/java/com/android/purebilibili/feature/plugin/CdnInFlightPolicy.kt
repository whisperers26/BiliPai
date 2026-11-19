package com.android.purebilibili.feature.plugin

import java.net.URI

/*
 * The video request the player is waiting on, judged before it stalls playback. Ported from
 * bilibili-accelerator's stuck-fragment trigger (docs/vod-routing.md, "When to switch"): a request
 * that will finish after the buffer runs out, or that has had no first byte for a second with
 * little buffered, is raced at once instead of waiting for the player to stall.
 */

private const val NO_FIRST_BYTE_MS = 1_000L
private const val NO_FIRST_BYTE_MAX_BUFFER_MS = 3_000L
private const val JUDGE_AFTER_MS = 500L
private const val JUDGE_AFTER_BYTES = 128L * 1024L
private const val MIN_JUDGED_LENGTH = 128L * 1024L
private const val SLOW_RATE_FACTOR = 1.3
private const val MIN_REMAINING_MS = 1_000L
private const val BUFFER_SPARE_MS = 2_000L

/** Ticks faster while little is buffered, so a stuck request is caught within a quarter second. */
internal const val CDN_SIGNAL_FAST_INTERVAL_MS = 250L
internal const val CDN_SIGNAL_INTERVAL_MS = 1_000L
internal const val CDN_SIGNAL_FAST_BELOW_BUFFER_MS = 10_000L

/** One HTTP request of a foreground video track: [position] is where it started in the file. */
internal data class CdnInFlightRequest(
    val id: Long,
    val originalUrl: String,
    val host: String,
    val position: Long,
    val startedMs: Long,
    val firstByteMs: Long?,
    val bytes: Long,
    val length: Long?,
    val total: Long?,
)

/** Bytes per second from the request's start, first-byte wait included: what the player pays. */
internal fun CdnInFlightRequest.rateBps(nowMs: Long): Long {
    val elapsed = nowMs - startedMs
    return if (elapsed <= 0) 0 else bytes * 1_000 / elapsed
}

/** True when [request] will hold up playback: [requiredVideoBps] is in bits/s, 0 when unknown. */
internal fun isStuckCdnRequest(request: CdnInFlightRequest, nowMs: Long, bufferMs: Long, requiredVideoBps: Long): Boolean {
    val elapsed = nowMs - request.startedMs
    if (request.firstByteMs == null) {
        return elapsed >= NO_FIRST_BYTE_MS && bufferMs < NO_FIRST_BYTE_MAX_BUFFER_MS
    }
    if (elapsed < JUDGE_AFTER_MS && request.bytes < JUDGE_AFTER_BYTES) return false
    val length = request.length ?: return false
    if (length < MIN_JUDGED_LENGTH || requiredVideoBps <= 0) return false
    val remaining = length - request.bytes
    if (remaining <= 0) return false
    val rate = request.rateBps(nowMs)
    if (rate * 8 >= requiredVideoBps * SLOW_RATE_FACTOR) return false
    val remainingMs = if (rate <= 0) Long.MAX_VALUE else remaining * 1_000 / rate
    return remainingMs > MIN_REMAINING_MS && remainingMs > bufferMs - BUFFER_SPARE_MS
}

/**
 * The video a URL belongs to, from Bilibili's media path `/upgcxcode/aa/bb/<cid>/<cid>-1-<qn>.m4s`:
 * every quality and codec of one video shares the directory, so a quality change keeps routing.
 */
internal fun cdnSessionKeyOf(url: String): String? = runCatching {
    val path = URI(url).rawPath.orEmpty()
    if (!path.startsWith("/upgcxcode/")) return@runCatching null
    path.substringBeforeLast('/').takeIf { it.count { ch -> ch == '/' } >= 4 }
}.getOrNull()
