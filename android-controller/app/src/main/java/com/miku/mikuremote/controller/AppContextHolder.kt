package com.miku.mikuremote.controller

import android.content.Context

/** Akses Context dari object non-Composable (mis. FileClient untuk cacheDir). */
object AppContextHolder {
    @Volatile lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context = appContext
}
