package com.android.purebilibili.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

class AppDnsCachePolicyTest {
    @Test
    fun `failed lookups are not replayed to the next request`() {
        assertEquals(Duration.ZERO, buildAppDnsCache().failureTimeToLive)
    }
}
