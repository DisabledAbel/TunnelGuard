package com.tunnelguard.app

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
        private const val KEY_DISABLE_SUBTITLES = "disable_subtitles_default"

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
     * Consolidate VPN capability detection helper.
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

    private fun isOurOurVpn(addresses: List<android.net.LinkAddress>): Boolean {
        return addresses.any { it.address.hostAddress == TUNNEL_ADDRESS || it.address.hostAddress == "2001:db8::1" }
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
     * Preference: Disable Subtitles by Default.
     */
    fun isDisableSubtitlesEnabled(): Boolean {
        return prefs.getBoolean(KEY_DISABLE_SUBTITLES, false)
    }

    fun setDisableSubtitlesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DISABLE_SUBTITLES, enabled).apply()
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
     * Persistent VPN state (especially useful for mocking or when simulation is enabled).
     */
    fun getVPNState(): VPNState {
        val name = prefs.getString(KEY_VPN_STATUS, VPNState.DISCONNECTED.name)
        return try {
            VPNState.valueOf(name ?: VPNState.DISCONNECTED.name)
        } catch (e: Exception) {
            VPNState.DISCONNECTED
        }
    }

    fun setVPNState(state: VPNState) {
        prefs.edit().putString(KEY_VPN_STATUS, state.name).apply()
    }

    /**
     * Protection Master Switch: Enabled (ACTIVE or BLOCKING) vs Disabled (INACTIVE).
     */
    fun isProtectionEnabled(): Boolean {
        return prefs.getBoolean(KEY_PROTECTION_ENABLED, false)
    }

    fun setProtectionEnabled(enabled: Boolean) {
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
    fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$timestamp] $message"
        val logs = getLogs().toMutableList()
        logs.add(0, formatted) // Keep newest first
        if (logs.size > 100) {
            logs.removeAt(logs.size - 1)
        }
        val arr = JSONArray()
        logs.forEach { arr.put(it) }
        prefs.edit().putString(KEY_LOGS, arr.toString()).apply()
    }

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
}