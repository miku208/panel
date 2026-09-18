package com.miku.mikuremote.server

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.StatFs
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Server Guard: layar peringatan custom (Phase 3B).
 *
 * - Semua teks & kontak dari remote config ([GuardConfigStore]), bukan hardcoded.
 * - Tombol kontak memakai ACTION_DIAL (buka dialer, TIDAK auto-call) sesuai
 *   aturan resmi Android; disembunyikan jika nomor kosong.
 * - Menampilkan status resource server secara live (battery/RAM/storage/uptime).
 * - Bukan kiosk: pengaman keluar = tap 2x, sama seperti BlackoutActivity.
 */
class GuardWarningActivity : Activity() {

    private var lastTap = 0L
    private val infoTick = object : Runnable {
        override fun run() {
            refreshInfo()
            window.decorView.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
        buildUi()
        instance = this
    }

    override fun onResume() {
        super.onResume()
        applyConfig()
        window.decorView.removeCallbacks(infoTick)
        window.decorView.post(infoTick)
    }

    override fun onPause() {
        window.decorView.removeCallbacks(infoTick)
        super.onPause()
    }

    private fun buildUi() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        setContentView(root)
    }

    private fun root(): LinearLayout = findViewById(android.R.id.content) as LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun applyConfig() {
        val cfg = ServiceBus.guardConfig.value
        val root = root()
        root.removeAllViews()

        val title = TextView(this).apply {
            text = cfg.warningTitle.ifBlank { "HP INI SEDANG DIGUNAKAN SEBAGAI SERVER" }
            setTextColor(0xFFE3B341.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val msg = TextView(this).apply {
            text = cfg.warningMessage.ifBlank {
                "Jangan membuka game atau aplikasi berat.\nPenggunaan HP ini dapat mengganggu layanan server."
            }
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 15f
            setLineSpacing(dp(4).toFloat(), 1f)
            gravity = Gravity.CENTER
        }
        root.addView(msg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        // Info server live (opsional per config).
        val info = TextView(this).apply {
            setTextColor(0xFF8B949E.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        root.addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        // Tombol kontak: hanya jika nomor diisi.
        val number = cfg.warningContactNumber.trim()
        if (number.isNotEmpty()) {
            val btn = Button(this).apply {
                text = cfg.warningButtonText.ifBlank { "HUBUNGI ADMIN" }
                // Nomor bisa berisi karakter dialer (mis. +62...); Uri.encode agar aman.
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
                    } catch (e: Exception) {
                        Toast.makeText(this@GuardWarningActivity, "Tidak bisa membuka dialer: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            root.addView(btn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(28) })

            val contact = cfg.warningContactName.trim()
            if (contact.isNotEmpty()) {
                val c = TextView(this).apply {
                    text = "Kontak: $contact"
                    setTextColor(0xFF8B949E.toInt())
                    textSize = 12f
                    gravity = Gravity.CENTER
                }
                root.addView(c, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            }
        }

        val hint = TextView(this).apply {
            text = "Server Guard aktif • tap 2x untuk keluar"
            setTextColor(0x668B949E)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        root.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })
    }

    private fun refreshInfo() {
        val tv = (root().getChildAt(2) as? TextView) ?: return
        val cfg = ServiceBus.guardConfig.value
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val ramUsedPct = if (mem.totalMem > 0) ((mem.totalMem - mem.availMem) * 100 / mem.totalMem).toInt() else 0
        val stat = StatFs(android.os.Environment.getDataDirectory().absolutePath)
        val storageFreeGb = stat.availableBytes / 1.073741824e9
        val uptimeH = SystemClock.elapsedRealtime() / 3_600_000

        val parts = mutableListOf<String>()
        if (cfg.showBattery && bat > 0) parts.add("Battery: $bat%")
        if (cfg.showRAM) parts.add("RAM: $ramUsedPct%")
        if (cfg.showStorage) parts.add("Storage: %.1f GB free".format(storageFreeGb))
        if (cfg.showUptime) parts.add("Uptime: ${uptimeH}h")
        tv.text = parts.joinToString("   •   ")
    }

    // Tap dua kali untuk keluar (pengaman lokal, seperti blackout).
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
        private var instance: GuardWarningActivity? = null

        fun show(context: Context) {
            if (instance != null) return
            try {
                context.startActivity(
                    Intent(context, GuardWarningActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                LogBuffer.log("ERROR", "Gagal buka GuardWarning: ${e.message}")
            }
        }

        fun hide() {
            instance?.finish()
        }

        val isShowing: Boolean get() = instance != null
    }
}
