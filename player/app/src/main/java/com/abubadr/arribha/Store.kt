package com.abubadr.arribha

import android.content.Context
import android.util.Base64

/** The linked console and the user's choices, kept on the TV. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("arribha", Context.MODE_PRIVATE)

    var accountId: String
        get() = p.getString("account_id", "") ?: ""
        set(v) = p.edit().putString("account_id", v.trim()).apply()

    // linked console
    var consoleHost: String
        get() = p.getString("host", "") ?: ""
        set(v) = p.edit().putString("host", v).apply()
    var consoleName: String
        get() = p.getString("name", "") ?: ""
        set(v) = p.edit().putString("name", v).apply()
    var consoleId: String
        get() = p.getString("host_id", "") ?: ""
        set(v) = p.edit().putString("host_id", v).apply()
    var isPS5: Boolean
        get() = p.getBoolean("ps5", true)
        set(v) = p.edit().putBoolean("ps5", v).apply()
    var registKey: ByteArray?
        get() = p.getString("regist_key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(v) = p.edit().putString("regist_key", v?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()
    var morning: ByteArray?
        get() = p.getString("rp_key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(v) = p.edit().putString("rp_key", v?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()

    val isLinked get() = registKey != null && morning != null && consoleHost.isNotEmpty()

    // translation choices
    var translateOn: Boolean
        get() = p.getBoolean("translate", true)
        set(v) = p.edit().putBoolean("translate", v).apply()
    var readFullScreen: Boolean
        get() = p.getBoolean("full", false)
        set(v) = p.edit().putBoolean("full", v).apply()
    var textSize: Int // 0 small, 1 medium, 2 large
        get() = p.getInt("size", 1)
        set(v) = p.edit().putInt("size", v).apply()

    /** stream size in kbit/s; 10000 keeps 1080p sharp while losing fewer pictures on Wi-Fi */
    var bitrate: Int
        get() = p.getInt("bitrate", 15000)
        set(v) = p.edit().putInt("bitrate", v).apply()

    var useHdr: Boolean
        get() = p.getBoolean("hdr", true)
        set(v) = p.edit().putBoolean("hdr", v).apply()

    var welcomed: Boolean
        get() = p.getBoolean("welcomed", false)
        set(v) = p.edit().putBoolean("welcomed", v).apply()

    // device check result (filled on first run)
    var tier: Int
        get() = p.getInt("tier", -1)
        set(v) = p.edit().putInt("tier", v).apply()
    var useHevc: Boolean
        get() = p.getBoolean("hevc", true)
        set(v) = p.edit().putBoolean("hevc", v).apply()
}
