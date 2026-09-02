package com.tunnelguard.app

/** Result of evaluating whether an external VPN satisfies the effective country policy. */
sealed class UpstreamVpnEvaluation {
    data class Valid(val detectedCountry: String? = null) : UpstreamVpnEvaluation()
    data object Missing : UpstreamVpnEvaluation()
    data class CountryMismatch(val required: String, val detected: String?) : UpstreamVpnEvaluation()
    data object ForegroundUnknown : UpstreamVpnEvaluation()
    data object Unknown : UpstreamVpnEvaluation()

    val isValid: Boolean
        get() = this is Valid

    companion object {
        /** Pure policy decision used by network detection, monitoring, routing, and tests. */
        fun fromObservation(
            externalVpnActive: Boolean,
            requiredCountry: String,
            detectedCountry: String?,
            detectionUncertain: Boolean = false
        ): UpstreamVpnEvaluation {
            if (!externalVpnActive) return if (detectionUncertain) Unknown else Missing
            val required = requiredCountry.uppercase().trim()
            if (required == "ANY") return Valid(detectedCountry)
            val detected = detectedCountry?.uppercase()?.trim()?.takeIf { it.isNotEmpty() }
            return if (detected == required) Valid(detected)
            else CountryMismatch(required, detected)
        }
    }
}

/** Country policy selected from a foreground-app observation. */
sealed class ForegroundVpnPolicy {
    abstract val requiredCountry: String

    data class ProtectedApp(val packageName: String, override val requiredCountry: String) : ForegroundVpnPolicy()
    data class UnprotectedApp(val packageName: String, override val requiredCountry: String) : ForegroundVpnPolicy()
    data class Unknown(
        override val requiredCountry: String,
        val hasProtectedCountryOverrides: Boolean
    ) : ForegroundVpnPolicy()
}
