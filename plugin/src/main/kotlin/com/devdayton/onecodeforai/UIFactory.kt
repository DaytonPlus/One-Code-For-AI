package com.devdayton.onecodeforai

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

object UiFactory {
    fun Context.textView(text: String, size: Float, color: Int, isBold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        if (isBold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    fun Context.sectionTitle(text: String) = textView(text, 13f, CodeAssistTheme.TEXT_SECONDARY).apply {
        setPadding(0, CodeAssistTheme.dp(this@sectionTitle, 16), 0, CodeAssistTheme.dp(this@sectionTitle, 8))
    }

    fun Context.checkBox(text: String) = CheckBox(this).apply {
        this.text = text
        setTextColor(CodeAssistTheme.TEXT_PRIMARY)
        textSize = 13f
    }

    fun Context.editText(initial: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        setText(initial)
        inputType = type
        setTextColor(android.graphics.Color.WHITE)
        setHintTextColor(android.graphics.Color.GRAY)
        textSize = 13f
        background = CodeAssistTheme.roundedBg(CodeAssistTheme.SURFACE, 8f, CodeAssistTheme.OUTLINE)
        setPadding(CodeAssistTheme.dp(this@editText, 20), CodeAssistTheme.dp(this@editText, 12), CodeAssistTheme.dp(this@editText, 20), CodeAssistTheme.dp(this@editText, 12))
    }

    fun Context.iconButton(iconRes: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(iconRes)
        background = CodeAssistTheme.roundedBg(CodeAssistTheme.SURFACE, 8f, CodeAssistTheme.OUTLINE)
        setColorFilter(android.graphics.Color.WHITE)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(CodeAssistTheme.dp(this@iconButton, 40), CodeAssistTheme.dp(this@iconButton, 40))
    }
}