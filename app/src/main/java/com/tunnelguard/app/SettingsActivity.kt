package com.tunnelguard.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

class SettingsActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig

    private lateinit var layoutPrefBoot: LinearLayout
    private lateinit var cbPrefBoot: CheckBox

    private lateinit var layoutPrefSimulation: LinearLayout
    private lateinit var cbPrefSimulation: CheckBox

    private lateinit var btnSimulateConnected: Button
    private lateinit var btnSimulateDisconnected: Button
    private lateinit var btnClearLogs: Button
    private lateinit var rvDebugLogs: RecyclerView

    private lateinit var btnCheckUpdates: Button
    private lateinit var tvAboutVersion: TextView

    private lateinit var logsAdapter: LogsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        config = TunnelGuardConfig(this)

        // Bind preference rows and checkboxes
        layoutPrefBoot = findViewById(R.id.layout_pref_boot)
        cbPrefBoot = findViewById(R.id.cb_pref_boot)

        layoutPrefSimulation = findViewById(R.id.layout_pref_simulation)
        cbPrefSimulation = findViewById(R.id.cb_pref_simulation)

        btnSimulateConnected = findViewById(R.id.btn_simulate_connected)
        btnSimulateDisconnected = findViewById(R.id.btn_simulate_disconnected)
        btnClearLogs = findViewById(R.id.btn_clear_logs)
        rvDebugLogs = findViewById(R.id.rv_debug_logs)

        btnCheckUpdates = findViewById(R.id.btn_check_updates)
        tvAboutVersion = findViewById(R.id.tv_about_version)

        // Initialize state
        cbPrefBoot.isChecked = config.isStartOnBootEnabled()
        cbPrefSimulation.isChecked = config.isSimulatedVpnEnabled()

        // Update version string with the current stored version name
        updateVersionDisplay()

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

        btnSimulateConnected.setOnClickListener {
            if (config.isSimulatedVpnEnabled()) {
                config.setVPNState(VPNState.CONNECTED)
                config.addLog("Simulating VPN state change to CONNECTED.")
                triggerVpnServiceUpdate()
            } else {
                config.addLog("Please enable Simulation Mode first before using simulated triggers.")
            }
        }

        btnSimulateDisconnected.setOnClickListener {
            if (config.isSimulatedVpnEnabled()) {
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

    private fun updateVersionDisplay() {
        val currentVersion = config.getAppVersionName()
        tvAboutVersion.text = "Version: $currentVersion\nDeveloper: Jules (TunnelGuard Team)\nDesigned for Android TV / Google TV."
    }

    private fun checkForUpdatesFlow() {
        val currentVersion = config.getAppVersionName()
        val nextVersion = when (currentVersion) {
            "1.0.0" -> "1.1.0"
            "1.1.0" -> "1.2.0"
            "1.2.0" -> "1.3.0"
            else -> {
                // Parse and increment the minor version or patch version
                try {
                    val parts = currentVersion.split(".")
                    if (parts.size >= 2) {
                        val major = parts[0]
                        val minor = parts[1].toInt() + 1
                        "$major.$minor.0"
                    } else {
                        "1.0.0"
                    }
                } catch (e: Exception) {
                    "1.0.0"
                }
            }
        }

        config.addLog("Checking for updates... Current version: $currentVersion")

        // Build alert dialog to show download and installation progress
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("New Update Available")
        builder.setMessage("A new version of TunnelGuard (v$nextVersion) is available.\n\nDownloading and installing update automatically...")
        builder.setCancelable(false)
        val progressDialog = builder.create()
        progressDialog.show()

        // Simulate network download and background installation
        btnCheckUpdates.postDelayed({
            progressDialog.dismiss()

            // Automatically update version ID in app config
            config.setAppVersionName(nextVersion)
            updateVersionDisplay()
            config.addLog("Version ID updated to $nextVersion in app config.")

            // Write mock APK file and start the Android installation prompt
            installMockApk(nextVersion)

            // Show success dialog
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Update Downloaded & Installed")
                .setMessage("TunnelGuard has successfully simulated the download and update to version $nextVersion.\n\nThe version ID has been updated in-app, and the package installer intent has been launched.")
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()

        }, 2000)
    }

    private fun installMockApk(versionName: String) {
        try {
            // Write a dummy apk file in the cache directory
            val updateApkFile = java.io.File(cacheDir, "TunnelGuard-v$versionName-update.apk")
            if (!updateApkFile.exists()) {
                updateApkFile.createNewFile()
            }
            // Populate with mock bytes
            val outputStream = java.io.FileOutputStream(updateApkFile)
            outputStream.write("MOCK_APK_BYTES".toByteArray())
            outputStream.close()

            config.addLog("Generated mock update APK at: ${updateApkFile.absolutePath}")

            // Generate content URI using FileProvider
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                updateApkFile
            )

            // Package installer intent
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            config.addLog("Launching package installer intent for URI: $apkUri")
            startActivity(installIntent)

        } catch (e: Exception) {
            config.addLog("Failed to auto-install mock APK: ${e.message}")
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
