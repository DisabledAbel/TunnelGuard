package com.tunnelguard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig

    private lateinit var layoutPrefBoot: LinearLayout
    private lateinit var cbPrefBoot: CheckBox

    private lateinit var layoutPrefSimulation: LinearLayout
    private lateinit var cbPrefSimulation: CheckBox

    private lateinit var layoutPrefMonitor: LinearLayout
    private lateinit var cbPrefMonitor: CheckBox

    private lateinit var layoutPrefForceUpdates: LinearLayout
    private lateinit var cbPrefForceUpdates: CheckBox

    private lateinit var layoutPrefVpnChoice: LinearLayout
    private lateinit var tvPrefVpnChoiceValue: TextView

    private lateinit var btnSimulateConnected: Button
    private lateinit var btnSimulateDisconnected: Button
    private lateinit var btnClearLogs: Button
    private lateinit var rvDebugLogs: RecyclerView

    private lateinit var btnCheckUpdates: Button
    private lateinit var tvAboutVersion: TextView

    private lateinit var logsAdapter: LogsAdapter

    private var isUpdateChecking = false
    private var updatePendingRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        config = TunnelGuardConfig(this)

        // Bind preference rows and checkboxes
        layoutPrefBoot = findViewById(R.id.layout_pref_boot)
        cbPrefBoot = findViewById(R.id.cb_pref_boot)

        layoutPrefSimulation = findViewById(R.id.layout_pref_simulation)
        cbPrefSimulation = findViewById(R.id.cb_pref_simulation)

        layoutPrefMonitor = findViewById(R.id.layout_pref_monitor)
        cbPrefMonitor = findViewById(R.id.cb_pref_monitor)

        layoutPrefForceUpdates = findViewById(R.id.layout_pref_force_updates)
        cbPrefForceUpdates = findViewById(R.id.cb_pref_force_updates)

        layoutPrefVpnChoice = findViewById(R.id.layout_pref_vpn_choice)
        tvPrefVpnChoiceValue = findViewById(R.id.tv_pref_vpn_choice_value)

        btnSimulateConnected = findViewById(R.id.btn_simulate_connected)
        btnSimulateDisconnected = findViewById(R.id.btn_simulate_disconnected)
        btnClearLogs = findViewById(R.id.btn_clear_logs)
        rvDebugLogs = findViewById(R.id.rv_debug_logs)

        btnCheckUpdates = findViewById(R.id.btn_check_updates)
        tvAboutVersion = findViewById(R.id.tv_about_version)

        // Initialize state
        cbPrefBoot.isChecked = config.isStartOnBootEnabled()
        cbPrefSimulation.isChecked = config.isSimulatedVpnEnabled()
        cbPrefMonitor.isChecked = config.isAppMonitorEnabled()
        cbPrefForceUpdates.isChecked = config.isForcedUpdatesEnabled()

        // Update version string with the current stored version name
        updateVersionDisplay()
        updateVpnAppOfChoiceDisplay()

        // Toggle behaviors
        layoutPrefBoot.setOnClickListener {
            val newChecked = !config.isStartOnBootEnabled()
            config.setStartOnBootEnabled(newChecked)
            cbPrefBoot.isChecked = newChecked
            config.addLog("Changed Start on Boot -> $newChecked")
        }

        layoutPrefSimulation.setOnClickListener {
            val newChecked = !config.isSimulatedVpnEnabled()
            config.setSimulatedVpnEnabled(newChecked)
            cbPrefSimulation.isChecked = newChecked
            config.addLog("Changed Simulation Mode -> $newChecked")

            // Notify VpnService of connectivity check methodology change
            triggerVpnServiceUpdate()
        }

        layoutPrefMonitor.setOnClickListener {
            if (config.hasUsageStatsPermission(this)) {
                val newChecked = !config.isAppMonitorEnabled()
                config.setAppMonitorEnabled(newChecked)
                cbPrefMonitor.isChecked = newChecked
                triggerVpnServiceUpdate()
            } else {
                UpdateManager(this, config).showPermissionRequiredDialog(
                    "Permission Required",
                    "To detect when a protected application is opened and display the security warning, TunnelGuard requires the 'Usage Access' permission.\n\nPlease enable TunnelGuard in the system settings screen that opens next.",
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                    "ACTION_USAGE_ACCESS_SETTINGS"
                )
            }
        }

        layoutPrefForceUpdates.setOnClickListener {
            val newChecked = !config.isForcedUpdatesEnabled()
            config.setForcedUpdatesEnabled(newChecked)
            cbPrefForceUpdates.isChecked = newChecked
            config.addLog("Changed Forced Updates -> $newChecked")
        }

        layoutPrefVpnChoice.setOnClickListener {
            showVpnAppOfChoiceDialog()
        }

        btnSimulateConnected.setOnClickListener {
            if (config.isSimulatedVpnEnabled()) {
                config.setVPNState(VPNState.PROTECTED)
                config.addLog("Simulating VPN state change to PROTECTED.")
                triggerVpnServiceUpdate()
            } else {
                config.addLog("Please enable Simulation Mode first before using simulated triggers.")
            }
        }

        btnSimulateDisconnected.setOnClickListener {
            if (config.isSimulatedVpnEnabled()) {
                config.setLastDisconnectReason("Simulation trigger")
                config.setVPNState(VPNState.DISCONNECTED)
                config.addLog("Simulating VPN state change to DISCONNECTED.")
                triggerVpnServiceUpdate()
            } else {
                config.addLog("Please enable Simulation Mode first before using simulated triggers.")
            }
        }

        // Setup Logs List
        rvDebugLogs.layoutManager = LinearLayoutManager(this)
        logsAdapter = LogsAdapter(this, config.getLogs())
        rvDebugLogs.adapter = logsAdapter

        btnClearLogs.setOnClickListener {
            config.clearLogs()
            logsAdapter.updateList(emptyList())
        }

        btnCheckUpdates.setOnClickListener {
            checkForUpdatesFlow()
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if usage stats permission was granted and update state
        if (config.hasUsageStatsPermission(this)) {
            cbPrefMonitor.isChecked = config.isAppMonitorEnabled()
        } else {
            cbPrefMonitor.isChecked = false
            if (config.isAppMonitorEnabled()) {
                config.setAppMonitorEnabled(false)
                triggerVpnServiceUpdate()
            }
        }
    }

    private fun updateVersionDisplay() {
        val currentVersion = config.getAppVersionName()
        tvAboutVersion.text = "Version: $currentVersion\nDeveloper: DisabledAbel (TunnelGuard Team)\nDesigned for Android TV / Google TV."
    }

    private fun updateVpnAppOfChoiceDisplay() {
        val vpnPkg = config.getVpnAppOfChoice()
        if (vpnPkg != null) {
            try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(vpnPkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                tvPrefVpnChoiceValue.text = "$label ($vpnPkg)"
            } catch (e: Exception) {
                config.addLog("Error looking up VPN app of choice display label: ${e.message}")
                tvPrefVpnChoiceValue.text = vpnPkg
            }
        } else {
            tvPrefVpnChoiceValue.text = "None (System Settings)"
        }
    }


    private fun showVpnAppOfChoiceDialog() {
        val pm = packageManager
        val standardIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherApps = pm.queryIntentActivities(standardIntent, 0)

        val tvIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        val tvLauncherApps = pm.queryIntentActivities(tvIntent, 0)

        val appsMap = mutableMapOf<String, String>()

        for (resolveInfo in launcherApps) {
            val pkg = resolveInfo.activityInfo.packageName
            if (pkg != packageName) {
                val label = resolveInfo.loadLabel(pm).toString()
                appsMap[pkg] = label
            }
        }
        for (resolveInfo in tvLauncherApps) {
            val pkg = resolveInfo.activityInfo.packageName
            if (pkg != packageName) {
                val label = resolveInfo.loadLabel(pm).toString()
                appsMap[pkg] = label
            }
        }

        val sortedList = appsMap.toList().sortedBy { it.second.lowercase() }

        val options = mutableListOf<String>()
        options.add("None (System Settings)")

        sortedList.forEach {
            options.add("${it.second} (${it.first})")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select VPN App of Choice")
            .setItems(options.toTypedArray()) { dialog, which ->
                if (which == 0) {
                    config.setVpnAppOfChoice(null)
                } else {
                    val selected = sortedList[which - 1]
                    config.setVpnAppOfChoice(selected.first)
                }
                updateVpnAppOfChoiceDisplay()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun checkForUpdatesFlow() {
        if (isUpdateChecking) {
            config.addLog("Update check already in progress.")
            return
        }

        // Check if the permission to install unknown apps is granted (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                config.addLog("Install unknown apps permission is NOT granted.")
                UpdateManager(this, config).showPermissionRequiredDialog(
                    "Permission Required",
                    "To automatically download and install updates, TunnelGuard requires the 'Install unknown apps' permission.\n\nPlease enable 'Allow from this source' on the next screen, then try checking for updates again.",
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    },
                    Intent(Settings.ACTION_SETTINGS),
                    "ACTION_MANAGE_UNKNOWN_APP_SOURCES"
                )
                return
            }
        }

        isUpdateChecking = true
        btnCheckUpdates.isEnabled = false

        val rawVersion = config.getAppVersionName()
        val currentVersion = VersionComparator.validateAndNormalizeVersion(rawVersion)
        config.addLog("Checking for updates... Current version: $currentVersion (raw: $rawVersion)")

        // Show a loading / checking updates dialog
        val loadingBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
        loadingBuilder.setTitle("Checking for Updates")
        loadingBuilder.setMessage("Connecting to GitHub to check for updates...")
        loadingBuilder.setCancelable(false)
        val loadingDialog = loadingBuilder.create()
        loadingDialog.show()

        UpdateChecker.instance.checkForLatestRelease(
            onSuccess = { cleanTagName, apkUrl ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loadingDialog.dismiss()
                    config.addLog("Latest release on GitHub: $cleanTagName")

                    if (VersionComparator.isNewerVersion(currentVersion, cleanTagName)) {
                        // New version is available! Ask the user to download.
                        UpdateManager(this, config).showUpdateAvailableDialog(cleanTagName, apkUrl, true)
                    } else {
                        // Up to date
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Up to Date")
                            .setMessage("TunnelGuard is already up to date!\n\nCurrent version: $currentVersion\nLatest version: $cleanTagName")
                            .setPositiveButton("OK") { d, _ -> d.dismiss() }
                            .show()
                    }
                    isUpdateChecking = false
                    btnCheckUpdates.isEnabled = true
                }
            },
            onFailure = { errorMessage ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loadingDialog.dismiss()
                    config.addLog("Update check failed: $errorMessage")
                    UpdateManager(this, config).showUpdateErrorDialog(errorMessage)
                    isUpdateChecking = false
                    btnCheckUpdates.isEnabled = true
                }
            }
        )
    }

    override fun onDestroy() {
        updatePendingRunnable?.let {
            btnCheckUpdates.removeCallbacks(it)
        }
        super.onDestroy()
    }

    private fun triggerVpnServiceUpdate() {
        if (TunnelGuardVpnService.isServiceRunning) {
            val serviceIntent = Intent(this, TunnelGuardVpnService::class.java).apply {
                action = TunnelGuardVpnService.ACTION_UPDATE
            }
            startService(serviceIntent)
        }
    }

    companion object {
        fun calculateNextVersion(currentVersion: String): String {
            return try {
                val parts = currentVersion.split(".")
                if (parts.size >= 3) {
                    val major = parts[0]
                    val minor = parts[1]
                    val patch = (parts[2].toIntOrNull() ?: 0) + 1
                    "$major.$minor.$patch"
                } else if (parts.size == 2) {
                    val major = parts[0]
                    val minor = parts[1]
                    "$major.$minor.1"
                } else {
                    "1.0.1"
                }
            } catch (e: Exception) {
                "1.0.1"
            }
        }
    }

    private class LogsAdapter(
        private val context: Context,
        private var items: List<String>
    ) : RecyclerView.Adapter<LogsAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val logText: TextView = v.findViewById(R.id.tv_log_item)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(context).inflate(R.layout.item_log, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.logText.text = items[position]
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newList: List<String>) {
            items = newList
            notifyDataSetChanged()
        }
    }
}
