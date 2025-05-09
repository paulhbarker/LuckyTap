package com.example.nfcapp

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject

// --- Enums (can be top-level or nested if preferred) ---
enum class NfcOperationMode { NONE, WRITE, READ, CHECK_IN, CHECK_OUT }
enum class AppUiState { NORMAL, SCANNING, SUCCESS_DISPLAY }
enum class WebSocketConnectionState { DISCONNECTED, CONNECTING, CONNECTED, CLOSING }

// --- Data classes for events/results (optional but good for structured data) ---
data class NfcScanResult(val success: Boolean, val data: String? = null, val error: String? = null)
data class WebSocketUpdate(val message: String, val isError: Boolean = false)

class NfcAppViewModel(application: Application) : AndroidViewModel(application) {

    // --- NFC Related State ---
    private val _currentNfcMode = MutableLiveData<NfcOperationMode>(NfcOperationMode.NONE)
    val currentNfcMode: LiveData<NfcOperationMode> = _currentNfcMode

    private val _nfcStatusMessage = MutableLiveData<String?>()
    val nfcStatusMessage: LiveData<String?> = _nfcStatusMessage // For general NFC status updates

    // --- UI State ---
    private val _uiState = MutableLiveData<AppUiState>(AppUiState.NORMAL)
    val uiState: LiveData<AppUiState> = _uiState

    private val _numberForSuccessDisplay = MutableLiveData<String?>()
    val numberForSuccessDisplay: LiveData<String?> = _numberForSuccessDisplay

    private val _isWriteButtonEnabled = MutableLiveData<Boolean>(false)
    val isWriteButtonEnabled: LiveData<Boolean> = _isWriteButtonEnabled

    private val _webSocketConnectionState = MutableLiveData<WebSocketConnectionState>(WebSocketConnectionState.DISCONNECTED)
    val webSocketConnectionState: LiveData<WebSocketConnectionState> = _webSocketConnectionState

    // --- WebSocket Related State & Logic ---
    private var webSocket: WebSocket? = null
    private val webSocketUrl = "ws://192.168.50.2:8080/ws" // Centralized
    private val okHttpClient = OkHttpClient() // Could be injected via Hilt/Koin later

    private val _webSocketStatus = MutableLiveData<WebSocketUpdate>()
    val webSocketStatus: LiveData<WebSocketUpdate> = _webSocketStatus

    // --- Constants ---
    private val SUCCESS_DISPLAY_DURATION_MS = 1000L

    init {
        connectWebSocket()
    }



    // --- UI Event Handlers (called by Activity/Fragment) ---

    fun onWriteButtonModeSelected(numberToWrite: String, currentInputIsValid: Boolean) {
        if (!currentInputIsValid) { // Double check, though UI should enforce
            _nfcStatusMessage.value = "Invalid number for writing."
            return
        }
        _currentNfcMode.value = NfcOperationMode.WRITE
        _nfcStatusMessage.value = "Ready to WRITE '$numberToWrite'. Tap NFC Card."
        // UI state (NORMAL or SCANNING) is mostly managed by the read-like operations
        // For write, we stay in NORMAL until a tag is tapped.
    }

    fun onReadButtonModeSelected() {
        _currentNfcMode.value = NfcOperationMode.READ
        _uiState.value = AppUiState.SCANNING
    }

    fun onCheckInButtonModeSelected() {
        _currentNfcMode.value = NfcOperationMode.CHECK_IN
        _uiState.value = AppUiState.SCANNING
    }

    fun onCheckOutButtonModeSelected() {
        _currentNfcMode.value = NfcOperationMode.CHECK_OUT
        _uiState.value = AppUiState.SCANNING
    }

    fun validateInputForWrite(text: String) {
        var isValid = false
        if (text.isNotBlank()) {
            try {
                val number = text.toInt()
                if (number in 1..100) {
                    isValid = true
                }
            } catch (e: NumberFormatException) { /* isValid remains false */ }
        }
        _isWriteButtonEnabled.value = isValid
    }

    fun onNfcTagScannedForWrite(tagData: String, success: Boolean, errorMessage: String? = null) {
        if (success) {
            Toast.makeText(getApplication(), "Wrote '$tagData' to NFC tag!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(getApplication(), "Write Error: $errorMessage", Toast.LENGTH_SHORT).show()
        }
        _currentNfcMode.value = NfcOperationMode.NONE // Reset mode
        _uiState.value = AppUiState.NORMAL          // Revert to normal UI
    }

    fun onNfcTagScannedForRead(result: NfcScanResult) {
        if (result.success && result.data != null) {
            _numberForSuccessDisplay.value = result.data
            _uiState.value = AppUiState.SUCCESS_DISPLAY

            val messageType = when (_currentNfcMode.value) {
                NfcOperationMode.CHECK_IN -> "nfc-check-in"
                NfcOperationMode.CHECK_OUT -> "nfc-check-out"
                NfcOperationMode.READ -> "card-scanned"
                else -> ""
            }
            if (messageType.isNotBlank()) {
                val cardPayload = JSONObject().apply { put("number", result.data.toIntOrNull()) }
                sendToWebSocket(messageType, cardPayload)
            }

            viewModelScope.launch {
                delay(SUCCESS_DISPLAY_DURATION_MS)
                _numberForSuccessDisplay.value = null // Clear it
                if (_currentNfcMode.value != NfcOperationMode.NONE && _currentNfcMode.value != NfcOperationMode.WRITE) {
                    _uiState.value = AppUiState.SCANNING // Return to scanning
                } else {
                    _uiState.value = AppUiState.NORMAL // Should not happen if scan initiated a read mode
                    _currentNfcMode.value = NfcOperationMode.NONE
                }
            }
        } else {
            _nfcStatusMessage.value = "Read Error: ${result.error ?: "No NDEF messages or parse error."}"
            _currentNfcMode.value = NfcOperationMode.NONE // Reset mode on read failure
            _uiState.value = AppUiState.NORMAL          // Revert to normal UI
        }
    }

    fun onResetGameButtonPressed() {
        sendToWebSocket("admin-reset-game", "")
    }

    fun onClearScansButtonPressed() {
        sendToWebSocket("admin-clear-scans", "")
    }

    fun onNfcTagScanFailed(reason: String) {
        _nfcStatusMessage.value = reason
        _currentNfcMode.value = NfcOperationMode.NONE
        _uiState.value = AppUiState.NORMAL
    }


    fun onBackButtonPressed() {
        if (_uiState.value == AppUiState.SCANNING || _uiState.value == AppUiState.SUCCESS_DISPLAY) {
            _currentNfcMode.value = NfcOperationMode.NONE
            _uiState.value = AppUiState.NORMAL
        } else {
            // Signal activity to perform super.onBackPressed() - could use a SingleLiveEvent for this
            // For now, the activity will handle the "else" case of its own onBackPressed
        }
    }

    fun resetToIdle() { // Called when user manually exits scanning mode (e.g. clicks Write btn)
        _currentNfcMode.value = NfcOperationMode.NONE
        _uiState.value = AppUiState.NORMAL
    }


    // --- WebSocket Logic ---
    private fun connectWebSocket() {
        if (webSocket != null || _webSocketConnectionState.value == WebSocketConnectionState.CONNECTING) {
            Log.d("ViewModelWebSocket", "Already connected or connecting.")
            return
        }

        _webSocketConnectionState.postValue(WebSocketConnectionState.CONNECTING) // Set state before connecting
        _webSocketStatus.postValue(WebSocketUpdate("WebSocket: Connecting..."))

        val request = Request.Builder().url(webSocketUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("ViewModelWebSocket", "Connected!")
                _webSocketConnectionState.postValue(WebSocketConnectionState.CONNECTED)
                _webSocketStatus.postValue(WebSocketUpdate("WebSocket: Connected"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("ViewModelWebSocket", "Message received: $text")
                _webSocketStatus.postValue(WebSocketUpdate("WebSocket Rx: $text"))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("ViewModelWebSocket", "Closing: $code / $reason")
                _webSocketConnectionState.postValue(WebSocketConnectionState.CLOSING) // Or DISCONNECTED
                _webSocketStatus.postValue(WebSocketUpdate("WebSocket: Closing"))
                this@NfcAppViewModel.webSocket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("ViewModelWebSocket", "Error: ${t.message}", t)
                _webSocketConnectionState.postValue(WebSocketConnectionState.DISCONNECTED)
                _webSocketStatus.postValue(WebSocketUpdate("WebSocket: Error - ${t.message}", true))
                this@NfcAppViewModel.webSocket = null
                // Simple retry
                viewModelScope.launch {
                    delay(5000)
                    if (_webSocketConnectionState.value == WebSocketConnectionState.DISCONNECTED) { // Only retry if still disconnected
                        connectWebSocket()
                    }
                }
            }
        })
    }

    private fun sendToWebSocket(type: String, payload: Any) {
        if (webSocket == null) {
            _webSocketStatus.value = WebSocketUpdate("WS: Not connected. Attempting to send.", true)
            // If not already connecting or connected, attempt to connect.
            // connectWebSocket() already sets state to CONNECTING.
            if (_webSocketConnectionState.value != WebSocketConnectionState.CONNECTING &&
                _webSocketConnectionState.value != WebSocketConnectionState.CONNECTED) {
                connectWebSocket()
            }
            return // Don't send if not connected
        }

        try {
            val jsonObject = JSONObject().apply {
                put("type", type)
                put("payload", payload)
            }
            val jsonMessage = jsonObject.toString()
            val success = webSocket?.send(jsonMessage)
            if (success == true) {
                Log.d("ViewModelWebSocket", "Sent: $jsonMessage")
                _webSocketStatus.value = WebSocketUpdate("WS: Sent '$jsonMessage'")
            } else {
                Log.w("ViewModelWebSocket", "Failed to send, queue full or socket closed.")
                _webSocketStatus.value = WebSocketUpdate("WS: Failed to send '$jsonMessage'", true)
            }
        } catch (e: org.json.JSONException) {
            Log.e("ViewModelWebSocket", "Error creating JSON", e)
            _webSocketStatus.value = WebSocketUpdate("Error creating JSON for WS.", true)
        }
    }

    override fun onCleared() { // Called when ViewModel is no longer used and will be destroyed
        super.onCleared()
        webSocket?.close(1000, "ViewModel Cleared")
        _webSocketConnectionState.postValue(WebSocketConnectionState.DISCONNECTED)
    }
}