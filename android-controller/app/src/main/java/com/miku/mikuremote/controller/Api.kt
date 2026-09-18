package com.miku.mikuremote.controller

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DeviceRow(
    val id: String,
    val name: String,
    val online: Boolean,
    val lastSeen: String?,
    val createdAt: String?,
    val state: String? = null, // starting | online | reconnecting (Phase 4)
)

/** REST client. Semua request ber-timeout, error selalu berpesan jelas. */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun post(url: String, body: JSONObject, token: String? = null): Pair<Int, JSONObject> {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(json))
            .apply { token?.let { header("authorization", "Bearer $it") } }
            .build()
        client.newCall(req).execute().use { resp ->
            val obj = JSONObject(resp.body?.string() ?: "{}")
            return resp.code to obj
        }
    }

    private fun call(url: String, method: String, token: String): Pair<Int, JSONObject> {
        val req = Request.Builder().url(url).method(method, null)
            .header("authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { resp ->
            val obj = if (method == "DELETE") JSONObject() else JSONObject(resp.body?.string() ?: "{}")
            return resp.code to obj
        }
    }

    /**
     * Mode pribadi: tukar kunci rahasia dengan JWT milik owner.
     * Token JWT ini yang dipakai untuk WS controller & REST device.
     */
    fun privateSession(baseUrl: String, key: String): Result<String> {
        val url = baseUrl.trimEnd('/') + "/api/private/session"
        return try {
            val (code, obj) = post(url, JSONObject().put("key", key))
            if (code == 200 && obj.has("token")) Result.success(obj.getString("token"))
            else Result.failure(Exception(obj.optString("error", "Kunci pribadi ditolak (HTTP $code)")))
        } catch (e: Exception) {
            Result.failure(Exception("Tidak dapat terhubung ke VPS: ${e.message}"))
        }
    }

    fun listDevices(baseUrl: String, token: String): Result<List<DeviceRow>> {
        val req = Request.Builder().url("$baseUrl/api/devices")
            .header("authorization", "Bearer $token").build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = JSONObject(resp.body?.string() ?: "{}")
                if (resp.code != 200) {
                    return Result.failure(Exception(body.optString("error", "HTTP ${resp.code}")))
                }
                val devices = body.optJSONArray("devices") ?: JSONArray()
                val list = mutableListOf<DeviceRow>()
                for (i in 0 until devices.length()) {
                    val d = devices.getJSONObject(i)
                    list.add(
                        DeviceRow(
                            id = d.getString("id"),
                            name = d.optString("device_name", "Device"),
                            online = d.optBoolean("online"),
                            lastSeen = d.optString("last_seen", null),
                            createdAt = d.optString("created_at", null),
                            state = if (d.has("state") && !d.isNull("state")) d.optString("state", null) else null,
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(Exception("Gagal mengambil daftar device: ${e.message}"))
        }
    }

    fun deleteDevice(baseUrl: String, token: String, deviceId: String): Result<Unit> {
        return try {
            val (code, obj) = call("$baseUrl/api/devices/$deviceId", "DELETE", token)
            if (code == 200) Result.success(Unit)
            else Result.failure(Exception(obj.optString("error", "Gagal menghapus device (HTTP $code)")))
        } catch (e: Exception) {
            Result.failure(Exception("Gagal menghubungi VPS: ${e.message}"))
        }
    }
}
