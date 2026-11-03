package com.devdayton.onecodeforai.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stateless JSON codec for [FilterConfig].
 *
 * Mirrors PresetManager's schema exactly (including the [useMaxSize] default
 * of `true`) so configs saved by either path remain interchangeable.
 */
object FilterConfigCodec {

    fun toJson(config: FilterConfig): String = JSONObject().apply {
        put("excludeBinaries", config.excludeBinaries)
        put("respectGitignore", config.respectGitignore)
        put("useMaxSize", config.useMaxSize)
        put("maxSizeKb", config.maxSizeKb)
        put("inclusions", JSONArray(config.inclusions))
        put("exclusions", JSONArray(config.exclusions))
        put("useGit", config.useGit)
        put("gitRef", config.gitRef ?: JSONObject.NULL)
        put("excludedPaths", JSONArray(config.excludedPaths))
    }.toString()

    fun fromJson(json: String): FilterConfig = runCatching {
        val obj = JSONObject(json)
        FilterConfig(
            excludeBinaries = obj.optBoolean("excludeBinaries", true),
            respectGitignore = obj.optBoolean("respectGitignore", true),
            useMaxSize = obj.optBoolean("useMaxSize", true),
            maxSizeKb = obj.optInt("maxSizeKb", FilterConfig.DEFAULT_MAX_SIZE_KB),
            inclusions = obj.readStringList("inclusions"),
            exclusions = obj.readStringList("exclusions"),
            useGit = obj.optBoolean("useGit", false),
            gitRef = obj.optString("gitRef").takeIf { it.isNotBlank() && it != "null" },
            excludedPaths = obj.readStringList("excludedPaths")
        ).normalized()
    }.getOrDefault(FilterConfig())

    private fun JSONObject.readStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}