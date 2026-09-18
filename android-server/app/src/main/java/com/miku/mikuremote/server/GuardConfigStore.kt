package com.miku.mikuremote.server

import android.content.Context
import org.json.JSONObject

/**
 * Config Server Guard (Phase 3B).
 *
 * - Default di sisi APK hanyalah fallback; teks final SELALU bisa ditimpa
 *   dari Controller via VPS (tidak ada teks/nomor yang hardcoded permanen).
 * - Disimpan lokal (SharedPreferences) agar tetap berlaku setelah process
 *   death / reboot / offline, dan di-sync dari VPS saat online.
 */
data class GuardConfig(
    val enabled: Boolean = false,
    val warningEnabled: Boolean = true,
    val warningTitle: String = "HP INI SEDANG DIGUNAKAN SEBAGAI SERVER",
    val warningMessage: String = "Jangan membuka game atau aplikasi berat.\nPenggunaan HP ini dapat mengganggu layanan server.",
    val warningContactName: String = "",
    val warningContactNumber: String = "",
    val warningButtonText: String = "HUBUNGI ADMIN",
    val serverName: String = "",
    val serverLocationLabel: String = "",
    val showBattery: Boolean = true,
    val showTemperature: Boolean = true,
    val showRAM: Boolean = true,
    val showStorage: Boolean = true,
    val showUptime: Boolean = true,
    val batteryLowThreshold: Int = 20,
    val storageLowThresholdGb: Int = 2,
    val ramHighThreshold: Int = 90,
    val temperatureHighThreshold: Int = 45,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("warningEnabled", warningEnabled)
        put("warningTitle", warningTitle)
        put("warningMessage", warningMessage)
        put("warningContactName", warningContactName)
        put("warningContactNumber", warningContactNumber)
        put("warningButtonText", warningButtonText)
        put("serverName", serverName)
        put("serverLocationLabel", serverLocationLabel)
        put("showBattery", showBattery)
        put("showTemperature", showTemperature)
        put("showRAM", showRAM)
        put("showStorage", showStorage)
        put("showUptime", showUptime)
        put("batteryLowThreshold", batteryLowThreshold)
        put("storageLowThresholdGb", storageLowThresholdGb)
        put("ramHighThreshold", ramHighThreshold)
        put("temperatureHighThreshold", temperatureHighThreshold)
    }

    companion object {
        /** Parse dari JSON remote (VPS), dengan fallback aman per-field. */
        fun fromJson(o: JSONObject): GuardConfig = GuardConfig(
            enabled = o.optBoolean("enabled", false),
            warningEnabled = o.optBoolean("warningEnabled", true),
            warningTitle = o.optString("warningTitle", "HP INI SEDANG DIGUNAKAN SEBAGAI SERVER").take(500),
            warningMessage = o.optString("warningMessage", "").take(2000),
            warningContactName = o.optString("warningContactName", "").take(500),
            warningContactNumber = o.optString("warningContactNumber", "").take(100),
            warningButtonText = o.optString("warningButtonText", "HUBUNGI ADMIN").take(100),
            serverName = o.optString("serverName", "").take(200),
            serverLocationLabel = o.optString("serverLocationLabel", "").take(200),
            showBattery = o.optBoolean("showBattery", true),
            showTemperature = o.optBoolean("showTemperature", true),
            showRAM = o.optBoolean("showRAM", true),
            showStorage = o.optBoolean("showStorage", true),
            showUptime = o.optBoolean("showUptime", true),
            batteryLowThreshold = o.optInt("batteryLowThreshold", 20).coerceIn(0, 100),
            storageLowThresholdGb = o.optInt("storageLowThresholdGb", 2).coerceIn(0, 512),
            ramHighThreshold = o.optInt("ramHighThreshold", 90).coerceIn(0, 100),
            temperatureHighThreshold = o.optInt("temperatureHighThreshold", 45).coerceIn(20, 90),
        )
    }
}

class GuardConfigStore(context: Context) {
    private val sp = context.getSharedPreferences("mikuremote_guard", Context.MODE_PRIVATE)

    @Volatile
    var config: GuardConfig = load()
        private set

    private fun load(): GuardConfig {
        val raw = sp.getString(KEY_JSON, null) ?: return GuardConfig()
        return try { GuardConfig.fromJson(JSONObject(raw)) } catch (_: Exception) { GuardConfig() }
    }

    /** Terapkan config dari remote (VPS/Controller). Return true jika berubah. */
    fun applyRemote(json: JSONObject): GuardConfig {
        val incoming = GuardConfig.fromJson(json)
        val changed = incoming.toJson().toString() != config.toJson().toString()
        config = incoming
        sp.edit().putString(KEY_JSON, incoming.toJson().toString()).apply()
        return if (changed) incoming else incoming
    }

    companion object {
        private const val KEY_JSON = "guard_config_json"
    }
}
