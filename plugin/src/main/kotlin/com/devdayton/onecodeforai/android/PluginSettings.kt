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
 * The two exceptions are [isForceEnglishEnabled] and [getTotalExportLimitKb],
 * which default to `false` and `0` respectively: they are opt-in switches that
 * must only take effect once the user explicitly turns them on in the settings
 * page, so a fresh install preserves the previous behavior (follow the host IDE
 * language, no total-size cap) without any migration.
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
    private const val KEY_FORCE_ENGLISH = "force_english"
    private const val KEY_TOTAL_EXPORT_LIMIT_KB = "total_export_limit_kb"

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
    // Language override
    // ------------------------------------------------------------------

    /**
     * Forces the plugin UI to use English regardless of the IDE language.
     *
     * When `true`, [com.devdayton.onecodeforai.android.LanguageHelper.detectIdeLocale]
     * short-circuits to [java.util.Locale.ENGLISH] **before** consulting either
     * of its caches. This means a newly opened `ConfigurationDialog` or
     * `OneCodeForAIInfoActivity` picks up the selected language automatically,
     * without requiring a process restart. Already-rendered windows keep their
     * current language until reopened, which matches the standard Android
     * locale-switch behavior.
     *
     * Defaults to `false` so the plugin keeps following the host IDE language
     * out of the box, and so existing installs are unaffected by the addition
     * of this setting.
     */
    fun isForceEnglishEnabled(context: Context): Boolean =
        read(context, KEY_FORCE_ENGLISH, false)

    /**
     * Persists the force-English preference.
     *
     * Callers are expected to also invoke
     * [com.devdayton.onecodeforai.android.LanguageHelper.invalidateCache] and
     * [com.devdayton.onecodeforai.ui.UiFactory.invalidateLocalizedCtxCache]
     * after this write so the next locale resolution rebuilds its cached
     * context. The settings page does exactly this inside `onChanged`.
     */
    fun setForceEnglishEnabled(context: Context, enabled: Boolean) =
        write(context, KEY_FORCE_ENGLISH, enabled)

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
     * Defaults to `0` (no limit) so existing behavior is preserved for users
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
}