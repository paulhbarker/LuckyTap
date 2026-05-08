package com.luckytap.app

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var editTextNumber: EditText
    private lateinit var buttonWriteNfc: MaterialButton
    private lateinit var buttonReadNfc: MaterialButton
    private lateinit var textViewStatus: TextView
    private lateinit var rootLayout: FrameLayout
    private lateinit var mainContentLayout: LinearLayout
    private lateinit var textViewSuccessNumber: TextView
    private lateinit var scanningBar: View
    private lateinit var buttonCheckInNfc: MaterialButton
    private lateinit var buttonCheckOutNfc: MaterialButton
    private lateinit var webSocketStatusIndicator: View
    private lateinit var buttonResetGame: MaterialButton
    private lateinit var buttonClearScans: MaterialButton
    private lateinit var buttonSettings: MaterialButton

    // --- Connection Card Views ---
    private lateinit var connectionCard: MaterialCardView
    private lateinit var wifiStatusDot: View
    private lateinit var wifiSpinner: ProgressBar
    private lateinit var wifiStatusValue: TextView
    private lateinit var wsStatusDot: View
    private lateinit var wsSpinner: ProgressBar
    private lateinit var wsStatusValue: TextView

    // --- NFC ---
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFiltersArray: Array<IntentFilter>? = null
    private var techListsArray: Array<Array<String>>? = null

    // --- Animation ---
    private var scanningBarAnimator: ValueAnimator? = null
    private companion object {
        const val ANIMATION_DURATION_MS = 300L
    }

    // --- Sound ---
    private var ringtone: Ringtone? = null
    private val notificationUri: Uri by lazy {
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    // --- Dependencies ---
    private val viewModel: NfcAppViewModel by viewModels()
    private lateinit var wifiController: WifiController
    private val appPreferences: AppPreferences by lazy { AppPreferences.getInstance(this) }

    // --- Activity Result Launchers ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Re-check live — the result map can return false for ACCESS_FINE_LOCATION when:
            // (a) Android 12+ user picked "Approximate" instead of "Precise" location, or
            // (b) system auto-denied without showing the dialog (permanently denied state).
            // checkSelfPermission is always the ground truth.
            val fineLocationGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED

            if (!fineLocationGranted) {
                val permanentlyDenied = !shouldShowRequestPermissionRationale(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                if (permanentlyDenied) {
                    // User selected "Don't ask again" — direct them to App Settings
                    Log.w("Permissions", "ACCESS_FINE_LOCATION permanently denied; opening App Settings.")
                    Toast.makeText(this, R.string.toast_location_permission_settings, Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.toast_location_permission_essential, Toast.LENGTH_LONG).show()
                }
            }

            if (fineLocationGranted) {
                Log.d("Permissions", "All critical permissions granted.")
                initiateWifiConnectionSequence()
            } else {
                viewModel.setWifiConnected(connected = false)
            }
        }

    private val wifiEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager?
            if (wifiManager?.isWifiEnabled == true) {
                Log.d("MainActivity", "WiFi enabled after settings panel.")
                initiateWifiConnectionSequence()
            } else {
                Log.w("MainActivity", "WiFi still not enabled.")
                Toast.makeText(this, R.string.toast_wifi_required, Toast.LENGTH_LONG).show()
                viewModel.setWifiConnected(connected = false)
            }
        }

    private val locationEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isLocationServiceEnabled()) {
                Log.d("MainActivity", "Location services enabled after settings.")
                initiateWifiConnectionSequence()
            } else {
                Log.w("MainActivity", "Location services still not enabled.")
                Toast.makeText(this, R.string.toast_location_required, Toast.LENGTH_LONG).show()
                viewModel.setWifiConnected(connected = false)
            }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onPreferencesChanged()
        }

    // --- WiFi State Receiver ---
    private var isReceiverRegistered = false
    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = IntentCompat.getParcelableExtra(
                        intent, WifiManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java
                    )
                    @Suppress("DEPRECATION")
                    if (networkInfo?.isConnected == true) {
                        val isTarget = wifiController.isCurrentlyConnectedToTarget(appPreferences.getEffectiveWifiSsid())
                        viewModel.setWifiConnected(connected = isTarget)
                    } else if (networkInfo?.isConnected == false) {
                        viewModel.setWifiConnected(connected = false)
                    }
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                        viewModel.setWifiConnected(false)
                    }
                }
            }
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @SuppressLint("WrongConstant")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiController = WifiController(this)

        initializeViews()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, R.string.toast_nfc_not_available, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupNfcForegroundDispatch()
        checkAndRequestPermissions()
        setupViewModelObservers()
        setupUiEventListeners()
        setupBackNavigation()
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFiltersArray, techListsArray)

        // Only register WiFi receiver if we have the necessary permissions
        if (arePermissionsGranted()) {
            val intentFilter = IntentFilter().apply {
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                this, wifiStateReceiver, intentFilter, RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        if (isReceiverRegistered) {
            unregisterReceiver(wifiStateReceiver)
            isReceiverRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ringtone?.stop()
        ringtone = null
        wifiController.cleanup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("NFC", "onNewIntent: ${intent.action}, state=${viewModel.uiState.value}")

        if (viewModel.uiState.value == AppUiState.SUCCESS_DISPLAY) return

        if (viewModel.uiState.value != AppUiState.SCANNING &&
            viewModel.currentNfcMode.value != NfcOperationMode.WRITE
        ) {
            viewModel.onNfcTagScanFailed(getString(R.string.nfc_not_active_mode))
            return
        }

        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        if (tag == null) {
            viewModel.onNfcTagScanFailed(getString(R.string.nfc_no_tag_in_intent))
            return
        }

        when (viewModel.currentNfcMode.value) {
            NfcOperationMode.WRITE -> handleNfcWrite(tag)
            NfcOperationMode.READ, NfcOperationMode.CHECK_IN, NfcOperationMode.CHECK_OUT -> {
                val result = NfcHelper.readFromIntent(intent)
                viewModel.onNfcTagScannedForRead(result)
            }
            NfcOperationMode.NONE -> {
                viewModel.onNfcTagScanFailed(getString(R.string.nfc_no_operation))
            }
        }
    }

    // ========================================================================
    // Setup
    // ========================================================================

    private fun initializeViews() {
        rootLayout = findViewById(R.id.rootLayout)
        mainContentLayout = findViewById(R.id.mainContentLayout)
        editTextNumber = findViewById(R.id.editTextNumber)
        buttonWriteNfc = findViewById(R.id.buttonWriteNfc)
        buttonReadNfc = findViewById(R.id.buttonReadNfc)
        textViewStatus = findViewById(R.id.textViewStatus)
        textViewSuccessNumber = findViewById(R.id.textViewSuccessNumber)
        scanningBar = findViewById(R.id.scanningBar)
        webSocketStatusIndicator = findViewById(R.id.webSocketStatusIndicator)
        buttonCheckInNfc = findViewById(R.id.buttonCheckInNfc)
        buttonCheckOutNfc = findViewById(R.id.buttonCheckOutNfc)
        buttonResetGame = findViewById(R.id.buttonResetGame)
        buttonClearScans = findViewById(R.id.buttonClearScans)
        buttonSettings = findViewById(R.id.buttonSettings)

        // Connection card
        connectionCard = findViewById(R.id.connectionCard)
        wifiStatusDot = findViewById(R.id.wifiStatusDot)
        wifiSpinner = findViewById(R.id.wifiSpinner)
        wifiStatusValue = findViewById(R.id.wifiStatusValue)
        wsStatusDot = findViewById(R.id.wsStatusDot)
        wsSpinner = findViewById(R.id.wsSpinner)
        wsStatusValue = findViewById(R.id.wsStatusValue)
    }

    @SuppressLint("WrongConstant")
    private fun setupNfcForegroundDispatch() {
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val ndefIntentFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
            ndefIntentFilter.addDataType("text/plain")
        } catch (e: IntentFilter.MalformedMimeTypeException) {
            throw RuntimeException("Failed to add Mime Type.", e)
        }
        intentFiltersArray = arrayOf(ndefIntentFilter)
        techListsArray = arrayOf(arrayOf(Ndef::class.java.name))
    }

    @SuppressLint("MissingPermission") // initiateWifiConnectionSequence() is only called after verifying all permissions via checkSelfPermission
    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET,
        )

        // API 33+: NEARBY_WIFI_DEVICES replaces the location requirement for Wi-Fi scanning.
        // Both are requested together so the user grants what the OS enforces on their version.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            Log.d("Permissions", "All permissions already granted.")
            initiateWifiConnectionSequence()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val state = viewModel.uiState.value
                    if ((state == AppUiState.SCANNING) || (state == AppUiState.SUCCESS_DISPLAY)) {
                        viewModel.onBackButtonPressed()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
    }

    // ========================================================================
    // Observers (StateFlow collection)
    // ========================================================================

    private fun setupViewModelObservers() {
        // Bridge WifiController LiveData (not a suspend collector)
        observeWifiControllerStatus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { collectUiState() }
                launch { collectNumberForSuccessDisplay() }
                launch { collectIsWriteButtonEnabled() }
                launch { collectWebSocketConnectionState() }
                launch { collectWifiConnectionState() }
                launch { collectAreEssentialConnectionsActive() }
                launch { collectConnectionStatusText() }
                launch { collectUiEvents() }
            }
        }
    }

    private suspend fun collectUiState() {
        viewModel.uiState.collect { state ->
            updateUiForState(state)
            if (state == AppUiState.SCANNING) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private suspend fun collectNumberForSuccessDisplay() {
        viewModel.numberForSuccessDisplay.collect { number ->
            if (number != null) {
                playNotificationSound()
                showSuccessNumberAnimation(number)
            }
        }
    }

    private suspend fun collectIsWriteButtonEnabled() {
        viewModel.isWriteButtonEnabled.collect { isEnabled ->
            buttonWriteNfc.isEnabled = isEnabled
        }
    }

    private suspend fun collectWebSocketConnectionState() {
        viewModel.webSocketConnectionState.collect { state ->
            when (state) {
                WebSocketConnectionState.CONNECTED -> {
                    wsSpinner.visibility = View.GONE
                    wsStatusDot.visibility = View.VISIBLE
                    wsStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                    wsStatusValue.text = getString(R.string.connection_value_connected)
                    wsStatusValue.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                }
                WebSocketConnectionState.CONNECTING, WebSocketConnectionState.CLOSING -> {
                    wsSpinner.visibility = View.VISIBLE
                    wsStatusDot.visibility = View.GONE
                    wsStatusValue.text = getString(R.string.connection_value_connecting)
                    wsStatusValue.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connecting))
                }
                WebSocketConnectionState.DISCONNECTED -> {
                    wsSpinner.visibility = View.GONE
                    wsStatusDot.visibility = View.VISIBLE
                    wsStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                    wsStatusValue.text = getString(R.string.connection_value_disconnected)
                    wsStatusValue.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                }
            }

            // Keep accessibility content description
            val descRes = when (state) {
                WebSocketConnectionState.CONNECTED -> R.string.cd_ws_status_connected
                WebSocketConnectionState.CONNECTING, WebSocketConnectionState.CLOSING -> R.string.cd_ws_status_connecting
                WebSocketConnectionState.DISCONNECTED -> R.string.cd_ws_status_disconnected
            }
            webSocketStatusIndicator.contentDescription = getString(descRes)
        }
    }

    private suspend fun collectWifiConnectionState() {
        viewModel.isWifiConnectedToTarget.collect { connected ->
            if (connected) {
                wifiSpinner.visibility = View.GONE
                wifiStatusDot.visibility = View.VISIBLE
                wifiStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                wifiStatusValue.text = getString(R.string.connection_value_connected)
                wifiStatusValue.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
            } else {
                // Show spinner when we're actively trying to connect (not in ERROR/idle)
                val isConnecting = wifiController.connectionStatus.value == WifiController.WifiConnectionState.CONNECTING
                wifiSpinner.visibility = if (isConnecting) View.VISIBLE else View.GONE
                wifiStatusDot.visibility = if (isConnecting) View.GONE else View.VISIBLE
                if (!isConnecting) {
                    wifiStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                }
                wifiStatusValue.text = if (isConnecting) getString(R.string.connection_value_connecting) else getString(R.string.connection_value_disconnected)
                wifiStatusValue.setTextColor(ContextCompat.getColor(this@MainActivity,
                    if (isConnecting) R.color.status_connecting else R.color.status_disconnected
                ))
            }
        }
    }

    /** Observe the WifiController's LiveData and bridge into the ViewModel. */
    private fun observeWifiControllerStatus() {
        wifiController.connectionStatus.observe(this) { state ->
            val isConnected = state == WifiController.WifiConnectionState.CONNECTED
            viewModel.setWifiConnected(isConnected)

            // Update connection card spinner/dot for WiFi
            when (state) {
                WifiController.WifiConnectionState.CONNECTING -> {
                    wifiSpinner.visibility = View.VISIBLE
                    wifiStatusDot.visibility = View.GONE
                    wifiStatusValue.text = getString(R.string.connection_value_connecting)
                    wifiStatusValue.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))
                }
                WifiController.WifiConnectionState.CONNECTED -> {
                    wifiSpinner.visibility = View.GONE
                    wifiStatusDot.visibility = View.VISIBLE
                    wifiStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_connected)
                    wifiStatusValue.text = getString(R.string.connection_value_connected)
                    wifiStatusValue.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
                }
                WifiController.WifiConnectionState.ERROR,
                WifiController.WifiConnectionState.DISCONNECTED -> {
                    wifiSpinner.visibility = View.GONE
                    wifiStatusDot.visibility = View.VISIBLE
                    wifiStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_disconnected)
                    wifiStatusValue.text = getString(R.string.connection_value_disconnected)
                    wifiStatusValue.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
                }
            }

            if (state == WifiController.WifiConnectionState.ERROR) {
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                if (!wifiManager.isWifiEnabled) {
                    promptEnableWifi()
                } else if (!isLocationServiceEnabled()) {
                    promptEnableLocationServices()
                }
            }
        }
    }

    private suspend fun collectAreEssentialConnectionsActive() {
        viewModel.areEssentialConnectionsActive.collect { isActive ->
            Log.i("MainActivity", "Essential connections active: $isActive")
            updateButtonVisibility(isActive)
        }
    }

    private suspend fun collectConnectionStatusText() {
        viewModel.connectionStatusText.collect { text ->
            if (text.isNotEmpty()) {
                textViewStatus.text = text
            }
        }
    }

    private suspend fun collectUiEvents() {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is UiEvent.ShowToast -> {
                    val duration = if (event.longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    Toast.makeText(this@MainActivity, event.message, duration).show()
                }
            }
        }
    }

    // ========================================================================
    // UI Event Listeners
    // ========================================================================

    private fun setupUiEventListeners() {
        editTextNumber.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    viewModel.validateInputForWrite(s.toString())
                }
            },
        )

        buttonWriteNfc.setOnClickListener {
            hideKeyboard()
            if (viewModel.uiState.value == AppUiState.SCANNING) {
                viewModel.resetToIdle()
            }
            viewModel.onWriteButtonModeSelected(
                editTextNumber.text.toString(),
                buttonWriteNfc.isEnabled,
            )
        }

        buttonReadNfc.setOnClickListener { hideKeyboard(); viewModel.onReadButtonModeSelected() }
        buttonCheckInNfc.setOnClickListener { hideKeyboard(); viewModel.onCheckInButtonModeSelected() }
        buttonCheckOutNfc.setOnClickListener { hideKeyboard(); viewModel.onCheckOutButtonModeSelected() }
        buttonResetGame.setOnClickListener { hideKeyboard(); viewModel.onResetGameButtonPressed() }
        buttonClearScans.setOnClickListener { hideKeyboard(); viewModel.onClearScansButtonPressed() }

        buttonSettings.setOnClickListener {
            hideKeyboard()
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
    }

    // ========================================================================
    // NFC
    // ========================================================================

    private fun handleNfcWrite(tag: Tag) {
        val numberToWriteStr = editTextNumber.text.toString()
        val num = numberToWriteStr.toIntOrNull()
        if (num == null || num !in 1..100) {
            viewModel.onNfcTagScannedForWrite(
                tagData = numberToWriteStr,
                success = false,
                errorMessage = getString(R.string.nfc_invalid_number_at_tap),
            )
            return
        }
        val result = NfcHelper.writeToTag(tag, numberToWriteStr)
        viewModel.onNfcTagScannedForWrite(
            tagData = numberToWriteStr,
            success = result.success,
            errorMessage = result.error,
        )
    }

    // ========================================================================
    // WiFi Connection Sequence
    // ========================================================================

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun initiateWifiConnectionSequence() {
        if (viewModel.isWifiConnectedToTarget.value) return

        if (!arePermissionsGranted()) {
            viewModel.setWifiConnected(connected = false)
            return
        }

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager?
        if (wifiManager == null || !wifiManager.isWifiEnabled) {
            promptEnableWifi()
            viewModel.setWifiConnected(connected = false)
            return
        }

        if (!isLocationServiceEnabled()) {
            promptEnableLocationServices()
            viewModel.setWifiConnected(connected = false)
            return
        }

        val ssid = viewModel.getEffectiveWifiSsidForActivity()
        val password = viewModel.getEffectiveWifiPasswordForActivity()
        Log.i("MainActivity", "Initiating WiFi connection to '$ssid'")
        wifiController.connectToWifi(applicationContext, ssid, password)
    }

    private fun promptEnableWifi() {
        Toast.makeText(this, R.string.toast_enable_wifi, Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: show the system Wi-Fi panel
            wifiEnableLauncher.launch(Intent(Settings.Panel.ACTION_WIFI))
        } else {
            // API 21–28: open the Wi-Fi settings screen directly
            wifiEnableLauncher.launch(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun promptEnableLocationServices() {
        Toast.makeText(this, R.string.toast_location_services_required, Toast.LENGTH_LONG).show()
        locationEnableLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun isLocationServiceEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            try {
                Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
            } catch (_: Settings.SettingNotFoundException) {
                false
            }
        }
    }

    private fun arePermissionsGranted(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
        )
        // On API 33+, NEARBY_WIFI_DEVICES is also required for Wi-Fi operations.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ========================================================================
    // UI State Management (consolidated visibility logic)
    // ========================================================================

    private fun updateButtonVisibility(areConnectionsActive: Boolean) {
        if (viewModel.uiState.value != AppUiState.NORMAL) return
        val actionVisibility = if (areConnectionsActive) View.VISIBLE else View.GONE
        // Primary actions
        buttonReadNfc.visibility = actionVisibility
        buttonCheckInNfc.visibility = actionVisibility
        buttonCheckOutNfc.visibility = actionVisibility
        // Check In/Out row container
        (buttonCheckInNfc.parent as? View)?.visibility = actionVisibility
        // Write section (header + divider above it + input + button)
        findViewById<View>(R.id.editTextNumberLayout).visibility = actionVisibility
        buttonWriteNfc.visibility = actionVisibility
        // Admin section
        buttonResetGame.visibility = actionVisibility
        buttonClearScans.visibility = actionVisibility
        // Admin row container
        (buttonResetGame.parent as? View)?.visibility = actionVisibility
        // Section headers and dividers — find by iterating tagged views
        mainContentLayout.findViewWithTag<View>("sectionWrite")?.visibility = actionVisibility
        mainContentLayout.findViewWithTag<View>("sectionAdmin")?.visibility = actionVisibility
        mainContentLayout.findViewWithTag<View>("dividerAdmin")?.visibility = actionVisibility
        // Connection card is always visible in NORMAL state
        connectionCard.visibility = View.VISIBLE
    }

    private fun updateUiForState(newState: AppUiState) {
        if (newState != AppUiState.SCANNING) stopScanningBarAnimation()
        if (newState != AppUiState.SUCCESS_DISPLAY) textViewSuccessNumber.visibility = View.GONE

        mainContentLayout.visibility = View.GONE
        scanningBar.visibility = View.GONE

        val targetColor = when (newState) {
            AppUiState.SCANNING -> ContextCompat.getColor(this, R.color.scanning_background_black)
            AppUiState.SUCCESS_DISPLAY -> ContextCompat.getColor(this, R.color.success_green)
            AppUiState.NORMAL -> ContextCompat.getColor(this, R.color.default_background)
        }
        animateBackgroundColor(targetColor)

        when (newState) {
            AppUiState.NORMAL -> {
                mainContentLayout.visibility = View.VISIBLE
                textViewStatus.visibility = View.VISIBLE
                // Delegate button visibility to the connection-aware method
                updateButtonVisibility(viewModel.areEssentialConnectionsActive.value)
            }
            AppUiState.SCANNING -> {
                scanningBar.visibility = View.VISIBLE
                startScanningBarAnimation()
            }
            AppUiState.SUCCESS_DISPLAY -> {
                // Handled by numberForSuccessDisplay observer
            }
        }
    }

    // ========================================================================
    // Animations & Utilities
    // ========================================================================

    private fun animateBackgroundColor(toColor: Int) {
        val fromColor = (rootLayout.background as? android.graphics.drawable.ColorDrawable)?.color
            ?: ContextCompat.getColor(this, R.color.default_background)
        ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            duration = ANIMATION_DURATION_MS
            addUpdateListener { rootLayout.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun startScanningBarAnimation() {
        stopScanningBarAnimation()
        scanningBar.visibility = View.VISIBLE
        scanningBar.translationY = 0f
        rootLayout.post {
            val screenHeight = rootLayout.height.toFloat()
            val barHeight = scanningBar.height.toFloat()
            if (screenHeight <= 0 || barHeight <= 0) return@post

            scanningBarAnimator = ValueAnimator.ofFloat(0f, screenHeight - barHeight).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { scanningBar.translationY = it.animatedValue as Float }
            }
            scanningBarAnimator?.start()
        }
    }

    private fun stopScanningBarAnimation() {
        scanningBarAnimator?.cancel()
        scanningBarAnimator = null
        scanningBar.visibility = View.GONE
    }

    private fun showSuccessNumberAnimation(number: String) {
        stopScanningBarAnimation()
        mainContentLayout.visibility = View.GONE
        scanningBar.visibility = View.GONE
        textViewSuccessNumber.text = number
        textViewSuccessNumber.contentDescription = getString(R.string.cd_scanned_number, number)
        textViewSuccessNumber.visibility = View.VISIBLE
        textViewSuccessNumber.alpha = 0f
        textViewSuccessNumber.scaleX = 0.5f
        textViewSuccessNumber.scaleY = 0.5f
        textViewSuccessNumber.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: View(this)
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun playNotificationSound() {
        try {
            ringtone?.stop()
            ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e("Sound", "Error playing notification sound", e)
        }
    }
}
