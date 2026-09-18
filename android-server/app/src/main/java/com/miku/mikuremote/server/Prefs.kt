package com.miku.mikuremote.server

import android.content.Context

/** Konfigurasi persisten. Disimpan di private storage aplikasi (bukan eksternal). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mikuremote", Context.MODE_PRIVATE)

    var vpsUrl: String
        get() = sp.getString(KEY_VPS, BuildConfig.DEFAULT_VPS_URL) ?: BuildConfig.DEFAULT_VPS_URL
        set(v) = sp.edit().putString(KEY_VPS, v.trim().trimEnd('/')).apply()

    var deviceToken: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    var deviceId: String?
        get() = sp.getString(KEY_DEVICE_ID, null)
        set(v) = sp.edit().putString(KEY_DEVICE_ID, v).apply()

    var deviceName: String
        get() = sp.getString(KEY_NAME, null) ?: android.os.Build.MODEL
        set(v) = sp.edit().putString(KEY_NAME, v).apply()

    /** Kunci pribadi mode personal (diisi manual jika tidak di-inject saat build). */
    var privateKey: String?
        get() = sp.getString(KEY_PRIVATE, null)
        set(v) = sp.edit().putString(KEY_PRIVATE, v).apply()

    /**
     * Auto Start Server: mulai service otomatis setelah boot (default ON).
     * BootReceiver hanya jalan jika device sudah paired + autoStart ON.
     */
    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_START, v).apply()

    /** Server Mode dipersistenkan agar kembali ON setelah reboot. */
    var serverModeOn: Boolean
        get() = sp.getBoolean(KEY_SERVER_MODE, false)
        set(v) = sp.edit().putBoolean(KEY_SERVER_MODE, v).apply()

    val isPaired: Boolean get() = !deviceToken.isNullOrBlank()

    fun unpair() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_DEVICE_ID).apply()
    }

    companion object {
        private const val KEY_VPS = "vps_url"
        private const val KEY_TOKEN = "device_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_NAME = "device_name"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_SERVER_MODE = "server_mode_on"
    }
}
