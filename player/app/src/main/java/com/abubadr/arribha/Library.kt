package com.abubadr.arribha

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The shared professional library, filled automatically for every game:
 *  - looks a line up by its fingerprint in small files (lib/<2 hex>.json) served free by jsDelivr, cached on the TV;
 *  - lines that have no professional translation yet are sent (in the background, never blocking play)
 *    to the intake; a free AI translates them within about an hour, and from then on every player gets them.
 */
object Library {
    private const val CONFIG = "https://cdn.jsdelivr.net/gh/fifn1/arribha-packs@main/config.json"
    @Volatile private var intake = "https://arribha-intake.d-fifn1.workers.dev/submit"
    @Volatile private var lib = "https://cdn.jsdelivr.net/gh/fifn1/arribha-packs@main/lib/"
    @Volatile private var sending = true

    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "arribha-net").apply { priority = Thread.MIN_PRIORITY } }
    private val shards = HashMap<String, Map<String, String>>()
    private val asked = HashSet<String>()
    private val queue = ArrayList<Pair<String, String>>()
    private val queued = HashSet<String>()
    private var lastSend = 0L
    private lateinit var dir: File

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "lib").apply { mkdirs() }
        val prefs = ctx.getSharedPreferences("arribha", Context.MODE_PRIVATE)
        if (!prefs.contains("sample_roll")) prefs.edit().putFloat("sample_roll", Math.random().toFloat()).apply()
        val roll = prefs.getFloat("sample_roll", 0f)
        net.execute {
            runCatching {
                val c = JSONObject(get(CONFIG))
                intake = c.optString("intake", intake); lib = c.optString("lib", lib)
                sending = roll < c.optDouble("sample", 1.0)         // only a sample of players send new lines
                Log.i("Arribha", "library ready, sending new lines: $sending")
                // download the whole library now, so known lines are instant from the first time they appear
                val idx = org.json.JSONArray(get("${lib}index.json"))
                for (i in 0 until idx.length()) {
                    val sh = idx.getString(i)
                    val f = File(dir, "$sh.json")
                    if (f.exists() && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L) { runCatching { load(sh, f.readText()) }; continue }
                    runCatching { val txt = get("$lib$sh.json"); f.writeText(txt); load(sh, txt) }
                }
                Log.i("Arribha", "library prefetched: ${idx.length()} files")
            }
        }
    }

    /** Professional translation from the shared library, or null (then fetched in the background for next time). */
    fun find(hash: String): String? {
        val sh = hash.take(2)
        synchronized(shards) { shards[sh]?.let { return it[hash] } }
        val f = File(dir, "$sh.json")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L
        if (f.exists()) runCatching { load(sh, f.readText()) }
        if (!fresh && synchronized(asked) { asked.add(sh) }) net.execute {
            runCatching {
                val t = get("$lib$sh.json")
                f.writeText(t); load(sh, t)
            }.onFailure { f.writeText(f.takeIf { it.exists() }?.readText() ?: "{}") }   // none yet: try again in 6 h
        }
        synchronized(shards) { return shards[sh]?.get(hash) }
    }

    private fun load(sh: String, text: String) {
        val o = JSONObject(text)
        val m = HashMap<String, String>()
        o.keys().forEach { m[it] = o.getString(it) }
        synchronized(shards) { shards[sh] = m }
    }

    /** A line we had to machine-translate: send it so it gets a professional translation for everyone. */
    fun submit(hash: String, en: String, game: String?) {
        if (!sending || en.split(" ").size < 3) return
        // misreads ("3to", "Ppwered", "tlnrel") would teach the library wrong lines: send clean text only
        val words = en.split(" ").map { it.trim(',', '.', '!', '?', ':', ';', '"', '\'') }.filter { it.isNotEmpty() && !it.startsWith("ZZ") }
        if (words.any { w -> w.any(Char::isDigit) && w.any(Char::isLetter) }) return
        if (words.count { w -> w.length > 2 && w.drop(1).any(Char::isUpperCase) && !w.all(Char::isUpperCase) } > 0) return
        if (words.count { w -> w.length >= 4 && w.none { it.lowercaseChar() in "aeiouy" } } > 0) return
        // only whole sentences: half lines cut by the screen make weak translations
        if (!Regex("""[.!?…"')]\s*$|ZZ\d+\s*$""").containsMatchIn(en.trim())) return
        synchronized(queue) {
            if (!queued.add(hash)) return
            queue.add(hash to en)
            val now = System.currentTimeMillis()
            if (queue.size < 25 && now - lastSend < 60_000) return
            lastSend = now
            val batch = ArrayList(queue); queue.clear()
            net.execute {
                runCatching {
                    // by now the library files are downloaded: drop lines that already have a professional translation
                    batch.removeAll { (h, _) -> find(h) != null }
                    if (batch.isEmpty()) return@runCatching
                    val arr = JSONArray()
                    batch.forEach { (h, e) -> arr.put(JSONObject().put("h", h).put("en", e)) }
                    post(intake, JSONObject().put("game", game ?: "").put("lines", arr).toString())
                }
            }
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "arribha-app/1")
        if (c.responseCode != 200) throw IllegalStateException("http ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun post(url: String, body: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 4000; c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "arribha-app/1")
        c.outputStream.use { it.write(body.toByteArray()) }
        c.responseCode
    }
}
