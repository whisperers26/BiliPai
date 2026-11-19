package com.android.purebilibili.feature.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CdnRebufferTrackerTest {
    private var now = 100_000L
    private fun tracker() = CdnRebufferTracker(clock = { now }).also {
        it.newSource()
        now += 5_000
        it.observe(buffering = false, ready = true)
    }

    private fun CdnRebufferTracker.stall(ms: Long): Boolean {
        val triggered = observe(buffering = true, ready = false)
        now += ms
        observe(buffering = false, ready = true)
        return triggered
    }

    @Test
    fun `two short stalls within fifteen seconds trigger a race`() {
        val tracker = tracker()
        assertFalse(tracker.stall(500))
        now += 10_000
        assertTrue(tracker.stall(500))
    }

    @Test
    fun `stalls further apart than the window do not trigger`() {
        val tracker = tracker()
        assertFalse(tracker.stall(500))
        now += 16_000
        assertFalse(tracker.stall(500))
        now += 10_000
        assertTrue(tracker.stall(500))
    }

    @Test
    fun `repeated observations of one stall count once`() {
        val tracker = tracker()
        assertFalse(tracker.observe(buffering = true, ready = false))
        now += 300
        assertFalse(tracker.observe(buffering = true, ready = false))
        now += 300
        assertFalse(tracker.observe(buffering = true, ready = false))
    }

    @Test
    fun `rebuffers right after a seek or before the first ready are excused`() {
        val tracker = CdnRebufferTracker(clock = { now })
        tracker.newSource()
        assertFalse(tracker.stall(800)) // start-up
        now += 3_000
        tracker.jumped()
        now += 200
        assertFalse(tracker.stall(800)) // seek
        now += 3_000
        assertFalse(tracker.stall(500)) // first real stall
        now += 3_000
        tracker.jumped()
        assertFalse(tracker.stall(500)) // seek again, still excused
        now += 3_000
        assertTrue(tracker.stall(500)) // second real stall inside the window
    }

    @Test
    fun `a seek during a stall excuses that stall's stuck time`() {
        val tracker = tracker()
        tracker.observe(buffering = true, ready = false)
        now += STUCK_BUFFERING_MS
        assertTrue(tracker.stuck())
        tracker.jumped()
        assertFalse(tracker.stuck())
        now += JUMP_STUCK_BUFFERING_MS
        assertTrue(tracker.stuck())
    }

    @Test
    fun `stuck needs one continuous second, or longer after a jump`() {
        val tracker = tracker()
        tracker.observe(buffering = true, ready = false)
        now += STUCK_BUFFERING_MS - 1
        assertFalse(tracker.stuck())
        now += 1
        assertTrue(tracker.stuck())
        assertEquals(STUCK_BUFFERING_MS, tracker.stuckThresholdMs())

        val fresh = CdnRebufferTracker(clock = { now })
        fresh.newSource()
        fresh.observe(buffering = true, ready = false)
        assertEquals(JUMP_STUCK_BUFFERING_MS, fresh.stuckThresholdMs())
        now += STUCK_BUFFERING_MS
        assertFalse(fresh.stuck())
        now += JUMP_STUCK_BUFFERING_MS
        assertTrue(fresh.stuck())
    }

    @Test
    fun `a race clears the stall history`() {
        val tracker = tracker()
        tracker.stall(500)
        tracker.raced()
        now += 3_000
        assertFalse(tracker.stall(500))
        now += 3_000
        assertTrue(tracker.stall(500))
    }
}
