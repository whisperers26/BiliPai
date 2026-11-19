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
}
