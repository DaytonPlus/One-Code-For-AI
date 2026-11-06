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
 * User override
 * -------------
 * The user can force the plugin UI to English from the plugin settings page
 * ([PluginSettings.isForceEnglishEnabled]). When that toggle is on,
 * [detectIdeLocale] returns [Locale.ENGLISH] **unconditionally** and never
 * consults the host activity or the cache, so the override is honoured even
 * on devices whose IDE is running in another language.
 *
 * The override is checked on **every** call rather than once at process
 * start. This is deliberate: it means the next `ConfigurationDialog` or
 * `OneCodeForAIInfoActivity` instance opened after the toggle is flipped
 * uses the new language immediately, without requiring a CodeAssist restart.
 * Already-rendered windows keep their current language until reopened, which
 * matches standard Android behavior for runtime locale changes.
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
 *
 * The force-English check runs **before** the cache read on purpose: a
 * `SharedPreferences` boolean read is an in-memory map lookup after the
 * first load, which is far cheaper than a cache miss, and checking it first
 * guarantees the toggle is never masked by a stale cached locale.
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
     *
     * This cache is intentionally **not** consulted when the user has enabled
     * the "Force English UI" preference; see the class-level *User override*
     * note.
     */
    @Volatile
    private var cachedIdeLocale: Locale? = null

    /**
     * Returns the locale the plugin UI should use.
     *
     * Resolution order:
     *
     *  1. **Force English override.** When [PluginSettings.isForceEnglishEnabled]
     *     returns `true`, [Locale.ENGLISH] is returned immediately, skipping
     *     both the cache and the host activity lookup. This is what allows a
     *     newly opened dialog to switch language as soon as the user flips
     *     the toggle, without a process restart.
     *  2. **Cached host locale.** The result of the previous successful host
     *     lookup, if any.
     *  3. **Live host lookup.** Reads the foreground host activity's
     *     `Configuration` and caches the result.
     *  4. **Caller fallback.** Uses the supplied [context]'s configuration
     *     (typically the system default). Not cached, because it depends on
     *     the caller and would otherwise poison the cache for later calls
     *     that do have a host activity available.
     *
     * Callers that only need a formatted string should go through
     * [com.devdayton.onecodeforai.ui.UiFactory.pluginString], which also
     * handles loading the plugin's own `Resources` with this locale applied.
     */
    fun detectIdeLocale(context: Context): Locale {
        // Honour the user's explicit "Force English UI" preference before
        // touching either the cache or the host activity. SharedPreferences
        // reads are in-memory after the first load, so this check is cheap
        // even though `pluginString` resolves a locale for every string in
        // the configuration dialog.
        //
        // Checking on every call (rather than memoizing the result) is what
        // guarantees that reopening the dialog after toggling the preference
        // shows the new language. Already-open windows keep their previous
        // language, which matches Android's standard locale-switch behavior.
        if (PluginSettings.isForceEnglishEnabled(context)) {
            return Locale.ENGLISH
        }

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
     * Called by [com.devdayton.onecodeforai.OneCodeForAISettingsPage] when
     * the "Force English UI" preference changes, and also available for hosts
     * that support a runtime language switch. The default CodeAssist flow
     * requires restarting the IDE after changing its language, so a manual
     * call is normally unnecessary outside those two cases.
     *
     * Note that clearing this cache is not strictly required for the
     * force-English toggle to work (it is checked before the cache read), but
     * it keeps the "toggle off after the host language changed" path
     * equivalent to a fresh process launch.
     */
    fun invalidateCache() {
        cachedIdeLocale = null
    }

    /**
     * Returns a new `Context` based on [base] whose resources are configured
     * with the locale returned by [detectIdeLocale].
     *
     * Meant to be called from `attachBaseContext` in plugin-owned activities
     * (see `OneCodeForAIInfoActivity`) so the rest of the Activity lifecycle
     * runs with the correct configuration. `createConfigurationContext` does
     * not mutate [base]; it returns a new wrapper that shares the same
     * resource graph.
     *
     * Because [detectIdeLocale] honours the force-English preference, an
     * activity created *after* the toggle is flipped will automatically
     * start in English without any extra plumbing here.
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