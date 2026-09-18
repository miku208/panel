package com.miku.mikuremote.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val Bg = Color(0xFF0E1116)
private val CardBg = Color(0xFF161B22)
private val Accent = Color(0xFF39D98A)
private val Warn = Color(0xFFE3B341)
private val Danger = Color(0xFFF85149)
private val TextMain = Color(0xFFE6EDF3)
private val TextDim = Color(0xFF8B949E)
private val Line = Color(0xFF21262D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MikuRemoteApp() }
    }
}

// ---------------------------------------------------------------------------
// App root & navigasi sederhana
// ---------------------------------------------------------------------------

private sealed class Screen {
    data object Boot : Screen()
    data object Devices : Screen()
    data class Detail(val deviceId: String, val name: String) : Screen()
    data class Logs(val deviceId: String) : Screen()
    data class Live(val deviceId: String, val name: String) : Screen()
    data class Camera(val deviceId: String, val name: String) : Screen()
    data class Guard(val deviceId: String, val name: String) : Screen()
}

data class LogEntryUi(val ts: Long, val level: String, val message: String)

@Composable
private fun MikuRemoteApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Boot) }
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }

    // State global
    val conn by ControllerSocket.state.collectAsState()
    val devices = remember { mutableStateListOf<DeviceRow>() }
    val onlineMap = remember { mutableStateMapOf<String, Boolean>() }
    val infoMap = remember { mutableStateMapOf<String, JSONObject>() }
    val torchMap = remember { mutableStateMapOf<String, Boolean>() }
    val serverModeMap = remember { mutableStateMapOf<String, Boolean>() }
    val logs = remember { mutableStateListOf<LogEntryUi>() }
    val snackbar = remember { SnackbarHostState() }

    var bootError by remember { mutableStateOf<String?>(null) }
    var manualKey by remember { mutableStateOf("") }
    var bootBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun showSnack(text: String) { snackbar.showSnackbar(text) }

    // ---- Bootstrap mode pribadi: tanpa login, langsung sesi + daftar device ----
    suspend fun boot(key: String) {
        bootBusy = true
        bootError = null
        // 1. Tukar kunci dengan JWT owner (sekali; hasilnya tersimpan).
        val session = withContext(Dispatchers.IO) { Api.privateSession(prefs.vpsUrl, key) }
        session
            .onSuccess { prefs.userToken = it }
            .onFailure { e ->
                bootError = e.message
                bootBusy = false
                return
            }
        // 2. Muat daftar device milik owner.
        val list = withContext(Dispatchers.IO) {
            Api.listDevices(prefs.vpsUrl, prefs.userToken!!)
        }
        list.onSuccess { rows ->
            devices.clear(); devices.addAll(rows)
            rows.forEach { onlineMap[it.id] = it.online }
            screen = Screen.Devices
        }.onFailure { e -> bootError = e.message }
        bootBusy = false
    }

    // Boot otomatis saat aplikasi dibuka.
    LaunchedEffect(Unit) {
        val key = BuildConfig.PRIVATE_KEY.ifBlank { prefs.privateKey ?: "" }
        if (key.isNotBlank()) boot(key)
        // Jika tidak ada kunci sama sekali, layar Boot menampilkan input kunci.
    }

    // ---- Event collector dari VPS ----
    LaunchedEffect(Unit) {
        ControllerSocket.events.collect { msg ->
            when (msg.optString("type")) {
                "device_status" -> {
                    val id = msg.optString("deviceId")
                    onlineMap[id] = msg.optBoolean("online")
                    if (!msg.optBoolean("online")) {
                        torchMap[id] = false
                        serverModeMap[id] = false
                    }
                }
                "device_info" -> {
                    val id = msg.optString("deviceId")
                    infoMap[id] = msg.optJSONObject("info") ?: JSONObject()
                    onlineMap[id] = true
                }
                "command_result" -> {
                    val id = msg.optString("deviceId")
                    val state = msg.optJSONObject("state")
                    state?.optBoolean("torch")?.let { torchMap[id] = it }
                    state?.optBoolean("serverMode")?.let { serverModeMap[id] = it }
                    val cmd = msg.optString("command")
                    val text = when {
                        msg.optBoolean("success") && cmd.startsWith("TORCH") ->
                            if (torchMap[id] == true) "Senter ON ✓" else "Senter OFF ✓"
                        msg.optBoolean("success") && cmd.startsWith("SERVER_MODE") ->
                            if (serverModeMap[id] == true) "Server mode ON ✓" else "Server mode OFF ✓"
                        msg.optBoolean("success") -> "$cmd ✓"
                        else -> "${msg.optString("error", "Command gagal")}"
                    }
                    showSnack(text)
                }
                "log_entry" -> {
                    val e = msg.optJSONObject("entry") ?: return@collect
                    addLog(logs, e)
                }
                "log_entries" -> {
                    val arr = msg.optJSONArray("entries") ?: return@collect
                    for (i in 0 until arr.length()) addLog(logs, arr.getJSONObject(i))
                }
                "screen_stopped" -> {
                    // Jika layar Live terbuka, LiveScreenOverlay menangani phase-nya
                    // sendiri; di luar itu cukup snack.
                    if (screen !is Screen.Live) showSnack("Live screen dihentikan HP server")
                }
                "screen_error" -> {
                    if (screen !is Screen.Live) showSnack(msg.optString("error", "Capture gagal"))
                }
                "camera_stopped" -> {
                    if (screen !is Screen.Camera) showSnack("Kamera dihentikan HP server")
                }
                "camera_error" -> {
                    if (screen !is Screen.Camera) showSnack(msg.optString("error", "Camera gagal"))
                }
                "auth_error" -> {
                    // Sesi JWT expired: ulangi boot dengan kunci tersimpan.
                    prefs.userToken = null
                    ControllerSocket.stop()
                    screen = Screen.Boot
                    val key = BuildConfig.PRIVATE_KEY.ifBlank { prefs.privateKey ?: "" }
                    if (key.isNotBlank()) boot(key) else bootError = "Sesi berakhir: ${msg.optString("error")}"
                }
            }
        }
    }

    // ---- Socket lifecycle ----
    LaunchedEffect(screen) {
        if (screen != Screen.Boot && prefs.userToken != null && conn != ControllerSocket.Conn.CONNECTED) {
            ControllerSocket.start(prefs.vpsUrl, prefs.userToken!!)
        }
    }

    // ---- Auto-save screenshot JPEG dari HP server ----
    LaunchedEffect(Unit) {
        ControllerSocket.binary.collect { frame ->
            val saved = ScreenshotSaver.saveIfJpeg(context, frame)
            if (saved != null) showSnack("Screenshot tersimpan: $saved")
        }
    }

    // ---- Refresh daftar device saat masuk layar Devices ----
    LaunchedEffect(screen) {
        if (screen is Screen.Devices && prefs.userToken != null) {
            val result = withContext(Dispatchers.IO) { Api.listDevices(prefs.vpsUrl, prefs.userToken!!) }
            result.onSuccess { list ->
                devices.clear(); devices.addAll(list)
                list.forEach { onlineMap[it.id] = it.online }
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = CardBg, primary = Accent,
            onPrimary = Color.Black, surfaceVariant = Line,
        )
    ) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            when {
                screen is Screen.Boot -> BootScreen(
                    prefs = prefs,
                    error = bootError,
                    busy = bootBusy,
                    manualKey = manualKey,
                    onManualKey = { manualKey = it },
                    onBoot = {
                        prefs.vpsUrl = prefs.vpsUrl
                        scope.launch {
                            prefs.privateKey = manualKey.trim()
                            boot(manualKey.trim())
                        }
                    },
                )
                screen is Screen.Devices -> DeviceListScreen(
                    prefs, devices, onlineMap, infoMap,
                    conn = conn,
                    onRefresh = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                Api.listDevices(prefs.vpsUrl, prefs.userToken!!)
                            }
                            result.onSuccess { list ->
                                devices.clear(); devices.addAll(list)
                                list.forEach { onlineMap[it.id] = it.online }
                            }.onFailure { showSnack(it.message ?: "Gagal memuat device") }
                        }
                    },
                    onOpenDevice = { d -> screen = Screen.Detail(d.id, d.name) },
                    showSnack = { showSnack(it) },
                )
                screen is Screen.Detail -> {
                    val s = screen as Screen.Detail
                    DetailScreen(
                        prefs = prefs,
                        deviceId = s.deviceId, name = s.name,
                        online = onlineMap[s.deviceId] ?: false,
                        info = infoMap[s.deviceId],
                        torchOn = torchMap[s.deviceId] ?: false,
                        serverModeOn = serverModeMap[s.deviceId] ?: false,
                        onBack = { screen = Screen.Devices },
                        onOpenLogs = { screen = Screen.Logs(s.deviceId) },
                        onOpenLive = { screen = Screen.Live(s.deviceId, s.name) },
                        onOpenCamera = { screen = Screen.Camera(s.deviceId, s.name) },
                        onOpenGuard = { screen = Screen.Guard(s.deviceId, s.name) },
                        onRevoked = { screen = Screen.Devices },
                        showSnack = { showSnack(it) },
                    )
                }
                screen is Screen.Camera -> {
                    val s = screen as Screen.Camera
                    CameraScreenOverlay(
                        deviceId = s.deviceId,
                        onBack = { screen = Screen.Detail(s.deviceId, s.name) },
                        showSnack = { showSnack(it) },
                    )
                }
                screen is Screen.Guard -> {
                    val s = screen as Screen.Guard
                    GuardScreen(
                        prefs = prefs,
                        deviceId = s.deviceId,
                        online = onlineMap[s.deviceId] ?: false,
                        onBack = { screen = Screen.Detail(s.deviceId, s.name) },
                        showSnack = { showSnack(it) },
                    )
                }
                screen is Screen.Live -> {
                    val s = screen as Screen.Live
                    LiveScreenOverlay(
                        deviceId = s.deviceId,
                        onBack = { screen = Screen.Detail(s.deviceId, s.name) },
                        showSnack = { showSnack(it) },
                    )
                }
                screen is Screen.Logs -> {
                    val s = screen as Screen.Logs
                    LogScreen(
                        deviceId = s.deviceId,
                        logs = logs,
                        onBack = { screen = Screen.Devices },
                        onRefresh = {
                            ControllerSocket.send(
                                JSONObject().put("type", "device_command")
                                    .put("deviceId", s.deviceId).put("command", "LOG_GET")
                            )
                        },
                    )
                }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
            )
        }
    }
}

private fun addLog(logs: MutableList<LogEntryUi>, e: JSONObject) {
    logs.add(
        LogEntryUi(
            ts = e.optLong("ts", System.currentTimeMillis()),
            level = e.optString("level", "INFO"),
            message = e.optString("message", ""),
        )
    )
    while (logs.size > 300) logs.removeAt(0)
}

private fun relTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    return try {
        val clean = iso.substringBefore('.')
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val t = fmt.parse(clean)?.time ?: return iso
        val diff = (System.currentTimeMillis() - t) / 1000
        when {
            diff < 60 -> "now"
            diff < 3600 -> "${diff / 60}m ago"
            diff < 86400 -> "${diff / 3600}h ago"
            else -> "${diff / 86400}d ago"
        }
    } catch (_: Exception) { iso }
}

@Composable
private fun StatusDot(online: Boolean, size: Int = 8) {
    Box(
        Modifier
            .size(size.dp)
            .background(if (online) Accent else Danger, RoundedCornerShape(2.dp))
    )
}

// ---------------------------------------------------------------------------
// BOOT (mode pribadi): otomatis dengan kunci build, atau input kunci sekali
// ---------------------------------------------------------------------------

@Composable
private fun BootScreen(
    prefs: Prefs,
    error: String?,
    busy: Boolean,
    manualKey: String,
    onManualKey: (String) -> Unit,
    onBoot: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("MikuRemote", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Mode pribadi", color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                Spacer(Modifier.width(10.dp))
                Text("Menghubungkan ke VPS…", color = TextMain, fontSize = 14.sp)
            }
        } else {
            OutlinedTextField(
                value = prefs.vpsUrl, onValueChange = { prefs.vpsUrl = it },
                label = { Text("VPS URL", color = TextDim) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = TextMain, fontSize = 14.sp),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = manualKey, onValueChange = onManualKey,
                label = { Text("Kunci pribadi", color = TextDim) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = LocalTextStyle.current.copy(color = TextMain, fontSize = 14.sp),
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onBoot,
                enabled = manualKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("HUBUNGKAN") }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Danger, fontSize = 13.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// DEVICE LIST
// ---------------------------------------------------------------------------

@Composable
private fun DeviceListScreen(
    prefs: Prefs,
    devices: List<DeviceRow>,
    onlineMap: Map<String, Boolean>,
    infoMap: Map<String, JSONObject>,
    conn: ControllerSocket.Conn,
    onRefresh: () -> Unit,
    onOpenDevice: (DeviceRow) -> Unit,
    showSnack: suspend (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MikuRemote", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "My Devices • " +
                        when (conn) {
                            ControllerSocket.Conn.CONNECTED -> "connected"
                            ControllerSocket.Conn.CONNECTING -> "connecting…"
                            else -> "disconnected"
                        },
                    color = TextDim, fontSize = 12.sp
                )
            }
            TextButton(onClick = onRefresh) { Text("Muat ulang", color = TextDim) }
        }
        Spacer(Modifier.height(16.dp))

        if (devices.isEmpty()) {
            Text(
                "Belum ada device.\nBuka aplikasi MikuRemote Server di HP server —\ndevice akan muncul di sini secara otomatis.",
                color = TextDim, fontSize = 14.sp, lineHeight = 20.sp
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(devices, key = { it.id }) { d ->
                val online = onlineMap[d.id] ?: d.online
                val info = infoMap[d.id]
                Card(
                    onClick = { onOpenDevice(d) },
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(d.name, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(online)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (online) "ONLINE" else "OFFLINE",
                                color = if (online) Accent else Danger,
                                fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                        }
                        info?.let {
                            Spacer(Modifier.height(6.dp))
                            val bat = it.opt("batteryPct")
                            val ram = it.optInt("ramUsedMb", -1)
                            val line = buildString {
                                if (bat is Int) append("Battery $bat%")
                                if (ram >= 0) {
                                    if (isNotEmpty()) append("  •  ")
                                    append("RAM ${"%.1f".format(ram / 1024f)} GB")
                                }
                            }
                            if (line.isNotEmpty()) Text(line, color = TextDim, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Last seen: ${relTime(d.lastSeen)}",
                            color = TextDim, fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DEVICE DETAIL
// ---------------------------------------------------------------------------

@Composable
private fun DetailScreen(
    prefs: Prefs,
    deviceId: String,
    name: String,
    online: Boolean,
    info: JSONObject?,
    torchOn: Boolean,
    serverModeOn: Boolean,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenGuard: () -> Unit,
    onRevoked: () -> Unit,
    showSnack: suspend (String) -> Unit,
) {
    var confirmRevoke by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun sendCmd(cmd: String) {
        if (!ControllerSocket.send(
                JSONObject().put("type", "device_command")
                    .put("deviceId", deviceId).put("command", cmd)
                    .put("cmdId", UUID.randomUUID().toString())
            )
        ) {
            scope.launchSnack(showSnack, "Koneksi ke VPS terputus")
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("← ", color = TextDim, fontSize = 18.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(name, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(online)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (online) "ONLINE" else "OFFLINE",
                        color = if (online) Accent else Danger,
                        fontSize = 12.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))

        // ---- Info grid ----
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info == null) {
                    Text("Menunggu info device…", color = TextDim, fontSize = 13.sp)
                } else {
                    InfoRow("Battery",
                        if (info.opt("batteryPct") is Int)
                            "${info.optInt("batteryPct")}%${if (info.optBoolean("charging")) " (charging)" else ""}"
                        else "-")
                    InfoRow("Storage",
                        "${info.optDouble("storageFreeGb", 0.0)} GB free / ${info.optDouble("storageTotalGb", 0.0)} GB")
                    InfoRow("RAM",
                        "${"%.1f".format(info.optInt("ramUsedMb") / 1024f)} / ${"%.1f".format(info.optInt("ramTotalMb") / 1024f)} GB")
                    InfoRow("Uptime", "${info.optInt("uptimeHours")} jam")
                    InfoRow("Android", "v${info.optString("androidVersion")}")
                    // Phase 3B: warning resource dari Server Guard (jika ada).
                    val gw = info.optJSONArray("guardWarnings")
                    if (gw != null && gw.length() > 0) {
                        val parts = mutableListOf<String>()
                        for (i in 0 until gw.length()) parts.add(gw.optString(i))
                        InfoRow("⚠ Guard", parts.joinToString("; "))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Actions ----
        ActionButton("LIVE SCREEN", enabled = online, onClick = onOpenLive)
        Spacer(Modifier.height(10.dp))

        // Torch
        OutlinedButton(
            onClick = { sendCmd(if (torchOn) "TORCH_OFF" else "TORCH_ON") },
            enabled = online,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (torchOn) Accent else TextMain
            )
        ) {
            Text(if (torchOn) "🔦 SENTER: ON" else "🔦 SENTER: OFF", fontSize = 15.sp)
        }
        Spacer(Modifier.height(10.dp))

        // Server mode
        OutlinedButton(
            onClick = { sendCmd(if (serverModeOn) "SERVER_MODE_OFF" else "SERVER_MODE_ON") },
            enabled = online,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (serverModeOn) Accent else TextMain
            )
        ) {
            Text(if (serverModeOn) "🌙 SERVER MODE: ON" else "🌙 SERVER MODE: OFF", fontSize = 15.sp)
        }
        Spacer(Modifier.height(10.dp))

        // Phase 3B: front camera + server guard
        ActionButton("FRONT CAMERA", enabled = online, onClick = onOpenCamera)
        Spacer(Modifier.height(10.dp))
        ActionButton("SERVER GUARD", enabled = true, onClick = onOpenGuard)
        Spacer(Modifier.height(10.dp))

        ActionButton("LOG", enabled = true, onClick = onOpenLogs)
        Spacer(Modifier.height(10.dp))
        ActionButton("SCREENSHOT", enabled = online) {
            val ok = ControllerSocket.send(
                JSONObject().put("type", "device_command")
                    .put("deviceId", deviceId).put("command", "SCREENSHOT")
                    .put("cmdId", UUID.randomUUID().toString())
            )
            scope.launchSnack(showSnack, if (ok) "Screenshot diminta — izinkan dialog capture di HP server" else "Koneksi ke VPS terputus")
        }

        Spacer(Modifier.weight(1f))
        TextButton(onClick = { confirmRevoke = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Hapus device ini dari daftar", color = Danger, fontSize = 13.sp)
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("Hapus device?") },
            text = { Text("HP server akan diputuskan dan terdaftar ulang otomatis saat aplikasi server dibuka lagi.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = false
                    val token = prefs.userToken ?: ""
                    scope.launch(Dispatchers.IO) {
                        val r = Api.deleteDevice(prefs.vpsUrl, token, deviceId)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            r.onSuccess { onRevoked() }
                                .onFailure { showSnack(it.message ?: "Gagal menghapus") }
                        }
                    }
                }) { Text("Hapus", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("Batal") } }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = TextDim, fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Text(value, color = TextMain, fontSize = 13.sp)
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)
    ) { Text(label, fontSize = 15.sp, letterSpacing = 1.sp) }
}

// ---------------------------------------------------------------------------
// LOG VIEWER
// ---------------------------------------------------------------------------

@Composable
private fun LogScreen(
    deviceId: String,
    logs: List<LogEntryUi>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    var paused by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("ALL") }
    val listState = rememberLazyListState()

    val filtered = remember(logs.size, filter) {
        if (filter == "ALL") logs else logs.filter { it.level == filter }
    }

    // Auto-scroll ke bawah saat log baru masuk (jika tidak pause).
    LaunchedEffect(filtered.size, paused) {
        if (!paused && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("← ", color = TextDim, fontSize = 18.sp)
            }
            Text("LOGS", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = paused,
                onClick = { paused = !paused },
                label = { Text(if (paused) "PAUSED" else "LIVE", fontSize = 12.sp) }
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRefresh) { Text("Muat", color = TextDim, fontSize = 12.sp) }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL", "INFO", "WARN", "ERROR").forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Line,
                        selectedLabelColor = if (f == "ERROR") Danger else if (f == "WARN") Warn else Accent,
                    )
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(CardBg, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered.size) { idx ->
                val e = filtered[idx]
                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.ts))
                Column {
                    Row {
                        Text(time, color = TextDim, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            e.level,
                            color = when (e.level) {
                                "ERROR" -> Danger
                                "WARN" -> Warn
                                else -> Accent
                            },
                            fontSize = 11.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    Text(e.message, color = TextMain, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text("Belum ada log.\nTekan 'Muat' untuk mengambil log dari server.",
                        color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

// Helper kecil
private fun kotlinx.coroutines.CoroutineScope.launchSnack(
    showSnack: suspend (String) -> Unit, text: String,
) = launch { showSnack(text) }
