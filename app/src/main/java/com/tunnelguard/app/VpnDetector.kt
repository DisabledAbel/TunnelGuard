package com.tunnelguard.app

import android.net.ConnectivityManager

/**
 * Interface for detecting VPN connection state and capabilities.
 */
interface VpnDetector {
    /**
 * Detects the current VPN state.
 *
 * @param connectivityManager The connectivity manager used to inspect network capabilities, or `null` to use the default behavior.
 * @return The detected VPN state.
 */
fun detectVpnState(connectivityManager: ConnectivityManager?): VpnDetectionResult
}

/**
 * Default implementation of VPN detection that uses dynamic country resolution.
 *
 * @param config The TunnelGuard configuration instance.
 * @param countryResolver The resolver used to determine the country code of detected VPN connections.
 */
class DefaultVpnDetector(
    private val config: TunnelGuardConfig,
    val countryResolver: VpnCountryResolver = config.defaultCountryResolver
) : VpnDetector {
    override fun detectVpnState(connectivityManager: ConnectivityManager?): VpnDetectionResult {
        return config.detectRealVpnCapabilities(
            connectivityManager,
            networkCountryResolver = { net -> countryResolver.resolveCountry(net) }
        )
    }
}
