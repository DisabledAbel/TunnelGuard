package com.tunnelguard.app

import android.net.ConnectivityManager

interface VpnDetector {
    /**
 * Detects the current VPN state.
 *
 * @param connectivityManager The connectivity manager used to inspect network capabilities, or `null` to use the default behavior.
 * @return The detected VPN state.
 */
fun detectVpnState(connectivityManager: ConnectivityManager?): VpnDetectionResult
}

class DefaultVpnDetector(
    private val config: TunnelGuardConfig,
    val countryResolver: VpnCountryResolver = config.defaultCountryResolver
) : VpnDetector {
    /** Evaluates VPN presence and country as one fail-closed policy decision. */
    fun evaluateUpstreamVpn(
        connectivityManager: ConnectivityManager?,
        requiredCountry: String
    ): UpstreamVpnEvaluation {
        return config.evaluateUpstreamVpn(
            connectivityManager,
            networkCountryResolver = { net -> countryResolver.resolveCountry(net) },
            requiredCountryCode = requiredCountry.takeUnless { it.equals("ANY", ignoreCase = true) }
        )
    }

    override fun detectVpnState(connectivityManager: ConnectivityManager?): VpnDetectionResult {
        return config.detectRealVpnCapabilities(
            connectivityManager,
            networkCountryResolver = { net -> countryResolver.resolveCountry(net) }
        )
    }
}
