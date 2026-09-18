package com.miku.mikuremote.server

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Activity tipis untuk menampilkan dialog consent MediaProjection sistem.
 * Hasilnya dikirim ke [ScreenConsentBus] (dikonsumsi ServerService) lalu
 * activity selesai. Tidak ada UI sendiri selain dialog sistem.
 */
class ScreenPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ScreenConsentBus.listener == null) {
            // Tidak ada yang menunggu (mis. service mati saat dialog muncul).
            finish()
            return
        }
        if (savedInstanceState != null) {
            // Hindari minta consent dua kali (rotasi/recreate).
            finish()
            return
        }
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(pm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            ScreenConsentBus.deliver(resultCode, data)
            finish()
        }
    }

    override fun onDestroy() {
        // Jika activity dibunuh tanpa jawaban (mis. user menutup dialog dari
        // recents), pastikan listener tidak menggantung selamanya.
        if (!isFinishing) ScreenConsentBus.deliver(RESULT_CANCELED, null)
        super.onDestroy()
    }

    companion object {
        private const val REQ = 4201
    }
}
