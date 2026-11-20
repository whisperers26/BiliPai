package com.android.purebilibili.feature.plugin

import android.content.Context
import android.net.ConnectivityManager
import com.android.purebilibili.core.coroutines.AppScope
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "CdnAutoRouting"
private const val PLAYBACK_SIGNAL_TTL_MS = 2_500L
// After the first finisher decides a race, the others get this long to reach history.
private const val RACE_GRACE_MS = 1_000L
// A switch is announced only once video actually reads from the new host, within this long.
private const val SWITCH_CONFIRM_TTL_MS = 20_000L

/** Why a race was started. */
internal enum class CdnRaceTrigger { SHORTFALL, STUCK, STUCK_REQUEST, REBUFFER, ERRORS }

/**
 * Process-wide glue between [CdnAutoRouter] and the media data sources. Signed URLs stay in
 * memory only. Playback state comes from the foreground player; stale samples count as unknown,
 * so mini, portrait and preload players never trigger a race on their own.
 *
 * Every decision follows the video track: audio usually sits on another issued host and its
 * small reads would otherwise drive races, false shortfalls and the shown host.
 */
internal object CdnAutoRoutingRuntime {
    @androidx.annotation.VisibleForTesting
    internal var router = CdnAutoRouter(clock = ::nowMs)
    @androidx.annotation.VisibleForTesting
    internal var rebuffers = CdnRebufferTracker(clock = ::nowMs)
    private val totalsByPath = ConcurrentHashMap<String, Long>()
    private val sources = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<CdnPlaybackDataSource, Boolean>())
    )
    private val switchGeneration = AtomicInteger(0)

    private data class PlaybackSample(val bufferMs: Long, val videoBitrateBps: Long, val atMs: Long)
    private data class ReadPosition(val originalUrl: String, val host: String, val position: Long, val total: Long?)
    private data class PendingSwitch(val switch: Switch, val atMs: Long)

    @Volatile private var playbackSample: PlaybackSample? = null
    @Volatile private var lastVideoRead: ReadPosition? = null
    @Volatile private var stuckCheck: Job? = null
    @Volatile private var videoSource: WeakReference<CdnPlaybackDataSource>? = null
    @Volatile private var racedRequestId = 0L
    @Volatile private var pendingSwitch: PendingSwitch? = null

    @Volatile
    var enabled: Boolean = false
        private set

    /** A host switch made by a race: [originalUrl] is the issued URL that was being read. */
    data class Switch(val originalUrl: String, val fromHost: String, val toHost: String)

    /** Routing fell back to Bilibili's own assignment, away from [fromHost]. */
    data class Revert(val fromHost: String, val reason: RevertReason)

    enum class RevertReason { REFUSED, FAILING, RATE_LIMITED, NETWORK_CHANGED }

    private val _currentHost = MutableStateFlow<String?>(null)
    /** The host the foreground video track is reading from right now, or null before its first read. */
    val currentHost: StateFlow<String?> = _currentHost.asStateFlow()

    private val _switches = MutableSharedFlow<Switch>(extraBufferCapacity = 4)
    /** Switches, each emitted once video has actually read from the new host. */
    val switches: SharedFlow<Switch> = _switches.asSharedFlow()

    private val _reverts = MutableSharedFlow<Revert>(extraBufferCapacity = 4)
    val reverts: SharedFlow<Revert> = _reverts.asSharedFlow()

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
        rebuffers = CdnRebufferTracker(clock = ::nowMs)
        stuckCheck?.cancel()
        stuckCheck = null
        totalsByPath.clear()
        sources.clear()
        clearVideoState()
        playbackSample = null
    }

    fun configure(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            router.reset()
            clearVideoState()
        }
    }

    /**
     * Registers issued URLs: the selected pair plus every representation of an adaptive
     * manifest. [videoBandwidthBps] is the selected video's manifest bandwidth, a floor for the
     * required rate when the player's format carries none.
     */
    fun register(videoUrls: List<String>, audioUrls: List<String>, videoBandwidthBps: Long = 0) {
        if (!enabled) return
        val sessionKey = videoUrls.firstNotNullOfOrNull(::cdnSessionKeyOf)
        if (router.register(videoUrls, audioUrls, sessionKey, videoBandwidthBps)) {
            clearVideoState()
            rebuffers.newSource()
        } else {
            rebuffers.jumped()
        }
    }

    private fun clearVideoState() {
        lastVideoRead = null
        videoSource = null
        pendingSwitch = null
        racedRequestId = 0
        _currentHost.value = null
    }

    /** The route to open for an issued [url], or null when the URL is not auto-routed. */
    fun resolve(url: String): CdnRoute? {
        if (!enabled || !router.isRegistered(url)) return null
        val routes = router.routesFor(url)
        val active = router.activeHost
        return routes.firstOrNull { it.host == active } ?: routes.firstOrNull { it.url == url }
            ?: CdnRoute(hostFromCdnUrl(url), url, issued = true)
    }

    fun isVideo(url: String): Boolean = router.isVideo(url)

    fun networkChanged(identity: String) = watchRevert(RevertReason.NETWORK_CHANGED) { router.networkChanged(identity) }

    fun attach(source: CdnPlaybackDataSource) { sources += source }
    fun detach(source: CdnPlaybackDataSource) { sources -= source }

    /** Remembers the first total length seen for a track; false when a mirror disagrees. */
    fun checkTotal(originalUrl: String, total: Long): Boolean {
        val path = pathOf(originalUrl)
        if (path.isBlank() || total <= 0) return true
        return totalsByPath.putIfAbsent(path, total)?.let { it == total } ?: true
    }

    fun recordFailure(route: CdnRoute, originalUrl: String, status: Int?) {
        val reason = when (status) {
            412, 429 -> RevertReason.RATE_LIMITED
            403, 404 -> RevertReason.REFUSED
            else -> RevertReason.FAILING
        }
        val failing = watchRevert(reason) { router.recordFailure(route.host, originalUrl, status, swapped = !route.issued) }
        Logger.d(TAG, "route failed host=${route.host} status=$status swapped=${!route.issued} failing=$failing")
        // A host failing twice in 30 s under the video is raced away from, issued hosts included.
        if (failing && router.isVideo(originalUrl) && lastVideoRead?.host == route.host) maybeRace(CdnRaceTrigger.ERRORS)
    }

    /** Runs [change] and reports a fall-back when it cleared the active host. */
    private inline fun <T> watchRevert(reason: RevertReason, change: () -> T): T {
        val before = router.activeHost
        val result = change()
        if (before != null && router.activeHost == null) {
            pendingSwitch = null
            Logger.i(TAG, "routing reverted from=$before reason=$reason")
            _reverts.tryEmit(Revert(before, reason))
        }
        return result
    }

    /** Throughput of a foreground or prefetch read; only video counts toward a host's goodput. */
    fun recordTransfer(originalUrl: String, host: String, bytes: Long, activeReadMs: Long, foreground: Boolean) {
        if (!router.isVideo(originalUrl)) return
        router.recordTransfer(host, bytes, activeReadMs)
        if (foreground) maybeRace(CdnRaceTrigger.SHORTFALL)
    }

    fun noteRead(originalUrl: String, host: String, position: Long, total: Long?) {
        if (!router.isVideo(originalUrl)) return
        lastVideoRead = ReadPosition(originalUrl, host, position, total)
        _currentHost.value = host
        val pending = pendingSwitch ?: return
        if (nowMs() - pending.atMs > SWITCH_CONFIRM_TTL_MS || router.activeHost != pending.switch.toHost) {
            pendingSwitch = null
        } else if (host == pending.switch.toHost) {
            pendingSwitch = null
            _switches.tryEmit(pending.switch)
        }
    }

    /** A foreground video request began; the latest one is watched for being stuck. */
    fun videoRequestStarted(source: CdnPlaybackDataSource) {
        videoSource = WeakReference(source)
    }

    /** How long the player should wait before its next [playback] call. */
    fun signalIntervalMs(): Long {
        val buffer = playbackSample?.bufferMs ?: return CDN_SIGNAL_INTERVAL_MS
        return if (buffer < CDN_SIGNAL_FAST_BELOW_BUFFER_MS) CDN_SIGNAL_FAST_INTERVAL_MS else CDN_SIGNAL_INTERVAL_MS
    }

    /**
     * Fed by the foreground player on every playback state change and on a 0.25–1 s tick.
     * [buffering] means it is buffering while it wants to play; [videoBitrateBps] is the video
     * format's bitrate, without audio.
     */
    fun playback(bufferMs: Long, videoBitrateBps: Long, buffering: Boolean, ready: Boolean) {
        if (!enabled) return
        val now = nowMs()
        playbackSample = PlaybackSample(bufferMs.coerceAtLeast(0), videoBitrateBps.coerceAtLeast(0), now)
        router.noteVideoBitrate(videoBitrateBps)
        checkInFlight(now, bufferMs.coerceAtLeast(0))
        val episode = rebuffers.episode
        if (rebuffers.observe(buffering, ready)) maybeRace(CdnRaceTrigger.REBUFFER)
        if (!buffering) {
            stuckCheck?.cancel()
            stuckCheck = null
            return
        }
        if (rebuffers.stuck()) maybeRace(CdnRaceTrigger.STUCK)
        if (rebuffers.episode != episode) scheduleStuckCheck(rebuffers.episode)
    }

    private fun checkInFlight(now: Long, bufferMs: Long) {
        val request = videoSource?.get()?.inFlight(now) ?: return
        if (request.id == racedRequestId) return
        if (!isStuckCdnRequest(request, now, bufferMs, router.requiredVideoBps)) return
        // Each request is raced at most once; a later request on the same host is judged afresh.
        if (maybeRace(CdnRaceTrigger.STUCK_REQUEST, request)) racedRequestId = request.id
    }

    /** A seek, skip or other position jump in the foreground player: its rebuffer is expected. */
    fun positionJumped() {
        if (!enabled) return
        rebuffers.jumped()
    }

    /** Checks a buffering episode exactly when it turns stuck, rather than at the next tick. */
    private fun scheduleStuckCheck(episode: Int) {
        stuckCheck?.cancel()
        stuckCheck = AppScope.ioScope.launch {
            delay(rebuffers.stuckThresholdMs())
            while (rebuffers.episode == episode && !rebuffers.stuck()) delay(100)
            if (rebuffers.episode == episode) maybeRace(CdnRaceTrigger.STUCK)
        }
    }

    /** Starts a race when [trigger] holds; returns true when one started. */
    private fun maybeRace(trigger: CdnRaceTrigger, request: CdnInFlightRequest? = null): Boolean {
        if (!enabled) return false
        val read = (request?.let { ReadPosition(it.originalUrl, it.host, it.position + it.bytes, it.total) }
            ?: lastVideoRead ?: return false)
            // During a blocked open the length is unknown; the remembered total keeps the race in the file.
            .let { if (it.total != null) it else it.copy(total = totalsByPath[pathOf(it.originalUrl)]) }
        val sample = playbackSample?.takeIf { nowMs() - it.atMs <= PLAYBACK_SIGNAL_TTL_MS }
        val metered = isMetered()
        val signal = CdnPlaybackSignal(
            currentHost = read.host,
            bufferMs = sample?.bufferMs,
            bitrateBps = sample?.videoBitrateBps ?: 0,
            stuck = trigger == CdnRaceTrigger.STUCK || trigger == CdnRaceTrigger.STUCK_REQUEST || trigger == CdnRaceTrigger.ERRORS,
            metered = metered,
            rebuffering = trigger == CdnRaceTrigger.REBUFFER
        )
        if (!router.shouldRace(signal)) return false
        val challengers = router.pickChallengers(router.routesFor(read.originalUrl), read.host, metered)
        if (challengers.isEmpty() || !router.beginRace()) return false
        rebuffers.raced()
        val inFlightRate = request?.rateBps()
            ?: videoSource?.get()?.inFlight(nowMs())?.takeIf { it.host == read.host }?.rateBps()
            ?: 0L
        AppScope.ioScope.launch { race(read, challengers, trigger, inFlightRate) }
        return true
    }

    private suspend fun race(read: ReadPosition, challengers: List<CdnRoute>, trigger: CdnRaceTrigger, inFlightRateBps: Long) {
        try {
            val range = raceRange(read.position, read.total)
            val results = raceChallengers(challengers, read.originalUrl, range)
            val currentRate = cdnJudgedRateBps(
                trigger, router.estimateBps(read.host) ?: 0L, inFlightRateBps, router.recentlyFailed(read.host)
            )
            val switched = router.finishRace(read.host, currentRate, results)
            Logger.i(
                TAG,
                "race from=${read.host} trigger=$trigger current=${currentRate * 8 / 1000}kbps " +
                    "results=${results.map { "${it.host}:${it.elapsedMs}ms:${if (it.cancelled) "cut" else it.success}" }} " +
                    "switched=$switched"
            )
            if (switched != null) {
                rebuffers.jumped()
                switchGeneration.incrementAndGet()
                pendingSwitch = PendingSwitch(Switch(read.originalUrl, read.host, switched), nowMs())
                synchronized(sources) { sources.toList() }.forEach { it.preemptIfOn(read.host) }
            } else if ((trigger == CdnRaceTrigger.STUCK || trigger == CdnRaceTrigger.STUCK_REQUEST) && results.any { it.success }) {
                // Other hosts answer but this one is stuck despite its record: a hung connection.
                // Count it, so a second hang drops the record and moves the video, and reconnect.
                recordFailure(CdnRoute(read.host, read.originalUrl, issued = true), read.originalUrl, null)
                synchronized(sources) { sources.toList() }.forEach { it.preemptIfOn(read.host) }
            }
        } catch (error: Throwable) {
            router.cancelRace()
            if (error is CancellationException) throw error
        }
    }

    /**
     * Races [challengers] on [range]. The race is decided at the first successful finisher; the
     * rest get [RACE_GRACE_MS] more so their numbers reach history, then are cut off without
     * counting as failures.
     */
    private suspend fun raceChallengers(challengers: List<CdnRoute>, originalUrl: String, range: CdnByteRange): List<CdnRaceResult> {
        val finished = ConcurrentHashMap<String, CdnRaceResult>()
        coroutineScope {
            val decided = CompletableDeferred<Unit>()
            val jobs = challengers.map { route ->
                launch {
                    val result = try {
                        val sample = withTimeout(CDN_RACE_TIMEOUT_MS) { readExactCdnRange(raceClient, route.url, range) }
                        CdnRaceResult(route.host, sample.bytes.size.toLong(), sample.elapsedMs, success = true)
                    } catch (error: CancellationException) {
                        if (decided.isCompleted) throw error
                        CdnRaceResult(route.host, 0, 0, success = false) // its own timeout
                    } catch (error: Exception) {
                        CdnRaceResult(route.host, 0, 0, success = false)
                            .also { recordFailure(route, originalUrl, parseHttpStatus(error.message)) }
                    }
                    finished[route.host] = result
                    if (result.success || finished.size == challengers.size) decided.complete(Unit)
                }
            }
            withTimeoutOrNull(CDN_RACE_TIMEOUT_MS + 200) { decided.await() }
            decided.complete(Unit)
            withTimeoutOrNull(RACE_GRACE_MS) { jobs.joinAll() }
            jobs.forEach { it.cancel() }
        }
        return challengers.map { finished[it.host] ?: CdnRaceResult(it.host, 0, 0, success = false, cancelled = true) }
    }

    private fun isMetered(): Boolean {
        val manager = NetworkModule.appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return manager.isActiveNetworkMetered
    }

    private fun pathOf(url: String): String = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("")
    private fun nowMs(): Long = System.nanoTime() / 1_000_000
}

/**
 * The rate the current host is judged by. A slow request in flight while the buffer still holds
 * ([CdnRaceTrigger.STUCK_REQUEST]) follows bilibili-accelerator's stuck-race verdict: the better of
 * what the request gets and the host's [recordBps], so one bad connection on a host that has been
 * keeping up does not move the video. Once the player itself stalls, or the host failed in the
 * last 30 s, the record proves nothing: a host that delivered bursts earlier can starve playback
 * now, and judging it on those bursts lets no challenger win. Only the request in flight counts
 * then. A shortfall is judged on the record alone.
 */
internal fun cdnJudgedRateBps(trigger: CdnRaceTrigger, recordBps: Long, inFlightRateBps: Long, recentlyFailed: Boolean): Long =
    when {
        trigger == CdnRaceTrigger.SHORTFALL -> recordBps
        trigger != CdnRaceTrigger.STUCK_REQUEST || recentlyFailed -> inFlightRateBps
        else -> maxOf(recordBps, inFlightRateBps)
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
