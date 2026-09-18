package com.miku.mikuremote.server

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import kotlin.math.max

/**
 * Server Mode: layar hitam penuh untuk mengurangi gangguan & burn-in saat
 * HP dibiarkan sebagai server. BUKAN display-off hardware (Android tidak
 * mengizinkan app biasa mematikan layar) — hanya blackout yang aman.
 * Service & koneksi WebSocket tetap berjalan normal.
 */
class BlackoutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
        setContentView(R.layout.activity_blackout)
        instance = this
    }

    // Tap layar dua kali untuk keluar dari blackout (pengaman).
    private var lastTap = 0L
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastTap < 400) finish()
            lastTap = now
        }
        return super.onTouchEvent(event)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: BlackoutActivity? = null

        fun show(context: Context) {
            if (instance != null) return
            context.startActivity(
                Intent(context, BlackoutActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        fun hide(context: Context) {
            instance?.finish() ?: run {
                // Jika activity belum jalan, cukup pastikan tidak ada task.
            }
        }
    }
}
