package com.devdayton.onecodeforai

import android.content.Context
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.HostEnvironment
import com.devdayton.onecodeforai.android.PluginLogger
import com.devdayton.onecodeforai.android.PluginSettings
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage

/**
 * Global preferences for One Code For AI, rendered natively by CodeAssist.
 *
 * ### Scope
 * This page manages application-wide boolean toggles:
 * 1. Git master kill switch.
 * 2. Animations.
 * 3. Notifications.
 *
 * Per-export options (like respecting .gitignore, excluding binaries, or
 * showing the file tree) are intentionally omitted here. They live inside the
 * ConfigurationDialog because they are part of the user's export profile,
 * not a global app setting.
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
 * getBoolean, get) to ensure compatibility across host updates.
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
     */
    override fun controls(): List<SettingControl> {
        val context = HostEnvironment.applicationContext() ?: run {
            PluginLogger.w(tag, "controls() called but application context is null.")
            return emptyList()
        }

        fun text(resourceId: Int, fallback: String): String =
            context.pluginString(resourceId).ifEmpty { fallback }

        val gitEnabled = PluginSettings.isGitEnabled(context)
        val animationsEnabled = PluginSettings.areAnimationsEnabled(context)
        val notificationsEnabled = PluginSettings.areNotificationsEnabled(context)

        PluginLogger.d(
            tag,
            "Rendering controls. Git=$gitEnabled, Anim=$animationsEnabled, Notif=$notificationsEnabled"
        )

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
        )
    }

    /**
     * Handles preference changes from the host.
     *
     * Extracts the new boolean value and persists it via PluginSettings.
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
        }
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

    private companion object {
        const val KEY_GIT_ENABLED = "git_enabled"
        const val KEY_ANIMATIONS_ENABLED = "animations_enabled"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"

        val SETTING_KEYS = setOf(
            KEY_GIT_ENABLED,
            KEY_ANIMATIONS_ENABLED,
            KEY_NOTIFICATIONS_ENABLED,
        )
    }
}