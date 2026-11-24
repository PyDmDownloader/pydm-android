package com.pydm.android

import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Status { QUEUED, PROBING, DOWNLOADING, MERGING, PAUSED, COMPLETED, ERROR }

/** Har run ka apna "roko" token, taake purana worker naye run ko disturb na kare. */
class RunToken { @Volatile var stopped = false }

data class TaskView(
    val id: String, val kind: String, val title: String, val status: Status,
    val progress: Float, val total: Long, val downloaded: Long, val speed: Double,
    val etaSec: Long, val error: String, val detail: String, val outUri: String?
)

abstract class Task(val kind: String, val id: String = UUID.randomUUID().toString()) {
    @Volatile var title = ""
    @Volatile var status = Status.QUEUED
    @Volatile var total = 0L
    @Volatile var downloaded = 0L
    @Volatile var percent = -1f          // >=0 ho to wohi progress dikhao
    @Volatile var speed = 0.0
    @Volatile var error = ""
    @Volatile var detail = ""
    @Volatile var outUri: String? = null
    @Volatile var added = System.currentTimeMillis()

    @Volatile var token = RunToken()
    private var worker: Thread? = null
    private var lastBytes = 0L
    private var lastTick = 0L

    protected val app get() = PyDMApp.instance
    val workDir: File get() = File(Storage.workRoot(app), id)

    val isActive: Boolean
        get() = status == Status.QUEUED || status == Status.PROBING ||
                status == Status.DOWNLOADING || status == Status.MERGING
    val isRunning: Boolean
        get() = status == Status.PROBING || status == Status.DOWNLOADING || status == Status.MERGING

    /** Blocking kaam. tok.stopped dekhte rahein. Ant mein complete()/fail() bulayein. */
    protected abstract fun run(tok: RunToken)
    protected open fun onPause() {}
    open fun onRemove() { workDir.deleteRecursively() }
    protected abstract fun extraToJson(o: JSONObject)
    abstract fun loadExtra(o: JSONObject)

    fun launch() {
        if (status != Status.QUEUED) return
        val tok = RunToken()
        token = tok
        status = Status.PROBING
        val prev = worker
        worker = Thread {
            try {
                prev?.join(8000)
                if (!tok.stopped) run(tok)
                if (!tok.stopped && isRunning) fail(tok, "Stopped unexpectedly")
            } catch (e: Throwable) {
                fail(tok, e.message ?: e.toString())
            } finally {
                Manager.save()
                Manager.pump()
            }
        }.also { it.isDaemon = true; it.name = "pydm-$kind"; it.start() }
    }

    fun pause() {
        if (!isActive) return
        token.stopped = true
        status = Status.PAUSED
        speed = 0.0
        try { onPause() } catch (_: Throwable) {}
        Manager.save()
    }

    fun resume() {
        if (status == Status.PAUSED || status == Status.ERROR) {
            error = ""
            status = Status.QUEUED
            Manager.pump()
        }
    }

    protected fun fail(tok: RunToken, msg: String) {
        if (tok.stopped) return
        error = msg
        status = Status.ERROR
        speed = 0.0
    }

    protected fun complete(tok: RunToken) {
        if (tok.stopped) return
        percent = 1f
        speed = 0.0
        status = Status.COMPLETED
    }

    /** Manager har second bulata hai. */
    fun tick(nowMs: Long) {
        if (lastTick == 0L) { lastTick = nowMs; lastBytes = downloaded; return }
        val dt = (nowMs - lastTick) / 1000.0
        if (dt < 0.5) return
        val inst = ((downloaded - lastBytes) / dt).coerceAtLeast(0.0)
        speed = if (status == Status.DOWNLOADING) (if (speed == 0.0) inst else speed * 0.6 + inst * 0.4) else 0.0
        lastBytes = downloaded
        lastTick = nowMs
    }

    fun view(): TaskView {
        val prog = when {
            status == Status.COMPLETED -> 1f
            percent >= 0 -> percent
            total > 0 -> (downloaded.toFloat() / total).coerceIn(0f, 1f)
            else -> 0f
        }
        val eta = if (speed > 1 && total > downloaded) ((total - downloaded) / speed).toLong() else -1L
        return TaskView(id, kind, title, status, prog, total, downloaded, speed, eta, error, detail, outUri)
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("kind", kind); o.put("id", id); o.put("title", title)
        o.put("status", if (status == Status.COMPLETED || status == Status.ERROR) status.name else Status.PAUSED.name)
        o.put("total", total); o.put("downloaded", downloaded); o.put("percent", percent.toDouble())
        o.put("error", error); o.put("detail", detail); o.put("outUri", outUri ?: ""); o.put("added", added)
        extraToJson(o)
        return o
    }

    fun loadBase(o: JSONObject) {
        title = o.optString("title")
        status = try { Status.valueOf(o.optString("status", "PAUSED")) } catch (_: Exception) { Status.PAUSED }
        total = o.optLong("total"); downloaded = o.optLong("downloaded")
        percent = o.optDouble("percent", -1.0).toFloat()
        error = o.optString("error"); detail = o.optString("detail")
        outUri = o.optString("outUri").ifBlank { null }
        added = o.optLong("added", System.currentTimeMillis())
        loadExtra(o)
    }
}

class SpeedLimiter {
    @Volatile var limitBps: Long = 0
    private var allowance = 0.0
    private var last = System.nanoTime()

    fun consume(n: Int) {
        val lim = limitBps
        if (lim <= 0) return
        var sleepMs = 0L
        synchronized(this) {
            val now = System.nanoTime()
            allowance += (now - last) / 1e9 * lim
            last = now
            if (allowance > lim) allowance = lim.toDouble()
            allowance -= n
            if (allowance < 0) sleepMs = (-allowance / lim * 1000).toLong()
        }
        if (sleepMs > 0) Thread.sleep(minOf(sleepMs, 2000))
    }
}
