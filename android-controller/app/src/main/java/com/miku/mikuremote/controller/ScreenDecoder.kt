package com.miku.mikuremote.controller

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decoder H.264 ke Surface (Phase 3 controller).
 *
 * Feed stream Annex-B dari HP server (via VPS). SPS/PPS di-cache dari stream
 * untuk konfigurasi decoder; render dimulai setelah IDR pertama lewat.
 * Semua callback dipanggil dari thread decoder internal.
 */
class ScreenDecoder(
    private val surface: Surface,
    private val onSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
) {

    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    private val started = AtomicBoolean(false)

    @Volatile private var width = 0
    @Volatile private var height = 0
    private val wire = ScreenWire.State()
    private var waitingFirstIdr = true

    /** Mulai decoder (dipanggil setelah Surface siap). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        thread = Thread({
            try {
                loop()
            } catch (e: Exception) {
                if (started.get()) onError(e.message ?: "decoder error")
            }
        }, "MikuScreenDecoder").apply { start() }
    }

    private fun loop() {
        if (!ensureCodec()) {
            onError("Menunggu parameter stream (SPS/PPS belum ada)")
            return
        }
        val info = MediaCodec.BufferInfo()
        while (started.get()) {
            // ---- output render ----
            val codecRef = codec ?: break
            var outIdx = try { codecRef.dequeueOutputBuffer(info, 0) } catch (_: Exception) { break }
            while (outIdx >= 0) {
                try { codecRef.releaseOutputBuffer(outIdx, true) } catch (_: Exception) {}
                outIdx = try { codecRef.dequeueOutputBuffer(info, 0) } catch (_: Exception) { -1 }
            }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = codecRef.outputFormat
                width = fmt.getInteger(MediaFormat.KEY_WIDTH)
                height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                onSize(width, height)
            }

            // ---- input feed ----
            val data = queue.poll()
            if (data == null) {
                sleep(8)
                continue
            }
            var inIdx = -1
            try { inIdx = codecRef.dequeueInputBuffer(10_000) } catch (_: Exception) { break }
            if (inIdx >= 0) {
                val buf = codecRef.getInputBuffer(inIdx) ?: continue
                buf.clear()
                val len = minOf(data.size, buf.capacity())
                buf.put(data, 0, len)
                codecRef.queueInputBuffer(inIdx, 0, len, inputPtsUs, 0)
                inputPtsUs += 33_333
            }
        }
    }

    private val queue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private var inputPtsUs = 10_000L

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /** Sinkronkan csd: buat/reconfigure decoder saat SPS+PPS tersedia. */
    private fun ensureCodec(): Boolean {
        val c = codec
        if (c != null) return true
        val csd = wire.csd0() ?: return false
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
        }
        val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        newCodec.configure(fmt, surface, null, 0)
        newCodec.start()
        codec = newCodec
        return true
    }

    /**
     * Masukkan output encoder HP server (Annex-B). Dipanggil dari WS thread.
     * Buffer di-copy kecil-kecilan agar aman dari reuse buffer OkHttp.
     */
    fun feed(buf: ByteArray) {
        if (!started.get()) return
        val hasIdr = wire.inspect(buf)
        if (codec == null && !wire.hasParams) return
        if (codec == null) {
            // Decoder belum ada: buat sekarang (csd sudah tersedia).
            if (!ensureCodec()) return
            waitingFirstIdr = true
        }
        if (waitingFirstIdr && !hasIdr) return // buang sampai IDR pertama
        waitingFirstIdr = false
        queue.add(buf.copyOf())
        // Jaga antrean tetap kecil (jangan menumpuk saat lag).
        while (queue.size > 60) queue.poll()
    }

    fun stop() {
        started.set(false)
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        thread?.interrupt()
        thread = null
        queue.clear()
    }
}
