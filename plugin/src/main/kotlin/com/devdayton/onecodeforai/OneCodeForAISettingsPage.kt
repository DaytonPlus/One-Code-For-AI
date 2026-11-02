package com.devdayton.onecodeforai

import android.content.Context
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.HostEnvironment
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage

/** Settings exposed by CodeAssist and persisted in the host application's preferences. */
internal class OneCodeForAISettingsPage : SettingsPage {

    override val id: String = "com.devdayton.onecodeforai"
    override val title: String = "One Code For AI"
    override val iconId: String = "sparkle"

    override fun controls(): List<SettingControl> {
        val context = HostEnvironment.applicationContext()
        fun text(resourceId: Int, fallback: String): String =
            context?.pluginString(resourceId) ?: fallback

        return listOf(
            SettingControl.Toggle(
                KEY_RESPECT_GITIGNORE,
                text(R.string.settings_gitignore, "Respect .gitignore"),
                text(R.string.settings_gitignore_desc, "Ignores files listed in .gitignore"),
                true,
            ),
            SettingControl.Toggle(
                KEY_EXCLUDE_BINARIES,
                text(R.string.settings_binaries, "Exclude binaries"),
                text(R.string.settings_binaries_desc, "Filters out images, videos, etc."),
                true,
            ),
            SettingControl.Toggle(
                KEY_GIT_ENABLED,
                text(R.string.settings_git, "Git integration"),
                text(R.string.settings_git_desc, "Lets you pick a branch or commit"),
                true,
            ),
            SettingControl.Toggle(
                KEY_TREE_ENABLED,
                text(R.string.settings_tree, "File tree & stats"),
                text(
                    R.string.settings_tree_desc,
                    "Shows the file tree and token count. Disable it on large projects if you don't need it.",
                ),
                true,
            ),
        )
    }

    override fun onChanged(key: String, values: PreferenceReader) {
        val context = HostEnvironment.applicationContext() ?: return
        if (key !in SETTING_KEYS) return

        val value = values.readBoolean(key, defaultValue = true) ?: true
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()
    }

    /**
     * CodeAssist's PreferenceReader has changed API shape between host versions. Resolve the
     * boolean getter reflectively while keeping the rest of the plugin independent of that API.
     */
    private fun PreferenceReader.readBoolean(key: String, defaultValue: Boolean): Boolean? =
        runCatching {
            val methods = javaClass.methods
            val twoArgumentGetter = methods.firstOrNull { method ->
                method.name == "get" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            if (twoArgumentGetter != null) {
                return@runCatching twoArgumentGetter.invoke(this, key, defaultValue) as? Boolean
            }

            val booleanGetter = methods.firstOrNull { method ->
                method.name == "getBoolean" && method.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            if (booleanGetter != null) {
                return@runCatching booleanGetter.invoke(this, key) as? Boolean
            }

            val genericGetter = methods.firstOrNull { method ->
                method.name == "get" && method.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            genericGetter?.invoke(this, key) as? Boolean
        }.getOrNull()

    private companion object {
        const val SETTINGS_FILE = "onecode_settings"
        const val KEY_RESPECT_GITIGNORE = "exclude_gitignored"
        const val KEY_EXCLUDE_BINARIES = "exclude_binaries"
        const val KEY_GIT_ENABLED = "git_enabled"
        const val KEY_TREE_ENABLED = "tree_enabled"
        val SETTING_KEYS = setOf(
            KEY_RESPECT_GITIGNORE,
            KEY_EXCLUDE_BINARIES,
            KEY_GIT_ENABLED,
            KEY_TREE_ENABLED,
        )
    }
}
