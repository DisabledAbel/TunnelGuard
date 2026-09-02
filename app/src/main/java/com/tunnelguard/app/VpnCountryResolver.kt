package com.tunnelguard.app

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Resolves country code for an active VPN connection using dynamic GeoIP lookup over the specified network.
 */
class VpnCountryResolver(
    private val config: TunnelGuardConfig,
    private val fetcher: ((network: Network?, url: String) -> String?)? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class CachedCountry(
        val countryCode: String,
        val timestamp: Long,
        val owner: String
    )

    private val cache = ConcurrentHashMap<String, CachedCountry>()
    private val lookupSequence = AtomicLong()

    companion object {
        private const val CACHE_TTL_MS = 300_000L // 5 minutes
        private val GEOIP_ENDPOINTS = listOf(
            "https://api.country.is",
            "https://ipapi.co/json",
            "https://ipinfo.io/json",
            "https://api.iplocation.net/?cmd=get-ip-country"
        )
    }

    /**
     * Resolves and caches the country code associated with the given network.
     *
     * @param network The network whose country code should be resolved, or `null` for the default network.
     * @return A country code, a stale cached code while lookup is pending, or `null` if no code is available.
     */
    fun resolveCountry(network: Network?): String? {
        val netKey = network?.toString() ?: "default"
        val cached = cache[netKey]
        val now = clock()
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.countryCode
        }
        val owner = "$netKey:${lookupSequence.incrementAndGet()}"
        if (cached != null && cache.remove(netKey, cached)) {
            config.transferActiveVpnCountryOwnership(cached.owner, owner)
        }

        if (isMainThread()) {
            CoroutineScope(Dispatchers.IO).launch {
                performLookup(network, netKey, now, owner)
            }
            return null
        }

        return performLookup(network, netKey, now, owner)
    }

    /**
     * Resolves and caches the country code for a network using the configured GeoIP providers.
     *
     * @param network The network used for the lookup, when available.
     * @param netKey The cache key associated with the network.
     * @param now The timestamp to associate with the resolved country code.
     * @return The resolved uppercase country code, or `null` if all providers fail.
     */
    private fun performLookup(network: Network?, netKey: String, now: Long, owner: String): String? {
        for (endpoint in GEOIP_ENDPOINTS) {
            // Never let a VPN-specific lookup fall through to an unbound socket: split-tunnel
            // configurations could otherwise report the physical network's country.
            val candidateNetworks = listOf(network)
            for (candidateNetwork in candidateNetworks) {
                try {
                    val responseText = if (fetcher != null) {
                        fetcher.invoke(candidateNetwork, endpoint)
                    } else {
                        executeHttpRequest(candidateNetwork, endpoint)
                    }

                    if (!responseText.isNullOrBlank()) {
                        val countryCode = parseCountryCodeFromJson(responseText)
                        if (!countryCode.isNullOrBlank()) {
                            val uppercaseCode = countryCode.uppercase().trim()
                            cache[netKey] = CachedCountry(uppercaseCode, now, owner)
                            config.setActiveVpnCountryCode(uppercaseCode, owner)
                            val route = if (candidateNetwork == null) "default route" else "VPN network"
                            config.addLogInfo("Country resolved via $endpoint ($route): $uppercaseCode")
                            return uppercaseCode
                        }
                    }
                } catch (e: Exception) {
                    config.addLogWarning("Failed country lookup via $endpoint: ${e.message}")
                }
            }
        }

        config.clearActiveVpnCountryCodeIfOwnedBy(owner)
        config.addLogWarning("All GeoIP providers failed to resolve country code for network: $netKey")
        return null
    }

    /**
     * Removes the cached country data for the specified network.
     *
     * @param network The network whose cached country data should be removed, or `null` for the default network.
     */
    fun clearCacheForNetwork(network: Network?) {
        val netKey = network?.toString() ?: "default"
        cache.remove(netKey)
    }

    /**
     * Clears all cached country resolutions.
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Determines whether execution is currently on Android's main thread.
     *
     * @return `true` if execution is on the main thread, `false` otherwise.
     */
    private fun isMainThread(): Boolean {
        return try {
            val mainLooper = android.os.Looper.getMainLooper()
            val myLooper = android.os.Looper.myLooper()
            mainLooper != null && myLooper == mainLooper
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Extracts and normalizes a country code from a GeoIP JSON response.
     *
     * @param jsonStr The JSON response containing a country or country_code field.
     * @return The uppercase country code when it contains two or three characters, or null otherwise.
     */
    private fun parseCountryCodeFromJson(jsonStr: String): String? {
        return try {
            val json = JSONObject(jsonStr)
            val code = listOf("country", "country_code", "countryCode", "country_code2")
                .firstNotNullOfOrNull { key ->
                    if (json.has(key) && !json.isNull(key)) json.optString(key).takeIf(String::isNotBlank) else null
                }

            if (!code.isNullOrBlank() && code.trim().length in 2..3) {
                code.uppercase().trim()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetches the response body from a GeoIP endpoint over the specified network.
     *
     * @param network The network used for the request, or the default network when null.
     * @param urlStr The URL of the GeoIP endpoint.
     * @return The response body when the request succeeds with HTTP 200; null otherwise.
     */
    private fun executeHttpRequest(network: Network?, urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = if (network != null) {
                network.openConnection(url) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "TunnelGuard/1.0")

            val statusCode = conn.responseCode
            if (statusCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
