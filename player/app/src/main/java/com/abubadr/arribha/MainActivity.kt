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
private const val CONTACT = "hello@taketore.com"

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
    private lateinit var choicesRow: LinearLayout
    private lateinit var signedRow: LinearLayout
    private var linkedOnly: List<View> = emptyList()
    private var linking = false          // a link request is running: never start a second one
    private var linkWhenFound = false    // code typed before the PS5 showed up: link as soon as it does

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        L.init(this)
        if (!L.chosen(this)) { startActivityForResult(Intent(this, LanguageActivity::class.java), 42) }
        Licensing.init(this)
        Licensing.onChange = { ui.post { refresh() } }
        if (L.chosen(this) && !store.welcomed) startActivity(Intent(this, WelcomeActivity::class.java))
        else if (BuildConfig.SELF_UPDATE) Updater.check(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = L.dir
            setPadding(100, 30, 100, 24)
        }
        val t1 = text(L.appName, 56f, Color.WHITE).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val t2 = text(L.s("ترجمة فورية لألعاب البلايستيشن", "PlayStation oyunları için anlık çeviri"), 17f, Color.rgb(143, 163, 199))
        col.addView(t1); col.addView(t2, lp(2)); titleViews = listOf(t1, t2)
        consoleLine = text("", 18f, Color.rgb(205, 216, 236)).apply {
            background = GradientDrawable().apply { cornerRadius = 100f; setColor(Color.argb(60, 255, 255, 255)) }
            setPadding(36, 12, 36, 12)
        }
        col.addView(consoleLine, lp(18))

        // controller not connected to the TV: one tap opens the TV's pairing screen
        padBtn = button(L.s("ربط اليد بالتلفزيون", "Kolu TV'ye bağla"), false).apply {
            setOnClickListener {
                openPairing()
                android.widget.Toast.makeText(this@MainActivity, L.s("اضغط Create وPS مع بعض لين يرمش الضوء، واختر «إضافة»", "Işık yanıp sönene kadar Create ve PS tuşlarına birlikte bas, sonra «Ekle»yi seç"), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        col.addView(padBtn, lp(12))
        playBtn = button(L.s("ابدأ اللعب", "Oynamaya başla"), true).also { col.addView(it, LinearLayout.LayoutParams(560, -2).apply { topMargin = 18; gravity = Gravity.CENTER_HORIZONTAL }) }
        playBtn.setOnClickListener { if (Licensing.canPlay) play() else paywall() }

        licenseLine = text("", 14f, Color.rgb(143, 163, 199)).also { col.addView(it, lp(6)) }

        // --- one-time linking ---
        linkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        linkBox.addView(text(L.s("ربط البلايستيشن — مرة وحدة", "PlayStation'ı bağla — tek seferlik"), 22f, Color.WHITE), lp(14))
        // two ways to sign in, side by side: on the TV, or by scanning with the phone
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        choicesRow = choices
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 36f; setColor(Color.argb(28, 255, 255, 255)) }
            setPadding(40, 26, 40, 26); minimumHeight = 330
        }
        val tvCard = card()
        tvCard.addView(text(L.s("من التلفزيون", "TV'den"), 20f, Color.WHITE))
        tvCard.addView(text(L.s("سجّل دخولك هنا بالريموت", "Kumandayla burada giriş yap"), 14f, Color.rgb(143, 163, 199)), lp(4))
        val psnBtn = button(L.s("تسجيل الدخول", "Giriş yap"), true).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setPadding(30, 14, 30, 14) }
        psnBtnRef = psnBtn
        psnBtn.setOnClickListener { startActivityForResult(Intent(this, PsnLoginActivity::class.java), 41) }
        tvCard.addView(psnBtn, LinearLayout.LayoutParams(340, -2).apply { topMargin = 18; gravity = Gravity.CENTER_HORIZONTAL })
        val phoneCard = card()
        phoneCard.addView(text(L.s("من الجوال", "Telefondan"), 20f, Color.WHITE))
        phoneCard.addView(text(L.s("امسح الرمز بكاميرا جوالك", "Kodu telefonunun kamerasıyla tara"), 14f, Color.rgb(143, 163, 199)), lp(4))
        phoneCard.addView(android.widget.ImageView(this).apply { setImageBitmap(qr("https://arribha-intake.d-fifn1.workers.dev/link?s=$linkSid&lang=${L.lang}", 170)) },
            LinearLayout.LayoutParams(170, 170).apply { topMargin = 12; gravity = Gravity.CENTER_HORIZONTAL })
        choices.addView(tvCard, LinearLayout.LayoutParams(460, -2))
        choices.addView(text(L.s("أو", "veya"), 18f, Color.rgb(143, 163, 199)), LinearLayout.LayoutParams(-2, -2).apply { setMargins(30, 0, 30, 0) })
        choices.addView(phoneCard, LinearLayout.LayoutParams(460, -2))
        linkBox.addView(choices, lp(16))
        // after sign-in the two cards give way to one calm line
        signedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        signedRow.addView(text(L.s("✓ سجّلت دخولك بحساب PlayStation", "✓ PlayStation hesabınla giriş yapıldı"), 19f, Color.rgb(52, 211, 120)))
        signedRow.addView(text(L.s("تغيير الحساب", "Hesabı değiştir"), 15f, Color.rgb(120, 136, 168)).apply {
            isFocusable = true; setPadding(28, 10, 28, 10)
            setOnClickListener { store.accountId = ""; accountField.setText(""); linkStatus.text = ""; refresh(); psnBtnRef?.requestFocus() }
            setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Color.WHITE else Color.rgb(120, 136, 168)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 24 })
        linkBox.addView(signedRow, lp(18))
        // the Account ID is filled in by the sign-in; players never need to see it
        accountField = field(L.s("رقم حساب PSN (Account ID)", "PSN hesap numarası (Account ID)"), InputType.TYPE_CLASS_TEXT).also { it.visibility = View.GONE; linkBox.addView(it, wide(16)) }
        linkBox.addView(text(L.s("في البلايستيشن: الإعدادات ← النظام ← اللعب عن بُعد ← إقران الجهاز", "PlayStation'da: Ayarlar → Sistem → Uzaktan Oynatma → Cihazı Bağla"), 16f, Color.rgb(143, 163, 199)), lp(18))
        pinField = field(L.s("رقم الربط (8 أرقام)", "Bağlantı kodu (8 haneli)"), InputType.TYPE_CLASS_NUMBER).also { linkBox.addView(it, wide(8)) }
        pinField.filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        // the 8th digit links right away: no extra button press needed
        pinField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { if (e?.length == 8 && signedIn()) link() }
        })
        pinField.setOnEditorActionListener { _, _, _ -> link(); true }
        val linkBtn = button(L.s("ربط", "Bağla"), true)
        linkBtn.setOnClickListener { link() }
        linkBox.addView(linkBtn, LinearLayout.LayoutParams(420, -2).apply { topMargin = 20; gravity = Gravity.CENTER_HORIZONTAL })
        linkStatus = text("", 17f, Color.rgb(250, 205, 95)).also { linkBox.addView(it, lp(10)) }
        col.addView(linkBox)

        // --- quick settings: three small chips ---
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val tBtn = button("", false); val aBtn = button("", false); val sBtn = button("", false)
        fun refreshSettings() {
            tBtn.text = if (store.translateOn) L.s("الترجمة: تعمل", "Çeviri: Açık") else L.s("الترجمة: متوقفة", "Çeviri: Kapalı")
            aBtn.text = if (store.readFullScreen) L.s("الشاشة كاملة", "Tüm ekran") else L.s("الحوارات فقط", "Yalnızca diyaloglar")
            sBtn.text = L.s("الخط: ", "Yazı: ") + arrayOf(L.s("صغير", "Küçük"), L.s("متوسط", "Orta"), L.s("كبير", "Büyük"))[store.textSize]
        }
        tBtn.setOnClickListener { store.translateOn = !store.translateOn; refreshSettings() }
        aBtn.setOnClickListener { store.readFullScreen = !store.readFullScreen; refreshSettings() }
        sBtn.setOnClickListener { store.textSize = (store.textSize + 1) % 3; refreshSettings() }
        refreshSettings()
        for (b in listOf(tBtn, aBtn, sBtn)) settingsBox.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(10, 0, 10, 0) })
        col.addView(settingsBox, lp(16))

        // --- sponsors: three tiles, filled from sponsors.json on GitHub ---
        sponsorLabel = text(L.s("إعلان", "Reklam"), 12f, Color.rgb(96, 110, 138))
        col.addView(sponsorLabel, lp(16))
        sponsorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        col.addView(sponsorRow, lp(6))
        loadSponsors()

        // --- footer: quiet details ---
        deviceLine = text("", 13f, Color.rgb(96, 110, 138))
        col.addView(deviceLine, lp(16))
        val relink = text(L.s("إلغاء الربط", "Bağlantıyı kaldır"), 13f, Color.rgb(120, 136, 168)).apply {
            isFocusable = true; setPadding(20, 8, 20, 8)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(L.s("تبغى تلغي ربط البلايستيشن وتسجيل الدخول من هذا التلفزيون؟", "PlayStation bağlantısı ve oturum bu TV'den kaldırılsın mı?"))
                    .setPositiveButton(L.s("إلغاء الربط", "Bağlantıyı kaldır")) { _, _ ->
                        unlink()
                    }
                    .setNegativeButton(L.s("رجوع", "Geri"), null).show().focusMain()
            }
            setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Color.WHITE else Color.rgb(120, 136, 168)) }
        }
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        col.addView(footer, lp(2))
        footer.addView(relink)
        val dot1 = text("•", 13f, Color.rgb(96, 110, 138))
        footer.addView(dot1, LinearLayout.LayoutParams(-2, -2).apply { setMargins(8, 0, 8, 0) })
        linkedOnly = listOf(relink, dot1)
        footer.addView(text(L.s("Türkçe", "العربية"), 13f, Color.rgb(120, 136, 168)).apply {
            isFocusable = true; setPadding(20, 8, 20, 8)
            setOnClickListener { L.set(this@MainActivity, if (L.isTr) "ar" else "tr"); restartApp() }
            setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Color.WHITE else Color.rgb(120, 136, 168)) }
        })
        footer.addView(text("•", 13f, Color.rgb(96, 110, 138)), LinearLayout.LayoutParams(-2, -2).apply { setMargins(8, 0, 8, 0) })
        footer.addView(text(L.s("طريقة الاستخدام", "Nasıl kullanılır"), 13f, Color.rgb(120, 136, 168)).apply {
            isFocusable = true; setPadding(20, 8, 20, 8)
            setOnClickListener { startActivity(Intent(this@MainActivity, WelcomeActivity::class.java)) }
            setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Color.WHITE else Color.rgb(120, 136, 168)) }
        })
        footer.addView(text("•", 13f, Color.rgb(96, 110, 138)), LinearLayout.LayoutParams(-2, -2).apply { setMargins(8, 0, 8, 0) })
        footer.addView(text(L.s("تواصل معنا", "Bize ulaşın"), 13f, Color.rgb(120, 136, 168)).apply {
            isFocusable = true; setPadding(20, 8, 20, 8)
            setOnClickListener { contact() }
            setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Color.WHITE else Color.rgb(120, 136, 168)) }
        })
        // quiet copyright line at the very bottom
        col.addView(text("© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} Taketore  ·  " + L.s("جميع الحقوق محفوظة", "Tüm hakları saklıdır"),
            11f, Color.rgb(78, 90, 114)).apply { letterSpacing = 0.04f }, lp(10))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(16, 27, 54), Color.rgb(7, 10, 20), Color.rgb(4, 5, 10)))
            addView(col, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        })
        accountField.setText(store.accountId)

        if (store.tier < 0) {
            deviceLine.text = L.s("⏳ أفحص قوة التلفزيون...", "⏳ TV'nin gücü kontrol ediliyor...")
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
        if (req == 42) { recreate(); if (!store.welcomed) startActivity(Intent(this, WelcomeActivity::class.java)); return }
        if (req != 41) return
        val id = data?.getStringExtra(PsnLoginActivity.EXTRA_ACCOUNT_ID)
        if (res == RESULT_OK && id != null) {
            store.accountId = id; accountField.setText(id)
            linkStatus.text = L.s("✓ تم تسجيل الدخول. باقي رقم الربط من البلايستيشن", "✓ Giriş yapıldı. Şimdi PlayStation'daki bağlantı kodunu gir")
            pinField.requestFocus()
            refresh(); pinField.requestFocus()
        } else if (data != null) linkStatus.text = L.s("ما اكتمل تسجيل الدخول، جرّب مرة ثانية", "Giriş tamamlanamadı, tekrar dene")
    }

    private fun signedIn() = runCatching { Base64.decode(store.accountId.trim(), Base64.DEFAULT).size == RegistInfo.ACCOUNT_ID_SIZE }.getOrDefault(false)

    /** forget everything about this PS5 and account, and show the link screen from the start */
    private fun unlink() {
        regist?.dispose(); regist = null; linking = false; linkWhenFound = false; waitingToPlay = false
        store.registKey = null; store.morning = null; store.accountId = ""; store.consoleId = ""; store.consoleHost = ""
        accountField.setText(""); pinField.setText(""); linkStatus.text = ""
        refresh()
        (linkBox.parent?.parent as? ScrollView)?.scrollTo(0, 0)
        psnBtnRef?.requestFocus()
        android.widget.Toast.makeText(this, L.s("✓ انلغى الربط", "✓ Bağlantı kaldırıldı"), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun linkFailed(msg: String) {
        linkStatus.text = msg; pinField.setText(""); pinField.requestFocus()
    }

    private var titleViews: List<View> = emptyList()
    private var psnBtnRef: View? = null
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
    private lateinit var sponsorLabel: TextView

    private fun paywall() {
        val price = Licensing.price ?: L.s("9.99 ر.س", "")
        android.app.AlertDialog.Builder(this)
            .setTitle(L.s("فعّل ترجمان مدى الحياة", "Tercüman'ı ömür boyu etkinleştir"))
            .setMessage(L.s("انتهت تجربتك المجانية (48 ساعة).\n\nادفع مرة وحدة بس $price وتلعب مترجم مدى الحياة، مع كل التحديثات.\n\nالدفع آمن عن طريق Google Play: بالبطاقة، أو رصيد الجوال، أو بطاقات Google Play، حسب المتاح في بلدك.", "Ücretsiz denemen bitti (48 saat).\n\nYalnızca bir kez $price öde, ömür boyu çeviriyle oyna; tüm güncellemeler dahil.\n\nÖdeme Google Play üzerinden güvenle yapılır: kart, mobil bakiye veya Google Play hediye kartı (ülkene göre)."))
            .setPositiveButton(L.s("شراء $price", "Satın al $price")) { _, _ ->
                if (!Licensing.buy(this)) android.widget.Toast.makeText(this, L.s("متجر Google Play غير جاهز، جرّب بعد شوي", "Google Play hazır değil, biraz sonra tekrar dene"), android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(L.s("لاحقًا", "Sonra"), null).show().focusMain()
    }

    private fun loadSponsors() {
        Thread {
            val cfg = runCatching {
                val c = java.net.URL("https://arribha-intake.d-fifn1.workers.dev/sponsors").openConnection() as java.net.HttpURLConnection
                c.setRequestProperty("User-Agent", "arribha-app/1"); c.connectTimeout = 4000; c.readTimeout = 6000
                org.json.JSONObject(c.inputStream.bufferedReader().readText())
            }.getOrNull()
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
                        else addView(text(if (name.isNotEmpty()) name else L.s("أعلن هنا", "Reklam ver"), 15f, Color.rgb(143, 163, 199)),
                            android.widget.FrameLayout.LayoutParams(-1, -1))
                        setOnFocusChangeListener { v, f -> bg.setStroke(if (f) 4 else 0, Color.WHITE); v.animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).setDuration(120).start() }
                        setOnClickListener { if (url.startsWith("https://")) showQr(url, name) else adInfo() }
                    }
                    sponsorRow.addView(tile, LinearLayout.LayoutParams(210, 118).apply { setMargins(12, 0, 12, 0) })
                }
            }
        }.start()
    }

    /** one address for everything: questions, problems, advertising */
    private fun contact() {
        val img = android.widget.ImageView(this).apply {
            setImageBitmap(qr("mailto:$CONTACT?subject=" + android.net.Uri.encode(L.appName), 300)); setPadding(30, 20, 30, 20) }
        android.app.AlertDialog.Builder(this).setTitle(L.s("تواصل معنا", "Bize ulaşın"))
            .setMessage(L.s("مشكلة، اقتراح، أو تبغى تعلن معنا؟ امسح الرمز بجوالك أو راسلنا على:\n\n$CONTACT",
                "Sorun, öneri ya da reklam mı? Kodu telefonunla tara ya da yaz:\n\n$CONTACT"))
            .setView(img).setPositiveButton(L.s("تم", "Tamam"), null)
            .setNeutralButton(L.s("الشروط والترخيص", "Şartlar ve lisans")) { _, _ -> legal() }.show().focusMain()
    }

    /** the notices the AGPL asks every user to be able to see, plus where the terms and privacy pages live */
    private fun legal() {
        android.app.AlertDialog.Builder(this).setTitle(L.s("الشروط والترخيص", "Şartlar ve lisans"))
            .setMessage(L.s(
                """© 2026 Taketore. ترجمان مبني على مشروع Chiaki المفتوح، ومرخّص بموجب رخصة GNU AGPL الإصدار 3، ويُقدَّم «كما هو» بدون أي ضمان.

الكود المصدري: github.com/fifn1/arribha
شروط الاستخدام: arribha-intake.d-fifn1.workers.dev/terms
الخصوصية: arribha-intake.d-fifn1.workers.dev/privacy

ترجمان تطبيق مستقل وغير تابع لشركة Sony Interactive Entertainment. «PlayStation» و«PS5» علامتان تجاريتان لأصحابهما.""",
                """© 2026 Taketore. Tercüman, açık kaynaklı Chiaki projesine dayanır, GNU AGPL sürüm 3 ile lisanslanmıştır ve hiçbir garanti olmaksızın «olduğu gibi» sunulur.

Kaynak kodu: github.com/fifn1/arribha
Kullanım şartları: arribha-intake.d-fifn1.workers.dev/terms?lang=tr
Gizlilik: arribha-intake.d-fifn1.workers.dev/privacy?lang=tr

Tercüman bağımsız bir uygulamadır; Sony Interactive Entertainment ile bağlantılı değildir. «PlayStation» ve «PS5» sahiplerinin ticari markalarıdır."""))
            .setPositiveButton(L.s("تم", "Tamam"), null).show().focusMain()
    }

    /** empty ad tile: how to advertise, with a QR that opens an email on the phone */
    private fun adInfo() {
        val mail = CONTACT
        val img = android.widget.ImageView(this).apply {
            setImageBitmap(qr("mailto:$mail?subject=" + android.net.Uri.encode(L.s("إعلان في ترجمان", "Tercüman reklam")), 300)); setPadding(30, 20, 30, 20) }
        android.app.AlertDialog.Builder(this).setTitle(L.s("أعلن في ترجمان", "Tercüman'da reklam ver"))
            .setMessage(L.s(
                "يظهر إعلانك هنا لكل اللاعبين في الشاشة الرئيسية.\n\n1. امسح الرمز بجوالك أو راسلنا على:\n$mail\n2. أرسل شعارك ورابط موقعك\n3. يظهر إعلانك بعد الموافقة",
                "Reklamın ana ekranda tüm oyunculara görünür.\n\n1. Kodu telefonunla tara ya da yaz:\n$mail\n2. Logonu ve web sitenin bağlantısını gönder\n3. Onaydan sonra reklamın yayında"))
            .setView(img).setPositiveButton(L.s("تم", "Tamam"), null).show().focusMain()
    }

    /** sponsor links open on the phone: scanning is easier than a TV browser */
    private fun showQr(url: String, title: String) {
        if (url.isEmpty()) return
        val img = android.widget.ImageView(this).apply { setImageBitmap(qr(url, 360)); setPadding(30, 30, 30, 30) }
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(L.s("امسح الرمز بكاميرا جوالك", "Kodu telefonunun kamerasıyla tara")).setView(img)
            .setPositiveButton(L.s("تم", "Tamam"), null).show().focusMain()
    }

    /** a new language needs a fresh start (translator model, library and texts) */
    private fun restartApp() {
        val i = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i); finish(); Runtime.getRuntime().exit(0)
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
            if (store.isLinked || signedIn() || linkBox.visibility != View.VISIBLE) { ui.postDelayed(this, 2000); return }
            Thread {
                val j = runCatching {
                    val c = java.net.URL("https://arribha-intake.d-fifn1.workers.dev/link-poll?s=$linkSid").openConnection() as java.net.HttpURLConnection
                    c.setRequestProperty("User-Agent", "arribha-app/1"); c.connectTimeout = 4000; c.readTimeout = 6000
                    org.json.JSONObject(c.inputStream.bufferedReader().readText())
                }.getOrNull()
                val code = j?.optString("code", "") ?: ""
                // the server already checked the sign-in with Sony and sends the account id
                val id = j?.optString("id", "")?.takeIf { v -> runCatching { Base64.decode(v, Base64.DEFAULT).size == RegistInfo.ACCOUNT_ID_SIZE }.getOrDefault(false) }
                    ?: if (code.isNotEmpty()) runCatching { PsnAuth.accountId(code) }.getOrNull() else null
                ui.post {
                    if (id != null) {
                        store.accountId = id; accountField.setText(id)
                        linkStatus.text = L.s("✓ تم تسجيل الدخول من الجوال. باقي رقم الربط من البلايستيشن", "✓ Telefondan giriş yapıldı. Şimdi PlayStation'daki bağlantı kodunu gir")
                        refresh(); pinField.requestFocus()
                    } else if (code.isNotEmpty()) linkStatus.text = L.s("ما اكتمل تسجيل الدخول، جرّب مرة ثانية", "Giriş tamamlanamadı, tekrar dene")
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
        if (StreamActivity.relinkHint && store.isLinked) {
            StreamActivity.relinkHint = false
            android.app.AlertDialog.Builder(this)
                .setMessage(L.s("البلايستيشن رفض الاتصال. إذا تكرر هذا، أعد ربط التلفزيون (يصير هذا إذا تغيّر الحساب أو انحذف الربط من البلايستيشن).",
                    "PlayStation bağlantıyı reddetti. Tekrarlarsa TV'yi yeniden bağla (hesap değiştiyse veya bağlantı PlayStation'dan silindiyse olur)."))
                .setPositiveButton(L.s("إعادة الربط", "Yeniden bağla")) { _, _ -> unlink() }
                .setNegativeButton(L.s("رجوع", "Geri"), null).show().focusMain()
        }
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
        if (linkWhenFound && !store.isLinked && h.hostAddr != null) { linkWhenFound = false; link() }
    }

    private fun refresh() {
        val h = myHost()
        consoleLine.text = when {
            h == null -> L.s("● جاري البحث عن البلايستيشن", "● PlayStation aranıyor")
            !store.isLinked -> L.s("● لقيت ${h.hostName} — باقي تربطه", "● ${h.hostName} bulundu — bağlaman gerekiyor")
            h.state == DiscoveryHost.State.READY -> L.s("● ${h.hostName} جاهز", "● ${h.hostName} hazır")
            h.state == DiscoveryHost.State.STANDBY -> L.s("● ${h.hostName} في وضع الراحة", "● ${h.hostName} dinlenme modunda")
            else -> "● ${h.hostName}"
        }
        consoleLine.text = android.text.SpannableString(consoleLine.text).apply {
            if (isNotEmpty() && this[0] == '●') setSpan(android.text.style.ForegroundColorSpan(
                if (store.isLinked && h?.state == DiscoveryHost.State.READY) Color.rgb(52, 211, 120) else Color.rgb(250, 190, 70)), 0, 1, 0)
        }
        val tierName = when (store.tier) { 2 -> L.s("قوي", "Güçlü"); 1 -> L.s("متوسط", "Orta"); 0 -> L.s("ضعيف", "Zayıf"); else -> null }
        if (tierName != null) {
            val warn = if (store.tier == 0 && store.isLinked) L.s("\n⚠️ تلفزيونك ضعيف، وممكن تنزل الفريمات حتى بدون ترجمة. الترجمة بتشتغل ببطء عشان تحافظ على السلاسة.", "\n⚠️ TV'n zayıf; çeviri olmadan da kare hızı düşebilir. Akıcılığı korumak için çeviri yavaş çalışır.") else ""
            deviceLine.text = "1080p • " + L.s("60 إطارًا", "60 fps") + " • ${if (store.useHevc) "HEVC" else "H.264"} • " + L.s("جهاز $tierName", "Cihaz: $tierName") + warn
        }
        licenseLine.text = when {
            Licensing.owned -> ""
            !Licensing.trialStarted -> L.s("تجربة مجانية 48 ساعة، تبدأ مع أول لعبة", "48 saat ücretsiz deneme, ilk oyunla başlar")
            Licensing.canPlay -> L.s("تجربة مجانية: باقي ${Licensing.trialLeftSeconds / 3600} س ${(Licensing.trialLeftSeconds % 3600) / 60} د", "Ücretsiz deneme: ${Licensing.trialLeftSeconds / 3600} sa ${(Licensing.trialLeftSeconds % 3600) / 60} dk kaldı")
            else -> L.s("انتهت التجربة المجانية — فعّل ترجمان مدى الحياة", "Ücretsiz deneme bitti — Tercüman'ı ömür boyu etkinleştir")
        }
        val linked = store.isLinked
        padBtn.visibility = if (linked && !hasPad()) View.VISIBLE else View.GONE
        // the link screen shows only what linking needs
        for (v in listOf(licenseLine, sponsorLabel, sponsorRow)) v.visibility = if (linked) View.VISIBLE else View.GONE
        titleViews.forEach { it.visibility = if (linked) View.VISIBLE else View.GONE }
        linkedOnly.forEach { it.visibility = if (linked) View.VISIBLE else View.GONE }
        val signed = signedIn()
        choicesRow.visibility = if (signed) View.GONE else View.VISIBLE
        signedRow.visibility = if (signed) View.VISIBLE else View.GONE
        linkBox.visibility = if (linked) View.GONE else View.VISIBLE
        settingsBox.visibility = if (linked) View.VISIBLE else View.GONE
        playBtn.visibility = if (linked) View.VISIBLE else View.GONE
        // the cursor always waits on the next step: play, the code field, or sign-in
        val f = currentFocus
        val lost = f == null || !f.isShown
        when {
            linked -> if (lost || linkBox.findFocus() != null) playBtn.requestFocus()
            signed -> if (lost || f !== pinField && pinField.text.isEmpty() && f?.parent !== signedRow) pinField.requestFocus()
            else -> if (lost) psnBtnRef?.requestFocus()
        }
    }

    // ---------------------------------------------------------------- link

    private fun link() {
        if (linking || store.isLinked) return
        val h = myHost()
        val addr = h?.hostAddr
        if (addr == null && signedIn() && pinField.text.length == 8) {
            linkWhenFound = true
            linkStatus.text = L.s("⏳ أدوّر على البلايستيشن... تأكد إنه شغّال وعلى نفس الراوتر", "⏳ PlayStation aranıyor... açık ve aynı modemde olduğundan emin ol"); return
        }
        if (addr == null) { linkStatus.text = L.s("ما لقيت البلايستيشن في الشبكة. شغّله وتأكد إنه على نفس الراوتر.", "PlayStation ağda bulunamadı. Açık ve aynı modeme bağlı olduğundan emin ol."); return }
        val acc = try { Base64.decode(accountField.text.toString().trim(), Base64.DEFAULT) } catch (e: Exception) { null }
        if (acc == null || acc.size != RegistInfo.ACCOUNT_ID_SIZE) { linkStatus.text = L.s("سجّل دخولك أول (من التلفزيون أو الجوال)", "Önce giriş yap (TV'den veya telefondan)"); return }
        val pin = pinField.text.toString().trim()
        if (pin.length != 8 || pin.any { !it.isDigit() }) { linkStatus.text = L.s("رقم الربط لازم يكون 8 أرقام", "Bağlantı kodu 8 haneli olmalı"); return }
        store.accountId = accountField.text.toString()
        val target = if (h.isPS5) Target.PS5_1 else Target.PS4_10
        linkStatus.text = L.s("⏳ جاري الربط...", "⏳ Bağlanıyor...")
        regist?.dispose()
        linking = true
        try {
            val log = ChiakiLog(ChiakiLog.Level.WARNING.value or ChiakiLog.Level.ERROR.value)  /* INFO prints the link keys */ { _, t -> Log.i("ArribhaRegist", t) }
            regist = Regist(RegistInfo(target, addr, false, null, acc, pin.toInt()), log) { ev ->
                ui.post {
                    linking = false
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
                        is RegistEventFailed -> linkFailed(L.s("❌ ما نجح الربط. تأكد من رقم الربط (يتغير كل 5 دقايق) ورقم الحساب.", "❌ Bağlanamadı. Kodu kontrol et (her 5 dakikada bir değişir)."))
                        is RegistEventCanceled -> linkStatus.text = L.s("انلغى الربط", "Bağlantı iptal edildi")
                    }
                }
            }
        } catch (e: CreateError) {
            linking = false
            linkStatus.text = "❌ ${e.errorCode}"
        }
    }

    // ---------------------------------------------------------------- play

    private fun play() {
        val h = myHost()
        if (h != null && h.state == DiscoveryHost.State.STANDBY) {
            consoleLine.text = L.s("⏳ أصحّي البلايستيشن... (تقريباً 20 ثانية)", "⏳ PlayStation uyandırılıyor... (yaklaşık 20 saniye)")
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
