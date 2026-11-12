package com.devdayton.onecodeforai.core

/**
 * Immutable configuration for a project export.
 *
 * The property names are intentionally stable: user profiles written by
 * previous versions are stored as JSON using these keys. New fields are
 * added with backward-compatible defaults.
 *
 * ### Scope: this is the rules half of the export model
 *
 * As of Tree v2 the export configuration is split into two independent
 * pieces:
 *
 *  - Rules (this class): declarative filters that decide the initial
 *    state of every entry when the project tree is scanned. They travel
 *    with the preset across projects.
 *  - Overrides (TreeOverrides): per-path manual state changes made in
 *    the tree selector. They are persisted per project under
 *    .platform/plugins/com.devdayton.onecodeforai/config.json and only
 *    on Generate.
 *
 * Keeping the two separate lets the user switch profiles without losing
 * manual decisions and lets a profile stay portable.
 *
 * ### excludedPaths was removed in Tree v2
 *
 * Earlier versions carried an excludedPaths field that mixed manually
 * excluded paths with the profile. That leaked one project's manual
 * exclusions into every other project opened with the same profile. The
 * codecs accept the old excludedPaths key on read and ignore it.
 *
 * ### Evaluation order in FileFilter.resolveMode
 *
 *  1. stubPatterns match -> STUB
 *  2. inclusions match   -> INCLUDE
 *  3. exclusions match   -> EXCLUDE
 *  4. .gitignore match   -> EXCLUDE (only when respectGitignore)
 *  5. size overflow      -> EXCLUDE (only when useMaxSize)
 *  6. binary extension   -> EXCLUDE (only when excludeBinaries)
 *  7. hidden files       -> EXCLUDE
 *  8. text extension     -> INCLUDE
 *  9. otherwise          -> EXCLUDE
 */
data class FilterConfig(
    val excludeBinaries: Boolean = true,
    val respectGitignore: Boolean = true,
    val useMaxSize: Boolean = true,
    val maxSizeKb: Int = DEFAULT_MAX_SIZE_KB,
    val inclusions: List<String> = emptyList(),
    val exclusions: List<String> = emptyList(),

    // Glob patterns for files that should be listed but not read.
    // A match here produces a NodeState.STUB entry in the export: the
    // file banner and a content-omitted marker are written, but the
    // file body is never opened.
    //
    // Syntax is the same as inclusions and exclusions: bare extension
    // (lock, log), filename glob (package-lock.json, *.min.js), or path
    // glob (**/generated/**, app/build/outputs/**). Case-insensitive.
    //
    // Evaluated before inclusions and exclusions: a file matching both
    // a stub pattern and an inclusion is exported as a stub.
    //
    // This list holds the rule-based half of the stub feature. The
    // per-path half lives in TreeOverrides.stubPaths.
    val stubPatterns: List<String> = emptyList(),

    val useGit: Boolean = false,
    val gitRef: String? = null
) {
    /**
     * A positive, overflow-safe byte limit used by file filters.
     * When useMaxSize is false, the filter never rejects a file for size.
     */
    val maxFileSizeBytes: Long
        get() = if (!useMaxSize) Long.MAX_VALUE
                else maxSizeKb.coerceAtLeast(1).toLong() * BYTES_PER_KB

    /**
     * Returns a copy with every list and numeric field in canonical form.
     */
    fun normalized(): FilterConfig = copy(
        maxSizeKb = maxSizeKb.coerceAtLeast(1),
        inclusions = inclusions.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() },
        exclusions = exclusions.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() },
        stubPatterns = stubPatterns.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
    )

    companion object {
        const val DEFAULT_MAX_SIZE_KB = 512
        const val BYTES_PER_KB = 1024L

        /**
         * Normalizes a relative path for storage and comparison.
         */
        fun normalizeRelativePath(path: String): String = path
            .trim()
            .replace('\\', '/')
            .trimStart('/')
            .replace(Regex("/+/"), "/")
            .removeSuffix("/")
    }
}