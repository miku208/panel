package com.miku.mikuremote.controller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

private val GsBg = Color(0xFF0E1116)
private val GsCard = Color(0xFF161B22)
private val GsAccent = Color(0xFF39D98A)
private val GsWarn = Color(0xFFE3B341)
private val GsText = Color(0xFFE6EDF3)
private val GsDim = Color(0xFF8B949E)
private val GsLine = Color(0xFF21262D)

/**
 * SERVER GUARD settings (Phase 3B): semua teks/kontak di-edit dari sini dan
 * dikirim ke VPS (guard_config_update) -> tersimpan di VPS -> didorong ke HP
 * server. Nomor admin & warning TIDAK hardcoded di APK. Server offline tetap
 * dapat config saat reconnect (VPS menyimpan).
 */
@Composable
fun GuardScreen(
    prefs: Prefs,
    deviceId: String,
    online: Boolean,
    onBack: () -> Unit,
    showSnack: suspend (String) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // ---- State form (campuran Boolean & String) ----
    val form = remember { mutableStateMapOf<String, Any>(
        "enabled" to false,
        "warningEnabled" to true,
        "warningTitle" to "",
        "warningMessage" to "",
        "warningContactName" to "",
        "warningContactNumber" to "",
        "warningButtonText" to "",
    ) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // ---- Muat config saat layar dibuka / balikan dari VPS ----
    LaunchedEffect(Unit) {
        ControllerSocket.send(
            JSONObject().put("type", "guard_config_get").put("deviceId", deviceId)
        )
    }
    LaunchedEffect(Unit) {
        ControllerSocket.events.collect { msg ->
            when (msg.optString("type")) {
                "guard_config" -> {
                    val c = msg.optJSONObject("config") ?: return@collect
                    form["enabled"] = c.optBoolean("enabled", false)
                    form["warningEnabled"] = c.optBoolean("warningEnabled", true)
                    form["warningTitle"] = c.optString("warningTitle", "")
                    form["warningMessage"] = c.optString("warningMessage", "")
                    form["warningContactName"] = c.optString("warningContactName", "")
                    form["warningContactNumber"] = c.optString("warningContactNumber", "")
                    form["warningButtonText"] = c.optString("warningButtonText", "")
                    loaded = true
                }
                "guard_config_updated" -> {
                    if (msg.optString("deviceId") == deviceId) showSnack("Config tersimpan di VPS ✓")
                }
                "guard_config_synced" -> showSnack("Config diterima HP server ✓")
                "error" -> showSnack(msg.optString("error", "Gagal"))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(GsBg).verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("← ", color = GsDim, fontSize = 18.sp)
            }
            Text("SERVER GUARD", color = GsText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        // ---- Status Guard ----
        Card(colors = CardDefaults.cardColors(containerColor = GsCard), shape = RoundedCornerShape(12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Guard aktif", color = GsText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (form["enabled"] == true) "Warning screen tampil di HP server"
                        else "Warning screen nonaktif",
                        color = GsDim, fontSize = 12.sp
                    )
                }
                Switch(
                    checked = form["enabled"] == true,
                    onCheckedChange = { form["enabled"] = it },
                    enabled = true,
                    colors = SwitchDefaults.colors(checkedTrackColor = GsAccent)
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // ---- Warning ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Warning", color = GsText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            FilterChip(
                selected = form["warningEnabled"] == true,
                onClick = { form["warningEnabled"] = !(form["warningEnabled"] == true) },
                label = { Text(if (form["warningEnabled"] == true) "ON" else "OFF", fontSize = 12.sp) }
            )
        }
        Spacer(Modifier.height(10.dp))

        GuardField("Warning Title", form, "warningTitle", "SERVER SEDANG DIGUNAKAN")
        GuardField("Warning Message", form, "warningMessage", "Jangan gunakan HP ini untuk bermain game…", minLines = 3)
        GuardField("Contact Name", form, "warningContactName", "Miku Admin")
        GuardField("Contact Number", form, "warningContactNumber", "08xxxxxxxxxx")
        GuardField("Button Text", form, "warningButtonText", "HUBUNGI ADMIN")

        Spacer(Modifier.height(16.dp))

        // ---- Save ----
        Button(
            onClick = {
                saving = true
                val ok = ControllerSocket.send(
                    JSONObject().put("type", "guard_config_update")
                        .put("deviceId", deviceId)
                        .put("config", JSONObject().apply {
                            put("enabled", form["enabled"] == true)
                            put("warningEnabled", form["warningEnabled"] == true)
                            put("warningTitle", form["warningTitle"] ?: "")
                            put("warningMessage", form["warningMessage"] ?: "")
                            put("warningContactName", form["warningContactName"] ?: "")
                            put("warningContactNumber", form["warningContactNumber"] ?: "")
                            put("warningButtonText", form["warningButtonText"] ?: "")
                        })
                )
                scope.launch {
                    if (!ok) showSnack("Koneksi ke VPS terputus")
                    kotlinx.coroutines.delay(1500)
                    saving = false
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GsAccent, contentColor = Color.Black)
        ) { Text(if (saving) "MENYIMPAN…" else "SAVE CONFIG", fontWeight = FontWeight.SemiBold) }

        Spacer(Modifier.height(8.dp))
        Text(
            if (!online) "HP server sedang OFFLINE — config tetap tersimpan di VPS dan diterima saat server reconnect."
            else "Config dikirim langsung ke HP server.",
            color = GsDim, fontSize = 12.sp, lineHeight = 16.sp
        )

        Spacer(Modifier.height(20.dp))

        // ---- Preview ----
        Text("PREVIEW", color = GsDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().background(GsCard, RoundedCornerShape(12.dp)).padding(16.dp)
        ) {
            Text(
                (form["warningTitle"] as? String ?: "").ifBlank { "HP INI SEDANG DIGUNAKAN SEBAGAI SERVER" },
                color = GsWarn, fontSize = 16.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                (form["warningMessage"] as? String ?: "").ifBlank {
                    "Jangan membuka game atau aplikasi berat.\nPenggunaan HP ini dapat mengganggu layanan server."
                },
                color = GsText, fontSize = 13.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))
            val num = (form["warningContactNumber"] as? String ?: "").trim()
            if (num.isNotEmpty()) {
                androidx.compose.material3.Surface(
                    color = GsWarn, shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        (form["warningButtonText"] as? String ?: "").ifBlank { "HUBUNGI ADMIN" },
                        color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                val nm = (form["warningContactName"] as? String ?: "").trim()
                if (nm.isNotEmpty()) {
                    Text("Kontak: $nm", color = GsDim, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Server Guard aktif • tap 2x untuk keluar", color = GsDim.copy(alpha = 0.6f), fontSize = 10.sp)
        }

        if (!loaded) {
            Spacer(Modifier.height(10.dp))
            Text("Memuat config dari VPS…", color = GsDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GuardField(
    label: String,
    form: MutableMap<String, Any>,
    key: String,
    placeholder: String,
    minLines: Int = 1,
) {
    val value = form[key] as? String ?: ""
    OutlinedTextField(
        value = value,
        onValueChange = { form[key] = it },
        label = { Text(label, color = GsDim) },
        placeholder = { Text(placeholder, color = GsDim.copy(alpha = 0.5f), fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        textStyle = LocalTextStyle.current.copy(color = GsText, fontSize = 14.sp),
    )
    Spacer(Modifier.height(10.dp))
}
