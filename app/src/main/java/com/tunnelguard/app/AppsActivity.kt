package com.tunnelguard.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig
    private lateinit var etSearchApps: EditText
    private lateinit var rvAppsList: RecyclerView
    private lateinit var adapter: AppsAdapter

    private var allAppsList = listOf<AppItem>()
    private var filteredList = listOf<AppItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        config = TunnelGuardConfig(this)

        etSearchApps = findViewById(R.id.et_search_apps)
        rvAppsList = findViewById(R.id.rv_apps_list)

        rvAppsList.layoutManager = LinearLayoutManager(this)
        adapter = AppsAdapter(this, emptyList(), { app, isChecked ->
            if (config.getSelectedProfileId() == "everything") {
                // Automatically switch to custom profile to allow edits
                config.setSelectedProfileId("custom")
                val allApps = config.getAllLauncherApps().toMutableSet()
                if (isChecked) {
                    allApps.add(app.packageName)
                } else {
                    allApps.remove(app.packageName)
                }
                config.setProtectedApps(allApps)
                Toast.makeText(this, "Switched to Custom profile to modify protected apps.", Toast.LENGTH_SHORT).show()
                config.addLog("Automatically switched from 'everything' to 'custom' profile for custom app selection.")
            } else {
                config.setAppProtected(app.packageName, isChecked)
            }
            config.addLog("Updated app protection: ${app.name} (${app.packageName}) -> $isChecked")

            // Notify VpnService to update routing table dynamically
            if (TunnelGuardVpnService.isServiceRunning) {
                val serviceIntent = Intent(this, TunnelGuardVpnService::class.java).apply {
                    action = TunnelGuardVpnService.ACTION_UPDATE
                }
                startService(serviceIntent)
            }
        }, ::showCountryDialog)
        rvAppsList.adapter = adapter

        // Search edit text text changed listener
        etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadInstalledApps()
    }

    /**
     * Displays a dialog for selecting the VPN country used by an app.
     *
     * @param app The app whose VPN country is being selected.
     */
    private fun showCountryDialog(app: AppItem) {
        val labels = arrayOf("Use global country", "United States (US)", "United Kingdom (GB)", "Canada (CA)", "Germany (DE)", "France (FR)", "Netherlands (NL)", "Spain (ES)", "Italy (IT)", "Australia (AU)", "Japan (JP)", "Singapore (SG)")
        val codes = arrayOf<String?>(null, "US", "GB", "CA", "DE", "FR", "NL", "ES", "IT", "AU", "JP", "SG")
        val current = config.getAppVpnCountry(app.packageName)
        val checked = codes.indexOf(current).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("VPN country for ${app.name}")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                config.setAppVpnCountry(app.packageName, codes[which])
                app.vpnCountry = codes[which]
                if (codes[which] != null) app.isProtected = true
                adapter.notifyDataSetChanged()
                if (TunnelGuardVpnService.isServiceRunning) startService(Intent(this, TunnelGuardVpnService::class.java).setAction(TunnelGuardVpnService.ACTION_UPDATE))
                Toast.makeText(this, "${app.name}: ${labels[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Loads installed launcher apps and updates the displayed list with their protection settings and VPN countries.
     */
    private fun loadInstalledApps() {
        // Offload Package Manager querying & icon loading to the IO dispatcher
        lifecycleScope.launch(Dispatchers.Main) {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val items = mutableListOf<AppItem>()

                // Query Leanback launcher apps (TV)
                val tvIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                }
                val tvApps = pm.queryIntentActivities(tvIntent, 0)

                // Query standard mobile launcher apps as fallback
                val standardIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val standardApps = pm.queryIntentActivities(standardIntent, 0)

                val processedPackages = mutableSetOf<String>()

                // 1. Process Leanback Launcher Apps
                for (resolveInfo in tvApps) {
                    val pkg = resolveInfo.activityInfo.packageName
                    if (!processedPackages.contains(pkg)) {
                        val name = resolveInfo.loadLabel(pm).toString()
                        val icon = resolveInfo.loadIcon(pm)
                        val isProtected = config.isAppProtected(pkg)
                        items.add(AppItem(name, pkg, icon, isProtected, config.getAppVpnCountry(pkg)))
                        processedPackages.add(pkg)
                    }
                }

                // 2. Process Standard Launcher Apps
                for (resolveInfo in standardApps) {
                    val pkg = resolveInfo.activityInfo.packageName
                    if (!processedPackages.contains(pkg)) {
                        val name = resolveInfo.loadLabel(pm).toString()
                        val icon = resolveInfo.loadIcon(pm)
                        val isProtected = config.isAppProtected(pkg)
                        items.add(AppItem(name, pkg, icon, isProtected, config.getAppVpnCountry(pkg)))
                        processedPackages.add(pkg)
                    }
                }

                // Sort alphabetically
                items.sortedBy { it.name.lowercase() }
            }

            // Return to Main thread to update state and update the adapter UI
            allAppsList = apps
            filterApps(etSearchApps.text.toString())
        }
    }

    private fun filterApps(query: String) {
        filteredList = if (query.isEmpty()) {
            allAppsList
        } else {
            allAppsList.filter {
                it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
        }
        adapter.updateList(filteredList)
    }

    data class AppItem(
        val name: String,
        val packageName: String,
        val icon: Drawable,
        var isProtected: Boolean,
        var vpnCountry: String?
    )

    private class AppsAdapter(
        private val context: Context,
        private var items: List<AppItem>,
        private val onToggle: (AppItem, Boolean) -> Unit,
        private val onCountryClick: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.iv_app_icon)
            val name: TextView = v.findViewById(R.id.tv_app_name)
            val pkg: TextView = v.findViewById(R.id.tv_app_package)
            val country: TextView = v.findViewById(R.id.tv_app_country)
            val checkBox: CheckBox = v.findViewById(R.id.cb_app_protect)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(context).inflate(R.layout.item_app_toggle, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.icon.setImageDrawable(item.icon)
            holder.name.text = item.name
            holder.pkg.text = item.packageName
            holder.checkBox.isChecked = item.isProtected
            holder.country.text = "VPN country: ${item.vpnCountry ?: "Global"}"
            holder.country.contentDescription = "Choose VPN country for ${item.name}. Current: ${item.vpnCountry ?: "global"}"
            holder.country.setOnClickListener { onCountryClick(item) }

            // Entire item is clickable for easy TV remote navigation
            holder.itemView.setOnClickListener {
                val newChecked = !item.isProtected
                item.isProtected = newChecked
                holder.checkBox.isChecked = newChecked
                onToggle(item, newChecked)
            }
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newList: List<AppItem>) {
            items = newList
            notifyDataSetChanged()
        }
    }
}
