package com.devdayton.onecodeforai.core

data class FilterConfig(
    val excludeBinaries: Boolean = true,
    val respectGitignore: Boolean = true,
    val useMaxSize: Boolean = true,
    val maxSizeKb: Int = DEFAULT_MAX_SIZE_KB,
    val inclusions: List<String> = emptyList(),
    val exclusions: List<String> = emptyList(),
    val stubPatterns: List<String> = emptyList(),

    val enableInclusions: Boolean = true,

    val enableExclusions: Boolean = true,

    val enableStubPatterns: Boolean = true,

    val useGit: Boolean = false,
    val gitRef: String? = null
) {

    val maxFileSizeBytes: Long
        get() = if (!useMaxSize) Long.MAX_VALUE
                else maxSizeKb.coerceAtLeast(1).toLong() * BYTES_PER_KB

    fun normalized(): FilterConfig = copy(
        maxSizeKb = maxSizeKb.coerceAtLeast(1),
        inclusions = inclusions.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() },
        exclusions = exclusions.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() },
        stubPatterns = stubPatterns.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
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