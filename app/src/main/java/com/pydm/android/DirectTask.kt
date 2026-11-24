package com.pydm.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/** Multi-connection direct downloader (engine.py ka port). */
class DirectTask(
    var url: String,
    var filename: String,
    var referrer: String,
    var connections: Int,
    id: String = java.util.UUID.randomUUID().toString()
) : Task("direct", id) {

    class Seg(val idx: Int, val start: Long, val end: Long) {
        @Volatile var done = 0L
        @Volatile var size = 0L
        @Volatile var finished = false
    }

    private var supportsRanges = false
    private var segments = mutableListOf<Seg>()
    private val halt = AtomicBoolean(false)
    private val needsSingle = AtomicBoolean(false)
    @Volatile private var attemptError: String? = null

    init { if (title.isBlank()) title = filename.ifBlank { Http.guessFilename(url, null) } }

    private fun partFile(i: Int) = File(workDir, "part$i")
    private fun halted(tok: RunToken) = tok.stopped || halt.get()

    private fun sumDone(): Long = segments.sumOf { it.done }

    private fun buildSegments() {
        segments = mutableListOf()
        if (supportsRanges && total > 0 && connections > 1) {
            val n = connections.coerceAtMost(16)
            val chunk = total / n
            for (i in 0 until n) {
                val s = i * chunk
                val e = if (i == n - 1) total - 1 else (s + chunk - 1)
                segments.add(Seg(i, s, e).also { it.size = e - s + 1 })
            }
        } else {
            val seg = Seg(0, 0, if (total > 0) total - 1 else -1)
            seg.size = if (total > 0) total else 0
            segments.add(seg)
        }
    }

    override fun run(tok: RunToken) {
        workDir.mkdirs()
        if (segments.isEmpty()) {
            status = Status.PROBING
            val p = Http.probe(url, referrer)
            if (tok.stopped) return
            total = p.total ?: 0L
            supportsRanges = p.ranges && total > 0
            if (filename.isBlank() || filename == "download") filename = p.filename
            title = filename
            workDir.listFiles()?.forEach { it.delete() }
            buildSegments()
            Manager.save()
        }

        while (true) {
            halt.set(false); needsSingle.set(false); attemptError = null
            status = Status.DOWNLOADING
            val pending = segments.filter { !isSegDone(it) }
            val threads = pending.map { seg ->
                Thread { worker(seg, tok) }.also { it.isDaemon = true; it.start() }
            }
            var ticks = 0
            while (threads.any { it.isAlive }) {
                downloaded = sumDone()
                if (++ticks % 10 == 0) Manager.save()
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            }
            threads.forEach { it.join(10000) }
            downloaded = sumDone()
            if (tok.stopped) return
            attemptError?.let { fail(tok, it); return }
            if (needsSingle.get()) {
                // Server ne Range support nahi kiya -> single connection
                supportsRanges = false
                workDir.listFiles()?.forEach { it.delete() }
                buildSegments()
                continue
            }
            if (segments.all { isSegDone(it) }) break
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        }

        status = Status.MERGING
        val out = File(workDir, "merged.tmp")
        if (segments.size == 1) {
            out.delete()
            if (!partFile(0).renameTo(out)) partFile(0).copyTo(out, true)
        } else {
            FileOutputStream(out).use { o ->
                for (s in segments.sortedBy { it.idx }) {
                    partFile(s.idx).inputStream().use { it.copyTo(o, 256 * 1024) }
                }
            }
        }
        if (total > 0 && out.length() != total) {
            fail(tok, "File size mismatch (${out.length()} / $total) - retry")
            segments.clear(); workDir.listFiles()?.forEach { it.delete() }
            return
        }
        total = out.length(); downloaded = total
        outUri = Storage.publish(app, out, "", filename)
        workDir.deleteRecursively()
        complete(tok)
    }

    private fun isSegDone(s: Seg) = s.finished || (s.size > 0 && s.done >= s.size)

    private fun worker(seg: Seg, tok: RunToken) {
        val part = partFile(seg.idx)
        val useRange = supportsRanges && seg.size > 0
        try {
            if (useRange) {
                val existing = if (part.exists()) part.length() else 0L
                seg.done = minOf(existing, seg.size)
                if (existing > seg.done) RandomAccessFile(part, "rw").use { it.setLength(seg.done) }
            } else {
                seg.done = 0
            }
            while (true) {
                if (halted(tok)) return
                if (isSegDone(seg)) return
                if (!fetch(seg, part, useRange, tok)) return
                Thread.sleep(50)
            }
        } catch (e: Exception) {
            if (!halted(tok)) { attemptError = e.message ?: e.toString(); halt.set(true) }
        }
    }

    /** false = fatal error (attemptError set). */
    private fun fetch(seg: Seg, part: File, useRange: Boolean, tok: RunToken): Boolean {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt <= MAX_RETRIES) {
            if (halted(tok)) return true
            var conn: HttpURLConnection? = null
            try {
                if (!useRange) seg.done = 0
                val from = seg.start + seg.done
                conn = Http.open(url, referrer, if (useRange) "bytes=$from-${seg.end}" else null)
                val code = conn.responseCode
                if (useRange && code != 206) {
                    needsSingle.set(true); halt.set(true)
                    return true
                }
                if (code >= 400) throw IOException("HTTP $code")
                FileOutputStream(part, useRange).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (halted(tok)) return true
                            val n = input.read(buf)
                            if (n < 0) break
                            Manager.limiter.consume(n)
                            out.write(buf, 0, n)
                            seg.done += n
                            if (seg.size > 0 && seg.done >= seg.size) return true
                        }
                    }
                }
                if (!useRange) {
                    seg.size = seg.done; seg.finished = true
                    total = seg.done
                }
                return true
            } catch (e: Exception) {
                lastErr = e
                attempt++
                if (halted(tok)) return true
                try { Thread.sleep(minOf(1L shl attempt, 20L) * 1000) } catch (_: InterruptedException) {}
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        attemptError = "Connection lost after $MAX_RETRIES retries: ${lastErr?.message}"
        halt.set(true)
        return false
    }

    override fun onPause() { halt.set(true) }

    override fun extraToJson(o: JSONObject) {
        o.put("url", url); o.put("filename", filename); o.put("referrer", referrer)
        o.put("connections", connections); o.put("ranges", supportsRanges)
        val arr = JSONArray()
        for (s in segments) {
            arr.put(JSONObject().put("i", s.idx).put("s", s.start).put("e", s.end)
                .put("done", s.done).put("size", s.size).put("fin", s.finished))
        }
        o.put("segments", arr)
    }

    override fun loadExtra(o: JSONObject) {
        url = o.optString("url"); filename = o.optString("filename"); referrer = o.optString("referrer")
        connections = o.optInt("connections", 8); supportsRanges = o.optBoolean("ranges")
        segments = mutableListOf()
        val arr = o.optJSONArray("segments") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            segments.add(Seg(j.getInt("i"), j.getLong("s"), j.getLong("e")).also {
                it.done = j.optLong("done"); it.size = j.optLong("size"); it.finished = j.optBoolean("fin")
            })
        }
    }

    companion object {
        private const val MAX_RETRIES = 6
        fun restore(o: JSONObject): DirectTask {
            val t = DirectTask(o.optString("url"), o.optString("filename"), o.optString("referrer"),
                o.optInt("connections", 8), o.optString("id"))
            t.loadBase(o)
            return t
        }
    }
}
