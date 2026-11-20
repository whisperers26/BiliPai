package com.android.purebilibili.feature.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class CdnJudgedRateTest {
    private val record = 2_500_000L // 20 Mbit/s of earlier bursts
    private val inFlight = 200_000L // 1.6 Mbit/s now

    @Test
    fun `a stalled player judges the host by the request in flight, not its record`() {
        assertEquals(inFlight, cdnJudgedRateBps(CdnRaceTrigger.STUCK, record, inFlight, recentlyFailed = false))
        assertEquals(inFlight, cdnJudgedRateBps(CdnRaceTrigger.REBUFFER, record, inFlight, recentlyFailed = false))
        assertEquals(inFlight, cdnJudgedRateBps(CdnRaceTrigger.ERRORS, record, inFlight, recentlyFailed = false))
    }

    @Test
    fun `a slow request with buffer left keeps the record unless the host just failed`() {
        assertEquals(record, cdnJudgedRateBps(CdnRaceTrigger.STUCK_REQUEST, record, inFlight, recentlyFailed = false))
        assertEquals(inFlight, cdnJudgedRateBps(CdnRaceTrigger.STUCK_REQUEST, record, inFlight, recentlyFailed = true))
    }

    @Test
    fun `a shortfall is judged on the record alone`() {
        assertEquals(record, cdnJudgedRateBps(CdnRaceTrigger.SHORTFALL, record, inFlight, recentlyFailed = true))
    }
}
