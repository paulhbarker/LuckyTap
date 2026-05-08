package com.luckytap.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

class AppPreferences private constructor(context: Context) {

    companion object {
        private const val TAG = "AppPreferences"
        private const val PREFS_NAME = "nfc_app_prefs"
        private const val SECURE_PREFS_NAME = "secure_nfc_app_prefs"
        private const val KEY_WIFI_SSID_OVERRIDE = "wifi_ssid_override"
        private const val KEY_WIFI_PASSWORD_OVERRIDE = "wifi_password_override"
        private const val KEY_WS_IP_OVERRIDE = "ws_ip_override"
        private const val KEY_WS_PORT_OVERRIDE = "ws_port_override"

        val DEFAULT_WIFI_SSID: String get() = BuildConfig.DEFAULT_WIFI_SSID
        val DEFAULT_WIFI_PASSWORD: String get() = BuildConfig.DEFAULT_WIFI_PASSWORD
        val DEFAULT_WS_IP: String get() = BuildConfig.DEFAULT_WS_IP
        val DEFAULT_WS_PORT: String get() = BuildConfig.DEFAULT_WS_PORT

        @Volatile
        private var INSTANCE: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * WiFi password is stored in EncryptedSharedPreferences backed by the Android Keystore
     * (AES-256-GCM). This requires API 23+ — which matches our minSdk. If the Keystore
     * state is ever corrupted (e.g. after a factory-reset-without-wipe scenario),
     * [createEncryptedPrefs] recovers by wiping and rebuilding the key material.
     */
    private val securePreferences: SharedPreferences = createEncryptedPrefs(context)

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences failed, resetting crypto state", e)
            clearCorruptedCryptoState(context)
            buildEncryptedPrefs(context) // retry with fresh keys
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun clearCorruptedCryptoState(context: Context) {
        // Delete the encrypted prefs file from disk
        val prefsFile = File(context.filesDir.parent, "shared_prefs/$SECURE_PREFS_NAME.xml")
        if (prefsFile.exists()) {
            val deleted = prefsFile.delete()
            Log.w(TAG, "Deleted corrupted prefs file: $deleted")
        }

        // Remove the Tink master keyset from normal SharedPreferences
        context.getSharedPreferences(
            "__androidx_security_crypto_encrypted_prefs__", Context.MODE_PRIVATE
        ).edit { clear() }

        // Remove the master key alias from Android Keystore
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val alias = "_androidx_security_master_key_"
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
                Log.w(TAG, "Deleted master key alias from Keystore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Keystore alias", e)
        }
    }

    // WiFi
    var wifiSsidOverride: String?
        get() = sharedPreferences.getString(KEY_WIFI_SSID_OVERRIDE, null)
        set(value) = sharedPreferences.edit { putString(KEY_WIFI_SSID_OVERRIDE, value) }

    var wifiPasswordOverride: String?
        get() = securePreferences.getString(KEY_WIFI_PASSWORD_OVERRIDE, null)
        set(value) = securePreferences.edit { putString(KEY_WIFI_PASSWORD_OVERRIDE, value) }

    fun getEffectiveWifiSsid(): String = wifiSsidOverride ?: DEFAULT_WIFI_SSID
    fun getEffectiveWifiPassword(): String = wifiPasswordOverride ?: DEFAULT_WIFI_PASSWORD

    // WebSocket
    var wsIpOverride: String?
        get() = sharedPreferences.getString(KEY_WS_IP_OVERRIDE, null)
        set(value) = sharedPreferences.edit { putString(KEY_WS_IP_OVERRIDE, value) }

    var wsPortOverride: String?
        get() = sharedPreferences.getString(KEY_WS_PORT_OVERRIDE, null)
        set(value) = sharedPreferences.edit { putString(KEY_WS_PORT_OVERRIDE, value) }

    fun getEffectiveWsIp(): String = wsIpOverride ?: DEFAULT_WS_IP
    fun getEffectiveWsPort(): String = wsPortOverride ?: DEFAULT_WS_PORT

    fun getEffectiveWebSocketUrl(): String {
        val ip = getEffectiveWsIp()
        val port = getEffectiveWsPort()
        return "ws://$ip:$port/ws/lucky-tap"
    }
}
