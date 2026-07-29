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

        // Initialize state
        cbPrefBoot.isChecked = config.isStartOnBootEnabled()
        cbPrefSimulation.isChecked = config.isSimulatedVpnEnabled()

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
