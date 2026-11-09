package com.devdayton.onecodeforai.android

import android.content.Context

/**
 * Single source of truth for every runtime-visible preference owned by the plugin.
 *
 * ### Architecture
 * Both the readers (e.g., `ConfigurationDialog`, `ExportCoordinator`, `ProjectTreeView`)
 * and the writer (`OneCodeForAISettingsPage`) go through this object.
 * Centralising access here guarantees that the preference file name and the
 * underlying keys are declared exactly once, preventing a rename from silently
 * disconnecting a UI toggle from its runtime consumer.
 *
 * ### Default Values
 * All flags default to `true`, matching the "everything enabled" expectation of a
 * fresh install. If a key is missing from [SharedPreferences], the fallback is `true`.
 * The two exceptions are [getUiLanguage] and [getTotalExportLimitKb], which
 * default to [LANGUAGE_AUTO] and `0` respectively: they are opt-in switches
 * that must only take effect once the user explicitly changes them in the
 * settings page, so a fresh install preserves the previous behaviour (follow
 * the host IDE language, no total-size cap) without any migration.
 *
 * ### Language model (replaces the former "Force English UI" toggle)
 * The plugin previously exposed a single boolean `force_english` preference
 * that hardcoded the UI to English. With the addition of ~20 localisations,
 * that model no longer scales: a user whose IDE runs in French may want the
 * plugin in Spanish — a language neither the system nor the IDE is using.
 * The preference is therefore now a **language code** stored under
 * [KEY_UI_LANGUAGE]:
 *
 *  - [LANGUAGE_AUTO] ("auto") — follow the IDE language (default).
 *  - an ISO-639 code ("en", "es", "fr", "pt", "zh", …) — force that language.
 *
 * The code is stored as a plain string rather than an integer index so that
 * inserting a new language in the middle of the selector's option list does
 * not silently change the user's saved selection. That is the same rationale
 * that led the profile spinner to use *names* rather than indices in
 * `ConfigurationDialog`.
 *
 * ### Backward compatibility with `force_english`
 * Because `LanguageHelper.detectIdeLocale` still calls
 * [isForceEnglishEnabled] (this file is updated independently of that one),
 * both legacy methods remain available as `@Deprecated` shims that delegate
 * to [getUiLanguage]/[setUiLanguage]. The previous "force English" behaviour
 * is therefore preserved exactly: a user whose `force_english` was `true`
 * before the upgrade keeps an English UI, and can switch to another language
 * from the settings page. Once `LanguageHelper` is migrated to read
 * [getUiLanguage] directly, both shims can be deleted.
 *
 * ### Legacy migration
 * [migrateLegacyLanguagePreference] performs a one-shot, idempotent migration
 * from the old `force_english` boolean to the new string key. It is called
 * from [com.devdayton.onecodeforai.OneCodeForAISettingsPage.controls] on
 * every settings render; the guard makes subsequent calls effectively free.
 *
 * ### Threading
 * `SharedPreferences` reads and writes are synchronous in memory and safe to call
 * from any thread (including the UI thread or the export worker thread).
 */
object PluginSettings {

    /**
     * Shared preference file for all plugin-owned flags.
     *
     * Exposed as `internal` so [com.devdayton.onecodeforai.OneCodeForAISettingsPage]
     * can reuse it for other UI-specific triggers without redeclaring the string,
     * while keeping it out of the public surface.
     */
    internal const val SETTINGS_FILE = "onecode_settings"

    private const val KEY_GIT_ENABLED = "git_enabled"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val KEY_ANIMATIONS_ENABLED = "animations_enabled"
    private const val KEY_TOTAL_EXPORT_LIMIT_KB = "total_export_limit_kb"

    /**
     * Preference key holding the plugin's UI language code.
     *
     * Values are either [LANGUAGE_AUTO] to follow the IDE, or an ISO-639 code
     * (e.g. `"en"`, `"es"`, `"fr"`, `"pt"`, `"zh"`) to force the plugin UI to
     * that specific language. The code is stored verbatim so that the mapping
     * to a `java.util.Locale` lives in exactly one place
     * ([com.devdayton.onecodeforai.android.LanguageHelper]) instead of being
     * duplicated between the settings writer and the locale reader.
     *
     * See the class-level *Language model* note for the full rationale.
     */
    private const val KEY_UI_LANGUAGE = "ui_language"

    /**
     * Legacy key from the former "Force English UI" toggle.
     *
     * Retained only so [migrateLegacyLanguagePreference] can read and clear
     * it. All new code must go through [getUiLanguage] / [setUiLanguage]
     * (or, temporarily, the `@Deprecated` shims).
     */
    private const val KEY_LEGACY_FORCE_ENGLISH = "force_english"

    /**
     * Sentinel value stored under [KEY_UI_LANGUAGE] when the plugin should
     * follow the IDE language.
     *
     * Declared as a public constant so that callers (settings page, tests,
     * migration) can compare against the same literal that the writer uses;
     * a typo in one call site would otherwise silently disable the
     * "auto-follow" behaviour.
     */
    const val LANGUAGE_AUTO = "auto"

    /** Tag for logging read/write operations in debug builds. */
    private const val TAG = "Settings"

    // ------------------------------------------------------------------
    // Git Integration
    // ------------------------------------------------------------------

    /**
     * Master kill switch for every Git feature.
     *
     * When `false`, the plugin never calls `GitUtils.findGitRoot` and never opens
     * a repository: a folder inside a Git repo behaves exactly like a plain folder.
     *
     * Note: This is intentionally separate from the `.gitignore` toggle, which
     * remains independent because parsing `.gitignore` only requires reading a
     * text file, not opening a Git repository.
     */
    fun isGitEnabled(context: Context): Boolean = read(context, KEY_GIT_ENABLED, true)

    /**
     * Persists the Git master kill switch.
     */
    fun setGitEnabled(context: Context, enabled: Boolean) =
        write(context, KEY_GIT_ENABLED, enabled)

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    /**
     * Gate for the progress, completion, and error notifications emitted while
     * exporting.
     *
     * When `false`, the [com.devdayton.onecodeforai.android.ExportCoordinator]
     * will skip posting any system notifications, but the in-app result panel
     * inside the dialog will continue to function normally.
     */
    fun areNotificationsEnabled(context: Context): Boolean =
        read(context, KEY_NOTIFICATIONS_ENABLED, true)

    /**
     * Persists the notifications gate.
     */
    fun setNotificationsEnabled(context: Context, enabled: Boolean) =
        write(context, KEY_NOTIFICATIONS_ENABLED, enabled)

    // ------------------------------------------------------------------
    // Animations
    // ------------------------------------------------------------------

    /**
     * Gate for all enter, scale, and fade animations in the dialog, tree,
     * and preset spinner.
     *
     * Useful on low-end devices or huge projects where rendering every frame
     * of an animation might cause dropped frames.
     */
    fun areAnimationsEnabled(context: Context): Boolean =
        read(context, KEY_ANIMATIONS_ENABLED, true)

    /**
     * Persists the animations gate.
     */
    fun setAnimationsEnabled(context: Context, enabled: Boolean) =
        write(context, KEY_ANIMATIONS_ENABLED, enabled)

    // ------------------------------------------------------------------
    // UI language (replaces the former "Force English UI" toggle)
    // ------------------------------------------------------------------

    /**
     * Returns the language code the plugin UI should use.
     *
     * The value is either [LANGUAGE_AUTO] (follow the IDE language — the
     * default and the pre-selector behaviour) or an ISO-639 code such as
     * `"en"`, `"es"`, `"fr"`, `"pt"`, `"zh"`.
     *
     * Consumers should not compare the result against `"auto"` directly;
     * [com.devdayton.onecodeforai.android.LanguageHelper.detectIdeLocale]
     * already handles the mapping from code → [java.util.Locale] (including
     * the `"auto"` branch) and is the single place where the plugin's
     * effective locale is decided.
     *
     * If the user has never opened the settings page, this returns
     * [LANGUAGE_AUTO] and the plugin follows the IDE language, which is
     * what the vast majority of users expect. A user who previously had
     * `force_english = true` is migrated to `"en"` by
     * [migrateLegacyLanguagePreference] the first time the settings page is
     * opened; in the meantime the [isForceEnglishEnabled] shim keeps the
     * legacy behaviour intact.
     */
    fun getUiLanguage(context: Context): String =
        readString(context, KEY_UI_LANGUAGE, LANGUAGE_AUTO)

    /**
     * Persists the UI language code.
     *
     * The caller is responsible for invalidating the locale-dependent caches
     * after this write — see
     * [com.devdayton.onecodeforai.OneCodeForAISettingsPage.onChanged] for the
     * exact sequence. This method only persists; it deliberately does **not**
     * touch `LanguageHelper` or `UiFactory` so that it stays usable from
     * non-Android code paths (e.g. a JVM-side config importer) without
     * triggering a locale refresh.
     */
    fun setUiLanguage(context: Context, languageCode: String) =
        writeString(context, KEY_UI_LANGUAGE, languageCode)

    /**
     * One-shot migration from the legacy `force_english` boolean to the new
     * `ui_language` string.
     *
     * ### Why this exists
     * Before the language selector was introduced, the only way to deviate
     * from the IDE language was to set `force_english = true`. Users who did
     * that expect an English UI to survive the upgrade. Without migration,
     * [getUiLanguage] would default to [LANGUAGE_AUTO] and those users would
     * silently switch back to their IDE language.
     *
     * ### How it works
     *  - If [KEY_UI_LANGUAGE] is already present, the user has either already
     *    been migrated or has explicitly picked a language: do nothing.
     *  - Otherwise, if the legacy `force_english` flag was `true`, seed the
     *    new key with `"en"` and remove the legacy key.
     *  - Otherwise, seed the new key with [LANGUAGE_AUTO] and remove the
     *    legacy key. This "commits" the migration so subsequent calls do
     *    not re-check the (now meaningless) legacy flag.
     *
     * ### Idempotency
     * The `contains(KEY_UI_LANGUAGE)` guard makes the function idempotent:
     * calling it on every settings-page render only performs real work once
     * per install, and only when the user actually had a legacy preference
     * set.
     */
    fun migrateLegacyLanguagePreference(context: Context) {
        val prefs = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_UI_LANGUAGE)) return
        val legacyForceEnglish = prefs.getBoolean(KEY_LEGACY_FORCE_ENGLISH, false)
        val migrated = if (legacyForceEnglish) "en" else LANGUAGE_AUTO
        prefs.edit()
            .putString(KEY_UI_LANGUAGE, migrated)
            .remove(KEY_LEGACY_FORCE_ENGLISH)
            .apply()
        PluginLogger.d(
            TAG,
            "Migrated legacy force_english=$legacyForceEnglish -> ui_language=$migrated"
        )
    }

    /**
     * Legacy shim kept for backward compatibility with
     * [com.devdayton.onecodeforai.android.LanguageHelper.detectIdeLocale],
     * which still calls this method.
     *
     * Semantically equivalent to `getUiLanguage(context) == "en"`. The
     * previous "force English" toggle is therefore preserved exactly: a user
     * whose preference was `force_english = true` continues to see an English
     * UI after the upgrade.
     *
     * **Deprecated because it only covers the "English" case.** Any other
     * language selected in the new settings page will *not* be honoured by
     * this shim (it returns `false` for anything other than `"en"`).
     * [LanguageHelper.detectIdeLocale] must be updated to consult
     * [getUiLanguage] directly (mapping the returned code to a
     * [java.util.Locale]) before non-English forced languages can take effect.
     * Once that migration is done, this method and its setter can be deleted.
     */
    @Deprecated(
        message = "Use getUiLanguage(context) == \"en\" instead, and migrate " +
            "LanguageHelper.detectIdeLocale to consult getUiLanguage directly " +
            "so all languages (not just English) are honoured.",
        replaceWith = ReplaceWith("getUiLanguage(context) == \"en\"")
    )
    fun isForceEnglishEnabled(context: Context): Boolean =
        getUiLanguage(context) == "en"

    /**
     * Legacy shim mirroring [isForceEnglishEnabled].
     *
     * `setForceEnglishEnabled(context, true)` is equivalent to
     * `setUiLanguage(context, "en")`; `setForceEnglishEnabled(context, false)`
     * is equivalent to `setUiLanguage(context, LANGUAGE_AUTO)`.
     *
     * See [isForceEnglishEnabled] for the full deprecation rationale.
     */
    @Deprecated(
        message = "Use setUiLanguage(context, if (enabled) \"en\" else LANGUAGE_AUTO) instead.",
        replaceWith = ReplaceWith(
            "setUiLanguage(context, if (enabled) \"en\" else LANGUAGE_AUTO)"
        )
    )
    fun setForceEnglishEnabled(context: Context, enabled: Boolean) =
        setUiLanguage(context, if (enabled) "en" else LANGUAGE_AUTO)

    // ------------------------------------------------------------------
    // Total export size limit
    // ------------------------------------------------------------------

    /**
     * Hard cap, in kilobytes, on the total size of a single dump.
     *
     * `0` means "no limit". This is a defence-in-depth safety net for the case
     * where the per-file size limit is disabled: without it, a project with
     * large generated files or vendored dependencies can produce multi-gigabyte
     * dumps that take a long time to generate and are unusable for AI context.
     * When the cap is reached, [com.devdayton.onecodeforai.core.CodeDumpGenerator]
     * stops including further files and appends a truncation notice to the
     * generated file so the consuming model can tell why it is incomplete.
     *
     * Read fresh on every export by
     * [com.devdayton.onecodeforai.android.ExportCoordinator], so changing the
     * value while a ConfigurationDialog is open takes effect on the next run.
     *
     * Defaults to `0` (no limit) so existing behaviour is preserved for users
     * who never open the settings page.
     */
    fun getTotalExportLimitKb(context: Context): Int =
        readInt(context, KEY_TOTAL_EXPORT_LIMIT_KB, 0)

    /**
     * Persists the total export size limit. Negative values are clamped to `0`
     * (no limit) because a negative cap has no meaningful interpretation and
     * would otherwise be silently treated as "reject everything" by the
     * generator's `> 0L` guard.
     */
    fun setTotalExportLimitKb(context: Context, value: Int) =
        writeInt(context, KEY_TOTAL_EXPORT_LIMIT_KB, value.coerceAtLeast(0))

    // ------------------------------------------------------------------
    // Low-level access
    // ------------------------------------------------------------------

    /**
     * Reads a boolean from the plugin's shared preferences.
     *
     * @param context The application or activity context.
     * @param key The preference key to read.
     * @param fallback The value to return if the key doesn't exist.
     */
    private fun read(context: Context, key: String, fallback: Boolean): Boolean {
        val value = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .getBoolean(key, fallback)

        PluginLogger.d(TAG, "READ [$key] -> $value (fallback=$fallback)")

        return value
    }

    /**
     * Writes a boolean to the plugin's shared preferences.
     *
     * @param context The application or activity context.
     * @param key The preference key to write.
     * @param value The value to save.
     */
    private fun write(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()

        PluginLogger.d(TAG, "WRITE [$key] -> $value")
    }

    /**
     * Reads an integer from the plugin's shared preferences.
     *
     * Mirrors [read] but uses the `Int` overload. Kept as a separate helper
     * rather than a generic one so that `SharedPreferences.getBoolean` vs
     * `getInt` mismatches surface as compile-time errors instead of silent
     * `ClassCastException`s at runtime.
     *
     * @param context The application or activity context.
     * @param key The preference key to read.
     * @param fallback The value to return if the key doesn't exist or has the
     *   wrong type (e.g. was written as a boolean by an older version).
     */
    private fun readInt(context: Context, key: String, fallback: Int): Int {
        val value = runCatching {
            context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
                .getInt(key, fallback)
        }.getOrDefault(fallback)

        PluginLogger.d(TAG, "READ [$key] -> $value (fallback=$fallback)")

        return value
    }

    /**
     * Writes an integer to the plugin's shared preferences.
     *
     * @param context The application or activity context.
     * @param key The preference key to write.
     * @param value The value to save.
     */
    private fun writeInt(context: Context, key: String, value: Int) {
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(key, value)
            .apply()

        PluginLogger.d(TAG, "WRITE [$key] -> $value")
    }

    /**
     * Reads a string from the plugin's shared preferences.
     *
     * Mirrors [read] / [readInt] but uses the `String` overload. The
     * `runCatching` wrapper covers the (rare but real) case where the key was
     * previously written with a different type — for example, a forward-
     * compatibility bug or a manual `adb shell` edit — and the platform
     * `SharedPreferences` throws `ClassCastException` on the read. Returning
     * the fallback in that case is strictly better than crashing the settings
     * page or the export worker.
     *
     * @param context The application or activity context.
     * @param key The preference key to read.
     * @param fallback The value to return if the key doesn't exist or has the
     *   wrong type.
     */
    private fun readString(context: Context, key: String, fallback: String): String {
        val value = runCatching {
            context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
                .getString(key, fallback) ?: fallback
        }.getOrDefault(fallback)

        PluginLogger.d(TAG, "READ [$key] -> $value (fallback=$fallback)")

        return value
    }

    /**
     * Writes a string to the plugin's shared preferences.
     *
     * @param context The application or activity context.
     * @param key The preference key to write.
     * @param value The value to save.
     */
    private fun writeString(context: Context, key: String, value: String) {
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()

        PluginLogger.d(TAG, "WRITE [$key] -> $value")
    }
}