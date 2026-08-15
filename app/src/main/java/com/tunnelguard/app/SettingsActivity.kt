package com.tunnelguard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tunnelguard.app.update.UpdateCheckResult
import com.tunnelguard.app.update.UpdateRepository
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter

class SettingsActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig

    private lateinit var layoutPrefProtection: LinearLayout
    private lateinit var cbPrefProtection: CheckBox
    private lateinit var btnManageApps: Button
    private lateinit var layoutPrefBoot: LinearLayout
    private lateinit var cbPrefBoot: CheckBox
    private lateinit var layoutPrefMonitor: LinearLayout
    private lateinit var cbPrefMonitor: CheckBox
    private lateinit var layoutPrefVpnChoice: LinearLayout
    private lateinit var tvPrefVpnChoiceValue: TextView

    private lateinit var btnLaunchDiagnostics: Button
    private lateinit var btnExportLogs: Button

    private lateinit var layoutPrefSimulation: LinearLayout
    private lateinit var cbPrefSimulation: CheckBox
    private lateinit var btnImportConfig: Button
    private lateinit var btnExportConfig: Button

    private lateinit var btnSimulateConnected: Button
    private lateinit var btnSimulateDisconnected: Button
    private lateinit var layoutSimStateControls: LinearLayout

    private lateinit var btnCheckUpdates: Button
    private lateinit var btnShowOnboarding: Button
    private lateinit var btnShowLicense: Button
    private lateinit var btnBack: Button

    private lateinit var tvAboutVersion: TextView
    private lateinit var tvIpv6Info: TextView

    private var isUpdateChecking = false
    private var loadingDialog: AlertDialog? = null

    companion object {
        private const val REQUEST_VPN_PREPARE = 2001
        private const val REQUEST_POST_NOTIFICATIONS = 2002

        fun calculateNextVersion(currentVersion: String): String {
            val parts = currentVersion.split(".")
            return if (parts.size >= 3) {
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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        config = TunnelGuardConfig(this)

        // Bind layout views
        layoutPrefProtection = findViewById(R.id.layout_pref_protection)
        cbPrefProtection = findViewById(R.id.cb_pref_protection)
        btnManageApps = findViewById(R.id.btn_settings_manage_apps)
        layoutPrefBoot = findViewById(R.id.layout_pref_boot)
        cbPrefBoot = findViewById(R.id.cb_pref_boot)
        layoutPrefMonitor = findViewById(R.id.layout_pref_monitor)
        cbPrefMonitor = findViewById(R.id.cb_pref_monitor)
        layoutPrefVpnChoice = findViewById(R.id.layout_pref_vpn_choice)
        tvPrefVpnChoiceValue = findViewById(R.id.tv_pref_vpn_choice_value)

        btnLaunchDiagnostics = findViewById(R.id.btn_launch_diagnostics)
        btnExportLogs = findViewById(R.id.btn_settings_export_logs)

        layoutPrefSimulation = findViewById(R.id.layout_pref_simulation)
        cbPrefSimulation = findViewById(R.id.cb_pref_simulation)
        btnImportConfig = findViewById(R.id.btn_import_config)
        btnExportConfig = findViewById(R.id.btn_export_config)

        btnSimulateConnected = findViewById(R.id.btn_simulate_connected)
        btnSimulateDisconnected = findViewById(R.id.btn_simulate_disconnected)
        layoutSimStateControls = findViewById(R.id.layout_sim_state_controls)

        btnCheckUpdates = findViewById(R.id.btn_check_updates)
        btnShowOnboarding = findViewById(R.id.btn_show_onboarding)
        btnShowLicense = findViewById(R.id.btn_show_license)
        btnBack = findViewById(R.id.btn_settings_back)

        tvAboutVersion = findViewById(R.id.tv_about_version)
        tvIpv6Info = findViewById(R.id.tv_settings_ipv6_info)

        // Populate initial check states
        cbPrefProtection.isChecked = config.isProtectionEnabled()
        cbPrefBoot.isChecked = config.isStartOnBootEnabled()
        cbPrefSimulation.isChecked = config.isSimulatedVpnEnabled()
        cbPrefMonitor.isChecked = config.isAppMonitorEnabled()

        updateRowAccessibilityDescription(layoutPrefProtection, "Enable Protection", config.isProtectionEnabled())
        updateRowAccessibilityDescription(layoutPrefBoot, "Start on Boot", config.isStartOnBootEnabled())
        updateRowAccessibilityDescription(layoutPrefSimulation, "Simulation Mode", config.isSimulatedVpnEnabled())
        updateRowAccessibilityDescription(layoutPrefMonitor, "Monitor Protected Apps", config.isAppMonitorEnabled())

        updateVersionDisplay()
        updateVpnAppOfChoiceDisplay()
        updateSimulatedControlsVisibility()

        // --- SECTION 1: PROTECTION ---
        layoutPrefProtection.setOnClickListener {
            val isEnabled = config.isProtectionEnabled()
            if (isEnabled) {
                config.setProtectionEnabled(false)
                cbPrefProtection.isChecked = false
                updateRowAccessibilityDescription(layoutPrefProtection, "Enable Protection", false)
                config.addLog("User stopped protection from Settings.")
                stopVpnService()
            } else {
                val intent = android.net.VpnService.prepare(this)
                if (intent != null) {
                    config.addLog("VpnService.prepare requires user approval from Settings. Launching permission request.")
                    startActivityForResult(intent, REQUEST_VPN_PREPARE)
                } else {
                    enableProtection()
                }
            }
        }

        btnManageApps.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        layoutPrefBoot.setOnClickListener {
            val newChecked = !config.isStartOnBootEnabled()
            config.setStartOnBootEnabled(newChecked)
            cbPrefBoot.isChecked = newChecked
            updateRowAccessibilityDescription(layoutPrefBoot, "Start on Boot", newChecked)
        }

        layoutPrefMonitor.setOnClickListener {
            val intendedState = !config.isAppMonitorEnabled()
            if (!intendedState) {
                config.setAppMonitorEnabled(false)
                cbPrefMonitor.isChecked = false
                updateRowAccessibilityDescription(layoutPrefMonitor, "Monitor Protected Apps", false)
                triggerVpnServiceUpdate()
            } else {
                if (!config.hasUsageStatsPermission(this)) {
                    UpdateManager(this, config).showPermissionRequiredDialog(
                        "Permission Required",
                        "To detect when a protected application is opened, TunnelGuard requires 'Usage Access' permission.\n\nPlease enable it in the system settings screen that opens next.",
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                        Intent(Settings.ACTION_SETTINGS),
                        "ACTION_USAGE_ACCESS_SETTINGS"
                    )
                } else if (!config.hasSystemAlertWindowPermission()) {
                    val overlayIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    } else {
                        Intent(Settings.ACTION_SETTINGS)
                    }
                    UpdateManager(this, config).showPermissionRequiredDialog(
                        "Permission Required",
                        "To display security overlay warning, TunnelGuard requires 'Display Over Other Apps' permission.\n\nPlease enable this on the next screen.",
                        overlayIntent,
                        Intent(Settings.ACTION_SETTINGS),
                        "ACTION_MANAGE_OVERLAY_PERMISSION"
                    )
                } else if (!config.hasNotificationPermission()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                            this,
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_POST_NOTIFICATIONS
                        )
                    }
                } else {
                    config.setAppMonitorEnabled(true)
                    cbPrefMonitor.isChecked = true
                    updateRowAccessibilityDescription(layoutPrefMonitor, "Monitor Protected Apps", true)
                    triggerVpnServiceUpdate()
                }
            }
        }

        layoutPrefVpnChoice.setOnClickListener {
            showVpnAppOfChoiceDialog()
        }

        // --- SECTION 2: DIAGNOSTICS ---
        btnLaunchDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        btnExportLogs.setOnClickListener {
            val file = config.exportLogsToFile()
            if (file != null) {
                Toast.makeText(this, "Logs exported to:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to export logs", Toast.LENGTH_SHORT).show()
            }
        }

        // --- SECTION 3: ADVANCED ---
        layoutPrefSimulation.setOnClickListener {
            val newChecked = !config.isSimulatedVpnEnabled()
            config.setSimulatedVpnEnabled(newChecked)
            cbPrefSimulation.isChecked = newChecked
            updateRowAccessibilityDescription(layoutPrefSimulation, "Simulation Mode", newChecked)
            updateSimulatedControlsVisibility()
            triggerVpnServiceUpdate()
        }

        btnImportConfig.setOnClickListener {
            showImportDialog()
        }

        btnExportConfig.setOnClickListener {
            performConfigExport()
        }

        // Simulation State triggers
        btnSimulateConnected.setOnClickListener {
            if (config.isSimulatedVpnEnabled()) {
                config.setVPNState(VPNState.PROTECTED)
                config.addLog("Simulating VPN state change to PROTECTED.")
                triggerVpnServiceUpdate()
                Toast.makeText(this, "Simulated Connected", Toast.LENGTH_SHORT).show()
            }
        }

        btnSimulateDisconnected.setOnClickListener {
            if (config.isSimulatedVpnEnabled()) {
                config.setLastDisconnectReason("Simulation trigger")
                config.setVPNState(VPNState.DISCONNECTED)
                config.addLog("Simulating VPN state change to DISCONNECTED.")
                triggerVpnServiceUpdate()
                Toast.makeText(this, "Simulated Disconnected", Toast.LENGTH_SHORT).show()
            }
        }

        // --- SECTION 4: ABOUT ---
        btnCheckUpdates.setOnClickListener {
            checkForUpdatesFlow()
        }

        btnShowOnboarding.setOnClickListener {
            val intent = Intent(this, OnboardingActivity::class.java).apply {
                putExtra("from_settings", true)
            }
            startActivity(intent)
        }

        btnShowLicense.setOnClickListener {
            showLicenseDialog()
        }

        btnBack.setOnClickListener {
            finish()
        }

        // Initial focus
        layoutPrefProtection.requestFocus()
    }

    private fun enableProtection() {
        config.setProtectionEnabled(true)
        cbPrefProtection.isChecked = true
        updateRowAccessibilityDescription(layoutPrefProtection, "Enable Protection", true)
        config.addLog("User enabled TunnelGuard protection from Settings.")
        startVpnService()
    }

    private fun startVpnService() {
        if (TunnelGuardVpnService.isServiceStarting) {
            return
        }
        TunnelGuardVpnService.isServiceStarting = true
        val intent = Intent(this, TunnelGuardVpnService::class.java).apply {
            action = TunnelGuardVpnService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            config.addLog("Failed to start VPN service from Settings: ${e.message}", "ERROR")
            TunnelGuardVpnService.isServiceStarting = false
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, TunnelGuardVpnService::class.java).apply {
            action = TunnelGuardVpnService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PREPARE && resultCode == RESULT_OK) {
            enableProtection()
        } else if (requestCode == REQUEST_VPN_PREPARE) {
            config.addLog("VPN permission request rejected by user in Settings.")
            Toast.makeText(this, "VPN permission is required to enable TunnelGuard protection.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                config.addLog("Notification permission granted in Settings.")
                config.setAppMonitorEnabled(true)
                cbPrefMonitor.isChecked = true
                updateRowAccessibilityDescription(layoutPrefMonitor, "Monitor Protected Apps", true)
                triggerVpnServiceUpdate()
            } else {
                config.addLog("Notification permission denied in Settings.")
                Toast.makeText(this, "Notification permission is required to post security warnings.", Toast.LENGTH_LONG).show()
                cbPrefMonitor.isChecked = false
                updateRowAccessibilityDescription(layoutPrefMonitor, "Monitor Protected Apps", false)
            }
        }
    }

    override fun onDestroy() {
        loadingDialog?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }
        loadingDialog = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        cbPrefProtection.isChecked = config.isProtectionEnabled()
        if (config.hasUsageStatsPermission(this) && config.hasSystemAlertWindowPermission()) {
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
        tvAboutVersion.text = "Version: $currentVersion\nDeveloper: DisabledAbel\nDesigned for Android TV / Google TV."
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
                tvPrefVpnChoiceValue.text = vpnPkg
            }
        } else {
            tvPrefVpnChoiceValue.text = "None (System Settings)"
        }
    }

    private fun updateSimulatedControlsVisibility() {
        if (config.isSimulatedVpnEnabled()) {
            layoutSimStateControls.visibility = View.VISIBLE
        } else {
            layoutSimStateControls.visibility = View.GONE
        }
    }

    private fun triggerVpnServiceUpdate() {
        if (TunnelGuardVpnService.isServiceRunning) {
            val serviceIntent = Intent(this, TunnelGuardVpnService::class.java).apply {
                action = TunnelGuardVpnService.ACTION_UPDATE
            }
            startService(serviceIntent)
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

        AlertDialog.Builder(this)
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

    // --- IMPORT / EXPORT METHODS ---

    private fun performConfigExport() {
        try {
            val jsonStr = config.exportConfigToJson()
            if (jsonStr == null) {
                AlertDialog.Builder(this)
                    .setTitle("Export Failed")
                    .setMessage("Failed to serialize the active configuration.")
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
                return
            }

            // 1. Write to local backup file
            val backupFile = File(getExternalFilesDir(null), "tunnelguard_backup.json")
            FileWriter(backupFile).use { writer ->
                writer.write(jsonStr)
            }

            // 2. Offer Clipboard copy in the success flow as an explicit choice
            AlertDialog.Builder(this)
                .setTitle("Configuration Exported")
                .setMessage("Your configuration backup has been saved to file:\n${backupFile.absolutePath}")
                .setNeutralButton("Copy to Clipboard") { dialog, _ ->
                    dialog.dismiss()
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("TunnelGuard Configuration", jsonStr)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Configuration copied to clipboard!", Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Export Failed")
                .setMessage("Failed to export configuration: ${e.message}")
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun showImportDialog() {
        val backupFile = File(getExternalFilesDir(null), "tunnelguard_backup.json")
        val items = mutableListOf<String>()
        items.add("Import from Clipboard")
        if (backupFile.exists()) {
            items.add("Import from Backup File")
        }

        AlertDialog.Builder(this)
            .setTitle("Import Configuration")
            .setItems(items.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    // Clipboard import
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = clipboard.primaryClip
                    if (clipData != null && clipData.itemCount > 0) {
                        val clipText = clipData.getItemAt(0).text?.toString()
                        if (!clipText.isNullOrBlank()) {
                            performImport(clipText)
                        } else {
                            Toast.makeText(this, "Clipboard is empty!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "Clipboard is empty!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Backup file import
                    try {
                        val jsonStr = backupFile.readText()
                        performImport(jsonStr)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to read backup file: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun performImport(jsonStr: String) {
        val success = config.importConfigFromJson(jsonStr)
        if (success) {
            // 1. Reconcile VPN service and cbPrefProtection
            var vpnAuthWarningRequired = false
            if (config.isProtectionEnabled()) {
                val intent = android.net.VpnService.prepare(this)
                if (intent == null) {
                    cbPrefProtection.isChecked = true
                    startVpnService()
                } else {
                    config.setProtectionEnabled(false)
                    cbPrefProtection.isChecked = false
                    vpnAuthWarningRequired = true
                }
            } else {
                cbPrefProtection.isChecked = false
                stopVpnService()
            }

            // 2. Enforce permission requirements immediately for imported monitor settings
            if (config.isAppMonitorEnabled()) {
                if (!config.hasUsageStatsPermission(this) || !config.hasSystemAlertWindowPermission()) {
                    config.setAppMonitorEnabled(false)
                    cbPrefMonitor.isChecked = false
                } else {
                    cbPrefMonitor.isChecked = true
                    triggerVpnServiceUpdate()
                }
            } else {
                cbPrefMonitor.isChecked = false
            }

            // 3. Update remaining check widgets
            cbPrefBoot.isChecked = config.isStartOnBootEnabled()
            cbPrefSimulation.isChecked = config.isSimulatedVpnEnabled()

            // 4. Update row accessibility descriptions
            updateRowAccessibilityDescription(layoutPrefProtection, "Enable Protection", config.isProtectionEnabled())
            updateRowAccessibilityDescription(layoutPrefBoot, "Start on Boot", config.isStartOnBootEnabled())
            updateRowAccessibilityDescription(layoutPrefSimulation, "Simulation Mode", config.isSimulatedVpnEnabled())
            updateRowAccessibilityDescription(layoutPrefMonitor, "Monitor Protected Apps", config.isAppMonitorEnabled())

            updateVpnAppOfChoiceDisplay()
            updateSimulatedControlsVisibility()

            if (vpnAuthWarningRequired) {
                AlertDialog.Builder(this)
                    .setTitle("Import Successful (Manual Action Required)")
                    .setMessage("Configuration has been imported successfully. However, VPN permission is required to enable protection. Please enable protection manually to authorize.")
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Import Successful")
                    .setMessage("Configuration backup has been imported successfully! All settings and profiles have been restored.")
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle("Import Failed")
                .setMessage("Failed to parse or apply the backup JSON. Please ensure the backup data is valid.")
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun showLicenseDialog() {
        val licenseText = """
            MIT License

            Copyright (c) 2024 DisabledAbel (TunnelGuard Team)

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all
            copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("MIT Open-Source License")
            .setMessage(licenseText)
            .setPositiveButton("Close") { d, _ -> d.dismiss() }
            .show()
    }

    private fun checkForUpdatesFlow() {
        if (isUpdateChecking) return
        isUpdateChecking = true
        btnCheckUpdates.isEnabled = false

        val dialog = AlertDialog.Builder(this)
            .setTitle("Checking for Updates")
            .setMessage("Connecting to GitHub to check for updates...")
            .setCancelable(false)
            .create()
        loadingDialog = dialog
        dialog.show()

        lifecycleScope.launch {
            val repository = UpdateRepository.getInstance(applicationContext)
            val result = repository.checkForUpdate(config.getAppVersionName())

            if (isFinishing || isDestroyed) return@launch
            loadingDialog?.dismiss()
            loadingDialog = null

            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    UpdateManager(this@SettingsActivity, config).showUpdateAvailableDialog(
                        result.latestVersion,
                        result.apkUrl,
                        true
                    )
                }
                is UpdateCheckResult.NoUpdate, is UpdateCheckResult.NotModified -> {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Up to Date")
                        .setMessage("TunnelGuard is already up to date!\n\nCurrent version: ${config.getAppVersionName()}")
                        .setPositiveButton("OK") { d, _ -> d.dismiss() }
                        .show()
                }
                is UpdateCheckResult.Failure -> {
                    UpdateManager(this@SettingsActivity, config).showUpdateErrorDialog(result.errorMessage)
                }
            }
            isUpdateChecking = false
            btnCheckUpdates.isEnabled = true
        }
    }

    private fun updateRowAccessibilityDescription(layout: View, title: String, checked: Boolean) {
        val stateText = if (checked) "enabled" else "disabled"
        layout.contentDescription = "$title option, currently $stateText. Double click to toggle."
    }
}
