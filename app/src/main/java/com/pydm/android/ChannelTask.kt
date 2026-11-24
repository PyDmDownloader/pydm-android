package com.pydm.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Channel ke selected videos ke thumbnails + titles ka document. */
class ChannelTask(
    var channel: String,
    var videos: List<Entry>,
    var doThumbs: Boolean,
    var doTitles: Boolean,
    var png: Boolean,
    id: String = java.util.UUID.randomUUID().toString()
) : Task("channel", id) {

    private var doneIds = HashSet<String>()
    private var failed = 0
    private var titlesDone = false

    init { if (title.isBlank()) title = "$channel - thumbs/titles" }

    private fun folder() = Fmt.safeName(channel)

    override fun run(tok: RunToken) {
        status = Status.DOWNLOADING
        val sub = folder()
        total = if (doThumbs) videos.size.toLong() else 0L

        if (doThumbs) {
            val todo = videos.filter { it.id !in doneIds }
            val counter = AtomicInteger(doneIds.size)
            val failCount = AtomicInteger(failed)
            val pool = Executors.newFixedThreadPool(4)
            for (v in todo) {
                pool.execute {
                    if (tok.stopped) return@execute
                    try {
                        val data = Yt.downloadThumbnail(v.id)
                        if (tok.stopped) return@execute
                        val name = Fmt.safeName(v.title) + " [" + v.id + "]"
                        if (png) {
                            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                            val bos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                            Storage.publishBytes(app, bos.toByteArray(), "$sub/thumbnails", "$name.png")
                        } else {
                            Storage.publishBytes(app, data, "$sub/thumbnails", "$name.jpg")
                        }
                        synchronized(doneIds) { doneIds.add(v.id) }
                    } catch (_: Exception) {
                        failCount.incrementAndGet()
                    }
                    val n = counter.incrementAndGet()
                    downloaded = n.toLong()
                    detail = "$n / ${videos.size} thumbnails"
                }
            }
            pool.shutdown()
            while (!pool.awaitTermination(300, TimeUnit.MILLISECONDS)) {
                if (tok.stopped) { pool.shutdownNow(); break }
            }
            failed = failCount.get()
            if (tok.stopped) return
        }

        if (doTitles && !titlesDone) {
            detail = "Writing titles document..."
            val lines = videos.mapIndexed { i, v -> Docx.Para("${i + 1}. ${v.title}") }
            val doc = listOf(Docx.Para("$channel - All Video Titles", bold = true, size = 36), Docx.Para("")) + lines
            outUri = Storage.publishBytes(app, Docx.build(doc), sub, Fmt.safeName(channel) + " - titles.docx")
            titlesDone = true
        }
        if (tok.stopped) return
        detail = buildString {
            append("Saved in Downloads/PyDM/").append(sub)
            if (failed > 0) append("  ($failed thumbnails failed)")
        }
        complete(tok)
    }

    override fun extraToJson(o: JSONObject) {
        o.put("channel", channel); o.put("thumbs", doThumbs); o.put("titles", doTitles); o.put("png", png)
        o.put("titlesDone", titlesDone); o.put("failed", failed)
        val v = JSONArray(); videos.forEach { v.put(JSONObject().put("id", it.id).put("t", it.title)) }
        o.put("videos", v)
        o.put("done", JSONArray(synchronized(doneIds) { doneIds.toList() }))
    }

    override fun loadExtra(o: JSONObject) {
        channel = o.optString("channel"); doThumbs = o.optBoolean("thumbs"); doTitles = o.optBoolean("titles")
        png = o.optBoolean("png"); titlesDone = o.optBoolean("titlesDone"); failed = o.optInt("failed")
        val a = o.optJSONArray("videos") ?: JSONArray()
        videos = (0 until a.length()).map { Entry(a.getJSONObject(it).getString("id"), a.getJSONObject(it).getString("t"), null, null) }
        val d = o.optJSONArray("done") ?: JSONArray()
        doneIds = HashSet((0 until d.length()).map { d.getString(it) })
    }

    override fun onRemove() { /* kuch temp nahi */ }

    companion object {
        fun restore(o: JSONObject): ChannelTask {
            val t = ChannelTask("", emptyList(), false, false, false, o.optString("id"))
            t.loadBase(o)
            return t
        }
    }
}
