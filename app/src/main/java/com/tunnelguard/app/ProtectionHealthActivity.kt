package com.tunnelguard.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ProtectionHealthActivity : AppCompatActivity() {
    private lateinit var config: TunnelGuardConfig
    private lateinit var collector: ProtectionHealthCollector
    private lateinit var overall: TextView
    private lateinit var adapter: HealthAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protection_health)
        config = TunnelGuardConfig(this)
        collector = ProtectionHealthCollector(this, config)
        overall = findViewById(R.id.health_overall)
        adapter = HealthAdapter(::performAction)
        findViewById<RecyclerView>(R.id.health_checks).apply {
            layoutManager = LinearLayoutManager(this@ProtectionHealthActivity)
            adapter = this@ProtectionHealthActivity.adapter
        }
        findViewById<Button>(R.id.btn_run_health).apply {
            setOnClickListener { refresh() }
            requestFocus()
        }
        findViewById<Button>(R.id.btn_back_health).setOnClickListener { finish() }
        refresh()
    }

    override fun onResume() { super.onResume(); if (::collector.isInitialized) refresh() }

    private fun refresh() {
        config.addLogInfo("Protection health check started")
        val report = collector.collect()
        overall.text = when (report.overall) {
            OverallHealthStatus.HEALTHY -> "PASS — ✓ Healthy"
            OverallHealthStatus.ATTENTION_REQUIRED -> "WARNING — ⚠ Attention Required"
            OverallHealthStatus.UNPROTECTED -> "FAIL — ✗ Protection Problem"
            OverallHealthStatus.UNKNOWN -> "UNKNOWN — ? Unable to Verify"
        }
        adapter.submit(report.checks)
        val counts = HealthCheckStatus.values().associateWith { status -> report.checks.count { it.status == status } }
        config.addLogInfo("Protection health check completed: ${counts[HealthCheckStatus.PASS]} PASS, ${counts[HealthCheckStatus.WARNING]} WARNING, ${counts[HealthCheckStatus.FAIL]} FAIL, ${counts[HealthCheckStatus.UNKNOWN]} UNKNOWN")
        if (report.overall == OverallHealthStatus.UNPROTECTED) config.addLogError("Critical protection health failure detected")
    }

    private fun performAction(action: HealthCheckAction) {
        config.addLogInfo("User opened health repair action: $action")
        when (action) {
            HealthCheckAction.GRANT_VPN_PERMISSION -> VpnService.prepare(this)?.let { startActivityForResult(it, 4100) }
            HealthCheckAction.ENABLE_PROTECTION -> { config.setProtectionEnabled(true); refresh() }
            HealthCheckAction.MANAGE_PROTECTED_APPS -> startActivity(Intent(this, AppsActivity::class.java))
            HealthCheckAction.GRANT_USAGE_ACCESS -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            HealthCheckAction.GRANT_OVERLAY_PERMISSION -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            HealthCheckAction.GRANT_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4101)
            HealthCheckAction.ENABLE_START_ON_BOOT -> { config.setStartOnBootEnabled(true); refresh() }
            HealthCheckAction.SELECT_VPN_APP -> startActivity(Intent(this, SettingsActivity::class.java))
            HealthCheckAction.OPEN_INSTALL_PERMISSION -> SettingsIntentLauncher.launch(SettingsIntentLauncher.installPermissionIntents(packageName), ::startActivity)
            HealthCheckAction.OPEN_BATTERY_SETTINGS -> SettingsIntentLauncher.launch(listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))), ::startActivity)
        }
    }

    private class HealthAdapter(private val action: (HealthCheckAction) -> Unit) : RecyclerView.Adapter<HealthAdapter.Holder>() {
        private var items = emptyList<HealthCheckResult>()
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.health_item_title)
            val summary: TextView = view.findViewById(R.id.health_item_summary)
            val fix: Button = view.findViewById(R.id.health_item_action)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_health_check, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val symbol = when (item.status) { HealthCheckStatus.PASS -> "✓"; HealthCheckStatus.WARNING -> "⚠"; HealthCheckStatus.FAIL -> "✗"; HealthCheckStatus.UNKNOWN -> "?"; HealthCheckStatus.NOT_APPLICABLE -> "—" }
            holder.title.text = "$symbol ${item.status}: ${item.title}"
            holder.summary.text = listOfNotNull(item.summary, item.details).joinToString("\n")
            holder.itemView.contentDescription = "${item.status}, ${item.title}. ${item.summary}"
            holder.fix.visibility = if (item.action == null) View.GONE else View.VISIBLE
            holder.fix.text = when (item.action) {
                HealthCheckAction.GRANT_VPN_PERMISSION -> "Grant VPN Permission"
                HealthCheckAction.GRANT_USAGE_ACCESS -> "Grant Usage Access"
                HealthCheckAction.GRANT_OVERLAY_PERMISSION -> "Grant Overlay Permission"
                HealthCheckAction.GRANT_NOTIFICATIONS -> "Request Notifications"
                HealthCheckAction.ENABLE_PROTECTION -> "Enable Protection"
                HealthCheckAction.MANAGE_PROTECTED_APPS -> "Manage Protected Apps"
                HealthCheckAction.ENABLE_START_ON_BOOT -> "Enable Start on Boot"
                HealthCheckAction.SELECT_VPN_APP -> "Select VPN App"
                HealthCheckAction.OPEN_INSTALL_PERMISSION -> "Open Install Settings"
                HealthCheckAction.OPEN_BATTERY_SETTINGS -> "Open Battery Settings"
                null -> "Fix"
            }
            holder.fix.setOnClickListener { item.action?.let(action) }
        }
        fun submit(value: List<HealthCheckResult>) { items = value; notifyDataSetChanged() }
    }
}
