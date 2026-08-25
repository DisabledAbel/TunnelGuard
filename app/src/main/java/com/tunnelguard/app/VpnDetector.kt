package com.tunnelguard.app

import android.net.ConnectivityManager

interface VpnDetector {
    fun detectVpnState(connectivityManager: ConnectivityManager?): VpnDetectionResult
}

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
