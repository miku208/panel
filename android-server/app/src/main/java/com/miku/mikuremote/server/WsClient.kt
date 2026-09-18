package com.miku.mikuremote.server

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client ke VPS dengan:
 * - auth token device (pesan pertama)
 * - auto-reconnect exponential backoff (1s -> 2s -> 4s ... maks 60s)
 * - heartbeat tiap 15s
 * - callback onMessage/onBinary/onState untuk service
 */
class WsClient(
    baseUrl: String,
    private val deviceToken: () -> String?,
    private val onState: (ConnState) -> Unit,
    private val onMessage: (JSONObject) -> Unit,
    private val onBinary: (ByteString) -> Unit = {},
    private val onAuthOk: (() -> Unit)? = null,
) {
    enum class ConnState { CONNECTING, ONLINE, OFFLINE }

    private val url = baseUrl.replace(Regex("^http"), "ws") + "/ws"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // koneksi panjang
        .pingInterval(20, TimeUnit.SECONDS)   // WS ping protocol-level
        .build()

    private var ws: WebSocket? = null
    private var heartbeatJob: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val stopping = AtomicBoolean(false)
    private var attempt = 0

    @Volatile var state: ConnState = ConnState.OFFLINE
        private set

    fun start() {
        stopping.set(false)
        connect()
    }

    fun stop() {
        stopping.set(true)
        heartbeatJob?.cancel(false)
        try { ws?.close(1000, "client_stop") } catch (_: Exception) {}
        ws = null
        setState(ConnState.OFFLINE)
    }

    fun send(obj: JSONObject): Boolean =
        ws?.send(obj.toString()) ?: false

    fun sendBinary(bytes: ByteArray): Boolean =
        ws?.send(bytes.toByteString()) ?: false

    private fun setState(s: ConnState) {
        state = s
        onState(s)
    }

    private fun connect() {
        if (stopping.get()) return
        setState(ConnState.CONNECTING)
        val token = deviceToken() ?: run {
            setState(ConnState.OFFLINE)
            return
        }
        val req = Request.Builder().url(url).build()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                // Auth pertama setelah terhubung.
                webSocket.send(JSONObject().apply {
                    put("type", "auth_device")
                    put("token", token)
                }.toString())
                startHeartbeat(webSocket)
                setState(ConnState.ONLINE)
                LogBuffer.log("INFO", "WebSocket connected")
                onAuthOk?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (obj.optString("type")) {
                    "auth_error" -> {
                        LogBuffer.log("ERROR", "Auth ditolak VPS: ${obj.optString("error")}")
                        // Token invalid: jangan reconnect terus-menerus.
                        stopping.set(true)
                        setState(ConnState.OFFLINE)
                    }
                    "auth_ok" -> {
                        LogBuffer.log("INFO", "Server authenticated")
                        onAuthOk?.invoke()
                    }
                    "heartbeat_ack" -> { /* koneksi sehat */ }
                    else -> onMessage(obj)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onBinary(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                heartbeatJob?.cancel(false)
                setState(ConnState.OFFLINE)
                LogBuffer.log("WARN", "Koneksi terputus: ${t.message ?: "unknown"}")
                if (!stopping.get()) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeatJob?.cancel(false)
                setState(ConnState.OFFLINE)
                if (code == 4004) LogBuffer.log("WARN", "Device di-revoke dari VPS")
                if (!stopping.get()) scheduleReconnect()
            }
        })
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel(false)
        heartbeatJob = scheduler.scheduleAtFixedRate({
            runCatching {
                webSocket.send(JSONObject().put("type", "heartbeat").toString())
            }
        }, 15, 15, TimeUnit.SECONDS)
    }

    private fun scheduleReconnect() {
        val delayMs = minOf(60_000L, 1000L * (1L shl minOf(5, attempt)).coerceAtLeast(1))
        attempt = (attempt + 1).coerceAtMost(6)
        LogBuffer.log("INFO", "Reconnect dalam ${delayMs / 1000}s")
        scheduler.schedule({ if (!stopping.get()) connect() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Paksa reconnect sekarang (mis. network callback "internet kembali").
     * Tidak dobel koneksi: socket lama ditutup dulu, attempt di-reset.
     */
    fun reconnectNow() {
        if (stopping.get()) return
        attempt = 0
        try { ws?.close(1000, "network_restored") } catch (_: Exception) {}
        connect()
    }
}
