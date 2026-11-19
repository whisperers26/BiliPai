package com.android.purebilibili.feature.plugin

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bilibili's media CDNs refuse open-ended requests (no Range, or `bytes=N-`) with 403/567,
 * so unbounded reads must go out as consecutive bounded ranges.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
@OptIn(UnstableApi::class)
class CdnBoundedRangeDataSourceTest {
    private val url = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/11/22/v.m4s?os=cosovbv&sign=a"
    private val bytes = ByteArray(2_500_000) { (it % 251).toByte() }

    @Test
    fun `an unbounded media read is sent as bounded ranges and returns every byte`() {
        val upstream = RangeOnlyUpstream(bytes)
        val source = CdnPlaybackDataSource(upstream, isWifi = { false }, mediaChunkBytes = 1_000_000)

        val length = source.open(DataSpec(Uri.parse(url), 0, C.LENGTH_UNSET.toLong()))

        assertEquals(bytes.size.toLong(), length)
        assertContentEquals(bytes, readAll(source))
        assertTrue(upstream.requested.all { it.second != C.LENGTH_UNSET.toLong() })
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L), upstream.requested.map { it.first })
        source.close()
    }

    @Test
    fun `an unbounded read from the middle stays bounded and ends at the file end`() {
        val upstream = RangeOnlyUpstream(bytes)
        val source = CdnPlaybackDataSource(upstream, isWifi = { false }, mediaChunkBytes = 1_000_000)

        val length = source.open(DataSpec(Uri.parse(url), 1_800_000, C.LENGTH_UNSET.toLong()))

        assertEquals(700_000L, length)
        assertContentEquals(bytes.copyOfRange(1_800_000, bytes.size), readAll(source))
        assertEquals(listOf(1_800_000L to 1_000_000L), upstream.requested)
        source.close()
    }

    @Test
    fun `bounded reads and non media urls are left unchanged`() {
        val upstream = RangeOnlyUpstream(bytes, rejectUnbounded = false)
        val source = CdnPlaybackDataSource(upstream, isWifi = { false }, mediaChunkBytes = 1_000_000)
        source.open(DataSpec(Uri.parse(url), 10, 50_000))
        source.close()
        source.open(DataSpec(Uri.parse("https://example.com/a.mp4"), 0, C.LENGTH_UNSET.toLong()))
        source.close()

        assertEquals(listOf(10L to 50_000L, 0L to C.LENGTH_UNSET.toLong()), upstream.requested)
    }

    private fun readAll(source: DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Behaves like the CDN: 403 for open-ended requests, 206 with Content-Range otherwise. */
    private class RangeOnlyUpstream(
        private val data: ByteArray,
        private val rejectUnbounded: Boolean = true,
    ) : DataSource {
        val requested = mutableListOf<Pair<Long, Long>>()
        private var uri: Uri? = null
        private var position = 0
        private var end = 0
        private var headers: Map<String, List<String>> = emptyMap()

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            requested += dataSpec.position to dataSpec.length
            val unbounded = dataSpec.length == C.LENGTH_UNSET.toLong()
            if (rejectUnbounded && unbounded) {
                throw HttpDataSource.InvalidResponseCodeException(403, null, null, emptyMap(), dataSpec, ByteArray(0))
            }
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            end = if (unbounded) data.size else minOf(data.size.toLong(), dataSpec.position + dataSpec.length).toInt()
            headers = mapOf("content-range" to listOf("bytes $position-${end - 1}/${data.size}"))
            return (end - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= end) return C.RESULT_END_OF_INPUT
            val count = minOf(length, end - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun getUri(): Uri? = uri
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun close() { uri = null }
    }
}
