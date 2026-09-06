package com.tunnelguard.app

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class ProfileAutomationActivity : AppCompatActivity() {
    private lateinit var config: TunnelGuardConfig
    private lateinit var rules: LinearLayout
    private lateinit var toggle: CheckBox
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setContentView(R.layout.activity_profile_automation)
        config = TunnelGuardConfig(this); rules = findViewById(R.id.automation_rules)
        toggle = findViewById(R.id.automation_toggle); status = findViewById(R.id.automation_status)
        findViewById<LinearLayout>(R.id.automation_toggle_row).setOnClickListener {
            config.setAutomaticProfileSwitchingEnabled(!config.isAutomaticProfileSwitchingEnabled()); render()
        }
        findViewById<Button>(R.id.automation_add).setOnClickListener { edit(null) }
        findViewById<Button>(R.id.automation_back).setOnClickListener { finish() }
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        toggle.isChecked = config.isAutomaticProfileSwitchingEnabled(); rules.removeAllViews()
        val profiles = config.getProfiles(); val all = config.getProfileSwitchRules()
        val active = profiles.find { it.id == config.getSelectedProfileId() }?.name ?: "Invalid"
        val last = config.getLastAutomaticProfileSwitch().let { if (it == 0L) "Never" else DateFormat.getDateTimeInstance().format(Date(it)) }
        status.text = "Active Profile: $active\nSelected By: ${config.getProfileSelectionSource()}\nLast Automatic Switch: $last"
        all.forEachIndexed { index, rule ->
            val target = profiles.find { it.id == rule.profileId }?.name ?: "Target profile no longer exists"
            val button = Button(this).apply {
                isAllCaps = false; isFocusable = true
                text = "${index + 1}. ${rule.condition.label} → $target\n${if (rule.enabled) "Enabled" else "Disabled"} — Select to manage"
                contentDescription = text; setOnClickListener { actions(rule, index, all) }
            }
            rules.addView(button, LinearLayout.LayoutParams(-1, -2))
        }
    }

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

    private fun edit(existing: ProfileSwitchRule?) {
        val base = existing ?: ProfileSwitchRule("rule_${UUID.randomUUID()}", ProfileRuleCondition.WIFI_CONNECTED, config.getDefaultProfileId(), true, config.getProfileSwitchRules().size)
        val list = config.getProfileSwitchRules().toMutableList().apply { add(base) }
        chooseCondition(base, list.lastIndex, list, thenProfile = true)
    }

    private fun chooseCondition(rule: ProfileSwitchRule, index: Int, list: MutableList<ProfileSwitchRule>, thenProfile: Boolean = false) {
        val values = ProfileRuleCondition.values()
        AlertDialog.Builder(this).setTitle("Condition").setItems(values.map { it.label }.toTypedArray()) { _, selected ->
            list[index] = rule.copy(condition = values[selected])
            if (thenProfile) chooseProfile(list[index], index, list) else saveOrdered(list)
        }.show()
    }

    private fun chooseProfile(rule: ProfileSwitchRule, index: Int, list: MutableList<ProfileSwitchRule>) {
        val profiles = config.getProfiles()
        AlertDialog.Builder(this).setTitle("Target profile").setItems(profiles.map { it.name }.toTypedArray()) { _, selected ->
            list[index] = rule.copy(profileId = profiles[selected].id); saveOrdered(list)
        }.show()
    }

    private fun saveOrdered(list: List<ProfileSwitchRule>) { config.saveProfileSwitchRules(list.mapIndexed { i, r -> r.copy(priority = i) }); render() }
}
