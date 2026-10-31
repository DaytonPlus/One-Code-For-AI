package com.devdayton.onecodeforai

data class FilterConfig(
    val excludeBinaries: Boolean = true,
    val respectGitignore: Boolean = true,
    val maxSizeKb: Int = 512,
    val inclusions: List<String> = emptyList(),
    val exclusions: List<String> = emptyList(),
    val useGit: Boolean = false,
    val gitRef: String? = null,
    val excludedPaths: List<String> = emptyList()
)