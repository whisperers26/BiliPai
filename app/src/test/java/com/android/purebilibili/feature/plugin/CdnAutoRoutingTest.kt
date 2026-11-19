package com.android.purebilibili.feature.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CdnAutoRoutingTest {
    private val cosov = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/11/22/v.m4s?os=cosovbv&sign=a"
    private val akam = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/11/22/v.m4s?os=akam&hdnts=b"
    private val audio = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/11/22/a.m4s?os=cosovbv&sign=c"

    /** Never draws the one-in-five random challenger. */
    private val noRandom = object : Random() { override fun nextBits(bitCount: Int): Int = 2 }

    private var now = 1_000_000L
    private fun router() = CdnAutoRouter(clock = { now }, random = noRandom)

    @Test
    fun `classifies upos akamai pcdn and redirect hosts`() {
        assertEquals(CdnHostKind.UPOS, classifyCdnHost("upos-sz-mirrorcosov.bilivideo.com"))
        assertEquals(CdnHostKind.UPOS, classifyCdnHost("upos-tf-all-hw.bilivideo.com"))
        assertEquals(CdnHostKind.AKAMAI, classifyCdnHost("upos-hz-mirrorakam.akamaized.net"))
        assertEquals(CdnHostKind.PCDN, classifyCdnHost("xy1x2x3x4xy.mcdn.bilivideo.cn"))
        assertEquals(CdnHostKind.PCDN, classifyCdnHost("1.2.3.4"))
        assertEquals(CdnHostKind.OTHER, classifyCdnHost("upos-sz-302kodo.bilivideo.com"))
        assertEquals(CdnHostKind.OTHER, classifyCdnHost("cn-hk-eq-01-01.bilivideo.com"))
    }

    @Test
    fun `only https upgcxcode upos urls outside mcdn are swappable`() {
        assertTrue(isSwappableUposUrl(cosov))
        assertFalse(isSwappableUposUrl(akam))
        assertFalse(isSwappableUposUrl(cosov.replace("https", "http")))
        assertFalse(isSwappableUposUrl(cosov.replace("os=cosovbv", "os=mcdn")))
        assertFalse(isSwappableUposUrl("https://upos-sz-mirrorcos.bilivideo.com/v1/resource/x.m4s"))
        assertFalse(isSwappableUposUrl("https://1.2.3.4:4483/upgcxcode/11/v.m4s"))
    }

    @Test
    fun `route candidates keep issued urls first and swap the upos path onto the pool`() {
        val routes = buildCdnRouteCandidates(listOf(akam, cosov))

        assertEquals(listOf("upos-hz-mirrorakam.akamaized.net", "upos-sz-mirrorcosov.bilivideo.com"),
            routes.take(2).map { it.host })
        assertTrue(routes.take(2).all { it.issued })
        val ali = routes.single { it.host == "upos-sz-mirrorali.bilivideo.com" }
        assertFalse(ali.issued)
        assertEquals(cosov.replace("upos-sz-mirrorcosov", "upos-sz-mirrorali"), ali.url)
        assertEquals(1, routes.count { it.host == "upos-sz-mirrorcosov.bilivideo.com" })
    }

    @Test
    fun `route candidates do not invent swaps without a swappable issued url`() {
        val pcdn = "https://xy1x2x3x4xy.mcdn.bilivideo.cn:4483/upgcxcode/11/v.m4s?os=mcdn"
        assertEquals(listOf(akam, pcdn), buildCdnRouteCandidates(listOf(akam, pcdn)).map { it.url })
    }

    @Test
    fun `estimator needs enough bytes and falls fast`() {
        val estimator = CdnThroughputEstimator()
        estimator.sample(durationMs = 100, bytes = 10_000)
        assertNull(estimator.estimateBps())
        repeat(4) { estimator.sample(durationMs = 1_000, bytes = 1_000_000) }
        assertEquals(1_000_000.0, estimator.estimateBps()!!.toDouble(), 1_000.0)
        estimator.sample(durationMs = 2_000, bytes = 200_000)
        assertTrue(estimator.estimateBps()!! < 700_000)
    }

    @Test
    fun `new video starts on its own assignment`() {
        val router = router()
        router.register(listOf(cosov, akam), listOf(audio))
        router.beginRace()
        router.finishRace("upos-sz-mirrorcosov.bilivideo.com", 100_000,
            listOf(CdnRaceResult("upos-sz-mirrorali.bilivideo.com", CDN_RACE_BYTES, 300, true)))
        assertEquals("upos-sz-mirrorali.bilivideo.com", router.activeHost)
        assertEquals(cosov.replace("upos-sz-mirrorcosov", "upos-sz-mirrorali"), router.resolve(cosov))
        assertEquals(audio.replace("upos-sz-mirrorcosov", "upos-sz-mirrorali"), router.resolve(audio))

        router.register(listOf(cosov.replace("/22/", "/33/")), emptyList())
        assertNull(router.activeHost)
    }

    @Test
    fun `active akamai host resolves to the issued akamai url of the same path`() {
        val router = router()
        router.register(listOf(cosov, akam), emptyList())
        router.beginRace()
        router.finishRace("upos-sz-mirrorcosov.bilivideo.com", 0,
            listOf(CdnRaceResult("upos-hz-mirrorakam.akamaized.net", CDN_RACE_BYTES, 500, true)))
        assertEquals(akam, router.resolve(cosov))
    }

    @Test
    fun `switch requires the winner to be clearly faster`() {
        val router = router()
        router.register(listOf(cosov), emptyList())
        // Current host needs 786 ms for the race bytes at 1 MB/s; 600 ms is not two thirds of that.
        router.beginRace()
        assertNull(router.finishRace("upos-sz-mirrorcosov.bilivideo.com", 1_000_000,
            listOf(CdnRaceResult("upos-sz-mirrorali.bilivideo.com", CDN_RACE_BYTES, 600, true))))
        assertNull(router.activeHost)
        now += 13_000
        router.beginRace()
        assertEquals("upos-sz-mirrorhw.bilivideo.com", router.finishRace("upos-sz-mirrorcosov.bilivideo.com", 1_000_000,
            listOf(CdnRaceResult("upos-sz-mirrorhw.bilivideo.com", CDN_RACE_BYTES, 400, true))))
    }

    @Test
    fun `stuck triggers a race but shortfall needs evidence and races are budgeted`() {
        val router = router()
        val host = "upos-sz-mirrorcosov.bilivideo.com"
        assertTrue(router.shouldRace(CdnPlaybackSignal(host, bufferMs = 1_000, bitrateBps = 0, stuck = true)))
        assertFalse(router.shouldRace(CdnPlaybackSignal(host, bufferMs = 1_000, bitrateBps = 8_000_000)))
        assertFalse(router.shouldRace(CdnPlaybackSignal(host, bufferMs = null, bitrateBps = 8_000_000, stuck = true)))

        repeat(10) { router.recordTransfer(host, 500_000, 1_000) }
        assertTrue(router.shouldRace(CdnPlaybackSignal(host, bufferMs = 10_000, bitrateBps = 8_000_000)))
        assertFalse(router.shouldRace(CdnPlaybackSignal(host, bufferMs = 40_000, bitrateBps = 8_000_000)))
        assertFalse(router.shouldRace(CdnPlaybackSignal(host, bufferMs = 10_000, bitrateBps = 8_000_000, metered = true)))

        router.beginRace()
        assertFalse(router.shouldRace(CdnPlaybackSignal(host, bufferMs = 1_000, bitrateBps = 0, stuck = true)))
        router.cancelRace()
        assertFalse(router.shouldRace(CdnPlaybackSignal(host, bufferMs = 1_000, bitrateBps = 0, stuck = true)))
        now += 13_000
        assertTrue(router.shouldRace(CdnPlaybackSignal(host, bufferMs = 1_000, bitrateBps = 0, stuck = true)))
    }

    @Test
    fun `repeated stalls race even on metered networks without throughput evidence`() {
        val router = router()
        val host = "upos-sz-mirrorcosov.bilivideo.com"
        assertTrue(router.shouldRace(CdnPlaybackSignal(host, bufferMs = 20_000, bitrateBps = 8_000_000, rebuffering = true, metered = true)))
        assertFalse(router.shouldRace(CdnPlaybackSignal(host, bufferMs = null, bitrateBps = 8_000_000, rebuffering = true)))
    }

    @Test
    fun `a stall race switches only to a host that can carry the stream`() {
        val router = router()
        router.register(listOf(cosov), emptyList())
        // 1 MB/s carries an 8 Mbit/s stream with the 1.2x margin; 786 KB in 1 s does not reach 1.2 MB/s.
        router.beginRace()
        assertNull(router.finishRace("upos-sz-mirrorcosov.bilivideo.com", 0,
            listOf(CdnRaceResult("upos-sz-mirrorali.bilivideo.com", CDN_RACE_BYTES, 1_000, true)),
            minWinnerBps = 1_200_000))
        now += 13_000
        router.beginRace()
        assertEquals("upos-sz-mirrorhw.bilivideo.com", router.finishRace("upos-sz-mirrorcosov.bilivideo.com", 0,
            listOf(CdnRaceResult("upos-sz-mirrorhw.bilivideo.com", CDN_RACE_BYTES, 500, true)),
            minWinnerBps = 1_200_000))
    }

    @Test
    fun `rate limits stop racing for minutes`() {
        val router = router()
        router.recordFailure("upos-sz-mirrorali.bilivideo.com", cosov, 429, swapped = true)
        val signal = CdnPlaybackSignal("upos-sz-mirrorcosov.bilivideo.com", 1_000, 0, stuck = true)
        now += 60_000
        assertFalse(router.shouldRace(signal))
        now += 5 * 60_000
        assertTrue(router.shouldRace(signal))
    }

    @Test
    fun `challengers include the other issued host and prefer mainland on ties`() {
        val router = router()
        val routes = buildCdnRouteCandidates(listOf(cosov, akam))
        val picked = router.pickChallengers(routes, "upos-sz-mirrorcosov.bilivideo.com", metered = false)

        assertEquals(listOf("upos-hz-mirrorakam.akamaized.net", "upos-sz-mirrorali.bilivideo.com"), picked.map { it.host })
        assertEquals(1, router.pickChallengers(routes, "upos-sz-mirrorcosov.bilivideo.com", metered = true).size)
    }

    @Test
    fun `challengers rank by history and skip resting hosts`() {
        val router = router()
        val routes = buildCdnRouteCandidates(listOf(cosov))
        repeat(4) { router.recordTransfer("upos-tf-all-tx.bilivideo.com", 2_000_000, 1_000) }
        repeat(4) { router.recordTransfer("upos-sz-mirrorali.bilivideo.com", 200_000, 1_000) }
        router.recordFailure("upos-sz-mirrorhw.bilivideo.com", cosov, null, swapped = true)
        router.recordFailure("upos-sz-mirrorhw.bilivideo.com", cosov, null, swapped = true)

        val picked = router.pickChallengers(routes, "upos-sz-mirrorcosov.bilivideo.com", metered = false).map { it.host }

        assertEquals("upos-tf-all-tx.bilivideo.com", picked.first())
        assertFalse("upos-sz-mirrorhw.bilivideo.com" in picked)
    }

    @Test
    fun `swapped 403 bans the host for that path and returns to the assignment`() {
        val router = router()
        router.register(listOf(cosov), emptyList())
        router.beginRace()
        router.finishRace("upos-sz-mirrorcosov.bilivideo.com", 0,
            listOf(CdnRaceResult("upos-sz-mirrorali.bilivideo.com", CDN_RACE_BYTES, 300, true)))
        router.recordFailure("upos-sz-mirrorali.bilivideo.com", cosov.replace("mirrorcosov", "mirrorali"), 403, swapped = true)

        assertNull(router.activeHost)
        assertFalse(router.routesFor(cosov).any { it.host == "upos-sz-mirrorali.bilivideo.com" })
        assertEquals(cosov, router.resolve(cosov))
    }

    @Test
    fun `issued 403 is an expired signature, not a bad host`() {
        val router = router()
        router.register(listOf(cosov), emptyList())
        router.recordFailure("upos-sz-mirrorcosov.bilivideo.com", cosov, 403, swapped = false)
        assertTrue(router.routesFor(cosov).any { it.host == "upos-sz-mirrorcosov.bilivideo.com" })
    }

    @Test
    fun `network change forgets history and routing`() {
        val router = router()
        router.networkChanged("wifi")
        router.register(listOf(cosov), emptyList())
        repeat(4) { router.recordTransfer("upos-sz-mirrorali.bilivideo.com", 2_000_000, 1_000) }
        router.beginRace()
        router.finishRace("upos-sz-mirrorcosov.bilivideo.com", 0,
            listOf(CdnRaceResult("upos-sz-mirrorali.bilivideo.com", CDN_RACE_BYTES, 300, true)))

        router.networkChanged("cellular")

        assertNull(router.activeHost)
        assertNull(router.estimateBps("upos-sz-mirrorali.bilivideo.com"))
    }

    @Test
    fun `race window starts where playback reads and stays inside the file`() {
        assertEquals(CdnByteRange(1_000, 1_000 + CDN_RACE_BYTES - 1), raceRange(1_000, null))
        assertEquals(CdnByteRange(10_000_000 - CDN_RACE_BYTES, 9_999_999), raceRange(9_999_000, 10_000_000))
        assertEquals(CdnByteRange(0, 99_999), raceRange(50_000, 100_000))
        assertEquals(403, parseHttpStatus("CDN invalid range: HTTP 403"))
        assertNull(parseHttpStatus("timeout"))
    }
}
