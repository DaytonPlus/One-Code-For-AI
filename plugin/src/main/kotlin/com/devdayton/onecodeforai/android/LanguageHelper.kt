package com.devdayton.onecodeforai.android

import android.content.Context
import android.content.res.Configuration
import com.devdayton.onecodeforai.android.HostEnvironment
import java.util.Locale

/**
 * Resolves the plugin's effective locale and exposes helpers that keep the
 * plugin's strings, activities, and any locale-sensitive UI aligned with
 * either the language the user has selected for the plugin, or — when no
 * override is active — whatever language CodeAssist is currently using.
 *
 * Background
 * ----------
 * CodeAssist can be localized independently of the system. The plugin's own
 * `Resources` are compiled with an English default and 26 language variants
 * (`values-es/`, `values-fr/`, …, `values-uk/`), but every call to
 * `getString` on a `Context` derived from the host would fall back to the
 * *host's* resources, which do not contain the plugin's string IDs. This
 * helper bridges that gap by:
 *
 *  1. Detecting the language the user has configured for the plugin (or, when
 *     they have not forced one, the language the host IDE is actually using —
 *     not the system locale, which can differ if the user changed
 *     CodeAssist's language without changing the device language).
 *  2. Exposing [wrapContextWithIdeLocale] so plugin-owned activities can
 *     inherit the resolved language before their `onCreate` runs.
 *
 * User override
 * -------------
 * The user can pick any language the plugin bundles from the plugin settings
 * page ([PluginSettings.getUiLanguage]), or leave the selector on
 * [PluginSettings.LANGUAGE_AUTO] to follow the IDE. When a specific language
 * is selected, [detectIdeLocale] returns the corresponding [Locale]
 * **unconditionally** and never consults the host activity or the host-locale
 * cache, so the override is honoured even on devices whose IDE is running in
 * a different language.
 *
 * The override is checked on **every** call rather than once at process
 * start. This is deliberate: it means the next `ConfigurationDialog` or
 * `OneCodeForAIInfoActivity` instance opened after the preference is changed
 * uses the new language immediately, without requiring a CodeAssist restart.
 * Already-rendered windows keep their current language until reopened, which
 * matches standard Android behavior for runtime locale changes.
 *
 * Language-code ↔ [Locale] mapping lives in [localeForLanguageCode]. Because
 * the mapping is a small `when` expression over `String` constants, it is
 * cheaper to evaluate on every call than to add a second layer of caching
 * that would need to be invalidated in lockstep with the preference. The
 * [com.devdayton.onecodeforai.ui.UiFactory] layer already caches the
 * fully-resolved localized `Context` per locale, so the map lookup happens at
 * most once per string-batch rather than once per string.
 *
 * Legacy `force_english` shim
 * ---------------------------
 * A previous version of the plugin exposed a boolean "Force English UI"
 * toggle. That preference is now represented as `ui_language = "en"` and is
 * migrated automatically by
 * [PluginSettings.migrateLegacyLanguagePreference]. The
 * `PluginSettings.isForceEnglishEnabled` method is kept as a deprecated shim
 * so out-of-tree callers keep compiling, but this class no longer uses it:
 * [detectIdeLocale] reads [PluginSettings.getUiLanguage] directly, which is
 * what lets the selector honour **any** forced language (not only English).
 *
 * Detection cost and caching
 * --------------------------
 * The host-locale lookup goes through [HostEnvironment.currentActivity],
 * which reflects into `android.app.ActivityThread`. That is measurable but
 * not huge (~0.1–0.5 ms on a mid-range device), and the IDE's locale changes
 * only when the host is restarted or the user changes it explicitly. Calling
 * it once per `getString` would still add up because the configuration
 * dialog requests 50+ strings during construction, so [detectIdeLocale]
 * caches the **host-detected** locale for the process lifetime. The
 * forced-language path deliberately does **not** use this cache: it is a
 * cheap `String` comparison against a small `when` expression, and mixing
 * the two in one cache slot would require invalidating the host-locale cache
 * every time the user flips the selector, which would defeat the point.
 *
 * Call [invalidateCache] after any preference change that could alter the
 * resolved locale. The settings page calls it from `onChanged` for the
 * language selector; see
 * [com.devdayton.onecodeforai.OneCodeForAISettingsPage].
 *
 * The override check runs **before** the host-locale cache read on purpose:
 * a `SharedPreferences` read is an in-memory map lookup after the first load,
 * which is far cheaper than a cache miss, and checking it first guarantees
 * the override is never masked by a stale cached host locale.
 *
 * Right-to-left languages
 * -----------------------
 * Three of the bundled languages — Arabic (`ar`), Persian (`fa`) and Hebrew
 * (`he`) — are written right-to-left. Android mirrors the framework widgets
 * automatically from the locale set on the `Context` returned by
 * [wrapContextWithIdeLocale], so this class has no RTL-specific code path.
 * The plugin's own custom views (`FlowLayout`, `ProjectTreeView`) are
 * documented as LTR-only; see their KDocs for the current limitations. Any
 * user-visible mirroring regression in an RTL language should be
 * investigated in those views, not here.
 *
 * Hebrew's legacy ISO-639 code is `iw`, which some OEM builds still report.
 * On every Android version the plugin supports (`minSdk 26`), `Locale("he")`
 * resolves to `values-he/` correctly, so the modern code is used throughout
 * and the legacy `iw` alias is not needed. If a specific device is observed
 * falling back to English for Hebrew, `Locale("iw", "")` is a safe substitute
 * that resolves to the same `values-he/` folder on those devices.
 *
 * Android API floor
 * -----------------
 * The plugin's `minSdk` is 26, so `Configuration.getLocales()` (added in API
 * 24) is always available. A previous version carried a fallback to the
 * deprecated `Configuration.locale` field for API < 24; that branch was
 * dead code and has been removed. Keeping it would imply the plugin supports
 * API < 24, which it does not.
 */
object LanguageHelper {

    /**
     * Cached result of a successful **host** locale lookup.
     *
     * Only successful host lookups are cached. When the host activity cannot
     * be found (e.g. from a background service, or during process startup
     * before any activity exists), [detectIdeLocale] falls back to the
     * caller's `Context`. That fallback depends on the caller and must not
     * poison the cache: a later call with an activity available would
     * otherwise keep returning the wrong locale forever.
     *
     * This cache is intentionally **not** consulted when the user has forced
     * a specific language (see the class-level *User override* note), and the
     * forced-language path never **writes** to it either. Keeping the two
     * concerns separate means that switching from a forced language back to
     * [PluginSettings.LANGUAGE_AUTO] still returns the correct host locale
     * without having to re-run the (expensive) reflection-based host lookup
     * on every string resolution.
     */
    @Volatile
    private var cachedIdeLocale: Locale? = null

    /**
     * Returns the locale the plugin UI should use.
     *
     * Resolution order:
     *
     *  1. **User-selected language.** When [PluginSettings.getUiLanguage]
     *     returns any code other than [PluginSettings.LANGUAGE_AUTO], the
     *     corresponding [Locale] is returned immediately via
     *     [localeForLanguageCode], skipping both the host-locale cache and
     *     the host activity lookup. This is what allows a newly opened dialog
     *     to switch language as soon as the user changes the selector,
     *     without a process restart.
     *  2. **Cached host locale.** The result of the previous successful host
     *     lookup, if any. Only host-detected locales are cached; the forced
     *     path never writes to this cache (see the class-level *Detection
     *     cost and caching* note).
     *  3. **Live host lookup.** Reads the foreground host activity's
     *     `Configuration` and caches the result.
     *  4. **Caller fallback.** Uses the supplied [context]'s configuration
     *     (typically the system default). Not cached, because it depends on
     *     the caller and would otherwise poison the cache for later calls
     *     that do have a host activity available.
     *
     * An unknown or corrupted `ui_language` value (e.g. a language that was
     * removed in a future version) is treated the same as
     * [PluginSettings.LANGUAGE_AUTO], so a corrupted preference always falls
     * back to the pre-selector behaviour (follow the IDE) rather than pinning
     * the UI to some arbitrary locale.
     *
     * Callers that only need a formatted string should go through
     * [com.devdayton.onecodeforai.ui.UiFactory.pluginString], which also
     * handles loading the plugin's own `Resources` with this locale applied.
     */
    fun detectIdeLocale(context: Context): Locale {
        // 1. Honour the user's explicit language selection (any language,
        //    including English). `localeForLanguageCode` returns `null` for
        //    `LANGUAGE_AUTO` and for unknown/corrupted codes; both cases fall
        //    through to the host-detection path below, which is exactly what
        //    the pre-selector behaviour was and what a corrupted preference
        //    should degrade to.
        //
        //    This check runs on every call (rather than being memoized)
        //    because that is what guarantees that reopening the dialog after
        //    changing the preference shows the new language. Already-open
        //    windows keep their previous language, which matches Android's
        //    standard locale-switch behavior.
        val selectedCode = PluginSettings.getUiLanguage(context)
        localeForLanguageCode(selectedCode)?.let { return it }

        // 2. Cached host locale.
        cachedIdeLocale?.let { return it }

        // 3. Live host lookup.
        val host = findHostActivity()
        if (host != null) {
            val locale = localeOf(host.resources.configuration)
            cachedIdeLocale = locale
            return locale
        }

        // 4. No forced language, no cached value, and no host activity:
        //    fall back to the caller's configuration. Do not cache this —
        //    see the `cachedIdeLocale` KDoc.
        return localeOf(context.resources.configuration)
    }

    /**
     * Clears the cached host locale so the next call re-detects it from the
     * host.
     *
     * Called by [com.devdayton.onecodeforai.OneCodeForAISettingsPage]
     * whenever the language selector changes, and also available for hosts
     * that support a runtime language switch. The default CodeAssist flow
     * requires restarting the IDE after changing its own language, so a
     * manual call is normally unnecessary outside those two cases.
     *
     * Note that clearing this cache is not strictly required for the
     * *forced* language path to work (the forced locale is checked before
     * the cache read), but it keeps the "switch from forced back to auto
     * after the host language changed" path equivalent to a fresh process
     * launch, and it is what allows
     * [com.devdayton.onecodeforai.ui.UiFactory.invalidateLocalizedCtxCache]
     * to rebuild the plugin's localized `Context` against the new resolution.
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
     * Because [detectIdeLocale] honours the language preference (including
     * the "follow the IDE" default), an activity created *after* the setting
     * is changed automatically starts in the newly selected language without
     * any extra plumbing here.
     *
     * For the three RTL locales (`ar`, `fa`, `he`), the `Configuration` that
     * this method applies inherits Android's automatic `layoutDirection`
     * resolution, so no extra mirroring step is needed. See the class-level
     * *Right-to-left languages* note for the framework-side implications.
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
     * Extracts the primary locale from a `Configuration`.
     *
     * `Configuration.getLocales()` was added in API 24, and the plugin's
     * `minSdk` is 26, so there is no legacy `Configuration.locale` branch to
     * fall back to. The deprecated field is unreachable here by construction.
     */
    private fun localeOf(config: Configuration): Locale =
        config.locales[0] ?: Locale.getDefault()

    /**
     * Maps a persisted UI language code (as stored by
     * [PluginSettings.getUiLanguage]) to the [Locale] the plugin's resources
     * should be resolved against, or `null` when the caller should fall back
     * to host-language detection.
     *
     * ### Return value contract
     *  - `null` means "no forced language, follow the IDE". This is returned
     *    for [PluginSettings.LANGUAGE_AUTO] and for any unrecognised code
     *    (e.g. a language that was removed in a future version, or a
     *    corrupted preference). Falling back to `null` rather than to an
     *    arbitrary Locale means a corrupted preference silently degrades to
     *    the pre-selector behaviour, which is the safest of all failure
     *    modes.
     *  - any non-null [Locale] means "force this language".
     *
     * ### Folder mapping
     * Each entry is chosen so that the resulting [Locale] matches exactly
     * one `values-<code>/` folder in this project:
     *
     *  - `en` → `Locale.ENGLISH` → `values/` (the default, English).
     *  - `es` → `Locale("es", "")` → `values-es/`.
     *  - `fr` → `Locale.FRENCH` → `values-fr/`.
     *  - `de` → `Locale.GERMAN` → `values-de/`.
     *  - `it` → `Locale.ITALIAN` → `values-it/`.
     *  - `pt` → `Locale("pt", "BR")` → `values-pt-rBR/`.
     *  - `ru`, `pl`, `cs`, `ro`, `el`, `tr`, `ar`, `hi`, `vi` → their own
     *    `values-<code>/` folder.
     *  - `in` → `Locale("in", "ID")` → `values-in/`. `in` is the **legacy**
     *    ISO-639 code for Indonesian; the modern code `id` does not match
     *    the `values-in/` folder name on all platform versions, so the
     *    legacy form is used deliberately here.
     *  - `ja` → `Locale.JAPANESE` → `values-ja/`.
     *  - `ko` → `Locale.KOREAN` → `values-ko/`.
     *  - `zh` → `Locale("zh", "CH")` → `values-zh-rCH/`. The project ships
     *    the Simplified Chinese translation under the unusual region code
     *    `CH` (Switzerland), not `CN` (China) or `TW` (Taiwan); if the
     *    folder is ever renamed to `values-zh/`, `values-zh-rCN/` or
     *    `values-zh-rTW/`, this line must be updated to match, otherwise the
     *    Chinese selection silently falls back to English.
     *
     * ### Languages added after 1.1.0 (i18n expansion pass)
     *  - `bn` → `Locale("bn", "")` → `values-bn/` (Bengali).
     *  - `ca` → `Locale("ca", "")` → `values-ca/` (Catalan).
     *  - `fa` → `Locale("fa", "")` → `values-fa/` (Persian, RTL).
     *  - `he` → `Locale("he", "")` → `values-he/` (Hebrew, RTL). Modern
     *    ISO-639 code; the legacy `iw` alias is not used. See the class-level
     *    *Right-to-left languages* note for the fallback reasoning.
     *  - `nl` → `Locale("nl", "")` → `values-nl/` (Dutch).
     *  - `sv` → `Locale("sv", "")` → `values-sv/` (Swedish).
     *  - `th` → `Locale("th", "")` → `values-th/` (Thai).
     *  - `uk` → `Locale("uk", "")` → `values-uk/` (Ukrainian).
     *
     * These branches are **appended after** the pre-1.1.0 entries rather
     * than inserted in the middle, so the relative order of the `when`
     * cases — and therefore the persisted value of any caller that happens
     * to iterate this expression — stays stable across releases. The
     * `localeForLanguageCode` mapping itself is not indexed by the dropdown
     * selection (see the `LANGUAGE_OPTIONS` KDoc in `OneCodeForAISettingsPage`
     * for that separate, index-sensitive list), so the ordering within this
     * `when` is a readability concern rather than a compatibility one.
     *
     * ### Why the deprecated constructors
     * `Locale(String)` and `Locale(String, String)` are marked deprecated in
     * Java 19, but they are the most reliable way to produce a `Locale`
     * whose `toString()` matches the `values-<code>/` folder qualifiers
     * exactly. `Locale.forLanguageTag` follows BCP-47, which does not accept
     * the legacy `in` code and treats some region codes slightly
     * differently, so using it here would trade a compile-time deprecation
     * warning for runtime mismatches in resource resolution. The suppression
     * is scoped to this single function so it cannot hide unrelated
     * deprecations.
     */
    @Suppress("DEPRECATION")
    private fun localeForLanguageCode(code: String): Locale? = when (code) {
        // Follow the IDE: signal "no forced language".
        PluginSettings.LANGUAGE_AUTO -> null

        // --- Pre-1.1.0 languages ---
        "en" -> Locale.ENGLISH
        "es" -> Locale("es", "")
        "fr" -> Locale.FRENCH
        "de" -> Locale.GERMAN
        "it" -> Locale.ITALIAN
        "pt" -> Locale("pt", "BR")   // values-pt-rBR/
        "ru" -> Locale("ru", "")
        "pl" -> Locale("pl", "")
        "cs" -> Locale("cs", "")
        "ro" -> Locale("ro", "")
        "el" -> Locale("el", "")
        "tr" -> Locale("tr", "")
        "ar" -> Locale("ar", "")     // RTL
        "hi" -> Locale("hi", "")
        "in" -> Locale("in", "ID")   // values-in/ (legacy ISO-639 code)
        "vi" -> Locale("vi", "")
        "ja" -> Locale.JAPANESE
        "ko" -> Locale.KOREAN
        "zh" -> Locale("zh", "CH")   // values-zh-rCH/

        // --- Languages added after 1.1.0 (i18n expansion pass) ---
        // Appended after the pre-1.1.0 block so the mapping stays easy to
        // audit against the release that introduced each language.
        "bn" -> Locale("bn", "")     // values-bn/ (Bengali)
        "ca" -> Locale("ca", "")     // values-ca/ (Catalan)
        "fa" -> Locale("fa", "")     // values-fa/ (Persian, RTL)
        "he" -> Locale("he", "")     // values-he/ (Hebrew, RTL)
        "nl" -> Locale("nl", "")     // values-nl/ (Dutch)
        "sv" -> Locale("sv", "")     // values-sv/ (Swedish)
        "th" -> Locale("th", "")     // values-th/ (Thai)
        "uk" -> Locale("uk", "")     // values-uk/ (Ukrainian)

        // Unknown code: treat as "auto" (fall through to host detection).
        // This is the safest failure mode for a corrupted preference.
        else -> null
    }
}