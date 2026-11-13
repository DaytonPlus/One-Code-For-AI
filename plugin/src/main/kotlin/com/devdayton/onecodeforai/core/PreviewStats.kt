package com.devdayton.onecodeforai.core

data class ExtensionStats(
    val extension: String,
    var files: Int = 0,
    var tokens: Long = 0L,
    var bytes: Long = 0L
)

data class PreviewStats(
    val totalFiles: Int = 0,
    val stubFiles: Int = 0,
    val totalTokens: Long = 0L,
    val totalBytes: Long = 0L,
    val byExtension: List<ExtensionStats> = emptyList()
)