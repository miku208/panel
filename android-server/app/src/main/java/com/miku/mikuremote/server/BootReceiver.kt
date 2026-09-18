package com.miku.mikuremote.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto Start (Phase 4): mulai ServerService setelah device selesai boot.
 *
 * - Resmi: RECEIVE_BOOT_COMPLETED + startForegroundService (tidak ada exploit).
 * - Cepat: tidak ada network/IO berat di sini — service yang menangani WS.
 * - Hanya start jika device sudah paired DAN Auto Start ON.
 * - LOCKED_BOOT_COMPLETED tidak dipakai (file prefs belum di-decrypt saat itu).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!prefs.isPaired || !prefs.autoStart) return

        // Deferrable di Android 15+: gunakan startForegroundService sesuai aturan
        // (receiver non-blocking; WS ditangani service, bukan di sini).
        try {
            ServerService.start(context)
            LogBuffer.log("INFO", "[BOOT] Auto start: service dijalankan setelah boot")
        } catch (e: Exception) {
            LogBuffer.log("ERROR", "[BOOT] Gagal auto start: ${e.message}")
        }
    }
}
