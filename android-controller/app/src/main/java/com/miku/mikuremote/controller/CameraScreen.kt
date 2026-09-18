package com.miku.mikuremote.controller

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.json.JSONObject

private val CsCard = Color(0xFF161B22)
private val CsAccent = Color(0xFF39D98A)
private val CsWarn = Color(0xFFE3B341)
private val CsDanger = Color(0xFFF85149)
private val CsText = Color(0xFFE6EDF3)
private val CsDim = Color(0xFF8B949E)

/**
 * Layar FRONT CAMERA remote (Phase 3B).
 *
 * Alur: buka layar -> camera_start ke device (via VPS) -> Camera2 device +
 * encoder H.264 -> binary frame -> decoder controller (reuse ScreenDecoder).
 * Konflik screen/camera ditolak oleh HP server dengan pesan jelas.
 */
@Composable
fun CameraScreenOverlay(
    deviceId: String,
    onBack: () -> Unit,
    showSnack: suspend (String) -> Unit,
) {
    var phase by remember { mutableStateOf("starting") } // starting | live | stopped | error
    var phaseMsg by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var fpsCount by remember { mutableIntStateOf(0) }
    var fpsShow by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }

    val decoderRef = remember { java.util.concurrent.atomic.AtomicReference<ScreenDecoder?>(null) }
    val surfaceReady = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val pendingFrames = remember { java.util.concurrent.ConcurrentLinkedQueue<ByteArray>() }

    // ---- Terima frame binary + event camera dari socket ----
    LaunchedEffect(Unit) {
        launch {
            ControllerSocket.binary.collect { frame ->
                // Jangan memberi makan decoder dengan JPEG (screenshot).
                if (frame.size > 2 && frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte()) return@collect
                fpsCount++
                val dec = decoderRef.get()
                if (dec != null && surfaceReady.get()) {
                    dec.feed(frame)
                } else {
                    pendingFrames.add(frame)
                    while (pendingFrames.size > 60) pendingFrames.poll()
                }
            }
        }
        launch {
            ControllerSocket.events.collect { msg ->
                when (msg.optString("type")) {
                    "camera_meta" -> {
                        meta = Pair(msg.optInt("width"), msg.optInt("height"))
                        phase = "live"
                    }
                    "camera_stopped" -> if (phase != "error") phase = "stopped"
                    "camera_error" -> {
                        phase = "error"
                        phaseMsg = msg.optString("error", "Camera gagal")
                    }
                }
            }
        }
    }

    // ---- Kirim camera_start saat layar dibuka ----
    LaunchedEffect(Unit) {
        phase = "starting"
        phaseMsg = ""
        meta = null
        val ok = ControllerSocket.send(
            JSONObject().put("type", "camera_start")
                .put("deviceId", deviceId)
                .put("camera", "front")
                .put("width", 640).put("height", 480)
                .put("fps", 12).put("bitrateKbps", 800)
        )
        if (!ok) {
            phase = "error"
            phaseMsg = "Koneksi ke VPS terputus"
        }
    }

    // ---- FPS meter ----
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            fpsShow = fpsCount
            fpsCount = 0
        }
    }

    // ---- Cleanup saat keluar layar ----
    DisposableEffect(Unit) {
        onDispose {
            ControllerSocket.send(JSONObject().put("type", "camera_stop").put("deviceId", deviceId))
            decoderRef.get()?.stop()
            decoderRef.set(null)
            surfaceReady.set(false)
            pendingFrames.clear()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Top bar (sembunyi saat fullscreen) ----
        if (!fullscreen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("← ", color = CsDim, fontSize = 18.sp)
                }
                Text("FRONT CAMERA", color = CsText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                if (phase == "live") {
                    Box(Modifier.size(8.dp).background(CsAccent, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(4.dp))
                    Text("LIVE", color = CsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                meta?.let { (w, h) -> Text("${w}×${h}", color = CsDim, fontSize = 12.sp) }
                Spacer(Modifier.width(10.dp))
                Text("$fpsShow fps", color = if (fpsShow > 0) CsAccent else CsWarn, fontSize = 12.sp)
            }
        }

        // ---- Video area ----
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surfaceReady.set(true)
                                val dec = ScreenDecoder(
                                    holder.surface,
                                    onSize = { _, _ -> },
                                    onError = { msg -> android.util.Log.w("MikuRemote", "cam decoder: $msg") },
                                )
                                decoderRef.set(dec)
                                dec.start()
                                var drained = pendingFrames.poll()
                                while (drained != null) {
                                    dec.feed(drained)
                                    drained = pendingFrames.poll()
                                }
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                surfaceReady.set(false)
                                decoderRef.getAndSet(null)?.stop()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Tap area untuk toggle fullscreen (overlay transparan di atas video).
            Box(
                Modifier.fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { fullscreen = !fullscreen }
            )

            // ---- Overlay status ----
            if (phase != "live") {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .background(CsCard.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (phase) {
                        "starting" -> {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = CsAccent)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Menyalakan kamera di HP server…\n(izin kamera harus sudah diberikan di HP server)",
                                color = CsText, fontSize = 13.sp, lineHeight = 18.sp
                            )
                        }
                        "error" -> {
                            val friendly = when {
                                phaseMsg.contains("CAMERA_PERMISSION_REQUIRED") ->
                                    "CAMERA PERMISSION REQUIRED\n\nBuka aplikasi MikuRemote Server di HP server, lalu tekan:\n\"ENABLE FRONT CAMERA (izin kamera)\""
                                phaseMsg.contains("STOP SCREEN STREAM") ->
                                    "STOP SCREEN STREAM TO USE CAMERA\n\nTutup Live Screen dulu, lalu buka kamera lagi."
                                else -> "⚠ $phaseMsg"
                            }
                            Text(friendly, color = CsDanger, fontSize = 13.sp, lineHeight = 18.sp)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    phase = "starting"; phaseMsg = ""
                                    ControllerSocket.send(
                                        JSONObject().put("type", "camera_start")
                                            .put("deviceId", deviceId)
                                            .put("camera", "front")
                                            .put("width", 640).put("height", 480)
                                            .put("fps", 12).put("bitrateKbps", 800)
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CsAccent, contentColor = Color.Black)
                            ) { Text("RETRY") }
                        }
                        else -> Text("Kamera berhenti", color = CsWarn, fontSize = 13.sp)
                    }
                }
            }

            // Tombol keluar fullscreen saat fullscreen aktif.
            if (fullscreen) {
                TextButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                ) { Text("✕", color = CsDim, fontSize = 16.sp) }
            }
        }

        // ---- Bottom bar (sembunyi saat fullscreen) ----
        if (!fullscreen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (phase) {
                        "live" -> "● CONNECTED"
                        "starting" -> "CONNECTING…"
                        "error" -> "ERROR"
                        else -> "STOPPED"
                    },
                    color = when (phase) {
                        "live" -> CsAccent
                        "error" -> CsDanger
                        else -> CsWarn
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    ControllerSocket.send(JSONObject().put("type", "camera_stop").put("deviceId", deviceId))
                }) { Text("STOP", color = CsDanger, fontSize = 13.sp) }
            }
        }
    }
}
