package com.devdayton.onecodeforai.core

import android.content.Context
import com.devdayton.onecodeforai.core.FilterConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence adapter for built-in/custom export profiles and the last dialog state.
 *
 * The JSON property names and preference keys intentionally remain stable so
 * installations upgraded from earlier versions keep their saved profiles.
 */
class PresetManager(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFERENCES_NAME = "onecode_presets"
        private const val PRESET_PREFIX = "preset_"
        private const val LAST_CONFIG_KEY = "last_state_config"
        private const val LAST_PRESET_KEY = "last_state_preset"

        private const val LEGACY_TEXT_PRESET = "Solo Texto Plano"
        private const val TEXT_PRESET = "Plain Text"
    }

    /** Names and contents of bundled presets are stable and language-independent. */
    fun getDefaultPresets(): Map<String, FilterConfig> = linkedMapOf(
        "Android (Kotlin)" to FilterConfig(
            inclusions = listOf("kt", "xml", "gradle", "toml", "properties", "pro"),
            exclusions = listOf("png", "jpg", "jar", "aar", "apk")
        ),
        "Web (JS/TS)" to FilterConfig(
            inclusions = listOf("js", "ts", "html", "css", "json", "md"),
            exclusions = listOf("png", "jpg", "svg", "ico", "woff", "woff2", "map")
        ),
        "Python" to FilterConfig(
            inclusions = listOf("py", "txt", "md", "json", "yaml", "yml", "toml"),
            exclusions = listOf("pyc", "pyd", "pyo")
        ),
        TEXT_PRESET to FilterConfig(
            excludeBinaries = true,
            inclusions = listOf("txt", "md", "json", "xml", "yaml", "yml"),
            exclusions = emptyList()
        )
    )

    fun savePreset(name: String, config: FilterConfig) {
        val normalizedName = normalizePresetName(name)
        require(normalizedName.isNotEmpty()) { "Preset name cannot be empty." }
        require(!isBuiltInPresetName(normalizedName)) { "A built-in preset already uses this name." }
        val caseConflict = getUserPresetNames().firstOrNull {
            it.equals(normalizedName, ignoreCase = true) && it != normalizedName
        }
        require(caseConflict == null) { "A user preset named '$caseConflict' already exists." }

        preferences.edit()
            .putString(PRESET_PREFIX + normalizedName, configToJson(config.normalized()).toString())
            .apply()
    }


    /** Prevents case-only duplicates of bundled profiles in the spinner. */
    fun isBuiltInPresetName(name: String): Boolean =
        getDefaultPresets().keys.any { it.equals(normalizePresetName(name), ignoreCase = true) }

    fun getUserPresetNames(): List<String> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith(PRESET_PREFIX) }
        .map { it.removePrefix(PRESET_PREFIX) }
        .filter(String::isNotBlank)
        .sortedBy { it.lowercase() }
        .toList()

    fun isUserPreset(name: String): Boolean = name in getUserPresetNames()

    fun deleteUserPreset(name: String) {
        if (!isUserPreset(name)) return
        preferences.edit().remove(PRESET_PREFIX + name).apply()
    }

    fun loadPreset(name: String): FilterConfig? {
        val resolvedName = normalizePresetName(name)
        getDefaultPresets()[resolvedName]?.let { return it }
        val serialized = preferences.getString(PRESET_PREFIX + resolvedName, null) ?: return null
        return runCatching { jsonToConfig(JSONObject(serialized)) }.getOrNull()
    }

    fun saveLastState(config: FilterConfig, presetName: String) {
        val resolvedName = normalizePresetName(presetName)
        preferences.edit()
            .putString(LAST_CONFIG_KEY, configToJson(config.normalized()).toString())
            .putString(LAST_PRESET_KEY, resolvedName)
            .apply()
    }

    fun loadLastState(): Pair<FilterConfig, String>? {
        val serializedConfig = preferences.getString(LAST_CONFIG_KEY, null) ?: return null
        val savedPreset = normalizePresetName(
            preferences.getString(LAST_PRESET_KEY, "Personalizado") ?: "Personalizado"
        )
        return runCatching {
            jsonToConfig(JSONObject(serializedConfig)) to savedPreset
        }.getOrNull()
    }

    private fun configToJson(config: FilterConfig): JSONObject = JSONObject().apply {
        put("excludeBinaries", config.excludeBinaries)
        put("respectGitignore", config.respectGitignore)
        put("maxSizeKb", config.maxSizeKb)
        put("inclusions", JSONArray(config.inclusions))
        put("exclusions", JSONArray(config.exclusions))
        put("useGit", config.useGit)
        put("gitRef", config.gitRef ?: JSONObject.NULL)
        put("excludedPaths", JSONArray(config.excludedPaths))
    }

    private fun jsonToConfig(json: JSONObject): FilterConfig = FilterConfig(
        excludeBinaries = json.optBoolean("excludeBinaries", true),
        respectGitignore = json.optBoolean("respectGitignore", true),
        maxSizeKb = json.optInt("maxSizeKb", FilterConfig.DEFAULT_MAX_SIZE_KB),
        inclusions = json.readStringList("inclusions"),
        exclusions = json.readStringList("exclusions"),
        useGit = json.optBoolean("useGit", false),
        gitRef = json.optString("gitRef").takeIf { it.isNotBlank() && it != "null" },
        excludedPaths = json.readStringList("excludedPaths")
    ).normalized()

    private fun JSONObject.readStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    private fun normalizePresetName(name: String): String =
        name.trim().let { if (it == LEGACY_TEXT_PRESET) TEXT_PRESET else it }
}
