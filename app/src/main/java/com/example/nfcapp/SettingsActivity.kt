package com.example.nfcapp

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Simple settings screen that lets the user override WiFi and WebSocket connection defaults.
 * Clearing a field resets that value to the BuildConfig default on next use.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var appPreferences: AppPreferences

    private lateinit var editWifiSsid: EditText
    private lateinit var editWifiPassword: EditText
    private lateinit var editWsIp: EditText
    private lateinit var editWsPort: EditText
    private lateinit var buttonSave: Button
    private lateinit var buttonResetDefaults: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        title = getString(R.string.title_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        appPreferences = AppPreferences.getInstance(this)

        editWifiSsid = findViewById(R.id.editWifiSsid)
        editWifiPassword = findViewById(R.id.editWifiPassword)
        editWsIp = findViewById(R.id.editWsIp)
        editWsPort = findViewById(R.id.editWsPort)
        buttonSave = findViewById(R.id.buttonSave)
        buttonResetDefaults = findViewById(R.id.buttonResetDefaults)

        loadCurrentValues()

        buttonSave.setOnClickListener { saveSettings() }
        buttonResetDefaults.setOnClickListener { resetDefaults() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadCurrentValues() {
        // Show current effective values; leave blank if it's the default (hint shows default)
        editWifiSsid.setText(appPreferences.wifiSsidOverride ?: "")
        editWifiPassword.setText(appPreferences.wifiPasswordOverride ?: "")
        editWsIp.setText(appPreferences.wsIpOverride ?: "")
        editWsPort.setText(appPreferences.wsPortOverride ?: "")

        // Set hints showing the default values
        editWifiSsid.hint = getString(R.string.hint_default_value, AppPreferences.DEFAULT_WIFI_SSID)
        editWifiPassword.hint = getString(R.string.hint_default_value, AppPreferences.DEFAULT_WIFI_PASSWORD)
        editWsIp.hint = getString(R.string.hint_default_value, AppPreferences.DEFAULT_WS_IP)
        editWsPort.hint = getString(R.string.hint_default_value, AppPreferences.DEFAULT_WS_PORT)
    }

    private fun saveSettings() {
        // Validate port if provided
        val portText = editWsPort.text.toString().trim()
        if (portText.isNotBlank()) {
            val port = portText.toIntOrNull()
            if (port == null || port !in 1..65535) {
                editWsPort.error = getString(R.string.settings_port_invalid)
                editWsPort.requestFocus()
                return
            }
        }

        appPreferences.wifiSsidOverride = editWifiSsid.text.toString().ifBlank { null }
        appPreferences.wifiPasswordOverride = editWifiPassword.text.toString().ifBlank { null }
        appPreferences.wsIpOverride = editWsIp.text.toString().ifBlank { null }
        appPreferences.wsPortOverride = portText.ifBlank { null }

        Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun resetDefaults() {
        appPreferences.wifiSsidOverride = null
        appPreferences.wifiPasswordOverride = null
        appPreferences.wsIpOverride = null
        appPreferences.wsPortOverride = null

        editWifiSsid.setText("")
        editWifiPassword.setText("")
        editWsIp.setText("")
        editWsPort.setText("")

        Toast.makeText(this, R.string.toast_settings_reset, Toast.LENGTH_SHORT).show()
    }
}
