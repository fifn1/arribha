package com.abubadr.arribha

import android.content.Context
import android.view.View
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * The player's language: Arabic or Turkish. Chosen on first start (the TV's own language is pre-selected)
 * and used for the whole interface and for the in-game translation.
 */
object L {
    @Volatile var lang = "ar"; private set
    val isTr get() = lang == "tr"

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("arribha", Context.MODE_PRIVATE)
        lang = p.getString("lang", null) ?: if (Locale.getDefault().language == "tr") "tr" else "ar"
    }

    fun chosen(ctx: Context) = ctx.getSharedPreferences("arribha", Context.MODE_PRIVATE).contains("lang")

    fun set(ctx: Context, l: String) {
        lang = l
        ctx.getSharedPreferences("arribha", Context.MODE_PRIVATE).edit().putString("lang", l).apply()
    }

    /** pick the text for the current language */
    fun s(ar: String, tr: String) = if (isTr) tr else ar

    val appName get() = s("ترجمان", "Tercüman")
    val dir get() = if (isTr) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
    val textDir get() = if (isTr) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_RTL
    val mlkitTarget get() = if (isTr) TranslateLanguage.TURKISH else TranslateLanguage.ARABIC
}
