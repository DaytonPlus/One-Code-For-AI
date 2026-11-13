package com.devdayton.onecodeforai.core

import com.devdayton.onecodeforai.android.PluginLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

object TreeConfigStore {

    private const val CONFIG_DIR = ".platform/plugins/com.devdayton.onecodeforai"

    private const val CONFIG_FILE = "config.json"

    private const val SCHEMA_VERSION = 1

    data class LoadedConfig(
        val presetName: String,
        val filterConfig: FilterConfig,
        val overrides: TreeOverrides
    )

    fun load(projectRoot: File): LoadedConfig? {
        val file = configFile(projectRoot)
        if (!file.isFile) return null

        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val version = root.optInt("version", -1)
            if (version != SCHEMA_VERSION) {
                PluginLogger.w(
                    "TreeConfigStore",
                    "Ignoring config.json with unsupported version $version " +
                        "at ${file.absolutePath} (expected $SCHEMA_VERSION)."
                )
                return null
            }

            val presetName = root.optString("presetName", "")
            val filterJson = root.optJSONObject("filterConfig")
            val filterConfig = if (filterJson != null) {

                FilterConfigCodec.fromJson(filterJson.toString())
            } else {
                FilterConfig()
            }

            val overridesJson = root.optJSONObject("overrides")
            val overrides = TreeOverrides(
                includedPaths = overridesJson.readStringList("includedPaths"),
                stubPaths = overridesJson.readStringList("stubPaths"),
                excludedPaths = overridesJson.readStringList("excludedPaths")
            )

            LoadedConfig(
                presetName = presetName,
                filterConfig = filterConfig,
                overrides = overrides
            )
        }.onFailure {
            PluginLogger.w(
                "TreeConfigStore",
                "Failed to load config.json at ${file.absolutePath}: ${it.message}",
                it
            )
        }.getOrNull()
    }

    fun saveDiff(
        projectRoot: File,
        currentStates: Map<String, NodeState>,
        filter: FileFilter,
        presetName: String
    ) {
        val included = mutableListOf<String>()
        val stub = mutableListOf<String>()
        val excluded = mutableListOf<String>()

        currentStates.forEach { (path, state) ->
            val natural = filter.resolveMode(
                relativePath = path,
                isDirectory = false,
                sizeBytes = 0L,
                respectExcludedPaths = false
            )

            if (state == natural) return@forEach
            when (state) {
                NodeState.INCLUDE -> included.add(path)
                NodeState.STUB -> stub.add(path)
                NodeState.EXCLUDE -> excluded.add(path)
                NodeState.LOCKED -> Unit
            }
        }

        persist(
            projectRoot = projectRoot,
            presetName = presetName,
            filterConfig = filter.config,
            overrides = TreeOverrides(
                includedPaths = included,
                stubPaths = stub,
                excludedPaths = excluded
            )
        )
    }

    fun clear(projectRoot: File) {
        runCatching { configFile(projectRoot).delete() }
    }

    private fun persist(
        projectRoot: File,
        presetName: String,
        filterConfig: FilterConfig,
        overrides: TreeOverrides
    ) {
        runCatching {
            val file = configFile(projectRoot)
            file.parentFile?.mkdirs()

            val payload = JSONObject().apply {
                put("version", SCHEMA_VERSION)
                put("presetName", presetName)
                put("filterConfig", JSONObject(FilterConfigCodec.toJson(filterConfig)))
                put("overrides", JSONObject().apply {
                    put("includedPaths", JSONArray(overrides.includedPaths))
                    put("stubPaths", JSONArray(overrides.stubPaths))
                    put("excludedPaths", JSONArray(overrides.excludedPaths))
                })
            }

            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(payload.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {

                runCatching { file.delete() }
                temp.renameTo(file)
            }
        }.onFailure {
            PluginLogger.w(
                "TreeConfigStore",
                "Failed to persist config.json at ${projectRoot.absolutePath}: ${it.message}",
                it
            )
        }
    }

    private fun configFile(projectRoot: File): File =
        File(projectRoot, "$CONFIG_DIR/$CONFIG_FILE")

    private fun JSONObject?.readStringList(key: String): List<String> {
        val array = this?.optJSONArray(key) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}