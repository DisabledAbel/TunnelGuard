package com.tunnelguard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyDecisionResolverTest {
    private val policy = EffectiveVpnPolicy(true, "com.example.vpn", "US", true, true,
        PolicySource.PROFILE, PolicySource.PROFILE, PolicySource.PER_APP, PolicySource.GLOBAL, PolicySource.GLOBAL)

    private fun decision(
        protected: Boolean = true,
        upstream: UpstreamVpnEvaluation = UpstreamVpnEvaluation.Valid("US"),
        emergency: Boolean = false,
        override: Boolean = false,
        provider: Boolean? = true,
        effective: EffectiveVpnPolicy? = policy
    ) = PolicyDecisionResolver.resolve(PolicyDecisionInput(protected, effective, upstream, emergency, override, provider))

    @Test fun unprotectedAppIsAllowed() = assertEquals(PolicyDecision.ALLOWED, decision(protected = false).decision)
    @Test fun validVpnProtectsApp() = assertEquals(PolicyDecision.PROTECTED, decision().decision)
    @Test fun missingVpnRequiresVpn() = assertEquals(PolicyDecision.VPN_REQUIRED, decision(upstream = UpstreamVpnEvaluation.Missing).decision)
    @Test fun matchingCountryProtects() = assertEquals(PolicyDecision.PROTECTED, decision(upstream = UpstreamVpnEvaluation.Valid("US")).decision)
    @Test fun mismatchedCountryIsExplicit() = assertEquals(PolicyDecision.COUNTRY_MISMATCH,
        decision(upstream = UpstreamVpnEvaluation.CountryMismatch("US", "CA")).decision)
    @Test fun unknownCountryFailsClosed() = assertEquals(PolicyDecision.COUNTRY_UNKNOWN,
        decision(upstream = UpstreamVpnEvaluation.CountryMismatch("US", null)).decision)
    @Test fun unavailableProviderIsExplicit() = assertEquals(PolicyDecision.VPN_PROVIDER_UNAVAILABLE, decision(provider = false).decision)
    @Test fun unknownUpstreamFailsClosed() = assertEquals(PolicyDecision.BLOCKED_FAIL_CLOSED,
        decision(upstream = UpstreamVpnEvaluation.Unknown).decision)
    @Test fun missingPolicyIsUnavailable() = assertEquals(PolicyDecision.POLICY_UNAVAILABLE, decision(effective = null).decision)
    @Test fun temporaryOverrideAllows() = assertEquals(PolicyDecision.TEMPORARILY_ALLOWED, decision(override = true).decision)
    @Test fun emergencySuppressesOverride() = assertEquals(PolicyDecision.EMERGENCY_LOCKED,
        decision(emergency = true, override = true).decision)

    @Test fun policySourcesAndExportAreReadable() {
        val result = PolicyTestResult("app.pkg", "Example", true, true, "stream", "Streaming",
            "Automatic: rule", 3, false, null, false, policy, "Example VPN", true, true,
            UpstreamVpnEvaluation.CountryMismatch("US", "CA"), PolicyDecision.COUNTRY_MISMATCH,
            "Country mismatch.", 123L)
        val export = result.exportText()
        assertTrue(export.contains("Required Country: US — Per app"))
        assertTrue(export.contains("Auto-Connect: Enabled — Global"))
        assertTrue(export.contains("Result: COUNTRY_MISMATCH"))
        assertTrue(export.contains("Package: app.pkg"))
    }

    @Test fun resolverIsDeterministicAndDoesNotMutateInput() {
        val input = PolicyDecisionInput(true, policy, UpstreamVpnEvaluation.Missing, false, false, true)
        val first = PolicyDecisionResolver.resolve(input)
        repeat(20) { assertEquals(first, PolicyDecisionResolver.resolve(input)) }
        assertEquals("US", input.policy?.requiredCountryCode)
    }

    @Test fun profileAndGlobalInheritanceMetadataComeFromSharedEffectiveResolver() {
        val globals = EffectiveVpnPolicyResolver.Globals(true, "global.vpn", "CA", true, true)
        val inherited = EffectiveVpnPolicyResolver.resolve(ProfileVpnPolicy(), globals, null, false)
        assertEquals(PolicySource.GLOBAL, inherited.providerSource)
        assertEquals(PolicySource.GLOBAL, inherited.countrySource)
        val overridden = EffectiveVpnPolicyResolver.resolve(
            ProfileVpnPolicy(providerPackage = "profile.vpn", country = "GB"), globals, "US", false)
        assertEquals(PolicySource.PROFILE, overridden.providerSource)
        assertEquals(PolicySource.PER_APP, overridden.countrySource)
        assertEquals(PolicyDecision.PROTECTED, PolicyDecisionResolver.resolve(PolicyDecisionInput(
            true, overridden, UpstreamVpnEvaluation.Valid("US"), false, false, true)).decision)
    }
}
