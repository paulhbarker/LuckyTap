package com.example.nfcapp

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

// --- Enums ---
enum class NfcOperationMode { NONE, WRITE, READ, CHECK_IN, CHECK_OUT }
enum class AppUiState { NORMAL, SCANNING, SUCCESS_DISPLAY }
enum class WebSocketConnectionState { DISCONNECTED, CONNECTING, CONNECTED, CLOSING }

// --- Data classes ---
data class NfcScanResult(val success: Boolean, val data: String? = null, val error: String? = null)

class NfcAppViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "NfcAppViewModel"
        private const val SUCCESS_DISPLAY_DURATION_MS = 1000L
    }

    private val appPreferences = AppPreferences.getInstance(application)

    /** Convenience accessor for string resources. */
    private fun str(resId: Int): String = getApplication<Application>().getString(resId)
    private fun str(resId: Int, vararg args: Any): String = getApplication<Application>().getString(resId, *args)

    // --- One-shot UI events ---
    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    // --- WebSocket (delegated to WebSocketManager) ---
    private val webSocketManager = WebSocketManager(
        scope = viewModelScope,
        getWebSocketUrl = { appPreferences.getEffectiveWebSocketUrl() },
    )
    val webSocketConnectionState: StateFlow<WebSocketConnectionState> = webSocketManager.connectionState

    // --- NFC state ---
    private val _currentNfcMode = MutableStateFlow(NfcOperationMode.NONE)
    val currentNfcMode: StateFlow<NfcOperationMode> = _currentNfcMode.asStateFlow()

    private val _nfcStatusMessage = MutableStateFlow<String?>(null)
    val nfcStatusMessage: StateFlow<String?> = _nfcStatusMessage.asStateFlow()

    // --- UI state ---
    private val _uiState = MutableStateFlow(AppUiState.NORMAL)
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val _numberForSuccessDisplay = MutableStateFlow<String?>(null)
    val numberForSuccessDisplay: StateFlow<String?> = _numberForSuccessDisplay.asStateFlow()

    private val _isWriteButtonEnabled = MutableStateFlow(false)
    val isWriteButtonEnabled: StateFlow<Boolean> = _isWriteButtonEnabled.asStateFlow()

    // --- WiFi connection state ---
    private val _isWifiConnectedToTarget = MutableStateFlow(false)
    val isWifiConnectedToTarget: StateFlow<Boolean> = _isWifiConnectedToTarget.asStateFlow()

    fun setWifiConnected(connected: Boolean) {
        _isWifiConnectedToTarget.value = connected
    }

    // --- Combined connection status ---
    val areEssentialConnectionsActive: StateFlow<Boolean> = combine(
        _isWifiConnectedToTarget,
        webSocketConnectionState,
    ) { wifiConnected, wsState ->
        wifiConnected && wsState == WebSocketConnectionState.CONNECTED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Pre-computed connection status text for the Activity to display directly. */
    val connectionStatusText: StateFlow<String> = combine(
        areEssentialConnectionsActive,
        _isWifiConnectedToTarget,
        webSocketConnectionState,
        _nfcStatusMessage,
        _uiState,
    ) { isActive, wifiOk, wsState, nfcMsg, uiState ->
        if (isActive) {
            if (uiState == AppUiState.NORMAL) nfcMsg ?: str(R.string.status_idle) else ""
        } else {
            str(R.string.status_waiting_for_connections)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, str(R.string.status_waiting_for_connections))

    private var _currentTargetWifiSsid: String = appPreferences.getEffectiveWifiSsid()
    val currentTargetWifiSsid: String get() = _currentTargetWifiSsid

    init {
        loadAndApplyPreferences()

        // When WiFi connects, trigger WebSocket connection
        viewModelScope.launch {
            _isWifiConnectedToTarget.collect { wifiConnected ->
                if (wifiConnected && webSocketConnectionState.value != WebSocketConnectionState.CONNECTED) {
                    Log.d(TAG, "WiFi connected. Attempting WebSocket connection.")
                    webSocketManager.connect(appPreferences.getEffectiveWebSocketUrl())
                }
            }
        }

        // Forward WebSocket manager events
        viewModelScope.launch {
            webSocketManager.events.collect { event -> _uiEvents.emit(event) }
        }

        // Handle connection loss
        viewModelScope.launch {
            areEssentialConnectionsActive.collect { active ->
                if (!active) handleConnectionLoss()
            }
        }
    }

    private fun loadAndApplyPreferences() {
        _currentTargetWifiSsid = appPreferences.getEffectiveWifiSsid()
        // Only connect WebSocket if WiFi is already connected;
        // otherwise the WiFi-connected collector will trigger it.
        if (_isWifiConnectedToTarget.value) {
            val newWsUrl = appPreferences.getEffectiveWebSocketUrl()
            webSocketManager.connect(newWsUrl)
        }
    }

    fun getEffectiveWifiSsidForActivity(): String = appPreferences.getEffectiveWifiSsid()
    fun getEffectiveWifiPasswordForActivity(): String = appPreferences.getEffectiveWifiPassword()

    /** Called from SettingsActivity after saving; reloads prefs and reconnects if needed. */
    fun onPreferencesChanged() {
        loadAndApplyPreferences()
    }

    // --- UI Event Handlers ---
    fun onWriteButtonModeSelected(numberToWrite: String, currentInputIsValid: Boolean) {
        if (!currentInputIsValid) {
            _nfcStatusMessage.value = str(R.string.status_invalid_number)
            return
        }
        _currentNfcMode.value = NfcOperationMode.WRITE
        _nfcStatusMessage.value = str(R.string.status_ready_write, numberToWrite)
    }

    fun onReadButtonModeSelected() {
        _currentNfcMode.value = NfcOperationMode.READ
        _uiState.value = AppUiState.SCANNING
        _nfcStatusMessage.value = str(R.string.status_scanning_read)
    }

    fun onCheckInButtonModeSelected() {
        _currentNfcMode.value = NfcOperationMode.CHECK_IN
        _uiState.value = AppUiState.SCANNING
        _nfcStatusMessage.value = str(R.string.status_scanning_check_in)
    }

    fun onCheckOutButtonModeSelected() {
        _currentNfcMode.value = NfcOperationMode.CHECK_OUT
        _uiState.value = AppUiState.SCANNING
        _nfcStatusMessage.value = str(R.string.status_scanning_check_out)
    }

    fun validateInputForWrite(text: String) {
        val isValid = (text.toIntOrNull()?.let { it in 1..100 }) ?: false
        _isWriteButtonEnabled.value = isValid
    }

    fun onNfcTagScannedForWrite(tagData: String, success: Boolean, errorMessage: String? = null) {
        viewModelScope.launch {
            if (success) {
                _nfcStatusMessage.value = str(R.string.status_write_success, tagData)
                _uiEvents.emit(UiEvent.ShowToast(str(R.string.toast_wrote_tag, tagData)))
            } else {
                val err = errorMessage ?: "Unknown error"
                _nfcStatusMessage.value = str(R.string.status_write_error, err)
                _uiEvents.emit(UiEvent.ShowToast(str(R.string.toast_write_error, err)))
            }
            _currentNfcMode.value = NfcOperationMode.NONE
            _uiState.value = AppUiState.NORMAL
        }
    }

    fun onNfcTagScannedForRead(result: NfcScanResult) {
        if (!result.success || result.data == null) {
            val errorMsg = result.error ?: str(R.string.status_no_ndef)
            _nfcStatusMessage.value = str(R.string.status_read_error, errorMsg)
            _currentNfcMode.value = NfcOperationMode.NONE
            _uiState.value = AppUiState.NORMAL
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowToast(str(R.string.toast_read_error, errorMsg), longDuration = true))
            }
            return
        }

        _numberForSuccessDisplay.value = result.data

        val messageType = when (_currentNfcMode.value) {
            NfcOperationMode.CHECK_IN -> "nfc-check-in"
            NfcOperationMode.CHECK_OUT -> "nfc-check-out"
            NfcOperationMode.READ -> "card-scanned"
            else -> ""
        }

        val socketResult = if (messageType.isNotBlank()) {
            val payload = JSONObject().apply { put("number", result.data.toIntOrNull()) }
            webSocketManager.send(messageType, payload)
        } else {
            false
        }

        if (!socketResult) {
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowToast(str(R.string.toast_read_error_short), longDuration = true))
            }
            _nfcStatusMessage.value = str(R.string.status_read_error, result.error ?: str(R.string.status_no_ndef))
            _uiState.value = AppUiState.NORMAL
            _currentNfcMode.value = NfcOperationMode.NONE
            return
        }

        _uiState.value = AppUiState.SUCCESS_DISPLAY
        viewModelScope.launch {
            delay(SUCCESS_DISPLAY_DURATION_MS)
            _numberForSuccessDisplay.value = null
            if (areEssentialConnectionsActive.value &&
                _currentNfcMode.value != NfcOperationMode.NONE &&
                _currentNfcMode.value != NfcOperationMode.WRITE
            ) {
                _uiState.value = AppUiState.SCANNING
            } else {
                _uiState.value = AppUiState.NORMAL
                _currentNfcMode.value = NfcOperationMode.NONE
            }
        }
    }

    fun onResetGameButtonPressed() {
        if (areEssentialConnectionsActive.value) {
            webSocketManager.send("admin-reset-game", JSONObject())
            _nfcStatusMessage.value = str(R.string.status_reset_sent)
        } else {
            _nfcStatusMessage.value = str(R.string.status_reset_no_connection)
            viewModelScope.launch { _uiEvents.emit(UiEvent.ShowToast(str(R.string.toast_no_connection))) }
        }
    }

    fun onClearScansButtonPressed() {
        if (areEssentialConnectionsActive.value) {
            webSocketManager.send("admin-clear-scans", JSONObject())
            _nfcStatusMessage.value = str(R.string.status_clear_sent)
        } else {
            _nfcStatusMessage.value = str(R.string.status_clear_no_connection)
            viewModelScope.launch { _uiEvents.emit(UiEvent.ShowToast(str(R.string.toast_no_connection))) }
        }
    }

    fun onNfcTagScanFailed(reason: String) {
        _nfcStatusMessage.value = reason
        if (_uiState.value != AppUiState.SCANNING) {
            _currentNfcMode.value = NfcOperationMode.NONE
            _uiState.value = AppUiState.NORMAL
        } else {
            viewModelScope.launch { _uiEvents.emit(UiEvent.ShowToast(reason)) }
        }
    }

    fun onBackButtonPressed() {
        if (_uiState.value == AppUiState.SCANNING || _uiState.value == AppUiState.SUCCESS_DISPLAY) {
            _currentNfcMode.value = NfcOperationMode.NONE
            _uiState.value = AppUiState.NORMAL
            _nfcStatusMessage.value = str(R.string.status_scan_cancelled)
        }
    }

    fun resetToIdle() {
        _currentNfcMode.value = NfcOperationMode.NONE
        _uiState.value = AppUiState.NORMAL
        _nfcStatusMessage.value = str(R.string.status_idle)
    }

    private fun handleConnectionLoss() {
        if (_uiState.value == AppUiState.SCANNING || _uiState.value == AppUiState.SUCCESS_DISPLAY) {
            Log.d(TAG, "Connection lost during active state. Resetting to NORMAL.")
            _currentNfcMode.value = NfcOperationMode.NONE
            _uiState.value = AppUiState.NORMAL
            _nfcStatusMessage.value = str(R.string.status_connection_lost)
        }
    }

    override fun onCleared() {
        super.onCleared()
        webSocketManager.shutdown()
        Log.d(TAG, "NfcAppViewModel cleared.")
    }
}
