package com.tunnelguard.app

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray

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

        const val TUNNEL_ADDRESS = "172.31.255.1"
        const val TUNNEL_PREFIX_LENGTH = 24
    }

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
                    val isOurOwnVpn = addresses.any { it.address.hostAddress == TUNNEL_ADDRESS }

                    if (isOurOwnVpn) {
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
     * Get the set of package names of selected apps to protect.
     */
    fun getProtectedApps(): Set<String> {
        val jsonStr = prefs.getString(KEY_PROTECTED_APPS, "[]") ?: "[]"
        val set = mutableSetOf<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return set
    }

    /**
     * Save the set of package names of selected apps to protect.
     */
    fun setProtectedApps(apps: Set<String>) {
        val arr = JSONArray()
        apps.forEach { arr.put(it) }
        prefs.edit().putString(KEY_PROTECTED_APPS, arr.toString()).apply()
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
