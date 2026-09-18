package com.miku.mikuremote.controller

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

private val LsCard = Color(0xFF161B22)
private val LsAccent = Color(0xFF39D98A)
private val LsWarn = Color(0xFFE3B341)
private val LsDanger = Color(0xFFF85149)
private val LsText = Color(0xFFE6EDF3)
private val LsDim = Color(0xFF8B949E)

/**
 * Layar live screen (Phase 3): SurfaceView + MediaCodec decoder.
 *
 * Alur: buka layar -> kirim screen_start ke device (via VPS) -> device
 * menampilkan dialog consent (jika sesi belum ada) -> encoder H.264 device
 * mengirim binary frame -> decoder controller merender ke Surface.
 * Ganti kualitas = kirim screen_start ulang (device restart encoder).
 */
@Composable
fun LiveScreenOverlay(
    deviceId: String,
    onBack: () -> Unit,
    showSnack: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf("starting") } // starting | live | stopped | error
    var phaseMsg by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var fpsCount by remember { mutableIntStateOf(0) }
    var fpsShow by remember { mutableIntStateOf(0) }
    var quality by remember { mutableStateOf("medium") }
    // Estimasi bitrate dari frame H.264 yang masuk (update per detik).
    var bytesAcc by remember { mutableIntStateOf(0) }
    var bitrateShow by remember { mutableIntStateOf(0) }

    val decoderRef = remember { java.util.concurrent.atomic.AtomicReference<ScreenDecoder?>(null) }
    val surfaceReady = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val pendingFrames = remember { java.util.concurrent.ConcurrentLinkedQueue<ByteArray>() }

    fun qualityParams(q: String): Triple<Int, Int, Int> = when (q) {
        "high" -> Triple(1600, 900, 2500)
        "low" -> Triple(854, 480, 700)
        else -> Triple(1280, 720, 1200)
    }

    // ---- Terima frame binary + event screen dari socket ----
    LaunchedEffect(Unit) {
        launch {
            ControllerSocket.binary.collect { frame ->
                // JPEG (screenshot) bukan bagian dari stream video.
                if (frame.size > 2 && frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte()) {
                    return@collect
                }
                fpsCount++
                bytesAcc += frame.size
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
                    "screen_meta" -> {
                        meta = Triple(msg.optInt("width"), msg.optInt("height"), msg.optInt("fps"))
                        phase = "live"
                    }
                    "screen_stopped" -> if (phase != "error") phase = "stopped"
                    "screen_error" -> {
                        phase = "error"
                        phaseMsg = msg.optString("error", "Capture gagal")
                    }
                }
            }
        }
    }

    // ---- Kirim screen_start saat layar dibuka / kualitas berubah ----
    LaunchedEffect(quality) {
        phase = "starting"
        phaseMsg = ""
        meta = null
        val (w, h, kbps) = qualityParams(quality)
        val ok = ControllerSocket.send(
            JSONObject().put("type", "screen_start")
                .put("deviceId", deviceId)
                .put("width", w).put("height", h).put("bitrateKbps", kbps)
        )
        if (!ok) {
            phase = "error"
            phaseMsg = "Koneksi ke VPS terputus"
        }
    }

    // ---- FPS + bitrate meter ----
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            fpsShow = fpsCount
            bitrateShow = bytesAcc
            fpsCount = 0
            bytesAcc = 0
        }
    }

    // ---- Cleanup saat keluar layar ----
    DisposableEffect(Unit) {
        onDispose {
            ControllerSocket.send(JSONObject().put("type", "screen_stop").put("deviceId", deviceId))
            decoderRef.get()?.stop()
            decoderRef.set(null)
            surfaceReady.set(false)
            pendingFrames.clear()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Top bar ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("← ", color = LsDim, fontSize = 18.sp)
            }
            Text("LIVE", color = LsDanger, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            meta?.let { (w, h, f) ->
                Text("${w}×${h} • ${f}fps", color = LsDim, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("$fpsShow fps • %.1f Mbps".format(bitrateShow * 8f / 1_000_000f), color = if (fpsShow > 0) LsAccent else LsWarn, fontSize = 12.sp)
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
                                    onError = { msg ->
                                        android.util.Log.w("MikuRemote", "decoder: $msg")
                                    },
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

            // ---- Overlay status ----
            if (phase != "live") {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .background(LsCard.copy(alpha = 0.88f), RoundedCornerShape(10.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (phase) {
                        "starting" -> {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = LsAccent)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Menunggu stream dari HP server…\nJika muncul dialog izin di HP server, terima dulu.",
                                color = LsText, fontSize = 13.sp, lineHeight = 18.sp
                            )
                        }
                        "error" -> Text("⚠ $phaseMsg", color = LsDanger, fontSize = 13.sp)
                        else -> Text("Stream berhenti", color = LsWarn, fontSize = 13.sp)
                    }
                }
            }
        }

        // ---- Bottom bar: kualitas + screenshot ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("low", "medium", "high").forEach { q ->
                FilterChip(
                    selected = quality == q,
                    onClick = { if (quality != q) quality = q },
                    label = { Text(q.uppercase(), fontSize = 11.sp) }
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val ok = ControllerSocket.send(
                    JSONObject().put("type", "device_command")
                        .put("deviceId", deviceId).put("command", "SCREENSHOT")
                        .put("cmdId", "shot_" + System.currentTimeMillis())
                )
                scope.launch {
                    showSnack(if (ok) "Screenshot diminta…" else "Koneksi ke VPS terputus")
                }
            }) { Text("📷 Screenshot", color = LsText, fontSize = 12.sp) }
        }
    }
}
