package com.miku.mikuremote.server

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bus penerima hasil consent MediaProjection dari ScreenPermissionActivity.
 * Service memasang listener sebelum meluncurkan activity, activity mengirim
 * (resultCode, data) saat user menjawab.
 */
object ScreenConsentBus {
    @Volatile var listener: ((Int, Intent?) -> Unit)? = null

    fun deliver(resultCode: Int, data: Intent?) {
        val l = listener
        listener = null
        try { l?.invoke(resultCode, data) } catch (_: Exception) {}
    }
}

/**
 * Phase 3: capture layar HP server via MediaProjection.
 *
 * - Live stream: VirtualDisplay -> Surface MediaCodec H.264 encoder ->
 *   tiap output buffer dikirim via WS binary (Annex-B).
 * - Screenshot: VirtualDisplay ImageReader RGBA -> Bitmap -> JPEG bytes.
 *
 * Sesi MediaProjection TETAP HIDUP setelah stream berhenti (selama user tidak
 * mencabut izin dan service hidup), sehingga ganti kualitas / screenshot
 * / mulai stream ulang TIDAK perlu dialog consent baru. Consent baru hanya
 * diminta saat projection belum ada.
 *
 * Persyaratan Android 14+ yang dipatuhi:
 * - FGS dengan tipe MEDIA_PROJECTION aktif SEBELUM getMediaProjection()
 *   (di-upgrade oleh ServerService).
 * - resultData consent hanya dipakai SEKALI (satu getMediaProjection).
 */
class ScreenCaptureEngine(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderThread: Thread? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private val streaming = AtomicBoolean(false)
    private val runningOneShot = AtomicBoolean(false)

    /** Stream live sedang berjalan. */
    val isActive: Boolean get() = streaming.get()

    /** Sesi capture tersedia (boleh mulai stream/screenshot tanpa consent baru). */
    val hasProjection: Boolean get() = projection != null

    private fun ensureWorker() {
        if (worker == null) {
            worker = HandlerThread("MikuScreen").apply { start() }
            handler = Handler(worker!!.looper)
        }
    }

    private class DisplayInfo(val width: Int, val height: Int, val dpi: Int)

    private fun realDisplay(): DisplayInfo {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return DisplayInfo(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    /** Skala ukuran request agar aspek rasio layar asli terjaga, genap (syarat H.264). */
    private fun scaledSize(reqW: Int, reqH: Int): Pair<Int, Int> {
        val d = realDisplay()
        if (d.width <= 0 || d.height <= 0) return reqW.coerceAtLeast(2) to reqH.coerceAtLeast(2)
        val scale = minOf(reqW.toFloat() / d.width, reqH.toFloat() / d.height)
        var w = (d.width * scale).toInt() / 2 * 2
        var h = (d.height * scale).toInt() / 2 * 2
        if (w < 2) w = 2
        if (h < 2) h = 2
        return w to h
    }

    private fun getProjection(resultCode: Int, data: Intent?): MediaProjection? {
        if (projection != null) return projection
        if (data == null) return null
        val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = pm.getMediaProjection(resultCode, data) ?: return null
        projection = p
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    // User mencabut consent dari tile/dialog sistem.
                    main.post {
                        val wasStreaming = streaming.getAndSet(false)
                        fullCleanup()
                        if (wasStreaming) onStreamEndedCallback?.invoke()
                    }
                }
            }
            projectionCallback = cb
            p.registerCallback(cb, handler)
        }
        return p
    }

    private var onStreamEndedCallback: (() -> Unit)? = null

    // ------------------------------------------------------------------
    // LIVE STREAM
    // ------------------------------------------------------------------

    /**
     * Mulai live stream. Return null jika sukses, atau pesan error.
     * Jika projection belum ada, [data] consent wajib; jika sudah ada, data
     * boleh null (sesi lama dipakai ulang).
     */
    fun startStream(
        resultCode: Int,
        data: Intent?,
        reqW: Int, reqH: Int, fps: Int, bitrateKbps: Int,
        sendFrame: (ByteArray) -> Unit,
        onMeta: (Int, Int, Int) -> Unit,
        onEnded: () -> Unit,
    ): String? {
        if (!streaming.compareAndSet(false, true)) return "Stream sudah berjalan"
        try {
            ensureWorker()
            val p = getProjection(resultCode, data)
                ?: return "Izin capture layar tidak tersedia".also { streaming.set(false) }
            onStreamEndedCallback = onEnded

            val (w, h) = scaledSize(reqW, reqH)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()
            encoder = codec
            encoderSurface = inputSurface

            val d = realDisplay()
            display = p.createVirtualDisplay(
                "MikuRemoteLive", w, h, d.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, handler
            )

            encoderThread = Thread {
                val info = MediaCodec.BufferInfo()
                var metaSent = false
                while (streaming.get()) {
                    val idx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
                    if (idx < 0) continue
                    val buf = try { codec.getOutputBuffer(idx) } catch (_: Exception) { break } ?: continue
                    if (info.size > 0) {
                        val out = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(out)
                        // Kirim semua output (termasuk SPS/PPS codec-config) —
                        // decoder controller langsung sinkron dari IDR pertama.
                        sendFrame(out)
                        if (!metaSent) {
                            metaSent = true
                            main.post { onMeta(w, h, fps) }
                        }
                    }
                    try { codec.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                }
            }.apply { start() }

            LogBuffer.log("INFO", "Live screen ON: ${w}x${h}@${fps}fps ${bitrateKbps}kbps")
            return null
        } catch (e: Exception) {
            val msg = "Gagal memulai capture: ${e.message ?: e.javaClass.simpleName}"
            LogBuffer.log("ERROR", msg)
            cleanupStream()
            streaming.set(false)
            return msg
        }
    }

    /** Hentikan live stream. Sesi projection DIPERTAHANKAN untuk pemakaian ulang. */
    fun stopStream() {
        if (streaming.getAndSet(false)) {
            cleanupStream()
            LogBuffer.log("INFO", "Live screen OFF")
            onStreamEndedCallback = null
        }
    }

    // ------------------------------------------------------------------
    // SCREENSHOT (one-shot) — butuh projection aktif
    // ------------------------------------------------------------------

    /**
     * Capture satu frame JPEG dari projection aktif (stream boleh sedang jalan).
     * onJpeg dipanggil dengan bytes JPEG atau null jika gagal/timeout.
     * Return pesan error jika capture tidak bisa dimulai.
     */
    fun captureScreenshot(onJpeg: (ByteArray?) -> Unit): String? {
        val p = projection ?: return "Belum ada izin capture aktif"
        if (!runningOneShot.compareAndSet(false, true)) return "Screenshot lain sedang berjalan"
        ensureWorker()

        val d = realDisplay()
        val scale = minOf(1080f / maxOf(d.width, d.height), 1f)
        val w = (d.width * scale).toInt() / 2 * 2
        val h = (d.height * scale).toInt() / 2 * 2

        var reader: ImageReader? = null
        var shotDisplay: VirtualDisplay? = null
        var done = false

        fun finishShot(bytes: ByteArray?) {
            if (done) return
            done = true
            try { shotDisplay?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            runningOneShot.set(false)
            main.post { onJpeg(bytes) }
        }

        return try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            shotDisplay = p.createVirtualDisplay(
                "MikuShot", w, h, d.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            reader.setOnImageAvailableListener({
                val img = try { it.acquireLatestImage() } catch (_: Exception) { null }
                if (img != null) {
                    val jpeg = try {
                        val plane = img.planes[0]
                        val buf = plane.buffer
                        val rowStride = plane.rowStride
                        val bitmap: Bitmap = if (rowStride == img.width * 4) {
                            Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888).apply {
                                copyPixelsFromBuffer(buf)
                            }
                        } else {
                            // RowStride padded: salin baris per baris lalu potong.
                            val bmp = Bitmap.createBitmap(rowStride / 4, img.height, Bitmap.Config.ARGB_8888)
                            bmp.copyPixelsFromBuffer(buf)
                            Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
                        }
                        val bos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, bos)
                        bitmap.recycle()
                        bos.toByteArray()
                    } catch (_: Exception) { null } finally {
                        try { img.close() } catch (_: Exception) {}
                    }
                    finishShot(jpeg)
                }
            }, handler)
            // Timeout 4 detik: layar tidak menghasilkan frame (mis. off).
            handler?.postDelayed({ finishShot(null) }, 4_000)
            null
        } catch (e: Exception) {
            done = true
            try { shotDisplay?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            runningOneShot.set(false)
            "Gagal capture: ${e.message}"
        }
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    /** Lepas encoder + virtual display stream (projection tetap hidup). */
    private fun cleanupStream() {
        encoderThread?.interrupt()
        encoderThread = null
        try { encoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
        try { display?.release() } catch (_: Exception) {}
        display = null
    }

    /** Lepas semuanya termasuk sesi projection (dipakai saat revoke/service mati). */
    private fun fullCleanup() {
        cleanupStream()
        onStreamEndedCallback = null
        val cb = projectionCallback
        projectionCallback = null
        if (cb != null) {
            try { projection?.unregisterCallback(cb) } catch (_: Exception) {}
        }
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    /** Lepas semua resource (service onDestroy). */
    fun release() {
        streaming.set(false)
        runningOneShot.set(false)
        fullCleanup()
    }
}
