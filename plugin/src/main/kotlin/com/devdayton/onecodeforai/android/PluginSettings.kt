package com.devdayton.onecodeforai.android

import android.content.Context

object PluginSettings {

    internal const val SETTINGS_FILE = "onecode_settings"

    private const val KEY_GIT_ENABLED = "git_enabled"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val KEY_ANIMATIONS_ENABLED = "animations_enabled"
    private const val KEY_TOTAL_EXPORT_LIMIT_KB = "total_export_limit_kb"

    private const val KEY_TREE_SHOW_LOCKED = "tree_show_locked"

    private const val KEY_UI_LANGUAGE = "ui_language"

    private const val KEY_UI_THEME = "ui_theme"

    private const val KEY_PARENT_SEARCH_LIMIT = "parent_search_limit"

    private const val THEME_DEFAULT = "dark"

    private const val DEFAULT_PARENT_SEARCH_LIMIT = 10

    private const val MAX_PARENT_SEARCH_LIMIT = 50

    private const val KEY_LEGACY_FORCE_ENGLISH = "force_english"

    const val LANGUAGE_AUTO = "auto"

    private const val TAG = "Settings"

    fun isGitEnabled(context: Context): Boolean = read(context, KEY_GIT_ENABLED, true)

    fun setGitEnabled(context: Context, enabled: Boolean) =
        write(context, KEY_GIT_ENABLED, enabled)

    fun areNotificationsEnabled(context: Context): Boolean =
        read(context, KEY_NOTIFICATIONS_ENABLED, true)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) =
        write(context, KEY_NOTIFICATIONS_ENABLED, enabled)

    fun areAnimationsEnabled(context: Context): Boolean =
        read(context, KEY_ANIMATIONS_ENABLED, true)

    fun setAnimationsEnabled(context: Context, enabled: Boolean) =
        write(context, KEY_ANIMATIONS_ENABLED, enabled)

    fun isTreeShowLockedEnabled(context: Context): Boolean =
        read(context, KEY_TREE_SHOW_LOCKED, false)

    fun setTreeShowLockedEnabled(context: Context, enabled: Boolean) =
        write(context, KEY_TREE_SHOW_LOCKED, enabled)

    fun getUiLanguage(context: Context): String =
        readString(context, KEY_UI_LANGUAGE, LANGUAGE_AUTO)

    fun setUiLanguage(context: Context, languageCode: String) =
        writeString(context, KEY_UI_LANGUAGE, languageCode)

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

    @Deprecated(
        message = "Use getUiLanguage(context) == \"en\" instead.",
        replaceWith = ReplaceWith("getUiLanguage(context) == \"en\"")
    )
    fun isForceEnglishEnabled(context: Context): Boolean =
        getUiLanguage(context) == "en"

    @Deprecated(
        message = "Use setUiLanguage(context, if (enabled) \"en\" else LANGUAGE_AUTO) instead.",
        replaceWith = ReplaceWith(
            "setUiLanguage(context, if (enabled) \"en\" else LANGUAGE_AUTO)"
        )
    )
    fun setForceEnglishEnabled(context: Context, enabled: Boolean) =
        setUiLanguage(context, if (enabled) "en" else LANGUAGE_AUTO)

    fun getUiTheme(context: Context): String =
        readString(context, KEY_UI_THEME, THEME_DEFAULT)

    fun setUiTheme(context: Context, themeCode: String) =
        writeString(context, KEY_UI_THEME, themeCode)

    fun getTotalExportLimitKb(context: Context): Int =
        readInt(context, KEY_TOTAL_EXPORT_LIMIT_KB, 0)

    fun setTotalExportLimitKb(context: Context, value: Int) =
        writeInt(context, KEY_TOTAL_EXPORT_LIMIT_KB, value.coerceAtLeast(0))

    fun getParentSearchLimit(context: Context): Int =
        readInt(context, KEY_PARENT_SEARCH_LIMIT, DEFAULT_PARENT_SEARCH_LIMIT)
            .coerceIn(0, MAX_PARENT_SEARCH_LIMIT)

    fun setParentSearchLimit(context: Context, value: Int) =
        writeInt(
            context,
            KEY_PARENT_SEARCH_LIMIT,
            value.coerceIn(0, MAX_PARENT_SEARCH_LIMIT)
        )

    private fun read(context: Context, key: String, fallback: Boolean): Boolean {
        val value = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .getBoolean(key, fallback)

        PluginLogger.d(TAG, "READ [$key] -> $value (fallback=$fallback)")

        return value
    }

    private fun write(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()

        PluginLogger.d(TAG, "WRITE [$key] -> $value")
    }

    private fun readInt(context: Context, key: String, fallback: Int): Int {
        val value = runCatching {
            context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
                .getInt(key, fallback)
        }.getOrDefault(fallback)

        PluginLogger.d(TAG, "READ [$key] -> $value (fallback=$fallback)")

        return value
    }

    private fun writeInt(context: Context, key: String, value: Int) {
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(key, value)
            .apply()

        PluginLogger.d(TAG, "WRITE [$key] -> $value")
    }

    private fun readString(context: Context, key: String, fallback: String): String {
        val value = runCatching {
            context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
                .getString(key, fallback) ?: fallback
        }.getOrDefault(fallback)

        PluginLogger.d(TAG, "READ [$key] -> $value (fallback=$fallback)")

        return value
    }

    private fun writeString(context: Context, key: String, value: String) {
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()

        PluginLogger.d(TAG, "WRITE [$key] -> $value")
    }
}