package com.android.purebilibili.feature.search

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTabLabelPolicyTest {

    @Test
    fun `label hides count until a type has loaded`() {
        assertEquals("视频", resolveSearchTypeTabLabel("视频", null))
        assertEquals("视频", resolveSearchTypeTabLabel("视频", -1))
    }

    @Test
    fun `label appends api count and caps at 99+`() {
        assertEquals("视频 12", resolveSearchTypeTabLabel("视频", 12))
        assertEquals("视频 99", resolveSearchTypeTabLabel("视频", 99))
        assertEquals("视频 99+", resolveSearchTypeTabLabel("视频", 100))
        assertEquals("视频 99+", resolveSearchTypeTabLabel("视频", 99520))
    }
}
