package com.example.nfcapp

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.*
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.nio.charset.Charset
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.media.RingtoneManager
import android.net.Uri
import androidx.lifecycle.Observer
import androidx.activity.viewModels

class MainActivity : AppCompatActivity() {

    private lateinit var editTextNumber: EditText
    private lateinit var buttonWriteNfc: Button
    private lateinit var buttonReadNfc: Button
    private lateinit var textViewStatus: TextView
    private lateinit var rootLayout: FrameLayout
    private lateinit var mainContentLayout: LinearLayout
    private lateinit var progressBarScanning: ProgressBar
    private lateinit var textViewSuccessNumber: TextView
    private lateinit var scanningBar: View
    private lateinit var buttonCheckInNfc: Button
    private lateinit var buttonCheckOutNfc: Button
    private lateinit var webSocketStatusIndicator: View
    private lateinit var buttonResetGame: Button
    private lateinit var buttonClearScans: Button

    // NFC System objects (Activity still needs to manage these for foreground dispatch)
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFiltersArray: Array<IntentFilter>? = null
    private var techListsArray: Array<Array<String>>? = null

    // Animator for scanning bar (still managed by Activity for UI direct manipulation)
    private var scanningBarAnimator: ValueAnimator? = null

    // Constants for UI animation (can stay here or move to companion object if shared more)
    private val ANIMATION_DURATION_MS = 300L

    // ViewModel instance
    private val viewModel: NfcAppViewModel by viewModels()

    @SuppressLint("WrongConstant")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize all UI elements
        rootLayout = findViewById(R.id.rootLayout)
        mainContentLayout = findViewById(R.id.mainContentLayout)
        editTextNumber = findViewById(R.id.editTextNumber)
        buttonWriteNfc = findViewById(R.id.buttonWriteNfc)
        buttonReadNfc = findViewById(R.id.buttonReadNfc)
        textViewStatus = findViewById(R.id.textViewStatus)
        progressBarScanning = findViewById(R.id.progressBarScanning)
        textViewSuccessNumber = findViewById(R.id.textViewSuccessNumber)
        scanningBar = findViewById(R.id.scanningBar)
        webSocketStatusIndicator = findViewById(R.id.webSocketStatusIndicator)
        buttonCheckInNfc = findViewById(R.id.buttonCheckInNfc)
        buttonCheckOutNfc = findViewById(R.id.buttonCheckOutNfc)
        buttonResetGame = findViewById(R.id.buttonResetGame)
        buttonClearScans = findViewById(R.id.buttonClearScans)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC is not available on this device.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val ndefIntentFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
            ndefIntentFilter.addDataType("text/plain") // Or your custom mime type
        } catch (e: IntentFilter.MalformedMimeTypeException) {
            Log.e("NFCSetup", "Malformed Mime Type", e)
            throw RuntimeException("Failed to add Mime Type.", e)
        }
        intentFiltersArray = arrayOf(ndefIntentFilter)
        techListsArray = arrayOf(arrayOf(Ndef::class.java.name))
        // --- End NFC Setup ---

        setupViewModelObservers()
        setupUIEventListeners()

        // Initial validation for write button from ViewModel's state (if any)
        viewModel.isWriteButtonEnabled.value?.let { buttonWriteNfc.isEnabled = it }
    }

    private fun setupViewModelObservers() {
        viewModel.uiState.observe(this, Observer { state ->
            updateUiForState(state ?: AppUiState.NORMAL)
        })

        viewModel.currentNfcMode.observe(this, Observer { mode ->
            // Optional: Update UI based on NFC mode if not covered by general UiState
            // e.g., specific text for "Ready to Check In" vs "Ready to Read"
            // For now, most visual changes are driven by AppUiState
            Log.d("MainActivity", "NFC Mode changed to: $mode")
        })

        viewModel.nfcStatusMessage.observe(this, Observer { message ->
            if (viewModel.uiState.value == AppUiState.NORMAL) {
                if (message != null) {
                    textViewStatus.text = "Status: $message"
                    // If you also want to show the last known WS status alongside:
                    // val lastWsMsg = viewModel.webSocketStatus.value?.message ?: ""
                    // if (lastWsMsg.isNotBlank()) {
                    //    textViewStatus.append("\n$lastWsMsg")
                    // }
                } else if (viewModel.uiState.value == AppUiState.NORMAL && viewModel.currentNfcMode.value == NfcOperationMode.NONE) {
                    // If message is null and we are truly idle, reset to default idle text
                    textViewStatus.text = "Status: Idle. Select an action."
                }
            }
            // Optional: Show Toasts for important NFC messages regardless of UI state
            // message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        })

        viewModel.webSocketStatus.observe(this, Observer { update ->
            // Only update textViewStatus if in NORMAL UI mode and not during scanning/success
            if (viewModel.uiState.value == AppUiState.NORMAL) {
                // Combine with existing NFC status or replace it.
                // For simplicity, let's assume WebSocket status is an addition to any NFC status.
                // If you want it to be the *only* thing, then:
                // textViewStatus.text = update.message
                // If you want to combine, manage a base status from nfcStatusMessage
                // and append WebSocket updates carefully.

                // Let's make it so that nfcStatusMessage is primary, and wsStatus is secondary if nfcStatus is generic.
                val currentNfcStatus = viewModel.nfcStatusMessage.value
                if (currentNfcStatus != null && !currentNfcStatus.startsWith("Status: Idle")) {
                    // If there's a specific NFC operation status, append WS status to it.
                    textViewStatus.text = "Status: $currentNfcStatus\n${update.message}"
                } else {
                    // If NFC status is idle or null, WS status can be more prominent.
                    textViewStatus.text = update.message // Show only the latest WebSocket message
                }
            }
            Log.d("MainActivity", "WebSocket Update: ${update.message}") // Keep logging all updates
        })

        viewModel.numberForSuccessDisplay.observe(this, Observer { number ->
            if (number != null) {
                playNotificationSound() // Sound still triggered by Activity for context
                showSuccessNumberAnimation(number)
            }
        })

        viewModel.isWriteButtonEnabled.observe(this, Observer { isEnabled ->
            buttonWriteNfc.isEnabled = isEnabled
        })

        viewModel.webSocketConnectionState.observe(this, Observer { state ->
            val colorRes = when (state) {
                WebSocketConnectionState.CONNECTED -> R.color.ws_status_connected
                WebSocketConnectionState.CONNECTING -> R.color.ws_status_connecting
                WebSocketConnectionState.CLOSING -> R.color.ws_status_connecting // Or a specific closing color
                WebSocketConnectionState.DISCONNECTED, null -> R.color.ws_status_disconnected
            }
            webSocketStatusIndicator.setBackgroundColor(ContextCompat.getColor(this, colorRes))
            // Visibility will be handled by updateUiForState
        })
    }

    private fun setupUIEventListeners() {
        editTextNumber.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                viewModel.validateInputForWrite(s.toString())
            }
        })

        buttonWriteNfc.setOnClickListener {
            hideKeyboard()
            if (viewModel.uiState.value == AppUiState.SCANNING) { // User cancels scanning by tapping write
                viewModel.resetToIdle() // ViewModel handles resetting states
            }
            viewModel.onWriteButtonModeSelected(
                editTextNumber.text.toString(),
                buttonWriteNfc.isEnabled // Pass current validity
            )
            // Toast for "Tap card" is now set by ViewModel's nfcStatusMessage
        }

        buttonReadNfc.setOnClickListener {
            hideKeyboard()
            viewModel.onReadButtonModeSelected()
            // Toast is managed via nfcStatusMessage or can be a generic "Scanning..." from UI state
        }

        buttonCheckInNfc.setOnClickListener {
            hideKeyboard()
            viewModel.onCheckInButtonModeSelected()
        }

        buttonCheckOutNfc.setOnClickListener {
            hideKeyboard()
            viewModel.onCheckOutButtonModeSelected()
        }

        buttonResetGame.setOnClickListener {
            hideKeyboard()
            viewModel.onResetGameButtonPressed()
        }

        buttonClearScans.setOnClickListener {
            hideKeyboard()
            viewModel.onClearScansButtonPressed()
        }
    }

    private fun updateUiForState(newState: AppUiState) {
        Log.d("MainActivity", "Updating UI for state: $newState")
        // Stop animations before changing visibility
        if (newState != AppUiState.SCANNING) {
            stopScanningBarAnimation()
        }
        if (newState != AppUiState.SUCCESS_DISPLAY) {
            textViewSuccessNumber.visibility = View.GONE
        }

        webSocketStatusIndicator.visibility = if (newState == AppUiState.NORMAL) View.VISIBLE else View.GONE

        // Default visibility for elements not directly tied to a single state
        mainContentLayout.visibility = View.GONE
        scanningBar.visibility = View.GONE
        progressBarScanning.visibility = View.GONE // Hide the old spinner

        val targetBackgroundColor = when (newState) {
            AppUiState.SCANNING -> ContextCompat.getColor(this, R.color.scanning_background_black)
            AppUiState.SUCCESS_DISPLAY -> ContextCompat.getColor(this, R.color.success_green)
            AppUiState.NORMAL -> ContextCompat.getColor(this, R.color.default_background)
        }
        animateBackgroundColor(targetBackgroundColor)

        when (newState) {
            AppUiState.NORMAL -> {
                mainContentLayout.visibility = View.VISIBLE
                editTextNumber.visibility = View.VISIBLE
                buttonWriteNfc.visibility = View.VISIBLE
                buttonReadNfc.visibility = View.VISIBLE
                buttonCheckInNfc.visibility = View.VISIBLE
                buttonCheckOutNfc.visibility = View.VISIBLE
                textViewStatus.visibility = View.VISIBLE
                if (viewModel.currentNfcMode.value == NfcOperationMode.NONE) {
                    textViewStatus.text = "Status: Idle. Select an action." // Default
                }
                // nfcStatusMessage from ViewModel will override if set
            }
            AppUiState.SCANNING -> {
                // Hide all main content elements that are not part of scanning
                mainContentLayout.visibility = View.GONE // Hides children like editText, buttons, statusText
                scanningBar.visibility = View.VISIBLE
                startScanningBarAnimation()
                // Optional: A small, persistent "Scanning..." text if needed, separate from mainContentLayout
            }
            AppUiState.SUCCESS_DISPLAY -> {
                // Success number animation is triggered by observing viewModel.numberForSuccessDisplay
                // Background color already set
                mainContentLayout.visibility = View.GONE
            }
        }
    }

    private fun animateBackgroundColor(toColor: Int) {
        val fromColor = (rootLayout.background as? android.graphics.drawable.ColorDrawable)?.color ?: ContextCompat.getColor(this, R.color.default_background)
        val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor)
        colorAnimation.duration = ANIMATION_DURATION_MS
        colorAnimation.addUpdateListener { animator -> rootLayout.setBackgroundColor(animator.animatedValue as Int) }
        colorAnimation.start()
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFiltersArray, techListsArray)
        // ViewModel handles WebSocket connection internally
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("NFC", "Activity onNewIntent: ${intent.action}")

        if (viewModel.uiState.value == AppUiState.SUCCESS_DISPLAY) {
            Log.d("NFC", "NFC tap while success animation is showing. Ignoring.")
            return
        }

        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag == null) {
            viewModel.onNfcTagScanFailed("Error: No tag found in intent.")
            return
        }

        when (viewModel.currentNfcMode.value) {
            NfcOperationMode.WRITE -> {
                val numberToWriteStr = editTextNumber.text.toString() // Get from UI at the moment of tap
                // Basic validation again before actual write attempt
                val num = numberToWriteStr.toIntOrNull()
                if (num == null || num < 1 || num > 100) {
                    viewModel.onNfcTagScannedForWrite(numberToWriteStr, false, "Invalid number at time of tap.")
                    return
                }
                performNfcWrite(tag, numberToWriteStr)
            }
            NfcOperationMode.READ, NfcOperationMode.CHECK_IN, NfcOperationMode.CHECK_OUT -> {
                performNfcRead(intent)
            }
            NfcOperationMode.NONE, null -> {
                viewModel.onNfcTagScanFailed("NFC detected, but no operation selected.")
            }
        }
    }

    // --- NFC Read/Write Operations (called by onNewIntent, results sent to ViewModel) ---
    private fun performNfcWrite(tag: Tag, dataToWrite: String) {
        val textRecord = NdefRecord.createTextRecord("en", dataToWrite)
        val ndefMessage = NdefMessage(arrayOf(textRecord))
        var success = false
        var errorMessage: String? = null

        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (ndef.maxSize < ndefMessage.toByteArray().size) {
                    errorMessage = "Tag too small."
                } else {
                    ndef.writeNdefMessage(ndefMessage)
                    success = true
                }
                ndef.close()
            } else {
                errorMessage = "Tag is not NDEF formatted."
            }
        } catch (e: Exception) {
            Log.e("NFCWrite", "Error writing to NFC", e)
            errorMessage = e.message ?: "Exception during write."
        }
        viewModel.onNfcTagScannedForWrite(dataToWrite, success, errorMessage)
    }

    private fun performNfcRead(intent: Intent) {
        val rawMessages: Array<Parcelable>? = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (rawMessages != null && rawMessages.isNotEmpty()) {
            try {
                val messages = rawMessages.map { it as NdefMessage }
                val record = messages[0].records[0]
                val payload = record.payload
                val textEncoding = if ((payload[0].toInt() and 128) == 0) "UTF-8" else "UTF-16"
                val languageCodeLength = payload[0].toInt() and 63
                val text = String(payload, languageCodeLength + 1, payload.size - languageCodeLength - 1, Charset.forName(textEncoding))

                viewModel.onNfcTagScannedForRead(NfcScanResult(success = true, data = text))
                return
            } catch (e: Exception) {
                Log.e("NFCRead", "Error parsing NDEF data", e)
                viewModel.onNfcTagScannedForRead(NfcScanResult(success = false, error = "Malformed NDEF Data: ${e.message}"))
                return
            }
        }
        viewModel.onNfcTagScannedForRead(NfcScanResult(success = false, error = "No NDEF messages found on tag."))
    }

    private fun startScanningBarAnimation() {
        stopScanningBarAnimation() // Ensure any previous animation is stopped

        // Ensure the bar is visible and at the top before starting
        scanningBar.visibility = View.VISIBLE
        scanningBar.translationY = 0f

        // Get the height of the root layout to determine animation bounds
        // We need to wait for the layout to be measured if it's not already
        rootLayout.post { // Ensures we get dimensions after layout pass
            val screenHeight = rootLayout.height.toFloat()
            val barHeight = scanningBar.height.toFloat()

            if (screenHeight <= 0 || barHeight <= 0) {
                Log.e("ScanningAnim", "Cannot start animation, dimensions not ready or invalid.")
                return@post
            }

            scanningBarAnimator = ValueAnimator.ofFloat(0f, screenHeight - barHeight).apply {
                duration = 800 // Duration for one full sweep (top to bottom)
                repeatMode = ValueAnimator.REVERSE // Go up after reaching bottom
                repeatCount = ValueAnimator.INFINITE // Repeat indefinitely
                interpolator = AccelerateDecelerateInterpolator() // Smooth start and end

                addUpdateListener { animation ->
                    scanningBar.translationY = animation.animatedValue as Float
                }
            }
            scanningBarAnimator?.start()
        }
    }

    private fun stopScanningBarAnimation() {
        scanningBarAnimator?.cancel()
        scanningBarAnimator = null
        scanningBar.visibility = View.GONE // Hide bar when animation stops
    }

    private fun showSuccessNumberAnimation(number: String) { // Unchanged, triggered by ViewModel
        // Stop scanning bar animation if it was running
        stopScanningBarAnimation()

        // Ensure other elements are hidden
        mainContentLayout.visibility = View.GONE
        scanningBar.visibility = View.GONE
        // ... (other elements like editTextNumber, buttons should already be managed by updateUiForState)

        textViewSuccessNumber.text = number
        textViewSuccessNumber.visibility = View.VISIBLE
        textViewSuccessNumber.alpha = 0f
        textViewSuccessNumber.scaleX = 0.5f
        textViewSuccessNumber.scaleY = 0.5f

        // Background color animation is now triggered by updateUiForState via ViewModel
        // animateBackgroundColor(ContextCompat.getColor(this, R.color.success_green))

        textViewSuccessNumber.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                // The ViewModel will handle transitioning back to SCANNING or NORMAL
                // after its internal delay in onNfcTagScannedForRead.
            }
            .start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // Find the currently focused view, so we can grab the correct window token from it.
        var view = currentFocus
        // If no view currently has focus, create a new one, just so we can grab a window token from it
        if (view == null) {
            view = View(this)
        }
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun playNotificationSound() {
        try {
            val notificationSoundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notificationSoundUri)
            r.play()
        } catch (e: Exception) {
            Log.e("Sound", "Error playing notification sound", e)
            // Optionally, you could fall back to a Toast or log if sound fails
        }
    }

    override fun onBackPressed() {
        // Let ViewModel decide if it handles back press for its states
        if (viewModel.uiState.value == AppUiState.SCANNING || viewModel.uiState.value == AppUiState.SUCCESS_DISPLAY) {
            viewModel.onBackButtonPressed()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}