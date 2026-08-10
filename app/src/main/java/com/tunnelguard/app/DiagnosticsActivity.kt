package com.tunnelguard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig
    private var connectivityManager: ConnectivityManager? = null

    private lateinit var tvVpnState: TextView
    private lateinit var tvProtectionState: TextView
    private lateinit var tvAppsCount: TextView
    private lateinit var tvLastTransition: TextView
    private lateinit var tvBootStatus: TextView
    private lateinit var tvIpv4Status: TextView
    private lateinit var tvIpv6Status: TextView
    private lateinit var tvAndroidVersion: TextView
    private lateinit var tvDeviceModel: TextView
    private lateinit var tvAppVersion: TextView

    private lateinit var btnRefresh: Button
    private lateinit var btnCopy: Button
    private lateinit var btnExport: Button
    private lateinit var btnClear: Button
    private lateinit var btnBack: Button

    private lateinit var rvDiagLogs: RecyclerView
    private lateinit var logsAdapter: DiagLogsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        config = TunnelGuardConfig(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Bind UI Elements
        tvVpnState = findViewById(R.id.diag_vpn_state)
        tvProtectionState = findViewById(R.id.diag_protection_state)
        tvAppsCount = findViewById(R.id.diag_apps_count)
        tvLastTransition = findViewById(R.id.diag_last_transition)
        tvBootStatus = findViewById(R.id.diag_boot_status)
        tvIpv4Status = findViewById(R.id.diag_ipv4_status)
        tvIpv6Status = findViewById(R.id.diag_ipv6_status)
        tvAndroidVersion = findViewById(R.id.diag_android_version)
        tvDeviceModel = findViewById(R.id.diag_device_model)
        tvAppVersion = findViewById(R.id.diag_app_version)

        btnRefresh = findViewById(R.id.btn_refresh_diag)
        btnCopy = findViewById(R.id.btn_copy_logs)
        btnExport = findViewById(R.id.btn_export_logs_diag)
        btnClear = findViewById(R.id.btn_clear_logs_diag)
        btnBack = findViewById(R.id.btn_back_diag)

        rvDiagLogs = findViewById(R.id.rv_diag_logs)

        // Setup Recycler view
        rvDiagLogs.layoutManager = LinearLayoutManager(this)
        logsAdapter = DiagLogsAdapter(this, emptyList())
        rvDiagLogs.adapter = logsAdapter

        // Button Click Listeners
        btnRefresh.setOnClickListener {
            refreshDiagnostics()
        }

        btnCopy.setOnClickListener {
            copyDiagnosticsToClipboard()
        }

        btnExport.setOnClickListener {
            val file = config.exportLogsToFile()
            if (file != null) {
                Toast.makeText(this, "Logs exported to:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to export logs", Toast.LENGTH_SHORT).show()
            }
        }

        btnClear.setOnClickListener {
            config.clearLogs()
            config.addLog("Diagnostics and debug logs cleared by user.")
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
            refreshDiagnostics()
        }

        btnBack.setOnClickListener {
            finish()
        }

        // Set focus
        btnRefresh.requestFocus()

        // Populate details
        refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
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
        val securityState = SecurityStateMachine.getSecurityState(
            this,
            config,
            TunnelGuardVpnService.isServiceRunning,
            TunnelGuardVpnService.isServiceStarting,
            TunnelGuardVpnService.isTunnelEstablished,
            connectivityManager
        )

        tvVpnState.text = vpnState.name
        tvProtectionState.text = securityState.name

        // Set colors based on state
        when (securityState) {
            SecurityState.PROTECTED -> tvProtectionState.setTextColor(resources.getColor(R.color.status_active))
            SecurityState.BLOCKING -> tvProtectionState.setTextColor(resources.getColor(R.color.status_blocking))
            SecurityState.INACTIVE -> tvProtectionState.setTextColor(resources.getColor(R.color.status_inactive))
            SecurityState.CONNECTING -> tvProtectionState.setTextColor(resources.getColor(R.color.status_connecting))
            SecurityState.ERROR, SecurityState.UNPROTECTED_FAULT -> tvProtectionState.setTextColor(resources.getColor(R.color.status_disconnected))
        }

        tvAppsCount.text = "${config.getProtectedApps().size}"

        val lastTrans = config.getLastStateTransitionTime()
        if (lastTrans == 0L) {
            tvLastTransition.text = "Never"
        } else {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            tvLastTransition.text = format.format(Date(lastTrans))
        }

        val bootFailure = config.getLastBootFailure()
        if (config.isStartOnBootEnabled()) {
            if (bootFailure != null) {
                tvBootStatus.text = "Enabled (Failed: $bootFailure)"
                tvBootStatus.setTextColor(resources.getColor(R.color.status_disconnected))
            } else {
                tvBootStatus.text = "Enabled"
                tvBootStatus.setTextColor(resources.getColor(R.color.status_active))
            }
        } else {
            tvBootStatus.text = "Disabled"
            tvBootStatus.setTextColor(resources.getColor(R.color.text_secondary))
        }

        // Traffic status and protocol routing details
        val protInfo = ProtocolProtectionMapper.getInfo(securityState, config.isIpv6ProtectionActive(), isDiagnostics = true)
        tvIpv4Status.text = protInfo.ipv4Text
        tvIpv4Status.setTextColor(resources.getColor(protInfo.ipv4ColorRes))
        tvIpv6Status.text = protInfo.ipv6Text
        tvIpv6Status.setTextColor(resources.getColor(protInfo.ipv6ColorRes))

        // Device and version info
        tvAndroidVersion.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        tvDeviceModel.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        tvAppVersion.text = config.getAppVersionName()

        // Structured logs List
        val allLogs = config.getLogs()
        logsAdapter.updateList(allLogs)
    }

    private fun copyDiagnosticsToClipboard() {
        refreshDiagnostics()
        val lastTrans = config.getLastStateTransitionTime()
        val transStr = if (lastTrans == 0L) "Never" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastTrans))

        val vpnState = config.getVPNState()
        val securityState = SecurityStateMachine.getSecurityState(
            this,
            config,
            TunnelGuardVpnService.isServiceRunning,
            TunnelGuardVpnService.isServiceStarting,
            TunnelGuardVpnService.isTunnelEstablished,
            connectivityManager
        )

        val report = """
            === TUNNELGUARD DIAGNOSTICS REPORT ===
            App Version: ${config.getAppVersionName()}
            Android Version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Device Model: ${Build.MANUFACTURER} ${Build.MODEL}
            VPN State: ${vpnState.name}
            Protection State: ${securityState.name}
            Protected Apps Count: ${config.getProtectedApps().size}
            Start on Boot: ${config.isStartOnBootEnabled()}
            Last Transition: $transStr
            IPv4 Protection: ${tvIpv4Status.text}
            IPv6 Protection: ${tvIpv6Status.text}

            --- LATEST SYSTEM EVENTS ---
            ${config.getLogs().take(20).joinToString("\n")}
        """.trimIndent()

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("TunnelGuard Diagnostics", report)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Diagnostics copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private class DiagLogsAdapter(
        private val context: Context,
        private var items: List<String>
    ) : RecyclerView.Adapter<DiagLogsAdapter.ViewHolder>() {

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
