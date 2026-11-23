package com.android.purebilibili.core.network

import android.os.Build
import com.android.purebilibili.core.network.policy.resolveHardcodedDnsFallback
import okhttp3.Dns
import okhttp3.DnsCache
import okhttp3.android.AndroidDns
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.time.Duration

/**
 * The default cache replays a failed lookup for 10 seconds, so a brief resolver failure after
 * waking or changing networks would also fail every retry made in that window.
 */
internal fun buildAppDnsCache(): DnsCache = DnsCache(failureTimeToLive = Duration.ZERO)

/**
 * Android 17 needs HTTPS DNS records as well as addresses to negotiate ECH.
 * Return AndroidDns itself: wrapping only lookup() drops its metadata-aware newCall().
 * It uses the system resolver and honors the platform's domain encryption policy;
 * effective ECH also depends on the DNS records and remote TLS server.
 * Debug configurations with explicit IP fallback keep their existing resolver.
 */
fun buildAppDns(allowHardcodedIpFallback: Boolean = false): Dns {
    if (Build.VERSION.SDK_INT >= 37 && !allowHardcodedIpFallback) {
        return AndroidDns(dnsCache = buildAppDnsCache())
    }
    return Dns { hostname ->
        try {
            InetAddress.getAllByName(hostname).toList().also { addresses ->
                CoreDataLog.d("ApiClient", "DNS resolved: $hostname -> $addresses")
            }
        } catch (error: UnknownHostException) {
            CoreDataLog.e("ApiClient", "DNS failed for $hostname: ${error.message}")
            val fallback = resolveHardcodedDnsFallback(hostname, allowHardcodedIpFallback)
                ?: throw error
            CoreDataLog.w(
                "ApiClient",
                "Using Hardcoded IP for ${fallback.description}: ${fallback.ipAddress}"
            )
            listOf(InetAddress.getByName(fallback.ipAddress))
        }
    }
}
