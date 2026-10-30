package com.devdayton.onecodeforai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object UiFactory {
    fun Context.textView(
        text: String,
        size: Float,
        color: Int,
        isBold: Boolean = false
    ) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        typeface = Typeface.create(
            if (isBold) "sans-serif-medium" else "sans-serif",
            Typeface.NORMAL
        )
        includeFontPadding = false
    }

    fun Context.sectionTitle(text: String) = textView(
        text.uppercase(),
        11f,
        CodeAssistTheme.TEXT_SECONDARY,
        true
    ).apply {
        setPadding(0, CodeAssistTheme.dp(this@sectionTitle, 18), 0, CodeAssistTheme.dp(this@sectionTitle, 9))
    }

    fun Context.checkBox(text: String) = CheckBox(this).apply {
        this.text = text
        setTextColor(CodeAssistTheme.TEXT_PRIMARY)
        textSize = 13f
        buttonTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
        minHeight = CodeAssistTheme.dp(this@checkBox, 42)
        setPadding(0, 0, 0, 0)
    }

    fun Context.editText(
        initial: String,
        type: Int = InputType.TYPE_CLASS_TEXT
    ) = EditText(this).apply {
        setText(initial)
        inputType = type
        setTextColor(CodeAssistTheme.TEXT_PRIMARY)
        setHintTextColor(CodeAssistTheme.TEXT_MUTED)
        textSize = 13f
        isSingleLine = true // 🔥 Corregido
        background = CodeAssistTheme.roundedBg(
            this@editText,
            CodeAssistTheme.SURFACE_RAISED,
            8f,
            CodeAssistTheme.OUTLINE
        )
        setPadding(
            CodeAssistTheme.dp(this@editText, 12),
            CodeAssistTheme.dp(this@editText, 10),
            CodeAssistTheme.dp(this@editText, 12),
            CodeAssistTheme.dp(this@editText, 10)
        )
        minHeight = CodeAssistTheme.dp(this@editText, 42)
    }

    fun Context.iconButton(
        iconRes: Int,
        contentDescriptionText: String = "Acción",
        onClick: () -> Unit
    ) = ImageButton(this).apply {
        setImageResource(iconRes)
        contentDescription = contentDescriptionText
        background = CodeAssistTheme.selectableBg(this@iconButton)
        setColorFilter(CodeAssistTheme.TEXT_SECONDARY)
        scaleType = ImageView.ScaleType.CENTER // 🔥 Corregido
        setPadding(
            CodeAssistTheme.dp(this@iconButton, 10),
            CodeAssistTheme.dp(this@iconButton, 10),
            CodeAssistTheme.dp(this@iconButton, 10),
            CodeAssistTheme.dp(this@iconButton, 10)
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            CodeAssistTheme.dp(this@iconButton, 40),
            CodeAssistTheme.dp(this@iconButton, 40)
        )
    }

    fun Context.actionText(
        text: String,
        onClick: () -> Unit
    ) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(CodeAssistTheme.ACCENT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = CodeAssistTheme.selectableBg(
            this@actionText,
            normalColor = Color.TRANSPARENT,
            pressedColor = CodeAssistTheme.SURFACE_RAISED,
            radiusDp = 7f,
            strokeColor = null
        )
        setPadding(
            CodeAssistTheme.dp(this@actionText, 10),
            CodeAssistTheme.dp(this@actionText, 8),
            CodeAssistTheme.dp(this@actionText, 10),
            CodeAssistTheme.dp(this@actionText, 8)
        )
        setOnClickListener { onClick() }
    }

    /** Spinner nativo con filas oscuras; no necesita dependencias Material. */
    fun Context.spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items
        ) {
            private fun row(position: Int, convertView: View?, dropdown: Boolean): View {
                val label = (convertView as? TextView) ?: TextView(context)
                label.text = getItem(position).orEmpty()
                label.setTextColor(CodeAssistTheme.TEXT_PRIMARY)
                label.textSize = 13f
                label.gravity = Gravity.CENTER_VERTICAL
                label.setPadding(
                    CodeAssistTheme.dp(context, 12),
                    CodeAssistTheme.dp(context, if (dropdown) 11 else 9),
                    CodeAssistTheme.dp(context, 12),
                    CodeAssistTheme.dp(context, if (dropdown) 11 else 9)
                )
                label.minHeight = CodeAssistTheme.dp(context, 40)
                if (dropdown) {
                    label.setBackgroundColor(CodeAssistTheme.SURFACE_RAISED)
                }
                return label
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                row(position, convertView, false)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                row(position, convertView, true)
        }
    }
}