package com.devdayton.onecodeforai

import android.content.Context
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.HostEnvironment
import com.devdayton.onecodeforai.android.LanguageHelper
import com.devdayton.onecodeforai.android.PluginLogger
import com.devdayton.onecodeforai.android.PluginSettings
import com.devdayton.onecodeforai.ui.UiFactory
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage

/**
 * Global preferences for One Code For AI, rendered natively by CodeAssist.
 *
 * ### Scope
 * This page manages application-wide settings:
 * 1. Git master kill switch.
 * 2. Animations.
 * 3. Notifications.
 * 4. UI language (replaces the previous "Force English UI" toggle).
 * 5. Total export size limit (KB).
 *
 * Per-export options (like respecting .gitignore, excluding binaries, or
 * showing the file tree) are intentionally omitted here. They live inside the
 * ConfigurationDialog because they are part of the user's export profile,
 * not a global app setting.
 *
 * ### Numeric settings
 * The total export size limit is the only *numeric* setting on this page,
 * surfaced as a [SettingControl.Text] input. It is read fresh by
 * [com.devdayton.onecodeforai.android.ExportCoordinator] on every export, so
 * changing it while a ConfigurationDialog is open takes effect on the next
 * run without needing to reopen the dialog. An empty or non-numeric value is
 * coerced to `0`, which means "no limit", matching the field's default and
 * keeping the generator's `> 0L` guard valid.
 *
 * ### Language selector
 * The plugin used to expose a boolean "Force English UI" toggle. With the
 * addition of ~20 localisations, that model no longer scales: a user whose
 * IDE runs in French may want the plugin in Spanish — a language neither the
 * system nor the IDE is using. The toggle is therefore now a
 * [SettingControl.Choice] dropdown listing every bundled language plus an
 * `Auto (follow IDE)` entry. The selected option is persisted as an ISO-639
 * code (or [PluginSettings.LANGUAGE_AUTO]) under `ui_language`; the mapping
 * to a [java.util.Locale] lives in
 * [com.devdayton.onecodeforai.android.LanguageHelper.detectIdeLocale].
 *
 * The `Auto` entry is first in the list and is the default, so a fresh
 * install and a user who never opens this page both keep the pre-selector
 * behaviour (follow the IDE language). The change is otherwise a direct
 * replacement: users who had `force_english = true` are migrated to `"en"`
 * the first time this page renders (see
 * [PluginSettings.migrateLegacyLanguagePreference]), and the deprecated
 * [PluginSettings.isForceEnglishEnabled] shim keeps the previous behaviour
 * intact for [LanguageHelper] until that class is migrated to consult
 * `getUiLanguage` directly.
 *
 * Because the plugin's `Resources` are re-resolved every time a string is
 * read (through [com.devdayton.onecodeforai.ui.UiFactory.pluginString] and
 * its internal locale-aware context cache), changing this setting takes
 * effect on the next *newly opened* dialog or info screen without requiring
 * a CodeAssist restart. Already-rendered windows keep the language they were
 * created with, which matches the standard Android behaviour for runtime
 * locale changes.
 *
 * ### Cache invalidation
 * The language selector is the only preference that affects a process-wide
 * cache. Two caches depend on the resolved locale:
 *  - [LanguageHelper] memoizes the host locale.
 *  - [com.devdayton.onecodeforai.ui.UiFactory] memoizes the plugin `Context`
 *    wrapped with that locale.
 *
 * When the language changes, `onChanged` invalidates **both**, and (on a
 * best-effort basis) asks the plugin to refresh the context-menu label so
 * the entry the user sees in the IDE matches the language they just chose.
 * Already-rendered windows keep their current language until reopened,
 * matching standard Android behaviour for runtime locale changes.
 *
 * ### Reflection & Host API Stability
 * The host's PreferenceReader API has changed shape across CodeAssist
 * versions. A previous naive implementation used a faulty reflection check that
 * defaulted to true on failure, silently overwriting the user's choice to
 * "off" every time they toggled a switch.
 *
 * Logs revealed that this specific version of CodeAssist uses the method
 * bool(String, boolean) instead of the standard getBoolean. The
 * getBooleanSafe function accounts for all known variations (bool,
 * getBoolean, get) to ensure compatibility across host updates. The same
 * approach is used for [getStringSafe], because text inputs and dropdowns
 * are exposed through a similarly unstable set of method names (`string`,
 * `getString`, `get`, `int`, `getInt`) and may return either `String` or
 * `Int` depending on the host version that persisted the value.
 *
 * The dropdown case is the trickiest: depending on the host version, a
 * [SettingControl.Choice] may report its selection as an **index**, as the
 * **selected value** ("en"), or as the **selected label** ("English"). The
 * [resolveLanguageCode] helper normalises all three into the ISO-639 code
 * that [PluginSettings.setUiLanguage] expects, so a host upgrade cannot
 * silently corrupt the saved language.
 */
internal class OneCodeForAISettingsPage : SettingsPage {

    override val id: String = "com.devdayton.onecodeforai"
    override val title: String = "One Code For AI"
    override val iconId: String = "sparkle"

    private val tag = "SettingsPage"

    /**
     * Builds the rows displayed in the IDE settings screen.
     *
     * This method is called on every render. All values are read directly from
     * PluginSettings to ensure the UI matches the runtime state.
     *
     * Toggle order intentionally follows the "most disruptive first" rule:
     * Git (changes export behavior globally), then UI-side switches that only
     * affect presentation (animations, language), then the safety limit.
     *
     * The very first line runs [PluginSettings.migrateLegacyLanguagePreference]
     * so a user upgrading from the "Force English UI" era keeps their previous
     * choice. The migration is idempotent and effectively free after the
     * first render, so calling it here (rather than from a `onCreate` hook
     * the [SettingsPage] interface does not expose) is the cleanest option.
     */
    override fun controls(): List<SettingControl> {
        val context = HostEnvironment.applicationContext() ?: run {
            PluginLogger.w(tag, "controls() called but application context is null.")
            return emptyList()
        }

        // One-shot, idempotent: safe to call on every render. See KDoc on
        // PluginSettings.migrateLegacyLanguagePreference for the exact
        // conditions under which it does real work.
        PluginSettings.migrateLegacyLanguagePreference(context)

        fun text(resourceId: Int, fallback: String): String =
            context.pluginString(resourceId).ifEmpty { fallback }

        val gitEnabled = PluginSettings.isGitEnabled(context)
        val animationsEnabled = PluginSettings.areAnimationsEnabled(context)
        val notificationsEnabled = PluginSettings.areNotificationsEnabled(context)
        val currentLanguage = PluginSettings.getUiLanguage(context)
        val totalLimitKb = PluginSettings.getTotalExportLimitKb(context)

        PluginLogger.d(
            tag,
            "Rendering controls. Git=$gitEnabled, Anim=$animationsEnabled, " +
                "Notif=$notificationsEnabled, Language=$currentLanguage, " +
                "TotalLimitKb=$totalLimitKb"
        )

        // Pre-compute the Choice option list once. The `value` of each option
        // is the ISO-639 code (or LANGUAGE_AUTO) that will be persisted, and
        // the `label` is the native display name shown in the dropdown.
        // Using the code as `value` (and not the label) is what makes the
        // saved preference survive translation changes and list reorders.
        val languageChoices = LANGUAGE_OPTIONS.map { (code, label) ->
            SettingControl.Choice.Option(code, label)
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
            // --------------------------------------------------------------
            // Language selector (replaces the former "Force English UI"
            // toggle).
            //
            // Signature (verified against the compile errors from the host):
            //   Choice(key, title, description, value: String, options: List<Option>)
            // The 4th argument is the *currently selected value* (the ISO
            // code, not the index); the 5th is the full option list. Both
            // must be provided together — the widget does not accept an
            // "index into options" shortcut.
            // --------------------------------------------------------------
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
        )
    }

    /**
     * Handles preference changes from the host.
     *
     * Extracts the new value and persists it via PluginSettings. The active
     * branch is keyed on [SETTING_KEYS]; any other key is logged and ignored
     * so a host version that pushes unrelated keys cannot corrupt the plugin
     * state.
     *
     * Two branches have side effects beyond persisting the value:
     *  - [KEY_UI_LANGUAGE]: invalidates the locale caches so the next
     *    dialog/activity instance uses the new language immediately, and
     *    (best-effort) asks the plugin to refresh its context-menu label.
     *  - [KEY_TOTAL_EXPORT_LIMIT_KB]: clamps and parses the raw text input,
     *    falling back to `0` (no limit) on any non-numeric value.
     */
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
                // The host may report the new selection as an index, as the
                // selected value ("en"), or as the selected label ("English"),
                // depending on its CodeAssist version. Normalise all three
                // before persisting so an OEM fork cannot silently corrupt
                // the saved language. See resolveLanguageCode for the exact
                // resolution order.
                val raw = values.getStringSafe(key, PluginSettings.LANGUAGE_AUTO)
                val code = resolveLanguageCode(raw)
                PluginLogger.d(tag, "Extracted value for '$key': '$raw' -> '$code'")
                PluginSettings.setUiLanguage(context, code)

                // The override is honoured before both caches on every call,
                // so a stale cache can only matter in the narrow window where
                // the user switches from a forced language back to Auto after
                // changing the host language. Clearing both caches keeps that
                // path equivalent to a fresh process launch, and is required
                // for the "pick a different forced language" path too: without
                // it, the next dialog would keep the locale captured the last
                // time the caches were populated.
                LanguageHelper.invalidateCache()
                UiFactory.invalidateLocalizedCtxCache()

                // Best-effort: refresh the context-menu label so the entry
                // the user sees in the IDE matches the language they just
                // chose. The concrete mechanism (re-registering the action,
                // invalidating the host's action cache, or simply relying on
                // the next IDE restart) depends on the host API — see
                // OneCodeForAIPlugin.refreshActionLabel for the documented
                // fallback behaviour. The call is wrapped in runCatching
                // because a failure here must not prevent the language
                // preference from being saved.
                runCatching {
                    OneCodeForAIPlugin.refreshActionLabel(context)
                }.onFailure { error ->
                    PluginLogger.w(
                        tag,
                        "Could not refresh context-menu label after language change: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                }
            }
            KEY_TOTAL_EXPORT_LIMIT_KB -> {
                val raw = values.getStringSafe(key, "0")
                val parsed = raw.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                PluginLogger.d(tag, "Extracted value for '$key': '$raw' -> $parsed")
                PluginSettings.setTotalExportLimitKb(context, parsed)
            }
        }
    }

    /**
     * Normalises whatever the host reports for the language `Choice` control
     * into an ISO-639 code understood by [PluginSettings.setUiLanguage].
     *
     * ### Why this is necessary
     * Different CodeAssist versions expose a dropdown's selection through
     * different channels:
     *  - Some report the **index** into the options list (as an Int, or as
     *    its decimal string representation).
     *  - Some report the **selected value** (here: the ISO-639 code, e.g.
     *    `"en"`).
     *  - Some report the **selected label** (here: the human-readable name,
     *    e.g. `"English"`).
     *
     * All three are valid for the host, but only the middle one is what
     * [PluginSettings.setUiLanguage] expects. This helper recognises all
     * three and always returns a code that appears in [LANGUAGE_OPTIONS]
     * (or [PluginSettings.LANGUAGE_AUTO] as a final fallback), so the saved
     * preference is always well-formed regardless of the host.
     *
     * ### Resolution order
     *  1. **Already a code.** Return as-is if it matches one of
     *     [LANGUAGE_OPTIONS]'s codes. This is the hot path on hosts that
     *     report the value directly.
     *  2. **Index.** Parse as a decimal integer and index into
     *     [LANGUAGE_OPTIONS]. Out-of-range indices fall through to the
     *     next step, which handles a host that reports a stale index after
     *     the user uninstalls/upgrades.
     *  3. **Label.** Match against the display labels in [LANGUAGE_OPTIONS].
     *     This is the fallback for hosts that report the label; because the
     *     labels are native names (e.g. "Español"), they are stable across
     *     host localisations.
     *  4. **Auto.** Last resort: return [PluginSettings.LANGUAGE_AUTO]. This
     *     matches the pre-selector behaviour and is always a safe choice.
     *
     * @param raw The un-normalised string reported by the host.
     * @return A code from [LANGUAGE_OPTIONS] (`it.first`), or
     *   [PluginSettings.LANGUAGE_AUTO] when nothing matched.
     */
    private fun resolveLanguageCode(raw: String): String {
        // 1. Already a code (fast path).
        if (LANGUAGE_OPTIONS.any { it.first == raw }) return raw

        // 2. Decimal index into LANGUAGE_OPTIONS.
        raw.trim().toIntOrNull()?.let { index ->
            if (index in LANGUAGE_OPTIONS.indices) {
                return LANGUAGE_OPTIONS[index].first
            }
        }

        // 3. Human-readable label.
        LANGUAGE_OPTIONS.firstOrNull { it.second == raw }?.let { return it.first }

        // 4. Safe fallback.
        return PluginSettings.LANGUAGE_AUTO
    }

    /**
     * Safely extracts a boolean from PreferenceReader across host API versions.
     *
     * This method inspects the runtime class of the PreferenceReader provided
     * by CodeAssist. Different versions of the host may expose:
     * - bool(String, Boolean)      <-- Used by current CodeAssist versions
     * - getBoolean(String, Boolean)
     * - get(String, Boolean)
     *
     * If the method cannot be found or invocation fails, it logs the available
     * methods (in debug mode) and returns the defaultValue.
     */
    private fun PreferenceReader.getBooleanSafe(key: String, defaultValue: Boolean): Boolean {
        return try {
            val methods = javaClass.methods

            // Log the available methods once to help debug host API changes
            if (key == KEY_GIT_ENABLED) {
                val signatures = methods.joinToString {
                    it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")"
                }
                PluginLogger.d(tag, "PreferenceReader available methods: $signatures")
            }

            // 1. Look for `bool(String, Boolean)`, `getBoolean(String, Boolean)`, or `get(String, Boolean)`
            val methodWithDefault = methods.firstOrNull {
                (it.name == "bool" || it.name == "getBoolean" || it.name == "get") &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            if (methodWithDefault != null) {
                PluginLogger.d(tag, "Using method: ${methodWithDefault.name}(String, Boolean)")
                return methodWithDefault.invoke(this, key, defaultValue) as? Boolean ?: defaultValue
            }

            // 2. Look for `bool(String)`, `getBoolean(String)`, or `get(String)` (returns Boolean?)
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

    /**
     * Safely extracts a string from PreferenceReader across host API versions.
     *
     * Mirrors [getBooleanSafe]: different CodeAssist versions expose
     * `string(String, String)`, `getString(String, String)`, or
     * `get(String, String)`, and text fields may be persisted as either String
     * or Int internally. The method therefore tries the String overloads first
     * and then falls back to Int, converting the result to a decimal string.
     * This covers both the case where the host stored the raw text ("1024")
     * and the case where it stored the parsed integer (1024); in both cases
     * the caller receives a string that `toIntOrNull()` can parse.
     *
     * The same dual-path resolution is what makes the language `Choice`
     * work: a host that stores the selection as an **index** returns it via
     * the Int overload, and the resulting decimal string is then normalised
     * by [resolveLanguageCode] into the actual ISO-639 code.
     *
     * If no suitable getter is found or the invocation throws, the method logs
     * the failure and returns [defaultValue] so a broken host API can never
     * silently reset the user's configured value to an invalid state.
     */
    private fun PreferenceReader.getStringSafe(key: String, defaultValue: String): String {
        return try {
            val methods = javaClass.methods

            // 1. Prefer a String-returning overload with a default.
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

            // 2. Fall back to an Int-returning overload. Some hosts persist
            //    numeric text fields and dropdown indices as integers, and
            //    reflection would otherwise fail to match the String
            //    signature above.
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

        /**
         * Preference key for the UI language code. Mirrors
         * `PluginSettings`'s private `KEY_UI_LANGUAGE`; redeclared here (as
         * `private`) because `PluginSettings` deliberately keeps its keys
         * `private` so the settings page cannot accidentally bypass the
         * getter/setter pair by writing raw preference values.
         */
        const val KEY_UI_LANGUAGE = "ui_language"

        val SETTING_KEYS = setOf(
            KEY_GIT_ENABLED,
            KEY_ANIMATIONS_ENABLED,
            KEY_NOTIFICATIONS_ENABLED,
            KEY_UI_LANGUAGE,
            KEY_TOTAL_EXPORT_LIMIT_KB,
        )

        /**
         * Ordered list of every language the plugin currently bundles.
         *
         * Each entry is a `(ISO-639 code, native display name)` pair. The
         * display name is intentionally in the **native language** of that
         * locale — a user looking for Spanish expects to find "Español"
         * regardless of the language the rest of the page is currently
         * rendering in, the same way Android's own "Add a language" screen
         * works. "Auto" is the only entry that stays in English, because it
         * has no native speaker.
         *
         * ### Why the order is stable
         * The list order is the persisted *index* on hosts that store the
         * dropdown selection as an integer. Reordering this list changes
         * what "index 3" means, so new languages must be **appended** at the
         * end rather than inserted in the middle. The saved preference
         * itself is always the **code**, not the index, so a reorder only
         * affects the initial selection when the settings page is first
         * rendered against a host that reports indices — the next change
         * persists the correct code regardless.
         *
         * ### Coupling with `res/values-<code>/strings.xml`
         * Every code in this list **must** have a matching
         * `values-<code>/strings.xml` folder. If it does not, selecting that
         * language will silently fall back to the English default (Android's
         * normal resource resolution behaviour), which is confusing enough
         * that the trade-off is documented here rather than relying on a
         * runtime warning that most users would never see.
         *
         * ### `zh` vs `zh-rCH`
         * The bundle ships `values-zh-rCH/`, not `values-zh/`. Mapping the
         * code "zh" to a `Locale("zh", "CH")` in
         * [com.devdayton.onecodeforai.android.LanguageHelper.detectIdeLocale]
         * is what makes the two line up; if you later add
         * `values-zh-rTW/`, split this entry into "zh-CN" and "zh-TW" and
         * update the locale mapping accordingly.
         */
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
            "zh" to "中文"
        )
    }
}