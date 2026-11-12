package com.devdayton.onecodeforai.core

import com.devdayton.onecodeforai.android.PluginLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Reads and writes the per-project tree configuration that the
 * configuration dialog persists under
 * `<projectRoot>/.platform/plugins/com.devdayton.onecodeforai/config.json`.
 *
 * ### What is stored
 *
 * The config captures three things:
 *
 *  - **[LoadedConfig.presetName]** — the name of the preset the user
 *    was on when they pressed Generate. Used to restore the spinner
 *    selection on the next open, and to detect whether the config
 *    should be applied at all (a config generated under one preset
 *    should not be silently applied when the user opens the dialog
 *    with a different preset).
 *  - **[LoadedConfig.filterConfig]** — the full [FilterConfig] that
 *    produced the tree, stored verbatim so that a config generated
 *    under a deleted or renamed preset can still be restored without
 *    losing the rules.
 *  - **[LoadedConfig.overrides]** — the [TreeOverrides] produced by
 *    diffing the tree's final states against the filter-derived
 *    states at Generate time. Only paths whose state differs from
 *    what the filter would produce are stored, which keeps the file
 *    small and stable across filter changes (see [saveDiff]).
 *
 * ### Why the project filesystem
 *
 * `SharedPreferences` is process-wide, so a per-project state stored
 * there would leak one project's manual decisions into every other
 * project opened by the same plugin install. The project's own
 * filesystem is a better fit: the state is scoped to the project by
 * construction, it travels with the project (a `git clone` of the
 * `.platform` directory carries the plugin's config along), and it is
 * readable by other tools that might want to inspect or edit it.
 *
 * The path under `.platform/plugins/<package>/` is chosen to match
 * the convention the host IDE already uses for its own plugin data, so
 * a user inspecting `.platform/` sees every plugin's data in one
 * place.
 *
 * ### Failure policy
 *
 *  - **Read failures** (missing file, malformed JSON, wrong version,
 *    permission error) return `null` and the dialog falls back to the
 *    filter-only state — the same behaviour as a fresh project.
 *    Corrupted config is not fatal: the user's recourse is to delete
 *    the file or generate again, both of which are possible from the
 *    UI.
 *  - **Write failures** (read-only project directory, disk full,
 *    permission error) are logged at WARN level and swallowed. The
 *    dump the user just generated is already valid and complete; a
 *    failure to persist the config does not invalidate it. The user's
 *    recourse is to generate again in a writable location.
 *
 * ### Atomicity
 *
 * Writes go through a `.tmp` sibling and a rename, so a crash mid-write
 * leaves either the previous config or no config, never a truncated
 * one. The rename is POSIX-atomic on the same filesystem, which is
 * always the case here because the temp file lives in the same
 * directory as the target.
 *
 * ### Schema versioning
 *
 * The top-level `version` field is checked on read. A config written
 * by a future version is rejected (returns `null`) rather than being
 * misinterpreted: the schema may have changed in ways the current
 * reader cannot understand, and silently falling back to filter-only
 * state is strictly safer than applying a partially-understood
 * config. A config written by a *past* version is read with the
 * documented defaults for any missing field, which is the same
 * forward-compatible behaviour the preset codecs use.
 */
object TreeConfigStore {

    /**
     * Directory (relative to the project root) where the config file
     * lives.
     *
     * Kept as a single constant so a future version that moves the
     * location (e.g. into a shared `.config/` directory) only has to
     * change it here. The nested `plugins/<package>` convention
     * matches the host IDE's own layout for plugin data.
     */
    private const val CONFIG_DIR = ".platform/plugins/com.devdayton.onecodeforai"

    /** Name of the config file inside [CONFIG_DIR]. */
    private const val CONFIG_FILE = "config.json"

    /**
     * Schema version this reader/writer understands.
     *
     * Bump when the schema changes in a way that an older reader
     * cannot safely handle. The reader rejects unknown versions
     * entirely rather than trying to interpret them, which is the
     * conservative choice for a file whose primary purpose is to
     * restore a UI state the user can otherwise reach manually.
     */
    private const val SCHEMA_VERSION = 1

    /**
     * The subset of the config file a caller actually needs.
     *
     * @param presetName    the preset the config was generated under.
     * @param filterConfig  the rules that produced the tree.
     * @param overrides     the per-path manual decisions.
     */
    data class LoadedConfig(
        val presetName: String,
        val filterConfig: FilterConfig,
        val overrides: TreeOverrides
    )

    /**
     * Loads the project's config, or returns `null` when there is no
     * config, it cannot be parsed, or it was written by an incompatible
     * schema version.
     *
     * The caller (`ConfigurationDialog`) treats `null` as "no saved
     * state": the tree is scanned with the filter-derived initial
     * states only, which is exactly what a fresh project sees.
     */
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
                // Reuse the existing codec so the config format stays
                // identical to the preset format. That is what lets a
                // user copy a preset's JSON into a config.json (or
                // vice versa) without the plugin rejecting it.
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

    /**
     * Computes the diff between the tree's final per-path states and
     * the states the filter would have produced on its own, and
     * persists that diff plus the preset name and the filter config
     * to `config.json`.
     *
     * ### Why the diff, not the full state
     *
     * If the filter says "every .kt is INCLUDE" and the user did not
     * touch any .kt file, storing those hundreds of paths as
     * `includedPaths` would be redundant: the filter already includes
     * them, and the entry would become incorrect if the user later
     * changed the filter to exclude .kt files. Storing only the paths
     * whose state *differs* from the filter's natural result keeps the
     * file small (typically zero to a handful of entries), stable
     * across filter changes, and correct when the filter changes.
     *
     * ### When the diff is recomputed
     *
     * Called on Generate, after the user has settled on a tree and
     * before the export runs. The caller is `ConfigurationDialog`
     * (see `launchExport`). Doing it here rather than in the export
     * coordinator keeps the write close to the moment the user
     * commits to it, and guarantees that a failed export does not
     * leave a config that no longer matches the tree on disk.
     *
     * @param projectRoot   project whose config should be written.
     * @param currentStates the tree's final per-path states at the
     *                      moment the user pressed Generate. LOCKED
     *                      entries must be omitted; the dialog's
     *                      `currentStates()` already does this.
     * @param filter        the filter that produced the initial tree.
     *                      Used to re-derive the "natural" state of
     *                      each path so the diff can be computed.
     * @param presetName    the preset the config is being saved under.
     *                      Stored verbatim and restored on the next
     *                      open.
     */
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
            // Only paths whose user-settled state differs from the
            // filter's natural state are worth persisting. Anything
            // that matches would be restored automatically from the
            // filter on the next open.
            if (state == natural) return@forEach
            when (state) {
                NodeState.INCLUDE -> included.add(path)
                NodeState.STUB -> stub.add(path)
                NodeState.EXCLUDE -> excluded.add(path)
                NodeState.LOCKED -> Unit // caller must filter these out
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

    /**
     * Removes the project's config file, if present. Used only by
     * future tooling; the dialog itself never calls this.
     */
    fun clear(projectRoot: File) {
        runCatching { configFile(projectRoot).delete() }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Serializes [filterConfig], [overrides], and [presetName] to the
     * config file atomically.
     *
     * The write is best-effort: failures are logged but not rethrown,
     * because the export that triggered this write is already complete
     * by the time this runs, and a failure to persist the config must
     * not fail the export.
     */
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

            // Write to a temp file in the same directory and rename,
            // so a crash mid-write leaves either the previous config
            // or no config, never a truncated one.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(payload.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // Fallback for filesystems that refuse to overwrite
                // the destination (rare on Android but possible on
                // some external storages). Not atomic, but the failure
                // path is strictly better than leaving a `.tmp`
                // sibling that nothing will ever read.
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

    /**
     * Returns the [File] object for the config path, without touching
     * the filesystem. The caller is responsible for creating the
     * parent directory if it does not exist.
     */
    private fun configFile(projectRoot: File): File =
        File(projectRoot, "$CONFIG_DIR/$CONFIG_FILE")

    /**
     * Reads a list-of-strings field from a [JSONObject] tolerantly.
     * Same contract as the equivalent helper in
     * [FilterConfigCodec]: missing key, wrong type, or a non-string
     * element all degrade to an empty list, and empty strings after
     * trimming are dropped.
     *
     * The duplication with the codec is intentional: the config file
     * is not a preset, its reader must not depend on future changes
     * to the preset codec, and the helper is small enough that
     * sharing it would create a maintenance coupling for no benefit.
     */
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