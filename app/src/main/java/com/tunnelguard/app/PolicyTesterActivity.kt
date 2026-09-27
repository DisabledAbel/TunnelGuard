package com.tunnelguard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

class PolicyTesterActivity : AppCompatActivity() {
    data class LauncherApp(val packageName: String, val label: String)

    private lateinit var tester: PolicyTester
    private lateinit var apps: ListView
    private lateinit var resultView: TextView
    private lateinit var evaluatedView: TextView
    private var selected: LauncherApp? = null
    private var result: PolicyTestResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_policy_tester)
        tester = PolicyTester(this)
        apps = findViewById(R.id.policy_app_list)
        resultView = findViewById(R.id.policy_result)
        evaluatedView = findViewById(R.id.policy_evaluated)
        val launcherApps = TunnelGuardConfig(this).getAllLauncherApps().map { pkg ->
            LauncherApp(pkg, runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg))
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        apps.adapter = LauncherAppAdapter(this, launcherApps)
        apps.setOnItemClickListener { _, _, position, _ -> selected = launcherApps[position]; evaluate() }
        findViewById<Button>(R.id.btn_policy_refresh).setOnClickListener { evaluate() }
        findViewById<Button>(R.id.btn_policy_copy).setOnClickListener { copy() }
        findViewById<Button>(R.id.btn_policy_share).setOnClickListener { share() }
        findViewById<Button>(R.id.btn_policy_back).setOnClickListener { finish() }
        apps.requestFocus()
    }

    private fun evaluate() {
        val app = selected ?: return Toast.makeText(this, "Select an app first", Toast.LENGTH_SHORT).show()
        result = tester.evaluate(app.packageName).also {
            resultView.text = formatResult(it)
            evaluatedView.text = "Evaluated ${DateFormat.getDateTimeInstance().format(Date(it.evaluatedAtMillis))}"
        }
    }

    private fun formatResult(value: PolicyTestResult): String = buildString {
        appendLine(value.appLabel)
        appendLine(value.packageName)
        if (value.emergencyLock) appendLine("\nEmergency Lock: ACTIVE")
        appendLine("\nRESULT: ${value.decision.name.replace('_', ' ')}")
        appendLine("Reason: ${value.explanation}")
        appendLine("\nPOLICY BREAKDOWN")
        appendLine("Protected: ${if (value.protectedApp) "Yes" else "No"}")
        appendLine("Profile: ${value.profileName ?: "Unavailable"} — ${value.profileSource}")
        appendLine("Protected apps in profile: ${value.profileAppCount}")
        value.temporaryOverride?.let {
            val remaining = it.expiresWallTimeMs?.let { end -> " • ${((end - value.evaluatedAtMillis).coerceAtLeast(0) / 1000)} seconds remaining" }.orEmpty()
            appendLine("Temporary override: ${it.type.name.replace('_', ' ')}$remaining${if (value.overrideSuppressed) " — suppressed by Emergency Lock" else ""}")
        } ?: appendLine("Temporary override: None")
        value.effectivePolicy?.let { policy ->
            appendLine("VPN requirement: ${if (policy.requireVpn) "Required" else "Not required"} — ${policy.requirementSource.displayName()}")
            appendLine("VPN provider: ${value.providerName ?: policy.providerPackage ?: "Not configured"} — ${policy.providerSource.displayName()}")
            if (policy.providerPackage != null) appendLine("Provider installed / launchable: ${value.providerInstalled} / ${value.providerLaunchable}")
            appendLine("Required country: ${policy.requiredCountryCode} — ${policy.countrySource.displayName()}")
            appendLine("Auto-Connect: ${if (policy.autoConnect) "Enabled" else "Disabled"} — ${policy.autoConnectSource.displayName()}")
        } ?: appendLine("Effective policy: Unavailable")
        appendLine("\nCURRENT VPN OBSERVATION")
        appendLine("Upstream evaluation: ${value.upstream.displayName()}")
        appendLine("Detected country: ${value.upstream.detectedCountry() ?: "Unknown"}")
        value.notes.forEach { appendLine("Note: $it") }
    }

    private fun copy() {
        val text = result?.exportText() ?: return Toast.makeText(this, "Test an app first", Toast.LENGTH_SHORT).show()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TunnelGuard Policy Test", text))
        Toast.makeText(this, "Policy test copied", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val text = result?.exportText() ?: return Toast.makeText(this, "Test an app first", Toast.LENGTH_SHORT).show()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share policy test"))
    }

    private class LauncherAppAdapter(context: Context, values: List<LauncherApp>) :
        ArrayAdapter<LauncherApp>(context, R.layout.item_policy_app, values) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_policy_app, parent, false)
            val item = getItem(position)!!
            view.findViewById<TextView>(R.id.policy_app_label).text = item.label
            view.findViewById<TextView>(R.id.policy_app_package).text = item.packageName
            view.findViewById<ImageView>(R.id.policy_app_icon).setImageDrawable(runCatching { context.packageManager.getApplicationIcon(item.packageName) }.getOrNull())
            return view
        }
    }
}
