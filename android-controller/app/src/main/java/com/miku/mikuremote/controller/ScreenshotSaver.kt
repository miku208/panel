package com.miku.mikuremote.controller

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/** Simpan screenshot JPEG dari HP server ke galeri (Pictures/MikuRemote). */
object ScreenshotSaver {

    /**
     * Coba simpan jika buffer adalah JPEG (SOI marker FFD8). Return path/null.
     * Aman dipanggil untuk frame binary apa pun (video frame bukan JPEG).
     */
    fun saveIfJpeg(context: Context, bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null // bukan JPEG

        return try {
            val name = "MikuRemote_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MikuRemote")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            "Pictures/MikuRemote/$name"
        } catch (_: Exception) {
            null
        }
    }
}
