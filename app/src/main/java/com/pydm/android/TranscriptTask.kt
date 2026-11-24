package com.pydm.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Channel/playlist ke selected videos ke transcripts -> docx/txt (ek file ya alag alag). */
class TranscriptTask(
    var channel: String,
    var videos: List<Entry>,
    var format: String,        // docx | txt
    var oneFile: Boolean,
    id: String = java.util.UUID.randomUUID().toString()
) : Task("transcript", id) {

    private class Res(val date: String?, val views: Long?, val text: String?, val reason: String)
    private var results = LinkedHashMap<String, Res>()

    init { if (title.isBlank()) title = "$channel - transcripts" }

    private fun resFile() = File(workDir, "results.json")

    override fun run(tok: RunToken) {
        workDir.mkdirs()
        if (results.isEmpty()) loadResults()
        status = Status.DOWNLOADING
        total = videos.size.toLong()
        val sub = "Transcript " + Fmt.safeName(channel)

        for (v in videos) {
            if (tok.stopped) return
            if (results.containsKey(v.id)) continue
            detail = "${results.size + 1}/${videos.size}: ${v.title}"
            val r = Yt.fetchTranscript(v.id, workDir)
            if (tok.stopped) return
            results[v.id] = Res(r.date ?: v.date, r.views ?: v.views, r.text, r.reason)
            downloaded = results.size.toLong()
            saveResults()
            Thread.sleep(400)   // YouTube ko zyada tang na karo
        }

        status = Status.MERGING
        detail = "Writing files..."
        val ok = results.values.count { it.text != null }
        if (oneFile) {
            val name = Fmt.safeName(channel) + " - transcripts." + format
            outUri = Storage.publishBytes(app, build(videos, true), sub, name)
        } else {
            for ((i, v) in videos.withIndex()) {
                if (tok.stopped) return
                val name = "%03d ".format(i + 1) + Fmt.safeName(v.title) + " - transcript." + format
                val u = Storage.publishBytes(app, build(listOf(v), false), sub, name)
                if (outUri == null) outUri = u
            }
        }
        detail = "$ok / ${videos.size} transcripts found  ·  Downloads/PyDM/$sub"
        workDir.deleteRecursively()
        complete(tok)
    }

    private fun naText(reason: String) =
        "[Transcript not available for this video" + (if (reason.isNotBlank()) ": $reason" else "") + "]"

    private fun block(v: Entry, first: Boolean): List<String> {
        val r = results[v.id]
        val out = ArrayList<String>()
        if (!first) { out.add("—".repeat(30)); out.add("") }
        out.add("Title: ${v.title}"); out.add("")
        out.add("Upload Date: ${r?.date ?: "Unknown"}"); out.add("")
        out.add("Total Views: ${r?.views?.let { Fmt.views(it) } ?: "Unknown"}"); out.add("")
        out.add("Video Transcript:"); out.add("")
        if (r?.text != null) out.addAll(r.text.split("\n\n")) else out.add(naText(r?.reason ?: ""))
        out.add("")
        return out
    }

    private fun build(list: List<Entry>, multi: Boolean): ByteArray {
        val lines = ArrayList<String>()
        list.forEachIndexed { i, v -> lines.addAll(block(v, i == 0)) }
        return if (format == "txt") lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        else Docx.build(lines.map { Docx.Para(it) })
    }

    private fun saveResults() {
        try {
            val o = JSONObject()
            for ((k, r) in results) o.put(k, JSONObject().put("d", r.date ?: "").put("v", r.views ?: -1)
                .put("t", r.text ?: JSONObject.NULL).put("r", r.reason))
            resFile().writeText(o.toString())
        } catch (_: Exception) {}
    }

    private fun loadResults() {
        try {
            if (!resFile().exists()) return
            val o = JSONObject(resFile().readText())
            for (k in o.keys()) {
                val j = o.getJSONObject(k)
                results[k] = Res(j.optString("d").ifBlank { null }, j.optLong("v", -1).let { if (it < 0) null else it },
                    if (j.isNull("t")) null else j.getString("t"), j.optString("r"))
            }
        } catch (_: Exception) {}
    }

    override fun extraToJson(o: JSONObject) {
        o.put("channel", channel); o.put("format", format); o.put("one", oneFile)
        val a = JSONArray()
        videos.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("d", it.date ?: "")
            .put("v", it.views ?: -1)) }
        o.put("videos", a)
    }

    override fun loadExtra(o: JSONObject) {
        channel = o.optString("channel"); format = o.optString("format", "docx"); oneFile = o.optBoolean("one", true)
        val a = o.optJSONArray("videos") ?: JSONArray()
        videos = (0 until a.length()).map {
            val j = a.getJSONObject(it)
            Entry(j.getString("id"), j.getString("t"), j.optLong("v", -1).let { v -> if (v < 0) null else v },
                j.optString("d").ifBlank { null })
        }
    }

    companion object {
        fun restore(o: JSONObject): TranscriptTask {
            val t = TranscriptTask("", emptyList(), "docx", true, o.optString("id"))
            t.loadBase(o)
            return t
        }
    }
}
