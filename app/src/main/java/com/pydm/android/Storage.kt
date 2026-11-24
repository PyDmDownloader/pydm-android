package com.pydm.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

object Storage {
    /** App ka andarooni kaam ka folder (parts, yt-dlp temp files). */
    fun workRoot(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "work").apply { mkdirs() }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mkv" -> "video/x-matroska"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else -> "application/octet-stream"
            }
    }

    /** File ko public Downloads/PyDM/<subDir> mein copy karo. Uri string return. */
    fun publish(ctx: Context, src: File, subDir: String = "", displayName: String = src.name): String {
        val resolver = ctx.contentResolver
        val rel = "Download/PyDM" + (if (subDir.isBlank()) "" else "/" + subDir.trim('/'))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(displayName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create file in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Could not open output")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    fun publishBytes(ctx: Context, bytes: ByteArray, subDir: String, displayName: String): String {
        val tmp = File(ctx.cacheDir, "tmp_" + System.nanoTime())
        try {
            tmp.writeBytes(bytes)
            return publish(ctx, tmp, subDir, displayName)
        } finally { tmp.delete() }
    }

    fun open(ctx: Context, uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            val type = ctx.contentResolver.getType(uri) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {}
    }
}
