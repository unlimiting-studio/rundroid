package com.example.remotecontrol.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import kotlin.math.min

class WebSocketManager(private val listener: ConnectionListener) {
    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(text: String)
        fun onBinaryMessage(bytes: ByteString)
    }

    companion object {
        private const val TAG = "WebSocketManager"
        private const val REQUEST_ID_SIZE = 36
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var shouldReconnect: Boolean = false

    @Volatile
    private var isReconnecting: Boolean = false

    @Volatile
    private var reconnectDelayMs: Long = INITIAL_BACKOFF_MS

    @Volatile
    private var isConnected: Boolean = false

    private var reconnectJob: Job? = null

    fun connect(url: String) {
        currentUrl = url
        shouldReconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false
        openWebSocket(url)
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false
        reconnectDelayMs = INITIAL_BACKOFF_MS

        webSocket?.close(1000, "Manual disconnect")
        webSocket = null
        notifyDisconnected()
        scope.cancel()
    }

    fun sendText(text: String) {
        val sent = webSocket?.send(text) ?: false
        if (!sent) {
            Log.w(TAG, "Failed to send text message")
        }
    }

    fun sendBinary(requestId: String, data: ByteArray) {
        val header = ByteArray(REQUEST_ID_SIZE) { ' '.code.toByte() }
        val requestIdBytes = requestId.toByteArray(Charsets.US_ASCII)
        System.arraycopy(requestIdBytes, 0, header, 0, min(requestIdBytes.size, REQUEST_ID_SIZE))

        val payload = ByteArray(REQUEST_ID_SIZE + data.size)
        System.arraycopy(header, 0, payload, 0, REQUEST_ID_SIZE)
        System.arraycopy(data, 0, payload, REQUEST_ID_SIZE, data.size)

        val sent = webSocket?.send(payload.toByteString()) ?: false
        if (!sent) {
            Log.w(TAG, "Failed to send binary message")
        }
    }

    private fun openWebSocket(url: String) {
        webSocket?.cancel()

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = INITIAL_BACKOFF_MS
                isReconnecting = false
                notifyConnected()
                Log.i(TAG, "WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onBinaryMessage(bytes)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@WebSocketManager.webSocket = null
                isReconnecting = false
                notifyDisconnected()
                Log.w(TAG, "WebSocket closed: $code / $reason")
                scheduleReconnect("closed")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@WebSocketManager.webSocket = null
                isReconnecting = false
                notifyDisconnected()
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                scheduleReconnect("failure")
            }
        })
    }

    private fun scheduleReconnect(reason: String) {
        val url = currentUrl
        if (!shouldReconnect || url.isNullOrBlank()) {
            return
        }

        if (isReconnecting) {
            return
        }

        isReconnecting = true
        val delayMs = reconnectDelayMs
        reconnectJob = scope.launch {
            Log.i(TAG, "Scheduling reconnect in ${delayMs}ms ($reason)")
            delay(delayMs)

            if (!shouldReconnect) {
                isReconnecting = false
                return@launch
            }

            openWebSocket(url)
            reconnectDelayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            isReconnecting = false
        }
    }

    private fun notifyConnected() {
        if (!isConnected) {
            isConnected = true
            listener.onConnected()
        }
    }

    private fun notifyDisconnected() {
        if (isConnected) {
            isConnected = false
            listener.onDisconnected()
        }
    }
}
