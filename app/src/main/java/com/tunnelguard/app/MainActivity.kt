package com.tunnelguard.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig
    private var connectivityManager: ConnectivityManager? = null
    private var mainNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var layoutMain: ConstraintLayout
    private lateinit var tvVpnStatus: TextView
    private lateinit var tvProtectionStatus: TextView
    private lateinit var tvIpv4Status: TextView
    private lateinit var tvIpv6Status: TextView
    private lateinit var tvDnsStatus: TextView
    private lateinit var tvActiveProfile: TextView
    private lateinit var tvProtectedCount: TextView

    private lateinit var btnToggleProtection: Button
    private lateinit var btnToggleEmergency: Button
    private lateinit var btnManageApps: Button
    private lateinit var btnManageProfiles: Button
    private lateinit var btnTestProtection: Button
    private lateinit var btnSettings: Button
    private lateinit var rvProtectedApps: RecyclerView

    private lateinit var adapter: HomeAppsAdapter

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI()
        }
    }

    companion object {
        private const val REQUEST_VPN_PREPARE = 2001
        var hasCheckedForUpdates = false
        var updateChecker: UpdateChecker = GitHubUpdateChecker()
    }

    private fun checkForUpdatesInBackground() {
        val rawVersion = config.getAppVersionName()
        val currentVersion = VersionComparator.validateAndNormalizeVersion(rawVersion)
        config.addLog("Main Update Check: Current version is $currentVersion (raw: $rawVersion)")

        updateChecker.checkForLatestRelease(
            currentVersion,
            onSuccess = { cleanTagName, apkUrl ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    config.addLog("Main Update Check: Latest release is $cleanTagName")
                    if (VersionComparator.isNewerVersion(currentVersion, cleanTagName)) {
                        showUpdateAvailableDialog(cleanTagName, apkUrl)
                    }
                }
            },
            onFailure = { errorMessage ->
                config.addLog("Main Update Check: Failed: $errorMessage")
            }
        )
    }

    private fun showUpdateAvailableDialog(latestVersion: String, apkUrl: String?) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New Update Available")
            .setMessage("A new version of TunnelGuard (v$latestVersion) is available.\n\nWould you like to download and install this update now?")
            .setPositiveButton("Download") { dialog, _ ->
                dialog.dismiss()
                if (apkUrl != null) {
                    UpdateManager(this, config).checkPermissionAndDownloadUpdate(latestVersion, apkUrl)
                } else {
                    config.addLog("Main Update Check Error: No APK file found in GitHub release assets.")
                    UpdateManager(this, config).showUpdateErrorDialog("No APK asset found in the latest GitHub release.")
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = TunnelGuardConfig(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        layoutMain = findViewById(R.id.main_layout)
        tvVpnStatus = findViewById(R.id.tv_vpn_status)
        tvProtectionStatus = findViewById(R.id.tv_protection_status)
        tvIpv4Status = findViewById(R.id.tv_ipv4_status)
        tvIpv6Status = findViewById(R.id.tv_ipv6_status)
        tvDnsStatus = findViewById(R.id.tv_dns_status)
        tvActiveProfile = findViewById(R.id.tv_active_profile)
        tvProtectedCount = findViewById(R.id.tv_protected_count)

        btnToggleProtection = findViewById(R.id.btn_toggle_protection)
        btnToggleEmergency = findViewById(R.id.btn_toggle_emergency)
        btnManageApps = findViewById(R.id.btn_manage_apps)
        btnManageProfiles = findViewById(R.id.btn_manage_profiles)
        btnTestProtection = findViewById(R.id.btn_test_protection)
        btnSettings = findViewById(R.id.btn_settings)
        rvProtectedApps = findViewById(R.id.rv_home_protected_apps)

        btnToggleProtection.requestFocus()

        btnToggleProtection.setOnClickListener {
            toggleProtection()
        }

        btnToggleEmergency.setOnClickListener {
            toggleEmergencyLock()
        }

        btnManageApps.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        btnManageProfiles.setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }

        btnTestProtection.setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Setup Apps Status List
        rvProtectedApps.layoutManager = LinearLayoutManager(this)
        adapter = HomeAppsAdapter(this, emptyList())
        rvProtectedApps.adapter = adapter

        updateUI()

        if (!hasCheckedForUpdates) {
            hasCheckedForUpdates = true
            checkForUpdatesInBackground()
        }
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                receiver,
                IntentFilter("com.tunnelguard.app.STATE_CHANGED"),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                receiver,
                IntentFilter("com.tunnelguard.app.STATE_CHANGED")
            )
        }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            mainNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread { updateUI() }
                }
                override fun onLost(network: Network) {
                    runOnUiThread { updateUI() }
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    runOnUiThread { updateUI() }
                }
            }
            connectivityManager?.registerNetworkCallback(request, mainNetworkCallback!!)
        } catch (e: Exception) {
            config.addLog("Error registering activity network callback: ${e.message}")
        }

        updateUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
        mainNetworkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignored
            }
            mainNetworkCallback = null
        }
    }

    private fun toggleProtection() {
        val isEnabled = config.isProtectionEnabled()
        if (isEnabled) {
            config.setProtectionEnabled(false)
            config.addLog("User stopped protection.")
            stopVpnService()
            updateUI()
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                config.addLog("VpnService.prepare requires user approval. Launching permission request.")
                startActivityForResult(intent, REQUEST_VPN_PREPARE)
            } else {
                onActivityResult(REQUEST_VPN_PREPARE, Activity.RESULT_OK, null)
            }
        }
    }

    private fun toggleEmergencyLock() {
        val isLocked = config.isEmergencyLockEnabled()
        val nextLockState = !isLocked
        config.setEmergencyLockEnabled(nextLockState)

        if (nextLockState) {
            Toast.makeText(this, "Emergency Lock ENGAGED. All protected traffic BLOCKED.", Toast.LENGTH_LONG).show()
            // Make sure service runs to hold the block
            if (!TunnelGuardVpnService.isServiceRunning) {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startVpnService()
                }
            } else {
                triggerVpnServiceUpdate()
            }
        } else {
            Toast.makeText(this, "Emergency Lock DISENGAGED.", Toast.LENGTH_SHORT).show()
            if (TunnelGuardVpnService.isServiceRunning) {
                triggerVpnServiceUpdate()
            }
        }
        updateUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PREPARE && resultCode == Activity.RESULT_OK) {
            config.setProtectionEnabled(true)
            config.addLog("User enabled TunnelGuard protection.")
            startVpnService()
            updateUI()
        } else if (requestCode == REQUEST_VPN_PREPARE) {
            config.addLog("VPN permission request rejected by user.")
            Toast.makeText(this, "VPN permission is required for TunnelGuard protection.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, TunnelGuardVpnService::class.java).apply {
            action = TunnelGuardVpnService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, TunnelGuardVpnService::class.java).apply {
            action = TunnelGuardVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun triggerVpnServiceUpdate() {
        val intent = Intent(this, TunnelGuardVpnService::class.java).apply {
            action = TunnelGuardVpnService.ACTION_UPDATE
        }
        startService(intent)
    }

    private fun updateUI() {
        if (!TunnelGuardVpnService.isServiceRunning) {
            val isUpstreamVpnConnected = config.detectRealVpnCapabilities(connectivityManager)
            val currentVpnState = if (isUpstreamVpnConnected) {
                VPNState.CONNECTED
            } else {
                VPNState.DISCONNECTED
            }
            config.setVPNState(currentVpnState)
        }

        val vpnState = config.getVPNState()
        val protectionState = config.getProtectionState()
        val isLocked = config.isEmergencyLockEnabled()
        val protectedApps = config.getProtectedApps().toList()

        // 1. Update VPN status display
        tvVpnStatus.text = "● ${vpnState.name}"
        when (vpnState) {
            VPNState.CONNECTED, VPNState.PROTECTED -> {
                tvVpnStatus.setTextColor(resources.getColor(R.color.status_connected))
            }
            VPNState.CONNECTING -> {
                tvVpnStatus.setTextColor(resources.getColor(R.color.status_connecting))
            }
            VPNState.DISCONNECTED, VPNState.BLOCKED, VPNState.ERROR -> {
                tvVpnStatus.setTextColor(resources.getColor(R.color.status_disconnected))
            }
        }

        // 2. Update Protection status display
        if (isLocked) {
            tvProtectionStatus.text = "● EMERGENCY LOCK ACTIVE"
            tvProtectionStatus.setTextColor(resources.getColor(R.color.status_disconnected))
            btnToggleProtection.text = "Start Protection"
            btnToggleEmergency.text = "Unlock Network"
        } else {
            btnToggleEmergency.text = "Emergency Lock"
            when (protectionState) {
                ProtectionState.ACTIVE -> {
                    tvProtectionStatus.text = "● PROTECTION ACTIVE"
                    tvProtectionStatus.setTextColor(resources.getColor(R.color.status_active))
                    btnToggleProtection.text = "Stop Protection"
                }
                ProtectionState.BLOCKING -> {
                    tvProtectionStatus.text = "● PROTECTION BLOCKED"
                    tvProtectionStatus.setTextColor(resources.getColor(R.color.status_blocking))
                    btnToggleProtection.text = "Stop Protection"
                }
                ProtectionState.INACTIVE -> {
                    tvProtectionStatus.text = "● PROTECTION INACTIVE"
                    tvProtectionStatus.setTextColor(resources.getColor(R.color.status_inactive))
                    btnToggleProtection.text = "Start Protection"
                }
            }
        }

        // 3. Update IPv4 & IPv6 Protection Status
        if (isLocked) {
            tvIpv4Status.text = "LOCKED (BLOCKED)"
            tvIpv4Status.setTextColor(resources.getColor(R.color.status_disconnected))
            tvIpv6Status.text = "LOCKED (BLOCKED)"
            tvIpv6Status.setTextColor(resources.getColor(R.color.status_disconnected))
        } else if (config.isProtectionEnabled()) {
            if (vpnState == VPNState.CONNECTED || vpnState == VPNState.PROTECTED) {
                tvIpv4Status.text = "Protected (VPN Routing)"
                tvIpv4Status.setTextColor(resources.getColor(R.color.status_active))
                tvIpv6Status.text = "Protected (VPN Routing)"
                tvIpv6Status.setTextColor(resources.getColor(R.color.status_active))
            } else {
                tvIpv4Status.text = "Blocked (Fail-Closed)"
                tvIpv4Status.setTextColor(resources.getColor(R.color.status_blocking))
                tvIpv6Status.text = "Blocked (Fail-Closed)"
                tvIpv6Status.setTextColor(resources.getColor(R.color.status_blocking))
            }
        } else {
            tvIpv4Status.text = "Unprotected"
            tvIpv4Status.setTextColor(resources.getColor(R.color.text_secondary))
            tvIpv6Status.text = "Unprotected"
            tvIpv6Status.setTextColor(resources.getColor(R.color.text_secondary))
        }

        // 4. Update DNS Protection Status
        val dnsStatus = config.detectDnsStatus(connectivityManager, TunnelGuardVpnService.isServiceRunning)
        when (dnsStatus) {
            DNSStatus.PROTECTED -> {
                tvDnsStatus.text = "Protected"
                tvDnsStatus.setTextColor(resources.getColor(R.color.status_active))
            }
            DNSStatus.WARNING -> {
                tvDnsStatus.text = "Warning (Leaks Possible)"
                tvDnsStatus.setTextColor(resources.getColor(R.color.status_disconnected))
            }
            DNSStatus.UNKNOWN -> {
                tvDnsStatus.text = "Unknown"
                tvDnsStatus.setTextColor(resources.getColor(R.color.text_secondary))
            }
        }

        // 5. Update Active Profile Status
        val selectedProfileId = config.getSelectedProfileId()
        val profile = config.getProfiles().find { it.id == selectedProfileId }
        tvActiveProfile.text = profile?.name ?: "Streaming"

        // Update the adapter list with only installed applications to avoid cluttering with "UNKNOWN" entries
        val appInfos = mutableListOf<AppStatusInfo>()
        val pm = packageManager
        for (pkg in protectedApps) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                val statusText = if (isLocked) {
                    "EMERGENCY BLOCKED"
                } else if (protectionState == ProtectionState.BLOCKING) {
                    "INTERNET BLOCKED"
                } else if (protectionState == ProtectionState.ACTIVE) {
                    "PROTECTED"
                } else {
                    "UNPROTECTED"
                }
                appInfos.add(AppStatusInfo(label, pkg, icon, statusText))
            } catch (e: Exception) {
                // Skip displaying uninstalled/unavailable apps on the home screen
            }
        }
        adapter.updateList(appInfos)

        // 6. Count actually installed protected apps
        tvProtectedCount.text = "${appInfos.size}"
    }

    data class AppStatusInfo(
        val name: String,
        val packageName: String,
        val icon: Drawable,
        val statusText: String
    )

    private class HomeAppsAdapter(
        private val context: Context,
        private var items: List<AppStatusInfo>
    ) : RecyclerView.Adapter<HomeAppsAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.iv_home_app_icon)
            val name: TextView = v.findViewById(R.id.tv_home_app_name)
            val pkg: TextView = v.findViewById(R.id.tv_home_app_package)
            val status: TextView = v.findViewById(R.id.tv_home_app_status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(context).inflate(R.layout.item_home_app_status, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.icon.setImageDrawable(item.icon)
            holder.name.text = item.name
            holder.pkg.text = item.packageName
            holder.status.text = item.statusText

            if (item.statusText == "INTERNET BLOCKED" || item.statusText == "EMERGENCY BLOCKED") {
                holder.status.setTextColor(context.resources.getColor(R.color.status_blocking))
            } else if (item.statusText == "PROTECTED") {
                holder.status.setTextColor(context.resources.getColor(R.color.status_active))
            } else {
                holder.status.setTextColor(context.resources.getColor(R.color.status_inactive))
            }
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newList: List<AppStatusInfo>) {
            items = newList
            notifyDataSetChanged()
        }
    }
}