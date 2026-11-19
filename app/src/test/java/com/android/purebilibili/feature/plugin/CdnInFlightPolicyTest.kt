package com.android.purebilibili.feature.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CdnInFlightPolicyTest {
    private val required = 8_000_000L // 1 MB/s

    private fun request(elapsedMs: Long, bytes: Long, length: Long? = 2_000_000, firstByte: Boolean = true) =
        CdnInFlightRequest(
            id = 1, originalUrl = "u", host = "h", position = 0, startedMs = 10_000 - elapsedMs,
            firstByteMs = if (firstByte) 10_000 - elapsedMs + 100 else null, bytes = bytes, length = length, total = null,
        )

    @Test
    fun `no first byte for a second is stuck only with little buffered`() {
        assertFalse(isStuckCdnRequest(request(900, 0, firstByte = false), 10_000, 1_000, required))
        assertTrue(isStuckCdnRequest(request(1_000, 0, firstByte = false), 10_000, 2_999, required))
        assertFalse(isStuckCdnRequest(request(5_000, 0, firstByte = false), 10_000, 3_000, required))
    }

    @Test
    fun `a slow request is stuck when it would finish after the buffer runs out`() {
        // 400 KB/s for 2 MB: 4 s left; stuck with 5 s buffered, not with 7 s.
        assertTrue(isStuckCdnRequest(request(1_000, 400_000), 10_000, 5_000, required))
        assertFalse(isStuckCdnRequest(request(1_000, 400_000), 10_000, 7_000, required))
    }

    @Test
    fun `a request fast enough for the stream is never stuck`() {
        // 1.4 MB/s carries an 8 Mbit/s stream with the 1.3x margin.
        assertFalse(isStuckCdnRequest(request(1_000, 1_400_000, length = 8_000_000), 10_000, 0, required))
    }

    @Test
    fun `too early, too short or unknown length or rate is not judged`() {
        assertFalse(isStuckCdnRequest(request(300, 50_000), 10_000, 0, required))
        assertFalse(isStuckCdnRequest(request(1_000, 50_000, length = 100_000), 10_000, 0, required))
        assertFalse(isStuckCdnRequest(request(1_000, 50_000, length = null), 10_000, 0, required))
        assertFalse(isStuckCdnRequest(request(1_000, 50_000), 10_000, 0, 0))
    }

    @Test
    fun `rate counts the first-byte wait`() {
        assertEquals(500_000L, request(2_000, 1_000_000).rateBps(10_000))
    }

    @Test
    fun `every quality of a video shares one session key`() {
        val hevc = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/22/33/123456/123456-1-30080.m4s?os=ali"
        val avc = "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/22/33/123456/123456-1-30064.m4s?os=hw"
        val other = "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/44/55/777/777-1-30064.m4s?os=hw"
        assertEquals(cdnSessionKeyOf(hevc), cdnSessionKeyOf(avc))
        assertTrue(cdnSessionKeyOf(hevc) != cdnSessionKeyOf(other))
        assertNull(cdnSessionKeyOf("https://example.com/v.mp4"))
    }
}
