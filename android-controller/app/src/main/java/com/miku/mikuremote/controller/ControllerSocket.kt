package com.miku.mikuremote.controller

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Koneksi WS controller ke VPS. Event dikonsumsi UI via [events]. */
object ControllerSocket {

    enum class Conn { DISCONNECTED, CONNECTING, CONNECTED }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(Conn.DISCONNECTED)
    val state: StateFlow<Conn> get() = _state

    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 128)
    val events: SharedFlow<JSONObject> get() = _events

    /** Frame binary (video H.264 / screenshot JPEG) dari HP server. */
    private val _binary = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val binary: SharedFlow<ByteArray> get() = _binary

    private var ws: WebSocket? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var retryJob: ScheduledFuture<*>? = null
    private val stopping = AtomicBoolean(true)
    private var attempt = 0
    private var baseUrl: String = ""
    private var token: String = ""

    fun start(url: String, userToken: String) {
        baseUrl = url
        token = userToken
        stopping.set(true)
        retryJob?.cancel(false)
        try { ws?.close(1000, "restart") } catch (_: Exception) {}
        ws = null
        stopping.set(false)
        attempt = 0
        connect()
    }

    fun stop() {
        stopping.set(true)
        retryJob?.cancel(false)
        try { ws?.close(1000, "client_stop") } catch (_: Exception) {}
        ws = null
        _state.value = Conn.DISCONNECTED
    }

    fun send(obj: JSONObject): Boolean = ws?.send(obj.toString()) ?: false

    /** Kirim frame binary ke VPS (hanya dipakai transfer file, Phase F). */
    fun sendBinary(bytes: ByteArray): Boolean = ws?.send(okio.ByteString.of(*bytes)) ?: false

    private fun connect() {
        if (stopping.get()) return
        _state.value = Conn.CONNECTING
        val url = baseUrl.replace(Regex("^http"), "ws") + "/ws"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                webSocket.send(JSONObject().apply {
                    put("type", "auth_controller")
                    put("token", token)
                }.toString())
                _state.value = Conn.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (obj.optString("type")) {
                    "auth_ok" -> _state.value = Conn.CONNECTED
                    "auth_error" -> {
                        _events.tryEmit(obj)
                        stopping.set(true) // token invalid: jangan retry
                        _state.value = Conn.DISCONNECTED
                    }
                    else -> _events.tryEmit(obj)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val arr = bytes.toByteArray()
                if (arr.isNotEmpty()) _binary.tryEmit(arr)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = Conn.DISCONNECTED
                if (!stopping.get()) scheduleRetry()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = Conn.DISCONNECTED
                if (!stopping.get()) scheduleRetry()
            }
        })
    }

    private fun scheduleRetry() {
        val delayMs = minOf(30_000L, 1000L * (1L shl minOf(4, attempt)).coerceAtLeast(1))
        attempt = (attempt + 1).coerceAtMost(5)
        retryJob = scheduler.schedule({ if (!stopping.get()) connect() }, delayMs, TimeUnit.MILLISECONDS)
    }
}
