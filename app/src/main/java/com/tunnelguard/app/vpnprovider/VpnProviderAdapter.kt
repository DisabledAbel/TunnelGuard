package com.tunnelguard.app.vpnprovider

import android.content.Context
import android.content.Intent

enum class VpnIntegrationLevel { GENERIC, STANDARD, ENHANCED }

data class VpnProviderCapabilities(
    val supportsGenericLaunch: Boolean = true,
    val supportsConnectRequest: Boolean = false,
    val supportsCountryRequest: Boolean = false,
    val supportsDisconnectRequest: Boolean = false
)

enum class VpnLaunchReason {
    PROTECTED_APP_OPENED, VPN_DISCONNECTED, COUNTRY_MISMATCH, MANUAL_RECOVERY
}

data class VpnLaunchRequest(
    val targetAppPackage: String,
    val requiredCountry: String?,
    val reason: VpnLaunchReason
)

sealed class VpnLaunchResult {
    data class Ready(val intent: Intent, val providerName: String) : VpnLaunchResult()
    data class Unavailable(val reason: String) : VpnLaunchResult()
    data class UnsupportedAction(val reason: String) : VpnLaunchResult()
    data class Error(val reason: String) : VpnLaunchResult()
}

interface VpnProviderAdapter {
    val packageName: String
    val integrationLevel: VpnIntegrationLevel
    val capabilities: VpnProviderCapabilities
    fun getDisplayName(context: Context): String
    fun buildLaunchRequest(context: Context, request: VpnLaunchRequest): VpnLaunchResult
}
