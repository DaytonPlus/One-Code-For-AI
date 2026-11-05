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

    /**
     * Bundled profiles. Names are stable and language-independent, so a saved
     * "last preset" keeps pointing at the same profile after switching the
     * IDE language.
     *
     * Design notes
     * ------------
     * CodeAssist is an Android IDE, so the majority of users work on Android
     * projects. The Android profiles are therefore the primary, opinionated
     * starting points; the generic ones (Web, Python, Plain Text) stay as
     * secondary options for mixed projects (docs, backend code, scripts).
     *
     * Binary exclusions
     * -----------------
     * `excludeBinaries = true` (the default) already filters ~40 binary
     * extensions via `FileFilter.BINARY_EXTENSIONS` — images, media, archives,
     * native libraries, fonts, databases. The `exclusions` list here only adds
     * what the fallback can't cover:
     *
     *  - generated Android code that still has a source extension (`R.java`,
     *    `BuildConfig.java`, `*.generated.kt`, `*Generated.kt`),
     *  - machine-specific files (`local.properties` — SDK path, user home),
     *  - large lock files that happen to use a text extension
     *    (`package-lock.json`, `pnpm-lock.yaml`).
     *
     * Note on inclusions being a soft whitelist
     * -----------------------------------------
     * `FileFilter.includePath` falls through to `extension in TEXT_EXTENSIONS`
     * when no inclusion matches. That means an extension present in
     * `TEXT_EXTENSIONS` (e.g. `json`, `xml`, `txt`) is included even if the
     * preset didn't list it — which is why `package-lock.json` and
     * `coverage.xml` need explicit exclusions instead of relying on the
     * inclusion whitelist.
     */
    fun getDefaultPresets(): Map<String, FilterConfig> = linkedMapOf(
        // ------------------------------------------------------------------
        // Android — primary, opinionated profiles.
        // ------------------------------------------------------------------

        // Full Android app in Kotlin: source, build, resources, docs, data.
        // Suitable for "review my whole project", "explain the architecture",
        // or "find bugs across modules". Tests are included because they
        // document intent and behaviour for the model.
        "Android (Kotlin)" to FilterConfig(
            inclusions = listOf(
                // Source
                "kt", "kts", "java",
                // Build system: Groovy + Kotlin DSL + version catalog + proguard
                "gradle", "toml", "properties", "pro", "cfg", "ini",
                // Manifest, layouts, values, vector drawables, config
                "xml", "json", "yaml", "yml",
                // Docs and structured data (Room schemas, CSVs)
                "md", "txt", "csv", "sql"
            ),
            exclusions = listOf(
                // Generated Android code — compiled from XML/resources at
                // build time. Useless and noisy for an AI reviewer.
                "R.java", "BuildConfig.java",
                "*.generated.kt", "*.generated.java",
                "*Generated.kt", "*Generated.java",
                // Machine-specific: absolute SDK path + user home. Never
                // useful, and contains developer-local paths.
                "local.properties"
                // Note: *.keystore, *.jks, *.p12, *.pem, *.key need no
                // explicit exclusion — those extensions aren't in
                // TEXT_EXTENSIONS, so the fallback already filters them out.
            )
        ),

        // Same as above but with tests and docs stripped. Use when you want
        // the model to reason about implementation only: lower token count,
        // higher signal-to-noise for "explain what this method does",
        // "suggest a refactor", "find a bug in this class".
        "Android (source only)" to FilterConfig(
            inclusions = listOf(
                "kt", "kts", "java",
                "gradle", "toml", "properties", "pro", "cfg", "ini",
                "xml"
            ),
            exclusions = listOf(
                "R.java", "BuildConfig.java",
                "*.generated.kt", "*.generated.java",
                "*Generated.kt", "*Generated.java",
                "local.properties",
                // Test files. Naming conventions covered:
                //  - JUnit 4/5:     LoginTest.kt, LoginTests.kt
                //  - kotest:        LoginSpec.kt
                //  - kebab/dotted:  login.test.kt
                "*Test.kt", "*Tests.kt", "*Spec.kt",
                "*Test.java", "*Tests.java", "*Spec.java",
                "*.test.kt", "*.test.java"
            )
        ),

        // Resources only: layouts, values, drawables, manifest, themes.
        // Use for UI-focused prompts ("redesign this screen", "explain the
        // theme hierarchy", "translate these strings"). Strips every source
        // file so the model stays focused on the visual layer.
        "Android (Resources)" to FilterConfig(
            inclusions = listOf("xml"),
            exclusions = emptyList()
        ),

        // ------------------------------------------------------------------
        // Generic — secondary profiles for non-Android work.
        // ------------------------------------------------------------------

        // Modern JS/TS web project. Covers React/Vue/Svelte/Next/Astro
        // conventions plus build config and docs. Lock files are excluded
        // explicitly because they use `.json`/`.yaml` extensions and would
        // otherwise be pulled in by the TEXT_EXTENSIONS fallback — they're
        // multi-megabyte, machine-generated, and useless to an AI.
        "Web (JS/TS)" to FilterConfig(
            inclusions = listOf(
                "js", "jsx", "ts", "tsx", "mjs", "cjs",
                "html", "htm", "css", "scss", "sass", "less",
                "json", "yaml", "yml", "toml",
                "md", "mdx", "txt",
                // Framework-specific single-file components
                "vue", "svelte", "astro"
            ),
            exclusions = listOf(
                // Bundled / minified / chunked build output. Even though
                // `build`, `dist` and `.next` are hardcoded excluded
                // directories, some projects emit bundles to `public/` or
                // `static/` — exclude them by name too.
                "*.bundle.js", "*.min.js", "*.min.css", "*.chunk.js",
                // Lock files with text extensions
                "package-lock.json", "pnpm-lock.yaml"
            )
        ),

        // Python project: source, type stubs, Cython, packaging, docs.
        // `__pycache__`, `venv` and `.venv` are already excluded as
        // directories by FileFilter, so no exclusions needed beyond
        // coverage reports.
        "Python" to FilterConfig(
            inclusions = listOf(
                "py", "pyi", "pyx", "pxd",
                "toml", "cfg", "ini",
                "txt", "md", "rst", "json", "yaml", "yml", "sql"
            ),
            exclusions = listOf(
                // Generated coverage report — has `.xml` extension so it
                // would be pulled in by the TEXT_EXTENSIONS fallback.
                "coverage.xml"
            )
        ),

        // Human-readable text only. Useful for feeding specifications,
        // READMEs, changelogs or notes to the model without any source code.
        TEXT_PRESET to FilterConfig(
            excludeBinaries = true,
            inclusions = listOf(
                "txt", "md", "rst", "adoc", "org", "tex", "log", "csv",
                "json", "xml", "yaml", "yml"
            ),
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
        put("useMaxSize", config.useMaxSize)
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
        useMaxSize = json.optBoolean("useMaxSize", true),
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