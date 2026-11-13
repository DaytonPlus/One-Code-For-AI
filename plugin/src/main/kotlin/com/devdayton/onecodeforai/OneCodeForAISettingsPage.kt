package com.devdayton.onecodeforai

import android.content.Context
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.HostEnvironment
import com.devdayton.onecodeforai.android.LanguageHelper
import com.devdayton.onecodeforai.android.PluginLogger
import com.devdayton.onecodeforai.android.PluginSettings
import com.devdayton.onecodeforai.ui.CodeAssistTheme
import com.devdayton.onecodeforai.ui.UiFactory
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage

internal class OneCodeForAISettingsPage : SettingsPage {

    override val id: String = "com.devdayton.onecodeforai"
    override val title: String = "One Code For AI"
    override val iconId: String = "sparkle"

    private val tag = "SettingsPage"

    override fun controls(): List<SettingControl> {
        val context = HostEnvironment.applicationContext() ?: run {
            PluginLogger.w(tag, "controls() called but application context is null.")
            return emptyList()
        }

        PluginSettings.migrateLegacyLanguagePreference(context)

        fun text(resourceId: Int, fallback: String): String =
            context.pluginString(resourceId).ifEmpty { fallback }

        val gitEnabled = PluginSettings.isGitEnabled(context)
        val animationsEnabled = PluginSettings.areAnimationsEnabled(context)
        val notificationsEnabled = PluginSettings.areNotificationsEnabled(context)
        val currentLanguage = PluginSettings.getUiLanguage(context)
        val currentTheme = PluginSettings.getUiTheme(context)
        val totalLimitKb = PluginSettings.getTotalExportLimitKb(context)
        val parentSearchLimit = PluginSettings.getParentSearchLimit(context)

        PluginLogger.d(
            tag,
            "Rendering controls. Git=$gitEnabled, Anim=$animationsEnabled, " +
                "Notif=$notificationsEnabled, Language=$currentLanguage, " +
                "Theme=$currentTheme, TotalLimitKb=$totalLimitKb, " +
                "ParentSearchLimit=$parentSearchLimit"
        )

        val languageChoices = LANGUAGE_OPTIONS.map { (code, label) ->
            SettingControl.Choice.Option(code, label)
        }

        val themeChoices = THEME_OPTIONS.map { (code, fallbackLabel) ->
            SettingControl.Choice.Option(
                code,
                when (code) {
                    CodeAssistTheme.THEME_SYSTEM -> text(
                        R.string.settings_ui_theme_system,
                        fallbackLabel
                    )
                    CodeAssistTheme.THEME_LIGHT -> text(
                        R.string.settings_ui_theme_light,
                        fallbackLabel
                    )
                    CodeAssistTheme.THEME_DARK -> text(
                        R.string.settings_ui_theme_dark,
                        fallbackLabel
                    )
                    else -> fallbackLabel
                }
            )
        }

        return listOf(
            SettingControl.Toggle(
                KEY_GIT_ENABLED,
                text(R.string.settings_git_global, "Git integration"),
                text(
                    R.string.settings_git_global_desc,
                    "Detects repositories and enables every Git feature. Disable to force plain-folder mode even inside a repo. .gitignore rules still apply.",
                ),
                gitEnabled,
            ),
            SettingControl.Toggle(
                KEY_ANIMATIONS_ENABLED,
                text(R.string.settings_animations, "Animations"),
                text(
                    R.string.settings_animations_desc,
                    "Fade and scale transitions in the dialog, tree and preset spinner. Disable on low-end devices.",
                ),
                animationsEnabled,
            ),
            SettingControl.Toggle(
                KEY_NOTIFICATIONS_ENABLED,
                text(R.string.settings_notifications, "Generation notifications"),
                text(
                    R.string.settings_notifications_desc,
                    "Progress, completion, and error notifications while exporting. Disable to silence the notification tray.",
                ),
                notificationsEnabled,
            ),

            SettingControl.Choice(
                KEY_UI_LANGUAGE,
                text(R.string.settings_ui_language, "Interface language"),
                text(
                    R.string.settings_ui_language_desc,
                    "Language used by the plugin UI. \"Auto\" follows the IDE language. " +
                        "Only newly opened dialogs and screens pick up the change; " +
                        "already-open windows keep their current language until reopened.",
                ),
                currentLanguage,
                languageChoices,
            ),

            SettingControl.Choice(
                KEY_UI_THEME,
                text(R.string.settings_ui_theme, "Theme"),
                text(
                    R.string.settings_ui_theme_desc,
                    "Color scheme used by the plugin UI. \"System\" follows the device's " +
                        "light/dark mode. Only newly opened dialogs and screens pick up " +
                        "the change; already-open windows keep their current theme until reopened.",
                ),
                currentTheme,
                themeChoices,
            ),
            SettingControl.Text(
                KEY_TOTAL_EXPORT_LIMIT_KB,
                text(R.string.settings_total_export_limit, "Total export size limit (KB)"),
                text(
                    R.string.settings_total_export_limit_desc,
                    "Hard cap, in kilobytes, on the total size of a single dump. When reached, " +
                        "the export stops including files and appends a truncation notice. " +
                        "Most useful when the per-file size limit is disabled. Set to 0 for no limit.",
                ),
                totalLimitKb.toString(),
            ),

            SettingControl.Text(
                KEY_PARENT_SEARCH_LIMIT,
                text(
                    R.string.settings_parent_search_limit,
                    "Parent folder search limit",
                ),
                text(
                    R.string.settings_parent_search_limit_desc,
                    "How many parent folders the plugin walks up looking for " +
                        ".gitignore or the CodeAssist project root. Increase for very " +
                        "deep package trees. Set to 0 to never leave the selected folder.",
                ),
                parentSearchLimit.toString(),
            ),
        )
    }

    override fun onChanged(key: String, values: PreferenceReader) {
        PluginLogger.d(tag, "onChanged triggered for key: '$key'")

        val context = HostEnvironment.applicationContext() ?: run {
            PluginLogger.e(tag, "onChanged failed: application context is null.")
            return
        }

        if (key !in SETTING_KEYS) {
            PluginLogger.w(tag, "onChanged ignored: key '$key' not in SETTING_KEYS.")
            return
        }

        when (key) {
            KEY_GIT_ENABLED -> {
                val value = values.getBooleanSafe(key, true)
                PluginLogger.d(tag, "Extracted value for '$key': $value")
                PluginSettings.setGitEnabled(context, value)
            }
            KEY_ANIMATIONS_ENABLED -> {
                val value = values.getBooleanSafe(key, true)
                PluginLogger.d(tag, "Extracted value for '$key': $value")
                PluginSettings.setAnimationsEnabled(context, value)
            }
            KEY_NOTIFICATIONS_ENABLED -> {
                val value = values.getBooleanSafe(key, true)
                PluginLogger.d(tag, "Extracted value for '$key': $value")
                PluginSettings.setNotificationsEnabled(context, value)
            }
            KEY_UI_LANGUAGE -> {

                val raw = values.getStringSafe(key, PluginSettings.LANGUAGE_AUTO)
                val code = resolveLanguageCode(raw)
                PluginLogger.d(tag, "Extracted value for '$key': '$raw' -> '$code'")
                PluginSettings.setUiLanguage(context, code)

                LanguageHelper.invalidateCache()
                UiFactory.invalidateLocalizedCtxCache()

            }
            KEY_UI_THEME -> {

                val raw = values.getStringSafe(key, CodeAssistTheme.THEME_SYSTEM)
                val code = resolveThemeCode(raw)
                PluginLogger.d(tag, "Extracted value for '$key': '$raw' -> '$code'")
                PluginSettings.setUiTheme(context, code)

            }
            KEY_TOTAL_EXPORT_LIMIT_KB -> {
                val raw = values.getStringSafe(key, "0")
                val parsed = raw.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                PluginLogger.d(tag, "Extracted value for '$key': '$raw' -> $parsed")
                PluginSettings.setTotalExportLimitKb(context, parsed)
            }
            KEY_PARENT_SEARCH_LIMIT -> {

                val raw = values.getStringSafe(key, "10")
                val parsed = raw.trim().toIntOrNull()?.coerceAtLeast(0) ?: 10
                PluginLogger.d(tag, "Extracted value for '$key': '$raw' -> $parsed")
                PluginSettings.setParentSearchLimit(context, parsed)
            }
        }
    }

    private fun resolveLanguageCode(raw: String): String {

        if (LANGUAGE_OPTIONS.any { it.first == raw }) return raw

        raw.trim().toIntOrNull()?.let { index ->
            if (index in LANGUAGE_OPTIONS.indices) {
                return LANGUAGE_OPTIONS[index].first
            }
        }

        LANGUAGE_OPTIONS.firstOrNull { it.second == raw }?.let { return it.first }

        return PluginSettings.LANGUAGE_AUTO
    }

    private fun resolveThemeCode(raw: String): String {

        if (THEME_OPTIONS.any { it.first == raw }) return raw

        raw.trim().toIntOrNull()?.let { index ->
            if (index in THEME_OPTIONS.indices) {
                return THEME_OPTIONS[index].first
            }
        }

        THEME_OPTIONS.firstOrNull { it.second.equals(raw, ignoreCase = true) }
            ?.let { return it.first }

        return CodeAssistTheme.THEME_SYSTEM
    }

    private fun PreferenceReader.getBooleanSafe(key: String, defaultValue: Boolean): Boolean {
        return try {
            val methods = javaClass.methods

            if (key == KEY_GIT_ENABLED) {
                val signatures = methods.joinToString {
                    it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")"
                }
                PluginLogger.d(tag, "PreferenceReader available methods: $signatures")
            }

            val methodWithDefault = methods.firstOrNull {
                (it.name == "bool" || it.name == "getBoolean" || it.name == "get") &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            if (methodWithDefault != null) {
                PluginLogger.d(tag, "Using method: ${methodWithDefault.name}(String, Boolean)")
                return methodWithDefault.invoke(this, key, defaultValue) as? Boolean ?: defaultValue
            }

            val methodSingleArg = methods.firstOrNull {
                (it.name == "bool" || it.name == "getBoolean" || it.name == "get") &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            }
            if (methodSingleArg != null) {
                PluginLogger.d(tag, "Using method: ${methodSingleArg.name}(String)")
                return (methodSingleArg.invoke(this, key) as? Boolean) ?: defaultValue
            }

            PluginLogger.w(tag, "Reflection failed to find a suitable getter for key: '$key'")
            defaultValue
        } catch (e: Throwable) {
            PluginLogger.e(tag, "Reflection exception in getBooleanSafe for key: '$key'", e)
            defaultValue
        }
    }

    private fun PreferenceReader.getStringSafe(key: String, defaultValue: String): String {
        return try {
            val methods = javaClass.methods

            val stringMethod = methods.firstOrNull {
                (it.name == "string" || it.name == "getString" || it.name == "get") &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == String::class.java
            }
            if (stringMethod != null) {
                PluginLogger.d(tag, "Using method: ${stringMethod.name}(String, String)")
                val result = stringMethod.invoke(this, key, defaultValue)
                if (result is String) return result
                if (result != null) return result.toString()
            }

            val intMethod = methods.firstOrNull {
                (it.name == "int" || it.name == "getInt" || it.name == "get") &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            if (intMethod != null) {
                PluginLogger.d(tag, "Using method: ${intMethod.name}(String, Int)")
                val fallbackInt = defaultValue.toIntOrNull() ?: 0
                val result = intMethod.invoke(this, key, fallbackInt)
                if (result is Int) return result.toString()
            }

            PluginLogger.w(tag, "Reflection failed to find a suitable string getter for key: '$key'")
            defaultValue
        } catch (e: Throwable) {
            PluginLogger.e(tag, "Reflection exception in getStringSafe for key: '$key'", e)
            defaultValue
        }
    }

    private companion object {
        const val KEY_GIT_ENABLED = "git_enabled"
        const val KEY_ANIMATIONS_ENABLED = "animations_enabled"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_TOTAL_EXPORT_LIMIT_KB = "total_export_limit_kb"
        const val KEY_PARENT_SEARCH_LIMIT = "parent_search_limit"

        const val KEY_UI_LANGUAGE = "ui_language"

        const val KEY_UI_THEME = "ui_theme"

        val SETTING_KEYS = setOf(
            KEY_GIT_ENABLED,
            KEY_ANIMATIONS_ENABLED,
            KEY_NOTIFICATIONS_ENABLED,
            KEY_UI_LANGUAGE,
            KEY_UI_THEME,
            KEY_TOTAL_EXPORT_LIMIT_KB,
            KEY_PARENT_SEARCH_LIMIT,
        )

        val LANGUAGE_OPTIONS: List<Pair<String, String>> = listOf(
            PluginSettings.LANGUAGE_AUTO to "Auto (follow IDE)",

            "en" to "English",
            "es" to "Español",
            "fr" to "Français",
            "de" to "Deutsch",
            "it" to "Italiano",
            "pt" to "Português (Brasil)",
            "ru" to "Русский",
            "pl" to "Polski",
            "cs" to "Čeština",
            "ro" to "Română",
            "el" to "Ελληνικά",
            "tr" to "Türkçe",
            "ar" to "العربية",
            "hi" to "हिन्दी",
            "in" to "Bahasa Indonesia",
            "vi" to "Tiếng Việt",
            "ja" to "日本語",
            "ko" to "한국어",
            "zh" to "中文",

            "bn" to "বাংলা",
            "ca" to "Català",
            "fa" to "فارسی",
            "he" to "עברית",
            "nl" to "Nederlands",
            "sv" to "Svenska",
            "th" to "ไทย",
            "uk" to "Українська"
        )

        val THEME_OPTIONS: List<Pair<String, String>> = listOf(
            CodeAssistTheme.THEME_SYSTEM to "System (follow device)",
            CodeAssistTheme.THEME_LIGHT to "Light",
            CodeAssistTheme.THEME_DARK to "Dark"
        )
    }
}