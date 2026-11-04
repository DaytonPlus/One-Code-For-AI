package com.devdayton.onecodeforai.android

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.devdayton.onecodeforai.android.HostEnvironment
import java.util.Locale

/**
 * Resolves the host IDE's locale and exposes helpers that keep the plugin's
 * strings, activities, and any locale-sensitive UI aligned with whatever
 * language CodeAssist is currently using.
 *
 * Background
 * ----------
 * CodeAssist can be localized independently of the system. The plugin's own
 * `Resources` are compiled with an English default and an `es` variant, but
 * every call to `getString` on a `Context` derived from the host would fall
 * back to the *host's* resources, which do not contain the plugin's string
 * IDs. This helper bridges that gap by:
 *
 *  1. Detecting the locale the host is *actually* using (not the system
 *     locale, which can differ if the user changed CodeAssist's language
 *     without changing the device language).
 *  2. Exposing [wrapContextWithIdeLocale] so plugin-owned activities can
 *     inherit the host locale before their `onCreate` runs.
 *
 * Detection cost and caching
 * --------------------------
 * The lookup itself goes through [HostEnvironment.currentActivity], which
 * reflects into `android.app.ActivityThread`. That is measurable but not
 * huge (~0.1–0.5 ms on a mid-range device), and the IDE's locale changes
 * only when the host is restarted or the user changes it explicitly. Calling
 * it once per `getString` would still add up because the configuration
 * dialog requests 50+ strings during construction, so [detectIdeLocale]
 * caches the result for the process lifetime. Call [invalidateCache] if the
 * host ever exposes a runtime language switch and you need to react to it.
 */
object LanguageHelper {

    /**
     * Cached result of a successful host lookup.
     *
     * Only successful lookups are cached. When the host activity cannot be
     * found (e.g. from a background service, or during process startup before
     * any activity exists), [detectIdeLocale] falls back to the caller's
     * `Context`. That fallback depends on the caller and must not poison the
     * cache: a later call with an activity available would otherwise keep
     * returning the wrong locale forever.
     */
    @Volatile
    private var cachedIdeLocale: Locale? = null

    /**
     * Returns the locale the host IDE is currently using.
     *
     * Prefers the configuration of the foreground host activity. When no
     * activity is available, falls back to the caller's `Context` (or the
     * system default) so callers such as background notification builders
     * still get a usable value.
     *
     * The result is cached on the first successful host lookup; subsequent
     * calls become a single volatile read.
     */
    fun detectIdeLocale(context: Context): Locale {
        cachedIdeLocale?.let { return it }

        val host = findHostActivity()
        if (host != null) {
            val locale = localeOf(host.resources.configuration)
            cachedIdeLocale = locale
            return locale
        }

        // No cached value and no host activity: fall back to the caller's
        // configuration. Do not cache this — see the field KDoc.
        return localeOf(context.resources.configuration)
    }

    /**
     * Clears the cached locale so the next call re-detects it from the host.
     *
     * Intended for hosts that support a runtime language switch. The default
     * CodeAssist flow requires restarting the IDE after changing its language,
     * so this is normally unnecessary.
     */
    fun invalidateCache() {
        cachedIdeLocale = null
    }

    /**
     * Returns a new `Context` based on [base] whose resources are configured
     * with the IDE's locale.
     *
     * Meant to be called from `attachBaseContext` in plugin-owned activities
     * (see `OneCodeForAIInfoActivity`) so the rest of the Activity lifecycle
     * runs with the correct configuration. `createConfigurationContext` does
     * not mutate [base]; it returns a new wrapper that shares the same
     * resource graph.
     */
    fun wrapContextWithIdeLocale(base: Context): Context {
        val locale = detectIdeLocale(base)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Returns the foreground host activity, or `null` when the IDE has no
     * visible activity. Delegates to [HostEnvironment] so all reflection
     * against `ActivityThread` lives in a single place.
     */
    fun findHostActivity() = HostEnvironment.currentActivity()

    /**
     * Extracts the primary locale from a `Configuration` while handling the
     * pre-API-24 `locale` field and the newer `locales[0]` list.
     */
    private fun localeOf(config: Configuration): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0] ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            config.locale ?: Locale.getDefault()
        }
}