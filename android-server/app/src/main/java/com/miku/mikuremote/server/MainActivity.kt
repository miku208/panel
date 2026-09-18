package com.miku.mikuremote.server

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0E1116)
private val Card = Color(0xFF161B22)
private val Accent = Color(0xFF39D98A)
private val TextMain = Color(0xFFE6EDF3)
private val TextDim = Color(0xFF8B949E)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Server tetap start walau notifikasi ditolak — FGS tetap valid,
            // hanya notifikasinya yang tidak tampil. Dulu: jika ditolak, tombol
            // START terasa mati (service tidak pernah jalan).
            if (!granted) {
                LogBuffer.log("WARN", "Izin notifikasi ditolak — server tetap berjalan")
            }
            ServerService.start(this)
        }

    // Phase 3B: izin CAMERA untuk remote front camera (resmi, tanpa bypass).
    private val cameraPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) LogBuffer.log("INFO", "[CAMERA] Izin kamera diberikan")
            else LogBuffer.log("WARN", "[CAMERA] Izin kamera ditolak — FRONT CAMERA tidak akan jalan")
        }

    private fun requestCameraPermissionIfNeeded() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= 23) cameraPerm.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Status battery optimization resmi (PowerManager.isIgnoringBatteryOptimizations). */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Mode pribadi: jika kunci tersedia tapi device belum aktif, aktifkan
        // otomatis SEKALI di sini (tanpa pairing code, tanpa input).
        if (!prefs.isPaired && BuildConfig.PRIVATE_KEY.isNotBlank()) {
            val url = prefs.vpsUrl
            // WAJIB Dispatchers.IO: OkHttp execute() dilarang di main thread
            // (NetworkOnMainThreadException -> "Tidak dapat terhubung ke VPS").
            lifecycleScope.launch(Dispatchers.IO) {
                Api.privateActivate(url, BuildConfig.PRIVATE_KEY, prefs.deviceName)
                    .onSuccess { (id, token) ->
                        prefs.deviceId = id
                        prefs.deviceToken = token
                        LogBuffer.log("INFO", "Device aktif (mode pribadi)")
                    }
                    .onFailure { LogBuffer.log("ERROR", "Aktivasi gagal: ${it.message}") }
            }
        }

        setContent { ServerUi() }
    }

    @Composable
    private fun ServerUi() {
        val wsState by ServiceBus.wsState.collectAsState()
        val running by ServiceBus.running.collectAsState()
        val serverMode by ServiceBus.serverMode.collectAsState()
        val screenState by ServiceBus.screenState.collectAsState()
        val cameraState by ServiceBus.cameraState.collectAsState()
        val guardEnabled by ServiceBus.guardEnabled.collectAsState()
        val logs by LogBuffer.entries.collectAsState()
        val scope = lifecycleScope

        // Auto Start: toggle persisten (BootReceiver membaca nilai ini setelah reboot).
        var autoStart by remember { mutableStateOf(prefs.autoStart) }
        var batOptimized by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }

        var editUrl by remember { mutableStateOf(false) }
        var vpsUrl by remember { mutableStateOf(prefs.vpsUrl) }
        var manualKey by remember { mutableStateOf("") }
        var keyMsg by remember { mutableStateOf<String?>(null) }
        var keyBusy by remember { mutableStateOf(false) }
        var resetConfirm by remember { mutableStateOf(false) }

        // Mode pribadi aktif jika kunci di-inject saat build ATAU tersimpan di prefs.
        val hasKey = BuildConfig.PRIVATE_KEY.isNotBlank() || !prefs.privateKey.isNullOrBlank()
        val paired = prefs.isPaired

        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Bg, surface = Card, primary = Accent, onPrimary = Color.Black
            )
        ) {
            Surface(Modifier.fillMaxSize(), color = Bg) {
                Column(Modifier.padding(20.dp)) {
                    Text("MikuRemote", color = TextMain, fontSize = 26.sp)
                    Text("Server Device", color = TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(20.dp))

                    if (paired) {
                        // -------------------- AKTIF --------------------
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(prefs.deviceName, color = TextMain, fontSize = 18.sp)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val dot = when (wsState) {
                                        "ONLINE" -> Accent
                                        "CONNECTING" -> Color(0xFFE3B341)
                                        else -> Color(0xFFF85149)
                                    }
                                    Box(Modifier.size(8.dp).background(dot, MaterialTheme.shapes.small))
                                    Spacer(Modifier.width(6.dp))
                                    Text(wsState, color = TextMain, fontSize = 14.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("VPS: ${prefs.vpsUrl}", color = TextDim, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                // ---- Status ringkas Phase 3B ----
                                Text(
                                    "Screen: $screenState   •   Camera: $cameraState   •   Guard: ${if (guardEnabled) "ON" else "OFF"}",
                                    color = TextDim, fontSize = 12.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))

                        // ---- Auto Start + Reliability (Phase 4: unattended) ----
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("AUTO START SERVER", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Automatically start MikuRemote Server after device boot.",
                                            color = TextDim, fontSize = 12.sp
                                        )
                                    }
                                    Switch(
                                        checked = autoStart,
                                        onCheckedChange = {
                                            prefs.autoStart = it
                                            autoStart = it
                                            LogBuffer.log("INFO", "Auto Start ${if (it) "ON" else "OFF"}")
                                        }
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Text("SERVER RELIABILITY", color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                ReliabilityRow("Device paired", paired)
                                ReliabilityRow("Auto Start enabled", autoStart)
                                ReliabilityRow("Battery optimization ignored", batOptimized)
                                ReliabilityRow("Camera permission", hasCameraPermission())
                                ReliabilityRow("VPS ${when (wsState) { "ONLINE" -> "connected"; "CONNECTING" -> "connecting…"; else -> "disconnected" }}", wsState == "ONLINE")
                                if (!batOptimized) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Untuk server 24/7, izinkan MikuRemote berjalan di background dan " +
                                            "nonaktifkan battery restriction melalui pengaturan sistem (resmi).",
                                        color = TextDim, fontSize = 11.sp, lineHeight = 15.sp
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(onClick = {
                                        try {
                                            startActivity(
                                                android.content.Intent(
                                                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                                                )
                                            )
                                        } catch (e: Exception) {
                                            LogBuffer.log("WARN", "Gagal buka settings battery: ${e.message}")
                                        }
                                    }) { Text("Battery settings") }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!running) {
                                Button(onClick = {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        ServerService.start(this@MainActivity)
                                    }
                                }) { Text("START SERVER") }
                            } else {
                                OutlinedButton(onClick = { ServerService.stop(this@MainActivity) }) {
                                    Text("STOP")
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    ServerService.start(this@MainActivity)
                                    sendServerMode(!serverMode)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (serverMode) Accent else TextMain
                                )
                            ) { Text(if (serverMode) "SERVER MODE: ON" else "SERVER MODE: OFF") }
                        }

                        Spacer(Modifier.height(10.dp))
                        // Phase 3B: minta izin kamera sekali (resmi, runtime).
                        OutlinedButton(
                            onClick = { requestCameraPermissionIfNeeded() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)
                        ) { Text("📦 ENABLE FRONT CAMERA (izin kamera)") }

                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { editUrl = !editUrl }) {
                                Text("Ubah VPS", color = TextDim)
                            }
                            TextButton(onClick = { resetConfirm = true }) {
                                Text("Reset device", color = Color(0xFFF85149))
                            }
                        }

                        if (editUrl) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = vpsUrl, onValueChange = { vpsUrl = it },
                                label = { Text("VPS URL", color = TextDim) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                textStyle = LocalTextStyle.current.copy(color = TextMain, fontSize = 14.sp),
                            )
                            TextButton(onClick = {
                                prefs.vpsUrl = vpsUrl
                                editUrl = false
                                LogBuffer.log("INFO", "VPS URL diubah: $vpsUrl")
                            }) { Text("Simpan URL") }
                        }

                        if (resetConfirm) {
                            AlertDialog(
                                onDismissRequest = { resetConfirm = false },
                                title = { Text("Reset device?") },
                                text = { Text("Device akan didaftarkan ulang dengan ID baru saat aplikasi dibuka lagi.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        ServerService.stop(this@MainActivity)
                                        prefs.unpair()
                                        resetConfirm = false
                                        if (BuildConfig.PRIVATE_KEY.isNotBlank()) {
                                            // langsung aktifkan ulang dengan ID baru
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                Api.privateActivate(
                                                    prefs.vpsUrl, BuildConfig.PRIVATE_KEY, prefs.deviceName
                                                ).onSuccess { (id, token) ->
                                                    prefs.deviceId = id
                                                    prefs.deviceToken = token
                                                }
                                            }
                                        }
                                    }) { Text("Reset", color = Color(0xFFF85149)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { resetConfirm = false }) { Text("Batal") }
                                }
                            )
                        }
                    } else if (hasKey) {
                        // -------------------- MENUNGGU AKTIVASI --------------------
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Menghubungkan ke VPS…", color = TextMain, fontSize = 16.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Device akan aktif otomatis. Pastikan VPS URL benar lalu buka ulang aplikasi.",
                                    color = TextDim, fontSize = 12.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("VPS: ${prefs.vpsUrl}", color = TextDim, fontSize = 12.sp)
                            }
                        }
                    } else {
                        // -------------------- KUNCI MANUAL (build tanpa kunci) --------------------
                        OutlinedTextField(
                            value = vpsUrl, onValueChange = { vpsUrl = it },
                            label = { Text("VPS URL", color = TextDim) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = LocalTextStyle.current.copy(color = TextMain, fontSize = 14.sp),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = manualKey, onValueChange = { manualKey = it },
                            label = { Text("Kunci pribadi", color = TextDim) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Password
                            ),
                            textStyle = LocalTextStyle.current.copy(color = TextMain, fontSize = 14.sp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                keyBusy = true; keyMsg = null
                                prefs.vpsUrl = vpsUrl
                                scope.launch(Dispatchers.IO) {
                                    Api.privateActivate(vpsUrl, manualKey.trim(), prefs.deviceName)
                                        .onSuccess { (id, token) ->
                                            prefs.privateKey = manualKey.trim()
                                            prefs.deviceId = id
                                            prefs.deviceToken = token
                                            keyMsg = "Aktif ✓"
                                        }
                                        .onFailure { keyMsg = it.message }
                                    keyBusy = false
                                }
                            },
                            enabled = manualKey.isNotBlank() && !keyBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (keyBusy) "Memproses…" else "AKTIFKAN") }
                        keyMsg?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = if (it.endsWith("✓")) Accent else Color(0xFFF0883E), fontSize = 13.sp)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("LOG", color = TextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Card, MaterialTheme.shapes.small)
                            .padding(10.dp)
                    ) {
                        items(logs.asReversed()) { e ->
                            Text(
                                LogBuffer.format(e),
                                color = when (e.level) {
                                    "ERROR" -> Color(0xFFF85149)
                                    "WARN" -> Color(0xFFE3B341)
                                    else -> TextDim
                                },
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sendServerMode(on: Boolean) {
        val i = android.content.Intent(this, ServerService::class.java)
            .setAction(ServerService.ACTION_SET_SERVER_MODE)
            .putExtra(ServerService.EXTRA_ON, on)
        startService(i)
    }
}

@Composable
private fun ReliabilityRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            if (ok) "✓" else "•",
            color = if (ok) Accent else Color(0xFFE3B341),
            fontSize = 13.sp,
            modifier = Modifier.width(20.dp)
        )
        Text(label, color = if (ok) TextMain else TextDim, fontSize = 13.sp)
    }
}
