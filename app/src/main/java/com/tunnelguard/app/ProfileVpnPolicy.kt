package com.tunnelguard.app

/** A profile's sparse VPN overrides. INHERIT values deliberately do not copy global settings. */
data class ProfileVpnPolicy(
    val requirement: RequirementMode = RequirementMode.INHERIT,
    val providerPackage: String? = null,
    val country: String? = null,
    val autoConnect: ToggleMode = ToggleMode.INHERIT,
    val countryValidation: ToggleMode = ToggleMode.INHERIT
) {
    enum class RequirementMode { INHERIT, REQUIRED, NOT_REQUIRED }
    enum class ToggleMode { INHERIT, ENABLED, DISABLED }

    companion object {
        fun normalizeCountry(value: String?): String? = value?.trim()?.uppercase()
            ?.takeIf { it == "ANY" || it.matches(Regex("^[A-Z]{2,3}$")) }
        fun normalizePackage(value: String?): String? = value?.trim()?.takeIf {
            it.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$"))
        }
    }
}

enum class PolicySource { EMERGENCY_LOCK, PER_APP, PROFILE, GLOBAL, DEFAULT }

data class EffectiveVpnPolicy(
    val requireVpn: Boolean,
    val providerPackage: String?,
    val requiredCountryCode: String,
    val autoConnect: Boolean,
    val requireCountryMatch: Boolean,
    val requirementSource: PolicySource,
    val providerSource: PolicySource,
    val countrySource: PolicySource,
    val autoConnectSource: PolicySource,
    val countryValidationSource: PolicySource
)

/**
 * The single precedence implementation: Emergency Lock > per-app country > profile override >
 * global setting > conservative defaults. Provider availability is evaluated by callers and never
 * causes selection of a different provider.
 */
object EffectiveVpnPolicyResolver {
    data class Globals(
        val requireVpn: Boolean = true,
        val providerPackage: String?,
        val country: String,
        val autoConnect: Boolean,
        val countryValidation: Boolean
    )

    fun resolve(policy: ProfileVpnPolicy?, globals: Globals, appCountry: String?, emergencyLock: Boolean): EffectiveVpnPolicy {
        val p = policy ?: ProfileVpnPolicy()
        val requirement = when {
            emergencyLock -> true
            appCountry != null -> true
            p.requirement == ProfileVpnPolicy.RequirementMode.REQUIRED -> true
            p.requirement == ProfileVpnPolicy.RequirementMode.NOT_REQUIRED -> false
            else -> globals.requireVpn
        }
        val country = ProfileVpnPolicy.normalizeCountry(appCountry)
            ?: ProfileVpnPolicy.normalizeCountry(p.country)
            ?: ProfileVpnPolicy.normalizeCountry(globals.country) ?: "ANY"
        val validation = if (appCountry != null) true else when (p.countryValidation) {
            ProfileVpnPolicy.ToggleMode.ENABLED -> true
            ProfileVpnPolicy.ToggleMode.DISABLED -> false
            ProfileVpnPolicy.ToggleMode.INHERIT -> globals.countryValidation
        }
        return EffectiveVpnPolicy(
            requireVpn = requirement,
            providerPackage = p.providerPackage ?: globals.providerPackage,
            requiredCountryCode = if (validation) country else "ANY",
            autoConnect = when (p.autoConnect) {
                ProfileVpnPolicy.ToggleMode.ENABLED -> true
                ProfileVpnPolicy.ToggleMode.DISABLED -> false
                ProfileVpnPolicy.ToggleMode.INHERIT -> globals.autoConnect
            },
            requireCountryMatch = validation && country != "ANY",
            requirementSource = when { emergencyLock -> PolicySource.EMERGENCY_LOCK; appCountry != null -> PolicySource.PER_APP; p.requirement != ProfileVpnPolicy.RequirementMode.INHERIT -> PolicySource.PROFILE; else -> PolicySource.GLOBAL },
            providerSource = if (p.providerPackage != null) PolicySource.PROFILE else PolicySource.GLOBAL,
            countrySource = when { appCountry != null -> PolicySource.PER_APP; p.country != null -> PolicySource.PROFILE; else -> PolicySource.GLOBAL },
            autoConnectSource = if (p.autoConnect != ProfileVpnPolicy.ToggleMode.INHERIT) PolicySource.PROFILE else PolicySource.GLOBAL,
            countryValidationSource = if (p.countryValidation != ProfileVpnPolicy.ToggleMode.INHERIT) PolicySource.PROFILE else PolicySource.GLOBAL
        )
    }
}
