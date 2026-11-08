package com.abubadr.arribha

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** First-run guide: everything the player sets once, in short simple steps (for ages 10+). */
class WelcomeActivity : Activity() {
    // order matters: everything that needs the controller on the PlayStation comes first,
    // the controller moves to the TV last, so ONE controller is enough
    private val steps = listOf(
        "جهّز البلايستيشن (باليد)" to "الإعدادات ← النظام ← اللعب عن بُعد ← شغّل «تمكين اللعب عن بُعد». ومن «توفير الطاقة ← الميزات في وضع الراحة» شغّل «البقاء متصلًا بالإنترنت» و«تشغيل PS5 من الشبكة»",
        "نفس الشبكة" to "البلايستيشن والتلفزيون على نفس الراوتر. الأفضل: البلايستيشن بكيبل والتلفزيون واي فاي 5G",
        "اربط عرّبها (بالريموت)" to "سجّل دخولك في عرّبها، وبعدها في البلايستيشن: اللعب عن بُعد ← إقران الجهاز، واكتب الرقم في عرّبها",
        "انقل اليد للتلفزيون" to "اضغط زر Create وزر PS مع بعض لين يرمش الضوء، وفي عرّبها اختر «ربط اليد». يد وحدة تكفي: عرّبها يشغّل البلايستيشن ويتحكم فيه",
        "شغّل الترجمة في اللعبة" to "داخل اللعبة: الإعدادات ← الترجمة (Subtitles) ← تشغيل. عرّبها يحوّلها للعربي",
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(140, 22, 140, 22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(16, 27, 54), Color.rgb(7, 10, 20), Color.rgb(4, 5, 10)))
        }
        col.addView(text("أهلًا بك في عرّبها", 40f, Color.WHITE, true))
        col.addView(text("خمس خطوات مرة وحدة بس، ويكفيك يد وحدة", 18f, Color.rgb(143, 163, 199), false), lp(6))
        for ((i, s) in steps.withIndex()) col.addView(row(i + 1, s.first, s.second), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 })
        val go = Button(this).apply {
            text = "يلا نبدأ"; isAllCaps = false; stateListAnimator = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(Color.rgb(47, 107, 255), Color.rgb(0, 166, 255))).apply { cornerRadius = 100f }
            setPadding(60, 20, 60, 20)
            setOnClickListener { Store(this@WelcomeActivity).welcomed = true; finish() }
        }
        col.addView(go, LinearLayout.LayoutParams(420, -2).apply { topMargin = 16; gravity = Gravity.CENTER_HORIZONTAL })
        setContentView(col)
        go.requestFocus()
    }

    private fun row(n: Int, title: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = 28f; setColor(Color.argb(26, 255, 255, 255)) }
        setPadding(26, 16, 26, 16)
        addView(TextView(context).apply {
            text = n.toString(); gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(47, 107, 255)) }
        }, LinearLayout.LayoutParams(56, 56))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 19f, Color.WHITE, true).apply { gravity = Gravity.START })
            addView(text(body, 15f, Color.rgb(170, 185, 210), false).apply { gravity = Gravity.START })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 22 })
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); gravity = Gravity.CENTER
        textDirection = View.TEXT_DIRECTION_RTL
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = top; gravity = Gravity.CENTER_HORIZONTAL }
}
