package com.miku.mikuremote.server

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** HTTP client kecil untuk operasi REST. Semua request ada timeout. */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun post(url: String, body: JSONObject): Pair<Int, JSONObject> {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            val obj = JSONObject(resp.body?.string() ?: "{}")
            return resp.code to obj
        }
    }

    /**
     * Mode pribadi: daftarkan device ini ke VPS hanya dengan kunci rahasia.
     * Return Pair(deviceId, deviceToken) atau error.
     */
    fun privateActivate(baseUrl: String, key: String, name: String): Result<Pair<String, String>> {
        val url = baseUrl.trimEnd('/') + "/api/private/device"
        return try {
            val (code, obj) = post(url, JSONObject()
                .put("key", key).put("name", name))
            if (code == 201 && obj.has("deviceId")) {
                Result.success(obj.getString("deviceId") to obj.getString("deviceToken"))
            } else {
                Result.failure(Exception(obj.optString("error", "HTTP $code")))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Tidak dapat terhubung ke VPS: ${e.message}"))
        }
    }

    /** Jalur lama (opsional): tukar pairing code dengan device token. */
    fun pairingExchange(baseUrl: String, code: String): Result<Pair<String, String>> {
        val url = baseUrl.trimEnd('/') + "/api/pairing-exchange"
        return try {
            val (code, obj) = post(url, JSONObject().put("code", code))
            if (code == 200 && obj.has("deviceId")) {
                Result.success(obj.getString("deviceId") to obj.getString("deviceToken"))
            } else {
                Result.failure(Exception(obj.optString("error", "HTTP $code")))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Tidak dapat terhubung ke VPS: ${e.message}"))
        }
    }
}
