package com.abubadr.arribha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads English text from the game picture and shows Arabic in its place.
 *
 * Fast and light, so the game stays smooth:
 *  - every ~0.2 s it copies one small still picture of the reading area and takes a ~3 ms "quick look"
 *    (are there letters? did they change?) — no continuous screen copy;
 *  - only when new text appears does it read it, and only the part of the picture that has text;
 *  - reading runs on a lowest-priority thread, at most about half of one core, and a guard backs off if the TV gets busy.
 *
 * Quality: hand-written Arabic for common game lines (Phrasebook), button icons copied from the game into the
 * Arabic sentence, character names kept the same every time, and colours taken from the game.
 */
class TranslateEngine(
    private val surfaceView: SurfaceView,
    private val overlayHost: FrameLayout,
    private val store: Store,
    tier: Int,
) {
    companion object { const val TAG = "Arribha" }

    private val lookEvery = when (tier) { 2 -> 150L; 1 -> 200L; else -> 400L }
    private val minReadGap = when (tier) { 2 -> 300L; 1 -> 450L; else -> 1000L }

    private val ui = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("arribha-read", Process.THREAD_PRIORITY_LOWEST).apply { start() }
    private val work = Handler(thread.looper)
    private val ctx: Context = overlayHost.context

    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.ARABIC).build())
    @Volatile private var modelReady = false
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 2000
    }
    private val names = ctx.getSharedPreferences("arribha_names", Context.MODE_PRIVATE)
    private val nameSeen = HashMap<String, Int>()
    private val speakers = HashSet<String>()

    @Volatile var enabled = true
        set(v) { field = v; if (!v) { hide(); shownSig = "" } }
    @Volatile var inGame = true
    @Volatile private var running = false

    private val capW = 800
    private var bitmap: Bitmap? = null

    private var shownSig = ""
    private var lastGrid: IntArray? = null
    private var lastReadAt = 0L
    private var lastReadMs = 0L
    private var emptyLooks = 0
    private var candGrid: IntArray? = null
    private var candSince = 0L

    // ---- guard ----
    @Volatile var pausedByGuard = false; private set
    private var ocrEma = 0.0
    private var ocrBase = 0.0
    private var ocrSamples = 0
    private var slowFrames = 0
    private var frames = 0
    private var lastFrameNs = 0L
    private var guardUntil = 0L
    private var extraDelay = 0L
    var onGuardChanged: ((Boolean) -> Unit)? = null

    private val frameWatcher = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            if (lastFrameNs != 0L) { frames++; if (t - lastFrameNs > 25_000_000L) slowFrames++ }
            lastFrameNs = t
            if (frames >= 120) {
                val slowShare = slowFrames.toDouble() / frames
                if (slowShare > 0.08) backOff("frame pacing ${"%.0f".format(slowShare * 100)}% slow")
                else if (slowShare < 0.02 && extraDelay > 0) extraDelay = max(0, extraDelay - 250)
                frames = 0; slowFrames = 0
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        GamePacks.load(ctx)
        Library.init(ctx)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { modelReady = true; Log.i(TAG, "model ready") }
            .addOnFailureListener { Log.e(TAG, "model download failed", it) }
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameWatcher)
        work.postDelayed(::tick, 2000)
    }

    fun stop() {
        running = false
        work.removeCallbacksAndMessages(null)
        hide()
    }

    fun release() {
        stop()
        ocr.close()
        translator.close()
        thread.quitSafely()
    }

    private fun backOff(why: String) {
        extraDelay = min(3000, extraDelay + 500)
        if (extraDelay >= 2500) {
            guardUntil = SystemClock.uptimeMillis() + 10_000
            if (!pausedByGuard) { pausedByGuard = true; ui.post { onGuardChanged?.invoke(true) } }
        }
        Log.w(TAG, "guard: $why -> extra delay $extraDelay ms")
    }

    // ------------------------------------------------------------------ loop

    private fun tick() {
        if (!running) return
        var next = lookEvery
        try {
            next = if (!enabled || !inGame) 500 else readOnce()
        } catch (t: Throwable) {
            Log.w(TAG, "read failed", t)
        }
        if (running) work.postDelayed(::tick, next)
    }

    /** @return ms until the next look */
    private fun readOnce(): Long {
        val now = SystemClock.uptimeMillis()
        if (pausedByGuard) {
            if (now < guardUntil) return 1000
            pausedByGuard = false
            extraDelay = 1000
            ui.post { onGuardChanged?.invoke(false) }
        }
        val holder = surfaceView.holder
        val frame = holder.surfaceFrame
        if (!holder.surface.isValid || frame.width() <= 0) return 1000

        val top = if (store.readFullScreen) 0 else frame.height() * 3 / 5
        val src = Rect(0, top, frame.width(), frame.height())
        val capH = max(1, capW * src.height() / src.width())
        var bm = bitmap
        if (bm == null || bm.height != capH) { bm?.recycle(); bm = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888); bitmap = bm }
        if (!copyFrame(src, bm!!)) return 1000

        // 1) quick look: any letters? changed since last read?
        val look = quickLook(bm)
        if (look.score < MIN_TEXT_SCORE || look.area == null) {
            // text gone: remove the Arabic only after two empty looks in a row (no flicker on one bad frame)
            if (++emptyLooks >= 2 && shownSig.isNotEmpty()) { shownSig = ""; hide() }
            lastGrid = null; candGrid = null
            return lookEvery + extraDelay
        }
        emptyLooks = 0
        val prev = lastGrid
        if (prev != null && gridChange(prev, look.grid) < 0.12) { candGrid = null; return lookEvery + extraDelay }
        // new text: wait until it stops changing (typing effect, fading in), so only the final line is shown
        val cand = candGrid
        if (cand == null || gridChange(cand, look.grid) >= 0.08) {
            if (cand == null) candSince = now
            candGrid = look.grid
            if (now - candSince < 1200) return lookEvery
        }
        candGrid = null
        // 2) keep reading light: wait a little if we read very recently
        val gap = max(minReadGap, lastReadMs) + extraDelay
        if (now - lastReadAt < gap) return gap - (now - lastReadAt)
        lastGrid = look.grid
        lastReadAt = now

        // 3) read only the part of the picture with text
        val area = look.area
        val crop = Bitmap.createBitmap(bm, area.left, area.top, area.width(), area.height())
        val t0 = SystemClock.uptimeMillis()
        val result = Tasks.await(ocr.process(InputImage.fromBitmap(crop, 0)), 5, TimeUnit.SECONDS)
        val ocrMs = SystemClock.uptimeMillis() - t0
        lastReadMs = ocrMs
        watchReadTime(ocrMs)

        val sx = surfaceView.width / capW.toFloat()
        val sy = (surfaceView.height * src.height() / frame.height().toFloat()) / capH
        val offY = surfaceView.height * top / frame.height().toFloat()
        val pieces = pick(result, bm, area.left, area.top, sx, sy, offY)
        crop.recycle()
        val sig = pieces.joinToString("|") { it.text.lowercase().replace(Regex("[^a-z0-9 ]"), "") }
        if (sig.isEmpty()) {
            if (shownSig.isNotEmpty()) { shownSig = ""; hide() }
            return lookEvery
        }
        if (shownSig.isNotEmpty() && sig.startsWith(shownSig.take(max(8, shownSig.length - 4))) && sig.length > shownSig.length)
            Log.d(TAG, "line grew: $sig")
        if (similar(sig, shownSig) >= 0.85) return lookEvery
        if (!modelReady) return 500

        // 4) translate and show
        val t1 = SystemClock.uptimeMillis()
        val shown = pieces.map { p ->
            Shown(p.rect, p.speaker?.let { nameAr(it) }, p.speakerColor, translatePiece(p.text), p.icons, p.textColor, p.bgColor, p.lineH, p.patch)
        }
        shownSig = sig
        ui.post { show(shown) }
        Log.i(TAG, "read ${ocrMs}ms + translate ${SystemClock.uptimeMillis() - t1}ms (area ${area.width()}x${area.height()}): " +
                pieces.joinToString(" | ") { it.text } + "  =>  " + shown.joinToString(" | ") { it.ar })
        return lookEvery
    }

    // ------------------------------------------------------------------ quick look

    private class Look(val score: Float, val grid: IntArray, val area: Rect?)
    private val MIN_TEXT_SCORE = 0.004f
    private var px = IntArray(0)
    private var looks = 0
    private val GW = 24
    private val GH = 12

    /** Sharp bright/dark edges (what letters look like) on every 2nd pixel, counted on a 24x12 grid. */
    private fun quickLook(bm: Bitmap): Look {
        val w = bm.width; val h = bm.height
        if (px.size != w * h) px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        val grid = IntArray(GW * GH)
        var edges = 0; var samples = 0
        // brightness of the brightest text-like pixels: HDR pictures come out dimmer, so thresholds follow the picture
        var maxL = 0
        var yy = 0
        while (yy < h) { var xx = 0; while (xx < w) { maxL = max(maxL, lum(px[yy * w + xx])); xx += 4 }; yy += 4 }
        val scale = (maxL.coerceAtLeast(110)) / 255f
        val needDiff = (110 * scale).toInt(); val needBright = (170 * scale).toInt()
        if (++looks % 100 == 1) Log.d(TAG, "look: brightest $maxL")
        var y = 0
        while (y < h) {
            var x = 0
            val row = y * w
            while (x < w - 2) {
                val la = lum(px[row + x]); val lb = lum(px[row + x + 2])
                if (abs(la - lb) > needDiff && max(la, lb) > needBright) { edges++; grid[(y * GH / h) * GW + (x * GW / w)]++ }
                samples++
                x += 2
            }
            y += 2
        }
        // area with text = cells with several edges, plus one cell of margin
        var l = GW; var t = GH; var r = -1; var b = -1
        for (gy in 0 until GH) for (gx in 0 until GW) if (grid[gy * GW + gx] >= 4) {
            l = min(l, gx); t = min(t, gy); r = max(r, gx); b = max(b, gy)
        }
        val area = if (r < 0) null else Rect(
            max(0, (l - 1) * w / GW), max(0, (t - 1) * h / GH),
            min(w, (r + 2) * w / GW), min(h, (b + 2) * h / GH))
        return Look(edges.toFloat() / samples, grid, area)
    }

    private fun lum(c: Int) = ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10

    private fun gridChange(a: IntArray, b: IntArray): Double {
        var diff = 0; var total = 0
        for (i in a.indices) { diff += abs(a[i] - b[i]); total += a[i] + b[i] }
        return if (total == 0) 0.0 else diff.toDouble() / total
    }

    private fun copyFrame(src: Rect, dst: Bitmap): Boolean {
        val lock = Object()
        var ok = false
        var done = false
        val listener = PixelCopy.OnPixelCopyFinishedListener { r ->
            synchronized(lock) { ok = r == PixelCopy.SUCCESS; done = true; lock.notifyAll() }
        }
        if (Build.VERSION.SDK_INT >= 26) PixelCopy.request(surfaceView, src, dst, listener, ui)
        else PixelCopy.request(surfaceView, dst, listener, ui)
        synchronized(lock) {
            val end = SystemClock.uptimeMillis() + 1000
            while (!done && SystemClock.uptimeMillis() < end) lock.wait(50)
        }
        return ok
    }

    private fun watchReadTime(ms: Long) {
        ocrEma = if (ocrSamples == 0) ms.toDouble() else ocrEma * 0.7 + ms * 0.3
        ocrSamples++
        if (ocrSamples == 5) ocrBase = ocrEma
        if (ocrSamples > 5 && ocrEma > ocrBase * 2.5 && ocrEma > 400) backOff("read time ${ocrEma.toInt()} ms")
    }

    // ------------------------------------------------------------------ choosing text, icons and colours

    private class Piece(
        val text: String, val rect: Rect, val lineH: Float, val icons: List<Bitmap>,
        val textColor: Int, val bgColor: Int, var speaker: String? = null, var speakerColor: Int = Color.rgb(250, 205, 95),
        var patch: Bitmap? = null,
    )

    private val realShort = setOf("a", "i", "an", "to", "of", "in", "is", "it", "on", "or", "at", "be", "do", "go", "my",
        "no", "so", "up", "we", "us", "me", "he", "oh", "ok", "hi", "am", "as", "by", "if", "ah", "uh", "hm")

    private fun pick(result: Text, bm: Bitmap, ox: Int, oy: Int, sx: Float, sy: Float, offY: Float): List<Piece> {
        val out = ArrayList<Piece>()
        var pendingName: String? = null
        var pendingRect: Rect? = null
        var pendingColor = Color.rgb(250, 205, 95)
        val groups = ArrayList<List<Text.Line>>()
        for (blk in result.textBlocks) {
            var cur = ArrayList<Text.Line>()
            for (ln in blk.lines) {
                val prevLn = cur.lastOrNull()
                if (prevLn != null && differentStyle(prevLn, ln)) { groups.add(cur); cur = ArrayList() }
                cur.add(ln)
            }
            if (cur.isNotEmpty()) groups.add(cur)
        }
        for (g in groups) {
            val r0 = Rect(g[0].boundingBox ?: continue).also { r -> g.forEach { l -> l.boundingBox?.let { r.union(it) } } }
            val br = Rect(r0.left + ox, r0.top + oy, r0.right + ox, r0.bottom + oy)          // picture pixels
            val sr = Rect((br.left * sx).toInt(), (br.top * sy + offY).toInt(), (br.right * sx).toInt(), (br.bottom * sy + offY).toInt())

            // words + icons, in reading order
            val icons = ArrayList<Bitmap>()
            val words = ArrayList<String>()
            for (line in g) {
                val lb = line.boundingBox ?: continue
                val lh = lb.height().toFloat()
                var prevRight = -1
                for (e in line.elements.sortedBy { it.boundingBox?.left ?: 0 }) {
                    val eb0 = e.boundingBox ?: continue
                    val eb = Rect(eb0.left + ox, eb0.top + oy, eb0.right + ox, eb0.bottom + oy)
                    // a wide empty gap inside a line is usually an icon the reader skipped
                    if (prevRight >= 0 && eb.left - prevRight > lh * 1.4f) {
                        val size = (lh * 1.25f).toInt()
                        val cx = (prevRight + eb.left) / 2
                        cropIcon(bm, Rect(cx - size / 2, eb.centerY() - size / 2, cx + size / 2, eb.centerY() + size / 2))
                            ?.let { icons.add(it); words.add("ZZ${icons.size - 1}") }
                    }
                    prevRight = eb.right
                    val txt = e.text
                    val core = txt.trimEnd('.', ',', '!', '?', ':', ';')
                    val punct = txt.substring(core.length)
                    val merged = Regex("^([XDBOA©@®])([A-Z][a-z]{2,}.*)$").find(core)
                    when {
                        isIconText(core, eb, lh) -> {
                            cropIcon(bm, padded(eb, 0.15f))?.let { icons.add(it); words.add("ZZ${icons.size - 1}$punct") } ?: words.add(txt)
                        }
                        merged != null && eb.width() > lh * 2 -> {   // "XContinue" = icon glued to a word
                            val iconR = Rect(eb.left, eb.top, eb.left + eb.height(), eb.bottom)
                            cropIcon(bm, padded(iconR, 0.15f))?.let { icons.add(it); words.add("ZZ${icons.size - 1}") }
                            words.add(merged.groupValues[2] + punct)
                        }
                        else -> words.add(txt)
                    }
                }
            }
            // a speaker name in capitals glued before the line ("GUS Missed me!") becomes the speaker
            var gluedSpeaker: String? = null
            Regex("^([A-Z]{2,}(?: [A-Z]{2,})?) (?=[A-Z][a-z])").find(words.joinToString(" "))?.let { m ->
                gluedSpeaker = m.groupValues[1].lowercase().replaceFirstChar { it.uppercase() }
                repeat(m.groupValues[1].split(" ").size) { if (words.isNotEmpty()) words.removeAt(0) }
            }
            var t = clean(words.joinToString(" "))
            t = t.replace(Regex("(?i)\\bzz(\\d+)")) { "ZZ" + it.groupValues[1] }
            val plain = t.replace(Regex("ZZ\\d+"), "").replace(Regex("\\s+"), " ").trim()
            val known = GamePacks.find(t) != null || Phrasebook.lookup(t) != null || Phrasebook.lookup(plain) != null
            if (!known) {
                if (!looksEnglish(plain)) continue
                val n = plain.split(Regex("\\s+")).size
                if (n <= 2 && g.size == 1 && isName(plain) && icons.isEmpty()) {
                    pendingName = plain; pendingRect = sr; pendingColor = textColorIn(bm, br, bgColorAround(bm, br)); continue
                }
                if (n < 2 && icons.isEmpty()) continue
                if (icons.isEmpty() && isTitle(plain)) continue      // game names, logos
            }
            val bg = bgColorAround(bm, br)
            // skip a line that overlaps one already taken (no translation on top of translation)
            if (out.any { Rect.intersects(it.rect, sr) }) continue
            val p = Piece(t, sr, sr.height().toFloat() / g.size.coerceAtLeast(1), icons, textColorIn(bm, br, bg), bg)
            p.patch = softPatch(bm, padded(br, 0.45f))
            if (gluedSpeaker != null && pendingName == null) { pendingName = gluedSpeaker; pendingRect = sr }
            val pr = pendingRect
            if (pendingName != null && pr != null && sr.top - pr.bottom < sr.height() && sr.top >= pr.top) {
                p.speaker = pendingName
                p.speakerColor = pendingColor
                speakers.add(pendingName.lowercase())
                p.rect.union(pr)
            }
            pendingName = null
            out.add(p)
        }
        out.sortBy { it.rect.top }
        return out.take(6)
    }

    /** A heading in capitals above a normal sentence, or a clearly different text size, is a separate piece. */
    private fun differentStyle(a: Text.Line, b: Text.Line): Boolean {
        val ha = a.boundingBox?.height() ?: return false
        val hb = b.boundingBox?.height() ?: return false
        if (max(ha, hb) > min(ha, hb) * 1.3f) return true
        fun caps(t: String): Boolean { val l = t.filter { it.isLetter() }; return l.length >= 4 && l.count { it.isUpperCase() } > l.length * 0.8 }
        return caps(a.text) != caps(b.text)
    }

    /** The game picture behind the line, heavily blurred: hides the English softly and blends with the scene. */
    private fun softPatch(bm: Bitmap, r0: Rect): Bitmap? {
        val r = Rect(max(0, r0.left), max(0, r0.top), min(bm.width, r0.right), min(bm.height, r0.bottom))
        if (r.width() < 8 || r.height() < 8) return null
        val part = Bitmap.createBitmap(bm, r.left, r.top, r.width(), r.height())
        val tiny = Bitmap.createScaledBitmap(part, max(2, r.width() / 32), max(2, r.height() / 32), true)
        val small = Bitmap.createScaledBitmap(tiny, max(4, r.width() / 6), max(4, r.height() / 6), true)
        part.recycle(); tiny.recycle()
        // feather: dark haze strongest in the middle, fading to nothing at the edges (no visible shape)
        val w = small.width; val h = small.height
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = (min(x, w - 1 - x) / (w * 0.22f)).coerceIn(0f, 1f)
            val fy = (min(y, h - 1 - y) / (h * 0.32f)).coerceIn(0f, 1f)
            val t = min(fx, fy); val a = t * t * (3 - 2 * t) * 0.93f
            val c = px[y * w + x]
            val r = ((c shr 16 and 255) * 0.55f).toInt(); val g = ((c shr 8 and 255) * 0.55f).toInt(); val bl = ((c and 255) * 0.55f).toInt()
            px[y * w + x] = Color.argb((a * 255).toInt(), r, g, bl)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); out.setPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        return out
    }

    private fun isIconText(core: String, box: Rect, lh: Float): Boolean {
        if (core.isEmpty()) return false
        if (core.all { it.isDigit() }) return false
        if (core.none { it.isLetterOrDigit() }) return true                          // ©, @, ® … = a drawn glyph
        if (core.length > 2 || core.lowercase() in realShort) return false
        val ratio = box.width().toFloat() / max(1, box.height())
        return ratio in 0.6f..1.7f && box.height() >= lh * 0.8f
    }

    private fun padded(r: Rect, f: Float): Rect {
        val dx = (r.width() * f).toInt(); val dy = (r.height() * f).toInt()
        return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
    }

    private fun cropIcon(bm: Bitmap, r0: Rect): Bitmap? {
        val r = Rect(max(0, r0.left), max(0, r0.top), min(bm.width, r0.right), min(bm.height, r0.bottom))
        if (r.width() < 6 || r.height() < 6) return null
        return Bitmap.createBitmap(bm, r.left, r.top, r.width(), r.height())
    }

    /** average colour just outside the text box (the game's own background there) */
    private fun bgColorAround(bm: Bitmap, r: Rect): Int {
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        val m = max(4, r.height() / 4)
        fun add(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= bm.width || y >= bm.height) return
            val c = bm.getPixel(x, y); rs += c shr 16 and 255; gs += c shr 8 and 255; bs += c and 255; n++
        }
        var x = r.left - m
        while (x <= r.right + m) { add(x, r.top - m); add(x, r.bottom + m); x += 4 }
        var y = r.top - m
        while (y <= r.bottom + m) { add(r.left - m, y); add(r.right + m, y); y += 4 }
        if (n == 0) return Color.rgb(12, 14, 20)
        return Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    /** colour of the letters: the pixels inside the box that stand out most from the background */
    private fun textColorIn(bm: Bitmap, r: Rect, bg: Int): Int {
        val bgL = lum(bg)
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        var y = max(0, r.top)
        while (y < min(bm.height, r.bottom)) {
            var x = max(0, r.left)
            while (x < min(bm.width, r.right)) {
                val c = bm.getPixel(x, y)
                if (abs(lum(c) - bgL) > 80) { rs += c shr 16 and 255; gs += c shr 8 and 255; bs += c and 255; n++ }
                x += 2
            }
            y += 2
        }
        if (n < 10) return if (bgL > 140) Color.BLACK else Color.WHITE
        return Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    private fun clean(raw: String): String {
        var s = raw.replace('|', 'I').replace('’', '\'').replace('“', '"').replace('”', '"')
            .replace(Regex("\\s+"), " ").trim()
        val up = s.count { it.isUpperCase() }
        val low = s.count { it.isLowerCase() }
        if (up > 6 && low < up / 4) { // ALL CAPS translates badly -> sentence case
            val b = StringBuilder()
            var cap = true
            for (c in s.lowercase()) {
                b.append(if (cap && c.isLetter()) c.uppercaseChar() else c)
                if (c.isLetter()) cap = false
                if (c == '.' || c == '!' || c == '?') cap = true
            }
            s = b.toString().replace(Regex("\\bi\\b"), "I")
        }
        return s
    }

    private fun looksEnglish(s: String): Boolean {
        var letters = 0; var other = 0; var longest = 0; var run = 0
        for (c in s) {
            if (c in 'a'..'z' || c in 'A'..'Z') { letters++; run++; longest = max(longest, run) }
            else { run = 0; if (!c.isWhitespace() && ".,!?'\"-:;()".indexOf(c) < 0) other++ }
        }
        if (letters < 3 || longest < 3 || other * 3 >= letters) return false
        // Arabic menus misread as Latin come out with accents (ä, ó) or odd casing ("UrRaySist")
        if (s.any { it.isLetter() && it.code > 127 }) return false
        if (Regex("[a-z][A-Z][a-z]").containsMatchIn(s)) return false
        val words = s.lowercase().split(Regex("[^a-z']+")).filter { it.length >= 2 }
        if (words.isEmpty()) return false
        if (words.count { w -> w.any { it in "aeiouy" } } < words.size * 0.8) return false
        if (words.size >= 4 && words.none { it in COMMON }) return false
        // prices and store tiles ("299,00 SAR", "$59.99") are not dialogue
        if (Regex("""(\d+[.,]\d{2})|\b(SAR|USD|AED|EUR)\b|[€£]""").containsMatchIn(s) || s.contains('$')) return false
        // dialogue and instructions use everyday words or end like a sentence; tile names and misreads do not
        if (words.none { it.length >= 3 && it in COMMON } && words.count { it in COMMON } < 2 && !s.trimEnd().endsWith(".") && !s.trimEnd().endsWith("!") && !s.trimEnd().endsWith("?")) return false
        return true
    }

    private val COMMON = setOf("the", "to", "you", "a", "i", "is", "of", "and", "we", "it", "that", "in", "my", "your",
        "this", "what", "be", "have", "not", "are", "for", "on", "with", "me", "do", "go", "can", "will", "was", "he",
        "she", "they", "there", "here", "at", "all", "but", "so", "if", "no", "yes", "get", "just", "now", "out", "up",
        "an", "as", "by", "from", "our", "us", "his", "her", "them", "who", "how", "where", "why", "when", "i'm", "it's",
        "don't", "let's", "come", "know", "like", "need", "want", "one", "time", "back", "press", "hold", "use", "find")

    private fun isTitle(s: String): Boolean {
        if (s.any { it == '.' || it == '?' || it == '!' || it == ',' }) return false
        val ws = s.split(" ").filter { it.any(Char::isLetter) }
        if (ws.size > 6) return false
        val small = setOf("of", "the", "and", "a", "an", "in", "on", "to", "for", "at", "by", "or", "with")
        return ws.all { w -> w[0].isUpperCase() || w[0].isDigit() || w.lowercase() in small }
    }

    private fun isName(s: String) = s.length <= 24 && !s.endsWith(".") &&
            s.split(" ").all { it.isNotEmpty() && it[0].isUpperCase() }

    private fun similar(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }

    // ------------------------------------------------------------------ translating

    private fun mt(en: String): String {
        cache[en]?.let { return it }
        val ar = Tasks.await(translator.translate(en), 5, TimeUnit.SECONDS)
            .replace(Regex("(?i)z\\s?z\\s?(\\d+)")) { "ZZ" + it.groupValues[1] }
            .replace(Regex("(?i)n\\s?n\\s?(\\d+)")) { "NN" + it.groupValues[1] }
        cache[en] = ar
        return ar
    }

    /** Arabic for a character or place name, always the same once chosen. */
    private fun nameAr(name: String): String {
        GamePacks.name(name)?.let { return it }
        val key = name.lowercase()
        names.getString(key, null)?.let { return it }
        val pretty = name.lowercase().replaceFirstChar { it.uppercase() }
        val ar = mt(pretty)
        names.edit().putString(key, ar).apply()
        return ar
    }

    private fun translatePiece(raw: String): String {
        // a stray single digit/letter before a sentence is usually a misread picture (e.g. "3 Explore …")
        val en = raw.replace(Regex("^[0-9]\\s+(?=[A-Z])"), "")
        GamePacks.find(en)?.let { return it }          // the game's own professional translation first
        Library.find(GamePacks.hash(en))?.let { return it }   // shared library, filled automatically
        Phrasebook.lookup(en)?.let { return it }

        // button prompts: "Press ZZ0 to jump", "Hold ZZ0 to …", "… by pressing ZZ0"
        val s = en.trim().trimEnd('.', '!')
        Regex("^(?:press|tap)\\s+(ZZ\\d+)(?:\\s+to\\s+(.+))?$", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            val rest = m.groupValues[2]
            return if (rest.isEmpty()) "اضغط ${m.groupValues[1]}"
            else "اضغط ${m.groupValues[1]} " + (Phrasebook.verb(rest) ?: mt("to $rest"))
        }
        Regex("^hold\\s+(ZZ\\d+)(?:\\s+to\\s+(.+))?$", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            val rest = m.groupValues[2]
            return "اضغط مطولًا على ${m.groupValues[1]}" + if (rest.isEmpty()) "" else " " + (Phrasebook.verb(rest) ?: mt("to $rest"))
        }
        Regex("^(.+?)\\s+by\\s+pressing\\s+(ZZ\\d+)$", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            return translateSentence(m.groupValues[1]).trimEnd('.', '،') + " بالضغط على ${m.groupValues[2]}."
        }
        Phrasebook.objective(en)?.let { (verb, obj) -> Library.submit(GamePacks.hash(en), en, GamePacks.activeGame); return verb + translateSentence(obj) }
        Library.submit(GamePacks.hash(en), en, GamePacks.activeGame)
        return translateSentence(en)
    }

    /** Machine translation with names protected so they come out the same every time. */
    private fun translateSentence(en: String): String {
        val words = en.split(" ")
        val found = ArrayList<String>()
        val out = ArrayList<String>()
        fun isCap(w: String): Boolean {
            val c = w.trim(',', '.', '!', '?', ':', ';', '"', '\'')
            return c.length >= 2 && c[0].isUpperCase() && c.drop(1).all { it.isLowerCase() || it == '\'' } && !c.startsWith("ZZ")
        }
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val sentenceStart = i == 0 || words[i - 1].endsWith(".") || words[i - 1].endsWith("!") || words[i - 1].endsWith("?")
            // run of capitalised words in the middle of a sentence = a name ("Dark Beauty Castle")
            var j = i
            while (j < words.size && isCap(words[j]) && (j == i || !words[j - 1].endsWith(","))) j++
            val run = words.subList(i, j)
            if (!sentenceStart && run.size >= 2) {
                val trail = run.last().takeLastWhile { !it.isLetter() }
                val name = run.joinToString(" ") { it.trim(',', '.', '!', '?', ':', ';', '"') }
                names.edit().putString(name.lowercase(), names.getString(name.lowercase(), null) ?: mt(name)).apply()
                found.add(name); out.add("NN${found.size - 1}$trail"); i = j; continue
            }
            val core = w.trim(',', '.', '!', '?', ':', ';', '"')
            var token = w
            if (isCap(w) && core.length >= 3) {
                val k = core.lowercase()
                val count = (nameSeen[k] ?: 0) + if (!sentenceStart) 1 else 0
                nameSeen[k] = count
                if (k in speakers || names.contains(k) || count >= 2) { found.add(core); token = w.replace(core, "NN${found.size - 1}") }
            }
            out.add(token)
            i++
        }
        var ar = mt(out.joinToString(" "))
        for ((n, name) in found.withIndex()) ar = ar.replace("NN$n", nameAr(name))
        return ar
    }

    // ------------------------------------------------------------------ showing

    private class Shown(val rect: Rect, val speaker: String?, val speakerColor: Int, val ar: String,
                        val icons: List<Bitmap>, val textColor: Int, val bgColor: Int, val lineH: Float, val patch: Bitmap?)

    private val boxes = ArrayList<View>()

    private fun show(list: List<Shown>) {
        if (!running || !enabled) return
        val old = ArrayList(boxes)
        boxes.clear()
        val w = overlayHost.width
        val h = overlayHost.height.toFloat()
        val mult = floatArrayOf(0.85f, 1.0f, 1.25f)[store.textSize.coerceIn(0, 2)]
        // lines that sit next to or on top of each other (dialogue + "Press X to continue") share ONE box
        val groups = ArrayList<MutableList<Shown>>()
        for (item in list) {
            val near = Rect(item.rect).apply { inset(-90, -30) }
            val g = groups.firstOrNull { gr -> gr.any { Rect.intersects(near, it.rect) } }
            if (g != null) g.add(item) else groups.add(mutableListOf(item))
        }
        for (g in groups) {
            g.sortWith(compareBy({ it.rect.top }, { -it.rect.right }))
            val s = g.maxBy { it.rect.width() * it.rect.height() }
            val r = Rect(g[0].rect).also { u -> g.forEach { u.union(it.rect) } }
            val px = 40f * mult * h / 1080f        // same size for every line
            // colours from the game; if text and background are too close, fall back to white on dark
            val colors = steadyColors(s, h)
            val fg = Color.WHITE
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                // cinematic subtitle: no box, no border - a soft haze of the scene itself that fades out at its edges
                background = s.patch?.let { BitmapDrawable(ctx.resources, it).apply { isFilterBitmap = true } }
                    ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(0, 0, 0, 0)))
                setPadding(70, 16, 70, 18)
            }
            for (m in g) {
            if (m.speaker != null) box.addView(TextView(ctx).apply {
                text = m.speaker
                setTextColor(colors.speaker)
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px * 0.8f)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER
            })
            box.addView(TextView(ctx).apply {
                text = withIcons(m.ar, m.icons, px)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
                setLineSpacing(0f, 1.08f)
                setShadowLayer(8f, 0f, 2f, Color.argb(200, 0, 0, 0)); letterSpacing = 0.01f
            })
            }
            val bw = (r.width() + 160).coerceIn((w * 0.10f).toInt(), (w * 0.95f).toInt())
            box.measure(View.MeasureSpec.makeMeasureSpec(bw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h.toInt(), View.MeasureSpec.AT_MOST))
            val bh = max(box.measuredHeight, r.height() + 44)   // always cover the English completely
            val lp = FrameLayout.LayoutParams(bw, bh, Gravity.TOP or Gravity.START)
            lp.leftMargin = (r.centerX() - bw / 2).coerceIn(0, max(0, w - bw))
            lp.topMargin = (r.centerY() - bh / 2).coerceIn(0, max(0, h.toInt() - bh))
            box.gravity = Gravity.CENTER
            box.alpha = 0f                       // built hidden, shown only when complete
            overlayHost.addView(box, lp)
            boxes.add(box)
        }
        // after the new boxes are laid out: fade them in together, fade the old ones out
        val fresh = ArrayList(boxes)
        overlayHost.post {
            for (b in fresh) b.animate().alpha(1f).setDuration(140).start()
        }
        for (b in old) overlayHost.removeView(b)
    }

    // colours chosen for one part of the screen stay the same while that dialogue box is in use
    private class Colors(val bg: Int, val fg: Int, val speaker: Int, val at: Long)
    private val colorMemory = HashMap<Int, Colors>()

    private fun steadyColors(s: Shown, h: Float): Colors {
        val bucket = (s.rect.centerY() * 12 / max(1f, h)).toInt()
        val now = SystemClock.uptimeMillis()
        colorMemory[bucket]?.let { if (now - it.at < 20_000) { colorMemory[bucket] = Colors(it.bg, it.fg, it.speaker, now); return it } }
        var bg = s.bgColor; var fg = s.textColor
        if (abs(lum(bg) - lum(fg)) < 90) { bg = Color.rgb(14, 16, 22); fg = Color.WHITE }
        return Colors(bg, fg, s.speakerColor, now).also { colorMemory[bucket] = it }
    }

    /** Puts the game's own button pictures where the ZZ0, ZZ1… marks are. */
    private fun withIcons(ar: String, icons: List<Bitmap>, px: Float): CharSequence {
        val sb = SpannableStringBuilder()
        var last = 0
        val used = HashSet<Int>()
        for (m in Regex("ZZ(\\d+)").findAll(ar)) {
            sb.append(ar, last, m.range.first)
            val i = m.groupValues[1].toInt()
            val icon = icons.getOrNull(i)
            if (icon != null) { appendIcon(sb, icon, px); used.add(i) }
            last = m.range.last + 1
        }
        sb.append(ar, last, ar.length)
        // any icon the translation dropped goes at the end, so nothing is lost
        for ((i, icon) in icons.withIndex()) if (i !in used) { sb.append(' '); appendIcon(sb, icon, px) }
        return sb
    }

    private fun appendIcon(sb: SpannableStringBuilder, icon: Bitmap, px: Float) {
        val d = BitmapDrawable(ctx.resources, icon)
        val size = (px * 1.15f).toInt()
        d.setBounds(0, 0, size * icon.width / max(1, icon.height), size)
        val start = sb.length
        sb.append('￼')
        val align = if (Build.VERSION.SDK_INT >= 29) DynamicDrawableSpan.ALIGN_CENTER else DynamicDrawableSpan.ALIGN_BASELINE
        sb.setSpan(ImageSpan(d, align), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun hideNow() {
        val old = ArrayList(boxes)
        boxes.clear()
        for (b in old) b.animate().alpha(0f).setDuration(120).withEndAction { overlayHost.removeView(b) }.start()
    }

    private fun hide() = ui.post { hideNow() }
}
