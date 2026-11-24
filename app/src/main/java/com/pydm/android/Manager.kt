package com.pydm.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList

object Manager {
    lateinit var app: Context
    lateinit var settings: Settings
    val limiter = SpeedLimiter()
    val tasks = CopyOnWriteArrayList<Task>()
    private val schedPaused = HashSet<String>()
    private var lastSched = 0L
    @Volatile private var started = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        settings = Settings(app)
        applySettings()
        load()
        started = true
        Thread {
            while (true) {
                try { loopOnce() } catch (_: Throwable) {}
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true; it.name = "pydm-manager"; it.start() }
    }

    fun applySettings() {
        limiter.limitBps = settings.speedLimitKbps.toLong() * 1024
    }

    private fun loopOnce() {
        val now = System.currentTimeMillis()
        var anyActive = false
        for (t in tasks) {
            t.tick(now)
            if (t.isActive) anyActive = true
        }
        if (now - lastSched > 30000) { lastSched = now; schedTick() }
        if (anyActive) DownloadService.ensureRunning(app)
    }

    private fun schedTick() {
        if (!settings.schedEnabled) return
        val inWindow = inWindow(settings.schedStart, settings.schedStop)
        if (inWindow) {
            for (id in schedPaused.toList()) tasks.find { it.id == id }?.resume()
            schedPaused.clear()
            pump()
        } else {
            for (t in tasks) if (t.isActive) { schedPaused.add(t.id); t.pause() }
        }
    }

    private fun minutes(s: String): Int? {
        val p = s.trim().split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun inWindow(start: String, stop: String): Boolean {
        val a = minutes(start) ?: return true
        val b = minutes(stop) ?: return true
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (a <= b) now in a until b else (now >= a || now < b)
    }

    @Synchronized fun pump() {
        var slots = settings.maxConcurrent - tasks.count { it.isRunning }
        for (t in tasks.sortedBy { it.added }) {
            if (slots <= 0) break
            if (t.status == Status.QUEUED) { t.launch(); slots-- }
        }
    }

    fun add(t: Task) {
        tasks.add(t)
        save()
        pump()
    }

    fun addDirect(url: String, filename: String = ""): DirectTask {
        val u = url.trim()
        require(u.startsWith("http://") || u.startsWith("https://")) { "Link http:// ya https:// se shuru hona chahiye" }
        val t = DirectTask(u, filename, "", settings.connections)
        add(t)
        return t
    }

    fun remove(t: Task) {
        t.pause()
        tasks.remove(t)
        try { t.onRemove() } catch (_: Throwable) {}
        save()
    }

    fun pauseAll() { tasks.forEach { if (it.isActive) it.pause() } }
    fun resumeAll() { tasks.forEach { it.resume() }; pump() }
    fun clearDone() { tasks.filter { it.status == Status.COMPLETED }.forEach { tasks.remove(it); it.onRemove() }; save() }

    fun views(): List<TaskView> = tasks.sortedByDescending { it.added }.map { it.view() }
    fun get(id: String): Task? = tasks.find { it.id == id }
    fun hasActive() = tasks.any { it.isActive }
    fun activeCount() = tasks.count { it.isActive }

    // ------------------------------------------------------------- persistence
    private val file get() = File(app.filesDir, "tasks.json")

    @Synchronized fun save() {
        if (!started) return
        try {
            val arr = JSONArray()
            for (t in tasks) arr.put(t.toJson())
            val tmp = File(app.filesDir, "tasks.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        } catch (_: Throwable) {}
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t: Task? = when (o.optString("kind")) {
                    "direct" -> DirectTask.restore(o)
                    "yt" -> YtTask.restore(o)
                    "channel" -> ChannelTask.restore(o)
                    "transcript" -> TranscriptTask.restore(o)
                    else -> null
                }
                if (t != null) tasks.add(t)
            }
        } catch (_: Throwable) {}
    }
}
