package com.devdayton.onecodeforai.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import com.devdayton.onecodeforai.android.PluginSettings

object CodeAssistTheme {

    const val THEME_SYSTEM = "system"

    const val THEME_LIGHT = "light"

    const val THEME_DARK = "dark"

    private interface Palette {
        val BG: Int
        val SURFACE: Int
        val SURFACE_RAISED: Int
        val OUTLINE: Int
        val TEXT_PRIMARY: Int
        val TEXT_SECONDARY: Int
        val TEXT_MUTED: Int
        val ACCENT: Int
        val ACCENT_PRESSED: Int
        val TAG_INCLUDE: Int
        val TAG_INCLUDE_TEXT: Int
        val TAG_EXCLUDE: Int
        val TAG_EXCLUDE_TEXT: Int
        val TAG_CONFLICT: Int
        val TAG_CONFLICT_TEXT: Int
    }

    private object DarkPalette : Palette {
        override val BG = Color.parseColor("#171719")
        override val SURFACE = Color.parseColor("#202024")
        override val SURFACE_RAISED = Color.parseColor("#26262B")
        override val OUTLINE = Color.parseColor("#36363D")
        override val TEXT_PRIMARY = Color.parseColor("#E7E7EA")
        override val TEXT_SECONDARY = Color.parseColor("#9999A2")
        override val TEXT_MUTED = Color.parseColor("#707079")
        override val ACCENT = Color.parseColor("#D99460")
        override val ACCENT_PRESSED = Color.parseColor("#B97849")
        override val TAG_INCLUDE = Color.parseColor("#253D35")
        override val TAG_INCLUDE_TEXT = Color.parseColor("#A8D7C2")
        override val TAG_EXCLUDE = Color.parseColor("#402B2D")
        override val TAG_EXCLUDE_TEXT = Color.parseColor("#E1A7A7")
        override val TAG_CONFLICT = Color.parseColor("#443820")
        override val TAG_CONFLICT_TEXT = Color.parseColor("#E5C27F")
    }

    private object LightPalette : Palette {
        override val BG = Color.parseColor("#F3F3F5")
        override val SURFACE = Color.parseColor("#FFFFFF")
        override val SURFACE_RAISED = Color.parseColor("#EBEBEF")
        override val OUTLINE = Color.parseColor("#D1D1D8")
        override val TEXT_PRIMARY = Color.parseColor("#1A1A1E")
        override val TEXT_SECONDARY = Color.parseColor("#5A5A64")
        override val TEXT_MUTED = Color.parseColor("#8A8A94")
        override val ACCENT = Color.parseColor("#A85826")
        override val ACCENT_PRESSED = Color.parseColor("#853F17")
        override val TAG_INCLUDE = Color.parseColor("#D9EFE3")
        override val TAG_INCLUDE_TEXT = Color.parseColor("#1F5F3F")
        override val TAG_EXCLUDE = Color.parseColor("#F6DDDD")
        override val TAG_EXCLUDE_TEXT = Color.parseColor("#7A2828")
        override val TAG_CONFLICT = Color.parseColor("#F3E5C8")
        override val TAG_CONFLICT_TEXT = Color.parseColor("#5C4018")
    }

    @Volatile
    private var activePalette: Palette = DarkPalette

    fun applyTheme(context: Context) {
        activePalette = when (PluginSettings.getUiTheme(context)) {

            THEME_LIGHT -> LightPalette

            THEME_DARK -> DarkPalette

            else -> if (isSystemDark(context)) DarkPalette else LightPalette
        }
    }

    fun isDark(): Boolean = activePalette === DarkPalette

    private fun isSystemDark(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    val BG: Int get() = activePalette.BG

    val SURFACE: Int get() = activePalette.SURFACE

    val SURFACE_RAISED: Int get() = activePalette.SURFACE_RAISED

    val OUTLINE: Int get() = activePalette.OUTLINE

    val TEXT_PRIMARY: Int get() = activePalette.TEXT_PRIMARY

    val TEXT_SECONDARY: Int get() = activePalette.TEXT_SECONDARY

    val TEXT_MUTED: Int get() = activePalette.TEXT_MUTED

    val ACCENT: Int get() = activePalette.ACCENT

    val ACCENT_PRESSED: Int get() = activePalette.ACCENT_PRESSED

    val TAG_INCLUDE: Int get() = activePalette.TAG_INCLUDE

    val TAG_INCLUDE_TEXT: Int get() = activePalette.TAG_INCLUDE_TEXT

    val TAG_EXCLUDE: Int get() = activePalette.TAG_EXCLUDE

    val TAG_EXCLUDE_TEXT: Int get() = activePalette.TAG_EXCLUDE_TEXT

    val TAG_CONFLICT: Int get() = activePalette.TAG_CONFLICT

    val TAG_CONFLICT_TEXT: Int get() = activePalette.TAG_CONFLICT_TEXT

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun roundedBg(
        context: Context,
        color: Int,
        radiusDp: Float = 10f,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 1f
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            if (strokeColor != null) {
                setStroke(
                    (strokeWidthDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    strokeColor
                )
            }
        }
    }

    fun selectableBg(
        context: Context,
        normalColor: Int = SURFACE,
        pressedColor: Int = SURFACE_RAISED,
        radiusDp: Float = 9f,
        strokeColor: Int? = OUTLINE
    ): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedBg(context, pressedColor, radiusDp, strokeColor)
            )
            addState(
                intArrayOf(),
                roundedBg(context, normalColor, radiusDp, strokeColor)
            )
        }
    }
}

fun Int.withAlpha(alpha: Int): Int =
    Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))