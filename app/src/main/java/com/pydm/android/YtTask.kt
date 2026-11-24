package com.pydm.android

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

/** Ek YouTube video/audio download (yt-dlp). */
class YtTask(
    var url: String,
    var baseName: String,
    var quality: String,     // "best" | "audio" | "1080" ...
    var container: String,   // mp4 mkv webm mp3 m4a
    var codec: String,       // avc | best
    var subDir: String = "",
    id: String = java.util.UUID.randomUUID().toString()
) : Task("yt", id) {

    init { if (title.isBlank()) title = baseName }

    private val procId get() = "yt-$id"

    override fun run(tok: RunToken) {
        Yt.waitReady()
        status = Status.DOWNLOADING
        val preset = Yt.makePreset(quality, container, codec)
        workDir.mkdirs()
        val req = YoutubeDLRequest(url)
        req.addOption("-f", preset.format)
        req.addOption("-o", File(workDir, "media.%(ext)s").absolutePath)
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        req.addOption("--retries", "8")
        req.addOption("--fragment-retries", "8")
        req.addOption("--continue")
        req.addOption("--no-mtime")
        req.addOption("--newline")
        val lim = Manager.settings.speedLimitKbps
        if (lim > 0) req.addOption("--limit-rate", "${lim}K")
        if (preset.key == "audio") {
            req.addOption("-x")
            req.addOption("--audio-format", container)
            req.addOption("--audio-quality", "192K")
        } else {
            req.addOption("--merge-output-format", container)
        }
        val sizeRe = Regex("of\\s+~?\\s*([\\d.]+)\\s*([KMGT]?i?B)")
        try {
            YoutubeDL.getInstance().execute(req, processId = procId) { progress, _, line ->
                if (tok.stopped) return@execute
                if (progress >= 0) percent = (progress / 100f).coerceIn(0f, 1f)
                sizeRe.find(line)?.let { m ->
                    val n = m.groupValues[1].toDoubleOrNull()
                    if (n != null) {
                        val mult = when (m.groupValues[2].uppercase().replace("I", "")) {
                            "KB" -> 1024.0; "MB" -> 1048576.0; "GB" -> 1073741824.0; "TB" -> 1099511627776.0
                            else -> 1.0
                        }
                        total = (n * mult).toLong()
                        downloaded = (total * (percent.coerceAtLeast(0f))).toLong()
                    }
                }
            }
        } catch (e: Throwable) {
            if (tok.stopped) return          // user ne roka (process kill hua)
            fail(tok, Yt.friendlyError(e))
            return
        }
        if (tok.stopped) return

        val out = workDir.listFiles()?.firstOrNull {
            it.name.startsWith("media.") && !it.name.endsWith(".part") &&
                !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp")
        }
        if (out == null) { fail(tok, "Download finished but no output file found"); return }
        status = Status.MERGING
        val ext = out.name.substringAfterLast('.')
        val finalName = Fmt.safeName(baseName) + "." + ext
        total = out.length(); downloaded = total
        outUri = Storage.publish(app, out, subDir, finalName)
        workDir.deleteRecursively()
        complete(tok)
    }

    override fun onPause() {
        try { YoutubeDL.getInstance().destroyProcessById(procId) } catch (_: Throwable) {}
    }

    override fun extraToJson(o: JSONObject) {
        o.put("url", url); o.put("base", baseName); o.put("quality", quality)
        o.put("container", container); o.put("codec", codec); o.put("sub", subDir)
    }

    override fun loadExtra(o: JSONObject) {
        url = o.optString("url"); baseName = o.optString("base"); quality = o.optString("quality")
        container = o.optString("container"); codec = o.optString("codec"); subDir = o.optString("sub")
    }

    companion object {
        fun restore(o: JSONObject): YtTask {
            val t = YtTask(o.optString("url"), o.optString("base"), o.optString("quality", "best"),
                o.optString("container", "mp4"), o.optString("codec", "avc"), o.optString("sub"), o.optString("id"))
            t.loadBase(o)
            return t
        }
    }
}
