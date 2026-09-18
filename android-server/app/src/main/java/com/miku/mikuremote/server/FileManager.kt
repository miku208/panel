package com.miku.mikuremote.server

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Remote File Manager (Phase F).
 *
 * Keamanan:
 * - Hanya satu root yang diizinkan: external storage (/storage/emulated/0,
 *   a.k.a. /sdcard). Tidak ada akses /data, /system, /proc, /sys, /vendor,
 *   private app dirs, dsb.
 * - Semua path divalidasi via canonical path (menangkal ../ dan symlink
 *   escape): resolved canonical path HARUS berada di dalam root canonical.
 * - Tidak ada shell, tidak ada arbitrary exec — hanya operasi file terbatas.
 *
 * Transfer streaming (bounded memory):
 * - Download: chunk dibaca incremental dari RandomAccessFile, dikirim dengan
 *   sliding window (tunggu ACK controller sebelum lanjut) — file 1GB tidak
 *   pernah masuk RAM sekaligus.
 * - Upload: chunk ditulis incremental ke file .part, direname saat selesai
 *   setelah verifikasi CRC32.
 *
 * Framing binary (tag byte pertama frame):
 *   0x01 = download chunk: [tag][idLen][transferId][seq:4 BE][data]
 *   0x02 = upload chunk:   [tag][idLen][transferId][seq:4 BE][data]
 */
object FileManager {

    const val TAG_DOWNLOAD: Int = 0x01
    const val TAG_UPLOAD: Int = 0x02

    const val DEFAULT_CHUNK_BYTES = 48 * 1024          // 48 KB per frame
    const val DEFAULT_WINDOW = 8                        // chunk in-flight
    const val MAX_READ_TEXT_BYTES = 64 * 1024           // preview text
    const val MAX_LIST_ENTRIES = 1000

    internal fun root(): File = Environment.getExternalStorageDirectory()

    /** Izin akses file: All-Files-Access (API 30+) atau legacy storage (≤29). */
    fun hasStorageAccess(ctx: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun rootPath(): String = root().canonicalPath

    /** Validasi path relatif/absolus → File di dalam root. Null jika ditolak. */
    fun resolveSafe(rel: String): File? {
        if (rel.isBlank() || rel.length > 512) return null
        // Tolak segmen ".." secara eksplisit (pertahanan lapis 1).
        val normalized = rel.replace('\\', '/').trimStart('/')
        if (normalized.split('/').any { it == ".." }) return null
        return try {
            val f = File(root(), normalized)
            val canonical = f.canonicalPath
            val rootC = rootPath()
            // Pertahanan lapis 2: canonical path wajib di dalam root canonical
            // (menangkal symlink yang keluar dari root).
            if (canonical == rootC || canonical.startsWith(rootC + "/")) f else null
        } catch (_: Exception) {
            null
        }
    }

    fun safeEntryName(name: String): String? {
        val n = name.trim()
        if (n.isEmpty() || n == "." || n == ".." || n.contains('/') || n.contains('\\') ||
            n.contains('\u0000') || n.length > 255
        ) return null
        return n
    }

    // ------------------------------------------------------------------
    // Operasi file (panggil dari Dispatchers.IO)
    // ------------------------------------------------------------------

    fun list(rel: String): JSONObject? {
        val dir = resolveSafe(rel) ?: return null
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        val arr = JSONArray()
        files.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .take(MAX_LIST_ENTRIES)
            .forEach { f ->
                arr.put(JSONObject()
                    .put("name", f.name)
                    .put("path", f.relativeToOrNull(root())?.path ?: f.name)
                    .put("isDir", f.isDirectory)
                    .put("size", if (f.isFile) f.length() else 0)
                    .put("mtime", f.lastModified()))
            }
        return JSONObject()
            .put("path", rel)
            .put("entries", arr)
            .put("truncated", files.size > MAX_LIST_ENTRIES)
    }

    fun mkdir(rel: String): Boolean {
        val dir = resolveSafe(rel) ?: return false
        return dir.mkdirs() || dir.isDirectory
    }

    fun rename(rel: String, newName: String): Boolean {
        val f = resolveSafe(rel) ?: return false
        val n = safeEntryName(newName) ?: return false
        val target = File(f.parentFile, n)
        // Target harus tetap di dalam root.
        if (target.canonicalPath.startsWith(rootPath() + "/") == false) return false
        return f.renameTo(target)
    }

    fun delete(rel: String): Boolean {
        val f = resolveSafe(rel) ?: return false
        val rootC = rootPath()
        if (f.canonicalPath == rootC) return false // jangan hapus root
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    fun stat(rel: String): JSONObject? {
        val f = resolveSafe(rel) ?: return null
        if (!f.exists()) return null
        return JSONObject()
            .put("path", rel)
            .put("name", f.name)
            .put("isDir", f.isDirectory)
            .put("size", if (f.isFile) f.length() else 0)
            .put("mtime", f.lastModified())
            .put("readable", f.canRead())
            .put("writable", f.canWrite())
    }

    /** Preview text kecil (truncated). Null jika file tidak cocok/terlalu besar. */
    fun readText(rel: String): JSONObject? {
        val f = resolveSafe(rel) ?: return null
        if (!f.isFile) return null
        if (f.length() > MAX_READ_TEXT_BYTES) return null
        return try {
            val text = f.readText(Charsets.UTF_8)
            JSONObject().put("path", rel).put("size", f.length()).put("text", text)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Handler transfer file (download/upload streaming) milik ServerService.
 * Satu instance per service; semua state dibersihkan saat koneksi WS putus.
 */
class FileTransferHandler(
    private val ctx: Context,
    private val guardStore: GuardConfigStore,
    private val send: (JSONObject) -> Unit,
    private val sendBinary: (ByteArray) -> Unit,
) {
    companion object {
        const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024   // 200 MB (default; override via config)
        const val MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024 // 1 GB
    }

    private val downloads = ConcurrentHashMap<String, DownloadSession>()
    private val uploads = ConcurrentHashMap<String, UploadSession>()

    private class DownloadSession(
        val rel: String,
        val file: File,
        val chunkSize: Int,
        val window: Int,
        val raf: RandomAccessFile,
        val crc: CRC32,
        var seq: Int,           // chunk terakhir yang terkirim
        var acked: Int,         // chunk terakhir yang di-ACK controller
        val total: Long,
    )

    private class UploadSession(
        val dirRel: String,
        val name: String,
        val target: File,
        val part: File,
        val size: Long,
        val raf: RandomAccessFile,
        val crc: CRC32,
        var received: Int,
        var acked: Int,
        val maxBytes: Long,
    )

    // ------------------------------------------------------------------
    // Download (device -> controller)
    // ------------------------------------------------------------------

    fun startDownload(rel: String, transferId: String) {
        if (transferId.length > 64) return
        if (downloads.containsKey(transferId) || uploads.containsKey(transferId)) {
            send(fileErr("download", transferId, "transferId sudah dipakai"))
            return
        }
        val f = FileManager.resolveSafe(rel)
        if (f == null || !f.isFile) {
            send(fileErr("download", transferId, "File tidak ditemukan atau di luar root"))
            return
        }
        val size = f.length()
        if (size > MAX_DOWNLOAD_BYTES) {
            send(fileErr("download", transferId, "File melebihi batas download (1 GB)"))
            return
        }
        try {
            val raf = RandomAccessFile(f, "r")
            val s = DownloadSession(
                rel, f, FileManager.DEFAULT_CHUNK_BYTES, FileManager.DEFAULT_WINDOW,
                raf, CRC32(), 0, 0, size,
            )
            downloads[transferId] = s
            send(JSONObject()
                .put("type", "file_download_start")
                .put("transferId", transferId)
                .put("size", size)
                .put("chunkSize", s.chunkSize)
                .put("window", s.window)
                .put("name", f.name))
            LogBuffer.log("INFO", "[FILE] Download mulai: ${f.name} ($size B)")
            sendWindow(transferId, s)
        } catch (e: Exception) {
            send(fileErr("download", transferId, "Gagal membuka file: ${e.message}"))
        }
    }

    private fun sendWindow(transferId: String, s: DownloadSession) {
        val buf = ByteArray(s.chunkSize)
        while (s.seq - s.acked < s.window) {
            val n = s.raf.read(buf)
            if (n <= 0) {
                // Semua data terkirim; tunggu ACK terakhir (file_done di onAck).
                return
            }
            s.seq += 1
            s.crc.update(buf, 0, n)
            sendBinary(frame(FileManager.TAG_DOWNLOAD, transferId, s.seq, buf, n))
        }
    }

    fun onAck(transferId: String, ackedSeq: Int) {
        val s = downloads[transferId] ?: return
        if (ackedSeq <= s.acked || ackedSeq > s.seq) return
        s.acked = ackedSeq
        try {
            sendWindow(transferId, s)
            if (s.acked >= s.seq && s.raf.filePointer >= s.total) {
                // Semua chunk ter-ACK -> selesai.
                downloads.remove(transferId)
                runCatching { s.raf.close() }
                send(JSONObject()
                    .put("type", "file_done")
                    .put("transferId", transferId)
                    .put("crc32", s.crc.value))
                LogBuffer.log("INFO", "[FILE] Download selesai: ${s.file.name}")
            }
        } catch (e: Exception) {
            cancel(transferId)
            send(fileErr("download", transferId, "Gagal membaca file: ${e.message}"))
        }
    }

    // ------------------------------------------------------------------
    // Upload (controller -> device)
    // ------------------------------------------------------------------

    fun startUpload(transferId: String, dirRel: String, rawName: String, size: Long) {
        if (transferId.length > 64) return
        if (downloads.containsKey(transferId) || uploads.containsKey(transferId)) {
            send(fileErr("upload", transferId, "transferId sudah dipakai"))
            return
        }
        val dir = FileManager.resolveSafe(dirRel)
        if (dir == null || !dir.isDirectory) {
            send(fileErr("upload", transferId, "Folder tujuan tidak valid"))
            return
        }
        val name = FileManager.safeEntryName(rawName)
        if (name == null) {
            send(fileErr("upload", transferId, "Nama file tidak valid"))
            return
        }
        val maxBytes = guardStore.config.maxUploadSizeMb
            .takeIf { it > 0 }?.toLong()?.times(1024) // Mb -> KB
            ?.times(1024) ?: MAX_UPLOAD_BYTES
        if (size <= 0 || size > maxBytes) {
            send(fileErr("upload", transferId, "Ukuran melebihi batas upload (${maxBytes / 1024 / 1024} MB)"))
            return
        }
        val target = File(dir, name)
        val part = File(dir, ".$name.miku-${transferId.takeLast(8)}.part")
        if (part.canonicalPath.startsWith(FileManager.root().canonicalPath + "/") == false) {
            send(fileErr("upload", transferId, "Path tujuan di luar root"))
            return
        }
        try {
            part.delete()
            val raf = RandomAccessFile(part, "rw")
            uploads[transferId] = UploadSession(
                dirRel, name, target, part, size, raf, CRC32(), 0, 0, maxBytes,
            )
            send(JSONObject()
                .put("type", "file_upload_ready")
                .put("transferId", transferId))
            LogBuffer.log("INFO", "[FILE] Upload mulai: $name ($size B)")
        } catch (e: Exception) {
            send(fileErr("upload", transferId, "Gagal menyiapkan file: ${e.message}"))
        }
    }

    fun onUploadChunk(transferId: String, seq: Int, data: ByteArray) {
        val s = uploads[transferId] ?: return
        if (seq != s.received + 1) return // in-order saja (controller memakai window)
        try {
            s.raf.write(data)
            s.crc.update(data)
            s.received = seq
            // ACK setiap chunk (ringan, JSON kecil) agar window controller mengalir.
            s.acked = seq
            send(JSONObject().put("type", "file_ack")
                .put("transferId", transferId).put("ackedSeq", seq))
        } catch (e: Exception) {
            cancel(transferId)
            send(fileErr("upload", transferId, "Gagal menulis file: ${e.message}"))
        }
    }

    fun endUpload(transferId: String, crc32: Long) {
        val s = uploads.remove(transferId) ?: return
        try {
            s.raf.close()
            if (s.crc.value != crc32) {
                s.part.delete()
                send(fileErr("upload", transferId, "CRC32 tidak cocok — transfer korup"))
                return
            }
            if (s.target.exists()) s.target.delete() // overwrite
            if (!s.part.renameTo(s.target)) {
                send(fileErr("upload", transferId, "Gagal finalisasi file"))
                return
            }
            send(JSONObject().put("type", "file_done")
                .put("transferId", transferId)
                .put("crc32", s.crc.value))
            send(JSONObject().put("type", "file_result")
                .put("op", "upload").put("requestId", transferId)
                .put("success", true)
                .put("data", JSONObject().put("path", s.target.name).put("size", s.target.length())))
            LogBuffer.log("INFO", "[FILE] Upload selesai: ${s.target.name}")
        } catch (e: Exception) {
            send(fileErr("upload", transferId, "Gagal finalisasi: ${e.message}"))
        }
    }

    // ------------------------------------------------------------------
    // Binary framing & cleanup
    // ------------------------------------------------------------------

    /** Frame binary masuk dari WS (dari controller). */
    fun onBinary(bytes: ByteArray) {
        if (bytes.size < 6) return
        val tag = bytes[0].toInt() and 0xFF
        val idLen = bytes[1].toInt() and 0xFF
        if (idLen == 0 || 2 + idLen + 4 > bytes.size) return
        val transferId = String(bytes, 2, idLen, Charsets.US_ASCII)
        val seq = ((bytes[2 + idLen].toInt() and 0xFF) shl 24) or
            ((bytes[3 + idLen].toInt() and 0xFF) shl 16) or
            ((bytes[4 + idLen].toInt() and 0xFF) shl 8) or
            (bytes[5 + idLen].toInt() and 0xFF)
        when (tag) {
            FileManager.TAG_UPLOAD ->
                onUploadChunk(transferId, seq, bytes.copyOfRange(6 + idLen, bytes.size))
            // TAG_DOWNLOAD tidak valid arah controller->device; abaikan.
        }
    }

    private fun frame(tag: Int, transferId: String, seq: Int, data: ByteArray, len: Int): ByteArray {
        val id = transferId.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(2 + id.size + 4 + len)
        out[0] = tag.toByte()
        out[1] = id.size.toByte()
        System.arraycopy(id, 0, out, 2, id.size)
        out[2 + id.size] = (seq ushr 24).toByte()
        out[3 + id.size] = (seq ushr 16).toByte()
        out[4 + id.size] = (seq ushr 8).toByte()
        out[5 + id.size] = seq.toByte()
        System.arraycopy(data, 0, out, 6 + id.size, len)
        return out
    }

    private fun fileErr(op: String, requestId: String, error: String): JSONObject =
        JSONObject().put("type", "file_result")
            .put("op", op)
            .put("requestId", requestId)
            .put("success", false)
            .put("error", error)

    fun cancel(transferId: String) {
        downloads.remove(transferId)?.let { runCatching { it.raf.close() } }
        uploads.remove(transferId)?.let {
            runCatching { it.raf.close() }
            runCatching { it.part.delete() }
        }
    }

    /** Bersihkan semua transfer aktif (dipanggil saat WS putus / service stop). */
    fun cancelAll() {
        for (id in downloads.keys.toList()) cancel(id)
        for (id in uploads.keys.toList()) cancel(id)
    }

    fun hasActive(): Boolean = downloads.isNotEmpty() || uploads.isNotEmpty()
}
