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
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tunnelguard.app.update.ForceUpdateActivity
import com.tunnelguard.app.update.UpdateCheckResult
import com.tunnelguard.app.update.UpdateRepository
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private lateinit var tvUptimeStatus: TextView
    private lateinit var tvDisconnectReason: TextView
    private lateinit var tvTrafficStatus: TextView
    private lateinit var tvLastTransition: TextView

    private lateinit var btnToggleProtection: Button
    private lateinit var btnToggleEmergency: Button
    private lateinit var btnManageApps: Button
    private lateinit var btnManageProfiles: Button
    private lateinit var btnTestProtection: Button
    private lateinit var btnLogsDashboard: Button
    private lateinit var btnSettings: Button
    private lateinit var rvProtectedApps: RecyclerView

    private lateinit var adapter: HomeAppsAdapter

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI()
        }
    }

    private var hasCheckedForUpdates = false

    companion object {
        private const val REQUEST_VPN_PREPARE = 2001
        private const val REQUEST_POST_NOTIFICATIONS = 2002
    }

    private fun runMandatoryUpdateCheck() {
        val repo = UpdateRepository.getInstance(this)
        val rawVersion = config.getAppVersionName()
        val currentVersion = VersionComparator.validateAndNormalizeVersion(rawVersion)

        // 1. Immediately block and route if a newer version is already known/detected
        val cachedVer = repo.getCachedLatestVersion()
        if (repo.isUpdateDetectedInSession() && cachedVer != null && VersionComparator.isNewerVersion(currentVersion, cachedVer)) {
            val intent = Intent(this, ForceUpdateActivity::class.java).apply {
                putExtra(ForceUpdateActivity.EXTRA_LATEST_VERSION, cachedVer)
                putExtra(ForceUpdateActivity.EXTRA_APK_URL, repo.getCachedApkUrl())
                putExtra(ForceUpdateActivity.EXTRA_RELEASE_NOTES, repo.getCachedReleaseNotes())
                putExtra(ForceUpdateActivity.EXTRA_RELEASE_NAME, repo.getCachedReleaseName())
                putExtra(ForceUpdateActivity.EXTRA_RELEASE_URL, repo.getCachedReleaseUrl())
                putExtra(ForceUpdateActivity.EXTRA_PUBLISHED_AT, repo.getCachedPublishedAt())
            }
            startActivity(intent)
            finish()
            return
        }

        // 2. Show non-dismissible checking dialog
        val checkingDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Checking for Updates")
            .setMessage("Checking for mandatory updates...")
            .setCancelable(false)
            .create()

        checkingDialog.show()

        lifecycleScope.launch {
            val result = repo.checkForUpdate(currentVersion)
            if (!isFinishing && !isDestroyed) {
                checkingDialog.dismiss()
                when (result) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        val intent = Intent(this@MainActivity, ForceUpdateActivity::class.java).apply {
                            putExtra(ForceUpdateActivity.EXTRA_LATEST_VERSION, result.latestVersion)
                            putExtra(ForceUpdateActivity.EXTRA_APK_URL, result.apkUrl)
                            putExtra(ForceUpdateActivity.EXTRA_RELEASE_NOTES, result.releaseNotes)
                            putExtra(ForceUpdateActivity.EXTRA_RELEASE_NAME, result.releaseName)
                            putExtra(ForceUpdateActivity.EXTRA_RELEASE_URL, result.releaseUrl)
                            putExtra(ForceUpdateActivity.EXTRA_PUBLISHED_AT, result.publishedAt)
                        }
                        startActivity(intent)
                        finish()
                    }
                    is UpdateCheckResult.Failure -> {
                        config.addLog("Mandatory Update Check Failed: ${result.errorMessage}")
                        Toast.makeText(this@MainActivity, "Update checking is currently unavailable. Continuing offline.", Toast.LENGTH_LONG).show()
                        // If offline but a session-level update was previously detected, force the update
                        if (repo.isUpdateDetectedInSession() && cachedVer != null && VersionComparator.isNewerVersion(currentVersion, cachedVer)) {
                            val intent = Intent(this@MainActivity, ForceUpdateActivity::class.java).apply {
                                putExtra(ForceUpdateActivity.EXTRA_LATEST_VERSION, cachedVer)
                                putExtra(ForceUpdateActivity.EXTRA_APK_URL, repo.getCachedApkUrl())
                                putExtra(ForceUpdateActivity.EXTRA_RELEASE_NOTES, repo.getCachedReleaseNotes())
                                putExtra(ForceUpdateActivity.EXTRA_RELEASE_NAME, repo.getCachedReleaseName())
                                putExtra(ForceUpdateActivity.EXTRA_RELEASE_URL, repo.getCachedReleaseUrl())
                                putExtra(ForceUpdateActivity.EXTRA_PUBLISHED_AT, repo.getCachedPublishedAt())
                            }
                            startActivity(intent)
                            finish()
                        }
                    }
                    is UpdateCheckResult.NoUpdate -> {
                        // Do nothing, allow normal app launch
                    }
                    is UpdateCheckResult.NotModified -> {
                        // Do nothing, allow normal app launch
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        config = TunnelGuardConfig(this)
        if (!config.isOnboardingCompleted()) {
            val intent = Intent(this, OnboardingActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        layoutMain = findViewById(R.id.main_layout)
        tvVpnStatus = findViewById(R.id.tv_vpn_status)
        tvProtectionStatus = findViewById(R.id.tv_protection_status)
        tvIpv4Status = findViewById(R.id.tv_ipv4_status)
        tvIpv6Status = findViewById(R.id.tv_ipv6_status)
        tvDnsStatus = findViewById(R.id.tv_dns_status)
        tvActiveProfile = findViewById(R.id.tv_active_profile)
        tvProtectedCount = findViewById(R.id.tv_protected_count)
        tvTrafficStatus = findViewById(R.id.tv_traffic_status)
        tvLastTransition = findViewById(R.id.tv_last_transition)

        btnToggleProtection = findViewById(R.id.btn_toggle_protection)
        btnToggleEmergency = findViewById(R.id.btn_toggle_emergency)
        btnManageApps = findViewById(R.id.btn_manage_apps)
        btnManageProfiles = findViewById(R.id.btn_manage_profiles)
        btnTestProtection = findViewById(R.id.btn_test_protection)
        btnLogsDashboard = findViewById(R.id.btn_logs_dashboard)
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

        btnLogsDashboard.setOnClickListener {
            startActivity(Intent(this, LogsDashboardActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Setup Apps Status List
        rvProtectedApps.layoutManager = LinearLayoutManager(this)
        adapter = HomeAppsAdapter(this, emptyList())
        rvProtectedApps.adapter = adapter

        tvUptimeStatus = findViewById(R.id.tv_uptime_status)
        tvDisconnectReason = findViewById(R.id.tv_disconnect_reason)

        updateUI()

        if (!hasCheckedForUpdates) {
            hasCheckedForUpdates = true
            if (config.isForcedUpdatesEnabled()) {
                runMandatoryUpdateCheck()
            }
        }
    }

    private var uptimeJob: kotlinx.coroutines.Job? = null

    private fun startUptimeUpdates() {
        uptimeJob?.cancel()
        uptimeJob = lifecycleScope.launch {
            while (isActive) {
                val uptimeMillis = config.getConnectionUptimeMillis()
                tvUptimeStatus.text = formatUptime(uptimeMillis)
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun stopUptimeUpdates() {
        uptimeJob?.cancel()
        uptimeJob = null
    }

    private fun formatUptime(millis: Long): String {
        if (millis <= 0) return "00:00:00"
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * Resumes the activity's monitoring, restores VPN service protection when required,
     * refreshes the interface, and starts connection uptime updates.
     */
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
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

        // Auto-start TunnelGuardVpnService if protection or emergency lock is enabled but service is not running,
        // and we already have VPN preparation permission, and we're not currently starting it
        if ((config.isProtectionEnabled() || config.isEmergencyLockEnabled()) && !TunnelGuardVpnService.isServiceRunning && !TunnelGuardVpnService.isServiceStarting) {
            if (VpnService.prepare(this) == null) {
                config.addLog("Protection or Emergency Lock is enabled but service is not running. Auto-starting TunnelGuardVpnService.")
                startVpnService()
            }
        }

        updateUI()
        startUptimeUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopUptimeUpdates()
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

    /**
     * Toggles emergency lock and updates the VPN service to enforce the new state.
     */
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

    /**
     * Handles the result of the VPN permission request.
     *
     * @param requestCode The identifier of the permission request.
     * @param resultCode The result of the permission request.
     * @param data Additional result data, if provided.
     */
    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                config.addLog("Notification permission granted via tamper resolution.")
                updateUI()
            } else {
                config.addLog("Notification permission denied via tamper resolution.")
            }
        }
    }

    /**
     * Starts the VPN service if it is not already starting.
     */
    private fun startVpnService() {
        if (TunnelGuardVpnService.isServiceStarting) {
            return
        }
        TunnelGuardVpnService.isServiceStarting = true
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

    /**
     * Requests the VPN service to apply the current protection settings.
     */
    private fun triggerVpnServiceUpdate() {
        val intent = Intent(this, TunnelGuardVpnService::class.java).apply {
            action = TunnelGuardVpnService.ACTION_UPDATE
        }
        startService(intent)
    }

    /**
     * Synchronizes the home screen with the current VPN, protection, DNS, profile, application, uptime, and tamper-warning states.
     */
    private fun updateUI() {
        if (!TunnelGuardVpnService.isServiceRunning && !config.isSimulatedVpnEnabled()) {
            val isUpstreamVpnConnected = config.detectRealVpnCapabilities(connectivityManager)
            val currentVpnState = if (isUpstreamVpnConnected) {
                VPNState.PROTECTED
            } else {
                VPNState.DISCONNECTED
            }
            config.setVPNState(currentVpnState)
        }

        val vpnState = config.getVPNState()
        val isLocked = config.isEmergencyLockEnabled()
        val protectedApps = config.getProtectedApps().toList()

        // Get single source of truth deterministic state
        val securityState = SecurityStateMachine.getSecurityState(
            this,
            config,
            TunnelGuardVpnService.isServiceRunning,
            TunnelGuardVpnService.isServiceStarting,
            TunnelGuardVpnService.isTunnelEstablished,
            connectivityManager
        )

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

        // 2. Update Protection status display using the SecurityState
        tvProtectionStatus.text = "● STATUS: ${securityState.name}"
        when (securityState) {
            SecurityState.PROTECTED -> {
                tvProtectionStatus.setTextColor(resources.getColor(R.color.status_active))
                btnToggleProtection.text = "Stop Protection"
            }
            SecurityState.BLOCKING -> {
                tvProtectionStatus.setTextColor(resources.getColor(R.color.status_blocking))
                btnToggleProtection.text = "Stop Protection"
            }
            SecurityState.INACTIVE -> {
                tvProtectionStatus.setTextColor(resources.getColor(R.color.status_inactive))
                btnToggleProtection.text = "Start Protection"
            }
            SecurityState.CONNECTING -> {
                tvProtectionStatus.setTextColor(resources.getColor(R.color.status_connecting))
                btnToggleProtection.text = "Stop Protection"
            }
            SecurityState.ERROR -> {
                tvProtectionStatus.setTextColor(resources.getColor(R.color.status_disconnected))
                btnToggleProtection.text = "Start Protection"
            }
        }

        if (isLocked) {
            btnToggleEmergency.text = "Unlock Network"
        } else {
            btnToggleEmergency.text = "Emergency Lock"
        }

        // 3. Update IPv4 & IPv6 Protection Status and Allowed/Blocked Traffic
        if (securityState == SecurityState.PROTECTED) {
            tvIpv4Status.text = "Protected (VPN Routing)"
            tvIpv4Status.setTextColor(resources.getColor(R.color.status_active))
            tvIpv6Status.text = "Protected (VPN Routing)"
            tvIpv6Status.setTextColor(resources.getColor(R.color.status_active))

            tvTrafficStatus.text = "ALLOWED (Secured)"
            tvTrafficStatus.setTextColor(resources.getColor(R.color.status_active))
        } else if (securityState == SecurityState.BLOCKING) {
            tvIpv4Status.text = "Blocked (Fail-Closed)"
            tvIpv4Status.setTextColor(resources.getColor(R.color.status_blocking))
            if (config.isIpv6ProtectionActive()) {
                tvIpv6Status.text = "Blocked (Fail-Closed)"
                tvIpv6Status.setTextColor(resources.getColor(R.color.status_blocking))
            } else {
                tvIpv6Status.text = "Unprotected (IPv6 Unsupported)"
                tvIpv6Status.setTextColor(resources.getColor(R.color.status_disconnected))
            }

            tvTrafficStatus.text = "BLOCKED"
            tvTrafficStatus.setTextColor(resources.getColor(R.color.status_blocking))
        } else if (securityState == SecurityState.CONNECTING) {
            tvIpv4Status.text = "Establishing..."
            tvIpv4Status.setTextColor(resources.getColor(R.color.status_connecting))
            tvIpv6Status.text = "Establishing..."
            tvIpv6Status.setTextColor(resources.getColor(R.color.status_connecting))

            tvTrafficStatus.text = "BLOCKED (Safe-Mode)"
            tvTrafficStatus.setTextColor(resources.getColor(R.color.status_blocking))
        } else if (securityState == SecurityState.ERROR) {
            tvIpv4Status.text = "Error"
            tvIpv4Status.setTextColor(resources.getColor(R.color.status_disconnected))
            tvIpv6Status.text = "Error"
            tvIpv6Status.setTextColor(resources.getColor(R.color.status_disconnected))

            tvTrafficStatus.text = "BLOCKED (Fail-Closed)"
            tvTrafficStatus.setTextColor(resources.getColor(R.color.status_blocking))
        } else {
            tvIpv4Status.text = "Unprotected"
            tvIpv4Status.setTextColor(resources.getColor(R.color.text_secondary))
            tvIpv6Status.text = "Unprotected"
            tvIpv6Status.setTextColor(resources.getColor(R.color.text_secondary))

            tvTrafficStatus.text = "ALLOWED (Unsecured)"
            tvTrafficStatus.setTextColor(resources.getColor(R.color.status_connecting))
        }

        // Format Last State Transition
        val lastTransition = config.getLastStateTransitionTime()
        if (lastTransition == 0L) {
            tvLastTransition.text = "Never"
        } else {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            tvLastTransition.text = format.format(java.util.Date(lastTransition))
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
                } else if (securityState == SecurityState.BLOCKING) {
                    "INTERNET BLOCKED"
                } else if (securityState == SecurityState.PROTECTED) {
                    "PROTECTED"
                } else if (securityState == SecurityState.CONNECTING) {
                    "CONNECTING"
                } else if (securityState == SecurityState.ERROR) {
                    "ERROR (BLOCKED)"
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

        // 7. Update Connection Uptime and Last Disconnect Reason
        val uptimeMillis = config.getConnectionUptimeMillis()
        tvUptimeStatus.text = formatUptime(uptimeMillis)
        tvDisconnectReason.text = config.getLastDisconnectReason()

        // 8. Handle Tamper warning banner
        val tamperInfo = checkTamperStatus()
        val layoutTamperWarning = findViewById<LinearLayout>(R.id.layout_tamper_warning)
        val tvTamperMessage = findViewById<TextView>(R.id.tv_tamper_message)

        if (tamperInfo.first) {
            layoutTamperWarning.visibility = View.VISIBLE
            tvTamperMessage.text = "${tamperInfo.second} Click to resolve."
            layoutTamperWarning.setOnClickListener {
                resolveTamper()
            }
        } else {
            layoutTamperWarning.visibility = View.GONE
        }
    }

    private fun checkTamperStatus(): Pair<Boolean, String> {
        val messages = mutableListOf<String>()
        var isTampered = false

        // 1. VPN permission check when protection is active
        if (config.isProtectionEnabled()) {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                isTampered = true
                messages.add("VPN permission has been revoked.")
            }
        }

        // 2. Usage access permission check when monitoring is active
        if (config.isAppMonitorEnabled()) {
            if (!config.hasUsageStatsPermission(this)) {
                isTampered = true
                messages.add("Usage Access permission is missing.")
            }
        }

        // 3. Draw over other apps permission check when monitoring is active
        if (config.isAppMonitorEnabled()) {
            if (!config.hasSystemAlertWindowPermission()) {
                isTampered = true
                messages.add("Display Over Other Apps permission is missing.")
            }
        }

        // 4. Notification permission check when monitoring is active
        if (config.isAppMonitorEnabled()) {
            if (!config.hasNotificationPermission()) {
                isTampered = true
                messages.add("Notification permission is missing.")
            }
        }

        return Pair(isTampered, messages.joinToString(" "))
    }

    private fun resolveTamper() {
        if (config.isProtectionEnabled() && VpnService.prepare(this) != null) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, REQUEST_VPN_PREPARE)
                return
            }
        }
        if (config.isAppMonitorEnabled() && !config.hasUsageStatsPermission(this)) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (ex: Exception) {
                    Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (config.isAppMonitorEnabled() && !config.hasSystemAlertWindowPermission()) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (ex: Exception) {
                    Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (config.isAppMonitorEnabled() && !config.hasNotificationPermission()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_POST_NOTIFICATIONS
                )
            }
        }
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