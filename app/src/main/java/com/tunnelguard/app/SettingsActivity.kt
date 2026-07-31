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
                showPermissionRequiredDialog(
                    "Permission Required",
                    "To detect when a protected application is opened and display the security warning, TunnelGuard requires the 'Usage Access' permission.\n\nPlease enable TunnelGuard in the system settings screen that opens next.",
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                    "ACTION_USAGE_ACCESS_SETTINGS"
                )
            }
        }

        layoutPrefVpnChoice.setOnClickListener {
            showVpnAppOfChoiceDialog()
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
        tvAboutVersion.text = "Version: $currentVersion\nDeveloper: Jules (TunnelGuard Team)\nDesigned for Android TV / Google TV."
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

    private fun showPermissionRequiredDialog(
        title: String,
        message: String,
        primaryIntent: Intent,
        fallbackIntent: Intent,
        logErrorTag: String
    ) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Settings") { dialog, _ ->
                dialog.dismiss()
                try {
                    startActivity(primaryIntent)
                } catch (e: Exception) {
                    config.addLog("Failed to launch $logErrorTag: ${e.message}")
                    try {
                        startActivity(fallbackIntent)
                    } catch (ex: Exception) {
                        config.addLog("Failed to launch fallback settings: ${ex.message}")
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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
                showPermissionRequiredDialog(
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

        Thread {
            try {
                val url = URL("https://api.github.com/repos/DisabledAbel/TunnelGuard/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "TunnelGuard-App")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonObj = JSONObject(jsonStr)
                    val tagName = jsonObj.getString("tag_name")
                    val cleanTagName = tagName.trim().removePrefix("v")

                    var apkUrl: String? = null
                    val assets = jsonObj.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val assetName = asset.getString("name")
                            if (assetName.endsWith(".apk")) {
                                apkUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                    }

                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        loadingDialog.dismiss()
                        config.addLog("Latest release on GitHub: $cleanTagName")

                        if (VersionComparator.isNewerVersion(currentVersion, cleanTagName)) {
                            // New version is available! Ask the user to download.
                            showUpdateAvailableDialog(cleanTagName, apkUrl)
                        } else {
                            // Up to date
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Up to Date")
                                .setMessage("TunnelGuard is already up to date!\n\nCurrent version: $currentVersion\nLatest version: $cleanTagName")
                                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                                .show()
                        }
                    }
                } else {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        loadingDialog.dismiss()
                        config.addLog("Update check failed with HTTP response code: $responseCode")
                        showUpdateErrorDialog("HTTP Error $responseCode trying to query GitHub.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loadingDialog.dismiss()
                    config.addLog("Error checking for updates: ${e.message}")
                    showUpdateErrorDialog(e.message ?: "Unknown network error")
                }
            } finally {
                runOnUiThread {
                    isUpdateChecking = false
                    btnCheckUpdates.isEnabled = true
                }
            }
        }.start()
    }

    private fun showUpdateAvailableDialog(latestVersion: String, apkUrl: String?) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New Update Available")
            .setMessage("A new version of TunnelGuard (v$latestVersion) is available.\n\nWould you like to download and install this update now?")
            .setPositiveButton("Download") { dialog, _ ->
                dialog.dismiss()
                if (apkUrl != null) {
                    downloadAndInstallUpdate(latestVersion, apkUrl)
                } else {
                    config.addLog("Error: No APK file found in GitHub release assets.")
                    showUpdateErrorDialog("No APK asset found in the latest GitHub release.")
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun downloadAndInstallUpdate(latestVersion: String, downloadUrl: String) {
        val downloadBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
        downloadBuilder.setTitle("Downloading Update")
        downloadBuilder.setMessage("Downloading TunnelGuard v$latestVersion...\n0%")
        downloadBuilder.setCancelable(false)
        val downloadDialog = downloadBuilder.create()
        downloadDialog.show()

        Thread {
            try {
                val updatesDir = File(cacheDir, "updates")
                if (!updatesDir.exists()) {
                    updatesDir.mkdirs()
                }
                val updateApkFile = File(updatesDir, "TunnelGuard-v$latestVersion-update.apk")

                downloadUrlWithRedirects(downloadUrl, updateApkFile) { progress, total ->
                    val percentage = if (total > 0) (progress * 100L / total).toInt() else 0
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            downloadDialog.setMessage("Downloading TunnelGuard v$latestVersion...\n$percentage%")
                        }
                    }
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    downloadDialog.dismiss()

                    // Install the APK
                    installApkFile(latestVersion)

                    // Show success dialog
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Update Downloaded")
                        .setMessage("TunnelGuard has successfully downloaded version $latestVersion.\n\nThe package installer intent has been launched to complete the update.")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    downloadDialog.dismiss()
                    config.addLog("Failed to download update: ${e.message}")
                    showUpdateErrorDialog("Failed to download update: ${e.message}")
                }
            }
        }.start()
    }

    private fun downloadUrlWithRedirects(urlString: String, outputFile: File, progressUpdate: (Int, Int) -> Unit) {
        var currentUrl = urlString
        var redirectCount = 0
        val maxRedirects = 5

        while (redirectCount < maxRedirects) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "TunnelGuard-App")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308) {

                val newUrl = conn.getHeaderField("Location") ?: throw IOException("Redirect with empty Location header.")
                currentUrl = newUrl
                redirectCount++
                continue
            }

            if (status == HttpURLConnection.HTTP_OK) {
                val contentLength = conn.contentLength
                conn.inputStream.use { inputStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var totalBytesRead = 0
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            progressUpdate(totalBytesRead, contentLength)
                        }
                    }
                }
                return
            } else {
                throw IOException("Server returned HTTP $status")
            }
        }
        throw IOException("Too many redirects")
    }

    private fun installApkFile(versionName: String) {
        try {
            val updatesDir = File(cacheDir, "updates")
            val updateApkFile = File(updatesDir, "TunnelGuard-v$versionName-update.apk")

            config.addLog("Preparing to install downloaded APK: ${updateApkFile.absolutePath}")

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
            config.addLog("Failed to auto-install APK: ${e.message}")
        }
    }

    private fun showUpdateErrorDialog(errorMessage: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Update Check Failed")
            .setMessage("Could not check for updates or download update.\n\nDetails: $errorMessage")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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
