package com.tunnelguard.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class OnboardingActivity : AppCompatActivity() {

    private lateinit var config: TunnelGuardConfig
    private var fromSettings: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        config = TunnelGuardConfig(this)
        fromSettings = intent.getBooleanExtra("from_settings", false)

        val btnGetStarted = findViewById<Button>(R.id.btn_get_started)
        btnGetStarted.requestFocus()

        btnGetStarted.setOnClickListener {
            if (!fromSettings) {
                config.setOnboardingCompleted(true)
                // Launch MainActivity
                val mainIntent = Intent(this, MainActivity::class.java)
                startActivity(mainIntent)
            }
            finish()
        }
    }

    override fun onBackPressed() {
        if (fromSettings) {
            super.onBackPressed()
        } else {
            // Do not allow exiting onboarding on first-run without clicking "Get Started"
        }
    }
}
