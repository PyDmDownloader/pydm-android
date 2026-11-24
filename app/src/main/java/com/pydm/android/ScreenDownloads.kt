package com.pydm.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun DownloadsScreen(modifier: Modifier, goYoutube: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var views by remember { mutableStateOf(Manager.views()) }
    val clip = LocalClipboardManager.current
    LaunchedEffect(Unit) { while (true) { views = Manager.views(); delay(500) } }

    Column(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PyDM", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Paste download link (http/https)") })
            OutlinedButton(onClick = { clip.getText()?.text?.let { url = it.trim() } }) { Text("Paste") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val u = url.trim()
                if (u.isEmpty()) return@Button
                if (Yt.isYoutubeUrl(u)) { goYoutube(u); url = "" }
                else try { Manager.addDirect(u); url = ""; msg = "" } catch (e: Exception) { msg = e.message ?: "Invalid link" }
            }) { Text("Add download") }
            OutlinedButton(onClick = { Manager.pauseAll() }) { Text("Pause all") }
            OutlinedButton(onClick = { Manager.resumeAll() }) { Text("Resume all") }
        }
        Row {
            TextButton(onClick = { Manager.clearDone() }) { Text("Clear completed") }
        }
        ErrorText(msg)
        if (views.isEmpty()) {
            Text("No downloads yet.\nPaste a link above, or use the YouTube / Channel tabs.",
                color = Color.Gray, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(views, key = { it.id }) { TaskCard(it) }
        }
    }
}

@Composable
fun TaskCard(v: TaskView) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(kindIcon(v.kind) + " " + v.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            if (v.status != Status.COMPLETED || v.kind == "direct" || v.kind == "yt")
                LinearProgressIndicator(progress = { v.progress }, Modifier.fillMaxWidth())
            Text(statusLine(v), style = MaterialTheme.typography.bodySmall,
                color = if (v.status == Status.ERROR) MaterialTheme.colorScheme.error else Color.DarkGray,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (v.status) {
                    Status.PAUSED, Status.ERROR ->
                        OutlinedButton(onClick = { Manager.get(v.id)?.resume() }) { Text("Resume") }
                    Status.COMPLETED ->
                        if (v.outUri != null) OutlinedButton(onClick = { Storage.open(ctx, v.outUri) }) { Text("Open") }
                    else -> OutlinedButton(onClick = { Manager.get(v.id)?.pause() }) { Text("Pause") }
                }
                TextButton(onClick = { Manager.get(v.id)?.let { Manager.remove(it) } }) { Text("Remove") }
            }
        }
    }
}

private fun kindIcon(k: String) = when (k) {
    "yt" -> "▶"; "channel" -> "🖼"; "transcript" -> "📝"; else -> "⬇"
}

private fun statusLine(v: TaskView): String {
    val bytesKind = v.kind == "direct" || v.kind == "yt"
    return when (v.status) {
        Status.QUEUED -> "Queued"
        Status.PROBING -> if (v.detail.isNotBlank()) v.detail else "Connecting..."
        Status.DOWNLOADING -> if (bytesKind) {
            val parts = ArrayList<String>()
            parts.add("${(v.progress * 100).toInt()}%")
            if (v.total > 0) parts.add("${Fmt.bytes(v.downloaded)} / ${Fmt.bytes(v.total)}")
            if (v.speed > 0) parts.add(Fmt.speed(v.speed))
            if (v.etaSec >= 0) parts.add("ETA " + Fmt.eta(v.etaSec))
            parts.joinToString(" · ")
        } else v.detail.ifBlank { "Working..." }
        Status.MERGING -> if (v.detail.isNotBlank()) v.detail else "Finishing (merging / saving)..."
        Status.PAUSED -> "Paused" + if (v.progress > 0) " at ${(v.progress * 100).toInt()}%" else ""
        Status.COMPLETED -> if (bytesKind) "Done · " + Fmt.bytes(v.total) + "  (Downloads/PyDM)"
                            else v.detail.ifBlank { "Done" }
        Status.ERROR -> "Error: " + v.error
    }
}
