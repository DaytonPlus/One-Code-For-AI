package com.devdayton.onecodeforai.core

import org.json.JSONArray
import org.json.JSONObject

object FilterConfigCodec {

    fun toJson(config: FilterConfig): String = JSONObject().apply {
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
            stubPatterns = obj.readStringList("stubPatterns"),
            enableInclusions = obj.optBoolean("enableInclusions", true),
            enableExclusions = obj.optBoolean("enableExclusions", true),
            enableStubPatterns = obj.optBoolean("enableStubPatterns", true),
            useGit = obj.optBoolean("useGit", false),
            gitRef = obj.optString("gitRef").takeIf { it.isNotBlank() && it != "null" }
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