package com.tunnelguard.app

data class PolicyTestResult(
    val packageName: String,
    val appLabel: String,
    val appInstalled: Boolean,
    val protectedApp: Boolean,
    val profileId: String?,
    val profileName: String?,
    val profileSource: String,
    val profileAppCount: Int,
    val emergencyLock: Boolean,
    val temporaryOverride: TemporaryOverride?,
    val overrideSuppressed: Boolean,
    val effectivePolicy: EffectiveVpnPolicy?,
    val providerName: String?,
    val providerInstalled: Boolean?,
    val providerLaunchable: Boolean?,
    val upstream: UpstreamVpnEvaluation,
    val decision: PolicyDecision,
    val explanation: String,
    val evaluatedAtMillis: Long,
    val notes: List<String> = emptyList()
) {
    fun exportText(): String = buildString {
        appendLine("TunnelGuard Policy Test")
        appendLine("App: $appLabel")
        appendLine("Package: $packageName")
        appendLine("Installed: ${yesNo(appInstalled)}")
        appendLine("Profile: ${profileName ?: "Unavailable"} ($profileSource)")
        appendLine("Protected: ${yesNo(protectedApp)}")
        appendLine("VPN Required: ${effectivePolicy?.requireVpn?.let(::yesNo) ?: "Unavailable"}")
        appendLine("VPN Provider: ${providerName ?: "Not configured"}${effectivePolicy?.let { " — ${it.providerSource.displayName()}" }.orEmpty()}")
        appendLine("Required Country: ${effectivePolicy?.requiredCountryCode ?: "Unavailable"}${effectivePolicy?.let { " — ${it.countrySource.displayName()}" }.orEmpty()}")
        appendLine("Detected Country: ${upstream.detectedCountry() ?: "Unknown"}")
        appendLine("Auto-Connect: ${effectivePolicy?.autoConnect?.let(::enabledDisabled) ?: "Unavailable"}${effectivePolicy?.let { " — ${it.autoConnectSource.displayName()}" }.orEmpty()}")
        appendLine("Temporary Override: ${temporaryOverride?.type?.name ?: "No"}${if (overrideSuppressed) " (suppressed)" else ""}")
        appendLine("Emergency Lock: ${if (emergencyLock) "Active" else "Off"}")
        appendLine("Upstream Evaluation: ${upstream.displayName()}")
        appendLine("Result: ${decision.name}")
        append("Reason: $explanation")
    }

    companion object {
        private fun yesNo(value: Boolean) = if (value) "Yes" else "No"
        private fun enabledDisabled(value: Boolean) = if (value) "Enabled" else "Disabled"
    }
}

fun PolicySource.displayName() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
fun UpstreamVpnEvaluation.detectedCountry(): String? = when (this) {
    is UpstreamVpnEvaluation.Valid -> detectedCountry
    is UpstreamVpnEvaluation.CountryMismatch -> detected
    else -> null
}
fun UpstreamVpnEvaluation.displayName() = when (this) {
    is UpstreamVpnEvaluation.Valid -> "Valid"
    UpstreamVpnEvaluation.Missing -> "Missing"
    is UpstreamVpnEvaluation.CountryMismatch -> if (detected == null) "Country unknown" else "Country mismatch"
    UpstreamVpnEvaluation.ForegroundUnknown -> "Foreground unknown"
    UpstreamVpnEvaluation.Unknown -> "Unknown"
}
