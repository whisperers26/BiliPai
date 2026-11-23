package com.android.purebilibili.data.repository

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeWebFeedRetryPolicyTest {

    @Test
    fun signatureRejectionsAndConnectionFailuresRetryOnce() {
        assertTrue(shouldRetryHomeWebFeed(ContentRequestException(-352, "")))
        assertTrue(shouldRetryHomeWebFeed(ContentRequestException(-403, "")))
        assertTrue(shouldRetryHomeWebFeed(IOException("connection reset")))
        assertTrue(shouldRetryHomeWebFeed(SocketTimeoutException("timeout")))
    }

    @Test
    fun rateLimitsLoginErrorsAndCancellationDoNotRetry() {
        assertFalse(shouldRetryHomeWebFeed(ContentRequestException(-412, "")))
        assertFalse(shouldRetryHomeWebFeed(ContentRequestException(-101, "")))
        assertFalse(shouldRetryHomeWebFeed(CancellationException("left the page")))
        assertFalse(shouldRetryHomeWebFeed(IllegalStateException("bad state")))
    }

    @Test
    fun onlyNetworkFailuresWaitBeforeRetrying() {
        assertEquals(500L, resolveHomeWebFeedRetryDelayMs(IOException("HTTPS query failed with code 1")))
        assertEquals(500L, resolveHomeWebFeedRetryDelayMs(UnknownHostException("api.bilibili.com")))
        assertEquals(0L, resolveHomeWebFeedRetryDelayMs(ContentRequestException(-352, "")))
    }
}
