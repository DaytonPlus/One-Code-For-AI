package com.devdayton.onecodeforai.ui

import com.devdayton.onecodeforai.android.LanguageHelper
import com.devdayton.onecodeforai.ui.CodeAssistTheme

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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

    // Paquete del plugin. Coincide con module.toml -> [android].namespace
    private const val PLUGIN_PACKAGE = "com.devdayton.onecodeforai"

    private var pluginResourcesCache: Resources? = null

    /**
     * Devuelve el Resources del APK del plugin. Necesario porque las vistas se crean
     * con el Activity de CodeAssist (contexto anfitrión) y sus recursos no contienen
     * los drawables del plugin.
     */
    fun Context.pluginResources(): Resources? {
        pluginResourcesCache?.let { return it }
        return try {
            val res = packageManager.getResourcesForApplication(PLUGIN_PACKAGE)
            pluginResourcesCache = res
            res
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Carga un drawable del plugin sin importar con qué Context se construyó la vista.
     * Si el ID es del framework (p. ej. android.R.drawable.*), hace fallback al
     * Resources del propio contexto.
     */
    fun Context.loadPluginDrawable(resId: Int): Drawable? {
        pluginResources()?.let { res ->
            try {
                return res.getDrawable(resId, null)
            } catch (_: Exception) {
                // cae al fallback
            }
        }
        return try { getDrawable(resId) } catch (_: Exception) { null }
    }

    /**
     * Resuelve un string del APK del plugin aplicando el locale del IDE host.
     *
     * Necesario porque las vistas del ConfigurationDialog se construyen con el
     * Context de CodeAssist (contexto anfitrión), cuyos Resources no conocen
     * los IDs del plugin. Además aplica el locale que tenga configurado el IDE,
     * para que si el usuario cambia el idioma de CodeAssist el plugin lo siga.
     */
    fun Context.pluginString(resId: Int): String {
        return try {
            val pluginCtx = createPackageContext(PLUGIN_PACKAGE, 0)
            val locale = LanguageHelper.detectIdeLocale(this)
            val config = Configuration(pluginCtx.resources.configuration)
            config.setLocale(locale)
            pluginCtx.createConfigurationContext(config).getString(resId)
        } catch (_: Exception) {
            // Último recurso: intentar con el contexto actual.
            // Funciona si el Context ya pertenece al plugin (p. ej. PluginInfoActivity).
            runCatching { getString(resId) }.getOrDefault("")
        }
    }

    /**
     * Igual que [pluginString] pero con argumentos de formato (como getString(id, args)).
     */
    fun Context.pluginString(resId: Int, vararg args: Any): String =
        String.format(pluginString(resId), *args)

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
        isSingleLine = true
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
    ): ImageButton {
        val button = ImageButton(this)
        // Cargar el drawable desde el Resources del plugin, no desde el contexto actual.
        val drawable = loadPluginDrawable(iconRes)
        if (drawable != null) {
            button.setImageDrawable(drawable)
        } else {
            runCatching { button.setImageResource(iconRes) }
        }
        button.contentDescription = contentDescriptionText
        button.background = CodeAssistTheme.selectableBg(this)
        button.setColorFilter(CodeAssistTheme.TEXT_SECONDARY)
        button.scaleType = ImageView.ScaleType.CENTER
        button.setPadding(
            CodeAssistTheme.dp(this, 10),
            CodeAssistTheme.dp(this, 10),
            CodeAssistTheme.dp(this, 10),
            CodeAssistTheme.dp(this, 10)
        )
        button.setOnClickListener { onClick() }
        button.layoutParams = LinearLayout.LayoutParams(
            CodeAssistTheme.dp(this, 40),
            CodeAssistTheme.dp(this, 40)
        )
        return button
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
