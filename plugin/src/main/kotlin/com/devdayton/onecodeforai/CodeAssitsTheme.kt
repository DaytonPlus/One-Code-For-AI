package com.devdayton.onecodeforai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object CodeAssistTheme {
    val BG = Color.parseColor("#1C1B1F")
    val SURFACE = Color.parseColor("#2B2930")
    val OUTLINE = Color.parseColor("#49454F")
    val TEXT_PRIMARY = Color.parseColor("#E6E1E5")
    val TEXT_SECONDARY = Color.parseColor("#CAC4D0")
    val ACCENT = Color.parseColor("#E50914")
    
    val TAG_INCLUDE = Color.parseColor("#2E7D32") // Verde
    val TAG_EXCLUDE = Color.parseColor("#C62828") // Rojo
    val TAG_CONFLICT = Color.parseColor("#F9A825") // Amarillo

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun roundedBg(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * 3f // Ajuste base de densidad
            if (strokeColor != null) setStroke(1, strokeColor)
        }
    }
}