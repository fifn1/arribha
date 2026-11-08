package com.abubadr.arribha

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub (fifn1/arribha releases) once per start; if a newer version exists, offers it,
 * downloads the signed APK and opens Android's installer. Skipped when installed from Google Play.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/fifn1/arribha/releases/latest"
    private var checked = false

    fun check(a: Activity) {
        if (checked) return
        checked = true
        val installer = runCatching { a.packageManager.getInstallerPackageName(a.packageName) }.getOrNull()
        if (installer == "com.android.vending") return   // Play Store updates the app itself
        val current = a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: return
        Thread {
            runCatching {
                val j = JSONObject(get(LATEST))
                val latest = j.getString("tag_name").removePrefix("v")
                if (!newer(latest, current)) return@runCatching
                val assets = j.getJSONArray("assets")
                // the APK made for this TV's processor (smaller); otherwise the universal one
                val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk") }
                val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
                val url = (apks.firstOrNull { abi != null && it.getString("name").contains(abi) }
                    ?: apks.firstOrNull { it.getString("name").contains("universal") } ?: apks.firstOrNull())
                    ?.getString("browser_download_url") ?: return@runCatching
                a.runOnUiThread {
                    if (a.isFinishing) return@runOnUiThread
                    AlertDialog.Builder(a)
                        .setTitle("تحديث جديد لعرّبها ($latest)")
                        .setMessage(j.optString("body").take(400).ifBlank { "تحسينات وإصلاحات." })
                        .setPositiveButton("تحديث الآن") { _, _ -> download(a, url) }
                        .setNegativeButton("لاحقًا", null).show()
                }
            }.onFailure { Log.w("Arribha", "update check: $it") }
        }.start()
    }

    private fun download(a: Activity, url: String) {
        Thread {
            runCatching {
                val dir = File(a.cacheDir, "updates").apply { mkdirs() }
                val f = File(dir, "arribha.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
                val uri = FileProvider.getUriForFile(a, a.packageName + ".files", f)
                a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w("Arribha", "update download: $it") }
        }.start()
    }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }; val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 }); if (d != 0) return d > 0 }
        return false
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "arribha-app/1"); c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 5000; c.readTimeout = 8000
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
