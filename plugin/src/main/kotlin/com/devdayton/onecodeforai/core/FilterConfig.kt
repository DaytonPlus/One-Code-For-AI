package com.devdayton.onecodeforai.core

/**
 * Immutable configuration for a project export.
 *
 * The property names are intentionally stable: user profiles written by previous
 * versions are stored as JSON using these keys.
 */
data class FilterConfig(
    val excludeBinaries: Boolean = true,
    val respectGitignore: Boolean = true,
    val maxSizeKb: Int = DEFAULT_MAX_SIZE_KB,
    val inclusions: List<String> = emptyList(),
    val exclusions: List<String> = emptyList(),
    val useGit: Boolean = false,
    val gitRef: String? = null,
    val excludedPaths: List<String> = emptyList()
) {
    /** A positive, overflow-safe byte limit used by file filters. */
    val maxFileSizeBytes: Long
        get() = maxSizeKb.coerceAtLeast(1).toLong() * BYTES_PER_KB

    /**
     * Removes duplicate and blank rules and normalizes paths without mutating
     * the instance. Existing JSON data remains backward-compatible.
     */
    fun normalized(): FilterConfig = copy(
        maxSizeKb = maxSizeKb.coerceAtLeast(1),
        inclusions = inclusions.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() },
        exclusions = exclusions.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() },
        excludedPaths = excludedPaths.map(::normalizeRelativePath)
            .filter(String::isNotEmpty)
            .distinct()
    )

    companion object {
        const val DEFAULT_MAX_SIZE_KB = 512
        const val BYTES_PER_KB = 1024L

        fun normalizeRelativePath(path: String): String = path
            .trim()
            .replace('\\', '/')
            .trimStart('/')
            .replace(Regex("/+/"), "/")
            .removeSuffix("/")
    }
}
