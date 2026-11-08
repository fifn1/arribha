package com.abubadr.arribha

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.ConnectedEvent
import com.metallic.chiaki.lib.CreateError
import com.metallic.chiaki.lib.DiscoveryService
import com.metallic.chiaki.lib.DiscoveryServiceOptions
import android.hardware.input.InputManager
import android.view.InputDevice
import java.net.InetSocketAddress
import com.metallic.chiaki.lib.Event
import com.metallic.chiaki.lib.LoginPinRequestEvent
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.Session
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Full-screen game stream with the Arabic translation on top. */
class StreamActivity : Activity() {
    private lateinit var store: Store
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var engine: TranslateEngine
    private val ui = Handler(Looper.getMainLooper())

    private var session: Session? = null
    private var watcher: DiscoveryService? = null
    private var gameName: String? = null
    private var surfaceReady = false
    private var lastBack = 0L
    private val input = GamepadInput { session?.setControllerState(it) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val overlayHost = FrameLayout(this)
        root.addView(overlayHost, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(48, 32, 48, 32)
            textDirection = View.TEXT_DIRECTION_RTL
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        setContentView(root)
        hideSystemBars()

        // keep the 16:9 picture shape on any screen
        root.addOnLayoutChangeListener { _, l, t, r, btm, _, _, _, _ ->
            val w = r - l; val h = btm - t
            val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
            val (vw, vh) = if (w * 9 > h * 16) Pair(h * 16 / 9, h) else Pair(w, w * 9 / 16)
            if (lp.width != vw || lp.height != vh) { lp.width = vw; lp.height = vh; surfaceView.post { surfaceView.layoutParams = lp } }
        }

        engine = TranslateEngine(surfaceView, overlayHost, store, store.tier.coerceAtLeast(0))
        engine.enabled = store.translateOn
        engine.onGuardChanged = { paused ->
            say(if (paused) "⏸ أوقفت الترجمة مؤقتاً عشان تبقى اللعبة سلسة" else "▶ رجعت الترجمة", 4000)
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceReady = true
                session?.setSurface(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                session?.setSurface(null)
            }
        })
    }

    /** HDR only when the screen shows HDR10 and the decoder plays 10-bit HEVC (Main10) at 1080p 60. */
    private fun hdrOk(): Boolean = runCatching {
        if (!store.useHevc || !store.useHdr) return false
        @Suppress("DEPRECATION")
        val caps = windowManager.defaultDisplay.hdrCapabilities ?: return false
        if (android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 !in caps.supportedHdrTypes) return false
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            !ci.isEncoder && "video/hevc" in ci.supportedTypes && ci.getCapabilitiesForType("video/hevc").let { c ->
                c.profileLevels.any { it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 } &&
                        c.videoCapabilities?.areSizeAndRateSupported(1920, 1080, 60.0) == true
            }
        }
    }.getOrDefault(false)

    /** Keeps Wi-Fi out of power saving while playing: power saving is what drops video packets and blurs the picture. */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun holdWifi(on: Boolean) {
        if (on) {
            if (wifiLock?.isHeld == true) return
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return
            @Suppress("DEPRECATION")
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "arribha:game").apply { setReferenceCounted(false); acquire() }
            Log.i("Arribha", "wifi game mode on (mode $mode)")
        } else {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        holdWifi(true)
        connect()
    }

    override fun onPause() {
        super.onPause()
        engine.stop()
        disconnect()
        holdWifi(false)
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }

    private fun connect() {
        if (session != null) return
        val regKey = store.registKey ?: return finish()
        val morning = store.morning ?: return finish()
        // always 1080p at 60 fps: the highest quality PlayStation Remote Play sends
        val preset = ConnectVideoProfile.preset(
            VideoResolutionPreset.RES_1080P, VideoFPSPreset.FPS_60,
            when { hdrOk() -> Codec.CODEC_H265_HDR; store.useHevc -> Codec.CODEC_H265; else -> Codec.CODEC_H264 })
        // full Remote Play bitrate for 1080p (15 Mbit/s): picture quality is never lowered
        val profile = ConnectVideoProfile(preset.width, preset.height, preset.maxFPS,
            max(preset.bitrate, store.bitrate), preset.codec)
        Log.i("Arribha", "video ${profile.width}x${profile.height}@${profile.maxFPS} ${profile.bitrate}kbps ${profile.codec}")
        val info = ConnectInfo(store.isPS5, store.consoleHost, regKey, morning, profile)
        say("⏳ جاري الاتصال بالبلايستيشن...")
        try {
            val s = Session(info, File(filesDir, "session.log").absolutePath, false)
            s.eventCallback = { e -> ui.post { onEvent(e) } }
            s.start()
            if (surfaceReady) s.setSurface(surfaceView.holder.surface)
            session = s
        } catch (e: CreateError) {
            say("❌ تعذر بدء الاتصال: ${e.errorCode}")
            health(false, "create: ${e.errorCode}")
        }
    }

    /** Asks the PlayStation every 2 s what is running, so we translate inside games only. */
    private fun watchGame() {
        if (watcher != null) return
        try {
            watcher = DiscoveryService(
                DiscoveryServiceOptions(4U, 3U, 2000U, InetSocketAddress(store.consoleHost, 987))
            ) { hosts ->
                val h = hosts.firstOrNull { it.hostId == store.consoleId } ?: hosts.firstOrNull { it.hostAddr == store.consoleHost }
                val title = h?.runningAppName?.takeIf { !h.runningAppTitleid.isNullOrBlank() }
                ui.post { onGameChanged(title) }
            }
        } catch (e: CreateError) {
            Log.e("Arribha", "game watcher failed", e)
            engine.inGame = true // cannot tell: translate everywhere rather than never
        }
    }

    private fun onGameChanged(title: String?) {
        if (title == gameName) return
        gameName = title
        // PS5 does not report the running game, so reading stays on; menus are filtered by language and style
        engine.inGame = true
        Log.i("Arribha", "running game: $title")
        if (title != null && engine.enabled) say("🎮 $title — الترجمة شغالة", 4000)
    }

    private fun hasGamepad(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { if (hasGamepad()) say("🎮 اليدة اتصلت ✅", 3000) }
        override fun onInputDeviceRemoved(id: Int) { if (!hasGamepad()) say("🎮 اليدة انفصلت عن التلفزيون: اضغط زر PS فيها", 8000) }
        override fun onInputDeviceChanged(id: Int) {}
    }

    override fun onStart() {
        super.onStart()
        getSystemService(InputManager::class.java).registerInputDeviceListener(deviceListener, ui)
    }

    override fun onStop() {
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(deviceListener)
        super.onStop()
    }

    /** Anonymous alarm signal: did connecting work with this PlayStation version? (successes: once a day) */
    private fun health(ok: Boolean, reason: String?) {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (ok && store.healthDay == day) return
        if (ok) store.healthDay = day
        val app = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val body = org.json.JSONObject().put("ok", ok).put("fw", store.consoleFw.ifEmpty { "?" }).put("app", app)
        if (reason != null) body.put("reason", reason)
        Thread {
            runCatching {
                val c = java.net.URL("https://arribha-intake.d-fifn1.workers.dev/health").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 4000; c.readTimeout = 6000
                c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "arribha-app/1")
                c.outputStream.use { it.write(body.toString().toByteArray()) }; c.responseCode
            }
        }.start()
    }

    /** trial clock: one minute of real play at a time */
    private val playClock = object : Runnable {
        override fun run() {
            if (session == null) return
            Licensing.addPlayed(60)
            if (!Licensing.canPlay) say("⏳ انتهت التجربة المجانية. كمّل جولتك، وبعدها فعّل عرّبها مدى الحياة من الشاشة الرئيسية", 15000)
            ui.postDelayed(this, 60_000)
        }
    }

    private fun disconnect() {
        ui.removeCallbacks(playClock)
        watcher?.dispose(); watcher = null; gameName = null
        val s = session ?: return
        session = null
        s.stop()
        s.dispose()
    }

    private fun onEvent(e: Event) {
        when (e) {
            is ConnectedEvent -> {
                ui.removeCallbacks(playClock); ui.postDelayed(playClock, 60_000)
                health(true, null)
                if (!hasGamepad()) say("🎮 اليدة مو موصولة بالتلفزيون: اضغط زر PS في اليدة. إذا ما اشتغلت، اربطها بالتلفزيون (Create + PS)", 12000)
                else say(if (engine.enabled) "✅ متصل — الترجمة تشتغل داخل الألعاب بس (OK بالريموت يشغّلها ويطفيها)" else "✅ متصل — الترجمة طافية", 5000)
                engine.inGame = true
                engine.start()
                watchGame()
            }
            is QuitEvent -> {
                if (e.reason.isError) health(false, e.reasonString ?: e.reason.toString())
                engine.stop()
                Log.w("Arribha", "quit: ${e.reason} ${e.reasonString}")
                val msg = if (e.reason.isError) "❌ انقطع الاتصال: ${e.reasonString ?: e.reason}" else "انتهى اللعب"
                say(msg)
                disconnect()
                ui.postDelayed({ finish() }, 3000)
            }
            is LoginPinRequestEvent -> askLoginPin(e.pinIncorrect)
            else -> {}
        }
    }

    private fun askLoginPin(wrong: Boolean) {
        val field = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle(if (wrong) "الرمز غلط، جرّب مرة ثانية" else "رمز الدخول لحسابك في البلايستيشن")
            .setView(field)
            .setPositiveButton("دخول") { _, _ -> session?.setLoginPin(field.text.toString()) }
            .setNegativeButton("إلغاء") { _, _ -> finish() }
            .show()
    }

    // ---- input: controller goes to the game, TV remote controls the app ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (GamepadInput.isGamepad(event) && input.onKey(event)) return true
        if (event.action != KeyEvent.ACTION_UP) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                engine.enabled = !engine.enabled
                store.translateOn = engine.enabled
                say(if (engine.enabled) "🌐 الترجمة شغالة" else "🌐 الترجمة طافية", 2500)
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val d = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1
                store.textSize = (store.textSize + d).coerceIn(0, 2)
                say("🔠 حجم الخط: " + arrayOf("صغير", "متوسط", "كبير")[store.textSize], 2000)
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                store.readFullScreen = !store.readFullScreen
                say(if (store.readFullScreen) "📺 يقرأ الشاشة كاملة" else "💬 يقرأ أسفل الشاشة (الحوارات)", 2500)
            }
            KeyEvent.KEYCODE_BACK -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastBack < 2500) finish() else say("اضغط رجوع مرة ثانية للخروج", 2500)
                lastBack = now
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent) = input.onMotion(event) || super.onGenericMotionEvent(event)

    private fun say(text: String, hideAfter: Long = 0) {
        status.text = text
        status.visibility = View.VISIBLE
        ui.removeCallbacksAndMessages("say")
        if (hideAfter > 0) ui.postAtTime({ status.visibility = View.GONE }, "say", SystemClock.uptimeMillis() + hideAfter)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }
}
