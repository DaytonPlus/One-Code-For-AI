package com.devdayton.onecodeforai.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import com.devdayton.onecodeforai.android.PluginSettings

/**
 * Centralized color palette and drawable factory for the plugin's UI.
 *
 * ### Theming
 *
 * The palette supports a dark and a light variant. The active variant is
 * resolved once per UI entry point by [applyTheme] and stored in
 * [activePalette]. All color properties on this object are computed
 * getters that return the color from the active palette, so existing call
 * sites keep working without changes.
 *
 * The theme is resolved in this order:
 *
 *  1. If the user has chosen "light" or "dark" in the settings page, that
 *     choice wins.
 *  2. Otherwise the system's night-mode flag decides.
 *  3. If neither is available, dark is used as a safe default — the
 *     plugin's original, hand-tuned palette.
 *
 * [applyTheme] must be called before any view is constructed in a given
 * UI entry point (activity onCreate, dialog show). It is safe to call
 * multiple times; the last call wins. The palette is process-global
 * because the plugin runs one UI entry point at a time.
 */
object CodeAssistTheme {

    /** Preference value for "follow the system theme". */
    const val THEME_SYSTEM = "system"

    /** Preference value for "always light". */
    const val THEME_LIGHT = "light"

    /** Preference value for "always dark". */
    const val THEME_DARK = "dark"

    /**
     * Internal contract for a color palette.
     *
     * Each palette defines the exact same set of color roles so that
     * consumers never have to know which concrete palette is active.
     */
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

    /**
     * The plugin's original, hand-tuned dark palette.
     *
     * Used as the default and as the fallback when the system theme cannot
     * be determined.
     */
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

    /**
     * Light palette. Chosen so that the plugin keeps its identity while
     * remaining legible on a light background.
     */
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

    /**
     * The palette currently in effect. Written by [applyTheme], read by
     * every color getter.
     */
    @Volatile
    private var activePalette: Palette = DarkPalette

    /**
     * Resolves and applies the effective palette for [context].
     */
    fun applyTheme(context: Context) {
        activePalette = when (PluginSettings.getUiTheme(context)) {
            THEME_LIGHT -> LightPalette
            THEME_DARK -> DarkPalette
            else -> if (isSystemDark(context)) DarkPalette else LightPalette
        }
    }

    /**
     * Returns true if the current effective palette is dark.
     */
    fun isDark(): Boolean = activePalette === DarkPalette

    /**
     * Reads the system night-mode flag.
     */
    private fun isSystemDark(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    // ------------------------------------------------------------------
    // Surfaces
    // ------------------------------------------------------------------

    /** Window background behind every dialog card. */
    val BG: Int get() = activePalette.BG

    /** Default background for grouped panels. */
    val SURFACE: Int get() = activePalette.SURFACE

    /** Raised surface for chips, pressed states, sticky header. */
    val SURFACE_RAISED: Int get() = activePalette.SURFACE_RAISED

    /** Hairline border color, and fill for 1dp dividers. */
    val OUTLINE: Int get() = activePalette.OUTLINE

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /** Main text: labels, values, content. */
    val TEXT_PRIMARY: Int get() = activePalette.TEXT_PRIMARY

    /** Secondary text: section headers, supporting info. */
    val TEXT_SECONDARY: Int get() = activePalette.TEXT_SECONDARY

    /** Muted text: disabled controls, placeholders. */
    val TEXT_MUTED: Int get() = activePalette.TEXT_MUTED

    // ------------------------------------------------------------------
    // Accent
    // ------------------------------------------------------------------

    /** Warm orange used for interactive affordances. */
    val ACCENT: Int get() = activePalette.ACCENT

    /** Pressed variant of [ACCENT]. */
    val ACCENT_PRESSED: Int get() = activePalette.ACCENT_PRESSED

    // ------------------------------------------------------------------
    // Tag chips
    // ------------------------------------------------------------------

    /** Background of an inclusion chip. */
    val TAG_INCLUDE: Int get() = activePalette.TAG_INCLUDE

    /** Foreground of an inclusion chip. */
    val TAG_INCLUDE_TEXT: Int get() = activePalette.TAG_INCLUDE_TEXT

    /** Background of an exclusion chip. */
    val TAG_EXCLUDE: Int get() = activePalette.TAG_EXCLUDE

    /** Foreground of an exclusion chip. */
    val TAG_EXCLUDE_TEXT: Int get() = activePalette.TAG_EXCLUDE_TEXT

    /** Background of a conflict chip. */
    val TAG_CONFLICT: Int get() = activePalette.TAG_CONFLICT

    /** Foreground of a conflict chip. */
    val TAG_CONFLICT_TEXT: Int get() = activePalette.TAG_CONFLICT_TEXT

    // ------------------------------------------------------------------
    // Measurement helpers
    // ------------------------------------------------------------------

    /**
     * Converts [value] from dp to pixels using the density of [context].
     */
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------------
    // Drawable factories
    // ------------------------------------------------------------------

    /**
     * Builds a rounded rectangle [GradientDrawable] with an optional
     * outline.
     */
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

    /**
     * Builds a two-state [StateListDrawable] for pressable surfaces.
     */
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