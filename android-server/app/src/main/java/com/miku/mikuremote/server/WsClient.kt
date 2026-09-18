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
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket client ke VPS dengan:
 * - state machine jujur: CONNECTING -> AUTHENTICATING -> ONLINE.
 *   ONLINE hanya SETELAH VPS mengirim "auth_ok" (token tervalidasi).
 *   Tidak ada fake ONLINE sebelum device terdaftar (spec: no fake success).
 * - auto-reconnect exponential backoff (2s -> 4s -> 8s -> 16s -> 30s -> 60s)
 * - generation guard: socket lama (sudah digantikan reconnectNow/stop)
 *   tidak bisa memicu reconnect duplikat -> hanya satu WS session aktif.
 * - heartbeat tiap 30s (hemat resource untuk server 24/7)
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
    enum class ConnState { CONNECTING, AUTHENTICATING, ONLINE, OFFLINE }

    private val url = baseUrl.replace(Regex("^http"), "ws") + "/ws"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // koneksi panjang
        .pingInterval(20, TimeUnit.SECONDS)   // WS ping protocol-level
        .build()

    private var ws: WebSocket? = null
    private var heartbeatJob: ScheduledFuture<*>? = null
    private var reconnectJob: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val stopping = AtomicBoolean(false)
    private var attempt = 0

    /**
     * Generasi socket aktif. Setiap connect() menaikkan nilainya; callback
     * dari socket lama (generation beda) diabaikan sehingga tidak ada
     * koneksi ganda / setState silang dari socket zombie.
     */
    private val generation = AtomicInteger(0)

    @Volatile var state: ConnState = ConnState.OFFLINE
        private set

    fun start() {
        stopping.set(false)
        connect()
    }

    fun stop() {
        stopping.set(true)
        generation.incrementAndGet() // batalkan semua callback socket berjalan
        reconnectJob?.cancel(false)
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
        if (state == s) return
        state = s
        onState(s)
    }

    private fun connect() {
        if (stopping.get()) return
        reconnectJob?.cancel(false)
        heartbeatJob?.cancel(false)
        val gen = generation.incrementAndGet()
        setState(ConnState.CONNECTING)
        val token = deviceToken() ?: run {
            LogBuffer.log("ERROR", "Tidak ada device token — tidak bisa connect")
            setState(ConnState.OFFLINE)
            return
        }
        val req = Request.Builder().url(url).build()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation.get()) return // socket stale
                attempt = 0
                // Auth pertama setelah terhubung. ONLINE menunggu auth_ok.
                webSocket.send(JSONObject().apply {
                    put("type", "auth_device")
                    put("token", token)
                }.toString())
                startHeartbeat(webSocket)
                setState(ConnState.AUTHENTICATING)
                LogBuffer.log("INFO", "WebSocket terhubung — mengautentikasi…")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation.get()) return // socket stale
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (obj.optString("type")) {
                    "auth_error" -> {
                        LogBuffer.log("ERROR", "Auth ditolak VPS (token invalid/revoked)")
                        // Token invalid: reconnect tidak akan menolong.
                        stopping.set(true)
                        generation.incrementAndGet()
                        heartbeatJob?.cancel(false)
                        try { webSocket.close(1000, "auth_rejected") } catch (_: Exception) {}
                        setState(ConnState.OFFLINE)
                    }
                    "auth_ok" -> {
                        // Satu-satunya tempat ONLINE di-set: device benar-benar
                        // terautentikasi + terdaftar di VPS.
                        LogBuffer.log("INFO", "Server authenticated")
                        setState(ConnState.ONLINE)
                        onAuthOk?.invoke()
                    }
                    "heartbeat_ack" -> { /* koneksi sehat */ }
                    else -> onMessage(obj)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen != generation.get()) return // socket stale
                onBinary(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation.get()) return // socket stale: abaikan
                heartbeatJob?.cancel(false)
                setState(ConnState.OFFLINE)
                LogBuffer.log("WARN", "Koneksi terputus: ${t.message ?: "unknown"}")
                if (!stopping.get()) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation.get()) return // socket stale: abaikan
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
        }, 30, 30, TimeUnit.SECONDS)
    }

    /** Backoff: 2s, 4s, 8s, 16s, 30s, 60s (maks). */
    private fun scheduleReconnect() {
        val delays = longArrayOf(2_000, 4_000, 8_000, 16_000, 30_000, 60_000)
        val delayMs = delays[minOf(delays.size - 1, attempt)]
        attempt += 1
        LogBuffer.log("INFO", "Reconnect dalam ${delayMs / 1000}s")
        reconnectJob?.cancel(false)
        reconnectJob = scheduler.schedule({ if (!stopping.get()) connect() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Paksa reconnect sekarang (mis. network callback "internet kembali").
     * generation dinaikkan -> callback socket lama diabaikan -> tidak ada
     * koneksi ganda. Attempt di-reset karena network baru belum dicoba.
     */
    fun reconnectNow() {
        if (stopping.get()) return
        attempt = 0
        heartbeatJob?.cancel(false)
        try { ws?.close(1000, "network_restored") } catch (_: Exception) {}
        ws = null
        connect()
    }
}
