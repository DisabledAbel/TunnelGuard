package com.tunnelguard.app

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves country code for an active VPN connection using dynamic GeoIP lookup over the specified network.
 */
class VpnCountryResolver(
    private val config: TunnelGuardConfig,
    private val fetcher: ((network: Network?, url: String) -> String?)? = null
) {
    data class CachedCountry(
        val countryCode: String,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CachedCountry>()

    companion object {
        private const val CACHE_TTL_MS = 300_000L // 5 minutes
        private val GEOIP_ENDPOINTS = listOf(
            "https://api.country.is",
            "https://ipapi.co/json",
            "https://ipinfo.io/json"
        )
    }

    /**
     * Resolves the country code for the given [network].
     *
     * Returns cached country code if still valid, otherwise queries GeoIP providers.
     */
    fun resolveCountry(network: Network?): String? {
        val netKey = network?.toString() ?: "default"
        val cached = cache[netKey]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.countryCode
        }

        if (isMainThread()) {
            CoroutineScope(Dispatchers.IO).launch {
                performLookup(network, netKey, now)
            }
            return cached?.countryCode
        }

        return performLookup(network, netKey, now)
    }

    private fun performLookup(network: Network?, netKey: String, now: Long): String? {
        for (endpoint in GEOIP_ENDPOINTS) {
            try {
                val responseText = if (fetcher != null) {
                    fetcher.invoke(network, endpoint)
                } else {
                    executeHttpRequest(network, endpoint)
                }

                if (!responseText.isNullOrBlank()) {
                    val countryCode = parseCountryCodeFromJson(responseText)
                    if (!countryCode.isNullOrBlank()) {
                        val uppercaseCode = countryCode.uppercase().trim()
                        cache[netKey] = CachedCountry(uppercaseCode, now)
                        config.setActiveVpnCountryCode(uppercaseCode)
                        config.addLogInfo("Country resolved via $endpoint: $uppercaseCode")
                        return uppercaseCode
                    }
                }
            } catch (e: Exception) {
                config.addLogWarning("Failed country lookup via $endpoint: ${e.message}")
            }
        }

        config.addLogWarning("All GeoIP providers failed to resolve country code for network: $netKey")
        return null
    }

    fun clearCacheForNetwork(network: Network?) {
        val netKey = network?.toString() ?: "default"
        cache.remove(netKey)
    }

    fun clearCache() {
        cache.clear()
    }

    private fun isMainThread(): Boolean {
        return try {
            val mainLooper = android.os.Looper.getMainLooper()
            val myLooper = android.os.Looper.myLooper()
            mainLooper != null && myLooper == mainLooper
        } catch (e: Throwable) {
            false
        }
    }

    private fun parseCountryCodeFromJson(jsonStr: String): String? {
        return try {
            val json = JSONObject(jsonStr)
            var code: String? = if (json.has("country") && !json.isNull("country")) {
                json.getString("country")
            } else null

            if (code.isNullOrBlank()) {
                code = if (json.has("country_code") && !json.isNull("country_code")) {
                    json.getString("country_code")
                } else null
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
