package com.pydm.android

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

object Fmt {
    fun bytes(n: Long): String {
        if (n <= 0) return "0 B"
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${n} B" else String.format(Locale.US, "%.1f %s", v, u[i])
    }

    fun speed(bps: Double): String = if (bps <= 0) "" else bytes(bps.toLong()) + "/s"

    fun eta(sec: Long): String {
        if (sec < 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun views(n: Long?): String = if (n == null) "?" else String.format(Locale.US, "%,d", n)

    fun safeName(name: String?): String {
        var s = (name ?: "").replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), "").trim()
        s = s.replace(Regex("\\s+"), " ")
        if (s.length > 120) s = s.substring(0, 120).trim()
        return s.ifBlank { "youtube_video" }
    }

    fun duration(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 PyDM/1.0"

    fun open(url: String, referrer: String = "", range: String? = null,
             timeoutMs: Int = 30000, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = method
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Encoding", "identity")
        if (referrer.isNotBlank()) c.setRequestProperty("Referer", referrer)
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    data class Probe(val total: Long?, val ranges: Boolean, val filename: String)

    fun probe(url: String, referrer: String): Probe {
        try {
            val c = open(url, referrer, "bytes=0-0", 25000)
            try {
                val code = c.responseCode
                if (code == 206) {
                    val cr = c.getHeaderField("Content-Range") ?: ""
                    val total = cr.substringAfterLast('/').toLongOrNull()
                    return Probe(total, true, guessFilename(url, c))
                }
                if (code in 200..299) {
                    val len = c.contentLengthLong
                    return Probe(if (len > 0) len else null, false, guessFilename(url, c))
                }
            } finally { c.disconnect() }
        } catch (_: Exception) {}
        // HEAD fallback
        try {
            val c = open(url, referrer, null, 25000, "HEAD")
            try {
                if (c.responseCode in 200..299) {
                    val len = c.contentLengthLong
                    val ar = (c.getHeaderField("Accept-Ranges") ?: "").contains("bytes", true)
                    return Probe(if (len > 0) len else null, ar && len > 0, guessFilename(url, c))
                }
            } finally { c.disconnect() }
        } catch (_: Exception) {}
        return Probe(null, false, guessFilename(url, null))
    }

    fun guessFilename(url: String, c: HttpURLConnection?): String {
        val cd = c?.getHeaderField("Content-Disposition")
        if (cd != null) {
            Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
                try { return Fmt.safeName(URLDecoder.decode(it.groupValues[1].trim(), "UTF-8")) } catch (_: Exception) {}
            }
            Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let {
                return Fmt.safeName(it.groupValues[1].trim())
            }
        }
        val path = try { URL(url).path } catch (_: Exception) { "" }
        val last = path.substringAfterLast('/')
        val dec = try { URLDecoder.decode(last, "UTF-8") } catch (_: Exception) { last }
        return if (dec.isBlank()) "download" else Fmt.safeName(dec)
    }

    /** Chhoti cheezein (thumbnail, caption file) ke liye: poora body bytes mein. */
    fun getBytes(url: String, timeoutMs: Int = 25000): ByteArray {
        val c = open(url, "", null, timeoutMs)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}
