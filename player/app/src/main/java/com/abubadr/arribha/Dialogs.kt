package com.abubadr.arribha

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** On a TV there is no touch: put the remote's focus on the main button and make focus clearly visible. */
fun AlertDialog.focusMain(): AlertDialog {
    val main = getButton(AlertDialog.BUTTON_POSITIVE) ?: return this
    for (b in listOfNotNull(main, getButton(AlertDialog.BUTTON_NEGATIVE))) {
        val bg = GradientDrawable().apply { cornerRadius = 40f; setColor(Color.TRANSPARENT) }
        b.background = bg
        b.setOnFocusChangeListener { _, f -> bg.setColor(if (f) Color.argb(60, 47, 107, 255) else Color.TRANSPARENT); bg.setStroke(if (f) 3 else 0, Color.rgb(47, 107, 255)) }
    }
    main.isFocusableInTouchMode = true
    main.requestFocus()
    return this
}
