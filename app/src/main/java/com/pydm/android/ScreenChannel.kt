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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChannelScreen(modifier: Modifier, goDownloads: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<Entry>>(emptyList()) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var doThumbs by remember { mutableStateOf(true) }
    var doTitles by remember { mutableStateOf(true) }
    var png by remember { mutableStateOf(false) }
    var fmt by remember { mutableStateOf("docx") }
    var oneFile by remember { mutableStateOf(true) }

    fun fetch() {
        val u = url.trim()
        if (u.isEmpty()) return
        loading = true; error = ""; videos = emptyList(); selected.clear()
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    if (Yt.isPlaylistUrl(u) && !u.contains("/@") && !u.contains("/channel/"))
                        Yt.fetchPlaylist(u) else Yt.fetchChannelVideos(u)
                }
                channel = r.first; videos = r.second
                videos.forEach { selected[it.id] = true }
                if (videos.isEmpty()) error = "No videos found"
            } catch (e: Throwable) {
                error = Yt.friendlyError(e)
            } finally { loading = false }
        }
    }

    fun chosen() = videos.filter { selected[it.id] == true }

    LazyColumn(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Channel tools", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text("Thumbnails · Titles · Transcripts", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("@channel, channel link or playlist link") })
                OutlinedButton(onClick = { clip.getText()?.text?.let { url = it.trim() } }) { Text("Paste") }
            }
        }
        item { Button(onClick = { fetch() }, enabled = !loading) { Text("Fetch videos") } }
        if (loading) item { Loading("Fetching video list (large channels can take a minute)...") }
        if (error.isNotBlank()) item { ErrorText(error) }

        if (videos.isNotEmpty()) {
            val n = videos.count { selected[it.id] == true }
            item {
                Section("$channel  ·  ${videos.size} videos  ·  $n selected") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { videos.forEach { selected[it.id] = true } }) { Text("All") }
                        OutlinedButton(onClick = { videos.forEach { selected[it.id] = false } }) { Text("None") }
                        OutlinedButton(onClick = {
                            videos.forEachIndexed { i, v -> selected[v.id] = i < 50 }
                        }) { Text("Top 50") }
                    }
                }
            }
            item {
                Section("Thumbnails + Titles") {
                    CheckRow("Download thumbnails (best quality)", doThumbs) { doThumbs = it }
                    if (doThumbs) CheckRow("Save as PNG (default JPG)", png) { png = it }
                    CheckRow("Titles document (.docx)", doTitles) { doTitles = it }
                    Button(onClick = {
                        Manager.add(ChannelTask(channel, chosen(), doThumbs, doTitles, png)); goDownloads()
                    }, enabled = n > 0 && (doThumbs || doTitles)) { Text("Start") }
                }
            }
            item {
                Section("Transcripts") {
                    Chips(listOf("docx" to "Word (.docx)", "txt" to "Text (.txt)"), fmt) { fmt = it }
                    Chips(listOf("one" to "One file for all", "many" to "Separate file per video"),
                        if (oneFile) "one" else "many") { oneFile = it == "one" }
                    Text("Tip: for big channels select fewer videos - YouTube may rate-limit.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Button(onClick = {
                        Manager.add(TranscriptTask(channel, chosen(), fmt, oneFile)); goDownloads()
                    }, enabled = n > 0) { Text("Get transcripts") }
                }
            }
            items(videos, key = { it.id }) { v ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected[v.id] == true, { selected[v.id] = it })
                    Column(Modifier.weight(1f)) {
                        Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${Fmt.views(v.views)} views" + (v.date?.let { "  ·  $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        }
    }
}
