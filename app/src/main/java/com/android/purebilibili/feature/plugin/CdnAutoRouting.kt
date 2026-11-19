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

private const val SHORTFALL_RATE_FACTOR = 1.2
private const val SHORTFALL_MIN_BYTES = 4L * 1024L * 1024L
private const val SHORTFALL_BUFFER_MS = 30_000L
private const val RACE_MIN_INTERVAL_MS = 12_000L
private const val RACE_MAX_PER_VIDEO = 6
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
