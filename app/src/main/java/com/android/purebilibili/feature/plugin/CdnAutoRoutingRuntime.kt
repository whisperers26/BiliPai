package com.android.purebilibili.feature.plugin

import android.content.Context
import android.net.ConnectivityManager
import com.android.purebilibili.core.coroutines.AppScope
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.util.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "CdnAutoRouting"
private const val PLAYBACK_SIGNAL_TTL_MS = 2_500L
private const val STUCK_BUFFERING_MS = 1_200L

/**
 * Process-wide glue between [CdnAutoRouter] and the media data sources. Signed URLs stay in
 * memory only. Playback state comes from the foreground player; stale samples count as unknown,
 * so mini, portrait and preload players never trigger a race on their own.
 */
internal object CdnAutoRoutingRuntime {
    @androidx.annotation.VisibleForTesting
    internal var router = CdnAutoRouter(clock = ::nowMs)
    private val totalsByPath = ConcurrentHashMap<String, Long>()
    private val sources = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<CdnPlaybackDataSource, Boolean>())
    )
    private val switchGeneration = AtomicInteger(0)

    private data class PlaybackSample(val bufferMs: Long, val bitrateBps: Long, val atMs: Long)
    private data class ReadPosition(val originalUrl: String, val host: String, val position: Long, val total: Long?)

    @Volatile private var playbackSample: PlaybackSample? = null
    @Volatile private var bufferingSinceMs: Long? = null
    @Volatile private var lastRead: ReadPosition? = null

    @Volatile
    var enabled: Boolean = false
        private set

    /** The generation of the latest host switch; open sources compare it to reroute mid-stream. */
    val generation: Int get() = switchGeneration.get()

    private val raceClient: OkHttpClient by lazy {
        NetworkModule.playbackOkHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CDN_RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(CDN_RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        router = CdnAutoRouter(clock = ::nowMs)
        totalsByPath.clear()
        sources.clear()
        lastRead = null
        playbackSample = null
        bufferingSinceMs = null
    }

    fun configure(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) router.reset()
    }

    fun register(videoUrls: List<String>, audioUrls: List<String>) {
        if (enabled) router.register(videoUrls, audioUrls)
    }

    /** The route to open for an issued [url], or null when the URL is not auto-routed. */
    fun resolve(url: String): CdnRoute? {
        if (!enabled || !router.isRegistered(url)) return null
        val routes = router.routesFor(url)
        val active = router.activeHost
        return routes.firstOrNull { it.host == active } ?: routes.firstOrNull { it.url == url }
            ?: CdnRoute(hostFromCdnUrl(url), url, issued = true)
    }

    fun networkChanged(identity: String) = router.networkChanged(identity)

    fun attach(source: CdnPlaybackDataSource) { sources += source }
    fun detach(source: CdnPlaybackDataSource) { sources -= source }

    /** Remembers the first total length seen for a track; false when a mirror disagrees. */
    fun checkTotal(originalUrl: String, total: Long): Boolean {
        val path = pathOf(originalUrl)
        if (path.isBlank() || total <= 0) return true
        return totalsByPath.putIfAbsent(path, total)?.let { it == total } ?: true
    }

    fun recordFailure(route: CdnRoute, originalUrl: String, status: Int?) {
        router.recordFailure(route.host, originalUrl, status, swapped = !route.issued)
        Logger.d(TAG, "route failed host=${route.host} status=$status swapped=${!route.issued}")
    }

    fun recordTransfer(host: String, bytes: Long, activeReadMs: Long, foreground: Boolean) {
        router.recordTransfer(host, bytes, activeReadMs)
        if (foreground) maybeRace(stuck = false)
    }

    fun noteRead(originalUrl: String, host: String, position: Long, total: Long?) {
        lastRead = ReadPosition(originalUrl, host, position, total)
    }

    /** Fed about once a second by the foreground player. */
    fun playback(bufferMs: Long, bitrateBps: Long, buffering: Boolean) {
        if (!enabled) return
        val now = nowMs()
        playbackSample = PlaybackSample(bufferMs.coerceAtLeast(0), bitrateBps.coerceAtLeast(0), now)
        if (!buffering) {
            bufferingSinceMs = null
            return
        }
        val since = bufferingSinceMs ?: now.also { bufferingSinceMs = it }
        if (now - since >= STUCK_BUFFERING_MS) maybeRace(stuck = true)
    }

    private fun maybeRace(stuck: Boolean) {
        if (!enabled) return
        val read = lastRead ?: return
        val sample = playbackSample?.takeIf { nowMs() - it.atMs <= PLAYBACK_SIGNAL_TTL_MS }
        val metered = isMetered()
        val signal = CdnPlaybackSignal(
            currentHost = read.host,
            bufferMs = sample?.bufferMs,
            bitrateBps = sample?.bitrateBps ?: 0,
            stuck = stuck,
            metered = metered
        )
        if (!router.shouldRace(signal)) return
        val challengers = router.pickChallengers(router.routesFor(read.originalUrl), read.host, metered)
        if (challengers.isEmpty() || !router.beginRace()) return
        AppScope.ioScope.launch { race(read, challengers, stuck) }
    }

    private suspend fun race(read: ReadPosition, challengers: List<CdnRoute>, stuck: Boolean) {
        try {
            val range = raceRange(read.position, read.total)
            val results = coroutineScope {
                challengers.map { route ->
                    async {
                        try {
                            val sample = withTimeout(CDN_RACE_TIMEOUT_MS) { readExactCdnRange(raceClient, route.url, range) }
                            CdnRaceResult(route.host, sample.bytes.size.toLong(), sample.elapsedMs, success = true)
                        } catch (error: Exception) {
                            recordFailure(route, read.originalUrl, parseHttpStatus(error.message))
                            CdnRaceResult(route.host, 0, 0, success = false)
                        }
                    }
                }.awaitAll()
            }
            val currentRate = if (stuck) 0L else router.estimateBps(read.host) ?: 0L
            val switched = router.finishRace(read.host, currentRate, results)
            Logger.d(TAG, "race from=${read.host} stuck=$stuck results=${results.map { "${it.host}:${it.elapsedMs}ms:${it.success}" }} switched=$switched")
            if (switched != null) {
                switchGeneration.incrementAndGet()
                synchronized(sources) { sources.toList() }.forEach { it.preemptIfOn(read.host) }
            }
        } catch (error: Throwable) {
            router.cancelRace()
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }

    private fun isMetered(): Boolean {
        val manager = NetworkModule.appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return manager.isActiveNetworkMetered
    }

    private fun pathOf(url: String): String = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("")
    private fun nowMs(): Long = System.nanoTime() / 1_000_000
}

/** The race window: [CDN_RACE_BYTES] from where playback is reading, kept inside the file. */
internal fun raceRange(position: Long, total: Long?): CdnByteRange {
    val start = when {
        total == null -> position.coerceAtLeast(0)
        total <= CDN_RACE_BYTES -> 0L
        else -> position.coerceIn(0, total - CDN_RACE_BYTES)
    }
    val end = if (total == null) start + CDN_RACE_BYTES - 1 else minOf(start + CDN_RACE_BYTES, total) - 1
    return CdnByteRange(start, end)
}

internal fun parseHttpStatus(message: String?): Int? =
    Regex("HTTP (\\d{3})").find(message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
