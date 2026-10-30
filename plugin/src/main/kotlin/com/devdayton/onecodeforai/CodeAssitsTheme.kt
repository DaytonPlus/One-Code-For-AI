package com.devdayton.onecodeforai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * Paleta visual alineada con la interfaz oscura de CodeAssist.
 * Los radios y grosores se expresan en dp y se convierten a px aquí.
 */
object CodeAssistTheme {
    val BG = Color.parseColor("#171719")
    val SURFACE = Color.parseColor("#202024")
    val SURFACE_RAISED = Color.parseColor("#26262B")
    val OUTLINE = Color.parseColor("#36363D")
    val TEXT_PRIMARY = Color.parseColor("#E7E7EA")
    val TEXT_SECONDARY = Color.parseColor("#9999A2")
    val TEXT_MUTED = Color.parseColor("#707079")

    // Naranja suave, cercano al color de acción de la captura.
    val ACCENT = Color.parseColor("#D99460")
    val ACCENT_PRESSED = Color.parseColor("#B97849")

    val TAG_INCLUDE = Color.parseColor("#253D35")
    val TAG_INCLUDE_TEXT = Color.parseColor("#A8D7C2")
    val TAG_EXCLUDE = Color.parseColor("#402B2D")
    val TAG_EXCLUDE_TEXT = Color.parseColor("#E1A7A7")
    val TAG_CONFLICT = Color.parseColor("#443820")
    val TAG_CONFLICT_TEXT = Color.parseColor("#E5C27F")

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
