package com.devdayton.onecodeforai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PresetManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("onecode_presets", Context.MODE_PRIVATE)

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
        "Solo Texto Plano" to FilterConfig(
            excludeBinaries = true,
            inclusions = listOf("txt", "md", "json", "xml", "yaml", "yml"),
            exclusions = emptyList()
        )
    )

    fun savePreset(name: String, config: FilterConfig) {
        val json = configToJson(config)
        prefs.edit().putString("preset_$name", json.toString()).commit()
    }

    fun getUserPresetNames(): List<String> {
        return prefs.all.keys.filter { it.startsWith("preset_") }.map { it.removePrefix("preset_") }.sorted()
    }

    fun loadPreset(name: String): FilterConfig? {
        getDefaultPresets()[name]?.let { return it }
        val str = prefs.getString("preset_$name", null) ?: return null
        return try {
            jsonToConfig(JSONObject(str))
        } catch (e: Exception) { null }
    }

    // --- Persistencia del último estado completo ---
    fun saveLastState(config: FilterConfig, presetName: String) {
        val json = configToJson(config)
        prefs.edit()
            .putString("last_state_config", json.toString())
            .putString("last_state_preset", presetName)
            .commit()
    }

    fun loadLastState(): Pair<FilterConfig, String>? {
        val configStr = prefs.getString("last_state_config", null) ?: return null
        val presetName = prefs.getString("last_state_preset", "Personalizado") ?: "Personalizado"
        return try {
            val config = jsonToConfig(JSONObject(configStr))
            Pair(config, presetName)
        } catch (e: Exception) { null }
    }

    private fun configToJson(config: FilterConfig): JSONObject {
        return JSONObject().apply {
            put("excludeBinaries", config.excludeBinaries)
            put("respectGitignore", config.respectGitignore)
            put("maxSizeKb", config.maxSizeKb)
            put("inclusions", JSONArray(config.inclusions))
            put("exclusions", JSONArray(config.exclusions))
            put("useGit", config.useGit)
            put("gitRef", config.gitRef ?: JSONObject.NULL)
        }
    }

    private fun jsonToConfig(json: JSONObject): FilterConfig {
        val inclusionsArray = json.optJSONArray("inclusions") ?: JSONArray()
        val exclusionsArray = json.optJSONArray("exclusions") ?: JSONArray()
        val inclusions = mutableListOf<String>()
        val exclusions = mutableListOf<String>()
        for (i in 0 until inclusionsArray.length()) inclusions.add(inclusionsArray.getString(i))
        for (i in 0 until exclusionsArray.length()) exclusions.add(exclusionsArray.getString(i))
        val gitRef = if (json.isNull("gitRef")) null else json.optString("gitRef").takeIf { it.isNotEmpty() }

        return FilterConfig(
            excludeBinaries = json.optBoolean("excludeBinaries", true),
            respectGitignore = json.optBoolean("respectGitignore", true),
            maxSizeKb = json.optInt("maxSizeKb", 512),
            inclusions = inclusions,
            exclusions = exclusions,
            useGit = json.optBoolean("useGit", false),
            gitRef = gitRef
        )
    }
}