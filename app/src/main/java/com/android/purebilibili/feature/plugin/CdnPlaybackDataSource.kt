package com.android.purebilibili.feature.plugin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.util.NetworkUtils
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Media3 calls this synchronous boundary on its loader thread. OkHttp owns cancellable I/O.
 * Finite ranges use bounded windows; unknown lengths, small reads and failures use the upstream.
 */
@UnstableApi
internal class CdnPlaybackDataSource(
    private val upstream: DataSource,
    private val background: Boolean = false,
    private val client: OkHttpClient? = null,
    private val isWifi: () -> Boolean = { NetworkModule.appContext?.let { NetworkUtils.isWifi(it) } == true },
    private val mediaChunkBytes: Long = CDN_MEDIA_CHUNK_BYTES
) : BaseDataSource(true) {
    private var spec: DataSpec? = null
    private var candidates: List<String> = emptyList()
    private var consumed = 0L
    private var window = ByteArray(0)
    private var windowOffset = 0
    private var delegateOpen = false
    private var started = false
    private var parallel = false
    private var monitored = false
    private var delegateHost = ""
    private var delegateBytes = 0L
    private var delegateStartedNs = 0L
    private var delegateFirstByteMs: Long? = null
    @Volatile private var closed = true
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    // Automatic routing state for the non-parallel path; null when the URL is not auto-routed.
    @Volatile private var autoRoute: CdnRoute? = null
    @Volatile private var preemptRequested = false
    @Volatile private var inUpstreamRead = false
    @Volatile private var lastReadProgressNs = 0L
    private var routeGeneration = 0
    private var routeTotal: Long? = null
    private var reopenCount = 0
    private var sampleBytes = 0L
    private var sampleNs = 0L
    private var samplePosition = 0L
    // Bounded-range state for unbounded media reads; chunkBase is null when not chunking.
    private var chunkBase: DataSpec? = null
    private var chunkTotal: Long? = null
    private var chunkPosition = 0L
    private var chunkRequested = 0L
    private var chunkReceived = 0L

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        spec = dataSpec
        consumed = 0
        window = ByteArray(0)
        windowOffset = 0
        if (CdnTransferRuntime.enabled || CdnAutoRoutingRuntime.enabled) {
            val manager = NetworkModule.appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val identity = manager?.activeNetwork?.toString().orEmpty()
            if (CdnTransferRuntime.enabled) CdnTransferRuntime.networkChanged(identity)
            if (CdnAutoRoutingRuntime.enabled) CdnAutoRoutingRuntime.networkChanged(identity)
        }
        candidates = CdnTransferRuntime.candidates(dataSpec.uri.toString())
        val observedMedia = CdnTransferRuntime.observeMediaUrl(dataSpec.uri.toString())
        monitored = candidates.isNotEmpty() || observedMedia
        val wifi = isWifi()
        parallel = candidates.isNotEmpty() && CdnTransferRuntime.parallelEnabled && wifi &&
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpBody == null &&
            dataSpec.length >= CDN_MIN_PARALLEL_BYTES &&
            dataSpec.position >= 0 && dataSpec.length - 1 <= Long.MAX_VALUE - dataSpec.position
        transferInitializing(dataSpec)
        reopenCount = 0
        val length = if (parallel) dataSpec.length else openRouted(dataSpec)
        started = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed || Thread.currentThread().isInterrupted) throw IOException("CDN read canceled")
        val current = spec ?: throw IOException("CDN source is not open")
        if (current.length != C.LENGTH_UNSET.toLong() && consumed >= current.length) return C.RESULT_END_OF_INPUT
        val count: Int
        if (!parallel) {
            if (shouldReroute()) rerouteAtConsumed(current)
            val readStartedNs = System.nanoTime()
            try {
                inUpstreamRead = true
                count = upstream.read(buffer, offset, length)
                if (count > 0) chunkReceived += count
            } catch (error: IOException) {
                inUpstreamRead = false
                if (!closed && autoRoute != null && reopenCount < MAX_REROUTES_PER_OPEN) {
                    // A preempted or failed routed read resumes at the delivered byte on the best route.
                    if (!preemptRequested) autoRoute?.let { CdnAutoRoutingRuntime.recordFailure(it, current.uri.toString(), null) }
                    rerouteAtConsumed(current)
                    return read(buffer, offset, length)
                }
                if (monitored) finishDelegate(false, closed)
                throw error
            } finally {
                inUpstreamRead = false
            }
            if (count == C.RESULT_END_OF_INPUT && openNextChunkIfAny()) return read(buffer, offset, length)
            if (count > 0 && monitored) recordDelegateBytes(count)
            if (count > 0) recordRoutedBytes(current, count, System.nanoTime() - readStartedNs)
        } else {
            if (windowOffset == window.size) {
                try {
                    if (!CdnTransferRuntime.parallelEnabled ||
                        !isWifi()) {
                        throw IOException("CDN parallel mode disabled")
                    }
                    val startupWindow = if (CdnTransferRuntime.state.value.bufferMs < 5_000) CDN_WINDOW_BYTES / 2 else CDN_WINDOW_BYTES
                    val size = minOf(startupWindow, current.length - consumed)
                    window = downloadWindow(current, current.position + consumed, size)
                    windowOffset = 0
                } catch (error: IOException) {
                    cancelCalls()
                    if (closed || Thread.currentThread().isInterrupted || background) throw error
                    CdnTransferRuntime.fallback()
                    parallel = false
                    // Resume at delivered position, never restart or expose a partial window.
                    openDelegate(current.subrange(consumed))
                    return read(buffer, offset, length)
                }
            }
            count = minOf(length, window.size - windowOffset)
            window.copyInto(buffer, offset, windowOffset, windowOffset + count)
            windowOffset += count
        }
        if (closed) throw IOException("CDN read canceled")
        if (count > 0) {
            consumed += count
            bytesTransferred(count)
            if (monitored) CdnTransferRuntime.delivered(count)
        } else if (count == C.RESULT_END_OF_INPUT && monitored) {
            finishDelegate(true)
        }
        return count
    }

    // A routed request reports the original URI so the cache never records the mirror as a redirect.
    override fun getUri(): Uri? = if (delegateOpen && autoRoute == null) upstream.uri else spec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = if (delegateOpen) upstream.responseHeaders else emptyMap()

    fun cancelPendingRequests() {
        closed = true
        cancelCalls()
        if (delegateOpen) upstream.close()
    }

    override fun close() {
        closed = true
        cancelCalls()
        try {
            if (delegateOpen) upstream.close()
        } finally {
            // CacheWriter can stop at its finite range without an EOF read.
            val current = spec
            val complete = current != null && current.length >= 0 && consumed >= current.length
            if (monitored) finishDelegate(complete, canceled = true)
            flushRoutedSample()
            if (autoRoute != null) CdnAutoRoutingRuntime.detach(this)
            autoRoute = null
            preemptRequested = false
            chunkBase = null
            delegateOpen = false
            window = ByteArray(0)
            spec = null
            if (started) { started = false; transferEnded() }
        }
    }

    private fun openDelegate(dataSpec: DataSpec): Long {
        delegateHost = hostFromCdnUrl(dataSpec.uri.toString())
        delegateBytes = 0
        delegateStartedNs = System.nanoTime()
        delegateFirstByteMs = null
        if (monitored) CdnTransferRuntime.start(delegateHost)
        // Set before open so failed opens can still release upstream resources in close().
        delegateOpen = true
        // Bilibili's media CDNs refuse open-ended requests (no Range, or `bytes=N-`) with
        // 403/567, so an unbounded media read goes out as consecutive bounded ranges.
        val chunked = dataSpec.length == C.LENGTH_UNSET.toLong() && dataSpec.position >= 0 &&
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && isDiagnosticMediaUrl(dataSpec.uri.toString())
        chunkBase = if (chunked) dataSpec else null
        chunkTotal = null
        com.android.purebilibili.core.util.Logger.d(
            "CdnPlaybackDataSource",
            "open host=$delegateHost position=${dataSpec.position} length=${dataSpec.length} chunked=$chunked"
        )
        if (!chunked) {
            return try { upstream.open(dataSpec) } catch (error: IOException) {
                if (monitored) finishDelegate(false, closed)
                throw error
            }
        }
        try {
            openChunk(dataSpec.position)
        } catch (error: IOException) {
            if (monitored) finishDelegate(false, closed)
            throw error
        }
        return chunkTotal?.let { (it - dataSpec.position).coerceAtLeast(0) } ?: C.LENGTH_UNSET.toLong()
    }

    /** Opens the bounded range starting at [position] of the current unbounded media read. */
    private fun openChunk(position: Long) {
        val base = chunkBase ?: return
        val remaining = chunkTotal?.let { it - position }
        val length = remaining?.let { minOf(it, mediaChunkBytes) } ?: mediaChunkBytes
        upstream.open(base.buildUpon().setPosition(position).setLength(length).build())
        delegateOpen = true
        if (chunkTotal == null) {
            chunkTotal = upstream.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()?.let(::parseCdnContentRange)?.total
        }
        chunkPosition = position
        chunkRequested = length
        chunkReceived = 0
    }

    /** Opens the next range when the current one ended and the file has more bytes. */
    private fun openNextChunkIfAny(): Boolean {
        if (chunkBase == null || closed) return false
        val next = chunkPosition + chunkReceived
        val more = chunkTotal?.let { next < it } ?: (chunkReceived >= chunkRequested)
        if (!more) return false
        runCatching { upstream.close() }
        delegateOpen = false
        openChunk(next)
        return true
    }

    private fun recordDelegateBytes(count: Int) {
        delegateBytes += count
        val elapsedMs = ((System.nanoTime() - delegateStartedNs) / 1_000_000).coerceAtLeast(1)
        if (delegateFirstByteMs == null) delegateFirstByteMs = elapsedMs
        CdnTransferRuntime.progress(delegateHost, count, delegateBytes * 1_000 / elapsedMs, delegateFirstByteMs)
    }

    private fun finishDelegate(success: Boolean, canceled: Boolean = false) {
        if (delegateHost.isNotEmpty()) {
            CdnTransferRuntime.finish(delegateHost, success, canceled = canceled)
            delegateHost = ""
        }
    }

    /**
     * Opens [dataSpec] on the auto-routed host when one is active, falling back to the issued URL
     * when the mirror fails or answers with a different range or file length.
     */
    private fun openRouted(dataSpec: DataSpec): Long {
        autoRoute = null
        routeTotal = null
        val original = dataSpec.uri.toString()
        val route = if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET) CdnAutoRoutingRuntime.resolve(original) else null
        if (route == null) return openDelegate(dataSpec)
        CdnAutoRoutingRuntime.attach(this)
        routeGeneration = CdnAutoRoutingRuntime.generation
        if (route.url != original) {
            try {
                val length = openDelegate(dataSpec.withUri(Uri.parse(route.url)))
                if (acceptRoutedResponse(dataSpec, original)) {
                    autoRoute = route
                    return length
                }
                // A mirror with a different range or length must never feed this cache entry.
                CdnAutoRoutingRuntime.recordFailure(route, original, 403)
            } catch (error: IOException) {
                if (closed) throw error
                CdnAutoRoutingRuntime.recordFailure(
                    route, original, (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                )
            }
            closeUpstreamQuietly()
        }
        val length = openDelegate(dataSpec)
        acceptRoutedResponse(dataSpec, original)
        autoRoute = CdnRoute(hostFromCdnUrl(original), original, issued = true)
        return length
    }

    private fun acceptRoutedResponse(dataSpec: DataSpec, original: String): Boolean {
        val range = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull()?.let(::parseCdnContentRange)
        if (dataSpec.position > 0 && (range == null || range.start != dataSpec.position)) return false
        if (range != null && !CdnAutoRoutingRuntime.checkTotal(original, range.total)) return false
        routeTotal = range?.total
        return true
    }

    private fun shouldReroute(): Boolean {
        val route = autoRoute ?: return false
        if (reopenCount >= MAX_REROUTES_PER_OPEN) return false
        if (preemptRequested) return true
        val generation = CdnAutoRoutingRuntime.generation
        if (generation == routeGeneration) return false
        routeGeneration = generation
        val original = spec?.uri?.toString() ?: return false
        return CdnAutoRoutingRuntime.resolve(original)?.host?.let { it != route.host } == true
    }

    /** Resumes the current request at the delivered byte on the best route, never restarting it. */
    private fun rerouteAtConsumed(current: DataSpec) {
        reopenCount++
        preemptRequested = false
        flushRoutedSample()
        closeUpstreamQuietly()
        openRouted(current.subrange(consumed))
    }

    private fun closeUpstreamQuietly() {
        if (monitored) finishDelegate(false, canceled = true)
        runCatching { upstream.close() }
        delegateOpen = false
    }

    private fun recordRoutedBytes(current: DataSpec, count: Int, elapsedNs: Long) {
        if (autoRoute == null) return
        lastReadProgressNs = System.nanoTime()
        sampleBytes += count
        sampleNs += elapsedNs
        samplePosition = current.position + consumed + count
        if (sampleBytes >= ROUTED_SAMPLE_BYTES) flushRoutedSample()
    }

    /** Throughput counts only time spent inside upstream reads, not time the loader was paused. */
    private fun flushRoutedSample() {
        val route = autoRoute
        if (route != null && sampleBytes > 0) {
            val original = spec?.uri?.toString()
            if (!background && original != null) {
                CdnAutoRoutingRuntime.noteRead(original, route.host, samplePosition, routeTotal)
            }
            CdnAutoRoutingRuntime.recordTransfer(route.host, sampleBytes, sampleNs / 1_000_000, foreground = !background)
        }
        sampleBytes = 0
        sampleNs = 0
    }

    /** Called from the race thread after a switch away from [host]. A stalled read is unblocked. */
    fun preemptIfOn(host: String) {
        if (closed || autoRoute?.host != host) return
        preemptRequested = true
        if (inUpstreamRead && System.nanoTime() - lastReadProgressNs > TimeUnit.MILLISECONDS.toNanos(PREEMPT_IDLE_MS)) {
            runCatching { upstream.close() }
        }
    }

    private class Attempt(val range: CdnByteRange, val url: String, prefix: ByteArray = ByteArray(0)) {
        val bytes = ByteArray(range.length.toInt()).also { prefix.copyInto(it) }
        @Volatile var received = prefix.size
        @Volatile var lastProgressNs = System.nanoTime()
        val result = CompletableFuture<ByteArray>()
        @Volatile var call: Call? = null
        var status: Int? = null
    }

    private fun downloadWindow(dataSpec: DataSpec, start: Long, length: Long): ByteArray {
        val urls = CdnTransferRuntime.rank(candidates)
        if (background && CdnTransferRuntime.state.value.bufferMs < CDN_PREFETCH_SAFE_BUFFER_MS) {
            throw IOException("CDN prefetch yields to playback")
        }
        val limit = if (background) 1 else CdnTransferRuntime.connectionLimit()
        val count = minOf(limit, CdnTransferRuntime.permits.availablePermits().coerceAtLeast(1))
        val pieces = splitCdnRange(start, length, count)
        val total = AtomicLong(-1)
        if (background && CdnTransferRuntime.permits.availablePermits() <= 1) throw IOException("CDN prefetch yields its slot")
        if (!CdnTransferRuntime.permits.tryAcquire(count)) throw IOException("CDN connection budget exhausted")
        val attempts = pieces.mapIndexed { index, range ->
            val selected = if (CdnTransferRuntime.state.value.preferredHost != null) urls.first() else urls[index % urls.size]
            launchAttempt(dataSpec, range, selected, total, reserved = true)
                ?: throw IOException("CDN connection budget exhausted")
        }
        val output = ByteArray(length.toInt())
        var outputOffset = 0
        for (primary in attempts) {
            var rescue: Attempt? = null
            var retry: Attempt? = null
            var winner: Attempt? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (winner == null) {
                if (closed || Thread.currentThread().isInterrupted) throw IOException("CDN read canceled")
                val active = listOfNotNull(primary, rescue, retry)
                winner = active.firstOrNull { it.result.isDone && !it.result.isCompletedExceptionally }
                if (winner != null) break
                if (System.nanoTime() >= deadline) throw IOException("CDN window timed out")
                if (active.all { it.result.isCompletedExceptionally }) {
                    if (retry != null || primary.status == 403 || primary.status == 412 || primary.status == 429) {
                        throw IOException("CDN range failed")
                    }
                    // Only validated contiguous bytes can be carried into a tail retry.
                    val best = active.maxBy { it.received }
                    val nextUrl = urls.firstOrNull { it != best.url } ?: best.url
                    retry = launchAttempt(dataSpec, primary.range, nextUrl, total, best.bytes.copyOf(best.received))
                    if (retry == null) throw IOException("CDN retry budget exhausted")
                    CdnTransferRuntime.resumed()
                } else if (!background && CdnTransferRuntime.state.value.bufferMs < 15_000 &&
                    rescue == null && retry == null && urls.size > 1 &&
                    System.nanoTime() - primary.lastProgressNs > TimeUnit.MILLISECONDS.toNanos(900)) {
                    val nextUrl = urls.firstOrNull { hostFromCdnUrl(it) != hostFromCdnUrl(primary.url) }
                    if (nextUrl != null) {
                        rescue = launchAttempt(
                            dataSpec, primary.range, nextUrl, total,
                            primary.bytes.copyOf(minOf(primary.received, primary.bytes.size - 1))
                        )
                        if (rescue != null) CdnTransferRuntime.rescue()
                    }
                }
                try {
                    CompletableFuture.anyOf(*active.filterNot { it.result.isDone }.map { it.result }.toTypedArray())
                        .get(100, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Wake periodically for cancellation and stalled-piece rescue.
                } catch (_: ExecutionException) {
                    // The loop either finds a valid winner or retries a failed tail.
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("CDN read interrupted", error)
                }
            }
            val completed = winner ?: throw IOException("CDN missing piece")
            listOfNotNull(primary, rescue, retry).filter { it !== completed }.forEach { it.call?.cancel() }
            completed.bytes.copyInto(output, outputOffset)
            outputOffset += completed.bytes.size
        }
        return output
    }

    private fun launchAttempt(
        dataSpec: DataSpec,
        range: CdnByteRange,
        url: String,
        total: AtomicLong,
        prefix: ByteArray = ByteArray(0),
        reserved: Boolean = false
    ): Attempt? {
        if (!reserved && (closed || (background && CdnTransferRuntime.permits.availablePermits() <= 1) ||
                !CdnTransferRuntime.permits.tryAcquire())) return null
        val attempt = Attempt(range, url, prefix)
        if (closed) {
            CdnTransferRuntime.permits.release()
            attempt.result.completeExceptionally(IOException("CDN read canceled"))
            return attempt
        }
        if (prefix.size == attempt.bytes.size) {
            CdnTransferRuntime.permits.release()
            attempt.result.complete(attempt.bytes)
            return attempt
        }
        val requested = CdnByteRange(range.start + prefix.size, range.endInclusive)
        val call = try {
            val request = Request.Builder().url(url)
                .header("Referer", "https://www.bilibili.com")
                .header("User-Agent", "Mozilla/5.0")
                .apply { dataSpec.httpRequestHeaders.forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=${requested.start}-${requested.endInclusive}")
                .header("Accept-Encoding", "identity")
                .build()
            (client ?: rangeClient).newCall(request)
        } catch (error: IllegalArgumentException) {
            CdnTransferRuntime.permits.release()
            attempt.result.completeExceptionally(IOException("CDN invalid request", error))
            return attempt
        }
        attempt.call = call
        calls += call
        val host = hostFromCdnUrl(url)
        val startedNs = System.nanoTime()
        CdnTransferRuntime.start(host)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                end(call, false, call.isCanceled())
                attempt.result.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                var success = false
                var failure: Exception? = null
                try {
                    response.use {
                        attempt.status = it.code
                        if (!isExactCdnRange(it.code, it.header("Content-Range"), requested)) throw IOException("CDN invalid range")
                        val actual = parseCdnContentRange(it.header("Content-Range")) ?: throw IOException("CDN missing range")
                        total.compareAndSet(-1, actual.total)
                        if (total.get() != actual.total) throw IOException("CDN mirrors disagree on length")
                        val body = it.body ?: throw IOException("CDN empty body")
                        body.byteStream().use { input ->
                            var firstByteMs: Long? = null
                            while (attempt.received < attempt.bytes.size) {
                                if (call.isCanceled() || closed) throw IOException("CDN read canceled")
                                val read = input.read(attempt.bytes, attempt.received, attempt.bytes.size - attempt.received)
                                if (read < 0) throw IOException("CDN truncated range")
                                if (read == 0) continue
                                attempt.received += read
                                attempt.lastProgressNs = System.nanoTime()
                                val elapsedMs = ((attempt.lastProgressNs - startedNs) / 1_000_000).coerceAtLeast(1)
                                if (firstByteMs == null) firstByteMs = elapsedMs
                                CdnTransferRuntime.progress(host, read, (attempt.received - prefix.size) * 1_000L / elapsedMs, firstByteMs)
                            }
                            if (input.read() != -1) throw IOException("CDN oversized range")
                        }
                    }
                    success = true
                } catch (error: Exception) {
                    // Invalid/oversized bodies must not be carried into a resumed request.
                    if (attempt.received == attempt.bytes.size) attempt.received = 0
                    failure = error
                } finally {
                    end(call, success, call.isCanceled())
                }
                // Release the shared slot before the loader schedules another window.
                if (success) attempt.result.complete(attempt.bytes)
                else attempt.result.completeExceptionally(failure ?: IOException("CDN range failed"))
            }

            private fun end(call: Call, success: Boolean, canceled: Boolean) {
                calls -= call
                CdnTransferRuntime.permits.release()
                CdnTransferRuntime.finish(host, success, attempt.status, canceled)
            }
        })
        if (closed) call.cancel()
        return attempt
    }

    private fun cancelCalls() { calls.forEach { it.cancel() } }

    companion object {
        private const val MAX_REROUTES_PER_OPEN = 2
        private const val ROUTED_SAMPLE_BYTES = 64L * 1024L
        private const val PREEMPT_IDLE_MS = 800L

        // A shared dispatcher and connection pool; no per-player executor or hidden scope.
        private val rangeClient: OkHttpClient by lazy {
            NetworkModule.playbackOkHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}
