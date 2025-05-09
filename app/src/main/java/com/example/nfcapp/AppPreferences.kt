// file: app/src/main/java/com/example/nfcapp/AppPreferences.kt
package com.example.nfcapp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class AppPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "nfc_app_prefs"
        private const val SECURE_PREFS_NAME = "secure_nfc_app_prefs"
        private const val KEY_WIFI_SSID_OVERRIDE = "wifi_ssid_override"
        private const val KEY_WIFI_PASSWORD_OVERRIDE = "wifi_password_override"
        private const val KEY_WS_IP_OVERRIDE = "ws_ip_override"
        private const val KEY_WS_PORT_OVERRIDE = "ws_port_override"

        const val DEFAULT_WIFI_SSID = "REDACTED_SSID"
        const val DEFAULT_WIFI_PASSWORD = "REDACTED_PASSWORD"
        const val DEFAULT_WS_IP = "192.168.50.2" // Your existing default
        const val DEFAULT_WS_PORT = "8080"       // Your existing default
    }

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val encryptedSharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        SECURE_PREFS_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // WiFi
    var wifiSsidOverride: String?
        get() = sharedPreferences.getString(KEY_WIFI_SSID_OVERRIDE, null)
        set(value) = sharedPreferences.edit().putString(KEY_WIFI_SSID_OVERRIDE, value).apply()

    var wifiPasswordOverride: String? // Stored encrypted
        get() = encryptedSharedPreferences.getString(KEY_WIFI_PASSWORD_OVERRIDE, null)
        set(value) = encryptedSharedPreferences.edit().putString(KEY_WIFI_PASSWORD_OVERRIDE, value).apply()

    fun getEffectiveWifiSsid(): String = wifiSsidOverride ?: DEFAULT_WIFI_SSID
    fun getEffectiveWifiPassword(): String = wifiPasswordOverride ?: DEFAULT_WIFI_PASSWORD

    fun clearWifiOverrides() {
        sharedPreferences.edit().remove(KEY_WIFI_SSID_OVERRIDE).apply()
        encryptedSharedPreferences.edit().remove(KEY_WIFI_PASSWORD_OVERRIDE).apply()
    }

    // WebSocket
    var wsIpOverride: String?
        get() = sharedPreferences.getString(KEY_WS_IP_OVERRIDE, null)
        set(value) = sharedPreferences.edit().putString(KEY_WS_IP_OVERRIDE, value).apply()

    var wsPortOverride: String?
        get() = sharedPreferences.getString(KEY_WS_PORT_OVERRIDE, null)
        set(value) = sharedPreferences.edit().putString(KEY_WS_PORT_OVERRIDE, value).apply()

    fun getEffectiveWsIp(): String = wsIpOverride ?: DEFAULT_WS_IP
    fun getEffectiveWsPort(): String = wsPortOverride ?: DEFAULT_WS_PORT

    fun getEffectiveWebSocketUrl(): String {
        val ip = getEffectiveWsIp()
        val port = getEffectiveWsPort()
        return "ws://$ip:$port/ws"
    }

    fun clearWebSocketOverrides() {
        sharedPreferences.edit().remove(KEY_WS_IP_OVERRIDE).remove(KEY_WS_PORT_OVERRIDE).apply()
    }
}