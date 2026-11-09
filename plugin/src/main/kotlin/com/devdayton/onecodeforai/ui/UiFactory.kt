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
import java.util.Locale

/**
 * Centralized factory for the plugin's UI primitives.
 *
 * ### Why this object exists
 * The plugin's views are built with the *host* IDE's `Activity` as their
 * context, but every drawable, string, and style they use belongs to the
 * plugin's own APK. The host `Resources` do not know about the plugin's IDs,
 * so calling `context.getDrawable(pluginResId)` or
 * `context.getString(pluginResId)` on the host activity would fail with
 * `Resources.NotFoundException`.
 *
 * `UiFactory` bridges that gap by resolving the plugin's `Resources` and
 * building a locale-aware `Context` from the plugin package once per process,
 * then exposing them through extension functions so call sites keep the
 * natural `activity.textView(...)` / `activity.iconButton(...)` shape without
 * threading a plugin context through every constructor.
 *
 * ### Performance
 * Three process-wide caches keep the hot paths cheap:
 *  - [pluginResourcesCache]: the plugin's raw `Resources`, resolved once.
 *  - [cachedLocalizedPluginCtx] / [cachedLocalizedLocale]: a `Context`
 *    derived from the plugin package with the IDE locale applied, rebuilt
 *    only when the effective locale changes.
 *  - [drawableStateCache]: per-resource `Drawable.ConstantState` so vector
 *    XMLs are not re-inflated for every `ImageView`; each call still returns
 *    a fresh `Drawable` so `setColorFilter` cannot bleed between views.
 *
 * ### Force English UI
 * [LanguageHelper.detectIdeLocale] honours the "Force English UI" preference
 * before consulting any cache. Because [getOrCreateLocalizedPluginCtx]
 * compares the detected locale against [cachedLocalizedLocale] on every call,
 * the toggle takes effect on the next string resolution without extra
 * invalidation. [invalidateLocalizedCtxCache] exists to cover the reverse
 * direction (turning the toggle off after the host language changed) and is
 * called from the settings page for clarity, not strict correctness.
 */
object UiFactory {

    /**
     * Package name of the plugin's APK.
     *
     * Must match `module.toml` → `[android].namespace`. Kept as a `String`
     * constant rather than a `BuildConfig` reference so this object can be
     * unit-tested in isolation without pulling in the generated build config.
     */
    private const val PLUGIN_PACKAGE = "com.devdayton.onecodeforai"

    /**
     * Default `contentDescription` for [iconButton] when the caller does not
     * supply one.
     *
     * English by default so screen readers always produce meaningful output
     * even when a caller forgets to pass a localized string. All current
     * callers override it; this is a safety net, not a user-facing label.
     */
    private const val DEFAULT_ICON_DESCRIPTION = "Action"

    // ------------------------------------------------------------------
    // Process-wide caches
    // ------------------------------------------------------------------

    /**
     * The plugin's own `Resources`, resolved once via
     * `PackageManager.getResourcesForApplication`.
     *
     * Language-agnostic: the concrete resource variant is chosen by the
     * `Configuration` of the `Context` used to load a drawable or string, not
     * by this object. Cached for the process lifetime; if the lookup fails,
     * the field stays `null` and every consumer falls back to the host
     * context's resources.
     */
    @Volatile
    private var pluginResourcesCache: Resources? = null

    /**
     * Plugin `Context` configured with the currently detected IDE locale.
     *
     * Built via `createPackageContext(PLUGIN_PACKAGE, 0)` followed by
     * `createConfigurationContext(config)` with the locale applied. Rebuilt
     * whenever [cachedLocalizedLocale] no longer matches the locale returned
     * by [LanguageHelper.detectIdeLocale] — which is exactly what happens the
     * first time the "Force English UI" toggle flips.
     *
     * Written under [pluginCtxLock]; read optimistically outside the lock
     * (see [getOrCreateLocalizedPluginCtx]).
     */
    @Volatile
    private var cachedLocalizedPluginCtx: Context? = null

    /**
     * Locale that [cachedLocalizedPluginCtx] was built with.
     *
     * Compared against the freshly detected locale on every string resolution
     * so a language change (IDE language switch or force-English toggle) is
     * picked up automatically. Held under [pluginCtxLock] for writes;
     * `@Volatile` for lock-free reads.
     */
    @Volatile
    private var cachedLocalizedLocale: Locale? = null

    /**
     * Lock guarding every write to [cachedLocalizedPluginCtx],
     * [cachedLocalizedLocale], and the slow path of
     * [getOrCreateLocalizedPluginCtx] / [invalidateLocalizedCtxCache].
     *
     * The double-checked locking pattern used in
     * [getOrCreateLocalizedPluginCtx] relies on this monitor to guarantee a
     * single construction under contention; reads on the fast path stay
     * lock-free thanks to the `@Volatile` annotations above.
     */
    private val pluginCtxLock = Any()

    /**
     * Cache of `ConstantState` per drawable resource ID.
     *
     * Vector drawables are expensive to inflate. Caching the `ConstantState`
     * lets every call return a brand-new `Drawable` via
     * `state.newDrawable(res)` while sharing the parsed vector internally.
     * Returning a fresh instance per call is essential: `setColorFilter`
     * mutates the `Drawable`, not the `ConstantState`, so sharing a single
     * instance would leak one view's tint into another.
     *
     * A `null` value is a valid cache entry meaning "this resource does not
     * resolve"; it prevents repeated failed lookups for the same ID.
     */
    private val drawableStateCache = HashMap<Int, Drawable.ConstantState?>()

    /**
     * Lock guarding [drawableStateCache]. Contention is minimal in practice
     * (writes happen once per unique `resId`), but the `HashMap` is not
     * thread-safe and UI construction can race with background pre-warming
     * from the export worker.
     */
    private val drawableCacheLock = Any()

    // ------------------------------------------------------------------
    // Plugin resources
    // ------------------------------------------------------------------

    /**
     * Returns the plugin's own `Resources`, resolving them lazily on first
     * call.
     *
     * The result is cached for the process lifetime; callers must not hold
     * onto it beyond the process and must not mutate it. Returns `null` if
     * the package manager cannot find the plugin package, which on Android
     * only happens in pathological install states (e.g. multi-user race
     * during uninstall).
     */
    fun Context.pluginResources(): Resources? {
        pluginResourcesCache?.let { return it }
        return try {
            val res = packageManager.getResourcesForApplication(PLUGIN_PACKAGE)
            pluginResourcesCache = res
            res
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Drawables
    // ------------------------------------------------------------------

    /**
     * Loads a drawable owned by the plugin, regardless of which `Context`
     * the calling view was built with.
     *
     * Resolution order:
     *  1. Plugin `Resources` via [pluginResources].
     *  2. Fallback to the caller's own resources, so framework IDs such as
     *     `android.R.drawable.*` still work.
     *
     * A fresh `Drawable` is returned on every call so that per-view
     * `setColorFilter` calls do not interfere with each other. The
     * underlying `ConstantState` is cached to avoid re-inflating vector XML
     * on every call.
     *
     * Returns `null` when the resource cannot be resolved by either source.
     */
    fun Context.loadPluginDrawable(resId: Int): Drawable? {
        // Fast path: ConstantState already cached for this resource ID.
        synchronized(drawableCacheLock) {
            if (drawableStateCache.containsKey(resId)) {
                val state = drawableStateCache[resId] ?: return null
                val res = pluginResources() ?: return null
                return try { state.newDrawable(res) } catch (_: Exception) { null }
            }
        }

        // Slow path: first time this resource ID is requested in the process.
        val drawable = loadDrawableUncached(resId)
        synchronized(drawableCacheLock) {
            drawableStateCache[resId] = drawable?.constantState
        }
        return drawable
    }

    /**
     * Uncached drawable lookup used by [loadPluginDrawable] on cache miss.
     *
     * Tries the plugin's own `Resources` first, then the caller's context as
     * a fallback for framework resources. Never throws: any failure results
     * in `null`, which the caller stores as a negative cache entry.
     */
    private fun Context.loadDrawableUncached(resId: Int): Drawable? {
        pluginResources()?.let { res ->
            try {
                return res.getDrawable(resId, null)
            } catch (_: Exception) {
                // Fall through to the host-resources fallback below.
            }
        }
        return runCatching { getDrawable(resId) }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Localized strings
    // ------------------------------------------------------------------

    /**
     * Resolves a string from the plugin's APK using the IDE's effective
     * locale (or English when the force-English toggle is on).
     *
     * Required because views in the configuration dialog are built with the
     * host activity as their context, whose `Resources` do not contain the
     * plugin's string IDs. Calls the plugin context lazily through
     * [getOrCreateLocalizedPluginCtx] so the underlying `Context` is built at
     * most once per locale.
     *
     * Fallback chain:
     *  1. Localized plugin context (correct path).
     *  2. Caller's own context (works when the caller already is the plugin,
     *     e.g. inside `OneCodeForAIInfoActivity`).
     *  3. Empty string (should never happen in practice; keeps callers free
     *     of null checks).
     */
    fun Context.pluginString(resId: Int): String {
        val localizedCtx = getOrCreateLocalizedPluginCtx(this)
        if (localizedCtx != null) {
            try {
                return localizedCtx.getString(resId)
            } catch (_: Exception) {
                // Fall through to the host-context fallback below.
            }
        }
        return runCatching { getString(resId) }.getOrDefault("")
    }

    /**
     * [pluginString] variant with `String.format` arguments, mirroring the
     * platform `Context.getString(id, vararg args)` overload.
     */
    fun Context.pluginString(resId: Int, vararg args: Any): String =
        String.format(pluginString(resId), *args)

    /**
     * Returns the plugin `Context` configured with the current IDE locale,
     * building it if necessary.
     *
     * Thread-safe via double-checked locking on [pluginCtxLock]. The fast
     * path is a single `@Volatile` read of [cachedLocalizedPluginCtx] plus a
     * locale comparison, which is why this method can safely be called for
     * every string without measurable overhead.
     *
     * The locale comparison is what makes the "Force English UI" toggle take
     * effect without an explicit invalidation call: once
     * [LanguageHelper.detectIdeLocale] starts returning `Locale.ENGLISH`, the
     * `cachedLocalizedLocale == currentLocale` check fails and a new context
     * is built with the English locale.
     *
     * Returns `null` only when `createPackageContext` itself fails, which on
     * Android implies the plugin APK is not visible to the current process;
     * callers must handle that by falling back to their own resources.
     */
    private fun getOrCreateLocalizedPluginCtx(callerCtx: Context): Context? {
        val currentLocale = LanguageHelper.detectIdeLocale(callerCtx)

        // Fast path, lock-free: hit when the cached context already matches
        // the currently detected locale.
        cachedLocalizedPluginCtx?.let { cached ->
            if (cachedLocalizedLocale == currentLocale) return cached
        }

        // Slow path: build under the lock and re-check to avoid double
        // construction if another thread won the race.
        synchronized(pluginCtxLock) {
            cachedLocalizedPluginCtx?.let { cached ->
                if (cachedLocalizedLocale == currentLocale) return cached
            }
            return try {
                val base = callerCtx.createPackageContext(PLUGIN_PACKAGE, 0)
                val config = Configuration(base.resources.configuration)
                config.setLocale(currentLocale)
                val localized = base.createConfigurationContext(config)
                cachedLocalizedPluginCtx = localized
                cachedLocalizedLocale = currentLocale
                localized
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Clears the cached localized plugin context and its associated locale.
     *
     * Called by [com.devdayton.onecodeforai.OneCodeForAISettingsPage] when
     * the "Force English UI" preference changes, right after
     * [LanguageHelper.invalidateCache]. Together they guarantee that a
     * **reopened** configuration dialog or info activity uses the selected
     * language immediately, without requiring a process restart.
     *
     * [pluginResourcesCache] is intentionally **not** cleared: the resources
     * themselves are language-agnostic, and the concrete variant is chosen at
     * string/drawable resolution time based on the configuration of
     * `cachedLocalizedPluginCtx`. Keeping it cached avoids an unnecessary
     * `PackageManager` round-trip on the next call.
     *
     * Thread-safe; serializes with [getOrCreateLocalizedPluginCtx] on
     * [pluginCtxLock].
     */
    fun invalidateLocalizedCtxCache() {
        synchronized(pluginCtxLock) {
            cachedLocalizedPluginCtx = null
            cachedLocalizedLocale = null
        }
    }

    // ------------------------------------------------------------------
    // Widget factories
    // ------------------------------------------------------------------

    /**
     * Builds a themed [TextView] with the plugin's typography conventions:
     * `sans-serif-medium` for bold labels, `sans-serif` otherwise, and font
     * padding disabled so vertical spacing is fully controlled by the layout.
     *
     * @param text    Initial text.
     * @param size    Text size in `sp`.
     * @param color   Text color, typically one of the `CodeAssistTheme.TEXT_*`
     *                constants.
     * @param isBold  Whether to use the medium weight typeface.
     */
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

    /**
     * Builds a section title: the supplied text uppercased, rendered in the
     * secondary text color with the standard 18 dp top / 9 dp bottom padding
     * used across the plugin's dialogs.
     *
     * Uppercasing is done here rather than in the caller so every section
     * title in the plugin follows the same convention. `.uppercase()` is
     * locale-aware and respects the current `Locale`.
     */
    fun Context.sectionTitle(text: String) = textView(
        // `Locale.getDefault()` would use the SYSTEM locale, which can diverge
        // from the IDE locale (e.g. Turkish system + English IDE → "İNFO").
        // `LanguageHelper.detectIdeLocale` is the same source of truth that
        // `pluginString` uses to localize the input, so the uppercased form
        // stays consistent with the localized content regardless of the
        // "Force English UI" toggle.
        text.uppercase(LanguageHelper.detectIdeLocale(this)),
        11f,
        CodeAssistTheme.TEXT_SECONDARY,
        true
    ).apply {
        setPadding(0, CodeAssistTheme.dp(this@sectionTitle, 18), 0, CodeAssistTheme.dp(this@sectionTitle, 9))
    }

    /**
     * Builds a themed [CheckBox] with the plugin's accent tint and a minimum
     * height that matches the tap target of other controls.
     *
     * Internal padding is zeroed so the checkbox indicator and label align
     * exactly with surrounding text; vertical spacing is provided by the
     * enclosing layout.
     */
    fun Context.checkBox(text: String) = CheckBox(this).apply {
        this.text = text
        setTextColor(CodeAssistTheme.TEXT_PRIMARY)
        textSize = 13f
        buttonTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
        minHeight = CodeAssistTheme.dp(this@checkBox, 42)
        setPadding(0, 0, 0, 0)
    }

    /**
     * Builds a themed single-line [EditText] with the plugin's rounded
     * surface background, muted hint color, and internal padding.
     *
     * @param initial  Pre-populated text. Pass an empty string for empty
     *                 fields; the hint should be set by the caller so it can
     *                 be localized.
     * @param type     One of the `InputType.TYPE_*` constants.
     */
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

    /**
     * Builds a 40×40 dp square [ImageButton] with a themed background and a
     * tinted icon loaded from the plugin's own resources.
     *
     * The icon is loaded via [loadPluginDrawable] so it works regardless of
     * which context the caller used to construct the button. If the icon
     * cannot be resolved, the button is left without a drawable but remains
     * clickable so the surrounding UI does not break.
     *
     * @param iconRes                 Drawable resource ID (plugin or framework).
     * @param contentDescriptionText  Localized description for accessibility.
     *                                Falls back to [DEFAULT_ICON_DESCRIPTION]
     *                                if a caller forgets to pass one.
     * @param onClick                 Click handler.
     */
    fun Context.iconButton(
        iconRes: Int,
        contentDescriptionText: String = DEFAULT_ICON_DESCRIPTION,
        onClick: () -> Unit
    ): ImageButton {
        val button = ImageButton(this)
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

    /**
     * Builds a small accent-colored text action ("Add", "Reload", "Expand",
     * etc.) styled as a transparent button with a pressable surface
     * background.
     *
     * Used as the lightweight affordance next to section headers, where a
     * full [Button] would be visually too heavy.
     */
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

    /**
     * Builds an [ArrayAdapter] for a themed spinner: dark surface background,
     * primary text color, and a minimum 40 dp row height for touch comfort.
     *
     * Implemented as an anonymous subclass rather than a plain
     * `ArrayAdapter` with `simple_spinner_item` because the dropdown row and
     * the collapsed row need different padding and backgrounds, and reusing
     * the framework layout would require a custom XML resource.
     *
     * @param items Localized display labels for the spinner.
     */
    fun Context.spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items
        ) {
            /**
             * Builds a single row for both the collapsed control and the
             * dropdown list.
             *
             * @param dropdown When `true`, the row is rendered as a dropdown
             *                 entry (raised surface background, extra vertical
             *                 padding); when `false`, it is the collapsed
             *                 display value.
             */
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
    
    /**
     * [pluginString] equivalent for plural strings.
     *
     * `Context.resources.getQuantityString(id, quantity, ...)` reads from
     * the *caller's* resources, which on the host activity do not contain
     * the plugin's plural IDs. This helper resolves the plugin's own
     * locale-aware `Context` (same path as [pluginString]) and calls
     * `getQuantityString` on it, so the correct CLDR rule for the current
     * locale is used.
     *
     * The `quantity` parameter is passed twice by the caller because
     * `Resources.getQuantityString` has two roles for it:
     *  - it selects which `<item>` to use, and
     *  - it can be referenced by `%d` in the chosen string.
     * The overload without format args is used when `formatArgs` is empty,
     * matching the platform API split.
     *
     * Falls back to the caller's own resources if the plugin context cannot
     * be built, mirroring [pluginString]'s fallback chain.
     */
    fun Context.pluginQuantityString(resId: Int, quantity: Int, vararg formatArgs: Any): String {
        val localizedCtx = getOrCreateLocalizedPluginCtx(this)
        if (localizedCtx != null) {
            try {
                return if (formatArgs.isEmpty()) {
                    localizedCtx.resources.getQuantityString(resId, quantity)
                } else {
                    localizedCtx.resources.getQuantityString(resId, quantity, *formatArgs)
                }
            } catch (_: Exception) {
                // Fall through to the host-context fallback below.
            }
        }
        return runCatching {
            if (formatArgs.isEmpty()) {
                resources.getQuantityString(resId, quantity)
            } else {
                resources.getQuantityString(resId, quantity, *formatArgs)
            }
        }.getOrDefault("")
    }
}