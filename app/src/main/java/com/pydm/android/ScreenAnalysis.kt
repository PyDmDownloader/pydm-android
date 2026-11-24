package com.pydm.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun AnalysisScreen(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var links by remember { mutableStateOf("") }
    var minCount by remember { mutableStateOf("5") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var view by remember { mutableStateOf("insights") }
    var exportMsg by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun run() {
        val urls = links.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return
        running = true; error = ""; result = null; exportMsg = ""
        scope.launch {
            try {
                val all = ArrayList<AVideo>()
                val used = HashSet<String>()
                for ((i, u) in urls.withIndex()) {
                    progress = "Fetching channel ${i + 1} / ${urls.size}..."
                    val (title, vids) = withContext(Dispatchers.IO) { Yt.fetchChannelVideos(u) }
                    var name = title; var k = 2
                    while (!used.add(name)) { name = "$title ($k)"; k++ }
                    vids.forEach { all.add(AVideo(it.id, it.title, it.views, it.date, name)) }
                }
                progress = "Analysing ${all.size} videos..."
                val mc = minCount.toIntOrNull()?.coerceAtLeast(1) ?: 5
                result = withContext(Dispatchers.Default) { Analysis.analyze(all, mc) }
                view = "insights"
            } catch (e: Throwable) {
                error = Yt.friendlyError(e)
            } finally { running = false; progress = "" }
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Title analysis", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text("Which words / title patterns get more views", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(links, { links = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                label = { Text("Channel links - one per line") })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(minCount, { minCount = it.filter { c -> c.isDigit() }.take(3) },
                    Modifier.width(150.dp), singleLine = true, label = { Text("Min videos") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedButton(onClick = { clip.getText()?.text?.let { links = (links + "\n" + it).trim() } }) { Text("Paste") }
                Button(onClick = { run() }, enabled = !running) { Text("Analyse") }
            }
        }
        if (running) item { Loading(progress) }
        if (error.isNotBlank()) item { ErrorText(error) }

        val res = result
        if (res != null) {
            item {
                Text("${res.totalVideos} videos · ${res.scoredVideos} with views", color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall)
                Chips(listOf("insights" to "Insights", "channels" to "Channels", "videos" to "Top videos",
                    "words" to "Words", "patterns" to "Patterns"), view) { view = it }
            }
            item {
                Button(onClick = {
                    scope.launch {
                        try {
                            val bytes = withContext(Dispatchers.Default) { Analysis.toXlsx(res) }
                            val name = "Title Analysis - " + Fmt.safeName(res.summary.joinToString(", ") { it.channel }.take(60)) + ".xlsx"
                            withContext(Dispatchers.IO) { Storage.publishBytes(ctx, bytes, "Analysis", name) }
                            exportMsg = "Saved: Downloads/PyDM/Analysis/$name"
                        } catch (e: Throwable) { exportMsg = "Export failed: " + (e.message ?: e.toString()) }
                    }
                }) { Text("Export to Excel (.xlsx)") }
                if (exportMsg.isNotBlank()) Text(exportMsg, style = MaterialTheme.typography.bodySmall)
            }
            when (view) {
                "insights" -> items(res.insights) { Section("Insight") { Text(it) } }
                "channels" -> items(res.summary) { s ->
                    Section(s.channel) {
                        Text("${s.videos} videos · total ${Fmt.views(s.totalViews)} views")
                        Text("avg ${Fmt.views(s.avgViews.toLong())} · median ${Fmt.views(s.medianViews.toLong())}")
                        Text("Top: ${s.topTitle} (${Fmt.views(s.topViews)})", style = MaterialTheme.typography.bodySmall)
                    }
                }
                "videos" -> items(res.videos.take(100)) { r ->
                    Section("${x(r.score)}x  ·  ${Fmt.views(r.v.views)} views") {
                        Text(r.v.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(r.v.channel + (r.v.date?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                "words" -> items(res.words.take(150)) { w ->
                    Section("\"${w.term}\"  ·  ${x(w.lift)}x") {
                        Text("${w.videos} videos · avg ${Fmt.views(w.avgViews.toLong())} · median ${Fmt.views(w.medianViews.toLong())}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> items(res.patterns) { p ->
                    Section("${p.pattern}  ·  ${x(p.lift)}x" + if (p.reliable) "" else "  (few videos)") {
                        Text("with: ${p.videos} videos · without: ${p.withoutVideos} videos (${x(p.withoutLift)}x)",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun x(v: Double?): String = if (v == null) "-" else String.format(Locale.US, "%.2f", v)
