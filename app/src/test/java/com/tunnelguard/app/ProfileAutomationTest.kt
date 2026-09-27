package com.tunnelguard.app

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ProfileAutomationTest {
    private val valid = setOf("streaming", "everything", "renamed-id")
    private fun rule(condition: ProfileRuleCondition, target: String = "streaming", enabled: Boolean = true, priority: Int = 0, id: String = condition.name, ssid: String? = null) =
        ProfileSwitchRule(id, condition, target, enabled, priority, ssid)
    private fun schedule(start: Int, end: Int? = null, days: Set<Int> = ScheduleRules.everyDay, target: String = "streaming", enabled: Boolean = true, priority: Int = 0, id: String = "schedule") =
        ProfileSwitchRule(id, ProfileRuleCondition.SCHEDULED_TIME, target, enabled, priority, startMinute = start, endMinute = end, daysOfWeek = days)
    private fun at(day: Int, hour: Int, minute: Int = 0) = ProfileTimeState(day, hour * 60 + minute, 0L, "test-zone")

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
        tracker.noteManualSelection(wifi("A"))
        assertEquals(ProfileAutomationResult.ManualOverride, tracker.observe(wifi("A")))
        assertNull(tracker.observe(wifi("B")))
        assertEquals(ProfileAutomationResult.UnchangedState, tracker.observe(wifi("B")))
    }

    @Test fun ssidIdentifierEnforcesAndroidLengthLimits() {
        assertTrue(isValidSsidIdentifier("é".repeat(16)))
        assertFalse(isValidSsidIdentifier("é".repeat(17)))
        assertTrue(isValidSsidIdentifier("ab".repeat(32)))
        assertTrue(isValidSsidIdentifier("0x" + "ab".repeat(32)))
        assertFalse(isValidSsidIdentifier("ab".repeat(33)))
        assertFalse(isValidSsidIdentifier("gg".repeat(20)))
    }

    @Test fun clearingTrackerRemovesBoundManualOverride() {
        val tracker = ProfileAutomationStateTracker()
        tracker.noteManualSelection(wifi("A"))
        tracker.clear()
        assertNull(tracker.observe(wifi("A")))
    }

    @Test fun dailySingleTimeMatchesOnlyExactMinute() {
        val rule = schedule(18 * 60)
        assertFalse(ScheduleRules.matches(rule, at(Calendar.MONDAY, 17, 59)))
        assertTrue(ScheduleRules.matches(rule, at(Calendar.MONDAY, 18)))
        assertFalse(ScheduleRules.matches(rule, at(Calendar.MONDAY, 18, 1)))
    }

    @Test fun weekdayWeekendAndCustomDaysMatch() {
        assertTrue(ScheduleRules.matches(schedule(8 * 60, 9 * 60, ScheduleRules.weekdays), at(Calendar.MONDAY, 8)))
        assertFalse(ScheduleRules.matches(schedule(8 * 60, 9 * 60, ScheduleRules.weekdays), at(Calendar.SUNDAY, 8)))
        assertTrue(ScheduleRules.matches(schedule(9 * 60, 10 * 60, ScheduleRules.weekends), at(Calendar.SATURDAY, 9)))
        assertTrue(ScheduleRules.matches(schedule(10 * 60, 11 * 60, setOf(Calendar.WEDNESDAY)), at(Calendar.WEDNESDAY, 10)))
    }

    @Test fun rangeBoundariesAreStartInclusiveEndExclusive() {
        val rule = schedule(18 * 60, 23 * 60)
        assertTrue(ScheduleRules.matches(rule, at(Calendar.MONDAY, 18)))
        assertTrue(ScheduleRules.matches(rule, at(Calendar.MONDAY, 22, 59)))
        assertFalse(ScheduleRules.matches(rule, at(Calendar.MONDAY, 23)))
    }

    @Test fun overnightAfterMidnightBelongsToPreviousSelectedDay() {
        val mondayNight = schedule(22 * 60, 6 * 60, setOf(Calendar.MONDAY))
        assertTrue(ScheduleRules.matches(mondayNight, at(Calendar.MONDAY, 23)))
        assertTrue(ScheduleRules.matches(mondayNight, at(Calendar.TUESDAY, 0)))
        assertTrue(ScheduleRules.matches(mondayNight, at(Calendar.TUESDAY, 2)))
        assertFalse(ScheduleRules.matches(mondayNight, at(Calendar.TUESDAY, 6)))
        assertFalse(ScheduleRules.matches(mondayNight, at(Calendar.MONDAY, 2)))
    }

    @Test fun invalidAndDisabledScheduleRulesDoNotWin() {
        val invalid = schedule(-1, 60)
        assertFalse(invalid.hasValidSchedule())
        assertNull(ProfileRuleEvaluator.match(listOf(invalid), wifi("Home"), valid, at(Calendar.MONDAY, 0)))
        assertNull(ProfileRuleEvaluator.match(listOf(schedule(0, 60, enabled = false)), wifi("Home"), valid, at(Calendar.MONDAY, 0)))
        assertNull(ProfileRuleEvaluator.match(listOf(schedule(0, 60, target = "missing")), wifi("Home"), valid, at(Calendar.MONDAY, 0)))
    }

    @Test fun schedulesAndNetworksSharePriorityAndStableIdTieBreak() {
        val network = rule(ProfileRuleCondition.WIFI_CONNECTED, target = "streaming", priority = 10, id = "network")
        val scheduled = schedule(0, 1439, target = "everything", priority = 0, id = "scheduled")
        assertEquals("scheduled", ProfileRuleEvaluator.match(listOf(network, scheduled), wifi("Home"), valid, at(Calendar.MONDAY, 12))?.id)
        val a = schedule(0, 1439, priority = 3, id = "a")
        val b = schedule(0, 1439, priority = 3, id = "b")
        assertEquals("a", ProfileRuleEvaluator.match(listOf(b, a), wifi("Home"), valid, at(Calendar.MONDAY, 12))?.id)
    }

    @Test fun manualOverridePersistsUntilSchedulePhaseChanges() {
        val tracker = ProfileAutomationStateTracker()
        tracker.noteManualSelection(wifi("Home"), "evening")
        assertEquals(ProfileAutomationResult.ManualOverride, tracker.observe(wifi("Home"), "evening"))
        assertNull(tracker.observe(wifi("Home"), ""))
    }

    @Test fun timeInputIsTimezoneIndependentAndBoundaryUsesRequestedZone() {
        val rule = schedule(22 * 60, 6 * 60, setOf(Calendar.MONDAY))
        assertTrue(ScheduleRules.matches(rule, ProfileTimeState(Calendar.TUESDAY, 120, 123L, "Pacific/Auckland")))
        val zone = TimeZone.getTimeZone("America/New_York")
        val now = Calendar.getInstance(zone).apply { set(2024, Calendar.MARCH, 9, 23, 0, 0); set(Calendar.MILLISECOND, 0) }
        val next = ProfileScheduleManager.nextBoundary(listOf(schedule(2 * 60 + 30)), now.timeInMillis, zone)!!
        val result = Calendar.getInstance(zone).apply { timeInMillis = next }
        assertEquals(Calendar.MARCH, result.get(Calendar.MONTH))
        assertEquals(10, result.get(Calendar.DAY_OF_MONTH))
        assertTrue(next > now.timeInMillis)
    }

    private fun wifi(ssid: String) = ProfileNetworkState(true, false, false, WifiIdentityStatus.KNOWN, ssid)
}
