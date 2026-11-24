package com.pydm.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun YoutubeScreen(modifier: Modifier, goDownloads: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf<VideoMeta?>(null) }
    var plTitle by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var isPlaylist by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var quality by remember { mutableStateOf("best") }
    var container by remember { mutableStateOf("mp4") }
    var codec by remember { mutableStateOf(Manager.settings.codec) }

    fun doFetch() {
        val u = url.trim()
        if (u.isEmpty()) return
        loading = true; error = ""; meta = null; entries = emptyList(); selected.clear()
        scope.launch {
            try {
                val pl = Yt.isPlaylistUrl(u)
                if (pl) {
                    val r = withContext(Dispatchers.IO) { Yt.fetchPlaylist(u) }
                    plTitle = r.first; entries = r.second
                    entries.forEach { selected[it.id] = true }
                    if (entries.isEmpty()) error = "No videos found in this playlist"
                } else {
                    meta = withContext(Dispatchers.IO) { Yt.fetchInfo(u) }
                }
                isPlaylist = pl
                quality = "best"; container = "mp4"
            } catch (e: Throwable) {
                error = Yt.friendlyError(e)
            } finally { loading = false }
        }
    }

    val prefill = Shared.ytUrl.value
    LaunchedEffect(prefill) {
        if (prefill.isNotBlank()) { url = prefill; Shared.ytUrl.value = ""; doFetch() }
    }

    fun startDownload() {
        if (isPlaylist) {
            for (e in entries.filter { selected[it.id] == true }) {
                Manager.add(YtTask("https://www.youtube.com/watch?v=" + e.id, e.title, quality, container, codec,
                    Fmt.safeName(plTitle)))
            }
        } else {
            meta?.let { Manager.add(YtTask(url.trim(), it.title, quality, container, codec)) }
        }
        goDownloads()
    }

    val qualityOptions: List<Pair<String, String>> = run {
        val hs = if (isPlaylist || meta == null) listOf(1080, 720, 480, 360) else meta!!.heights
        listOf("best" to "Best") + hs.map { it.toString() to Yt.qualityLabel(it) } + listOf("audio" to "Audio only")
    }
    val containerOptions: List<Pair<String, String>> =
        if (quality == "audio") listOf("mp3" to "MP3", "m4a" to "M4A")
        else listOf("mp4" to "MP4", "mkv" to "MKV", "webm" to "WEBM")
    val hasResult = meta != null || entries.isNotEmpty()

    LazyColumn(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("YouTube downloader", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("YouTube video or playlist link") })
                OutlinedButton(onClick = { clip.getText()?.text?.let { url = it.trim() } }) { Text("Paste") }
            }
        }
        item {
            Button(onClick = { doFetch() }, enabled = !loading) { Text("Fetch info") }
        }
        if (loading) item { Loading("Fetching... (first time yt-dlp may take a few seconds to start)") }
        if (error.isNotBlank()) item { ErrorText(error) }

        if (hasResult) {
            item {
                Section(if (isPlaylist) "Playlist: $plTitle  (${entries.size} videos)"
                        else (meta?.title ?: "") + (meta?.let { "  ·  " + Fmt.duration(it.duration) } ?: "")) {
                    Text("Quality")
                    Chips(qualityOptions, quality) {
                        quality = it
                        container = if (it == "audio") "mp3" else if (container in listOf("mp3", "m4a")) "mp4" else container
                    }
                    Text("Format")
                    Chips(containerOptions, container) { container = it }
                    if (quality != "audio") {
                        Text("Video codec")
                        Chips(listOf("avc" to "H.264 + AAC (plays everywhere)", "best" to "Best (AV1/VP9, smaller)"),
                            codec) { codec = it }
                    }
                    val n = if (isPlaylist) selected.count { it.value } else 1
                    Button(onClick = { startDownload() }, enabled = n > 0) {
                        Text(if (isPlaylist) "Download $n selected" else "Download")
                    }
                }
            }
        }
        if (isPlaylist && entries.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { entries.forEach { selected[it.id] = true } }) { Text("Select all") }
                    OutlinedButton(onClick = { entries.forEach { selected[it.id] = false } }) { Text("None") }
                }
            }
            items(entries, key = { it.id }) { e ->
                CheckRow("${e.title}", selected[e.id] == true) { selected[e.id] = it }
            }
        }
    }
}
