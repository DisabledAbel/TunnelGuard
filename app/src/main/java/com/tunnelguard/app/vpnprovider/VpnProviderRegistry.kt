package com.tunnelguard.app.vpnprovider

/** Trusted, code-owned provider metadata. Known providers currently expose launcher integration only. */
object VpnProviderRegistry {
    private val knownProviders = mapOf(
        "ch.protonvpn.android" to "Proton VPN",
        "com.nordvpn.android" to "NordVPN",
        "com.surfshark.vpnclient.android" to "Surfshark",
        "com.expressvpn.vpn" to "ExpressVPN",
        "net.mullvad.mullvadvpn" to "Mullvad VPN",
        "com.windscribe.vpn" to "Windscribe"
    )

    fun resolve(packageName: String): VpnProviderAdapter =
        GenericVpnProviderAdapter(packageName, knownProviders[packageName])
}
