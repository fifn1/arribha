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
 *  - a line is looked up by its fingerprint on our server, one sentence at a time (the library is never listed,
 *    so nobody can copy it); answers are kept on the TV, so a line seen once is instant from then on;
 *  - lines that have no professional translation yet are sent (in the background, never blocking play)
 *    to the intake; a free AI translates them within about an hour, and from then on every player gets them.
 */
object Library {
    private const val SERVER = "https://arribha-intake.d-fifn1.workers.dev"
    @Volatile private var intake = "$SERVER/submit"
    @Volatile private var sending = true

    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "arribha-net").apply { priority = Thread.MIN_PRIORITY } }
    private val known = HashMap<String, String>()          // fingerprint -> translation, kept on the TV
    private val missing = HashMap<String, Long>()          // fingerprint -> when we last heard "not yet"
    @Volatile private var offlineUntil = 0L
    private val queue = ArrayList<Pair<String, String>>()
    private val queued = HashSet<String>()
    private var lastSend = 0L
    private lateinit var file: File

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, if (L.isTr) "lib-tr.json" else "lib-ar.json")
        runCatching { val o = JSONObject(file.readText()); o.keys().forEach { known[it] = o.getString(it) } }
        val prefs = ctx.getSharedPreferences("arribha", Context.MODE_PRIVATE)
        if (!prefs.contains("sample_roll")) prefs.edit().putFloat("sample_roll", Math.random().toFloat()).apply()
        val roll = prefs.getFloat("sample_roll", 0f)
        net.execute {
            runCatching {
                val c = JSONObject(get("$SERVER/config", 4000))
                intake = c.optString("intake", intake)
                sending = roll < c.optDouble("sample", 1.0)         // only a sample of players send new lines
                Log.i("Arribha", "library ready (${known.size} lines on TV), sending new lines: $sending")
            }
        }
    }

    /** Professional translation from the shared library, or null. Asks the server once per new sentence (short wait). */
    fun find(hash: String): String? {
        synchronized(known) { known[hash]?.let { return it } }
        val now = System.currentTimeMillis()
        synchronized(missing) { missing[hash]?.let { if (now - it < 2 * 3600_000L) return null } }
        if (now < offlineUntil || !::file.isInitialized) return null
        val t = runCatching {
            JSONObject(get("$SERVER/lib?h=$hash" + if (L.isTr) "&l=tr" else "", 700)).optString("t", "")
        }.getOrElse { offlineUntil = now + 60_000; return null }      // no internet: don't wait again for a minute
        if (t.isEmpty()) { synchronized(missing) { missing[hash] = now }; return null }
        synchronized(known) { known[hash] = t }
        net.execute { runCatching { synchronized(known) { file.writeText(JSONObject(known as Map<*, *>).toString()) } } }
        return t
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
                    // drop lines that got a professional translation meanwhile
                    batch.removeAll { (h, _) -> find(h) != null }
                    if (batch.isEmpty()) return@runCatching
                    val arr = JSONArray()
                    batch.forEach { (h, e) -> arr.put(JSONObject().put("h", h).put("en", e)) }
                    post(intake, JSONObject().put("game", game ?: "").put("lang", L.lang).put("lines", arr).toString())
                }
            }
        }
    }

    private fun get(url: String, ms: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = ms; c.readTimeout = ms
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
