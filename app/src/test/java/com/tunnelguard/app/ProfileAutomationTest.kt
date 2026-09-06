package com.tunnelguard.app

import org.junit.Assert.*
import org.junit.Test

class ProfileAutomationTest {
    private val valid = setOf("streaming", "everything", "renamed-id")
    private fun rule(condition: ProfileRuleCondition, target: String = "streaming", enabled: Boolean = true, priority: Int = 0, id: String = condition.name) =
        ProfileSwitchRule(id, condition, target, enabled, priority)

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
}
