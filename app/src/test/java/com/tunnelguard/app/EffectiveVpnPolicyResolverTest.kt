package com.tunnelguard.app

import org.junit.Assert.*
import org.junit.Test

class EffectiveVpnPolicyResolverTest {
    private val globals = EffectiveVpnPolicyResolver.Globals(true, "com.global.vpn", "CA", true, true)

    @Test fun oldProfileInheritsEveryGlobal() {
        val result = EffectiveVpnPolicyResolver.resolve(null, globals, null, false)
        assertTrue(result.requireVpn)
        assertEquals("com.global.vpn", result.providerPackage)
        assertEquals("CA", result.requiredCountryCode)
        assertTrue(result.autoConnect)
        assertEquals(PolicySource.GLOBAL, result.providerSource)
    }

    @Test fun explicitProfileOverridesAreResolved() {
        val policy = ProfileVpnPolicy(ProfileVpnPolicy.RequirementMode.NOT_REQUIRED, "com.profile.vpn", "US",
            ProfileVpnPolicy.ToggleMode.DISABLED, ProfileVpnPolicy.ToggleMode.ENABLED)
        val result = EffectiveVpnPolicyResolver.resolve(policy, globals, null, false)
        assertFalse(result.requireVpn)
        assertEquals("com.profile.vpn", result.providerPackage)
        assertEquals("US", result.requiredCountryCode)
        assertFalse(result.autoConnect)
        assertEquals(PolicySource.PROFILE, result.countrySource)
    }

    @Test fun appCountryAndEmergencyLockHaveHighestPrecedence() {
        val policy = ProfileVpnPolicy(requirement = ProfileVpnPolicy.RequirementMode.NOT_REQUIRED, country = "US")
        val result = EffectiveVpnPolicyResolver.resolve(policy, globals, "gb", true)
        assertTrue(result.requireVpn)
        assertEquals("GB", result.requiredCountryCode)
        assertEquals(PolicySource.EMERGENCY_LOCK, result.requirementSource)
        assertEquals(PolicySource.PER_APP, result.countrySource)
    }

    @Test fun appCountryStillRequiresVpnWhenProfileDoesNot() {
        val policy = ProfileVpnPolicy(requirement = ProfileVpnPolicy.RequirementMode.NOT_REQUIRED,
            countryValidation = ProfileVpnPolicy.ToggleMode.DISABLED)
        val result = EffectiveVpnPolicyResolver.resolve(policy, globals, "US", false)
        assertTrue(result.requireVpn)
        assertTrue(result.requireCountryMatch)
        assertEquals("US", result.requiredCountryCode)
    }

    @Test fun disabledValidationProducesAnyAndUnknownValuesAreRejected() {
        val policy = ProfileVpnPolicy(country = "USA!", countryValidation = ProfileVpnPolicy.ToggleMode.DISABLED)
        val result = EffectiveVpnPolicyResolver.resolve(policy, globals, null, false)
        assertEquals("ANY", result.requiredCountryCode)
        assertFalse(result.requireCountryMatch)
        assertNull(ProfileVpnPolicy.normalizePackage("not a package"))
        assertNull(ProfileVpnPolicy.normalizeCountry("USA!"))
    }
}
