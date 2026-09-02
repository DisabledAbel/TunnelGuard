package com.tunnelguard.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamVpnEvaluationTest {
    @Test fun matchingCountryIsValid() = assertTrue(evaluate(true, "US", "US").isValid)

    @Test fun wrongCountryFailsClosed() = assertFalse(evaluate(true, "US", "CA").isValid)

    @Test fun unresolvedCountryFailsClosed() = assertFalse(evaluate(true, "US", null).isValid)

    @Test fun globalCountryStillAppliesWithoutOverride() =
        assertFalse(evaluate(true, "CA", "US").isValid)

    @Test fun globalAnyAcceptsDetectedVpn() = assertTrue(evaluate(true, "ANY", null).isValid)

    @Test fun disconnectedVpnFailsClosed() = assertFalse(evaluate(false, "ANY", null).isValid)

    @Test fun uncertainDetectionFailsClosed() =
        assertFalse(evaluate(false, "US", null, detectionUncertain = true).isValid)

    @Test fun transportVpnCannotOverrideCountryMismatch() {
        val result = evaluate(externalVpnActive = true, requiredCountry = "US", detectedCountry = "CA")
        assertTrue(result is UpstreamVpnEvaluation.CountryMismatch)
        assertFalse(result.isValid)
    }

    private fun evaluate(
        externalVpnActive: Boolean,
        requiredCountry: String,
        detectedCountry: String?,
        detectionUncertain: Boolean = false
    ) = UpstreamVpnEvaluation.fromObservation(
        externalVpnActive,
        requiredCountry,
        detectedCountry,
        detectionUncertain
    )
}
