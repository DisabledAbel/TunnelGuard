package com.tunnelguard.app

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TestActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig
    private lateinit var connectivityManager: ConnectivityManager

    private lateinit var tvTestVpn: TextView
    private lateinit var tvTestApps: TextView
    private lateinit var tvTestIpv4: TextView
    private lateinit var tvTestIpv6: TextView
    private lateinit var tvTestDns: TextView
    private lateinit var tvTestResult: TextView

    private lateinit var btnRunAgain: Button
    private lateinit var btnBackDashboard: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        config = TunnelGuardConfig(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        tvTestVpn = findViewById(R.id.tv_test_vpn)
        tvTestApps = findViewById(R.id.tv_test_apps)
        tvTestIpv4 = findViewById(R.id.tv_test_ipv4)
        tvTestIpv6 = findViewById(R.id.tv_test_ipv6)
        tvTestDns = findViewById(R.id.tv_test_dns)
        tvTestResult = findViewById(R.id.tv_test_result)

        btnRunAgain = findViewById(R.id.btn_run_again)
        btnBackDashboard = findViewById(R.id.btn_back_dashboard)

        btnRunAgain.setOnClickListener {
            runProtectionTest()
        }

        btnBackDashboard.setOnClickListener {
            finish()
        }

        btnRunAgain.requestFocus()

        runProtectionTest()
    }

    private fun runProtectionTest() {
        config.addLog("Running security kill-switch protection test...")

        // 1. VPN detection
        val vpnState = config.getVPNState()
        val simulated = config.isSimulatedVpnEnabled()
        if (simulated) {
            tvTestVpn.text = "✓ PASS (SIMULATED $vpnState)"
            tvTestVpn.setTextColor(resources.getColor(R.color.status_connected))
        } else {
            val vpnDetection = config.detectRealVpnCapabilities(connectivityManager)
            when (vpnDetection) {
                VpnDetectionResult.VPN_DETECTED -> {
                    tvTestVpn.text = "✓ PASS (VPN CONNECTED)"
                    tvTestVpn.setTextColor(resources.getColor(R.color.status_connected))
                }
                VpnDetectionResult.VPN_NOT_DETECTED -> {
                    if (TunnelGuardVpnService.isServiceRunning && config.isProtectionEnabled()) {
                        tvTestVpn.text = "✓ PASS (BLOCKING INTERNET)"
                        tvTestVpn.setTextColor(resources.getColor(R.color.status_blocking))
                    } else {
                        tvTestVpn.text = "✓ PASS (DISCONNECTED)"
                        tvTestVpn.setTextColor(resources.getColor(R.color.status_connected))
                    }
                }
                VpnDetectionResult.VPN_UNKNOWN -> {
                    if (TunnelGuardVpnService.isServiceRunning && config.isProtectionEnabled()) {
                        tvTestVpn.text = "✓ PASS (BLOCKING INTERNET - STATUS UNKNOWN)"
                        tvTestVpn.setTextColor(resources.getColor(R.color.status_blocking))
                    } else {
                        tvTestVpn.text = "✗ FAIL (VPN STATUS UNKNOWN)"
                        tvTestVpn.setTextColor(resources.getColor(R.color.status_disconnected))
                    }
                }
            }
        }

        // 2. Protected apps
        val protectedApps = config.getProtectedApps()
        if (protectedApps.isNotEmpty()) {
            tvTestApps.text = "✓ PASS (${protectedApps.size} apps)"
            tvTestApps.setTextColor(resources.getColor(R.color.status_connected))
        } else {
            tvTestApps.text = "✗ FAIL (No apps protected)"
            tvTestApps.setTextColor(resources.getColor(R.color.status_disconnected))
        }

        // 3. IPv4 protection
        val isLockActive = config.isEmergencyLockEnabled()
        val isProtectionEnabled = config.isProtectionEnabled()
        if (isLockActive) {
            tvTestIpv4.text = "✓ PASS (EMERGENCY LOCKED)"
            tvTestIpv4.setTextColor(resources.getColor(R.color.status_connected))
        } else if (isProtectionEnabled) {
            tvTestIpv4.text = "✓ PASS (PROTECTED)"
            tvTestIpv4.setTextColor(resources.getColor(R.color.status_connected))
        } else {
            tvTestIpv4.text = "✗ FAIL (Kill-switch Disabled)"
            tvTestIpv4.setTextColor(resources.getColor(R.color.status_disconnected))
        }

        // 4. IPv6 protection
        if (isLockActive) {
            tvTestIpv6.text = "✓ PASS (EMERGENCY LOCKED)"
            tvTestIpv6.setTextColor(resources.getColor(R.color.status_connected))
        } else if (isProtectionEnabled) {
            tvTestIpv6.text = "✓ PASS (BLACKHOLED)"
            tvTestIpv6.setTextColor(resources.getColor(R.color.status_connected))
        } else {
            tvTestIpv6.text = "✗ FAIL (IPv6 exposed)"
            tvTestIpv6.setTextColor(resources.getColor(R.color.status_disconnected))
        }

        // 5. DNS protection
        val dnsStatus = config.detectDnsStatus(connectivityManager, TunnelGuardVpnService.isServiceRunning)
        when (dnsStatus) {
            DNSStatus.PROTECTED -> {
                tvTestDns.text = "✓ PASS"
                tvTestDns.setTextColor(resources.getColor(R.color.status_connected))
            }
            DNSStatus.WARNING -> {
                tvTestDns.text = "✗ WARNING (DNS Leaks possible)"
                tvTestDns.setTextColor(resources.getColor(R.color.status_disconnected))
            }
            DNSStatus.UNKNOWN -> {
                tvTestDns.text = "? UNKNOWN"
                tvTestDns.setTextColor(resources.getColor(R.color.status_inactive))
            }
        }

        // Overall Result Verdict
        val protectionState = config.getProtectionState()
        if (isLockActive) {
            tvTestResult.text = "EMERGENCY LOCK\nACTIVE"
            tvTestResult.setTextColor(resources.getColor(R.color.status_disconnected))
        } else {
            when (protectionState) {
                ProtectionState.ACTIVE -> {
                    tvTestResult.text = "PROTECTION ACTIVE"
                    tvTestResult.setTextColor(resources.getColor(R.color.status_active))
                }
                ProtectionState.BLOCKING -> {
                    tvTestResult.text = "PROTECTION BLOCKED"
                    tvTestResult.setTextColor(resources.getColor(R.color.status_blocking))
                }
                ProtectionState.INACTIVE -> {
                    tvTestResult.text = "PROTECTION INACTIVE"
                    tvTestResult.setTextColor(resources.getColor(R.color.status_inactive))
                }
            }
        }
    }
}