package com.tunnelguard.app

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

class TunnelGuardConfig(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "tunnel_guard_prefs"
        private const val KEY_PROTECTED_APPS = "protected_apps"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_SIMULATE_VPN = "simulate_vpn"
        private const val KEY_LOGS = "debug_logs"
        private const val KEY_VPN_STATUS = "vpn_status" // To persist mocked/detected VPN state
        private const val KEY_PROTECTION_ENABLED = "protection_enabled" // Active or inactive
        private const val KEY_VERSION_NAME = "override_version_name"
        private const val KEY_FORCE_UPDATES = "forced_updates_enabled"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        const val TUNNEL_ADDRESS = "10.0.0.1"
        const val TUNNEL_PREFIX_LENGTH = 24
    }

    /**
     * Profile data class definition
     */
    data class ProtectionProfile(
        val id: String,
        val name: String,
        val appPackages: Set<String>,
        val isSystem: Boolean = false
    )

    /**
     * Determines whether an external VPN is currently active.
     *
     * @param connectivityManager The connectivity manager used to inspect available networks.
     * @return `true` if an external VPN network is detected, `false` otherwise.
     */
    fun detectRealVpnCapabilities(connectivityManager: ConnectivityManager?): Boolean {
        if (connectivityManager == null) return false
        try {
            val networks = connectivityManager.allNetworks
            for (network in networks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue

                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    // Exclude the local VPN interface created by our own service to prevent self-detection feedback loops
                    val linkProperties = connectivityManager.getLinkProperties(network)
                    val addresses = linkProperties?.linkAddresses ?: emptyList()

                    if (isOurOurVpn(addresses)) {
                        continue // Skip our own local interface
                    }

                    return true
                }
            }
        } catch (e: Exception) {
            addLog("Error detecting active VPN capabilities: ${e.message}")
        }
        return false
    }

    /**
     * Determines whether the interface includes one of TunnelGuard's tunnel addresses.
     *
     * @param addresses The interface link addresses to inspect.
     * @return `true` if an address matches the configured IPv4 or documented IPv6 tunnel address, `false` otherwise.
     */
    private fun isOurOurVpn(addresses: List<android.net.LinkAddress>): Boolean {
        val ourIpv4 = try { java.net.InetAddress.getByName(TUNNEL_ADDRESS) } catch (e: Exception) { null }
        val ourIpv6 = try { java.net.InetAddress.getByName("2001:db8::1") } catch (e: Exception) { null }
        return addresses.any {
            val addr = it.address
            (ourIpv4 != null && addr == ourIpv4) || (ourIpv6 != null && addr == ourIpv6)
        }
    }

    /**
     * Profile Management
     */
    fun getProfiles(): List<ProtectionProfile> {
        val jsonStr = prefs.getString("protection_profiles", null)
        if (jsonStr == null) {
            // Prepopulate default profiles
            val list = listOf(
                ProtectionProfile("streaming", "Streaming", setOf("com.tivimate.app", "org.xbmc.kodi", "com.netflix.mediaclient", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv", "org.courville.nova"), true),
                ProtectionProfile("everything", "Everything", emptySet(), true),
                ProtectionProfile("custom", "Custom", emptySet(), true)
            )
            saveProfiles(list)
            return list
        }
        val list = mutableListOf<ProtectionProfile>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val isSystem = obj.optBoolean("isSystem", false)
                val appsArr = obj.getJSONArray("apps")
                val apps = mutableSetOf<String>()
                for (j in 0 until appsArr.length()) {
                    apps.add(appsArr.getString(j))
                }
                list.add(ProtectionProfile(id, name, apps, isSystem))
            }
        } catch (e: Exception) {
            addLog("Error parsing profiles: ${e.message}")
        }
        return list
    }

    fun saveProfiles(profiles: List<ProtectionProfile>) {
        val arr = JSONArray()
        for (profile in profiles) {
            val obj = JSONObject()
            obj.put("id", profile.id)
            obj.put("name", profile.name)
            obj.put("isSystem", profile.isSystem)
            val appsArr = JSONArray()
            profile.appPackages.forEach { appsArr.put(it) }
            obj.put("apps", appsArr)
            arr.put(obj)
        }
        prefs.edit().putString("protection_profiles", arr.toString()).apply()
    }

    fun getSelectedProfileId(): String {
        return prefs.getString("selected_profile_id", "streaming") ?: "streaming"
    }

    fun setSelectedProfileId(id: String) {
        prefs.edit().putString("selected_profile_id", id).apply()
        addLog("Selected profile changed to: $id")
    }

    fun getDefaultProfileId(): String {
        return prefs.getString("default_profile_id", "streaming") ?: "streaming"
    }

    fun setDefaultProfileId(id: String) {
        prefs.edit().putString("default_profile_id", id).apply()
        addLog("Default profile changed to: $id")
    }

    fun createProfile(name: String): String {
        val id = "custom_" + java.util.UUID.randomUUID().toString().take(8)
        val profiles = getProfiles().toMutableList()
        profiles.add(ProtectionProfile(id, name, emptySet(), false))
        saveProfiles(profiles)
        addLog("Created custom profile: $name ($id)")
        return id
    }

    fun renameProfile(id: String, newName: String) {
        val profiles = getProfiles().map {
            if (it.id == id && !it.isSystem) {
                it.copy(name = newName)
            } else {
                it
            }
        }
        saveProfiles(profiles)
        addLog("Renamed profile $id -> $newName")
    }

    fun deleteProfile(id: String) {
        val profiles = getProfiles().filter { it.id != id || it.isSystem }
        saveProfiles(profiles)
        addLog("Deleted profile $id")
        if (getSelectedProfileId() == id) {
            setSelectedProfileId("streaming")
        }
        if (getDefaultProfileId() == id) {
            setDefaultProfileId("streaming")
        }
    }

    fun getAllLauncherApps(): Set<String> {
        val set = mutableSetOf<String>()
        try {
            val pm = context.packageManager
            // Query Leanback launcher apps
            val tvIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
            }
            val tvApps = pm.queryIntentActivities(tvIntent, 0)
            for (resolveInfo in tvApps) {
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg != context.packageName) {
                    set.add(pkg)
                }
            }

            // Query standard launcher apps
            val standardIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val standardApps = pm.queryIntentActivities(standardIntent, 0)
            for (resolveInfo in standardApps) {
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg != context.packageName) {
                    set.add(pkg)
                }
            }
        } catch (e: Exception) {
            addLog("Error querying launcher apps: ${e.message}")
        }
        return set
    }

    /**
     * Get the set of package names of selected apps to protect.
     */
    fun getProtectedApps(): Set<String> {
        val activeId = getSelectedProfileId()
        if (activeId == "everything") {
            return getAllLauncherApps()
        }
        val profiles = getProfiles()
        val activeProfile = profiles.find { it.id == activeId }
        return activeProfile?.appPackages ?: emptySet()
    }

    /**
     * Save the set of package names of selected apps to protect.
     */
    fun setProtectedApps(apps: Set<String>) {
        val activeId = getSelectedProfileId()
        if (activeId == "everything") {
            // Cannot edit "everything" profile apps directly since it dynamically returns all launcher apps.
            return
        }
        val profiles = getProfiles().map {
            if (it.id == activeId) {
                it.copy(appPackages = apps)
            } else {
                it
            }
        }
        saveProfiles(profiles)
    }

    /**
     * Add or remove a single app from the list of protected apps.
     */
    fun setAppProtected(packageName: String, protected: Boolean) {
        val apps = getProtectedApps().toMutableSet()
        if (protected) {
            apps.add(packageName)
        } else {
            apps.remove(packageName)
        }
        setProtectedApps(apps)
    }

    fun isAppProtected(packageName: String): Boolean {
        return getProtectedApps().contains(packageName)
    }

    /**
     * Startup behavior: Start on Boot.
     */
    fun isStartOnBootEnabled(): Boolean {
        return prefs.getBoolean(KEY_START_ON_BOOT, false)
    }

    fun setStartOnBootEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
    }

    /**
     * Simulation mode: whether to simulate VPN status changes instead of relying purely on TRANSPORT_VPN.
     */
    fun isSimulatedVpnEnabled(): Boolean {
        return prefs.getBoolean(KEY_SIMULATE_VPN, false)
    }

    fun setSimulatedVpnEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SIMULATE_VPN, enabled).apply()
    }

    /**
     * Retrieves the persisted VPN state, normalizing `CONNECTED` to `PROTECTED`.
     *
     * @return The persisted VPN state, or `DISCONNECTED` if the stored value is missing or invalid.
     */
    fun getVPNState(): VPNState {
        val name = prefs.getString(KEY_VPN_STATUS, VPNState.DISCONNECTED.name)
        val parsed = try {
            VPNState.valueOf(name ?: VPNState.DISCONNECTED.name)
        } catch (e: Exception) {
            VPNState.DISCONNECTED
        }
        return if (parsed == VPNState.CONNECTED) VPNState.PROTECTED else parsed
    }

    var elapsedRealtimeProvider: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /**
     * Persists the VPN state and updates the connection start time.
     *
     * `CONNECTED` is stored as `PROTECTED`. Active states preserve an existing connection start time,
     * while other states clear it.
     *
     * @param state The VPN state to persist.
     */
    fun setVPNState(state: VPNState) {
        val normalizedState = if (state == VPNState.CONNECTED) VPNState.PROTECTED else state
        val oldStateName = prefs.getString(KEY_VPN_STATUS, VPNState.DISCONNECTED.name)
        if (normalizedState.name != oldStateName) {
            updateLastStateTransitionTime(System.currentTimeMillis())
        }
        prefs.edit().putString(KEY_VPN_STATUS, normalizedState.name).apply()
        if (normalizedState == VPNState.CONNECTED || normalizedState == VPNState.PROTECTED) {
            val startTime = prefs.getLong("vpn_connection_start_time", 0L)
            if (startTime == 0L) {
                prefs.edit().putLong("vpn_connection_start_time", elapsedRealtimeProvider()).apply()
            }
        } else {
            prefs.edit().putLong("vpn_connection_start_time", 0L).apply()
        }
    }

    fun isForcedUpdatesEnabled(): Boolean {
        return prefs.getBoolean(KEY_FORCE_UPDATES, true)
    }

    fun setForcedUpdatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_UPDATES, enabled).apply()
        addLog("Forced updates enabled set to: $enabled")
    }

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
        addLog("Onboarding completed set to: $completed")
    }

    fun getLastStateTransitionTime(): Long {
        return prefs.getLong("last_state_transition_time", 0L)
    }

    fun updateLastStateTransitionTime(time: Long) {
        prefs.edit().putLong("last_state_transition_time", time).apply()
    }

    fun isIpv6ProtectionActive(): Boolean {
        return prefs.getBoolean("ipv6_protection_active", false)
    }

    fun setIpv6ProtectionActive(active: Boolean) {
        prefs.edit().putBoolean("ipv6_protection_active", active).apply()
    }

    fun getLastBootFailure(): String? {
        return prefs.getString("last_boot_failure", null)
    }

    fun setLastBootFailure(failure: String?) {
        prefs.edit().putString("last_boot_failure", failure).apply()
    }

    fun getConnectionUptimeMillis(): Long {
        val state = getVPNState()
        if (state == VPNState.CONNECTED || state == VPNState.PROTECTED) {
            val startTime = prefs.getLong("vpn_connection_start_time", 0L)
            if (startTime > 0L) {
                return elapsedRealtimeProvider() - startTime
            }
        }
        return 0L
    }

    fun getLastDisconnectReason(): String {
        return prefs.getString("last_disconnect_reason", "None") ?: "None"
    }

    fun setLastDisconnectReason(reason: String) {
        prefs.edit().putString("last_disconnect_reason", reason).apply()
        addLog("Last Disconnect Reason updated: $reason")
    }

    /**
     * Protection Master Switch: Enabled (ACTIVE or BLOCKING) vs Disabled (INACTIVE).
     */
    fun isProtectionEnabled(): Boolean {
        return prefs.getBoolean(KEY_PROTECTION_ENABLED, false)
    }

    fun setProtectionEnabled(enabled: Boolean) {
        val old = prefs.getBoolean(KEY_PROTECTION_ENABLED, false)
        if (old != enabled) {
            updateLastStateTransitionTime(System.currentTimeMillis())
        }
        prefs.edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).apply()
    }

    /**
     * Helper to get computed Protection State based on Master Switch and VPN State.
     */
    fun getProtectionState(): ProtectionState {
        if (isEmergencyLockEnabled()) {
            return ProtectionState.BLOCKING
        }
        if (!isProtectionEnabled()) {
            return ProtectionState.INACTIVE
        }
        val vpn = getVPNState()
        return if (vpn == VPNState.CONNECTED || vpn == VPNState.PROTECTED) {
            ProtectionState.ACTIVE
        } else {
            ProtectionState.BLOCKING
        }
    }

    /**
     * Emergency Lock Option
     */
    fun isEmergencyLockEnabled(): Boolean {
        return prefs.getBoolean("emergency_lock_enabled", false)
    }

    fun setEmergencyLockEnabled(enabled: Boolean) {
        val old = prefs.getBoolean("emergency_lock_enabled", false)
        if (old != enabled) {
            updateLastStateTransitionTime(System.currentTimeMillis())
        }
        prefs.edit().putBoolean("emergency_lock_enabled", enabled).apply()
        addLog("Emergency Lock set to: $enabled")
    }

    /**
     * DNS Protection/Status detection
     */
    fun detectDnsStatus(connectivityManager: ConnectivityManager?, isServiceRunning: Boolean): DNSStatus {
        if (isEmergencyLockEnabled()) {
            return DNSStatus.PROTECTED
        }
        if (isSimulatedVpnEnabled()) {
            val state = getVPNState()
            return if (state == VPNState.CONNECTED || state == VPNState.PROTECTED) {
                DNSStatus.PROTECTED
            } else {
                if (isProtectionEnabled()) DNSStatus.PROTECTED else DNSStatus.WARNING
            }
        }

        if (connectivityManager == null) return DNSStatus.UNKNOWN

        try {
            if (isProtectionEnabled() && getProtectionState() == ProtectionState.BLOCKING && isServiceRunning) {
                return DNSStatus.PROTECTED
            }

            val networks = connectivityManager.allNetworks
            var vpnActive = false
            var vpnHasDns = false

            for (network in networks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    val linkProperties = connectivityManager.getLinkProperties(network)
                    val addresses = linkProperties?.linkAddresses ?: emptyList()
                    if (isOurOurVpn(addresses)) {
                        continue
                    }
                    vpnActive = true
                    val dnsServers = linkProperties?.dnsServers ?: emptyList()
                    if (dnsServers.isNotEmpty()) {
                        vpnHasDns = true
                    }
                }
            }

            return when {
                vpnActive && vpnHasDns -> DNSStatus.PROTECTED
                vpnActive -> DNSStatus.UNKNOWN
                isProtectionEnabled() -> DNSStatus.WARNING
                else -> DNSStatus.WARNING
            }
        } catch (e: Exception) {
            addLog("Error detecting DNS status: ${e.message}")
            return DNSStatus.UNKNOWN
        }
    }

    /**
     * Log persistence. Simple in-memory or SharedPref based log store for development display.
     */
    fun addLog(message: String, level: String = "INFO") {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$timestamp] [$level] $message"
        val logs = getLogs().toMutableList()
        logs.add(0, formatted) // Keep newest first

        // Log rotation: Keep max 500 lines
        while (logs.size > 500) {
            logs.removeAt(logs.size - 1)
        }

        val arr = JSONArray()
        logs.forEach { arr.put(it) }
        prefs.edit().putString(KEY_LOGS, arr.toString()).apply()
    }

    fun addLogInfo(message: String) = addLog(message, "INFO")
    fun addLogWarning(message: String) = addLog(message, "WARN")
    fun addLogError(message: String) = addLog(message, "ERROR")

    fun getLogs(): List<String> {
        val jsonStr = prefs.getString(KEY_LOGS, "[]") ?: "[]"
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun clearLogs() {
        prefs.edit().remove(KEY_LOGS).apply()
    }

    fun exportLogsToFile(): java.io.File? {
        try {
            val exportFile = java.io.File(context.cacheDir, "exported_tunnelguard_logs.txt")
            java.io.FileWriter(exportFile).use { writer ->
                val logs = getLogs()
                // Write oldest first for correct chronological reading
                for (i in logs.indices.reversed()) {
                    writer.write(logs[i])
                    writer.write("\n")
                }
            }
            return exportFile
        } catch (e: Exception) {
            addLog("Failed to export logs: ${e.message}", "ERROR")
            return null
        }
    }

    fun exportConfigToJson(): String? {
        val obj = JSONObject()
        try {
            obj.put("start_on_boot", isStartOnBootEnabled())
            obj.put("app_monitor_enabled", isAppMonitorEnabled())
            obj.put("forced_updates_enabled", isForcedUpdatesEnabled())
            obj.put("selected_profile_id", getSelectedProfileId())

            val profilesStr = prefs.getString("protection_profiles", null)
            if (profilesStr != null) {
                obj.put("protection_profiles", JSONArray(profilesStr))
            }
            return obj.toString()
        } catch (e: Exception) {
            addLog("Failed to export configuration: ${e.message}", "ERROR")
            return null
        }
    }

    fun importConfigFromJson(jsonStr: String): Boolean {
        try {
            val obj = JSONObject(jsonStr)
            val startOnBoot = obj.optBoolean("start_on_boot", false)
            val appMonitorEnabled = obj.optBoolean("app_monitor_enabled", false)
            val forcedUpdatesEnabled = obj.optBoolean("forced_updates_enabled", true)
            val selectedProfileId = obj.optString("selected_profile_id", "streaming")

            val pkgRegex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")

            val profilesArr = obj.optJSONArray("protection_profiles")
            val validatedProfiles = mutableListOf<ProtectionProfile>()

            if (profilesArr != null) {
                for (i in 0 until profilesArr.length()) {
                    val pObj = profilesArr.getJSONObject(i)
                    val id = pObj.getString("id")
                    val name = pObj.getString("name")
                    val isSystem = pObj.optBoolean("isSystem", false)
                    val appsArr = pObj.getJSONArray("apps")

                    val validatedApps = mutableSetOf<String>()
                    for (j in 0 until appsArr.length()) {
                        val appPkg = appsArr.getString(j)
                        if (pkgRegex.matches(appPkg)) {
                            validatedApps.add(appPkg)
                        } else {
                            addLog("Ignored invalid package name on import: $appPkg", "WARN")
                        }
                    }
                    validatedProfiles.add(ProtectionProfile(id, name, validatedApps, isSystem))
                }
            }

            setStartOnBootEnabled(startOnBoot)
            setAppMonitorEnabled(appMonitorEnabled)
            setForcedUpdatesEnabled(forcedUpdatesEnabled)

            if (validatedProfiles.isNotEmpty()) {
                saveProfiles(validatedProfiles)
            }

            // Validate selectedProfileId against imported profiles + existing profiles
            val existingProfiles = getProfiles()
            val validProfileIds = (validatedProfiles.map { it.id } + existingProfiles.map { it.id }).toSet()
            val finalProfileId = if (validProfileIds.contains(selectedProfileId)) selectedProfileId else "streaming"
            setSelectedProfileId(finalProfileId)

            addLog("Configuration imported successfully.", "INFO")
            return true
        } catch (e: Exception) {
            addLog("Failed to import configuration: ${e.message}", "ERROR")
            return false
        }
    }

    /**
     * Get the dynamic app version name. If no override exists, returns package manager's versionName.
     */
    fun getAppVersionName(): String {
        val override = prefs.getString(KEY_VERSION_NAME, null)
        if (override != null) {
            return override
        }
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            addLog("Failed package-info lookup: ${e.message}")
            "1.0.0"
        }
    }

    /**
     * Set/Override the dynamic app version name.
     */
    fun setAppVersionName(versionName: String) {
        prefs.edit().putString(KEY_VERSION_NAME, versionName).apply()
    }

    /**
     * App Monitor preferences and utilities
     */
    fun isAppMonitorEnabled(): Boolean {
        return prefs.getBoolean("app_monitor_enabled", false)
    }

    fun setAppMonitorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("app_monitor_enabled", enabled).apply()
        addLog("App Monitor enabled set to: $enabled")
    }

    fun getVpnAppOfChoice(): String? {
        return prefs.getString("vpn_app_of_choice", null)
    }

    fun setVpnAppOfChoice(packageName: String?) {
        prefs.edit().putString("vpn_app_of_choice", packageName).apply()
        addLog("VPN App of Choice set to: $packageName")
    }

    fun hasSystemAlertWindowPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasUsageStatsPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getForegroundPackageName(ctx: Context): String? {
        val usageStatsManager = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val time = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(time - 10000, time) ?: return null
        val event = UsageEvents.Event()
        var lastForegroundApp: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForegroundApp = event.packageName
            }
        }
        return lastForegroundApp
    }
}