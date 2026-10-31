package com.devdayton.onecodeforai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PresetManager(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("onecode_presets", Context.MODE_PRIVATE)

    companion object {
        // Nombres de presets por defecto en inglés (estables, no se traducen).
        // "Plain Text" sustituye al antiguo "Solo Texto Plano".
        private const val LEGACY_TEXT_PRESET = "Solo Texto Plano"
        private const val TEXT_PRESET = "Plain Text"
    }

    fun getDefaultPresets(): Map<String, FilterConfig> = mapOf(
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
        prefs.edit().putString("preset_$name", configToJson(config).toString()).commit()
    }

    fun getUserPresetNames(): List<String> =
        prefs.all.keys.filter { it.startsWith("preset_") }
            .map { it.removePrefix("preset_") }
            .sorted()

    fun isUserPreset(name: String): Boolean = name in getUserPresetNames()

    fun deleteUserPreset(name: String) {
        prefs.edit().remove("preset_$name").commit()
    }

    fun loadPreset(name: String): FilterConfig? {
        // Compatibilidad: si el llamador pasó "Solo Texto Plano" (nombre antiguo),
        // lo traducimos al nombre actual.
        val resolved = if (name == LEGACY_TEXT_PRESET) TEXT_PRESET else name
        getDefaultPresets()[resolved]?.let { return it }
        val str = prefs.getString("preset_$resolved", null) ?: return null
        return try { jsonToConfig(JSONObject(str)) } catch (e: Exception) { null }
    }

    fun saveLastState(config: FilterConfig, presetName: String) {
        // Guardamos siempre el nombre actual (migrando el legacy si viniera).
        val resolved = if (presetName == LEGACY_TEXT_PRESET) TEXT_PRESET else presetName
        prefs.edit()
            .putString("last_state_config", configToJson(config).toString())
            .putString("last_state_preset", resolved)
            .commit()
    }

    fun loadLastState(): Pair<FilterConfig, String>? {
        val configStr = prefs.getString("last_state_config", null) ?: return null
        val rawPreset = prefs.getString("last_state_preset", "Personalizado") ?: "Personalizado"
        // Migración: si el estado guardado tiene el nombre antiguo, devolvemos el nuevo.
        val presetName = if (rawPreset == LEGACY_TEXT_PRESET) TEXT_PRESET else rawPreset
        return try {
            Pair(jsonToConfig(JSONObject(configStr)), presetName)
        } catch (e: Exception) { null }
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

    private fun jsonToConfig(json: JSONObject): FilterConfig {
        val inclusions = mutableListOf<String>()
        val exclusions = mutableListOf<String>()
        val excludedPaths = mutableListOf<String>()
        json.optJSONArray("inclusions")?.let { arr ->
            for (i in 0 until arr.length()) inclusions.add(arr.getString(i))
        }
        json.optJSONArray("exclusions")?.let { arr ->
            for (i in 0 until arr.length()) exclusions.add(arr.getString(i))
        }
        json.optJSONArray("excludedPaths")?.let { arr ->
            for (i in 0 until arr.length()) excludedPaths.add(arr.getString(i))
        }
        val gitRef = if (json.isNull("gitRef")) null
                     else json.optString("gitRef").takeIf { it.isNotEmpty() }

        return FilterConfig(
            excludeBinaries = json.optBoolean("excludeBinaries", true),
            respectGitignore = json.optBoolean("respectGitignore", true),
            maxSizeKb = json.optInt("maxSizeKb", 512),
            inclusions = inclusions,
            exclusions = exclusions,
            useGit = json.optBoolean("useGit", false),
            gitRef = gitRef,
            excludedPaths = excludedPaths
        )
    }
}