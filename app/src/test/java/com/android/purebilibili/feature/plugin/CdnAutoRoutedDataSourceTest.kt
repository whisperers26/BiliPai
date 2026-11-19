package com.android.purebilibili.feature.plugin

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
@OptIn(UnstableApi::class)
class CdnAutoRoutedDataSourceTest {
    private val issued = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/77/88/v.m4s?os=cosovbv&sign=a"
    private val ali = "upos-sz-mirrorali.bilivideo.com"
    private val hw = "upos-sz-mirrorhw.bilivideo.com"
    private val bytes = ByteArray(300_000) { (it % 251).toByte() }

    @Before fun setUp() {
        CdnAutoRoutingRuntime.resetForTest()
        CdnAutoRoutingRuntime.configure(true)
        CdnAutoRoutingRuntime.register(listOf(issued), emptyList())
    }

    @After fun tearDown() {
        CdnAutoRoutingRuntime.configure(false)
        CdnAutoRoutingRuntime.resetForTest()
    }

    private fun activate(host: String) {
        val router = CdnAutoRoutingRuntime.router
        router.cancelRace()
        router.beginRace()
        router.finishRace(router.activeHost ?: "upos-sz-mirrorcosov.bilivideo.com", 0,
            listOf(CdnRaceResult(host, CDN_RACE_BYTES, 100, true)))
    }

    @Test
    fun `routes to the active mirror and reports the original uri`() {
        activate(ali)
        val upstream = FakeUpstream(bytes)
        val source = CdnPlaybackDataSource(upstream, isWifi = { false })

        source.open(DataSpec(Uri.parse(issued), 1_000, 50_000))

        assertEquals(ali, Uri.parse(upstream.opened.single()).host)
        assertEquals(issued, source.uri.toString())
        assertContentEquals(bytes.copyOfRange(1_000, 51_000), readAll(source))
        source.close()
    }

    @Test
    fun `a refused mirror falls back to the issued url and is not used again for the track`() {
        activate(ali)
        val upstream = FakeUpstream(bytes, statusFor = { if (Uri.parse(it).host == ali) 403 else 206 })
        val source = CdnPlaybackDataSource(upstream, isWifi = { false })

        source.open(DataSpec(Uri.parse(issued), 0, 10_000))
        source.close()

        assertEquals(listOf(ali, "upos-sz-mirrorcosov.bilivideo.com"), upstream.opened.map { Uri.parse(it).host })
        assertEquals(issued, CdnAutoRoutingRuntime.resolve(issued)?.url)
    }

    @Test
    fun `a mirror with a different file length is rejected`() {
        val upstream = FakeUpstream(bytes, totalFor = { if (Uri.parse(it).host == ali) bytes.size + 1L else bytes.size.toLong() })
        CdnPlaybackDataSource(upstream, isWifi = { false }).apply { open(DataSpec(Uri.parse(issued), 0, 10_000)); close() }
        activate(ali)
        val source = CdnPlaybackDataSource(upstream, isWifi = { false })

        source.open(DataSpec(Uri.parse(issued), 10_000, 10_000))

        assertEquals("upos-sz-mirrorcosov.bilivideo.com", Uri.parse(upstream.opened.last()).host)
        assertContentEquals(bytes.copyOfRange(10_000, 20_000), readAll(source))
        source.close()
    }

    @Test
    fun `a switch mid-stream resumes at the delivered byte on the new host`() {
        activate(ali)
        val upstream = FakeUpstream(bytes)
        val source = CdnPlaybackDataSource(upstream, isWifi = { false })
        source.open(DataSpec(Uri.parse(issued), 500, C.LENGTH_UNSET.toLong()))
        val head = ByteArray(4_000)
        var got = 0
        while (got < head.size) got += source.read(head, got, head.size - got)

        activate(hw)
        source.preemptIfOn(ali)
        val rest = readAll(source)

        assertEquals(listOf(ali, hw), upstream.opened.map { Uri.parse(it).host })
        assertEquals(4_500L, upstream.lastPosition)
        assertContentEquals(bytes.copyOfRange(500, bytes.size), head + rest)
        source.close()
    }

    @Test
    fun `unregistered urls are opened unchanged`() {
        activate(ali)
        val other = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/99/v.m4s?sign=z"
        val upstream = FakeUpstream(bytes)
        val source = CdnPlaybackDataSource(upstream, isWifi = { false })

        source.open(DataSpec(Uri.parse(other), 0, 1_000))

        assertEquals(other, upstream.opened.single())
        source.close()
    }

    private fun readAll(source: DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private class FakeUpstream(
        private val data: ByteArray,
        private val statusFor: (String) -> Int = { 206 },
        private val totalFor: (String) -> Long = { data.size.toLong() },
    ) : DataSource {
        val opened = mutableListOf<String>()
        var lastPosition = -1L
        private var uri: Uri? = null
        private var position = 0
        private var end = 0
        private var headers: Map<String, List<String>> = emptyMap()

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            val url = dataSpec.uri.toString()
            opened += url
            lastPosition = dataSpec.position
            val status = statusFor(url)
            if (status >= 400) {
                throw HttpDataSource.InvalidResponseCodeException(status, null, null, emptyMap(), dataSpec, ByteArray(0))
            }
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) data.size else (position + dataSpec.length).toInt()
            headers = mapOf("content-range" to listOf("bytes $position-${end - 1}/${totalFor(url)}"))
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
