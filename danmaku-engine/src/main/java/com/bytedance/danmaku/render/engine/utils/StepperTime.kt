package com.bytedance.danmaku.render.engine.utils

/** Resolves how far scrolling items advance for a frame drawn [elapsedMs] after the previous one. */
internal fun resolveStepperTime(elapsedMs: Long): Long =
    if (elapsedMs < HIGH_REFRESH_MAX_TIME) elapsedMs else STEPPER_TIME
