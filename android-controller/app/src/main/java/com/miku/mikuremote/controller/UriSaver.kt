package com.miku.mikuremote.controller

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Salin file dari content URI (hasil picker) ke cache lokal controller
 * agar bisa dibaca per-chunk secara streaming oleh FileClient.
 * File cache dihapus lagi setelah upload selesai/gagal.
 */
object UriSaver {

    fun copyToCache(context: Context, uri: Uri): File? {
        return try {
            val resolver = context.contentResolver
            val name = queryDisplayName(context, uri) ?: "upload-${System.currentTimeMillis()}"
            val safe = name.replace('/', '_').replace('\\', '_').take(200)
            val out = File(context.cacheDir, "up-$safe")
            resolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return null
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
