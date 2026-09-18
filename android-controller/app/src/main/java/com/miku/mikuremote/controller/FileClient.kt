package com.miku.mikuremote.controller

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Client File Manager di sisi controller (Phase F).
 *
 * Download: chunk binary dari HP server (sliding window, ACK per chunk) ->
 * ditulis incremental ke file .part -> CRC32 diverifikasi saat file_done ->
 * baru dipindah ke Download/MikuRemote (tidak ada file setengah jadi yang
 * dianggap utuh).
 *
 * Upload: file lokal dibaca per-chunk, dikirim binary bertag setelah
 * file_upload_ready, window terjaga lewat file_ack dari device, ditutup
 * dengan file_done (CRC32 diverifikasi device sebelum rename).
 *
 * Framing binary (tag byte pertama):
 *   0x01 download chunk: [0x01][idLen][transferId][seq:4 BE][data]
 *   0x02 upload chunk:   [0x02][idLen][transferId][seq:4 BE][data]
 */
object FileClient {

    const val TAG_DOWNLOAD: Int = 0x01
    const val TAG_UPLOAD: Int = 0x02

    const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024 // pra-check; device otoritatif

    private const val UPLOAD_CHUNK = 48 * 1024
    private const val UPLOAD_WINDOW = 8

    class DownloadState(
        val transferId: String,
        val name: String,
        val tmp: File,
        val raf: RandomAccessFile,
        val crc: CRC32,
        val onProgress: (received: Long, total: Long) -> Unit,
        val onDone: (savedTo: String) -> Unit,
        val onError: (String) -> Unit,
    ) {
        var total: Long = 0
        var receivedSeq = 0
        var finished = false
    }

    class UploadState(
        val transferId: String,
        val local: File,
        val remoteDir: String,
        val raf: RandomAccessFile,
        val crc: CRC32,
        val onProgress: (sent: Long, total: Long) -> Unit,
        val onDone: () -> Unit,
        val onError: (String) -> Unit,
    ) {
        var sentSeq = 0
        var ackedSeq = 0
        var finished = false
        val total: Long get() = local.length()
    }

    @Volatile var activeDownload: DownloadState? = null
    @Volatile var activeUpload: UploadState? = null

    @Volatile var currentDeviceId: String? = null

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    /** file_download_start: metadata transfer dari device. */
    fun onDownloadStart(msg: JSONObject): Boolean {
        val st = activeDownload ?: return false
        if (msg.optString("transferId") != st.transferId) return false
        st.total = msg.optLong("size", 0)
        st.onProgress(0, st.total)
        return true
    }

    /** Frame binary masuk dari WS. Return true jika frame milik file transfer. */
    fun onBinary(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val tag = bytes[0].toInt() and 0xFF
        if (tag != TAG_DOWNLOAD && tag != TAG_UPLOAD) return false
        val idLen = bytes[1].toInt() and 0xFF
        if (idLen == 0 || 6 + idLen > bytes.size) return false
        val transferId = String(bytes, 2, idLen, Charsets.US_ASCII)
        val seq = ((bytes[2 + idLen].toInt() and 0xFF) shl 24) or
            ((bytes[3 + idLen].toInt() and 0xFF) shl 16) or
            ((bytes[4 + idLen].toInt() and 0xFF) shl 8) or
            (bytes[5 + idLen].toInt() and 0xFF)

        if (tag == TAG_DOWNLOAD) {
            val st = activeDownload ?: return false // tidak ada transfer: bukan frame file
            if (transferId != st.transferId || st.finished) return true // frame basi: telan
            if (seq != st.receivedSeq + 1) return true // in-order saja
            val data = bytes.copyOfRange(6 + idLen, bytes.size)
            return try {
                st.raf.write(data)
                st.crc.update(data)
                st.receivedSeq = seq
                st.onProgress(minOf(st.receivedSeq.toLong() * 48 * 1024, st.total), st.total)
                // ACK -> window device mengalir.
                ControllerSocket.send(
                    JSONObject().put("type", "file_ack")
                        .put("deviceId", currentDeviceId ?: "")
                        .put("transferId", st.transferId)
                        .put("ackedSeq", seq)
                )
                true
            } catch (e: Exception) {
                val err = st
                activeDownload = null
                runCatching { err.raf.close() }
                runCatching { err.tmp.delete() }
                err.onError("Gagal menulis: ${e.message}")
                true
            }
        } else {
            // Chunk upload tidak mengalir ke controller. Hanya telan bila
            // memang ada upload aktif; selain itu biarkan handler lain memproses.
            return activeUpload != null
        }
    }

    /**
     * file_done: device menyelesaikan transfer + kirim CRC32.
     * Upload  : CRC diverifikasi device sebelum rename -> sukses.
     * Download: CRC diverifikasi di sini sebelum file dipindah final.
     */
    fun onFileDone(msg: JSONObject): Boolean {
        val transferId = msg.optString("transferId")
        activeUpload?.let { up ->
            if (up.transferId == transferId && !up.finished) {
                runCatching { up.raf.close() }
                activeUpload = null
                up.finished = true
                up.onDone()
                return true
            }
        }
        activeDownload?.let { dl ->
            if (dl.transferId == transferId && !dl.finished) {
                dl.finished = true
                activeDownload = null
                return try {
                    runCatching { dl.raf.close() }
                    if (msg.optLong("crc32", -1) != dl.crc.value) {
                        dl.tmp.delete()
                        dl.onError("CRC32 tidak cocok — file dibuang")
                        return true
                    }
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "MikuRemote",
                    )
                    dir.mkdirs()
                    var out = File(dir, dl.name)
                    if (out.exists()) {
                        out = File(dir, dl.name.substringBeforeLast('.') + "-" +
                            System.currentTimeMillis() + "." + dl.name.substringAfterLast('.', "bin"))
                    }
                    if (!dl.tmp.renameTo(out)) {
                        dl.tmp.delete()
                        dl.onError("Gagal menyimpan file final")
                        return true
                    }
                    dl.onProgress(dl.total, dl.total)
                    dl.onDone(out.absolutePath)
                    true
                } catch (e: Exception) {
                    runCatching { dl.tmp.delete() }
                    dl.onError("Gagal finalisasi: ${e.message}")
                    true
                }
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    /** file_ack dari device (chunk upload ter-ACK) -> jaga window. */
    fun onUploadAck(msg: JSONObject): Boolean {
        val st = activeUpload ?: return false
        if (msg.optString("transferId") != st.transferId || st.finished) return false
        st.ackedSeq = msg.optInt("ackedSeq", st.ackedSeq)
        sendUploadWindow(st)
        return true
    }

    /** file_upload_ready: device siap menerima chunk. */
    fun onUploadReady(msg: JSONObject): Boolean {
        val st = activeUpload ?: return false
        if (msg.optString("transferId") != st.transferId || st.finished) return false
        sendUploadWindow(st)
        return true
    }

    private fun sendUploadWindow(st: UploadState) {
        try {
            val buf = ByteArray(UPLOAD_CHUNK)
            while (st.sentSeq - st.ackedSeq < UPLOAD_WINDOW) {
                val n = st.raf.read(buf)
                if (n <= 0) return // file habis; selesai via file_done (CRC)
                st.sentSeq += 1
                st.crc.update(buf, 0, n)
                st.onProgress(minOf(st.sentSeq.toLong() * UPLOAD_CHUNK, st.total), st.total)
                sendChunk(st, st.sentSeq, buf, n)
            }
        } catch (e: Exception) {
            val err = st
            activeUpload = null
            runCatching { err.raf.close() }
            err.finished = true
            err.onError("Gagal membaca file lokal: ${e.message}")
        }
    }

    private fun sendChunk(st: UploadState, seq: Int, data: ByteArray, len: Int) {
        val id = st.transferId.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(2 + id.size + 4 + len)
        out[0] = TAG_UPLOAD.toByte()
        out[1] = id.size.toByte()
        System.arraycopy(id, 0, out, 2, id.size)
        out[2 + id.size] = (seq ushr 24).toByte()
        out[3 + id.size] = (seq ushr 16).toByte()
        out[4 + id.size] = (seq ushr 8).toByte()
        out[5 + id.size] = seq.toByte()
        System.arraycopy(data, 0, out, 6 + id.size, len)
        ControllerSocket.sendBinary(out)
    }

    // ------------------------------------------------------------------
    // Mulai transfer (dipanggil UI)
    // ------------------------------------------------------------------

    fun startDownload(
        deviceId: String, path: String, name: String,
        onProgress: (Long, Long) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit,
    ): String? {
        if (activeDownload != null || activeUpload != null) {
            onError("Ada transfer berjalan — batalkan dulu")
            return null
        }
        val ctx = AppContextHolder.get()
        val transferId = "dl-" + UUID.randomUUID().toString().take(12)
        currentDeviceId = deviceId
        return try {
            val dir = File(ctx.cacheDir, "dl")
            dir.mkdirs()
            val tmp = File(dir, "$transferId.part")
            val st = DownloadState(
                transferId, name, tmp,
                RandomAccessFile(tmp, "rw"), CRC32(), onProgress, onDone, onError,
            )
            activeDownload = st
            val ok = ControllerSocket.send(
                JSONObject().put("type", "file_op").put("deviceId", deviceId)
                    .put("op", "download").put("path", path).put("transferId", transferId)
            )
            if (!ok) {
                activeDownload = null
                runCatching { st.raf.close() }
                onError("Koneksi ke VPS terputus")
                null
            } else transferId
        } catch (e: Exception) {
            onError("Gagal menyiapkan penyimpanan: ${e.message}")
            null
        }
    }

    fun startUpload(
        deviceId: String, local: File, remoteDir: String,
        onProgress: (Long, Long) -> Unit, onDone: () -> Unit, onError: (String) -> Unit,
    ): String? {
        if (activeDownload != null || activeUpload != null) {
            onError("Ada transfer berjalan — batalkan dulu")
            return null
        }
        if (local.length() > MAX_UPLOAD_BYTES) {
            onError("File melebihi batas upload (200 MB)")
            return null
        }
        val transferId = "ul-" + UUID.randomUUID().toString().take(12)
        currentDeviceId = deviceId
        return try {
            val st = UploadState(
                transferId, local, remoteDir,
                RandomAccessFile(local, "r"), CRC32(), onProgress, onDone, onError,
            )
            activeUpload = st
            val ok = ControllerSocket.send(
                JSONObject().put("type", "file_op").put("deviceId", deviceId)
                    .put("op", "upload").put("path", remoteDir)
                    .put("name", local.name).put("size", local.length())
                    .put("transferId", transferId)
            )
            if (!ok) {
                activeUpload = null
                runCatching { st.raf.close() }
                onError("Koneksi ke VPS terputus")
                null
            } else transferId
        } catch (e: Exception) {
            onError("Gagal membuka file lokal: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // Cancel & cleanup
    // ------------------------------------------------------------------

    /**
     * file_result op=download success=false (mis. file tidak ada):
     * bersihkan state download dan laporkan error. Return true jika cocok.
     */
    fun failDownload(msg: JSONObject): Boolean {
        val st = activeDownload ?: return false
        if (msg.optString("op") != "download") return false
        if (msg.optString("requestId") != st.transferId) return false
        activeDownload = null
        st.finished = true
        runCatching { st.raf.close() }
        runCatching { st.tmp.delete() }
        st.onError(msg.optString("error", "Download gagal"))
        return true
    }

    /** file_result op=upload success=false: bersihkan state upload. */
    fun failUpload(msg: JSONObject): Boolean {
        val st = activeUpload ?: return false
        if (msg.optString("op") != "upload") return false
        if (msg.optString("requestId") != st.transferId) return false
        activeUpload = null
        st.finished = true
        runCatching { st.raf.close() }
        st.onError(msg.optString("error", "Upload gagal"))
        return true
    }

    fun cancelActive() {
        activeDownload?.let { st ->
            activeDownload = null
            st.finished = true
            runCatching { st.raf.close() }
            runCatching { st.tmp.delete() }
            currentDeviceId?.let {
                ControllerSocket.send(
                    JSONObject().put("type", "file_cancel").put("deviceId", it)
                        .put("transferId", st.transferId)
                )
            }
        }
        activeUpload?.let { st ->
            activeUpload = null
            st.finished = true
            runCatching { st.raf.close() }
            currentDeviceId?.let {
                ControllerSocket.send(
                    JSONObject().put("type", "file_cancel").put("deviceId", it)
                        .put("transferId", st.transferId)
                )
            }
        }
    }

    /** Bersihkan transfer lokal tanpa cancel ke device (device offline dll). */
    fun cleanupLocal() {
        activeDownload?.let { runCatching { it.raf.close() }; runCatching { it.tmp.delete() } }
        activeDownload = null
        activeUpload?.let { runCatching { it.raf.close() } }
        activeUpload = null
    }
}
