package com.miku.mikuremote.server

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 3B: remote front camera.
 *
 * - Camera2 → Surface encoder MediaCodec H.264 → frame binary (Annex-B) via WS.
 * - Hanya membuka kamera setelah CAMERA permission di-grant (dicek service);
 *   privacy indicator Android tetap tampil (tidak ada bypass).
 * - Bounded: satu session, satu encoder; cleanup penuh saat stop/error.
 * - Torch (kamera belakang via setTorchMode) tidak konflik dengan session ini.
 */
class CameraCaptureEngine(private val context: Context) {

    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderThread: Thread? = null

    private val streaming = AtomicBoolean(false)

    val isActive: Boolean get() = streaming.get()

    private fun ensureWorker() {
        if (worker == null) {
            worker = HandlerThread("MikuCamera").apply { start() }
            handler = Handler(worker!!.looper)
        }
    }

    private fun frontCameraId(): String? = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) return@runCatching id
        }
        null
    }.getOrNull()

    /**
     * Mulai stream kamera. Return null jika sukses, atau pesan error.
     * Pemanggil wajib memastikan permission CAMERA sudah di-grant.
     */
    @SuppressLint("MissingPermission")
    fun startStream(
        facingFront: Boolean,
        reqW: Int, reqH: Int, fps: Int, bitrateKbps: Int,
        sendFrame: (ByteArray) -> Unit,
        onMeta: (Int, Int, Int) -> Unit,
        onEnded: () -> Unit,
    ): String? {
        if (!streaming.compareAndSet(false, true)) return "Kamera sedang aktif"
        onEndedCallback = onEnded
        try {
            ensureWorker()
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val wanted = if (facingFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            var cameraId: String? = null
            var targetW = reqW; var targetH = reqH
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == wanted) {
                    cameraId = id
                    // Pilih ukuran preview paling dekat request (SimpleThreshold, hemat CPU).
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                    val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: continue
                    var best = sizes[0]
                    var bestScore = Int.MAX_VALUE
                    for (s in sizes) {
                        val score = Math.abs(s.width - reqW) + Math.abs(s.height - reqH)
                        if (score < bestScore && s.width <= 1280 && s.height <= 1280) { best = s; bestScore = score }
                    }
                    targetW = best.width; targetH = best.height
                    break
                }
            }
            val id = cameraId ?: return "Kamera ${if (facingFront) "depan" else "belakang"} tidak ditemukan".also { streaming.set(false) }

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH).apply {
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

            // Metadata dimain setelah frame pertama terkirim (paling akurat).
            var metaSent = false

            encoderThread = Thread {
                val info = MediaCodec.BufferInfo()
                while (streaming.get()) {
                    val idx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
                    if (idx < 0) continue
                    val buf = try { codec.getOutputBuffer(idx) } catch (_: Exception) { break } ?: continue
                    if (info.size > 0) {
                        val out = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(out)
                        sendFrame(out)
                        if (!metaSent) { metaSent = true; handler?.post { onMeta(targetW, targetH, fps) } }
                    }
                    try { codec.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                }
            }.apply { start() }

            val camCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        val captureReq = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(inputSurface)
                            set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }.build()
                        // H.264 butuh ukuran min 2 (genap) — pilih ukuran asli kamera.
                        device.createCaptureSession(listOf(inputSurface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try { s.setRepeatingRequest(captureReq, null, handler) } catch (e: Exception) {
                                    LogBuffer.log("ERROR", "[CAMERA] Gagal setRepeatingRequest: ${e.message}")
                                    stopStream()
                                }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                LogBuffer.log("ERROR", "[CAMERA] Session config gagal")
                                stopStream()
                            }
                        }, handler)
                    } catch (e: Exception) {
                        LogBuffer.log("ERROR", "[CAMERA] Capture gagal: ${e.message}")
                        stopStream()
                    }
                }
                override fun onDisconnected(device: CameraDevice) { stopStream() }
                override fun onError(device: CameraDevice, error: Int) {
                    LogBuffer.log("ERROR", "[CAMERA] CameraDevice error code=$error")
                    stopStream()
                }
            }

            handler?.post {
                try {
                    cm.openCamera(id, camCallback, handler)
                    LogBuffer.log("INFO", "[CAMERA] Kamera ${if (facingFront) "depan" else "belakang"} dibuka ${targetW}x${targetH}@${fps}")
                } catch (e: SecurityException) {
                    LogBuffer.log("ERROR", "[CAMERA] Permission kamera belum diberikan")
                    stopStream()
                } catch (e: Exception) {
                    LogBuffer.log("ERROR", "[CAMERA] Gagal buka kamera: ${e.message}")
                    stopStream()
                }
            }
            return null
        } catch (e: Exception) {
            val msg = "Gagal memulai kamera: ${e.message ?: e.javaClass.simpleName}"
            LogBuffer.log("ERROR", msg)
            cleanup()
            streaming.set(false)
            return msg
        }
    }

    private var onEndedCallback: (() -> Unit)? = null

    /** Hentikan stream & tutup semua resource kamera/encoder. */
    fun stopStream() {
        val wasActive = streaming.getAndSet(false)
        cleanup()
        if (wasActive) {
            LogBuffer.log("INFO", "[CAMERA] Stream kamera dihentikan")
            val cb = onEndedCallback
            onEndedCallback = null
            cb?.invoke()
        }
    }

    private fun cleanup() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        encoderThread?.interrupt()
        encoderThread = null
        try { encoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
    }

    fun release() {
        streaming.set(false)
        onEndedCallback = null
        cleanup()
    }
}
