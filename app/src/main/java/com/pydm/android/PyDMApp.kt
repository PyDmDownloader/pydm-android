package com.pydm.android

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class PyDMApp : Application() {
    @Volatile var ytReady = false
    @Volatile var ytInitError: String = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        Manager.init(this)
        // yt-dlp + ffmpeg ko background mein tayyar karo (pehli baar kuch seconds lagte hain)
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                ytReady = true
            } catch (e: Throwable) {
                ytInitError = e.message ?: e.toString()
                Log.e("PyDM", "yt-dlp init failed", e)
            }
        }.start()
    }

    companion object {
        lateinit var instance: PyDMApp
    }
}
