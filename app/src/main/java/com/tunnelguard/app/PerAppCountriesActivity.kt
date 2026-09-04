package com.tunnelguard.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings screen for app-specific VPN exit-country requirements. */
class PerAppCountriesActivity : AppCompatActivity() {
    private lateinit var config: TunnelGuardConfig
    private lateinit var list: RecyclerView
    private lateinit var emptyState: View
    private lateinit var adapter: CountryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_per_app_countries)
        config = TunnelGuardConfig(this)
        list = findViewById(R.id.rv_per_app_countries)
        emptyState = findViewById(R.id.layout_per_app_countries_empty)
        adapter = CountryAdapter(this, ::showCountryDialog)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<Button>(R.id.btn_per_app_manage_apps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_per_app_countries_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        loadProtectedApps()
    }

    private fun loadProtectedApps() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                config.getProtectedApps().map { packageName ->
                    val applicationInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
                    CountryItem(
                        applicationInfo?.loadLabel(packageManager)?.toString() ?: packageName,
                        packageName,
                        applicationInfo?.loadIcon(packageManager),
                        config.getAppVpnCountry(packageName)
                    )
                }.sortedBy { it.name.lowercase() }
            }
            adapter.update(items)
            emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            if (items.isEmpty()) findViewById<Button>(R.id.btn_per_app_manage_apps).requestFocus()
        }
    }

    private fun showCountryDialog(item: CountryItem) {
        val current = item.country
        val knownIndex = COUNTRY_CODES.indexOf(current)
        val labels = if (current != null && knownIndex < 0) COUNTRY_LABELS + "Current requirement ($current)" else COUNTRY_LABELS
        val codes = if (current != null && knownIndex < 0) COUNTRY_CODES + current else COUNTRY_CODES
        val checked = knownIndex.takeIf { it >= 0 } ?: labels.lastIndex
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.per_app_country_dialog_title, item.name))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val country = codes[which]
                applyCountryRequirement(item.packageName, country)
                item.country = country
                adapter.notifyItemChanged(adapter.indexOf(item))
                Toast.makeText(this, "${item.name}: ${labels[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun applyCountryRequirement(packageName: String, country: String?) {
        config.setAppVpnCountry(packageName, country)
        triggerPolicyUpdate()
    }

    private fun triggerPolicyUpdate() {
        if (TunnelGuardVpnService.isServiceRunning) {
            startService(Intent(this, TunnelGuardVpnService::class.java).setAction(TunnelGuardVpnService.ACTION_UPDATE))
        }
    }

    data class CountryItem(val name: String, val packageName: String, val icon: Drawable?, var country: String?)

    private class CountryAdapter(
        private val context: Context,
        private val onClick: (CountryItem) -> Unit
    ) : RecyclerView.Adapter<CountryAdapter.ViewHolder>() {
        private var items = emptyList<CountryItem>()
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_country_app_icon)
            val name: TextView = view.findViewById(R.id.tv_country_app_name)
            val packageName: TextView = view.findViewById(R.id.tv_country_app_package)
            val requirement: TextView = view.findViewById(R.id.tv_country_requirement)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(context).inflate(R.layout.item_app_country, parent, false)
        )
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            if (item.icon != null) holder.icon.setImageDrawable(item.icon) else holder.icon.setImageResource(R.drawable.ic_launcher)
            holder.name.text = item.name
            holder.packageName.text = item.packageName
            val label = countryLabel(item.country)
            holder.requirement.text = context.getString(R.string.per_app_country_current, label)
            holder.itemView.contentDescription = context.getString(R.string.per_app_country_accessibility, item.name, label)
            holder.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = items.size
        fun update(newItems: List<CountryItem>) { items = newItems; notifyDataSetChanged() }
        fun indexOf(item: CountryItem) = items.indexOf(item)
    }

    companion object {
        val COUNTRY_LABELS = arrayOf("No country requirement", "United States (US)", "United Kingdom (GB)", "Canada (CA)", "Germany (DE)", "France (FR)", "Netherlands (NL)", "Spain (ES)", "Italy (IT)", "Australia (AU)", "Japan (JP)", "Singapore (SG)")
        val COUNTRY_CODES = arrayOf<String?>(null, "US", "GB", "CA", "DE", "FR", "NL", "ES", "IT", "AU", "JP", "SG")
        fun countryLabel(code: String?): String {
            val index = COUNTRY_CODES.indexOf(code)
            return if (index >= 0) COUNTRY_LABELS[index] else code ?: COUNTRY_LABELS[0]
        }
    }
}
