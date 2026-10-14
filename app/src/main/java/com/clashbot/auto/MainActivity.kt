package com.clashbot.auto

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvLastLog: TextView
    private lateinit var btnToggleBot: Button
    private lateinit var btnAccessibilitySettings: Button
    private lateinit var btnOverlaySettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvLastLog = findViewById(R.id.tvLastLog)
        btnToggleBot = findViewById(R.id.btnToggleBot)
        btnAccessibilitySettings = findViewById(R.id.btnAccessibilitySettings)
        btnOverlaySettings = findViewById(R.id.btnOverlaySettings)

        btnToggleBot.setOnClickListener {
            val service = BotAccessibilityService.instance
            if (service == null || !isAccessibilityServiceEnabled()) {
                Toast.makeText(
                    this,
                    "Önce Erişilebilirlik Servisini etkinleştirin.",
                    Toast.LENGTH_SHORT
                ).show()
                openAccessibilitySettings()
                return@setOnClickListener
            }

            service.toggleBot()
            refreshUi()
        }

        btnAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }

        btnOverlaySettings.setOnClickListener {
            openOverlaySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        BotAccessibilityService.statusListener = { isRunning, status ->
            runOnUiThread {
                updateBotButton(isRunning)
                tvLastLog.text = "Durum: $status"
            }
        }
        refreshUi()
    }

    override fun onPause() {
        BotAccessibilityService.statusListener = null
        super.onPause()
    }

    private fun refreshUi() {
        val enabled = isAccessibilityServiceEnabled()
        val service = BotAccessibilityService.instance
        val running = service?.isBotRunning() == true

        if (enabled && service != null) {
            tvServiceStatus.text = getString(R.string.status_service_enabled)
            tvServiceStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            tvServiceStatus.text = getString(R.string.status_service_disabled)
            tvServiceStatus.setTextColor(Color.parseColor("#EF4444"))
        }

        tvLastLog.text = "Durum: ${BotAccessibilityService.lastStatusText}"
        updateBotButton(running)
    }

    private fun updateBotButton(isRunning: Boolean) {
        if (isRunning) {
            btnToggleBot.text = getString(R.string.btn_stop)
            btnToggleBot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        } else {
            btnToggleBot.text = getString(R.string.btn_start)
            btnToggleBot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo?.serviceInfo?.packageName == packageName &&
                it.resolveInfo?.serviceInfo?.name == BotAccessibilityService::class.java.name
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Throwable) {
        }
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}
