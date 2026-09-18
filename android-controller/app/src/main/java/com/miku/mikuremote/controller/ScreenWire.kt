package com.miku.mikuremote.controller

/**
 * Util stream H.264 Annex-B dari HP server (di-relay VPS apa adanya).
 *
 * Decoder controller tidak perlu memecah akses unit secara presisi: setiap
 * output buffer encoder boleh langsung diberikan ke MediaCodec. Yang penting:
 * - SPS (NAL type 7) & PPS (type 8) di-cache untuk membuat decoder (csd-0).
 * - Tahu kapan IDR (type 5) lewat supaya tahu kapan decode mulai menghasilkan
 *   frame (output sebelum IDR pertama kosong).
 */
object ScreenWire {

    class State {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val hasParams: Boolean get() = sps != null && pps != null

        /** Periksa buffer, cache SPS/PPS bila ada. Return true jika ada IDR. */
        fun inspect(buf: ByteArray): Boolean {
            var hasIdr = false
            val starts = ArrayList<Int>(8)
            var i = 0
            val n = buf.size
            while (i < n - 2) {
                if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()) {
                    // 4-byte code (00 00 00 01) jika byte sebelum pola 3-byte adalah 0
                    // dan kita belum menghitungnya.
                    if (i > 0 && buf[i - 1] == 0.toByte() && (starts.isEmpty() || starts.last() != i - 1)) {
                        starts.add(i - 1)
                    } else {
                        starts.add(i)
                    }
                    i += 3
                } else i++
            }
            for (k in starts.indices) {
                val s = starts[k]
                val e = if (k + 1 < starts.size) starts[k + 1] else n
                val nalStart = s + if (buf[s + 2] == 1.toByte()) 3 else 4
                if (nalStart >= e) continue
                when (buf[nalStart].toInt() and 0x1F) {
                    7 -> sps = buf.copyOfRange(s, e)
                    8 -> pps = buf.copyOfRange(s, e)
                    5 -> hasIdr = true
                }
            }
            return hasIdr
        }

        /** SPS+PPS (dengan start code) sebagai csd-0, atau null jika belum lengkap. */
        fun csd0(): ByteArray? {
            val s = sps ?: return null
            val p = pps ?: return null
            return s + p
        }
    }
}
