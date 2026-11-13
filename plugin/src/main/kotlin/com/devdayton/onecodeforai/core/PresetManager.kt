package com.devdayton.onecodeforai.core

import android.content.Context
import com.devdayton.onecodeforai.core.FilterConfig
import org.json.JSONArray
import org.json.JSONObject

class PresetManager(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFERENCES_NAME = "onecode_presets"
        private const val PRESET_PREFIX = "preset_"

        private const val LEGACY_TEXT_PRESET = "Solo Texto Plano"
        private const val TEXT_PRESET = "Plain Text"
    }

    fun getDefaultPresets(): Map<String, FilterConfig> = linkedMapOf(

        "Android (Kotlin)" to FilterConfig(
            inclusions = listOf(

                "kt", "kts", "java",

                "gradle", "toml", "properties", "pro", "cfg", "ini",

                "xml", "json", "yaml", "yml",

                "md", "txt", "csv", "sql"
            ),
            exclusions = emptyList(),
            stubPatterns = listOf(

                "R.java", "BuildConfig.java",
                "*.generated.kt", "*.generated.java",
                "*Generated.kt", "*Generated.java",

                "local.properties"

            ),
            enableExclusions = false
        ),

        "Android (source only)" to FilterConfig(
            inclusions = listOf(
                "kt", "kts", "java",
                "gradle", "toml", "properties", "pro", "cfg", "ini",
                "xml"
            ),
            exclusions = listOf(

                "*Test.kt", "*Tests.kt", "*Spec.kt",
                "*Test.java", "*Tests.java", "*Spec.java",
                "*.test.kt", "*.test.java"
            ),
            stubPatterns = listOf(
                "R.java", "BuildConfig.java",
                "*.generated.kt", "*.generated.java",
                "*Generated.kt", "*Generated.java",
                "local.properties"
            )
        ),

        "Android (Resources)" to FilterConfig(
            inclusions = listOf("xml"),
            exclusions = emptyList(),
            enableExclusions = false,
            enableStubPatterns = false
        ),

        "Web (JS/TS)" to FilterConfig(
            inclusions = listOf(
                "js", "jsx", "ts", "tsx", "mjs", "cjs",
                "html", "htm", "css", "scss", "sass", "less",
                "json", "yaml", "yml", "toml",
                "md", "mdx", "txt",

                "vue", "svelte", "astro"
            ),
            exclusions = listOf(

                "*.bundle.js", "*.min.js", "*.min.css", "*.chunk.js"
            ),
            stubPatterns = listOf(

                "package-lock.json", "pnpm-lock.yaml", "yarn.lock"
            )
        ),

        "Python" to FilterConfig(
            inclusions = listOf(
                "py", "pyi", "pyx", "pxd",
                "toml", "cfg", "ini",
                "txt", "md", "rst", "json", "yaml", "yml", "sql"
            ),
            exclusions = listOf(

                "coverage.xml"
            ),
            stubPatterns = listOf(

                "poetry.lock", "Pipfile.lock"
            )
        ),

        TEXT_PRESET to FilterConfig(
            excludeBinaries = true,
            inclusions = listOf(
                "txt", "md", "rst", "adoc", "org", "tex", "log", "csv",
                "json", "xml", "yaml", "yml"
            ),
            exclusions = emptyList(),
            enableExclusions = false,
            enableStubPatterns = false
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

    private fun configToJson(config: FilterConfig): JSONObject = JSONObject().apply {
        put("excludeBinaries", config.excludeBinaries)
        put("respectGitignore", config.respectGitignore)
        put("useMaxSize", config.useMaxSize)
        put("maxSizeKb", config.maxSizeKb)
        put("inclusions", JSONArray(config.inclusions))
        put("exclusions", JSONArray(config.exclusions))
        put("stubPatterns", JSONArray(config.stubPatterns))
        put("enableInclusions", config.enableInclusions)
        put("enableExclusions", config.enableExclusions)
        put("enableStubPatterns", config.enableStubPatterns)
        put("useGit", config.useGit)
        put("gitRef", config.gitRef ?: JSONObject.NULL)
    }

    private fun jsonToConfig(json: JSONObject): FilterConfig = FilterConfig(
        excludeBinaries = json.optBoolean("excludeBinaries", true),
        respectGitignore = json.optBoolean("respectGitignore", true),
        useMaxSize = json.optBoolean("useMaxSize", true),
        maxSizeKb = json.optInt("maxSizeKb", FilterConfig.DEFAULT_MAX_SIZE_KB),
        inclusions = json.readStringList("inclusions"),
        exclusions = json.readStringList("exclusions"),
        stubPatterns = json.readStringList("stubPatterns"),
        enableInclusions = json.optBoolean("enableInclusions", true),
        enableExclusions = json.optBoolean("enableExclusions", true),
        enableStubPatterns = json.optBoolean("enableStubPatterns", true),
        useGit = json.optBoolean("useGit", false),
        gitRef = json.optString("gitRef").takeIf { it.isNotBlank() && it != "null" }
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