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
}