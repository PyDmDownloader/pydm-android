package com.pydm.android

import android.content.Context

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pydm", Context.MODE_PRIVATE)

    var connections: Int
        get() = sp.getInt("connections", 8)
        set(v) = sp.edit().putInt("connections", v.coerceIn(1, 16)).apply()

    var maxConcurrent: Int
        get() = sp.getInt("max_concurrent", 3)
        set(v) = sp.edit().putInt("max_concurrent", v.coerceIn(1, 10)).apply()

    /** KB/s; 0 = unlimited */
    var speedLimitKbps: Int
        get() = sp.getInt("speed_limit", 0)
        set(v) = sp.edit().putInt("speed_limit", v.coerceAtLeast(0)).apply()

    var schedEnabled: Boolean
        get() = sp.getBoolean("sched_enabled", false)
        set(v) = sp.edit().putBoolean("sched_enabled", v).apply()

    var schedStart: String
        get() = sp.getString("sched_start", "02:00") ?: "02:00"
        set(v) = sp.edit().putString("sched_start", v).apply()

    var schedStop: String
        get() = sp.getString("sched_stop", "06:00") ?: "06:00"
        set(v) = sp.edit().putString("sched_stop", v).apply()

    var codec: String   // "avc" ya "best"
        get() = sp.getString("codec", "avc") ?: "avc"
        set(v) = sp.edit().putString("codec", v).apply()
}
