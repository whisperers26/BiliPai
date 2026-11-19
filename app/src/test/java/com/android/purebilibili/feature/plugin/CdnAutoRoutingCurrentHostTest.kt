package com.android.purebilibili.feature.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CdnAutoRoutingCurrentHostTest {
    private val url = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/11/22/v.m4s?os=cosovbv&sign=a"

    @After
    fun tearDown() {
        CdnAutoRoutingRuntime.configure(false)
        CdnAutoRoutingRuntime.resetForTest()
    }

    @Test
    fun `foreground reads publish the current host and disabling clears it`() {
        CdnAutoRoutingRuntime.resetForTest()
        CdnAutoRoutingRuntime.configure(true)
        assertNull(CdnAutoRoutingRuntime.currentHost.value)

        CdnAutoRoutingRuntime.noteRead(url, "upos-sz-mirrorhw.bilivideo.com", 0, null)
        assertEquals("upos-sz-mirrorhw.bilivideo.com", CdnAutoRoutingRuntime.currentHost.value)

        CdnAutoRoutingRuntime.configure(false)
        assertNull(CdnAutoRoutingRuntime.currentHost.value)
    }

    @Test
    fun `auto host label uses the known CDN name or falls back to the host`() {
        assertEquals("hw（华为云，融合CDN）", resolveAutoCdnHostLabel("upos-sz-mirrorhw.bilivideo.com"))
        assertEquals("cn-sh-ct-01.bilivideo.com", resolveAutoCdnHostLabel("cn-sh-ct-01.bilivideo.com"))
    }
}
