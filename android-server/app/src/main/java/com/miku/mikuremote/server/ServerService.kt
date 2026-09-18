package com.miku.mikuremote.server

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service utama: koneksi WS 24/7, eksekusi command,
 * heartbeat, device info berkala, live screen + screenshot (Phase 3),
 * front camera + Server Guard (Phase 3B). Tetap hidup saat app background.
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: Prefs
    private lateinit var torch: TorchController
    private lateinit var screen: ScreenCaptureEngine
    private lateinit var camera: CameraCaptureEngine
    private lateinit var guardStore: GuardConfigStore
    private var ws: WsClient? = null

    private var torchOn = false
    private var serverModeOn = false

    /** Stream aktif (penanda supaya screen_stopped hanya dikirim sekali). */
    private var streamActive = false
    private var cameraActive = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        torch = TorchController(this)
        screen = ScreenCaptureEngine(this)
        camera = CameraCaptureEngine(this)
        guardStore = GuardConfigStore(this)
        ServiceBus.setRunning(true)
        ServiceBus.setGuardConfig(guardStore.config)
        ServiceBus.setGuardEnabled(guardStore.config.enabled)
        ServiceBus.setScreenState("READY")
        ServiceBus.setCameraState("READY")

        // Restore Server Mode dari persistence (reboot -> tetap ON jika sebelumnya ON).
        serverModeOn = prefs.serverModeOn
        ServiceBus.setServerMode(serverModeOn)
        if (serverModeOn) BlackoutActivity.show(this)

        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_SERVER_MODE -> {
                serverModeOn = intent.getBooleanExtra(EXTRA_ON, false)
                prefs.serverModeOn = serverModeOn // persisten agar selamat reboot
                ServiceBus.setServerMode(serverModeOn)
                if (!serverModeOn) BlackoutActivity.hide(this)
            }
        }

        startForegroundWithNotification("Connecting…")

        // Single-instance guard: pemanggil start() berulang (BootReceiver +
        // Activity + system restart) tidak membuat koneksi WS kedua.
        if (ws == null) {
            if (!prefs.isPaired) {
                LogBuffer.log("ERROR", "Device belum dipairing")
                stopSelf()
                return START_NOT_STICKY
            }
            connectWs()
            scope.launch { infoLoop() }
            scope.launch { noViewerWatchdog() }
        }

        // Restart otomatis oleh sistem jika proses dibunuh (auto-start resmi).
        return START_STICKY
    }

    private fun connectWs() {
        ws = WsClient(
            baseUrl = prefs.vpsUrl,
            deviceToken = { prefs.deviceToken },
            onState = { state ->
                ServiceBus.setWsState(state.name)
                when (state) {
                    WsClient.ConnState.ONLINE -> updateNotification("Connected")
                    WsClient.ConnState.CONNECTING -> updateNotification("Connecting…")
                    WsClient.ConnState.OFFLINE -> {
                        updateNotification("Reconnecting…")
                        // Laporkan RECONNECTING ke controller (sekali per transisi).
                        if (lastReportedOnline) {
                            lastReportedOnline = false
                            ws?.send(JSONObject().put("type", "device_state")
                                .put("state", "reconnecting"))
                        }
                    }
                }
            },
            onMessage = { msg -> handleCommand(msg) },
            onAuthOk = {
                // Sesudah authenticated: status online + sync config guard.
                // (Berlaku juga setelah reconnect — controller selalu up-to-date.)
                lastReportedOnline = true
                ws?.send(JSONObject().put("type", "device_state").put("state", "online"))
                ws?.send(JSONObject().put("type", "guard_config_get"))
            },
        )
        ws?.start()
    }

    @Volatile private var lastReportedOnline = false

    /**
     * Network callback (resmi): internet kembali -> reconnect sekarang.
     * Tidak ada polling berkala.
     */
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
            val req = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    LogBuffer.log("INFO", "[NET] Internet tersedia — reconnect")
                    ws?.reconnectNow()
                }
            }
            networkCallback = cb
            cm.registerNetworkCallback(req, cb)
        } catch (e: Exception) {
            LogBuffer.log("WARN", "Network callback gagal: ${e.message}")
        }
    }

    /**
     * Kirim device_info tiap ±1 menit saat idle (hemat resource).
     * Phase 3B: + evaluasi threshold Server Guard -> warning masuk log &
     * dilampirkan ke device_info sehingga Controller melihatnya realtime.
     */
    private suspend fun infoLoop() {
        while (true) {
            delay(60_000)
            val w = ws ?: continue
            if (w.state != WsClient.ConnState.ONLINE) continue
            val info = DeviceInfoStore.collect(this)
            val warnings = evaluateResourceWarnings(info)
            if (warnings.isNotEmpty()) info.put("guardWarnings", JSONArray(warnings))
            w.send(JSONObject().put("type", "device_info").put("info", info))
        }
    }

    /** Threshold guard dari remote config; hanya warning saat status BERUBAH. */
    private val lastWarnings = mutableMapOf<String, Boolean>()

    private fun evaluateResourceWarnings(info: JSONObject): List<String> {
        val cfg = guardStore.config
        val out = mutableListOf<String>()

        fun changed(key: String, active: Boolean): Boolean {
            val prev = lastWarnings[key] ?: false
            lastWarnings[key] = active
            return active && !prev
        }

        val bat = if (info.opt("batteryPct") is Int) info.optInt("batteryPct") else -1
        if (bat in 0..cfg.batteryLowThreshold && changed("battery", true)) {
            out += "Battery rendah: $bat% (< ${cfg.batteryLowThreshold}%)"
        } else if (bat > cfg.batteryLowThreshold) lastWarnings["battery"] = false

        val freeGb = info.optDouble("storageFreeGb", 999.0)
        if (freeGb <= cfg.storageLowThresholdGb && changed("storage", true)) {
            out += "Storage hampir penuh: %.1f GB free".format(freeGb)
        } else if (freeGb > cfg.storageLowThresholdGb) lastWarnings["storage"] = false

        val ramPct = if (info.optInt("ramTotalMb", 0) > 0)
            info.optInt("ramUsedMb") * 100 / info.optInt("ramTotalMb") else 0
        if (ramPct >= cfg.ramHighThreshold && changed("ram", true)) {
            out += "RAM tinggi: $ramPct% (>= ${cfg.ramHighThreshold}%)"
        } else if (ramPct < cfg.ramHighThreshold) lastWarnings["ram"] = false

        val temp = info.optDouble("batteryTempC", 0.0)
        if (temp >= cfg.temperatureHighThreshold && changed("temp", true)) {
            out += "Suhu tinggi: %.0f°C".format(temp)
        } else if (temp < cfg.temperatureHighThreshold) lastWarnings["temp"] = false

        for (wMsg in out) LogBuffer.log("WARN", "RESOURCE_WARNING: $wMsg")
        return out
    }

    // ------------------------------------------------------------------
    // Command routing
    // ------------------------------------------------------------------

    private fun handleCommand(msg: JSONObject) {
        when (msg.optString("type")) {
            "command" -> {
                val cmd = msg.optString("command")
                val cmdId = msg.opt("cmdId")
                val result = executeCommand(cmd)
                ws?.send(JSONObject().apply {
                    put("type", "command_result")
                    put("command", cmd)
                    if (cmdId != null) put("cmdId", cmdId)
                    put("success", result.error == null)
                    if (result.error != null) put("error", result.error)
                    put("state", JSONObject().put("torch", torchOn).put("serverMode", serverModeOn))
                })
            }
            "screen_start" -> handleScreenStart(msg)
            "screen_stop" -> {
                screen.stopStream()
                ServiceBus.setScreenState("READY")
                if (streamActive) {
                    streamActive = false
                    ws?.send(JSONObject().put("type", "screen_stopped"))
                    restoreDataSyncForeground()
                }
            }
            "camera_start" -> handleCameraStart(msg)
            "camera_stop" -> {
                camera.stopStream()
                ServiceBus.setCameraState("READY")
                if (cameraActive) {
                    cameraActive = false
                    ws?.send(JSONObject().put("type", "camera_stopped"))
                    restoreDataSyncForeground()
                }
            }
            "guard_config" -> handleGuardConfig(msg)
        }
    }

    // ------------------------------------------------------------------
    // Phase 3B: Server Guard config (remote configurable)
    // ------------------------------------------------------------------

    private fun handleGuardConfig(msg: JSONObject) {
        val cfgJson = msg.optJSONObject("config") ?: return
        val cfg = guardStore.applyRemote(cfgJson)
        ServiceBus.setGuardConfig(cfg)
        ServiceBus.setGuardEnabled(cfg.enabled)
        LogBuffer.log("INFO", "[GUARD] Config updated (guard=${cfg.enabled}, warning=${cfg.warningEnabled})")

        // Terapkan warning screen sesuai config terbaru.
        if (cfg.enabled && cfg.warningEnabled && !GuardWarningActivity.isShowing) {
            GuardWarningActivity.show(this)
        } else if (!cfg.enabled || !cfg.warningEnabled) {
            GuardWarningActivity.hide()
        }
        // Ack ke VPS -> controller tahu sync sukses.
        ws?.send(JSONObject().put("type", "guard_config_synced"))
    }

    // ------------------------------------------------------------------
    // Phase 3: live screen (tambah conflict check + guard mode)
    // ------------------------------------------------------------------

    private fun handleScreenStart(msg: JSONObject) {
        val w = ws ?: return
        val reqW = msg.optInt("width", 1280)
        val reqH = msg.optInt("height", 720)
        val fps = msg.optInt("fps", 12)
        val kbps = msg.optInt("bitrateKbps", 1200)

        fun reportError(text: String) {
            LogBuffer.log("ERROR", text)
            w.send(JSONObject().put("type", "screen_error").put("error", text))
        }

        // ---- Resource conflict (Phase 3B): screen vs camera ----
        if (camera.isActive) {
            reportError("STOP CAMERA STREAM TO USE SCREEN")
            return
        }

        fun begin(resultCode: Int, data: Intent?) {
            if (data == null && !screen.hasProjection) {
                reportError("Izin capture layar ditolak")
                return
            }
            // Android 14+: FGS type MEDIA_PROJECTION harus aktif SEBELUM
            // getMediaProjection().
            try {
                upgradeToMediaProjectionForeground()
            } catch (e: Exception) {
                LogBuffer.log("WARN", "FGS upgrade gagal: ${e.message}")
            }

            val err = screen.startStream(
                resultCode, data, reqW, reqH, fps, kbps,
                sendFrame = { frame -> if (frame.isNotEmpty()) w.sendBinary(frame) },
                onMeta = { fw, fh, ffps ->
                    w.send(JSONObject()
                        .put("type", "screen_meta")
                        .put("width", fw).put("height", fh)
                        .put("fps", ffps).put("codec", "h264"))
                },
                onEnded = {
                    streamActive = false
                    ServiceBus.setScreenState("READY")
                    w.send(JSONObject().put("type", "screen_stopped"))
                    restoreDataSyncForeground()
                },
            )
            if (err != null) {
                reportError(err)
                restoreDataSyncForeground()
            } else {
                streamActive = true
                ServiceBus.setScreenState("STREAMING")
            }
        }

        if (screen.isActive) {
            // Stream sudah jalan (permintaan ulang / controller kedua).
            streamActive = true
            LogBuffer.log("INFO", "screen_start diterima tapi stream sudah aktif")
            return
        }

        if (screen.hasProjection) {
            // Sesi projection masih sah — mulai langsung tanpa dialog consent.
            begin(-1, null) // -1 = Activity.RESULT_OK (result dipakai ulang tak relevan)
            return
        }

        // Guard/unattended: JANGAN memunculkan dialog saat HP ditinggal —
        // laporkan status agar Controller menampilkan instruksi setup lokal.
        // (Bypass permission dilarang; dialog consent hanya muncul saat user
        // benar-benar memegang HP server.)
        if (guardStore.config.enabled) {
            ServiceBus.setScreenState("PERMISSION_REQUIRED")
            LogBuffer.log("WARN", "[SCREEN] Permission required (Server Guard ON, unattended)")
            w.send(JSONObject().put("type", "screen_error")
                .put("error", "SCREEN_PERMISSION_REQUIRED: buka MikuRemote Server di HP server dan mulai LIVE SCREEN sekali untuk memberi izin"))
            return
        }

        // Consent baru: resultData MediaProjection hanya boleh dipakai SEKALI.
        ScreenConsentBus.listener = { resultCode, data -> begin(resultCode, data) }
        try {
            startActivity(
                Intent(this, ScreenPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ServiceBus.setScreenState("PERMISSION_REQUIRED")
            LogBuffer.log("INFO", "Menunggu izin capture layar…")
        } catch (e: Exception) {
            ScreenConsentBus.listener = null
            ServiceBus.setScreenState("ERROR")
            reportError("Gagal membuka dialog izin: ${e.message}")
        }
    }

    private fun upgradeToMediaProjectionForeground() {
        if (Build.VERSION.SDK_INT >= 29) {
            val notif = buildNotification("Screen capture aktif")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 3B: front camera
    // ------------------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun handleCameraStart(msg: JSONObject) {
        val w = ws ?: return
        val front = msg.optString("camera", "front") != "back"
        val reqW = msg.optInt("width", 640)
        val reqH = msg.optInt("height", 480)
        val fps = msg.optInt("fps", 12)
        val kbps = msg.optInt("bitrateKbps", 800)

        fun reportError(text: String) {
            LogBuffer.log("ERROR", text)
            w.send(JSONObject().put("type", "camera_error").put("error", text))
        }

        // ---- Resource conflict (Phase 3B): camera vs screen ----
        if (screen.isActive) {
            LogBuffer.log("WARN", "[CAMERA] Request ditolak: screen stream aktif")
            reportError("STOP SCREEN STREAM TO USE CAMERA")
            return
        }
        if (camera.isActive) {
            LogBuffer.log("INFO", "[CAMERA] Request saat kamera sudah aktif — diabaikan")
            return
        }

        // Permission resmi: TANPA bypass. Kalau belum di-grant, laporkan status.
        if (!hasCameraPermission()) {
            LogBuffer.log("WARN", "[CAMERA] Permission required")
            reportError("CAMERA_PERMISSION_REQUIRED: buka MikuRemote Server di HP server dan tekan START (izin kamera)")
            return
        }

        // Android 14+: FGS type CAMERA harus aktif sebelum openCamera.
        try {
            upgradeToCameraForeground()
        } catch (e: Exception) {
            LogBuffer.log("WARN", "FGS camera upgrade gagal: ${e.message}")
        }

        val err = camera.startStream(
            front, reqW, reqH, fps, kbps,
            sendFrame = { frame -> if (frame.isNotEmpty()) w.sendBinary(frame) },
            onMeta = { fw, fh, ffps ->
                w.send(JSONObject()
                    .put("type", "camera_meta")
                    .put("width", fw).put("height", fh)
                    .put("fps", ffps).put("codec", "h264"))
            },
            onEnded = {
                cameraActive = false
                ServiceBus.setCameraState("READY")
                w.send(JSONObject().put("type", "camera_stopped"))
                restoreDataSyncForeground()
            },
        )
        if (err != null) {
            reportError(err)
            restoreDataSyncForeground()
        } else {
            cameraActive = true
            ServiceBus.setCameraState("STREAMING")
        }
    }

    private fun upgradeToCameraForeground() {
        if (Build.VERSION.SDK_INT >= 29) {
            val notif = buildNotification("Camera aktif")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            }
        }
    }

    /** Kembali ke FGS dataSync setelah capture berhenti (Android 14+ wajib). */
    private fun restoreDataSyncForeground() {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                startForeground(
                    NOTIF_ID, buildNotification(notificationStateText()),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        }
    }

    /**
     * Watchdog: jika WS ke VPS putus saat stream berjalan, hentikan stream
     * setelah ±8 detik (tidak ada penerima = buang resource & kuota).
     */
    private suspend fun noViewerWatchdog() {
        while (scope.isActive) {
            delay(8_000)
            if (!streamActive && !cameraActive) continue
            if (ws?.state != WsClient.ConnState.ONLINE) {
                LogBuffer.log("WARN", "[STREAM] VPS offline saat streaming — hentikan stream (hemat resource)")
                if (streamActive) {
                    screen.stopStream()
                    streamActive = false
                    ServiceBus.setScreenState("READY")
                }
                if (cameraActive) {
                    camera.stopStream()
                    cameraActive = false
                    ServiceBus.setCameraState("READY")
                }
                restoreDataSyncForeground()
            }
        }
    }

    private fun notificationStateText(): String = when (ServiceBus.wsState.value) {
        "ONLINE" -> "Connected"
        "CONNECTING" -> "Connecting…"
        else -> "Reconnecting…"
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private data class CmdResult(val error: String?)

    private fun executeCommand(cmd: String): CmdResult = when (cmd) {
        "TORCH_ON" -> {
            val err = torch.setTorch(true)
            if (err == null) {
                torchOn = true
                LogBuffer.log("INFO", "Torch enabled")
            } else {
                LogBuffer.log("ERROR", err)
            }
            CmdResult(err)
        }
        "TORCH_OFF" -> {
            val err = torch.setTorch(false)
            if (err == null) {
                torchOn = false
                LogBuffer.log("INFO", "Torch disabled")
            }
            CmdResult(err)
        }
        "SERVER_MODE_ON" -> {
            serverModeOn = true
            prefs.serverModeOn = true
            ServiceBus.setServerMode(true)
            BlackoutActivity.show(this)
            LogBuffer.log("INFO", "Server mode ON")
            CmdResult(null)
        }
        "SERVER_MODE_OFF" -> {
            serverModeOn = false
            prefs.serverModeOn = false
            ServiceBus.setServerMode(false)
            BlackoutActivity.hide(this)
            LogBuffer.log("INFO", "Server mode OFF")
            CmdResult(null)
        }
        "GET_DEVICE_INFO" -> {
            val info = DeviceInfoStore.collect(this)
            val warnings = evaluateResourceWarnings(info)
            if (warnings.isNotEmpty()) info.put("guardWarnings", JSONArray(warnings))
            ws?.send(JSONObject().put("type", "device_info").put("info", info))
            CmdResult(null)
        }
        "SCREENSHOT" -> {
            handleScreenshotCommand()
            CmdResult(null)
        }
        "LOG_GET" -> {
            val arr = org.json.JSONArray()
            for (e in LogBuffer.snapshot()) {
                arr.put(JSONObject().put("ts", e.ts).put("level", e.level).put("message", e.message))
            }
            ws?.send(JSONObject().put("type", "log_entries").put("entries", arr))
            CmdResult(null)
        }
        else -> CmdResult("Command tidak dikenal: $cmd")
    }

    /**
     * Screenshot: satu frame JPEG dikirim sebagai WS binary ke controller
     * yang meminta (VPS menandai peminta selama 15 detik).
     */
    private fun handleScreenshotCommand() {
        val w = ws ?: return

        fun sendJpeg(bytes: ByteArray?) {
            if (bytes != null && bytes.isNotEmpty()) {
                w.sendBinary(bytes)
                LogBuffer.log("INFO", "Screenshot terkirim (${bytes.size / 1024} KB)")
            } else {
                LogBuffer.log("WARN", "Screenshot gagal: tidak ada frame")
                w.send(JSONObject().put("type", "screen_error")
                    .put("error", "Screenshot gagal: layar tidak menghasilkan frame"))
            }
        }

        if (screen.hasProjection) {
            val err = screen.captureScreenshot(::sendJpeg)
            if (err != null) {
                LogBuffer.log("ERROR", err)
                w.send(JSONObject().put("type", "screen_error").put("error", err))
            }
            return
        }

        // Guard/unattended: jangan minta consent saat HP ditinggal.
        if (guardStore.config.enabled) {
            LogBuffer.log("WARN", "[SCREEN] Screenshot butuh izin capture (Server Guard ON)")
            w.send(JSONObject().put("type", "screen_error")
                .put("error", "SCREEN_PERMISSION_REQUIRED: buka MikuRemote Server di HP server dan mulai LIVE SCREEN sekali untuk memberi izin"))
            return
        }

        // Tanpa sesi capture: consent baru (resultData hanya dipakai sekali).
        ScreenConsentBus.listener = { resultCode, data ->
            if (data == null) {
                sendJpeg(null)
            } else {
                try { upgradeToMediaProjectionForeground() } catch (_: Exception) {}
                // Buat projection dari consent, lalu screenshot.
                val err = screen.startStream(
                    resultCode, data, 640, 360, 5, 300,
                    sendFrame = { /* tidak dipakai: langsung stop */ },
                    onMeta = { _, _, _ -> },
                    onEnded = {},
                )
                if (err != null) {
                    LogBuffer.log("ERROR", err)
                    w.send(JSONObject().put("type", "screen_error").put("error", err))
                } else {
                    streamActive = true
                    val shotErr = screen.captureScreenshot { jpeg ->
                        sendJpeg(jpeg)
                        // Hentikan stream dummy; sesi projection dipertahankan.
                        screen.stopStream()
                        streamActive = false
                    }
                    if (shotErr != null) {
                        LogBuffer.log("ERROR", shotErr)
                        w.send(JSONObject().put("type", "screen_error").put("error", shotErr))
                    }
                    restoreDataSyncForeground()
                    if (screen.hasProjection) LogBuffer.log("INFO", "Sesi capture tersimpan untuk pemakaian berikutnya")
                }
            }
        }
        try {
            startActivity(
                Intent(this, ScreenPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            LogBuffer.log("INFO", "Menunggu izin screenshot…")
        } catch (e: Exception) {
            ScreenConsentBus.listener = null
            val err = "Gagal membuka dialog izin: ${e.message}"
            LogBuffer.log("ERROR", err)
            w.send(JSONObject().put("type", "screen_error").put("error", err))
        }
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ServerApp.CHANNEL_SERVER)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MikuRemote Server")
            .setContentText("Server aktif • $text")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun startForegroundWithNotification(text: String) {
        val notif = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // dataSync dulu; MEDIA_PROJECTION/CAMERA ditambahkan saat capture
                // dimulai SETELAH consent/permission ada — syarat Android 14+.
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Defensif: beberapa OEM gagal startForeground dengan tipe spesifik
            // atau channel belum terdaftar. Jangan biarkan service crash.
            LogBuffer.log("ERROR", "startForeground gagal: ${e.message}")
            runCatching { startForeground(NOTIF_ID, notif) }
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onDestroy() {
        try {
            networkCallback?.let { cb ->
                getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        } catch (_: Exception) {}
        screen.release()
        camera.release()
        ws?.stop()
        ws = null
        scope.cancel()
        ServiceBus.setRunning(false)
        ServiceBus.setWsState("OFFLINE")
        if (serverModeOn) BlackoutActivity.hide(this)
        GuardWarningActivity.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.miku.mikuremote.server.START"
        const val ACTION_STOP = "com.miku.mikuremote.server.STOP"
        const val ACTION_SET_SERVER_MODE = "com.miku.mikuremote.server.SET_SERVER_MODE"
        const val EXTRA_ON = "on"
        private const val NOTIF_ID = 1001

        fun start(context: android.content.Context) {
            val i = Intent(context, ServerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
