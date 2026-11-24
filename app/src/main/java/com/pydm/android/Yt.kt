package com.pydm.android

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class VideoMeta(val title: String, val duration: Long, val heights: List<Int>, val id: String)
data class Entry(val id: String, val title: String, val views: Long?, val date: String?)
data class TranscriptResult(val date: String?, val views: Long?, val text: String?, val reason: String)

/** yt-dlp (youtubedl-android) ke upar PyDM ke YouTube features (youtube.py ka port). */
object Yt {
    private val ydl get() = YoutubeDL.getInstance()

    fun isYoutubeUrl(u: String): Boolean = Regex(
        "^(https?://)?((www|m|music)\\.)?(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/",
        RegexOption.IGNORE_CASE).containsMatchIn(u.trim())

    fun isPlaylistUrl(u: String) = "list=" in u || "/playlist" in u

    fun waitReady(timeoutMs: Long = 90000) {
        val app = PyDMApp.instance
        val end = System.currentTimeMillis() + timeoutMs
        while (!app.ytReady) {
            if (app.ytInitError.isNotBlank()) throw RuntimeException("yt-dlp could not start: " + app.ytInitError)
            if (System.currentTimeMillis() > end) throw RuntimeException("yt-dlp is still starting - wait a few seconds and retry")
            Thread.sleep(300)
        }
    }

    // ------------------------------------------------------------ errors
    fun friendlyError(e: Throwable): String {
        var s = e.message ?: e.toString()
        val low = s.lowercase()
        if ("not a bot" in s || "sign in to confirm" in low)
            return "YouTube asked for sign-in (bot check). Try again later, or switch between Wi-Fi and mobile data."
        s = s.replace(Regex("^(ERROR:\\s*)+"), "")
        s = s.replace(Regex("^\\[youtube\\][^:]*:\\s*"), "")
        val tech = s.lines().lastOrNull { it.isNotBlank() }?.trim() ?: s
        val t = tech.take(220)
        val reason = when {
            Regex("403|forbidden").containsMatchIn(low) -> "YouTube blocked (403)"
            Regex("connection reset|remote end closed|connection aborted").containsMatchIn(low) -> "Connection dropped"
            Regex("timed out|timeout").containsMatchIn(low) -> "Timed out - retry"
            Regex("unable to resolve|name or service|no route to host|network is unreachable|unknownhost").containsMatchIn(low) -> "No internet / DNS problem"
            "429" in s || "too many requests" in low -> "Too many requests - wait"
            "private video" in low -> "Private video"
            "video unavailable" in low -> "Video unavailable"
            else -> null
        }
        return if (reason != null) "$reason  ($t)" else t
    }

    // ------------------------------------------------------------ yt-dlp helpers
    private fun runJson(url: String, extra: (YoutubeDLRequest) -> Unit = {}): JSONObject {
        waitReady()
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--no-warnings")
        req.addOption("--skip-download")
        req.addOption("--socket-timeout", "30")
        extra(req)
        val resp = ydl.execute(req, processId = null, callback = null)
        val out = resp.out.trim()
        val i = out.indexOf('{')
        if (i < 0) throw RuntimeException(resp.err.ifBlank { "yt-dlp returned no data" })
        return JSONObject(out.substring(i))
    }

    private fun JSONObject.longOrNull(k: String): Long? =
        if (has(k) && !isNull(k)) optLong(k) else null

    private fun dateOf(e: JSONObject): String? {
        val ts = e.longOrNull("timestamp")
        if (ts != null) {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date(ts * 1000))
        }
        val ud = e.optString("upload_date")
        return if (ud.length == 8) "${ud.substring(0, 4)}-${ud.substring(4, 6)}-${ud.substring(6, 8)}" else null
    }

    // ------------------------------------------------------------ single video / playlist
    fun fetchInfo(url: String): VideoMeta {
        val j = runJson(url) { it.addOption("--no-playlist") }
        val hs = sortedSetOf<Int>()
        val fm = j.optJSONArray("formats") ?: JSONArray()
        for (i in 0 until fm.length()) {
            val f = fm.optJSONObject(i) ?: continue
            val vc = f.optString("vcodec", "none")
            val h = f.optInt("height", 0)
            if (vc != "none" && vc != "null" && h > 0) hs.add(h)
        }
        return VideoMeta(j.optString("title", "YouTube video"), j.optLong("duration", 0),
            hs.toList().sortedDescending(), j.optString("id"))
    }

    fun fetchPlaylist(url: String): Pair<String, List<Entry>> {
        val j = runJson(url) { it.addOption("--flat-playlist") }
        val title = Fmt.safeName(j.optString("title", "playlist"))
        val out = ArrayList<Entry>()
        val arr = j.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            var id = e.optString("id")
            if (id.isBlank()) id = e.optString("url")
            if ("watch?v=" in id) id = id.substringAfter("watch?v=").substringBefore("&")
            if (id.isNotBlank() && id.length < 32)
                out.add(Entry(id, e.optString("title", id), e.longOrNull("view_count"), dateOf(e)))
        }
        return title to out
    }

    // ------------------------------------------------------------ channel
    private val TAB_RE = Regex(
        "^(https?://(?:www\\.|m\\.)?youtube\\.com/(?:@[^/?#]+|channel/[^/?#]+|c/[^/?#]+|user/[^/?#]+))" +
        "(?:/(?:videos|shorts|streams|live|featured|home|playlists|community|about|releases|podcasts))?/?(?:[?#].*)?$",
        RegexOption.IGNORE_CASE)

    fun normalizeChannelUrl(url: String): String {
        var u = url.trim().trim('<', '>', '"', '\'')
        if (u.isEmpty()) return u
        if (u.startsWith("@")) u = "https://www.youtube.com/$u"
        else if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u) &&
            Regex("^(www\\.|m\\.)?youtube\\.com/", RegexOption.IGNORE_CASE).containsMatchIn(u)) u = "https://$u"
        u = u.replace(Regex("^(https?://)m\\.youtube\\.com", RegexOption.IGNORE_CASE), "$1www.youtube.com")
        u = u.replace(Regex("^(https?://)youtube\\.com", RegexOption.IGNORE_CASE), "$1www.youtube.com")
        return u
    }

    private fun channelTabUrls(url: String): List<String> {
        val u = normalizeChannelUrl(url)
        val m = TAB_RE.find(u) ?: return listOf(u)
        val root = m.groupValues[1]
        val tail = u.substring(root.length).trimStart('/').substringBefore("?").substringBefore("#")
            .trimEnd('/').lowercase()
        if (tail in listOf("videos", "shorts", "streams", "live"))
            return listOf(root + "/" + (if (tail == "live") "streams" else tail))
        return listOf("$root/videos", "$root/streams", "$root/shorts")
    }

    private fun flatten(entries: JSONArray?, out: MutableList<JSONObject>, depth: Int = 0) {
        if (entries == null) return
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            if (e.optString("_type") == "playlist" || e.has("entries")) {
                if (depth < 3) flatten(e.optJSONArray("entries"), out, depth + 1)
                continue
            }
            if (e.optString("id").length == 11) out.add(e)
        }
    }

    /** (channel title, videos sorted by most views). */
    fun fetchChannelVideos(url: String): Pair<String, List<Entry>> {
        var title = ""
        var raw: List<JSONObject> = emptyList()
        var lastErr: Throwable? = null
        for (turl in channelTabUrls(url)) {
            val j = try {
                runJson(turl) {
                    it.addOption("--flat-playlist")
                    it.addOption("--extractor-args", "youtubetab:approximate_date")
                }
            } catch (e: Throwable) {
                if (lastErr == null || ("tab" in (lastErr.message ?: "").lowercase() &&
                        "tab" !in (e.message ?: "").lowercase())) lastErr = e
                continue
            }
            if (title.isEmpty()) {
                title = (j.optString("channel").ifBlank { j.optString("uploader") }).trim()
                if (title.isEmpty()) {
                    val t = j.optString("title", "channel")
                    title = t.replace(Regex("\\s*-\\s*(Videos|Shorts|Live)\\s*$"), "").trim().ifEmpty { t }
                }
            }
            val found = ArrayList<JSONObject>()
            flatten(j.optJSONArray("entries"), found)
            if (found.isNotEmpty()) { raw = found; break }
        }
        if (raw.isEmpty() && lastErr != null) throw RuntimeException(friendlyError(lastErr))
        val seen = HashSet<String>()
        val videos = ArrayList<Entry>()
        for (e in raw) {
            val id = e.optString("id")
            if (!seen.add(id)) continue
            val views = e.longOrNull("view_count") ?: e.longOrNull("concurrent_view_count")
            videos.add(Entry(id, e.optString("title", id).ifBlank { id }, views, dateOf(e)))
        }
        videos.sortWith(compareBy<Entry> { it.views == null }.thenByDescending { it.views ?: 0L })
        return Fmt.safeName(title.ifEmpty { "channel" }) to videos
    }

    // ------------------------------------------------------------ presets
    class Preset(val key: String, val format: String, val container: String, val needMerge: Boolean)

    fun makePreset(quality: String, container: String, vcodec: String = "avc"): Preset {
        if (quality == "audio") return Preset("audio", "bestaudio/best", container, false)
        if (quality == "best") {
            val fmt = if (vcodec == "avc")
                "bestvideo[vcodec^=avc]+bestaudio[acodec^=mp4a]/best[vcodec^=avc]/bestvideo+bestaudio/best"
            else "bestvideo+bestaudio/best"
            return Preset("best", fmt, container, true)
        }
        val h = quality.toInt()
        val fmt = if (vcodec == "avc")
            "bestvideo[vcodec^=avc][height<=$h]+bestaudio[acodec^=mp4a]/best[vcodec^=avc][height<=$h]/best[height<=$h]/best"
        else "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best"
        return Preset("${h}p", fmt, container, true)
    }

    fun qualityLabel(h: Int) = when (h) {
        2160 -> "2160p 4K"; 1440 -> "1440p QHD"; 1080 -> "1080p Full HD"; 720 -> "720p HD"
        else -> "${h}p"
    }

    // ------------------------------------------------------------ thumbnails
    private val THUMBS = listOf("maxresdefault", "sddefault", "hqdefault", "mqdefault", "default")

    fun downloadThumbnail(id: String): ByteArray {
        for (q in THUMBS) {
            try {
                val data = Http.getBytes("https://i.ytimg.com/vi/$id/$q.jpg")
                if (data.size >= 2048) return data
            } catch (_: Exception) {}
        }
        throw RuntimeException("thumbnail not found for $id")
    }

    // ------------------------------------------------------------ transcripts
    private class Cand(val key: String, val tracks: JSONArray)

    private fun langCandidates(info: JSONObject): List<Cand> {
        val manual = info.optJSONObject("subtitles") ?: JSONObject()
        val auto = info.optJSONObject("automatic_captions") ?: JSONObject()
        val out = ArrayList<Cand>()
        val seen = HashSet<String>()
        fun keys(d: JSONObject): List<String> = d.keys().asSequence().toList()
        fun add(src: String, key: String, d: JSONObject) {
            val tr = d.optJSONArray(key)
            if (tr != null && tr.length() > 0 && seen.add("$src:$key")) out.add(Cand(key, tr))
        }
        fun enKeys(d: JSONObject): List<String> {
            val ks = keys(d)
            val r = ks.filter { it.equals("en", true) }.toMutableList()
            r += ks.filter { it.lowercase().startsWith("en") && it !in r && it.lowercase() != "en-orig" }
            if ("en-orig" in ks) r.add("en-orig")
            return r
        }
        enKeys(manual).forEach { add("manual", it, manual) }
        enKeys(auto).forEach { add("auto", it, auto) }
        keys(manual).take(3).forEach { add("manual", it, manual) }
        keys(auto).filter { it.lowercase().endsWith("-orig") }.take(2).forEach { add("auto", it, auto) }
        val lang = info.optString("language")
        if (lang.isNotBlank() && auto.has(lang)) add("auto", lang, auto)
        return out
    }

    fun cleanTranscript(parts: List<String>, dedupe: Boolean = true): String? {
        val words = ArrayList<String>()
        for (p in parts) {
            val ws = p.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (ws.isEmpty()) continue
            var best = 0
            val maxk = if (dedupe) minOf(ws.size, words.size, 15) else 0
            for (k in maxk downTo 1) {
                if (words.subList(words.size - k, words.size) == ws.subList(0, k)) { best = k; break }
            }
            words.addAll(ws.subList(best, ws.size))
        }
        var text = words.joinToString(" ").replace(Regex("\\s+([.,!?;:])"), "$1")
        if (text.isBlank()) return null
        val sents = text.split(Regex("(?<=[.!?])\\s+"))
        val paras = ArrayList<String>()
        var cur = ArrayList<String>()
        for (s in sents) {
            if (s.isNotBlank()) cur.add(s.trim())
            if (cur.size >= 5) { paras.add(cur.joinToString(" ")); cur = ArrayList() }
        }
        if (cur.isNotEmpty()) paras.add(cur.joinToString(" "))
        return paras.joinToString("\n\n")
    }

    private fun parseJson3(raw: String): String? {
        return try {
            val j = JSONObject(raw)
            val parts = ArrayList<String>()
            val ev = j.optJSONArray("events") ?: JSONArray()
            for (i in 0 until ev.length()) {
                val segs = ev.optJSONObject(i)?.optJSONArray("segs") ?: continue
                for (k in 0 until segs.length()) {
                    val t = segs.optJSONObject(k)?.optString("utf8", "")?.replace("\n", " ")?.trim().orEmpty()
                    if (t.isNotEmpty()) parts.add(t)
                }
            }
            cleanTranscript(parts)
        } catch (_: Exception) { null }
    }

    private fun parseVtt(raw: String): String? {
        val parts = ArrayList<String>()
        for (line in raw.lines()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("WEBVTT") || "-->" in s || s.all { it.isDigit() }) continue
            val c = s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
            if (c.isNotEmpty()) parts.add(c)
        }
        return cleanTranscript(parts)
    }

    private fun textFromTracks(tracks: JSONArray): Pair<String?, String> {
        var reason = "caption file empty"
        for (want in listOf("json3", "vtt")) {
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                if (t.optString("ext") != want || t.optString("url").isBlank()) continue
                for (attempt in 0 until 3) {
                    var raw = ""
                    try { raw = String(Http.getBytes(t.getString("url")), Charsets.UTF_8) }
                    catch (e: Exception) { reason = "caption download failed (${(e.message ?: "").take(80)})" }
                    if (raw.isNotBlank()) {
                        val text = if (want == "json3") parseJson3(raw) else parseVtt(raw)
                        if (text != null) return text to ""
                        reason = "caption had no text"
                        break
                    }
                    Thread.sleep(1500L * (attempt + 1))
                }
                break
            }
        }
        return null to reason
    }

    /** Doosra raasta: yt-dlp khud subtitle files likhe. */
    private fun transcriptViaFiles(id: String, langs: List<String>, dir: File): String? {
        dir.deleteRecursively(); dir.mkdirs()
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
        req.addOption("--skip-download"); req.addOption("--no-playlist"); req.addOption("--no-warnings")
        req.addOption("--write-subs"); req.addOption("--write-auto-subs")
        req.addOption("--sub-langs", langs.joinToString(","))
        req.addOption("--sub-format", "json3/vtt")
        req.addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
        try { ydl.execute(req, processId = null, callback = null) } catch (_: Exception) {}
        val files = dir.listFiles()?.sortedBy { if (it.name.endsWith(".json3")) 0 else 1 } ?: return null
        for (f in files) {
            val raw = try { f.readText() } catch (_: Exception) { continue }
            val text = if (f.name.endsWith(".json3")) parseJson3(raw) else parseVtt(raw)
            if (text != null) return text
        }
        return null
    }

    fun fetchTranscript(id: String, tmpDir: File): TranscriptResult {
        var date: String? = null
        var views: Long? = null
        var reason = "no captions on this video"
        try {
            val info = runJson("https://www.youtube.com/watch?v=$id") { it.addOption("--no-playlist") }
            views = info.longOrNull("view_count")
            date = dateOf(info)
            val cands = langCandidates(info)
            if (cands.isEmpty()) return TranscriptResult(date, views, null, reason)
            for (c in cands.take(4)) {
                val (text, why) = textFromTracks(c.tracks)
                if (text != null) return TranscriptResult(date, views, text, "")
                reason = why
            }
            // fallback: yt-dlp khud download kare
            val text = transcriptViaFiles(id, cands.take(3).map { it.key }, File(tmpDir, "subs_$id"))
            File(tmpDir, "subs_$id").deleteRecursively()
            if (text != null) return TranscriptResult(date, views, text, "")
            return TranscriptResult(date, views, null, reason)
        } catch (e: Throwable) {
            return TranscriptResult(date, views, null, friendlyError(e))
        }
    }
}
