package com.abubadr.arribha

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** First start: Arabic or Turkish. The TV's own language is pre-selected; can be changed later from the home screen. */
class LanguageActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        L.init(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(16, 27, 54), Color.rgb(7, 10, 20), Color.rgb(4, 5, 10)))
        }
        col.addView(text("ترجمان  |  Tercüman", 48f, Color.WHITE, true))
        col.addView(text("اختر لغة الترجمة  •  Çeviri dilini seç", 20f, Color.rgb(143, 163, 199), false), lp(10))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val ar = choice("العربية", "ترجمة الألعاب للعربي")
        val tr = choice("Türkçe", "Oyunları Türkçeye çevir")
        ar.setOnClickListener { pick("ar") }
        tr.setOnClickListener { pick("tr") }
        row.addView(ar, LinearLayout.LayoutParams(500, 200).apply { setMargins(24, 0, 24, 0) })
        row.addView(tr, LinearLayout.LayoutParams(500, 200).apply { setMargins(24, 0, 24, 0) })
        col.addView(row, lp(40))
        setContentView(col)
        (if (L.isTr) tr else ar).requestFocus()
    }

    private fun pick(l: String) {
        L.set(this, l)
        setResult(RESULT_OK)
        finish()
    }

    private fun choice(title: String, sub: String) = Button(this).apply {
        text = "$title\n$sub"; isAllCaps = false; stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); setTextColor(Color.WHITE)
        val bg = GradientDrawable().apply { cornerRadius = 36f; setColor(Color.argb(30, 255, 255, 255)) }
        background = bg
        setOnFocusChangeListener { v, f ->
            bg.setColor(if (f) Color.rgb(47, 107, 255) else Color.argb(30, 255, 255, 255))
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(140).start()
        }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = top; gravity = Gravity.CENTER_HORIZONTAL }
}
