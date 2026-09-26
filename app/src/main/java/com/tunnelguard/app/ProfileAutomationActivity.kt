package com.tunnelguard.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class ProfileAutomationActivity : AppCompatActivity() {
    private lateinit var config: TunnelGuardConfig
    private lateinit var rules: LinearLayout
    private lateinit var toggle: CheckBox
    private lateinit var status: TextView
    private lateinit var toggleRow: LinearLayout
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }

    /**
     * Initializes the profile automation screen and configures its controls.
     *
     * @param state Previously saved activity state, if available.
     */
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setContentView(R.layout.activity_profile_automation)
        config = TunnelGuardConfig(this); rules = findViewById(R.id.automation_rules)
        toggle = findViewById(R.id.automation_toggle); status = findViewById(R.id.automation_status)
        toggleRow = findViewById(R.id.automation_toggle_row)
        toggleRow.setOnClickListener {
            val enabled = !config.isAutomaticProfileSwitchingEnabled()
            config.setAutomaticProfileSwitchingEnabled(enabled)
            if (!enabled) ProfileAutomationManager.clearManualSelectionState()
            render()
        }
        findViewById<Button>(R.id.automation_add).setOnClickListener { edit(null) }
        findViewById<Button>(R.id.automation_wifi_permission).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Wi-Fi network name access")
                .setMessage("Android requires Wi-Fi and location permission to reveal the connected network name, and Location Services must be enabled. TunnelGuard uses the name only on this device for profile rules; it does not collect coordinates or send network names anywhere.")
                .setPositiveButton("Continue") { _, _ -> permissionRequest.launch(wifiPermissions()) }
                .setNegativeButton("Not now", null).show()
        }
        findViewById<Button>(R.id.automation_back).setOnClickListener { finish() }
        render()
    }

    /**
 * Refreshes the displayed profile-switching rules and status when the activity resumes.
 */
override fun onResume() { super.onResume(); render() }

    /**
     * Refreshes the automatic profile-switching interface with the current setting, status, and rules.
     */
    private fun render() {
        toggle.isChecked = config.isAutomaticProfileSwitchingEnabled(); rules.removeAllViews()
        toggleRow.contentDescription = "Automatic Profile Switching, ${if (toggle.isChecked) "enabled" else "disabled"}. Select to toggle."
        val profiles = config.getProfiles(); val all = config.getProfileSwitchRules()
        val active = profiles.find { it.id == config.getSelectedProfileId() }?.name ?: "Invalid"
        val last = config.getLastAutomaticProfileSwitch().let { if (it == 0L) "Never" else DateFormat.getDateTimeInstance().format(Date(it)) }
        val network = currentNetworkState()
        val identity = when (network.wifiIdentity) {
            WifiIdentityStatus.KNOWN -> "Wi-Fi Network: ${network.wifiSsid}"
            WifiIdentityStatus.UNAVAILABLE -> "Wi-Fi Network: identity unavailable (generic Wi-Fi rules still work)"
            WifiIdentityStatus.NOT_WIFI -> "Wi-Fi Network: not connected"
        }
        status.text = "Active Profile: $active\nSelected By: ${config.getProfileSelectionSource()}\nTransport: ${network.transportDescription()}\n$identity\nLast Automatic Switch: $last"
        findViewById<Button>(R.id.automation_wifi_permission).visibility =
            if (hasWifiPermission()) android.view.View.GONE else android.view.View.VISIBLE
        all.forEachIndexed { index, rule ->
            val target = profiles.find { it.id == rule.profileId }?.name ?: "Target profile no longer exists"
            val button = Button(this).apply {
                isAllCaps = false; isFocusable = true
                text = "${index + 1}. ${rule.summaryCondition()} → $target\n${if (rule.enabled) "Enabled" else "Disabled"} — Select to manage"
                contentDescription = text; setOnClickListener { actions(rule, index, all) }
            }
            rules.addView(button, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * Displays actions for enabling, editing, reordering, or deleting a profile-switching rule.
     *
     * @param rule The rule to manage.
     * @param index The rule's position in the list.
     * @param all All profile-switching rules.
     */
    private fun actions(rule: ProfileSwitchRule, index: Int, all: List<ProfileSwitchRule>) {
        val labels = arrayOf(if (rule.enabled) "Disable" else "Enable", "Change condition", "Change target profile", "Move Up", "Move Down", "Delete")
        AlertDialog.Builder(this).setTitle("Manage rule").setItems(labels) { _, which ->
            val mutable = all.toMutableList()
            when (which) {
                0 -> mutable[index] = rule.copy(enabled = !rule.enabled)
                1 -> chooseCondition(rule, index, mutable)
                2 -> chooseProfile(rule, index, mutable)
                3 -> if (index > 0) { java.util.Collections.swap(mutable, index, index - 1); saveOrdered(mutable) }
                4 -> if (index < mutable.lastIndex) { java.util.Collections.swap(mutable, index, index + 1); saveOrdered(mutable) }
                5 -> { mutable.removeAt(index); saveOrdered(mutable) }
            }
            if (which == 0) saveOrdered(mutable)
        }.setNegativeButton("Cancel", null).show()
    }

    /**
     * Starts editing a profile-switching rule, creating a default rule when none is provided.
     *
     * @param existing The rule to edit, or `null` to create a new rule.
     */
    private fun edit(existing: ProfileSwitchRule?) {
        val base = existing ?: ProfileSwitchRule("rule_${UUID.randomUUID()}", ProfileRuleCondition.WIFI_CONNECTED, config.getDefaultProfileId(), true, config.getProfileSwitchRules().size)
        val list = config.getProfileSwitchRules().toMutableList().apply { add(base) }
        chooseCondition(base, list.lastIndex, list, thenProfile = true)
    }

    /**
     * Prompts the user to select a condition for a profile-switching rule.
     *
     * @param rule The rule whose condition is being changed.
     * @param index The rule's position in the mutable list.
     * @param list The list containing the rule to update.
     * @param thenProfile Whether to continue to profile selection after choosing a condition.
     */
    private fun chooseCondition(rule: ProfileSwitchRule, index: Int, list: MutableList<ProfileSwitchRule>, thenProfile: Boolean = false) {
        val values = ProfileRuleCondition.values()
        AlertDialog.Builder(this).setTitle("Condition").setItems(values.map { it.label }.toTypedArray()) { _, selected ->
            val condition = values[selected]
            val updated = rule.copy(
                condition = condition,
                networkIdentifier = if (condition == ProfileRuleCondition.WIFI_NETWORK && rule.condition == condition) rule.networkIdentifier else null
            )
            list[index] = updated
            if (updated.condition == ProfileRuleCondition.WIFI_NETWORK) {
                enterNetwork(updated, index, list, thenProfile)
            } else if (thenProfile) chooseProfile(updated, index, list) else saveOrdered(list)
        }.show()
    }

    private fun enterNetwork(rule: ProfileSwitchRule, index: Int, list: MutableList<ProfileSwitchRule>, thenProfile: Boolean) {
        val current = currentNetworkState().takeIf { it.wifiIdentity == WifiIdentityStatus.KNOWN }?.wifiSsid.orEmpty()
        val input = EditText(this).apply {
            hint = "Network name (SSID)"
            setText(normalizeSsid(rule.networkIdentifier) ?: current)
            isSingleLine = true
            isFocusableInTouchMode = true
        }
        val dialog = AlertDialog.Builder(this).setTitle("Specific Wi-Fi network")
            .setMessage(if (current.isNotBlank()) "Currently connected: $current\nYou can use it or enter another network name." else "Enter a network name. Android is not currently exposing a usable connected name.")
            .setView(input).setPositiveButton("Continue", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = normalizeSsid(input.text?.toString())
                if (!isValidSsidIdentifier(normalized)) input.error = "Enter a Wi-Fi name of at most 32 UTF-8 bytes (or 32 hexadecimal octets)" else {
                    val updated = rule.copy(networkIdentifier = normalized)
                    list[index] = updated
                    dialog.dismiss()
                    if (thenProfile) chooseProfile(updated, index, list) else saveOrdered(list)
                }
            }
        }
        dialog.show()
    }

    private fun currentNetworkState(): ProfileNetworkState {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        return ProfileNetworkStateCollector.collect(this, config, cm)
    }

    private fun wifiPermissions() = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private fun hasWifiPermission() = wifiPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Prompts the user to select the rule's target profile and saves the updated rule list.
     *
     * @param rule The rule whose target profile is being selected.
     * @param index The rule's position in the list.
     * @param list The mutable list of profile-switching rules to update.
     */
    private fun chooseProfile(rule: ProfileSwitchRule, index: Int, list: MutableList<ProfileSwitchRule>) {
        val profiles = config.getProfiles()
        AlertDialog.Builder(this).setTitle("Target profile").setItems(profiles.map { it.name }.toTypedArray()) { _, selected ->
            list[index] = rule.copy(profileId = profiles[selected].id); saveOrdered(list)
        }.show()
    }

    /**
 * Saves profile-switching rules with priorities matching their order in the list and refreshes the display.
 *
 * @param list The profile-switching rules in their desired order.
 */
private fun saveOrdered(list: List<ProfileSwitchRule>) { config.saveProfileSwitchRules(list.mapIndexed { i, r -> r.copy(priority = i) }); render() }
}
