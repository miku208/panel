package com.miku.mikuremote.server

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Kontrol senter via CameraManager.setTorchMode().
 * Menangani: device tanpa flash, torch dipakai app lain, error kamera.
 */
class TorchController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var torchEnabled = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            torchEnabled = enabled
        }
    }

    init {
        runCatching { cameraManager.registerTorchCallback(torchCallback, null) }
    }

    fun hasFlash(): Boolean = flashCameraId() != null

    /** Return null jika sukses, atau pesan error. */
    fun setTorch(on: Boolean): String? {
        val cameraId = flashCameraId()
            ?: return "Device ini tidak memiliki flashlight"
        return try {
            cameraManager.setTorchMode(cameraId, on)
            torchEnabled = on
            null
        } catch (e: IllegalArgumentException) {
            "Flashlight tidak tersedia"
        } catch (e: Exception) {
            // CameraInUseException dsb: kamera sedang dipakai aplikasi lain.
            "Torch gagal: kamera sedang dipakai aplikasi lain"
        }
    }

    fun isEnabled(): Boolean = torchEnabled

    private fun flashCameraId(): String? {
        return runCatching {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                ) return id
            }
            null
        }.getOrNull()
    }
}
