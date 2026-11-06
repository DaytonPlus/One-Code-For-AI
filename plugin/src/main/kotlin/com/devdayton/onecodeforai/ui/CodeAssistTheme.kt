package com.devdayton.onecodeforai.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * Centralized color palette and drawable factory for the plugin's UI.
 *
 * ### Purpose
 * Every dialog, tree, and toast in the plugin shares a single dark color
 * scheme and the same set of corner radii. Keeping the values in one object
 * — instead of duplicating hex literals across files — guarantees that a
 * theme tweak (e.g. adjusting the accent orange) reaches every surface at
 * once, and it gives the UI a consistent visual identity that lines up with
 * the host CodeAssist IDE.
 *
 * ### Color roles
 * The palette is organized into four groups, each with a clearly defined
 * purpose:
 *
 *  1. **Surfaces** — layered backgrounds. The higher the elevation, the
 *     lighter the surface.
 *       - [BG]: window background behind the dialog card.
 *       - [SURFACE]: default panel background for grouped controls.
 *       - [SURFACE_RAISED]: chips, pressed states, and elements that should
 *         look "lifted" off a surface.
 *       - [OUTLINE]: hairline borders between surfaces.
 *  2. **Text** — three levels of emphasis.
 *       - [TEXT_PRIMARY]: main labels and values.
 *       - [TEXT_SECONDARY]: supporting text, section headers.
 *       - [TEXT_MUTED]: disabled or low-emphasis hints.
 *  3. **Accent** — the warm orange used for interactive affordances
 *     (buttons, help icons, active tabs, resolved refs). Two shades exist so
 *     pressable elements can visibly darken on `state_pressed`:
 *       - [ACCENT]: the resting color.
 *       - [ACCENT_PRESSED]: the pressed color.
 *  4. **Tag chips** — paired background / foreground colors used by the
 *     inclusion, exclusion, and conflict chips in the configuration dialog:
 *       - [TAG_INCLUDE] / [TAG_INCLUDE_TEXT]: green pair for inclusions.
 *       - [TAG_EXCLUDE] / [TAG_EXCLUDE_TEXT]: red pair for exclusions.
 *       - [TAG_CONFLICT] / [TAG_CONFLICT_TEXT]: amber pair for rules that
 *         conflict with `.gitignore` or with the opposite list.
 *
 * ### Units
 * The object is a pure factory: it never holds state and every method is
 * thread-safe. Distances are always expressed in **dp** and converted to
 * pixels using the density of the `Context` passed in, so callers never
 * need to know the device density.
 */
object CodeAssistTheme {
    // ------------------------------------------------------------------
    // Surfaces
    // ------------------------------------------------------------------

    /** Window background behind every dialog card. Darker than any surface. */
    val BG = Color.parseColor("#171719")

    /** Default background for grouped panels (cards, tag sections, lists). */
    val SURFACE = Color.parseColor("#202024")

    /**
     * Raised surface for chips, pressed states, and any element that should
     * visually sit "on top" of a [SURFACE]. Also used as the sticky-header
     * background in the project tree.
     */
    val SURFACE_RAISED = Color.parseColor("#26262B")

    /**
     * Hairline border color drawn around surfaces and chips, and used as the
     * fill for 1dp dividers between sections.
     */
    val OUTLINE = Color.parseColor("#36363D")

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /** Main text: labels, values, and any content the user is expected to read. */
    val TEXT_PRIMARY = Color.parseColor("#E7E7EA")

    /** Secondary text: section headers, supporting information, subtitle lines. */
    val TEXT_SECONDARY = Color.parseColor("#9999A2")

    /**
     * Muted text: disabled controls, empty-state placeholders, and any
     * element whose content is not actionable.
     */
    val TEXT_MUTED = Color.parseColor("#707079")

    // ------------------------------------------------------------------
    // Accent
    // ------------------------------------------------------------------

    /**
     * Warm orange used for interactive affordances: primary buttons, help
     * icons, active tabs, the resolved-ref label, and any control the user
     * is meant to tap. Chosen to sit comfortably next to the CodeAssist
     * action color without matching it exactly.
     */
    val ACCENT = Color.parseColor("#D99460")

    /**
     * Pressed variant of [ACCENT]. Used as the `state_pressed` fill in
     * [selectableBg] so buttons visibly darken during a tap. Kept as a
     * manual hex literal rather than derived from [ACCENT] so the exact
     * shade can be tuned without touching the resting color.
     */
    val ACCENT_PRESSED = Color.parseColor("#B97849")

    // ------------------------------------------------------------------
    // Tag chips
    //
    // Every pair follows the same contract: the `TAG_*` value is the chip's
    // background, the `TAG_*_TEXT` value is its foreground. The pairs are
    // intentionally semantic (include / exclude / conflict) rather than
    // tied to specific widgets, so the same chip style can be reused by
    // any future surface that needs to signal one of the three states.
    // ------------------------------------------------------------------

    /** Background of an inclusion chip (successful include). */
    val TAG_INCLUDE = Color.parseColor("#253D35")

    /** Foreground of an inclusion chip. Contrast-checked against [TAG_INCLUDE]. */
    val TAG_INCLUDE_TEXT = Color.parseColor("#A8D7C2")

    /** Background of an exclusion chip (successful exclude). */
    val TAG_EXCLUDE = Color.parseColor("#402B2D")

    /** Foreground of an exclusion chip. Contrast-checked against [TAG_EXCLUDE]. */
    val TAG_EXCLUDE_TEXT = Color.parseColor("#E1A7A7")

    /**
     * Background of a conflict chip: a rule that simultaneously matches an
     * inclusion and an exclusion, or that overrides a `.gitignore` entry.
     */
    val TAG_CONFLICT = Color.parseColor("#443820")

    /** Foreground of a conflict chip. Contrast-checked against [TAG_CONFLICT]. */
    val TAG_CONFLICT_TEXT = Color.parseColor("#E5C27F")

    // ------------------------------------------------------------------
    // Measurement helpers
    // ------------------------------------------------------------------

    /**
     * Converts [value] from dp to pixels using the density of [context].
     *
     * Uses a `+ 0.5f` rounding bias before truncating so half-pixel values
     * round to the nearest whole pixel rather than being floored — this
     * prevents a 1.5dp line from collapsing to 1px on medium-density
     * devices where the exact pixel count would otherwise be ambiguous.
     *
     * @param context any `Context`; its `displayMetrics.density` is read on
     *                every call, so the result tracks the current
     *                configuration automatically.
     * @param value   the distance in dp.
     * @return the equivalent distance in pixels, rounded to the nearest
     *         integer.
     */
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------------
    // Drawable factories
    // ------------------------------------------------------------------

    /**
     * Builds a rounded rectangle [GradientDrawable] with an optional outline.
     *
     * This is the single primitive used by every themed surface in the
     * plugin: dialog backgrounds, panel backgrounds, chips, tag pills, and
     * the diff container. Centralizing it here is what guarantees that
     * every corner radius, outline width, and stroke behavior is applied
     * consistently.
     *
     * ### Rounded corners
     * The radius is applied to all four corners. There is intentionally no
     * per-corner override: the plugin's visual language is uniform-rounded
     * everywhere, and adding `topLeftRadius` style parameters would
     * multiply the surface area of this factory for no current consumer.
     *
     * ### Outline
     * When [strokeColor] is non-null, a hairline stroke is drawn. The stroke
     * width is coerced to **at least 1 px** after conversion, because a
     * fractional dp value (e.g. `0.5dp` on a low-density device) would
     * round to zero and effectively hide the border, breaking the visual
     * separation between adjacent surfaces.
     *
     * @param context       used to resolve the display density for the
     *                      radius and stroke width conversions.
     * @param color         fill color. Typically one of the `SURFACE*` or
     *                      `TAG_*` constants.
     * @param radiusDp      corner radius in dp. Defaults to 10dp, which is
     *                      the standard panel radius used throughout the
     *                      dialog.
     * @param strokeColor   outline color. Pass `null` (the default) for no
     *                      outline.
     * @param strokeWidthDp outline width in dp. Ignored when [strokeColor]
     *                      is `null`.
     * @return a fresh [GradientDrawable]. The caller owns the instance and
     *         may mutate it freely — no shared state is used.
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
                // Coerce to >= 1 px so a sub-pixel stroke width cannot
                // silently disable the outline on low-density devices.
                setStroke(
                    (strokeWidthDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    strokeColor
                )
            }
        }
    }

    /**
     * Builds a two-state [StateListDrawable] for pressable surfaces.
     *
     * ### Why a StateListDrawable
     * Pressable rows in the dialog (icon buttons, list items in the Git
     * reference picker, tag chips) need to visibly react to a tap without
     * shipping per-widget ripple drawables. This helper produces a compact
     * pressed/unpressed pair that works on every API level the plugin
     * supports and stays fully under the theme's control.
     *
     * ### State order
     * `state_pressed` is registered **first** on purpose. `StateListDrawable`
     * returns the first state whose criteria the view matches, so registering
     * the default state first would shadow the pressed state and the pressed
     * fill would never be shown. The default state is registered with an
     * empty criteria array (`intArrayOf()`), which matches every view that
     * did not match the pressed state above it.
     *
     * ### Outline sharing
     * Both states use the same [strokeColor] so the border does not flicker
     * or change width when the user presses and releases the control. Only
     * the fill color swaps, which is the minimal visual cue the theme needs
     * for a pressable surface.
     *
     * @param context      used to resolve the display density for radius and
     *                     stroke width conversions.
     * @param normalColor  fill color when the view is not pressed. Defaults
     *                     to [SURFACE] so the result blends into the
     *                     default panel background out of the box.
     * @param pressedColor fill color while the view is pressed. Defaults to
     *                     [SURFACE_RAISED] so a tap visibly lifts the
     *                     surface by one elevation step.
     * @param radiusDp     corner radius in dp, applied identically to both
     *                     states. Defaults to 9dp, slightly tighter than
     *                     [roundedBg]'s default because pressable surfaces
     *                     tend to be smaller (icon buttons, chips).
     * @param strokeColor  outline color for both states. Pass `null` for a
     *                     borderless pressable surface. Defaults to
     *                     [OUTLINE] so pressables have the same hairline
     *                     border as static panels.
     * @return a fresh [StateListDrawable] with exactly two states.
     */
    fun selectableBg(
        context: Context,
        normalColor: Int = SURFACE,
        pressedColor: Int = SURFACE_RAISED,
        radiusDp: Float = 9f,
        strokeColor: Int? = OUTLINE
    ): StateListDrawable {
        return StateListDrawable().apply {
            // Register the pressed state FIRST — StateListDrawable returns
            // the first matching state, so a default-first ordering would
            // shadow the pressed fill entirely.
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedBg(context, pressedColor, radiusDp, strokeColor)
            )
            // Empty criteria matches every view that did not match above.
            addState(
                intArrayOf(),
                roundedBg(context, normalColor, radiusDp, strokeColor)
            )
        }
    }
}