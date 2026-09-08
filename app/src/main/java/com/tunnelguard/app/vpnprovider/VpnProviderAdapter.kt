package com.tunnelguard.app.vpnprovider

import android.content.Context
import android.content.Intent

/** Defines the level of integration TunnelGuard has with a VPN provider. */
enum class VpnIntegrationLevel { GENERIC, STANDARD, ENHANCED }

/**
 * Describes the capabilities of a VPN provider adapter.
 *
 * @param supportsGenericLaunch Whether the provider supports basic launcher activity launch.
 * @param supportsConnectRequest Whether the provider supports explicit connect requests.
 * @param supportsCountryRequest Whether the provider supports automated country selection.
 * @param supportsDisconnectRequest Whether the provider supports explicit disconnect requests.
 */
data class VpnProviderCapabilities(
    val supportsGenericLaunch: Boolean = true,
    val supportsConnectRequest: Boolean = false,
    val supportsCountryRequest: Boolean = false,
    val supportsDisconnectRequest: Boolean = false
)

/** Describes why TunnelGuard is requesting to launch a VPN provider. */
enum class VpnLaunchReason {
    PROTECTED_APP_OPENED, VPN_DISCONNECTED, COUNTRY_MISMATCH, MANUAL_RECOVERY
}

/**
 * Encapsulates a request to launch a VPN provider.
 *
 * @param targetAppPackage The package name of the protected app that triggered this request.
 * @param requiredCountry The ISO country code required for the VPN connection, or null if no country requirement.
 * @param reason Why the VPN launch is being requested.
 */
data class VpnLaunchRequest(
    val targetAppPackage: String,
    val requiredCountry: String?,
    val reason: VpnLaunchReason
)

/**
 * Result of attempting to build a VPN provider launch intent.
 *
 * @property Ready The provider can be launched with the provided intent.
 * @property Unavailable The provider cannot be launched due to installation or configuration issues.
 * @property UnsupportedAction The requested action is not supported by this provider.
 * @property Error An error occurred while preparing the launch request.
 */
sealed class VpnLaunchResult {
    data class Ready(val intent: Intent, val providerName: String) : VpnLaunchResult()
    data class Unavailable(val reason: String) : VpnLaunchResult()
    data class UnsupportedAction(val reason: String) : VpnLaunchResult()
    data class Error(val reason: String) : VpnLaunchResult()
}

/**
 * Abstraction for VPN provider integration.
 *
 * Adapters provide safe, validated launch intents for VPN applications without
 * requiring undocumented APIs or deep integrations.
 */
interface VpnProviderAdapter {
    /** The Android package name of the VPN provider application. */
    val packageName: String

    /** The level of integration TunnelGuard has with this provider. */
    val integrationLevel: VpnIntegrationLevel

    /** The capabilities supported by this provider adapter. */
    val capabilities: VpnProviderCapabilities

    /**
     * Returns the human-readable display name for this VPN provider.
     *
     * @param context Android context for accessing system resources.
     * @return The provider's display name.
     */
    fun getDisplayName(context: Context): String

    /**
 * Prepares a launch request for the VPN provider.
 *
 * The operation does not change VPN state or guarantee that a connection is established.
 *
 * @param context Context used to access provider availability information.
 * @param request The requested VPN action and its target app or country.
 * @return The prepared launch intent, or a result describing why it is unavailable,
 * unsupported, or could not be prepared.
 */
    fun buildLaunchRequest(context: Context, request: VpnLaunchRequest): VpnLaunchResult
}
