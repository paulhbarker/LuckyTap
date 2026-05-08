package com.luckytap.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Encapsulates all WebSocket connection, retry, and send logic.
 * The ViewModel delegates networking to this class and observes [connectionState].
 */
class WebSocketManager(
    private val scope: CoroutineScope,
    private val getWebSocketUrl: () -> String,
) {
    companion object {
        private const val TAG = "WebSocketManager"
        private const val WS_RETRY_BASE_DELAY_MS = 1_000L
        private const val WS_RETRY_MAX_DELAY_MS = 30_000L
        private const val WS_MAX_RETRIES = 10
    }

    private val _connectionState = MutableStateFlow(WebSocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WebSocketConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var retryJob: Job? = null
    private var retryCount = 0

    fun connect(url: String) {
        retryJob?.cancel()

        if (webSocket != null &&
            (_connectionState.value == WebSocketConnectionState.CONNECTING ||
                    _connectionState.value == WebSocketConnectionState.CONNECTED) &&
            webSocket?.request()?.url?.toString() == url
        ) {
            Log.d(TAG, "Already connected/connecting to $url.")
            return
        }

        if (webSocket != null && webSocket?.request()?.url?.toString() != url) {
            Log.w(TAG, "Stale WebSocket for different URL. Closing.")
            webSocket?.close(1001, "Stale connection")
            webSocket = null
        }

        _connectionState.value = WebSocketConnectionState.CONNECTING
        Log.d(TAG, "Connecting to: $url")

        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket Connected to: ${webSocket.request().url}")
                    _connectionState.value = WebSocketConnectionState.CONNECTED
                    retryJob?.cancel()
                    retryCount = 0
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Message received: $text")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket Closing: $code / $reason")
                    _connectionState.value = WebSocketConnectionState.CLOSING
                    this@WebSocketManager.webSocket = null
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosed(webSocket, code, reason)
                    Log.d(TAG, "WebSocket Closed: $code / $reason")
                    _connectionState.value = WebSocketConnectionState.DISCONNECTED
                    this@WebSocketManager.webSocket = null
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val failedUrl = webSocket.request().url.toString()
                    Log.e(TAG, "WebSocket Error for $failedUrl: ${t.message}", t)
                    _connectionState.value = WebSocketConnectionState.DISCONNECTED
                    this@WebSocketManager.webSocket = null
                    scheduleRetry(failedUrl)
                }
            },
        )
    }

    fun send(type: String, payload: Any): Boolean {
        if (_connectionState.value == WebSocketConnectionState.DISCONNECTED) {
            connect(getWebSocketUrl())
        }

        return try {
            val jsonMessage = JSONObject().apply {
                put("type", type)
                put("payload", payload)
            }.toString()

            val success = webSocket?.send(jsonMessage) ?: false
            if (success) {
                Log.d(TAG, "Sent: $jsonMessage")
            } else {
                Log.w(TAG, "Failed to send: $jsonMessage (state=${_connectionState.value})")
                connect(getWebSocketUrl())
            }
            success
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating JSON for WebSocket", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending WebSocket message", e)
            false
        }
    }

    private fun scheduleRetry(url: String) {
        retryJob?.cancel()

        if (retryCount >= WS_MAX_RETRIES) {
            Log.w(TAG, "Max WebSocket retries ($WS_MAX_RETRIES) reached. Giving up.")
            scope.launch {
                _events.emit(UiEvent.ShowToast("WebSocket connection failed after $WS_MAX_RETRIES attempts."))
            }
            return
        }

        val delayMs = (WS_RETRY_BASE_DELAY_MS * (1L shl retryCount.coerceAtMost(5)))
            .coerceAtMost(WS_RETRY_MAX_DELAY_MS)
        retryCount++

        Log.d(TAG, "Scheduling WebSocket retry #$retryCount in ${delayMs}ms for $url")
        retryJob = scope.launch {
            delay(delayMs)
            if (_connectionState.value == WebSocketConnectionState.DISCONNECTED) {
                connect(url)
            }
        }
    }

    fun disconnect() {
        retryJob?.cancel()
        webSocket?.close(1000, "Disconnect requested")
        webSocket = null
    }

    fun shutdown() {
        disconnect()
        okHttpClient.dispatcher.executorService.shutdown()
        Log.d(TAG, "WebSocketManager shut down.")
    }
}
