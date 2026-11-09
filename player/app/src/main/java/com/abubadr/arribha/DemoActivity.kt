package com.abubadr.arribha

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Demo without a PlayStation (for store reviewers and curious players): a sample scene where English
 * subtitles appear and the translation replaces them in the same soft style as in real play.
 */
class DemoActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val lines = listOf(
        Triple("Mia: Meet me at the docks before sunset.", "ميا: قابلني عند الرصيف قبل الغروب.", "Mia: Gün batmadan iskelede buluşalım."),
        Triple("Press ✕ to jump", "اضغط ✕ للقفز", "Zıplamak için ✕ tuşuna bas"),
        Triple("Mia: We have to reach the north gate before nightfall.", "ميا: لازم نوصل البوابة الشمالية قبل ما يطيح الليل.", "Mia: Gece çökmeden kuzey kapısına ulaşmalıyız."),
        Triple("New Objective: Find the old lighthouse", "هدف جديد: اعثر على المنارة القديمة", "Yeni hedef: Eski deniz fenerini bul"),
    )
    private var i = 0
    private lateinit var en: TextView
    private lateinit var tr: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        L.init(this)
        val root = FrameLayout(this)
        root.addView(ImageView(this).apply { setImageResource(R.drawable.demo_scene); scaleType = ImageView.ScaleType.CENTER_CROP })
        root.addView(TextView(this).apply {
            text = L.s("عرض تجريبي • بدون بلايستيشن — اضغط رجوع للخروج", "Deneme gösterimi • PlayStation olmadan — çıkmak için Geri'ye bas")
            setTextColor(Color.argb(220, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(28, 12, 28, 12)
            background = GradientDrawable().apply { cornerRadius = 40f; setColor(Color.argb(120, 0, 0, 0)) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 40 })
        en = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        root.addView(en, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 90 })
        tr = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); gravity = Gravity.CENTER
            textDirection = L.textDir; setShadowLayer(8f, 0f, 2f, Color.argb(200, 0, 0, 0))
            setPadding(70, 18, 70, 20); alpha = 0f
            // same look as the real overlay: a soft dark haze that fades at its edges
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(0, 12, 16, 28), Color.argb(235, 12, 16, 28), Color.argb(235, 12, 16, 28), Color.argb(0, 12, 16, 28))).apply { cornerRadius = 30f }
        }
        root.addView(tr, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 78 })
        setContentView(root)
        step()
    }

    /** English appears, then the translation covers it, then the next line */
    private fun step() {
        val (e, ar, t) = lines[i % lines.size]; i++
        tr.animate().alpha(0f).setDuration(200).start()
        en.text = e; en.animate().alpha(1f).setDuration(200).start()
        ui.postDelayed({
            tr.text = if (L.isTr) t else ar
            tr.animate().alpha(1f).setDuration(250).start()
            en.animate().alpha(0f).setDuration(250).start()      // the translation takes the English line's place
        }, 1200)
        ui.postDelayed(::step, 4500)
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }
}
