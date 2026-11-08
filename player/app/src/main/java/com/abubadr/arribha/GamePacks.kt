package com.abubadr.arribha

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Professional Arabic for each game's own lines, downloaded only for the game being played.
 *
 *  - The app ships a tiny index (assets/packs/index.json): per game, fingerprints of ~30 common lines.
 *  - When lines on screen match a game's fingerprints, that game's pack is downloaded once (GitHub, free)
 *    and kept on the TV; packs not used for 60 days are deleted. App size stays small.
 *  - Packs store a fingerprint (short hash) of each English line, not the English text itself.
 *    Pack: {"id","game","version","names":{"Mickey":"ميكي"},"lines":{"<hash>":"Arabic"}}
 */
object GamePacks {
    /** Where packs are published. Set to the GitHub repository once it exists. */
    var baseUrl = "https://raw.githubusercontent.com/fifn1/arribha-packs/main/"

    private class Game(val id: String, val name: String, val fp: Set<String>, val file: String)
    private class Pack(val id: String, val game: String, val lines: Map<String, String>, val names: Map<String, String>)

    private val games = ArrayList<Game>()
    private val loaded = HashMap<String, Pack>()
    private val hits = HashMap<String, Int>()
    private val downloading = HashSet<String>()
    private var active: Pack? = null
    private lateinit var dir: File
    var activeGame: String? = null; private set

    fun load(ctx: Context) {
        if (games.isNotEmpty()) return
        dir = File(ctx.filesDir, "packs").apply { mkdirs() }
        val cached = File(dir, "index.json")
        val text = if (cached.exists()) cached.readText() else ctx.assets.open("packs/index.json").bufferedReader().readText()
        readIndex(text)
        // packs bundled for testing + packs already downloaded
        ctx.assets.list("packs")?.filter { it != "index.json" }?.forEach { f ->
            runCatching { addPack(JSONObject(ctx.assets.open("packs/$f").bufferedReader().readText())) }
        }
        dir.listFiles()?.filter { it.name != "index.json" }?.forEach { f ->
            if (System.currentTimeMillis() - f.lastModified() > 60L * 24 * 3600 * 1000) f.delete()   // not played for 60 days
            else runCatching { addPack(JSONObject(f.readText())) }
        }
        Thread { runCatching { val t = get(baseUrl + "index.json"); readIndex(t); cached.writeText(t) } }.start()
        Log.i("Arribha", "games known: ${games.size}, packs on TV: ${loaded.keys}")
    }

    private fun readIndex(text: String) {
        val arr = JSONArray(text)
        val list = ArrayList<Game>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fp = HashSet<String>()
            o.getJSONArray("fp").let { a -> for (k in 0 until a.length()) fp.add(a.getString(k)) }
            list.add(Game(o.getString("id"), o.getString("game"), fp, o.getString("file")))
        }
        synchronized(games) { games.clear(); games.addAll(list) }
    }

    private fun addPack(j: JSONObject) {
        val lines = HashMap<String, String>()
        j.getJSONObject("lines").let { o -> o.keys().forEach { lines[it] = o.getString(it) } }
        val names = HashMap<String, String>()
        j.optJSONObject("names")?.let { o -> o.keys().forEach { names[it.lowercase()] = o.getString(it) } }
        loaded[j.getString("id")] = Pack(j.getString("id"), j.getString("game"), lines, names)
    }

    /** letters and digits only, lower case, then a short hash: the same for the app and the pack maker */
    fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    fun hash(s: String): String = MessageDigest.getInstance("SHA-1").digest(key(s).toByteArray())
        .take(6).joinToString("") { "%02x".format(it) }

    /** Ready translation of a line, or null. Also recognises the game and fetches its pack. */
    fun find(en: String): String? {
        if (key(en).length < 2) return null
        val h = hash(en)
        val order = listOfNotNull(active) + loaded.values.filter { it !== active }
        // only real sentences (3+ words) count as evidence of which game it is; "Back" or "Continue" are in every game
        val evidence = key(en).split(" ").size >= 3
        for (p in order) p.lines[h]?.let { if (evidence) noteHit(p.id); return it }
        if (evidence) synchronized(games) { games.firstOrNull { h in it.fp } }?.let { noteHit(it.id) }
        return null
    }

    fun name(n: String): String? = active?.names?.get(n.lowercase()) ?: loaded.values.firstNotNullOfOrNull { it.names[n.lowercase()] }

    private fun noteHit(id: String) {
        val n = (hits[id] ?: 0) + 1
        hits[id] = n
        if (n < 2) return
        val p = loaded[id]
        if (p != null) {
            if (active !== p) { active = p; activeGame = p.game; Log.i("Arribha", "game recognised: ${p.game}") }
            return
        }
        val g = synchronized(games) { games.firstOrNull { it.id == id } } ?: return
        if (!downloading.add(id)) return
        Thread {
            runCatching {
                val t = get(baseUrl + g.file)
                File(dir, "$id.json").writeText(t)
                addPack(JSONObject(t))
                Log.i("Arribha", "pack downloaded: ${g.name}")
            }.onFailure { Log.w("Arribha", "pack download failed: ${g.name}: $it"); downloading.remove(id) }
        }.start()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 10000
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
