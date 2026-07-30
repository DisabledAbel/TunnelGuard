package com.tunnelguard.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class VpnWarningActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig
    private var targetPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var secondsLeft = 3

    private lateinit var ivTargetAppIcon: ImageView
    private lateinit var tvTargetAppName: TextView
    private lateinit var tvTargetAppPackage: TextView
    private lateinit var tvCountdownStatus: TextView
    private lateinit var btnOpenVpn: Button
    private lateinit var btnCancelWarning: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn_warning)

        config = TunnelGuardConfig(this)
        targetPackage = intent.getStringExtra("target_package")

        ivTargetAppIcon = findViewById(R.id.iv_target_app_icon)
        tvTargetAppName = findViewById(R.id.tv_target_app_name)
        tvTargetAppPackage = findViewById(R.id.tv_target_app_package)
        tvCountdownStatus = findViewById(R.id.tv_countdown_status)
        btnOpenVpn = findViewById(R.id.btn_open_vpn)
        btnCancelWarning = findViewById(R.id.btn_cancel_warning)

        btnOpenVpn.requestFocus()

        setupAppDetails()
        setupListeners()
        startAutoConnectFlow()
    }

    private fun setupAppDetails() {
        val pkg = targetPackage ?: return
        tvTargetAppPackage.text = pkg
        try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            tvTargetAppName.text = pm.getApplicationLabel(appInfo).toString()
            ivTargetAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
        } catch (e: Exception) {
            tvTargetAppName.text = "Unknown Application"
            ivTargetAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        val vpnAppChoice = config.getVpnAppOfChoice()
        if (vpnAppChoice != null) {
            try {
                val pm = packageManager
                val vpnAppInfo = pm.getApplicationInfo(vpnAppChoice, 0)
                btnOpenVpn.text = "Open ${pm.getApplicationLabel(vpnAppInfo)}"
            } catch (e: Exception) {
                btnOpenVpn.text = "Open VPN App ($vpnAppChoice)"
            }
        } else {
            btnOpenVpn.text = "Open VPN Settings"
        }
    }

    private fun setupListeners() {
        btnOpenVpn.setOnClickListener {
            cancelCountdown()
            redirectAndFinish()
        }

        btnCancelWarning.setOnClickListener {
            cancelCountdown()
            finish()
        }
    }

    private fun startAutoConnectFlow() {
        if (config.isSimulatedVpnEnabled()) {
            tvCountdownStatus.text = "Simulation Mode: Trying to auto-connect VPN..."
            // In simulation mode, we simulate automatic connection
            val runnable = Runnable {
                config.setVPNState(VPNState.CONNECTED)
                config.addLog("Auto-connected simulated VPN for $targetPackage")

                // Notify VpnService to update routing table dynamically
                val serviceIntent = Intent(this, TunnelGuardVpnService::class.java).apply {
                    action = TunnelGuardVpnService.ACTION_UPDATE
                }
                startService(serviceIntent)

                Toast.makeText(this, "VPN simulated successfully connected!", Toast.LENGTH_SHORT).show()

                // Launch target application now that VPN is secured
                launchTargetAppAndFinish()
            }
            countdownRunnable = runnable
            handler.postDelayed(runnable, 2000)
        } else {
            // Real VPN connection cannot be programmatically connected, so we countdown to redirect
            secondsLeft = 3
            updateCountdownText()

            val runnable = object : Runnable {
                override fun run() {
                    secondsLeft--
                    if (secondsLeft <= 0) {
                        tvCountdownStatus.text = "Redirecting to VPN app of choice..."
                        redirectAndFinish()
                    } else {
                        updateCountdownText()
                        handler.postDelayed(this, 1000)
                    }
                }
            }
            countdownRunnable = runnable
            handler.postDelayed(runnable, 1000)
        }
    }

    private fun updateCountdownText() {
        val choice = config.getVpnAppOfChoice()
        if (choice != null) {
            try {
                val pm = packageManager
                val appLabel = pm.getApplicationLabel(pm.getApplicationInfo(choice, 0))
                tvCountdownStatus.text = "Redirecting to $appLabel in $secondsLeft seconds..."
            } catch (e: Exception) {
                tvCountdownStatus.text = "Redirecting to VPN app in $secondsLeft seconds..."
            }
        } else {
            tvCountdownStatus.text = "Redirecting to VPN Settings in $secondsLeft seconds..."
        }
    }

    private fun cancelCountdown() {
        countdownRunnable?.let {
            handler.removeCallbacks(it)
        }
        countdownRunnable = null
    }

    private fun launchTargetAppAndFinish() {
        val pkg = targetPackage ?: return
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                startActivity(intent)
            }
        } catch (e: Exception) {
            config.addLog("Failed to launch target application: ${e.message}")
        }
        finish()
    }

    private fun redirectAndFinish() {
        val vpnAppChoice = config.getVpnAppOfChoice()
        if (vpnAppChoice != null) {
            try {
                config.addLog("Redirecting to chosen VPN app: $vpnAppChoice")
                val intent = packageManager.getLaunchIntentForPackage(vpnAppChoice)
                if (intent != null) {
                    startActivity(intent)
                    finish()
                    return
                }
            } catch (e: Exception) {
                config.addLog("Error launching VPN App of Choice: ${e.message}")
            }
        }

        // Fallback to standard Android VPN settings
        try {
            config.addLog("Redirecting to Android system VPN settings.")
            val intent = Intent("android.net.vpn.SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            config.addLog("Failed to open Android VPN Settings: ${e.message}")
            Toast.makeText(this, "Could not open VPN settings.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        cancelCountdown()
        super.onDestroy()
    }
}
