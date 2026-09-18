package com.miku.mikuremote.controller

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mikuremote_ctrl", Context.MODE_PRIVATE)

    var vpsUrl: String
        get() = sp.getString(KEY_VPS, BuildConfig.DEFAULT_VPS_URL) ?: BuildConfig.DEFAULT_VPS_URL
        set(v) = sp.edit().putString(KEY_VPS, v.trim().trimEnd('/')).apply()

    var userToken: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    var username: String?
        get() = sp.getString(KEY_USER, null)
        set(v) = sp.edit().putString(KEY_USER, v).apply()

    /** Kunci pribadi mode personal (diisi manual jika tidak di-inject saat build). */
    var privateKey: String?
        get() = sp.getString(KEY_PRIVATE, null)
        set(v) = sp.edit().putString(KEY_PRIVATE, v).apply()

    val isLoggedIn: Boolean get() = !userToken.isNullOrBlank()

    fun logout() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_USER).apply()
    }

    companion object {
        private const val KEY_VPS = "vps_url"
        private const val KEY_TOKEN = "user_token"
        private const val KEY_USER = "username"
        private const val KEY_PRIVATE = "private_key"
    }
}
