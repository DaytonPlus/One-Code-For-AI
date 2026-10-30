package com.devdayton.onecodeforai

data class FilterConfig(
    val excludeBinaries: Boolean = true,
    val respectGitignore: Boolean = true,
    val maxSizeKb: Int = 512,
    val inclusions: List<String> = emptyList(),
    val exclusions: List<String> = emptyList(),
    val useGit: Boolean = false, // Si usar Git para leer el código
    val gitRef: String? = null   // Rama o commit específico
)