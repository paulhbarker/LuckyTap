// file: app/src/main/java/com/example/nfcapp/WifiController.kt
package com.example.nfcapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class WifiController(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _connectionStatus = MutableLiveData<WifiConnectionState>(WifiConnectionState.DISCONNECTED)
    val connectionStatus: LiveData<WifiConnectionState> = _connectionStatus

    private var currentTargetSsid: String? = null
    private var currentNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var addedNetworkId: Int = -1 // For pre-API 29 legacy connections
    private var isReceiverRegistered = false


    enum class WifiConnectionState {
        CONNECTED, CONNECTING, DISCONNECTED, ERROR
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (WifiManager.NETWORK_STATE_CHANGED_ACTION == intent.action) {
                val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                val currentSsidInBroadcast = wifiManager.connectionInfo?.ssid?.replace("\"", "")

                if (networkInfo?.isConnected == true && networkInfo.typeName.equals("WIFI", ignoreCase = true)) {
                    Log.d("WifiControllerLegacy", "Broadcast: Connected to $currentSsidInBroadcast, target was $currentTargetSsid")
                    if (currentSsidInBroadcast == currentTargetSsid) {
                        _connectionStatus.postValue(WifiConnectionState.CONNECTED)
                    } else if (currentTargetSsid != null && _connectionStatus.value == WifiConnectionState.CONNECTING) {
                        // Connected to a different WiFi while trying to connect to targetSsid
                        Log.w("WifiControllerLegacy", "Connected to $currentSsidInBroadcast instead of $currentTargetSsid")
                        // This could be an error or just a slow connection to the target
                    }
                } else if (networkInfo?.detailedState == android.net.NetworkInfo.DetailedState.DISCONNECTED) {
                    Log.d("WifiControllerLegacy", "Broadcast: Disconnected from WiFi $currentSsidInBroadcast")
                    // Only update if we were attempting to connect to this SSID or were connected.
                    if (currentSsidInBroadcast == currentTargetSsid || currentTargetSsid != null) {
                        // _connectionStatus.postValue(WifiConnectionState.DISCONNECTED) // Might be too aggressive
                    }
                }
            }
        }
    }


    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun connectToWifi(ssid: String, psk: String) {
        if (!wifiManager.isWifiEnabled) {
            Log.w("WifiController", "WiFi is not enabled.")
            _connectionStatus.postValue(WifiConnectionState.ERROR)
            return
        }

        Log.i("WifiController", "Attempting to connect to SSID: $ssid")
        _connectionStatus.postValue(WifiConnectionState.CONNECTING)
        currentTargetSsid = ssid
        disconnectFromWifi() // Clean up previous connection attempts/callbacks before starting a new one

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (psk.isNotEmpty()) { // WPA2/3 Enterprise might not use PSK
                specifierBuilder.setWpa2Passphrase(psk)
            }
            val specifier = specifierBuilder.build()

            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Try to connect even if no internet initially
                .setNetworkSpecifier(specifier)
                .build()

            currentNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.i("WifiControllerQ", "NetworkCallback: AVAILABLE for $ssid. Binding process to network.")
                    connectivityManager.bindProcessToNetwork(network)
                    _connectionStatus.postValue(WifiConnectionState.CONNECTED)
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.w("WifiControllerQ", "NetworkCallback: LOST for $ssid")
                    connectivityManager.bindProcessToNetwork(null) // Unbind
                    if (currentTargetSsid == ssid) {
                        _connectionStatus.postValue(WifiConnectionState.DISCONNECTED)
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    Log.e("WifiControllerQ", "NetworkCallback: UNAVAILABLE for $ssid. Connection failed.")
                    if (currentTargetSsid == ssid) {
                        _connectionStatus.postValue(WifiConnectionState.ERROR)
                    }
                }
            }
            try {
                // Adding a timeout for the request
                connectivityManager.requestNetwork(networkRequest, currentNetworkCallback!!, 30000) // 30s
            } catch (e: SecurityException) {
                Log.e("WifiControllerQ", "SecurityException requesting network: ${e.message}", e)
                _connectionStatus.postValue(WifiConnectionState.ERROR)
            } catch (e: Exception) {
                Log.e("WifiControllerQ", "Exception requesting network: ${e.message}", e)
                _connectionStatus.postValue(WifiConnectionState.ERROR)
            }
        } else {
            // Legacy connection method (pre-API 29)
            val wifiConfig = WifiConfiguration()
            wifiConfig.SSID = "\"$ssid\""
            wifiConfig.preSharedKey = "\"$psk\""

            // Ensure receiver is registered for legacy path
            if (!isReceiverRegistered) {
                context.registerReceiver(wifiStateReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
                isReceiverRegistered = true
            }

            val existingConfig = wifiManager.configuredNetworks?.find { it.SSID == wifiConfig.SSID }
            addedNetworkId = existingConfig?.networkId ?: wifiManager.addNetwork(wifiConfig)

            if (addedNetworkId != -1) {
                Log.d("WifiControllerLegacy", "Using networkId $addedNetworkId for $ssid")
                wifiManager.disconnect() // Disconnect from current to allow connection to new one
                val enabled = wifiManager.enableNetwork(addedNetworkId, true)
                val reconnected = wifiManager.reconnect()
                Log.d("WifiControllerLegacy", "enableNetwork($addedNetworkId) success: $enabled, reconnect success: $reconnected")
                if (!enabled) { // Reconnect might not immediately reflect success
                    Log.e("WifiControllerLegacy", "Failed to enable network $ssid")
                    _connectionStatus.postValue(WifiConnectionState.ERROR)
                }
                // Connection status for legacy is primarily updated by wifiStateReceiver
            } else {
                Log.e("WifiControllerLegacy", "Failed to add/find network configuration for $ssid. Network ID was -1.")
                _connectionStatus.postValue(WifiConnectionState.ERROR)
            }
        }
    }

    fun disconnectFromWifi() {
        Log.i("WifiController", "Disconnecting from WiFi (if managed by app for SSID: $currentTargetSsid)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentNetworkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                    Log.d("WifiControllerQ", "NetworkCallback unregistered for $currentTargetSsid")
                } catch (e: Exception) { // Catch broader exceptions like IllegalArgumentException
                    Log.w("WifiControllerQ", "Error unregistering network callback: ${e.message}")
                }
                currentNetworkCallback = null
            }
            connectivityManager.bindProcessToNetwork(null) // Unbind from any specific network
        } else {
            if (addedNetworkId != -1) {
                // wifiManager.disableNetwork(addedNetworkId) // Optionally disable
                // wifiManager.removeNetwork(addedNetworkId) // Careful: this removes the config
                Log.d("WifiControllerLegacy", "Legacy: disconnecting from networkId $addedNetworkId ($currentTargetSsid)")
                wifiManager.disconnect() // General disconnect
            }
            if (isReceiverRegistered) {
                try {
                    context.unregisterReceiver(wifiStateReceiver)
                    isReceiverRegistered = false
                    Log.d("WifiControllerLegacy", "wifiStateReceiver unregistered.")
                } catch (e: IllegalArgumentException) {
                    Log.w("WifiControllerLegacy", "wifiStateReceiver not registered or already unregistered.")
                }
            }
        }
        // Only set to disconnected if we were actively managing a connection.
        // A general disconnectFromWifi might be called even if not connected to the target.
        if (_connectionStatus.value != WifiConnectionState.DISCONNECTED && currentTargetSsid != null) {
            _connectionStatus.postValue(WifiConnectionState.DISCONNECTED)
        }
        currentTargetSsid = null // Clear the target after attempting disconnect
        addedNetworkId = -1
    }

    fun isCurrentlyConnectedToTarget(targetSsidToCheck: String?): Boolean {
        if (targetSsidToCheck == null || !wifiManager.isWifiEnabled) return false

        val connectionInfo = wifiManager.connectionInfo
        val currentConnectedSsid = connectionInfo?.ssid?.replace("\"", "")
        val isConnected = connectionInfo?.networkId != -1 && currentConnectedSsid == targetSsidToCheck

        // For API Q+, the _connectionStatus driven by NetworkCallback is more reliable for the *specific* request
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return isConnected && _connectionStatus.value == WifiConnectionState.CONNECTED && this.currentTargetSsid == targetSsidToCheck
        }
        // For legacy, check connectionInfo and ensure it's the one we added/enabled
        return isConnected
    }

    fun cleanup() {
        Log.d("WifiController", "Cleanup called.")
        disconnectFromWifi() // Ensure callbacks/receivers are unregistered
    }
}