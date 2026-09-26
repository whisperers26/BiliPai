package com.bytedance.danmaku.render.engine.utils

/** Longest single frame step, so a stalled draw loop does not make items jump across the screen. */
internal const val MAX_STEPPER_TIME = 250L

/**
 * Resolves how far scrolling items advance for a frame drawn [elapsedMs] after the previous one.
 * Uses the real interval: variable-refresh panels drop the draw rate while idle, and a fixed
 * 16ms step for slow frames made danmaku crawl until a touch boosted the refresh rate again.
 */
internal fun resolveStepperTime(elapsedMs: Long): Long = elapsedMs.coerceIn(0L, MAX_STEPPER_TIME)
