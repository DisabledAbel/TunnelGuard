package com.tunnelguard.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ProfilesActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig
    private lateinit var btnCreateProfile: Button
    private lateinit var rvProfiles: RecyclerView
    private lateinit var adapter: ProfilesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)

        config = TunnelGuardConfig(this)

        btnCreateProfile = findViewById(R.id.btn_create_profile)
        rvProfiles = findViewById(R.id.rv_profiles)

        btnCreateProfile.setOnClickListener {
            showCreateProfileDialog()
        }

        rvProfiles.layoutManager = LinearLayoutManager(this)
        adapter = ProfilesAdapter(this, emptyList()) { profile ->
            showProfileActionsDialog(profile)
        }
        rvProfiles.adapter = adapter

        btnCreateProfile.requestFocus()

        loadProfilesList()
    }

    private fun loadProfilesList() {
        val profiles = config.getProfiles()
        adapter.updateList(profiles)
    }

    private fun showCreateProfileDialog() {
        val input = EditText(this)
        input.setSingleLine()
        input.hint = "e.g., Gaming"

        AlertDialog.Builder(this)
            .setTitle("Create Custom Profile")
            .setMessage("Enter a name for the new profile:")
            .setView(input)
            .setPositiveButton("Create") { dialog, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val id = config.createProfile(name)
                    config.setSelectedProfileId(id)
                    ProfileAutomationManager.noteManualSelection(this)
                    loadProfilesList()
                    triggerVpnServiceUpdate()
                    Toast.makeText(this, "Profile '$name' created and selected", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Displays actions for selecting, setting the default, renaming, or deleting a profile.
     *
     * @param profile The profile whose available management actions are displayed.
     */
    private fun showProfileActionsDialog(profile: TunnelGuardConfig.ProtectionProfile) {
        val options = mutableListOf<String>()

        val selectIndex = 0
        options.add("Select Profile (Make Active)")

        val setDefaultIndex = 1
        options.add("Set as Default Profile")

        val vpnSettingsIndex = options.size
        options.add("VPN Settings")

        var renameIndex = -1
        var deleteIndex = -1

        if (!profile.isSystem) {
            renameIndex = options.size
            options.add("Rename Profile")
            deleteIndex = options.size
            options.add("Delete Profile")
        }

        AlertDialog.Builder(this)
            .setTitle("Manage Profile: ${profile.name}")
            .setItems(options.toTypedArray()) { dialog, which ->
                when (which) {
                    selectIndex -> {
                        config.setSelectedProfileId(profile.id)
                        ProfileAutomationManager.noteManualSelection(this)
                        Toast.makeText(this, "Selected: ${profile.name}", Toast.LENGTH_SHORT).show()
                    }
                    setDefaultIndex -> {
                        config.setDefaultProfileId(profile.id)
                        Toast.makeText(this, "Default profile: ${profile.name}", Toast.LENGTH_SHORT).show()
                    }
                    vpnSettingsIndex -> showVpnSettingsDialog(profile)
                    renameIndex -> {
                        showRenameProfileDialog(profile)
                    }
                    deleteIndex -> {
                        showDeleteConfirmDialog(profile)
                    }
                }
                loadProfilesList()
                triggerVpnServiceUpdate()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showVpnSettingsDialog(profile: TunnelGuardConfig.ProtectionProfile) {
        val items = arrayOf("VPN Requirement", "VPN Provider", "Required Country", "Auto-Connect", "Country Validation")
        AlertDialog.Builder(this).setTitle("${profile.name}: VPN Settings").setItems(items) { _, which ->
            when (which) {
                0 -> choose(profile, "VPN Requirement", arrayOf("Use Global Setting", "Required", "Not Required")) { i ->
                    profile.vpnPolicy.copy(requirement = ProfileVpnPolicy.RequirementMode.entries[i]) }
                1 -> chooseProvider(profile)
                2 -> choose(profile, "Required Country", arrayOf("Use Global Setting", "Any", "US", "CA", "GB")) { i ->
                    profile.vpnPolicy.copy(country = arrayOf(null, "ANY", "US", "CA", "GB")[i]) }
                3 -> choose(profile, "Auto-Connect", arrayOf("Use Global Setting", "Enabled", "Disabled")) { i ->
                    profile.vpnPolicy.copy(autoConnect = ProfileVpnPolicy.ToggleMode.entries[i]) }
                4 -> choose(profile, "Country Validation", arrayOf("Use Global Setting", "Required", "Disabled")) { i ->
                    profile.vpnPolicy.copy(countryValidation = ProfileVpnPolicy.ToggleMode.entries[i]) }
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun choose(profile: TunnelGuardConfig.ProtectionProfile, title: String, labels: Array<String>, update: (Int) -> ProfileVpnPolicy) {
        AlertDialog.Builder(this).setTitle(title).setItems(labels) { _, i ->
            config.setProfileVpnPolicy(profile.id, update(i)); loadProfilesList(); triggerVpnServiceUpdate()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseProvider(profile: TunnelGuardConfig.ProtectionProfile) {
        // A selectable provider must expose Android's VPN service contract and a launcher activity.
        // The generic provider adapter safely supports apps not explicitly known by the registry.
        val installed = packageManager.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .filter { it != packageName && packageManager.getLaunchIntentForPackage(it) != null }
            .distinct()
            .sortedBy { com.tunnelguard.app.vpnprovider.VpnProviderRegistry.resolve(it).getDisplayName(this).lowercase() }
        val packages = listOf<String?>(null) + installed
        val labels = listOf("Use Global VPN") + installed.map {
            "${com.tunnelguard.app.vpnprovider.VpnProviderRegistry.resolve(it).getDisplayName(this)} ($it)"
        }
        choose(profile, "VPN Provider", labels.toTypedArray()) { i -> profile.vpnPolicy.copy(providerPackage = packages[i]) }
    }

    private fun showRenameProfileDialog(profile: TunnelGuardConfig.ProtectionProfile) {
        val input = EditText(this)
        input.setSingleLine()
        input.setText(profile.name)

        AlertDialog.Builder(this)
            .setTitle("Rename Profile")
            .setMessage("Enter new name for '${profile.name}':")
            .setView(input)
            .setPositiveButton("Rename") { dialog, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    config.renameProfile(profile.id, newName)
                    loadProfilesList()
                    Toast.makeText(this, "Renamed to '$newName'", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showDeleteConfirmDialog(profile: TunnelGuardConfig.ProtectionProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete Profile")
            .setMessage("Are you sure you want to delete profile '${profile.name}'?")
            .setPositiveButton("Delete") { dialog, _ ->
                config.deleteProfile(profile.id)
                loadProfilesList()
                triggerVpnServiceUpdate()
                Toast.makeText(this, "Profile deleted", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun triggerVpnServiceUpdate() {
        if (TunnelGuardVpnService.isServiceRunning) {
            val serviceIntent = Intent(this, TunnelGuardVpnService::class.java).apply {
                action = TunnelGuardVpnService.ACTION_UPDATE
            }
            startService(serviceIntent)
        }
    }

    private class ProfilesAdapter(
        private val context: Context,
        private var items: List<TunnelGuardConfig.ProtectionProfile>,
        private val onItemClick: (TunnelGuardConfig.ProtectionProfile) -> Unit
    ) : RecyclerView.Adapter<ProfilesAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tv_profile_name)
            val details: TextView = v.findViewById(R.id.tv_profile_details)
            val badges: TextView = v.findViewById(R.id.tv_profile_badges)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(context).inflate(R.layout.item_profile, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val config = TunnelGuardConfig(context)
            val item = items[position]
            holder.name.text = item.name

            val count = if (item.id == "everything") {
                "All launcher applications"
            } else {
                "${item.appPackages.size} protected apps"
            }
            holder.details.text = if (item.isSystem) "System Profile • $count" else "Custom Profile • $count"

            val isSelected = config.getSelectedProfileId() == item.id
            val isDefault = config.getDefaultProfileId() == item.id

            val badges = mutableListOf<String>()
            if (isSelected) badges.add("ACTIVE")
            if (isDefault) badges.add("DEFAULT")

            holder.badges.text = badges.joinToString(" | ")
            if (badges.isNotEmpty()) {
                holder.badges.visibility = View.VISIBLE
            } else {
                holder.badges.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size

        /**
         * Replaces the displayed profiles with a new list.
         *
         * @param newList The profiles to display.
         */
        fun updateList(newList: List<TunnelGuardConfig.ProtectionProfile>) {
            items = newList
            notifyDataSetChanged()
        }
    }
}
