package com.abubadr.arribha

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.metallic.chiaki.lib.ChiakiLog
import com.metallic.chiaki.lib.CreateError
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.lib.DiscoveryService
import com.metallic.chiaki.lib.DiscoveryServiceOptions
import com.metallic.chiaki.lib.Regist
import com.metallic.chiaki.lib.RegistEventCanceled
import com.metallic.chiaki.lib.RegistEventFailed
import com.metallic.chiaki.lib.RegistEventSuccess
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/** Home screen in Arabic: link the PlayStation once, then one button to play. */
class MainActivity : Activity() {
    private lateinit var store: Store
    private val ui = Handler(Looper.getMainLooper())
    private var discovery: DiscoveryService? = null
    @Volatile private var hosts: List<DiscoveryHost> = emptyList()
    private var regist: Regist? = null
    private var waitingToPlay = false

    private lateinit var consoleLine: TextView
    private lateinit var deviceLine: TextView
    private lateinit var playBtn: Button
    private lateinit var linkBox: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var accountField: EditText
    private lateinit var pinField: EditText
    private lateinit var linkStatus: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        Licensing.init(this)
        Licensing.onChange = { ui.post { refresh() } }
        if (!store.welcomed) startActivity(Intent(this, WelcomeActivity::class.java))
        else Updater.check(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(100, 30, 100, 24)
        }
        val t1 = text("عرّبها", 56f, Color.WHITE).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val t2 = text("ترجمة عربية لألعاب البلايستيشن", 17f, Color.rgb(143, 163, 199))
        col.addView(t1); col.addView(t2, lp(2)); titleViews = listOf(t1, t2)
        consoleLine = text("", 18f, Color.rgb(205, 216, 236)).apply {
            background = GradientDrawable().apply { cornerRadius = 100f; setColor(Color.argb(60, 255, 255, 255)) }
            setPadding(36, 12, 36, 12)
        }
        col.addView(consoleLine, lp(18))

        // controller not connected to the TV: one tap opens the TV's pairing screen
        padBtn = button("ربط اليد بالتلفزيون", false).apply {
            setOnClickListener {
                openPairing()
                android.widget.Toast.makeText(this@MainActivity, "اضغط Create وPS مع بعض لين يرمش الضوء، واختر «إضافة»", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        col.addView(padBtn, lp(12))
        playBtn = button("ابدأ اللعب", true).also { col.addView(it, LinearLayout.LayoutParams(560, -2).apply { topMargin = 18; gravity = Gravity.CENTER_HORIZONTAL }) }
        playBtn.setOnClickListener { if (Licensing.canPlay) play() else paywall() }

        licenseLine = text("", 14f, Color.rgb(143, 163, 199)).also { col.addView(it, lp(6)) }

        // --- one-time linking ---
        linkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        linkBox.addView(text("ربط البلايستيشن — مرة وحدة", 22f, Color.WHITE), lp(14))
        // two ways to sign in, side by side: on the TV, or by scanning with the phone
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 36f; setColor(Color.argb(28, 255, 255, 255)) }
            setPadding(40, 26, 40, 26)
        }
        val tvCard = card()
        tvCard.addView(text("من التلفزيون", 20f, Color.WHITE))
        tvCard.addView(text("سجّل دخولك هنا بالريموت", 14f, Color.rgb(143, 163, 199)), lp(4))
        val psnBtn = button("تسجيل الدخول", true).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setPadding(30, 14, 30, 14) }
        psnBtn.setOnClickListener { startActivityForResult(Intent(this, PsnLoginActivity::class.java), 41) }
        tvCard.addView(psnBtn, LinearLayout.LayoutParams(340, -2).apply { topMargin = 18; gravity = Gravity.CENTER_HORIZONTAL })
        val phoneCard = card()
        phoneCard.addView(text("من الجوال", 20f, Color.WHITE))
        phoneCard.addView(text("امسح الرمز بكاميرا جوالك", 14f, Color.rgb(143, 163, 199)), lp(4))
        phoneCard.addView(android.widget.ImageView(this).apply { setImageBitmap(qr("https://arribha-intake.d-fifn1.workers.dev/link?s=$linkSid", 190)) },
            LinearLayout.LayoutParams(190, 190).apply { topMargin = 12; gravity = Gravity.CENTER_HORIZONTAL })
        choices.addView(tvCard, LinearLayout.LayoutParams(440, 330))
        choices.addView(text("أو", 18f, Color.rgb(143, 163, 199)), LinearLayout.LayoutParams(-2, -2).apply { setMargins(30, 0, 30, 0) })
        choices.addView(phoneCard, LinearLayout.LayoutParams(440, 330))
        linkBox.addView(choices, lp(16))
        // the Account ID is filled in by the sign-in; players never need to see it
        accountField = field("رقم حساب PSN (Account ID)", InputType.TYPE_CLASS_TEXT).also { it.visibility = View.GONE; linkBox.addView(it, wide(16)) }
        linkBox.addView(text("في البلايستيشن: الإعدادات ← النظام ← اللعب عن بُعد ← إقران الجهاز", 16f, Color.rgb(143, 163, 199)), lp(18))
        pinField = field("رقم الربط (8 أرقام)", InputType.TYPE_CLASS_NUMBER).also { linkBox.addView(it, wide(8)) }
        val linkBtn = button("ربط", true)
        linkBtn.setOnClickListener { link() }
        linkBox.addView(linkBtn, LinearLayout.LayoutParams(420, -2).apply { topMargin = 20; gravity = Gravity.CENTER_HORIZONTAL })
        linkStatus = text("", 17f, Color.rgb(250, 205, 95)).also { linkBox.addView(it, lp(10)) }
        col.addView(linkBox)

        // --- quick settings: three small chips ---
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val tBtn = button("", false); val aBtn = button("", false); val sBtn = button("", false)
        fun refreshSettings() {
            tBtn.text = if (store.translateOn) "الترجمة: تعمل" else "الترجمة: متوقفة"
            aBtn.text = if (store.readFullScreen) "الشاشة كاملة" else "الحوارات فقط"
            sBtn.text = "الخط: " + arrayOf("صغير", "متوسط", "كبير")[store.textSize]
        }
        tBtn.setOnClickListener { store.translateOn = !store.translateOn; refreshSettings() }
        aBtn.setOnClickListener { store.readFullScreen = !store.readFullScreen; refreshSettings() }
        sBtn.setOnClickListener { store.textSize = (store.textSize + 1) % 3; refreshSettings() }
        refreshSettings()
        for (b in listOf(tBtn, aBtn, sBtn)) settingsBox.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(10, 0, 10, 0) })
        col.addView(settingsBox, lp(16))

        // --- sponsors: three tiles, filled from sponsors.json on GitHub ---
        col.addView(text("رعاة", 12f, Color.rgb(96, 110, 138)), lp(16))
        sponsorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        col.addView(sponsorRow, lp(6))
        loadSponsors()

        // --- footer: quiet details ---
        deviceLine = text("", 13f, Color.rgb(96, 110, 138))
        col.addView(deviceLine, lp(16))
        val relink = text("إلغاء الربط", 13f, Color.rgb(120, 136, 168)).apply {
            isFocusable = true; setPadding(20, 8, 20, 8)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage("تبغى تلغي ربط البلايستيشن وتسجيل الدخول من هذا التلفزيون؟")
                    .setPositiveButton("إلغاء الربط") { _, _ ->
                        store.registKey = null; store.morning = null; store.accountId = ""; store.consoleId = ""
                        accountField.setText(""); linkStatus.text = ""; refresh()
                    }
                    .setNegativeButton("رجوع", null).show().focusMain()
            }
            setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Color.WHITE else Color.rgb(120, 136, 168)) }
        }
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        col.addView(footer, lp(2))
        footer.addView(relink)
        footer.addView(text("•", 13f, Color.rgb(96, 110, 138)), LinearLayout.LayoutParams(-2, -2).apply { setMargins(8, 0, 8, 0) })
        footer.addView(text("طريقة الاستخدام", 13f, Color.rgb(120, 136, 168)).apply {
            isFocusable = true; setPadding(20, 8, 20, 8)
            setOnClickListener { startActivity(Intent(this@MainActivity, WelcomeActivity::class.java)) }
            setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Color.WHITE else Color.rgb(120, 136, 168)) }
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(16, 27, 54), Color.rgb(7, 10, 20), Color.rgb(4, 5, 10)))
            addView(col, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        })
        accountField.setText(store.accountId)

        if (store.tier < 0) {
            deviceLine.text = "⏳ أفحص قوة التلفزيون..."
            Thread {
                val r = DeviceCheck.run(this)
                store.tier = r.tier
                store.useHevc = r.hevc
                Log.i("Arribha", "device: tier ${r.tier} ram ${r.ramMb} cores ${r.cores} ${r.maxMhz}MHz bench ${r.benchMs}ms hevc ${r.hevc} 1080 ${r.decode1080}")
                ui.post { refresh() }
            }.start()
        }
        refresh()
    }

    @Deprecated("simple result")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 41) return
        val id = data?.getStringExtra(PsnLoginActivity.EXTRA_ACCOUNT_ID)
        if (res == RESULT_OK && id != null) {
            store.accountId = id; accountField.setText(id)
            linkStatus.text = "✓ تم تسجيل الدخول. باقي رقم الربط من البلايستيشن"
            pinField.requestFocus()
        } else linkStatus.text = "ما اكتمل تسجيل الدخول، جرّب مرة ثانية"
    }

    private var titleViews: List<View> = emptyList()
    private lateinit var licenseLine: TextView
    private lateinit var padBtn: Button
    /** Opens the "add controller" screen on any Android TV / Google TV / Fire TV, trying the known screens in order. */
    private fun openPairing() {
        val tries = listOf(
            Intent("com.google.android.intent.action.CONNECT_INPUT"),                                   // Android TV / Google TV: add accessory
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"),
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.connectivity.setup.AddAccessoryActivity"),
            Intent().setClassName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothDevicesActivity"), // Fire TV
            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            if (i.resolveActivity(packageManager) == null) continue
            if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    private lateinit var sponsorRow: LinearLayout

    private fun paywall() {
        val price = Licensing.price ?: "9.99 ر.س"
        android.app.AlertDialog.Builder(this)
            .setTitle("فعّل عرّبها مدى الحياة")
            .setMessage("انتهت تجربتك المجانية (3 ساعات لعب).\n\nادفع مرة وحدة بس $price وتلعب بالعربي مدى الحياة، مع كل التحديثات.\n\nالدفع آمن عن طريق Google Play: بالبطاقة، أو رصيد الجوال، أو بطاقات Google Play، حسب المتاح في بلدك.")
            .setPositiveButton("شراء $price") { _, _ ->
                if (!Licensing.buy(this)) android.widget.Toast.makeText(this, "متجر Google Play غير جاهز، جرّب بعد شوي", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("لاحقًا", null).show().focusMain()
    }

    private fun loadSponsors() {
        Thread {
            val cfg = runCatching {
                val c = java.net.URL("https://cdn.jsdelivr.net/gh/fifn1/arribha-packs@main/sponsors.json").openConnection() as java.net.HttpURLConnection
                c.setRequestProperty("User-Agent", "arribha-app/1"); c.connectTimeout = 4000; c.readTimeout = 6000
                org.json.JSONObject(c.inputStream.bufferedReader().readText())
            }.getOrNull()
            val adUrl = cfg?.optString("advertise_url", "") ?: ""
            val slots = (0 until 3).map { i -> cfg?.optJSONArray("slots")?.optJSONObject(i) }
            val images = slots.map { s ->
                val img = s?.optString("image", "") ?: ""
                if (img.startsWith("https://")) runCatching { android.graphics.BitmapFactory.decodeStream(java.net.URL(img).openStream()) }.getOrNull() else null
            }
            ui.post {
                sponsorRow.removeAllViews()
                for (i in 0 until 3) {
                    val s = slots[i]; val name = s?.optString("name", "") ?: ""; val url = s?.optString("url", "") ?: ""
                    val tile = android.widget.FrameLayout(this).apply {
                        isFocusable = true
                        val bg = GradientDrawable().apply { cornerRadius = 22f; setColor(Color.argb(30, 255, 255, 255)) }
                        background = bg; clipToOutline = true
                        if (images[i] != null) addView(android.widget.ImageView(context).apply {
                            setImageBitmap(images[i]); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP })
                        else addView(text(if (name.isNotEmpty()) name else "أعلن هنا", 15f, Color.rgb(143, 163, 199)),
                            android.widget.FrameLayout.LayoutParams(-1, -1))
                        setOnFocusChangeListener { v, f -> bg.setStroke(if (f) 4 else 0, Color.WHITE); v.animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).setDuration(120).start() }
                        setOnClickListener { showQr(if (url.startsWith("https://")) url else adUrl, if (url.isNotEmpty()) name else "أعلن في عرّبها") }
                    }
                    sponsorRow.addView(tile, LinearLayout.LayoutParams(210, 118).apply { setMargins(12, 0, 12, 0) })
                }
            }
        }.start()
    }

    /** sponsor links open on the phone: scanning is easier than a TV browser */
    private fun showQr(url: String, title: String) {
        if (url.isEmpty()) return
        val img = android.widget.ImageView(this).apply { setImageBitmap(qr(url, 360)); setPadding(30, 30, 30, 30) }
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage("امسح الرمز بكاميرا جوالك").setView(img)
            .setPositiveButton("تم", null).show().focusMain()
    }

    private fun hasPad() = android.view.InputDevice.getDeviceIds().any { id ->
        val d = android.view.InputDevice.getDevice(id); d != null && (d.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD }
    private val linkSid = java.security.SecureRandom().let { r -> ByteArray(16).also { r.nextBytes(it) } }.joinToString("") { "%02x".format(it) }

    private fun qr(text: String, size: Int): android.graphics.Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until size) for (x in 0 until size) bm.setPixel(x, y, if (m[x, y]) Color.rgb(10, 14, 26) else Color.WHITE)
        return bm
    }

    /** while the link screen is open, ask every 2 s whether the phone finished signing in */
    private val pollQr = object : Runnable {
        override fun run() {
            if (store.isLinked || linkBox.visibility != View.VISIBLE) { ui.postDelayed(this, 2000); return }
            Thread {
                val code = runCatching {
                    val c = java.net.URL("https://arribha-intake.d-fifn1.workers.dev/link-poll?s=$linkSid").openConnection() as java.net.HttpURLConnection
                    c.setRequestProperty("User-Agent", "arribha-app/1"); c.connectTimeout = 4000; c.readTimeout = 6000
                    org.json.JSONObject(c.inputStream.bufferedReader().readText()).optString("code", "")
                }.getOrDefault("")
                val id = if (code.isNotEmpty()) runCatching { PsnAuth.accountId(code) }.getOrNull() else null
                ui.post {
                    if (id != null) {
                        store.accountId = id; accountField.setText(id)
                        linkStatus.text = "✓ تم تسجيل الدخول من الجوال. باقي رقم الربط من البلايستيشن"
                        pinField.requestFocus()
                    } else if (code.isNotEmpty()) linkStatus.text = "ما اكتمل تسجيل الدخول، جرّب مرة ثانية"
                }
            }.start()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        startDiscovery()
        ui.removeCallbacks(pollQr); ui.postDelayed(pollQr, 2000)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        waitingToPlay = false
        ui.removeCallbacks(pollQr)
        discovery?.dispose()
        discovery = null
    }

    private fun startDiscovery() {
        if (discovery != null) return
        try {
            discovery = DiscoveryService(
                DiscoveryServiceOptions(16U, 3U, 500U, InetSocketAddress("255.255.255.255", 987))
            ) { list -> hosts = list; ui.post { onHosts() } }
        } catch (e: CreateError) {
            Log.e("Arribha", "discovery failed", e)
        }
    }

    private fun myHost(): DiscoveryHost? =
        hosts.firstOrNull { store.consoleId.isNotEmpty() && it.hostId == store.consoleId }
            ?: hosts.firstOrNull { it.hostAddr == store.consoleHost }
            ?: if (!store.isLinked) hosts.firstOrNull() else null

    private fun onHosts() {
        refresh()
        val h = myHost() ?: return
        if (h.hostAddr != null && store.isLinked && h.hostAddr != store.consoleHost) store.consoleHost = h.hostAddr
        h.systemVersion?.let { if (it != store.consoleFw) store.consoleFw = it }
        if (waitingToPlay && h.state == DiscoveryHost.State.READY) { waitingToPlay = false; openStream() }
    }

    private fun refresh() {
        val h = myHost()
        consoleLine.text = when {
            h == null -> "● جاري البحث عن البلايستيشن"
            h.state == DiscoveryHost.State.READY -> "● ${h.hostName} جاهز"
            h.state == DiscoveryHost.State.STANDBY -> "● ${h.hostName} في وضع الراحة"
            else -> "● ${h.hostName}"
        }
        consoleLine.text = android.text.SpannableString(consoleLine.text).apply {
            if (isNotEmpty() && this[0] == '●') setSpan(android.text.style.ForegroundColorSpan(
                if (h?.state == DiscoveryHost.State.READY) Color.rgb(52, 211, 120) else Color.rgb(250, 190, 70)), 0, 1, 0)
        }
        val tierName = when (store.tier) { 2 -> "قوي"; 1 -> "متوسط"; 0 -> "ضعيف"; else -> null }
        if (tierName != null) {
            val warn = if (store.tier == 0) "\n⚠️ تلفزيونك ضعيف، وممكن تنزل الفريمات حتى بدون ترجمة. الترجمة بتشتغل ببطء عشان تحافظ على السلاسة." else ""
            deviceLine.text = "1080p • 60 إطارًا • ${if (store.useHevc) "HEVC" else "H.264"} • جهاز $tierName$warn"
        }
        licenseLine.text = when {
            Licensing.owned -> ""
            Licensing.canPlay -> "تجربة مجانية: باقي ${Licensing.trialLeftSeconds / 3600} س ${(Licensing.trialLeftSeconds % 3600) / 60} د من اللعب"
            else -> "انتهت التجربة المجانية — فعّل عرّبها مدى الحياة"
        }
        val linked = store.isLinked
        padBtn.visibility = if (linked && !hasPad()) View.VISIBLE else View.GONE
        titleViews.forEach { it.visibility = if (linked) View.VISIBLE else View.GONE }
        linkBox.visibility = if (linked) View.GONE else View.VISIBLE
        settingsBox.visibility = if (linked) View.VISIBLE else View.GONE
        playBtn.visibility = if (linked) View.VISIBLE else View.GONE
        if (linked && !playBtn.hasFocus() && currentFocus == null) playBtn.requestFocus()
    }

    // ---------------------------------------------------------------- link

    private fun link() {
        val h = myHost()
        val addr = h?.hostAddr
        if (addr == null) { linkStatus.text = "ما لقيت البلايستيشن في الشبكة. شغّله وتأكد إنه على نفس الراوتر."; return }
        val acc = try { Base64.decode(accountField.text.toString().trim(), Base64.DEFAULT) } catch (e: Exception) { null }
        if (acc == null || acc.size != RegistInfo.ACCOUNT_ID_SIZE) { linkStatus.text = "سجّل دخولك أول (من التلفزيون أو الجوال)"; return }
        val pin = pinField.text.toString().trim()
        if (pin.length != 8 || pin.any { !it.isDigit() }) { linkStatus.text = "رقم الربط لازم يكون 8 أرقام"; return }
        store.accountId = accountField.text.toString()
        val target = if (h.isPS5) Target.PS5_1 else Target.PS4_10
        linkStatus.text = "⏳ جاري الربط..."
        regist?.dispose()
        try {
            val log = ChiakiLog(ChiakiLog.Level.WARNING.value or ChiakiLog.Level.ERROR.value)  /* INFO prints the link keys */ { _, t -> Log.i("ArribhaRegist", t) }
            regist = Regist(RegistInfo(target, addr, false, null, acc, pin.toInt()), log) { ev ->
                ui.post {
                    when (ev) {
                        is RegistEventSuccess -> {
                            store.registKey = ev.host.rpRegistKey
                            store.morning = ev.host.rpKey
                            store.consoleHost = addr
                            store.consoleName = ev.host.serverNickname
                            store.consoleId = h.hostId ?: ""
                            store.isPS5 = h.isPS5
                            linkStatus.text = ""
                            pinField.setText("")
                            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                .hideSoftInputFromWindow(pinField.windowToken, 0)
                            refresh()
                            playBtn.requestFocus()
                        }
                        is RegistEventFailed -> linkStatus.text = "❌ ما نجح الربط. تأكد من رقم الربط (يتغير كل 5 دقايق) ورقم الحساب."
                        is RegistEventCanceled -> linkStatus.text = "انلغى الربط"
                    }
                }
            }
        } catch (e: CreateError) {
            linkStatus.text = "❌ ${e.errorCode}"
        }
    }

    // ---------------------------------------------------------------- play

    private fun play() {
        val h = myHost()
        if (h != null && h.state == DiscoveryHost.State.STANDBY) {
            consoleLine.text = "⏳ أصحّي البلايستيشن... (تقريباً 20 ثانية)"
            wakeup(h.hostAddr ?: store.consoleHost)
            waitingToPlay = true
            ui.postDelayed({ if (waitingToPlay) { waitingToPlay = false; openStream() } }, 35_000)
            return
        }
        openStream()
    }

    private fun wakeup(addr: String) {
        val key = store.registKey ?: return
        val end = key.indexOfFirst { it == 0.toByte() }.let { if (it >= 0) it else key.size }
        val cred = try { String(key, 0, end, StandardCharsets.UTF_8).toULong(16) } catch (e: Exception) { return }
        DiscoveryService.wakeup(discovery, addr, cred, store.isPS5)
    }

    private fun openStream() = startActivity(Intent(this, StreamActivity::class.java))

    // ---------------------------------------------------------------- ui helpers

    private fun text(s: String, sp: Float, color: Int) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); gravity = Gravity.CENTER
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    }

    private fun button(s: String, primary: Boolean) = Button(this).apply {
        text = s; isAllCaps = false; stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 26f else 16f)
        setTextColor(if (primary) Color.WHITE else Color.rgb(205, 216, 236))
        if (primary) typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(if (primary) 60 else 34, if (primary) 26 else 12, if (primary) 60 else 34, if (primary) 26 else 12)
        val bg = if (primary) GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(Color.rgb(47, 107, 255), Color.rgb(0, 166, 255))).apply { cornerRadius = 100f }
                 else GradientDrawable().apply { cornerRadius = 100f; setColor(Color.argb(40, 255, 255, 255)) }
        background = bg
        setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(140).start()
            bg.setStroke(if (f) 4 else 0, Color.argb(220, 255, 255, 255))
            if (!primary) (v as Button).setTextColor(if (f) Color.WHITE else Color.rgb(205, 216, 236))
        }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = top; gravity = Gravity.CENTER_HORIZONTAL }
    private fun wide(top: Int) = LinearLayout.LayoutParams(900, -2).apply { topMargin = top; gravity = Gravity.CENTER_HORIZONTAL }
}
