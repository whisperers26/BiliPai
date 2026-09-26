package com.bytedance.danmaku.render.engine.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class StepperTimeTest {

    @Test
    fun `high refresh frames advance by their real interval`() {
        assertEquals(8L, resolveStepperTime(8L))
    }

    @Test
    fun `low refresh frames advance by their real interval so scrolling keeps its speed`() {
        // A 30Hz or 10Hz draw rate (idle variable-refresh panels) must not slow danmaku down.
        assertEquals(33L, resolveStepperTime(33L))
        assertEquals(100L, resolveStepperTime(100L))
    }

    @Test
    fun `long stalls are capped so items do not teleport across the screen`() {
        assertEquals(MAX_STEPPER_TIME, resolveStepperTime(5_000L))
    }

    @Test
    fun `clock going backwards does not move items backwards`() {
        assertEquals(0L, resolveStepperTime(-20L))
    }
}
