package com.android.purebilibili.feature.plugin

import java.net.URI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/*
 * Automatic CDN routing for VOD playback, kept free of Android and network so it can be tested.
 *
 * The numbers follow realzza/bilibili-accelerator (docs/vod-routing.md), measured from the US:
 * which host is fast depends on the video (an overseas edge is fast for files other overseas
 * viewers pulled and slow while it relays one they haven't), a short probe mostly measures
 * TCP slow start, and every UPOS mirror accepts another UPOS host's signed path while Akamai
 * accepts only its own token. So playback starts on Bilibili's assignment and only races
 * alternatives when the current host is in trouble.
 */

internal const val CDN_RACE_BYTES = 768L * 1024L
internal const val CDN_RACE_TIMEOUT_MS = 4_000L

private const val EWMA_FAST_HALF_LIFE_S = 2.0
private const val EWMA_SLOW_HALF_LIFE_S = 5.0
private const val MIN_SAMPLE_BYTES = 16_000L
private const val MIN_TOTAL_BYTES = 128_000L

// Race once throughput is under 1.5x the stream bitrate, so a host is replaced before it stalls.
private const val SHORTFALL_RATE_FACTOR = 1.5
private const val SHORTFALL_MIN_BYTES = 2L * 1024L * 1024L
private const val SHORTFALL_BUFFER_MS = 30_000L
private const val RACE_MIN_INTERVAL_MS = 8_000L
// Each switch costs a reconnect, so a host that won stays: 10, 20, 40 s before the next race.
// There is no per-video budget: a host that turns slow mid-video can always be left again.
private const val SWITCH_COOLDOWN_MS = 10_000L
// A race that found nothing faster waits longer before the next one: 8, 16, 32, then 60 s.
private const val NO_CHANGE_COOLDOWN_MAX_MS = 60_000L
// While playback is actually stalling, a race may follow the previous one this soon, whatever the cooldowns.
private const val STALL_RACE_INTERVAL_MS = 2_000L
private const val REST_MS = 60_000L
private const val FAILURE_WINDOW_MS = 30_000L
private const val FAILURE_MEMORY_MS = 60 * 60_000L
private const val RATE_LIMIT_BACKOFF_MS = 5 * 60_000L
private const val MAX_TRACKED_PATHS = 64

/** UPOS mirrors a host swap may reach; mainland ones hold every file, overseas ones are edges. */
internal val CDN_AUTO_POOL_HOSTS = listOf(
    "upos-sz-mirrorali.bilivideo.com",
    "upos-sz-mirrorhw.bilivideo.com",
    "upos-sz-mirrorcos.bilivideo.com",
    "upos-tf-all-hw.bilivideo.com",
    "upos-tf-all-tx.bilivideo.com",
    "upos-sz-mirrorcosov.bilivideo.com",
    "upos-sz-mirrorhwov.bilivideo.com",
    "upos-sz-mirroraliov.bilivideo.com",
)

internal enum class CdnHostKind { UPOS, AKAMAI, PCDN, OTHER }

private val uposHostPattern = Regex("^(?:upos-[a-z0-9]+-(?!302)[a-z0-9]+|upos-tf-all-[a-z0-9]+)\\.bilivideo\\.com$")
private val ipHostPattern = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

internal fun classifyCdnHost(host: String): CdnHostKind {
    val h = host.lowercase()
    return when {
        isAkamaiCdnHost(h) -> CdnHostKind.AKAMAI
        ipHostPattern.matches(h) || h.contains("mcdn") || h.contains("pcdn") || h.contains("szbdyd") -> CdnHostKind.PCDN
        uposHostPattern.matches(h) && !h.contains("302") -> CdnHostKind.UPOS
        else -> CdnHostKind.OTHER
    }
}

/** True when only the host of [url] may be replaced by another UPOS mirror. */
internal fun isSwappableUposUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    val query = uri.rawQuery.orEmpty().split('&').associate {
        val pair = it.split('=', limit = 2)
        pair[0] to pair.getOrElse(1) { "" }
    }
    uri.scheme.equals("https", true) &&
        classifyCdnHost(uri.host.orEmpty()) == CdnHostKind.UPOS &&
        uri.rawPath.orEmpty().startsWith("/upgcxcode/") &&
        query["os"] != "mcdn"
}.getOrDefault(false)

internal fun isOverseasCdnHost(host: String): Boolean {
    val h = host.lowercase()
    return isAkamaiCdnHost(h) || Regex("^upos-[a-z]+-mirror[a-z0-9]+ov\\.bilivideo\\.com$").matches(h)
}

internal data class CdnRoute(val host: String, val url: String, val issued: Boolean)

/**
 * Bilibili's issued URLs in order, then host swaps of the first swappable issued URL onto the pool.
 * Swaps are never derived from Akamai, PCDN or redirect hosts.
 */
internal fun buildCdnRouteCandidates(issuedUrls: List<String>): List<CdnRoute> {
    val issued = issuedUrls.filter { it.isNotBlank() }.distinct()
        .map { CdnRoute(hostFromCdnUrl(it), it, issued = true) }
        .filter { it.host.isNotBlank() }
        .distinctBy { it.host }
    val source = issued.firstOrNull { isSwappableUposUrl(it.url) } ?: return issued
    val known = issued.map { it.host }.toSet()
    val swaps = CDN_AUTO_POOL_HOSTS.filter { it !in known }.mapNotNull { host ->
        rewriteBilivideoHost(source.url, host)?.let { CdnRoute(host, it, issued = false) }
    }
    return issued + swaps
}

/** Shaka Player's bandwidth estimator: the lower of a fast and a slow EWMA, in bytes per second. */
internal class CdnThroughputEstimator {
    private val fast = Ewma(EWMA_FAST_HALF_LIFE_S)
    private val slow = Ewma(EWMA_SLOW_HALF_LIFE_S)
    var totalBytes: Long = 0
        private set

    fun sample(durationMs: Long, bytes: Long) {
        if (bytes < MIN_SAMPLE_BYTES || durationMs <= 0) return
        val bytesPerSecond = bytes * 1_000.0 / durationMs
        val weight = durationMs / 1_000.0
        fast.sample(weight, bytesPerSecond)
        slow.sample(weight, bytesPerSecond)
        totalBytes += bytes
    }

    fun estimateBps(): Long? {
        if (totalBytes < MIN_TOTAL_BYTES) return null
        return minOf(fast.estimate(), slow.estimate()).toLong()
    }

    private class Ewma(halfLifeS: Double) {
        private val alpha = exp(ln(0.5) / halfLifeS)
        private var value = 0.0
        private var totalWeight = 0.0
        fun sample(weight: Double, sample: Double) {
            val adjusted = alpha.pow(weight)
            value = sample * (1 - adjusted) + adjusted * value
            totalWeight += weight
        }
        fun estimate(): Double = value / (1 - alpha.pow(totalWeight))
    }
}

/** What the player reports about the current host. Rates in bytes/s, bitrate in bits/s. */
internal data class CdnPlaybackSignal(
    val currentHost: String,
    val bufferMs: Long?,
    val bitrateBps: Long,
    val stuck: Boolean = false,
    val metered: Boolean = false,
    /** Short stalls keep recurring in ordinary playback (see [CdnRebufferTracker]). */
    val rebuffering: Boolean = false,
)

/** One contender's race. [cancelled] means it was cut off after the race was decided: no failure, no rest. */
internal data class CdnRaceResult(
    val host: String,
    val bytes: Long,
    val elapsedMs: Long,
    val success: Boolean,
    val cancelled: Boolean = false,
)

internal class CdnAutoRouter(
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) {
    private class HostStats {
        val estimator = CdnThroughputEstimator()
        val failuresAtMs = ArrayDeque<Long>()
        var restUntilMs = 0L
    }

    private val stats = mutableMapOf<String, HostStats>()
    private val issuedByPath = linkedMapOf<String, List<String>>()
    private val bannedByPath = mutableMapOf<String, MutableSet<String>>()
    private val videoPaths = mutableSetOf<String>()
    private var networkIdentity: String? = null
    private var limitedUntilMs = 0L
    private var nextRaceAtMs = Long.MIN_VALUE / 2
    private var stallRaceAtMs = Long.MIN_VALUE / 2
    private var switchesThisVideo = 0
    private var noChangeStreak = 0
    private var raceInFlight = false
    private var sessionKey: String? = null

    /** The highest video bitrate seen this video, bits/s; it only rises, so ABR cannot hide a slow host. */
    var requiredVideoBps: Long = 0
        private set

    /** The host requests are routed to; null means Bilibili's own assignment. */
    var activeHost: String? = null
        private set

    /**
     * Registers issued URLs. [sessionKey] names the video (bvid and cid): a quality change or a URL
     * refresh of the same video keeps the active host. Without a key, a set of only unknown paths
     * counts as a new video. Returns true when this starts a new video.
     */
    @Synchronized
    fun register(
        issuedVideo: List<String>,
        issuedAudio: List<String>,
        sessionKey: String? = null,
        videoBandwidthBps: Long = 0,
    ): Boolean {
        val byPath = (issuedVideo + issuedAudio).filter { it.isNotBlank() }.groupBy(::pathOf)
        val newSession = if (sessionKey != null) sessionKey != this.sessionKey else byPath.keys.none { it in issuedByPath }
        // Kept even when null, so a keyed video played again after a keyless one starts afresh.
        this.sessionKey = sessionKey
        if (newSession) {
            // A new video starts on its own assignment: host speed depends on the file.
            activeHost = null
            switchesThisVideo = 0
            noChangeStreak = 0
            nextRaceAtMs = Long.MIN_VALUE / 2
            stallRaceAtMs = Long.MIN_VALUE / 2
            requiredVideoBps = 0
        }
        // A (re)selected quality sets the bar, so a manual downgrade is judged at its own bitrate;
        // the player's formats can only raise it from there.
        if (videoBandwidthBps > 0) requiredVideoBps = videoBandwidthBps
        issuedVideo.filter { it.isNotBlank() }.map(::pathOf).filterTo(videoPaths) { it.isNotBlank() }
        byPath.forEach { (path, urls) ->
            if (path.isNotBlank()) {
                issuedByPath.remove(path)
                issuedByPath[path] = urls.distinct()
            }
        }
        while (issuedByPath.size > MAX_TRACKED_PATHS) {
            val oldest = issuedByPath.keys.first()
            issuedByPath.remove(oldest)
            bannedByPath.remove(oldest)
            videoPaths.remove(oldest)
        }
        return newSession
    }

    /** True for a registered video track; audio and unknown URLs are false. */
    @Synchronized
    fun isVideo(url: String): Boolean = pathOf(url) in videoPaths

    @Synchronized
    fun isRegistered(url: String): Boolean = issuedByPath[pathOf(url)]?.contains(url) == true

    /** Candidate routes for the track [url] belongs to, without hosts banned for that path. */
    @Synchronized
    fun routesFor(url: String): List<CdnRoute> {
        val path = pathOf(url)
        val issued = issuedByPath[path] ?: return emptyList()
        val banned = bannedByPath[path].orEmpty()
        return buildCdnRouteCandidates(listOf(url) + issued).filter { it.host !in banned }
    }

    /** The URL to actually open for an issued [url]: the active host's route when it has one. */
    @Synchronized
    fun resolve(url: String): String {
        val host = activeHost ?: return url
        return routesFor(url).firstOrNull { it.host == host }?.url ?: url
    }

    @Synchronized
    fun recordTransfer(host: String, bytes: Long, activeReadMs: Long) {
        if (host.isBlank()) return
        stats.getOrPut(host, ::HostStats).estimator.sample(activeReadMs, bytes)
    }

    @Synchronized
    fun estimateBps(host: String): Long? = stats[host]?.estimator?.estimateBps()

    /** Raises the required rate to [bitrateBps] (a video format's or manifest bandwidth, bits/s). */
    @Synchronized
    fun noteVideoBitrate(bitrateBps: Long) {
        if (bitrateBps > requiredVideoBps) requiredVideoBps = bitrateBps
    }

    /** True when [host] failed within the last 30 s, so its earlier record no longer vouches for it. */
    @Synchronized
    fun recentlyFailed(host: String): Boolean {
        val now = clock()
        return stats[host]?.failuresAtMs?.any { now - it <= FAILURE_WINDOW_MS } == true
    }

    /**
     * A failed request on [host] for [url]. A 403 on a swapped host bans it for that path only;
     * a 403 on an issued URL means the signature expired and is not held against the host.
     * Returns true when [host] has now failed twice within 30 s: it is failing, not slow.
     */
    @Synchronized
    fun recordFailure(host: String, url: String, status: Int?, swapped: Boolean): Boolean {
        val now = clock()
        if (status == 412 || status == 429) {
            limitedUntilMs = now + RATE_LIMIT_BACKOFF_MS
            if (swapped && activeHost == host) activeHost = null
            return false
        }
        if (status == 403 || status == 404) {
            if (swapped) {
                bannedByPath.getOrPut(pathOf(url), ::mutableSetOf) += host
                if (activeHost == host) activeHost = null
            }
            return false
        }
        val entry = stats.getOrPut(host, ::HostStats)
        entry.failuresAtMs.addLast(now)
        while (entry.failuresAtMs.isNotEmpty() && now - entry.failuresAtMs.first() > FAILURE_MEMORY_MS) {
            entry.failuresAtMs.removeFirst()
        }
        val recent = entry.failuresAtMs.count { now - it <= FAILURE_WINDOW_MS }
        if (recent >= 2) {
            entry.restUntilMs = now + REST_MS
            if (activeHost == host) activeHost = null
        }
        return recent >= 2
    }

    /** Whether the current host is in enough trouble to spend bytes on a race. */
    @Synchronized
    fun shouldRace(signal: CdnPlaybackSignal): Boolean {
        val now = clock()
        if (raceInFlight || now < limitedUntilMs) return false
        val buffer = signal.bufferMs ?: return false
        // Playback is starving: the cooldowns that spare a working host reconnects do not apply.
        if (signal.stuck || signal.rebuffering) return now >= stallRaceAtMs
        if (now < nextRaceAtMs) return false
        if (signal.metered) return false
        val current = stats[signal.currentHost]?.estimator ?: return false
        val rate = current.estimateBps() ?: return false
        val required = maxOf(signal.bitrateBps, requiredVideoBps)
        return required > 0 && buffer < SHORTFALL_BUFFER_MS &&
            current.totalBytes >= SHORTFALL_MIN_BYTES &&
            rate * 8 < required * SHORTFALL_RATE_FACTOR
    }

    /**
     * Up to two challengers (one when metered) by history. The other issued host is always one of
     * them until it has history; unknown hosts score the lower median; ties go to mainland mirrors;
     * one challenger in five is drawn at random so history keeps learning.
     */
    @Synchronized
    fun pickChallengers(routes: List<CdnRoute>, currentHost: String, metered: Boolean): List<CdnRoute> {
        val now = clock()
        // The active host is excluded too: when the track still reads elsewhere, it could not reach it.
        val pool = routes.filter { it.host != currentHost && it.host != activeHost }.filter { route ->
            val entry = stats[route.host]
            entry == null || (entry.restUntilMs <= now && entry.failuresAtMs.count { now - it <= FAILURE_MEMORY_MS } < 2)
        }
        if (pool.isEmpty()) return emptyList()
        val known = pool.mapNotNull { estimateBps(it.host) }.sorted()
        val fallbackScore = if (known.isEmpty()) 0L else known[(known.size - 1) / 2]
        val ranked = pool.sortedWith(
            compareByDescending<CdnRoute> { estimateBps(it.host) ?: fallbackScore }
                .thenBy { if (isOverseasCdnHost(it.host)) 1 else 0 }
        )
        val count = if (metered) 1 else 2
        val chosen = mutableListOf<CdnRoute>()
        ranked.firstOrNull { it.issued && (stats[it.host]?.estimator?.totalBytes ?: 0L) < MIN_TOTAL_BYTES }
            ?.let(chosen::add)
        chosen += ranked.filter { it !in chosen }.take(count - chosen.size)
        val rest = ranked.filter { it !in chosen }
        if (count > 1 && chosen.size == count && rest.isNotEmpty() && random.nextInt(5) == 0) {
            chosen[chosen.lastIndex] = rest.random(random)
        }
        return chosen
    }

    @Synchronized
    fun beginRace(): Boolean {
        if (raceInFlight) return false
        raceInFlight = true
        nextRaceAtMs = clock() + RACE_MIN_INTERVAL_MS
        stallRaceAtMs = clock() + STALL_RACE_INTERVAL_MS
        return true
    }

    /**
     * Applies a finished race. The fastest finisher becomes active only if it moved the race bytes
     * in at most two-thirds of the time [currentRateBps] needs; otherwise nothing changes. Losers and
     * the host being left rest for a minute; contenders cut off after the decision do not. The
     * caller passes the current host's best honest rate (see [CdnAutoRoutingRuntime]), so a stuck
     * request on a host that has been keeping up does not move the video to a slow winner.
     * Returns the new active host, or null for no switch.
     */
    @Synchronized
    fun finishRace(currentHost: String, currentRateBps: Long, results: List<CdnRaceResult>): String? {
        raceInFlight = false
        val now = clock()
        stallRaceAtMs = now + STALL_RACE_INTERVAL_MS
        results.filter { it.success }.forEach { recordTransfer(it.host, it.bytes, it.elapsedMs) }
        val winner = results.filter { it.success }.minByOrNull(::raceMs)
        results.filter { it !== winner && !it.cancelled }
            .forEach { stats.getOrPut(it.host, ::HostStats).restUntilMs = now + REST_MS }
        val switched = winner?.takeIf { won ->
            // Never "switch" to where routing already points: that would announce a switch and change nothing.
            won.host != currentHost && won.host != activeHost &&
                (currentRateBps <= 0 || raceMs(won) * 3 <= CDN_RACE_BYTES * 1_000 / currentRateBps * 2)
        }
        if (switched == null) {
            noChangeStreak++
            val wait = RACE_MIN_INTERVAL_MS shl (noChangeStreak - 1).coerceAtMost(4)
            nextRaceAtMs = now + wait.coerceAtMost(NO_CHANGE_COOLDOWN_MAX_MS)
            return null
        }
        noChangeStreak = 0
        switchesThisVideo++
        nextRaceAtMs = now + (SWITCH_COOLDOWN_MS shl (switchesThisVideo - 1).coerceAtMost(4))
        stats.getOrPut(currentHost, ::HostStats).restUntilMs = now + REST_MS
        activeHost = switched.host
        return switched.host
    }

    /** What the race bytes would have taken this contender, from a partial or a full read. */
    private fun raceMs(result: CdnRaceResult): Long =
        result.elapsedMs * CDN_RACE_BYTES / result.bytes.coerceAtLeast(1)

    @Synchronized
    fun cancelRace() {
        raceInFlight = false
        stallRaceAtMs = clock() + STALL_RACE_INTERVAL_MS
    }

    @Synchronized
    fun networkChanged(identity: String) {
        if (networkIdentity == identity) return
        val first = networkIdentity == null
        networkIdentity = identity
        if (first) return
        activeHost = null
        stats.clear()
        bannedByPath.clear()
        limitedUntilMs = 0
    }

    /** Pins routing back to Bilibili's assignment, e.g. after a manual CDN choice. */
    @Synchronized
    fun reset() {
        activeHost = null
        raceInFlight = false
    }

    private fun pathOf(url: String): String = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("")
}
