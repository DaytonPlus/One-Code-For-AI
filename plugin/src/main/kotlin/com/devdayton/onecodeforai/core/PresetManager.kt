package com.devdayton.onecodeforai.core

import android.content.Context
import com.devdayton.onecodeforai.core.FilterConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence adapter for built-in/custom export profiles.
 *
 * The JSON property names and preference keys intentionally remain stable
 * so installations upgraded from earlier versions keep their saved profiles.
 *
 * ### Tree v2: what this class no longer does
 *
 * Prior to Tree v2 this class was also responsible for two pieces of state
 * that have now been moved elsewhere:
 *
 *  - **Last dialog state** (the config and the spinner selection the user
 *    had when they closed the dialog). That state was written under
 *    LAST_CONFIG_KEY / LAST_PRESET_KEY and rehydrated on the next open.
 *    It has been removed in favor of [TreeConfigStore], which persists
 *    the same information — plus the tree selector's per-path overrides —
 *    under `.platform/plugins/com.devdayton.onecodeforai/config.json` in
 *    the project itself, and only on Generate. The memory-only session
 *    behavior (survives dialog dismissal but not process death) is now
 *    provided by [TreeSessionState], which is the in-memory counterpart
 *    of [TreeConfigStore].
 *  - **Manual per-file exclusions** (excludedPaths). These were stored in
 *    the profile itself, which made two projects opened with the same
 *    profile share manual choices. Tree v2 moved them to
 *    [TreeOverrides.excludedPaths], and the profile now stores only the
 *    declarative rules ([FilterConfig]).
 *
 * Both changes are transparent to users on read: profiles saved by
 * earlier versions still load (the removed key is simply ignored), and
 * the "last state" that used to be restored from preferences is
 * replaced by the more accurate "last state restored from config.json".
 *
 * ### FilterConfig changes adopted here
 *
 *  - **Removed**: excludedPaths is no longer serialized or deserialized.
 *    Profiles saved by pre-Tree-v2 builds may still contain the key; it
 *    is ignored on read and dropped on the next write.
 *  - **Added**: stubPatterns, the rule-based half of the reference-only
 *    feature. Mirrors [FilterConfigCodec] exactly so profiles written by
 *    either path remain interchangeable.
 *
 * ### Built-in profiles
 *
 * Built-in profiles are hardcoded, language-independent, and immutable.
 * Their names are stable identifiers, not localized labels: a saved
 * "last preset" keeps pointing at the same profile after the user
 * switches the IDE language, because the spinner stores the name, not the
 * translated label.
 *
 * Design notes
 * ------------
 *
 * CodeAssist is an Android IDE, so the majority of users work on Android
 * projects. The Android profiles are therefore the primary, opinionated
 * starting points; the generic ones (Web, Python, Plain Text) stay as
 * secondary options for mixed projects (docs, backend code, scripts).
 *
 * Binary exclusions
 * -----------------
 *
 * excludeBinaries = true (the default) already filters ~40 binary
 * extensions via FileFilter.BINARY_EXTENSIONS — images, media, archives,
 * native libraries, fonts, databases. The exclusions list here only adds
 * what the fallback can't cover:
 *
 *  - generated Android code that still has a source extension (R.java,
 *    BuildConfig.java, *.generated.kt, *Generated.kt),
 *  - machine-specific files (local.properties — SDK path, user home),
 *  - large lock files that happen to use a text extension
 *    (package-lock.json, pnpm-lock.yaml).
 *
 * Note on inclusions being a soft whitelist
 * -----------------------------------------
 *
 * FileFilter.includePath falls through to "extension in TEXT_EXTENSIONS"
 * when no inclusion matches. That means an extension present in
 * TEXT_EXTENSIONS (e.g. json, xml, txt) is included even if the preset
 * didn't list it — which is why package-lock.json and coverage.xml need
 * explicit exclusions instead of relying on the inclusion whitelist.
 *
 * ### Reference-only (stubs)
 *
 * Several entries that used to live in exclusions have moved to
 * stubPatterns:
 *
 *  - **Android (Kotlin) / (source only)**: R.java, BuildConfig.java,
 *    *.generated.*, local.properties. These files are the reference case
 *    for stubs: the model benefits from knowing they exist (schema,
 *    package, application id) without receiving their generated bodies.
 *  - **Web (JS/TS)**: package-lock.json, pnpm-lock.yaml. Machine-
 *    generated and multi-megabyte; their presence and version pins are
 *    useful, their content is not.
 *  - **Python**: poetry.lock, Pipfile.lock. Same reasoning; coverage.xml
 *    stays in exclusions because a coverage report is noise with no
 *    structural information the model can reuse.
 *
 * The effect is a smaller, more focused dump for the same filters, at no
 * cost in structural information.
 *
 * @param context Any context. Only the application context is retained,
 *   so a short-lived Activity context does not leak into a long-lived
 *   singleton.
 */
class PresetManager(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFERENCES_NAME = "onecode_presets"
        private const val PRESET_PREFIX = "preset_"

        private const val LEGACY_TEXT_PRESET = "Solo Texto Plano"
        private const val TEXT_PRESET = "Plain Text"
    }

    /**
     * Bundled profiles. Names are stable and language-independent, so a
     * saved "last preset" keeps pointing at the same profile after
     * switching the IDE language.
     *
     * The entries here are intentionally ordered: the Android profiles
     * are primary, then the generic ones. PresetManager.getUserPresetNames
     * returns user presets in a separate list so the spinner can render
     * "Custom", then built-ins, then user-saved ones in one consistent
     * order.
     *
     * Each profile is defined by its [FilterConfig]; the per-path
     * overrides that the user may have applied through the tree selector
     * are deliberately not part of the profile. See the class-level
     * KDoc for the rationale.
     *
     * Built-in profile changes across releases are silent upgrades: a
     * user who picks "Android (Kotlin)" after updating the plugin gets
     * the new definition the next time they select it, because profiles
     * are resolved by name and this map is the source of truth. The only
     * way to preserve a previous version of a built-in profile is to save
     * it as a custom preset before upgrading.
     */
    fun getDefaultPresets(): Map<String, FilterConfig> = linkedMapOf(
        // ------------------------------------------------------------------
        // Android — primary, opinionated profiles.
        // ------------------------------------------------------------------

        // Full Android app in Kotlin: source, build, resources, docs, data.
        // Suitable for "review my whole project", "explain the architecture",
        // or "find bugs across modules". Tests are included because they
        // document intent and behaviour for the model.
        //
        // Generated Android code and local.properties are stubbed instead of
        // excluded. The model benefits from knowing they exist — schema,
        // package, application id — without their generated bodies.
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
            exclusions = emptyList(),
            stubPatterns = listOf(
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
                // They also do not need stubbing: a stub of a keystore
                // would only reveal its size, which is never useful.
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
                // Test files. Naming conventions covered:
                //  - JUnit 4/5:     LoginTest.kt, LoginTests.kt
                //  - kotest:        LoginSpec.kt
                //  - kebab/dotted:  login.test.kt
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

        // Resources only: layouts, values, drawables, manifest, themes.
        // Use for UI-focused prompts ("redesign this screen", "explain the
        // theme hierarchy", "translate these strings"). Strips every source
        // file so the model stays focused on the visual layer. No stubs:
        // every generated resource is excluded by the inclusions list
        // (only "xml" is included), so there is nothing worth listing
        // without its content.
        "Android (Resources)" to FilterConfig(
            inclusions = listOf("xml"),
            exclusions = emptyList()
        ),

        // ------------------------------------------------------------------
        // Generic — secondary profiles for non-Android work.
        // ------------------------------------------------------------------

        // Modern JS/TS web project. Covers React/Vue/Svelte/Next/Astro
        // conventions plus build config and docs. Lock files are stubbed
        // instead of excluded so the model knows which package manager
        // and version pins the project uses, without receiving the
        // multi-megabyte content.
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
                // build, dist and .next are hardcoded excluded directories,
                // some projects emit bundles to public/ or static/ —
                // exclude them by name too. These are pure noise: the
                // model cannot meaningfully reason about minified bundles.
                "*.bundle.js", "*.min.js", "*.min.css", "*.chunk.js"
            ),
            stubPatterns = listOf(
                // Lock files with text extensions. Their content is
                // machine-generated and enormous; their presence and
                // pinned versions are what the model can actually use.
                "package-lock.json", "pnpm-lock.yaml", "yarn.lock"
            )
        ),

        // Python project: source, type stubs, Cython, packaging, docs.
        // __pycache__, venv and .venv are already excluded as directories
        // by FileFilter, so no exclusions needed beyond coverage reports.
        "Python" to FilterConfig(
            inclusions = listOf(
                "py", "pyi", "pyx", "pxd",
                "toml", "cfg", "ini",
                "txt", "md", "rst", "json", "yaml", "yml", "sql"
            ),
            exclusions = listOf(
                // Generated coverage report — has .xml extension so it
                // would be pulled in by the TEXT_EXTENSIONS fallback.
                // Unlike lockfiles, it carries no structural information
                // the model can reuse, so it is excluded outright.
                "coverage.xml"
            ),
            stubPatterns = listOf(
                // Lockfiles: presence and pinned versions are useful,
                // content is machine-generated.
                "poetry.lock", "Pipfile.lock"
            )
        ),

        // Human-readable text only. Useful for feeding specifications,
        // READMEs, changelogs or notes to the model without any source
        // code. No stubs: the whole point of this profile is to include
        // human-readable content, and every included extension is already
        // small enough that a stub would defeat the purpose.
        TEXT_PRESET to FilterConfig(
            excludeBinaries = true,
            inclusions = listOf(
                "txt", "md", "rst", "adoc", "org", "tex", "log", "csv",
                "json", "xml", "yaml", "yml"
            ),
            exclusions = emptyList()
        )
    )

    /**
     * Saves [config] as a user-defined profile named [name].
     *
     * Three validation rules are enforced here, mirroring the checks the
     * dialog already performs:
     *
     *  - the name cannot be blank after trimming,
     *  - the name cannot collide (case-insensitively) with a built-in
     *    profile, because built-ins are the canonical identifiers the
     *    spinner stores and a collision would make the saved profile
     *    unreachable, and
     *  - the name cannot collide (case-insensitively) with an existing
     *    user profile, because the underlying key is case-sensitive and
     *    two entries that differ only in case would be indistinguishable
     *    in the spinner.
     *
     * Throws IllegalArgumentException when any rule is violated. The
     * dialog surfaces the message verbatim through a Toast, so the
     * wording is deliberately user-facing.
     *
     * The config is stored normalized, so a caller that forgets to call
     * [FilterConfig.normalized] still gets the canonical form on disk.
     */
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

    /**
     * Returns true when [name] collides (case-insensitively) with a
     * built-in profile. Used by the save dialog to reject reserved names
     * before [savePreset] throws, so the user sees a friendly message
     * instead of an exception.
     */
    fun isBuiltInPresetName(name: String): Boolean =
        getDefaultPresets().keys.any { it.equals(normalizePresetName(name), ignoreCase = true) }

    /**
     * Returns the names of every user-saved profile, sorted
     * case-insensitively.
     *
     * The list is derived from the preference keys at read time rather
     * than maintained as a separate index. That keeps the storage model
     * flat (one key per profile, no bookkeeping entries) at the cost of a
     * full preference read, which is negligible in practice — the number
     * of saved profiles is small (a handful at most) and the read happens
     * only when the dialog is opened.
     *
     * Blank names are filtered out defensively: a corruption of the
     * preferences file or a manual `adb shell` edit could leave behind a
     * key whose suffix is empty, and surfacing a nameless entry in the
     * spinner would be confusing with no upside.
     */
    fun getUserPresetNames(): List<String> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith(PRESET_PREFIX) }
        .map { it.removePrefix(PRESET_PREFIX) }
        .filter(String::isNotBlank)
        .sortedBy { it.lowercase() }
        .toList()

    /** Returns true when [name] is an existing user-saved profile. */
    fun isUserPreset(name: String): Boolean = name in getUserPresetNames()

    /**
     * Deletes the user-defined profile named [name], if one exists.
     *
     * No-op when the name is not a user profile — built-in profiles are
     * immutable and cannot be removed, and the dialog is expected to hide
     * the delete affordance for them anyway. Being tolerant here rather
     * than throwing keeps the delete button safe to press even if the
     * profile was already removed by another path.
     */
    fun deleteUserPreset(name: String) {
        if (!isUserPreset(name)) return
        preferences.edit().remove(PRESET_PREFIX + name).apply()
    }

    /**
     * Loads the profile named [name], or returns null when it does not
     * exist.
     *
     * Resolution order:
     *  1. Built-in profiles (checked first, because they are the canonical
     *     identifiers the spinner stores).
     *  2. User-saved profiles.
     *
     * The returned config is already normalized (both the built-in
     * definitions and the deserialized user profiles go through
     * [FilterConfig.normalized]), so callers can use it directly without
     * a second pass.
     *
     * A user profile whose stored JSON cannot be parsed returns null
     * rather than throwing: a corrupted profile should behave like a
     * missing one, which lets the dialog fall back to a sensible default
     * instead of failing to open. The user's recourse is to delete the
     * corrupted profile through the same dialog, which is what the null
     * return path enables.
     */
    fun loadPreset(name: String): FilterConfig? {
        val resolvedName = normalizePresetName(name)
        getDefaultPresets()[resolvedName]?.let { return it }
        val serialized = preferences.getString(PRESET_PREFIX + resolvedName, null) ?: return null
        return runCatching { jsonToConfig(JSONObject(serialized)) }.getOrNull()
    }

    /**
     * Serializes [config] to the JSON shape stored in preferences.
     *
     * Mirrors [FilterConfigCodec.toJson] exactly, so a profile saved here
     * can be read by the codec and vice versa. Kept as a private helper
     * rather than delegating to the codec for historical reasons: the two
     * implementations existed in parallel before the codec was
     * extracted, and the duplication is small enough that collapsing it
     * now would risk a behavior change in profile round-tripping for
     * existing users. Any schema change must therefore be applied to both
     * places.
     *
     * Note: excludedPaths is intentionally absent. Profiles saved by
     * earlier versions may still contain the key; see [jsonToConfig] for
     * the tolerant read path.
     */
    private fun configToJson(config: FilterConfig): JSONObject = JSONObject().apply {
        put("excludeBinaries", config.excludeBinaries)
        put("respectGitignore", config.respectGitignore)
        put("useMaxSize", config.useMaxSize)
        put("maxSizeKb", config.maxSizeKb)
        put("inclusions", JSONArray(config.inclusions))
        put("exclusions", JSONArray(config.exclusions))
        put("stubPatterns", JSONArray(config.stubPatterns))
        put("useGit", config.useGit)
        put("gitRef", config.gitRef ?: JSONObject.NULL)
    }

    /**
     * Deserializes the JSON shape produced by [configToJson].
     *
     * Every field is read with its documented default, so a profile saved
     * by a pre-Tree-v2 build — which may be missing stubPatterns entirely
     * and which may contain the now-removed excludedPaths key — loads
     * cleanly. The removed key is simply ignored; the added one defaults
     * to empty. The result is passed through [FilterConfig.normalized] so
     * a hand-edited profile or a profile saved by a looser constraint
     * than the current normalizer comes out canonical.
     *
     * This method is called only from [loadPreset], which already wraps
     * it in runCatching; it is therefore allowed to throw on a malformed
     * JSON payload and rely on that outer catch to convert the failure
     * into a null return.
     */
    private fun jsonToConfig(json: JSONObject): FilterConfig = FilterConfig(
        excludeBinaries = json.optBoolean("excludeBinaries", true),
        respectGitignore = json.optBoolean("respectGitignore", true),
        useMaxSize = json.optBoolean("useMaxSize", true),
        maxSizeKb = json.optInt("maxSizeKb", FilterConfig.DEFAULT_MAX_SIZE_KB),
        inclusions = json.readStringList("inclusions"),
        exclusions = json.readStringList("exclusions"),
        stubPatterns = json.readStringList("stubPatterns"),
        useGit = json.optBoolean("useGit", false),
        gitRef = json.optString("gitRef").takeIf { it.isNotBlank() && it != "null" }
    ).normalized()

    /**
     * Reads a list-of-strings field from a [JSONObject] tolerantly.
     *
     * Same contract as [FilterConfigCodec]'s equivalent helper: missing
     * keys and wrong types yield an empty list, non-string elements are
     * converted to their string form, and empty strings after trimming
     * are dropped. The duplication with the codec is intentional — see
     * [configToJson] for the rationale.
     */
    private fun JSONObject.readStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    /**
     * Normalizes a profile name to its canonical form.
     *
     * Currently this trims surrounding whitespace and maps the legacy
     * Spanish label "Solo Texto Plano" to the canonical "Plain Text",
     * so a profile or preference written before the name was
     * translated keeps resolving to the same built-in entry.
     *
     * Future migrations of built-in names should be added here as
     * additional rename rules, so that a single call site handles every
     * legacy alias.
     */
    private fun normalizePresetName(name: String): String =
        name.trim().let { if (it == LEGACY_TEXT_PRESET) TEXT_PRESET else it }
}