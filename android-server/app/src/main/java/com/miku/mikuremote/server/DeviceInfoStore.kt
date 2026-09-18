package com.miku.mikuremote.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.app.ActivityManager
import org.json.JSONObject

/** Info device dasar — tanpa data pribadi. */
object DeviceInfoStore {

    fun collect(context: Context): JSONObject {
        val jm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = jm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val charging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        // Suhu baterai (tenth of °C) — API resmi, tersedia di mayoritas device.
        val tempDeci = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val tempC = if (tempDeci != Int.MIN_VALUE) tempDeci / 10.0 else 0.0

        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val usedRamMb = (mem.totalMem - mem.availMem) / (1024 * 1024)
        val totalRamMb = mem.totalMem / (1024 * 1024)

        val uptimeH = SystemClock.elapsedRealtime() / 3_600_000

        return JSONObject().apply {
            put("deviceName", Prefs(context).deviceName)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("batteryPct", if (batteryPct > 0) batteryPct else JSONObject.NULL)
            put("charging", charging)
            put("storageFreeGb", Math.round(freeBytes / 1.073741824e9 * 10) / 10.0)
            put("storageTotalGb", Math.round(totalBytes / 1.073741824e9 * 10) / 10.0)
            put("ramUsedMb", usedRamMb)
            put("ramTotalMb", totalRamMb)
            put("batteryTempC", tempC)
            put("uptimeHours", uptimeH)
        }
    }
}
