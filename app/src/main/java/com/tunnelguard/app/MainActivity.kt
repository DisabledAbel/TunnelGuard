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
    private lateinit var tvProtectedCount: TextView
    private lateinit var btnToggleProtection: Button
    private lateinit var btnManageApps: Button
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = TunnelGuardConfig(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        layoutMain = findViewById(R.id.main_layout)
        tvVpnStatus = findViewById(R.id.tv_vpn_status)
        tvProtectionStatus = findViewById(R.id.tv_protection_status)
        tvProtectedCount = findViewById(R.id.tv_protected_count)
        btnToggleProtection = findViewById(R.id.btn_toggle_protection)
        btnManageApps = findViewById(R.id.btn_manage_apps)
        btnSettings = findViewById(R.id.btn_settings)
        rvProtectedApps = findViewById(R.id.rv_home_protected_apps)

        // Remote Navigation Hookups
        btnToggleProtection.requestFocus()

        btnToggleProtection.setOnClickListener {
            toggleProtection()
        }

        btnManageApps.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Setup Apps Status List
        rvProtectedApps.layoutManager = LinearLayoutManager(this)
        adapter = HomeAppsAdapter(this, emptyList())
        rvProtectedApps.adapter = adapter

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        // SDK 33 compatibility: specify RECEIVER_NOT_EXPORTED flags dynamically on Tiramisu and above
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

        // Register network callback to detect VPN on/off in real-time even when service is not running
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
            // Stop Protection
            config.setProtectionEnabled(false)
            config.addLog("User stopped protection.")
            stopVpnService()
            updateUI()
        } else {
            // Start Protection - Requires VpnService preparation first
            val intent = VpnService.prepare(this)
            if (intent != null) {
                config.addLog("VpnService.prepare requires user approval. Launching permission request.")
                startActivityForResult(intent, REQUEST_VPN_PREPARE)
            } else {
                onActivityResult(REQUEST_VPN_PREPARE, Activity.RESULT_OK, null)
            }
        }
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

    private fun updateUI() {
        // If the service is not running, check and update the real VPN state in config so the UI is accurate
        if (!TunnelGuardVpnService.isServiceRunning) {
            val isUpstreamVpnConnected = config.detectRealVpnCapabilities(connectivityManager)
            val currentVpnState = if (isUpstreamVpnConnected) {
                VPNState.CONNECTED
            } else {
                VPNState.DISCONNECTED
            }
            config.setVPNState(currentVpnState)
        }

        // Fetch current states
        val vpnState = config.getVPNState()
        val protectionState = config.getProtectionState()
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
        tvProtectionStatus.text = "● ${protectionState.name}"
        when (protectionState) {
            ProtectionState.ACTIVE -> {
                tvProtectionStatus.setTextColor(resources.getColor(R.color.status_active))
                btnToggleProtection.text = "Stop Protection"
                // Change layout background subtle design or theme colors on state changes
                layoutMain.setBackgroundColor(resources.getColor(R.color.background_dark))
            }
            ProtectionState.BLOCKING -> {
                tvProtectionStatus.text = "● BLOCKING"
                tvProtectionStatus.setTextColor(resources.getColor(R.color.status_blocking))
                btnToggleProtection.text = "Stop Protection"
                // Security focus block state: subtle reddish highlight background
                layoutMain.setBackgroundColor(resources.getColor(R.color.background_dark))
            }
            ProtectionState.INACTIVE -> {
                tvProtectionStatus.setTextColor(resources.getColor(R.color.status_inactive))
                btnToggleProtection.text = "Start Protection"
                layoutMain.setBackgroundColor(resources.getColor(R.color.background_dark))
            }
        }

        // 3. Count protected apps
        tvProtectedCount.text = "Protected Apps: ${protectedApps.size}"

        // 4. Update the adapter list
        val appInfos = mutableListOf<AppStatusInfo>()
        val pm = packageManager
        for (pkg in protectedApps) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                val statusText = if (protectionState == ProtectionState.BLOCKING) {
                    "INTERNET BLOCKED"
                } else if (protectionState == ProtectionState.ACTIVE) {
                    "PROTECTED"
                } else {
                    "UNPROTECTED"
                }
                appInfos.add(AppStatusInfo(label, pkg, icon, statusText))
            } catch (e: Exception) {
                // Application uninstalled but still in config list
                appInfos.add(AppStatusInfo(pkg, pkg, resources.getDrawable(android.R.drawable.sym_def_app_icon), "UNKNOWN"))
            }
        }
        adapter.updateList(appInfos)
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

            if (item.statusText == "INTERNET BLOCKED") {
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
