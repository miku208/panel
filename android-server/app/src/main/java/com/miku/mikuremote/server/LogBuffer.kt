package com.miku.mikuremote.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class LogEntry(val ts: Long, val level: String, val message: String)

/** Ring buffer log lokal (maks 200 baris) agar RAM tidak membengkak. */
object LogBuffer {
    private const val MAX = 200
    private val deque = ArrayDeque<LogEntry>(MAX)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> get() = _entries

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(level: String, message: String) {
        val e = LogEntry(System.currentTimeMillis(), level, message.take(500))
        synchronized(deque) {
            if (deque.size >= MAX) deque.removeFirst()
            deque.addLast(e)
        }
        _entries.value = synchronized(deque) { deque.toList() }
        android.util.Log.d("MikuRemote", "[$level] $message")
    }

    fun snapshot(): List<LogEntry> = synchronized(deque) { deque.toList() }

    fun format(e: LogEntry): String = "[${fmt.format(Date(e.ts))}] ${e.level} ${e.message}"
}
