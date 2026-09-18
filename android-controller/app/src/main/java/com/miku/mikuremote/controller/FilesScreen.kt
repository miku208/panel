package com.miku.mikuremote.controller

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private val FBg = Color(0xFF0E1116)
private val FCard = Color(0xFF161B22)
private val FAccent = Color(0xFF39D98A)
private val FWarn = Color(0xFFE3B341)
private val FDanger = Color(0xFFF85149)
private val FTextMain = Color(0xFFE6EDF3)
private val FTextDim = Color(0xFF8B949E)
private val FLine = Color(0xFF21262D)

/**
 * Remote File Manager (Phase F).
 * Root di HP server: /storage/emulated/0 (divalidasi & dibatasi device).
 * Semua op lewat VPS (file_op) — VPS memverifikasi kepemilikan device.
 */
@Composable
fun FilesScreen(
    deviceId: String,
    deviceOnline: Boolean,
    onBack: () -> Unit,
    showSnack: suspend (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var cwd by remember { mutableStateOf("") }
    val entries = remember { mutableStateListOf<JSONObject>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyOp by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var pendingRename by remember { mutableStateOf<JSONObject?>(null) }
    var pendingDelete by remember { mutableStateOf<JSONObject?>(null) }
    var showUploadInfo by remember { mutableStateOf(false) }

    // Callback op mutasi (mkdir/rename/delete) berdasarkan requestId.
    val pendingOps = remember { mutableMapOf<String, () -> Unit>() }

    // Progress transfer aktif (satu pada satu waktu).
    var transferLabel by remember { mutableStateOf<String?>(null) }
    var transferPct by remember { mutableStateOf(-1) }

    fun requestId() = UUID.randomUUID().toString().take(12)

    fun refresh(path: String) {
        if (!deviceOnline) {
            loading = false
            error = "Device offline — hidupkan HP server dahulu"
            return
        }
        loading = true
        error = null
        val ok = ControllerSocket.send(
            JSONObject().put("type", "file_op").put("deviceId", deviceId)
                .put("op", "list").put("path", path).put("requestId", requestId())
        )
        if (!ok) {
            loading = false
            error = "Koneksi ke VPS terputus"
        }
    }

    fun fileOp(op: String, path: String, newName: String? = null, onOk: () -> Unit = {}) {
        if (!deviceOnline) {
            scope.launch { showSnack("Device offline") }
            return
        }
        val rid = requestId()
        busyOp = op
        pendingOps[rid] = {
            busyOp = null
            onOk()
            refresh(cwd)
        }
        val ok = ControllerSocket.send(
            JSONObject().put("type", "file_op").put("deviceId", deviceId)
                .put("op", op).put("path", path)
                .apply { newName?.let { put("newName", it) } }
                .put("requestId", rid)
        )
        if (!ok) {
            pendingOps.remove(rid)
            busyOp = null
            scope.launch { showSnack("Koneksi ke VPS terputus") }
        }
    }

    fun startDownload(e: JSONObject) {
        val name = e.optString("name")
        transferLabel = "Download $name"
        transferPct = 0
        val started = FileClient.startDownload(
            deviceId, e.optString("path"), name,
            onProgress = { rec, total ->
                transferPct = if (total > 0) (rec * 100 / total).toInt() else 0
            },
            onDone = { savedTo ->
                transferLabel = null
                transferPct = -1
                scope.launch { showSnack("Tersimpan: $savedTo") }
            },
            onError = { msg ->
                transferLabel = null
                transferPct = -1
                scope.launch { showSnack(msg) }
            },
        )
        if (started == null) {
            transferLabel = null
            transferPct = -1
        }
    }

    fun startUpload(uri: android.net.Uri) {
        scope.launch {
            val local = withContext(Dispatchers.IO) { UriSaver.copyToCache(ctx, uri) }
            if (local == null) {
                showSnack("Gagal membaca file terpilih")
                return@launch
            }
            transferLabel = "Upload ${local.name}"
            transferPct = 0
            val started = FileClient.startUpload(
                deviceId, local, cwd,
                onProgress = { sent, total ->
                    transferPct = if (total > 0) (sent * 100 / total).toInt() else 0
                },
                onDone = {
                    transferLabel = null
                    transferPct = -1
                    local.delete()
                    scope.launch { showSnack("Upload selesai ✓") }
                    refresh(cwd)
                },
                onError = { msg ->
                    transferLabel = null
                    transferPct = -1
                    local.delete()
                    scope.launch { showSnack(msg) }
                },
            )
            if (started == null) {
                transferLabel = null
                transferPct = -1
                local.delete()
            }
        }
    }

    // ---- Muat folder pertama + collect event ----
    LaunchedEffect(Unit) {
        FileClient.cleanupLocal()
        refresh("")
    }

    // Binary frame (chunk download) -> FileClient.
    LaunchedEffect(Unit) {
        ControllerSocket.binary.collect { bytes ->
            FileClient.onBinary(bytes)
        }
    }

    // Bersihkan transfer saat keluar layar.
    DisposableEffect(Unit) {
        onDispose { FileClient.cancelActive() }
    }

    LaunchedEffect(deviceId) {
        ControllerSocket.events.collect { msg ->
            when (msg.optString("type")) {
                "file_result" -> {
                    if (msg.optString("deviceId") != deviceId) return@collect
                    val rid = msg.optString("requestId")
                    loading = false
                    if (msg.optBoolean("success")) {
                        when (msg.optString("op")) {
                            "list" -> {
                                val data = msg.optJSONObject("data")
                                if (data != null && data.optString("path") == cwd) {
                                    entries.clear()
                                    val arr = data.optJSONArray("entries")
                                    if (arr != null) {
                                        for (i in 0 until arr.length()) entries.add(arr.getJSONObject(i))
                                    }
                                }
                            }
                            "readText" -> {
                                preview = msg.optJSONObject("data")
                                busyOp = null
                                pendingOps.remove(rid)
                            }
                            "mkdir", "rename", "delete" -> {
                                pendingOps.remove(rid)?.invoke()
                            }
                        }
                    } else {
                        val err = msg.optString("error", "Operasi gagal")
                        when (msg.optString("op")) {
                            "list" -> error = err
                            "download" -> FileClient.failDownload(msg)
                            "upload" -> FileClient.failUpload(msg)
                            else -> scope.launch { showSnack(err) }
                        }
                        pendingOps.remove(rid)?.let {
                            scope.launch { showSnack(err) }
                        }
                        busyOp = null
                    }
                }
                "file_download_start" -> {
                    if (msg.optString("deviceId") == deviceId) FileClient.onDownloadStart(msg)
                }
                "file_upload_ready" -> {
                    if (msg.optString("deviceId") == deviceId) FileClient.onUploadReady(msg)
                }
                "file_ack" -> {
                    if (msg.optString("deviceId") == deviceId) FileClient.onUploadAck(msg)
                }
                "file_done" -> {
                    if (msg.optString("deviceId") == deviceId) FileClient.onFileDone(msg)
                }
            }
        }
    }

    // ---- Picker upload ----
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) startUpload(uri) }

    // ---- UI ----
    Column(Modifier.fillMaxSize().background(FBg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("← ", color = FTextDim, fontSize = 18.sp)
            }
            Column {
                Text("FILES", color = FTextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Root: /storage/emulated/0", color = FTextDim, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        // Navigasi path
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (cwd.isNotEmpty()) {
                TextButton(
                    onClick = {
                        cwd = cwd.substringBeforeLast('/', "")
                        refresh(cwd)
                    },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("⬆ ..", color = FAccent, fontSize = 13.sp) }
            }
            Text(
                "/" + cwd,
                color = FTextDim, fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(
                onClick = { refresh(cwd) },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Muat ulang", color = FTextDim, fontSize = 12.sp) }
        }

        // Tombol aksi
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (!deviceOnline) scope.launch { showSnack("Device offline") }
                    else uploadLauncher.launch(arrayOf("*/*"))
                },
                enabled = transferLabel == null,
            ) { Text("⬆ Upload", fontSize = 12.sp) }
            OutlinedButton(
                onClick = {
                    val base = if (cwd.isEmpty()) "Folder Baru" else "$cwd/Folder Baru"
                    fileOp("mkdir", base)
                },
                enabled = busyOp == null && transferLabel == null,
            ) { Text("📁 Folder", fontSize = 12.sp) }
        }

        if (!deviceOnline) {
            Spacer(Modifier.height(8.dp))
            Text("Device offline — operasi file dinonaktifkan", color = FWarn, fontSize = 12.sp)
        }

        // Progress transfer
        transferLabel?.let { label ->
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = FCard)) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Row {
                        Text(label, color = FTextMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            FileClient.cancelActive()
                            transferLabel = null; transferPct = -1
                        }, contentPadding = PaddingValues(0.dp)) {
                            Text("Batal", color = FDanger, fontSize = 12.sp)
                        }
                    }
                    if (transferPct >= 0) {
                        LinearProgressIndicator(
                            progress = { transferPct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = FAccent,
                        )
                        Text("$transferPct%", color = FTextDim, fontSize = 11.sp)
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = FDanger, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { refresh(cwd) }) { Text("Coba lagi") }
        }

        Spacer(Modifier.height(8.dp))

        if (loading) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FAccent)
                Spacer(Modifier.width(10.dp))
                Text("Memuat folder…", color = FTextDim, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FCard, RoundedCornerShape(12.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.optString("path") }) { e ->
                    FileItemRow(
                        e = e,
                        busy = busyOp != null || transferLabel != null,
                        onOpen = {
                            if (e.optBoolean("isDir")) {
                                cwd = e.optString("path")
                                refresh(cwd)
                            } else {
                                val rid = requestId()
                                busyOp = "readText"
                                pendingOps[rid] = { }
                                val ok = ControllerSocket.send(
                                    JSONObject().put("type", "file_op").put("deviceId", deviceId)
                                        .put("op", "readText").put("path", e.optString("path"))
                                        .put("requestId", rid)
                                )
                                if (!ok) {
                                    pendingOps.remove(rid)
                                    busyOp = null
                                    scope.launch { showSnack("Koneksi ke VPS terputus") }
                                }
                            }
                        },
                        onDownload = { startDownload(e) },
                        onRename = { pendingRename = e },
                        onDelete = { pendingDelete = e },
                    )
                }
                if (entries.isEmpty() && error == null) {
                    item {
                        Text(
                            "Folder kosong.",
                            color = FTextDim, fontSize = 13.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    // ---- Dialog ----
    pendingRename?.let { e ->
        var newName by remember(e) { mutableStateOf(e.optString("name")) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Ganti nama") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    singleLine = true, label = { Text("Nama baru") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = e.optString("path")
                    val n = newName.trim()
                    pendingRename = null
                    if (n.isNotEmpty() && n != e.optString("name")) fileOp("rename", target, n)
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text("Batal") } },
        )
    }

    pendingDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus?") },
            text = { Text("\"${e.optString("name")}\"${if (e.optBoolean("isDir")) " beserta isinya" else ""} akan dihapus permanen.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = e.optString("path")
                    pendingDelete = null
                    fileOp("delete", target)
                }) { Text("Hapus", color = FDanger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Batal") } },
        )
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(p.optString("path").substringAfterLast('/'), maxLines = 1) },
            text = {
                Column {
                    Text(
                        fmSize(p.optLong("size", 0)) + " • preview teks",
                        color = FTextDim, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        p.optString("text").ifBlank { "(kosong)" },
                        color = FTextMain, fontSize = 12.sp,
                        modifier = Modifier
                            .background(FCard, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .heightIn(max = 320.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Tutup") } },
        )
    }

    if (showUploadInfo) {
        AlertDialog(
            onDismissRequest = { showUploadInfo = false },
            title = { Text("Upload") },
            text = { Text("Pilih file dari HP ini untuk diunggah ke folder \"/$cwd\" di HP server.") },
            confirmButton = { TextButton(onClick = { showUploadInfo = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun FileItemRow(
    e: JSONObject,
    busy: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val isDir = e.optBoolean("isDir")
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) { onOpen() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isDir) "📁" else "📄",
                fontSize = 16.sp, modifier = Modifier.width(26.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(e.optString("name"), color = FTextMain, fontSize = 14.sp, maxLines = 1)
                Text(
                    if (isDir) "folder" else fmSize(e.optLong("size", 0)),
                    color = FTextDim, fontSize = 11.sp,
                )
            }
            Box {
                TextButton(onClick = { menu = true }, contentPadding = PaddingValues(4.dp)) {
                    Text("⋯", color = FTextDim, fontSize = 16.sp)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!isDir) {
                        DropdownMenuItem(
                            text = { Text("Download", color = FTextMain) },
                            onClick = { menu = false; onDownload() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Ganti nama", color = FTextMain) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Hapus", color = FDanger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = FLine, thickness = 0.5.dp)
}

private fun fmSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
