package com.tunnelguard.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsDashboardActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig

    private lateinit var btnTabAppLogs: Button
    private lateinit var btnTabVpnLogs: Button
    private lateinit var btnTabDeviceLogs: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnClearAppLogs: Button
    private lateinit var btnExportLogs: Button
    private lateinit var btnBack: Button

    private lateinit var tvActiveTabTitle: TextView
    private lateinit var etLogSearch: android.widget.EditText
    private lateinit var rvLogsList: RecyclerView
    private lateinit var logsAdapter: LogsAdapter

    enum class LogTab {
        APP,
        VPN,
        DEVICE
    }

    private var activeTab = LogTab.APP
    private var allLogsList: List<String> = emptyList()

    var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    var onRefreshCompleteCallback: (() -> Unit)? = null
    private var currentRequestId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs_dashboard)

        config = TunnelGuardConfig(this)

        // Bind layouts
        btnTabAppLogs = findViewById(R.id.btn_tab_app_logs)
        btnTabVpnLogs = findViewById(R.id.btn_tab_vpn_logs)
        btnTabDeviceLogs = findViewById(R.id.btn_tab_device_logs)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnClearAppLogs = findViewById(R.id.btn_clear_app_logs)
        btnExportLogs = findViewById(R.id.btn_export_logs)
        btnBack = findViewById(R.id.btn_back)

        tvActiveTabTitle = findViewById(R.id.tv_active_tab_title)
        etLogSearch = findViewById(R.id.et_log_search)
        rvLogsList = findViewById(R.id.rv_logs_list)

        // Setup Recycler
        rvLogsList.layoutManager = LinearLayoutManager(this)
        logsAdapter = LogsAdapter(this, emptyList())
        rvLogsList.adapter = logsAdapter

        // Set Click Listeners
        btnTabAppLogs.setOnClickListener {
            switchTab(LogTab.APP)
        }

        btnTabVpnLogs.setOnClickListener {
            switchTab(LogTab.VPN)
        }

        btnTabDeviceLogs.setOnClickListener {
            switchTab(LogTab.DEVICE)
        }

        btnRefresh.setOnClickListener {
            refreshCurrentLogs()
        }

        btnClearAppLogs.setOnClickListener {
            config.clearLogs()
            config.addLog("App debug logs cleared by user.")
            Toast.makeText(this, "App logs cleared", Toast.LENGTH_SHORT).show()
            if (activeTab == LogTab.APP || activeTab == LogTab.VPN) {
                refreshCurrentLogs()
            }
        }

        btnExportLogs.setOnClickListener {
            val file = config.exportLogsToFile()
            if (file != null) {
                Toast.makeText(this, "Logs exported to:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to export logs", Toast.LENGTH_SHORT).show()
            }
        }

        btnBack.setOnClickListener {
            finish()
        }

        // Search text watcher
        etLogSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyLogSearchFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Initialize state & focus
        switchTab(LogTab.APP)
        btnTabAppLogs.requestFocus()
    }

    private fun switchTab(tab: LogTab) {
        activeTab = tab
        updateTabButtonLabels()
        refreshCurrentLogs()
    }

    private fun updateTabButtonLabels() {
        btnTabAppLogs.text = if (activeTab == LogTab.APP) "● App Debug Logs" else "App Debug Logs"
        btnTabVpnLogs.text = if (activeTab == LogTab.VPN) "● VPN Logs" else "VPN Logs"
        btnTabDeviceLogs.text = if (activeTab == LogTab.DEVICE) "● Device Logs" else "Device Logs"

        tvActiveTabTitle.text = when (activeTab) {
            LogTab.APP -> "App Debug Logs"
            LogTab.VPN -> "VPN Logs"
            LogTab.DEVICE -> "Device Logs (Logcat)"
        }
    }

    private fun refreshCurrentLogs() {
        val requestId = ++currentRequestId
        val capturedTab = activeTab
        lifecycleScope.launch {
            val logs = withContext(ioDispatcher) {
                when (capturedTab) {
                    LogTab.APP -> config.getLogs()
                    LogTab.VPN -> getVpnLogs()
                    LogTab.DEVICE -> getDeviceLogcatRaw()
                }
            }
            if (requestId == currentRequestId && capturedTab == activeTab) {
                allLogsList = logs
                applyLogSearchFilter(etLogSearch.text.toString())
                if (logs.isNotEmpty()) {
                    rvLogsList.scrollToPosition(0)
                }
            }
            onRefreshCompleteCallback?.invoke()
        }
    }

    private fun applyLogSearchFilter(query: String) {
        val filtered = if (query.isBlank()) {
            allLogsList
        } else {
            allLogsList.filter { it.contains(query, ignoreCase = true) }
        }
        logsAdapter.updateList(filtered)
    }

    private fun getVpnLogs(): List<String> {
        val result = mutableListOf<String>()
        // 1. Get filtered app logs
        val appLogs = config.getLogs().filter {
            it.contains("vpn", ignoreCase = true) ||
            it.contains("VpnService", ignoreCase = true) ||
            it.contains("TunnelGuard", ignoreCase = true) ||
            it.contains("fail-closed", ignoreCase = true)
        }
        result.addAll(appLogs)

        // 2. Get filtered device logs (logcat)
        try {
            val logcat = getDeviceLogcatRaw()
            val filteredLogcat = logcat.filter {
                it.contains("vpn", ignoreCase = true) ||
                it.contains("VpnService", ignoreCase = true) ||
                it.contains("TunnelGuard", ignoreCase = true) ||
                it.contains("fail-closed", ignoreCase = true)
            }
            if (filteredLogcat.isNotEmpty()) {
                result.add("--- SYSTEM DEVICE VPN LOGS ---")
                result.addAll(filteredLogcat)
            }
        } catch (e: Exception) {
            result.add("Error reading system VPN logs: ${e.message}")
        }
        return result
    }

    private fun getDeviceLogcatRaw(): List<String> {
        val lines = java.util.ArrayDeque<String>(500)
        try {
            val process = Runtime.getRuntime().exec("logcat -d -v time")
            val bufferedReader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String?
            while (bufferedReader.readLine().also { line = it } != null) {
                line?.let {
                    if (lines.size >= 500) {
                        lines.removeFirst()
                    }
                    lines.add(it)
                }
            }
        } catch (e: Exception) {
            lines.add("Error reading logcat: ${e.message}")
        }
        return lines.toList()
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
