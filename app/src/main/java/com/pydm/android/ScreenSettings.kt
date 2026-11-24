package com.pydm.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier) {
    val s = Manager.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var connections by remember { mutableIntStateOf(s.connections) }
    var maxConc by remember { mutableIntStateOf(s.maxConcurrent) }
    var limit by remember { mutableStateOf(s.speedLimitKbps.toString()) }
    var codec by remember { mutableStateOf(s.codec) }
    var sched by remember { mutableStateOf(s.schedEnabled) }
    var start by remember { mutableStateOf(s.schedStart) }
    var stop by remember { mutableStateOf(s.schedStop) }
    var updMsg by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)

        Section("Downloads") {
            Stepper("Connections per file", connections, 1, 16) { connections = it; s.connections = it }
            Stepper("Simultaneous downloads", maxConc, 1, 10) { maxConc = it; s.maxConcurrent = it; Manager.pump() }
            OutlinedTextField(limit, {
                limit = it.filter { c -> c.isDigit() }.take(7)
                s.speedLimitKbps = limit.toIntOrNull() ?: 0
                Manager.applySettings()
            }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Speed limit KB/s (0 = unlimited)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }

        Section("YouTube") {
            Text("Default video codec")
            Chips(listOf("avc" to "H.264 + AAC", "best" to "Best (AV1/VP9)"), codec) { codec = it; s.codec = it }
        }

        Section("Scheduler (auto start / stop)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(sched, { sched = it; s.schedEnabled = it })
                Text("  Enabled")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it; s.schedStart = it.trim() }, Modifier.weight(1f),
                    singleLine = true, label = { Text("Start (HH:MM)") })
                OutlinedTextField(stop, { stop = it; s.schedStop = it.trim() }, Modifier.weight(1f),
                    singleLine = true, label = { Text("Stop (HH:MM)") })
            }
            Text("24-hour format, e.g. 02:00. Outside this window active downloads pause automatically.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }

        Section("yt-dlp") {
            val ver = remember { try { YoutubeDL.getInstance().version(ctx) ?: "?" } catch (_: Throwable) { "not ready" } }
            Text("Version: $ver")
            Button(onClick = {
                updating = true; updMsg = ""
                scope.launch {
                    try {
                        val st = withContext(Dispatchers.IO) {
                            Yt.waitReady()
                            YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                        }
                        updMsg = if (st == YoutubeDL.UpdateStatus.DONE) "Updated. Restart the app." else "Already up to date."
                    } catch (e: Throwable) { updMsg = "Update failed: " + (e.message ?: e.toString()) }
                    finally { updating = false }
                }
            }, enabled = !updating) { Text("Update yt-dlp") }
            if (updMsg.isNotBlank()) Text(updMsg, style = MaterialTheme.typography.bodySmall)
            Text("If YouTube downloads suddenly stop working, update yt-dlp.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }

        Section("Where are my files?") {
            Text("Everything is saved in your phone's Downloads/PyDM folder (open it with the Files app).")
        }
    }
}
