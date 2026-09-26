package com.tunnelguard.app

import org.junit.Assert.*
import org.junit.Test

class ProfileAutomationTest {
    private val valid = setOf("streaming", "everything", "renamed-id")
    private fun rule(condition: ProfileRuleCondition, target: String = "streaming", enabled: Boolean = true, priority: Int = 0, id: String = condition.name, ssid: String? = null) =
        ProfileSwitchRule(id, condition, target, enabled, priority, ssid)

    @Test fun transportAndVpnConditionsMatch() {
        assertEquals(ProfileRuleCondition.WIFI_CONNECTED, ProfileRuleEvaluator.match(listOf(rule(ProfileRuleCondition.WIFI_CONNECTED)), ProfileNetworkState(true, false, false), valid)?.condition)
        assertEquals(ProfileRuleCondition.ETHERNET_CONNECTED, ProfileRuleEvaluator.match(listOf(rule(ProfileRuleCondition.ETHERNET_CONNECTED)), ProfileNetworkState(false, true, false), valid)?.condition)
        assertEquals(ProfileRuleCondition.VPN_CONNECTED, ProfileRuleEvaluator.match(listOf(rule(ProfileRuleCondition.VPN_CONNECTED)), ProfileNetworkState(false, false, true), valid)?.condition)
        assertEquals(ProfileRuleCondition.VPN_DISCONNECTED, ProfileRuleEvaluator.match(listOf(rule(ProfileRuleCondition.VPN_DISCONNECTED)), ProfileNetworkState(false, false, false), valid)?.condition)
    }

    @Test fun priorityIsExplicitAndDeterministic() {
        val low = rule(ProfileRuleCondition.WIFI_CONNECTED, "streaming", priority = 8, id = "low")
        val high = rule(ProfileRuleCondition.WIFI_CONNECTED, "everything", priority = 1, id = "high")
        assertEquals("high", ProfileRuleEvaluator.match(listOf(low, high), ProfileNetworkState(true, false, false), valid)?.id)
    }

    @Test fun disabledAndDeletedTargetsAreIgnored() {
        assertNull(ProfileRuleEvaluator.match(listOf(rule(ProfileRuleCondition.WIFI_CONNECTED, enabled = false)), ProfileNetworkState(true, false, false), valid))
        assertNull(ProfileRuleEvaluator.match(listOf(rule(ProfileRuleCondition.WIFI_CONNECTED, "deleted")), ProfileNetworkState(true, false, false), valid))
    }

    @Test fun renamedProfileStillMatchesByStableId() {
        assertEquals("renamed-id", ProfileRuleEvaluator.match(listOf(rule(ProfileRuleCondition.WIFI_CONNECTED, "renamed-id")), ProfileNetworkState(true, false, false), valid)?.profileId)
    }

    @Test fun noConditionProducesNoMatch() {
        assertNull(ProfileRuleEvaluator.match(listOf(rule(ProfileRuleCondition.ETHERNET_CONNECTED)), ProfileNetworkState(true, false, false), valid))
    }

    @Test fun indeterminateVpnMatchesNeitherVpnRule() {
        val rules = listOf(rule(ProfileRuleCondition.VPN_CONNECTED), rule(ProfileRuleCondition.VPN_DISCONNECTED, id = "disconnected"))
        assertNull(ProfileRuleEvaluator.match(rules, ProfileNetworkState(false, false, null), valid))
    }

    @Test fun exactSsidAndQuoteNormalizationMatch() {
        val named = rule(ProfileRuleCondition.WIFI_NETWORK, ssid = "HomeNetwork")
        assertEquals(named, ProfileRuleEvaluator.match(listOf(named), wifi("\"HomeNetwork\""), valid))
        assertEquals("HomeNetwork", normalizeSsid(" \"HomeNetwork\" "))
    }

    @Test fun ssidMismatchDoesNotMatchNamedRule() {
        assertNull(ProfileRuleEvaluator.match(listOf(rule(ProfileRuleCondition.WIFI_NETWORK, ssid = "Home")), wifi("CoffeeShop"), valid))
    }

    @Test fun unavailableValuesNeverBecomeNamedOrUnknownNetworks() {
        listOf<String?>(null, "", "  ", "<unknown ssid>", "\"<unknown ssid>\"").forEach { raw ->
            assertNull(normalizeSsid(raw))
            val state = ProfileNetworkState(true, false, false, WifiIdentityStatus.UNAVAILABLE, raw)
            assertNull(ProfileRuleEvaluator.match(listOf(
                rule(ProfileRuleCondition.WIFI_NETWORK, ssid = "Home"),
                rule(ProfileRuleCondition.UNKNOWN_WIFI_NETWORK, id = "unknown")
            ), state, valid))
        }
    }

    @Test fun usableUnrecognizedWifiMatchesUnknownRule() {
        val unknown = rule(ProfileRuleCondition.UNKNOWN_WIFI_NETWORK, id = "unknown")
        assertEquals(unknown, ProfileRuleEvaluator.match(listOf(unknown), wifi("CoffeeShop"), valid))
    }

    @Test fun recognizedWifiDoesNotMatchUnknownRuleEvenWhenUnknownHasHigherPriority() {
        val named = rule(ProfileRuleCondition.WIFI_NETWORK, priority = 10, id = "named", ssid = "Home")
        val unknown = rule(ProfileRuleCondition.UNKNOWN_WIFI_NETWORK, priority = 0, id = "unknown")
        assertEquals(named, ProfileRuleEvaluator.match(listOf(unknown, named), wifi("Home"), valid))
    }

    @Test fun specificAndGenericWifiUseNormalPriorityRules() {
        val named = rule(ProfileRuleCondition.WIFI_NETWORK, priority = 0, id = "named", ssid = "Home")
        val generic = rule(ProfileRuleCondition.WIFI_CONNECTED, priority = 10, id = "generic")
        assertEquals(named, ProfileRuleEvaluator.match(listOf(generic, named), wifi("Home"), valid))
        assertEquals(generic, ProfileRuleEvaluator.match(listOf(generic, named), wifi("Other"), valid))
    }

    @Test fun equalPriorityUsesStableRuleId() {
        val b = rule(ProfileRuleCondition.WIFI_CONNECTED, id = "b")
        val a = rule(ProfileRuleCondition.WIFI_CONNECTED, id = "a")
        assertEquals("a", ProfileRuleEvaluator.match(listOf(b, a), wifi("Home"), valid)?.id)
    }

    @Test fun trackerSuppressesRepeatsAndTreatsSsidChangeAsMeaningful() {
        val tracker = ProfileAutomationStateTracker()
        assertNull(tracker.observe(wifi("A")))
        assertEquals(ProfileAutomationResult.UnchangedState, tracker.observe(wifi("A")))
        assertNull(tracker.observe(wifi("B")))
    }

    @Test fun manualOverrideSurvivesSameIdentityAndClearsOnSsidChange() {
        val tracker = ProfileAutomationStateTracker()
        tracker.noteManualSelection()
        assertEquals(ProfileAutomationResult.ManualOverride, tracker.observe(wifi("A")))
        assertEquals(ProfileAutomationResult.ManualOverride, tracker.observe(wifi("A")))
        assertNull(tracker.observe(wifi("B")))
        assertEquals(ProfileAutomationResult.UnchangedState, tracker.observe(wifi("B")))
    }

    private fun wifi(ssid: String) = ProfileNetworkState(true, false, false, WifiIdentityStatus.KNOWN, ssid)
}
