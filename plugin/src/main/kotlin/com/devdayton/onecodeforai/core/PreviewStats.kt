package com.devdayton.onecodeforai.core

/** Aggregated export preview statistics grouped by file extension. */
data class ExtensionStats(
    val extension: String,
    var files: Int = 0,
    var tokens: Long = 0L,
    var bytes: Long = 0L
)

/**
 * Statistics displayed in the configuration dialog.
 *
 * Token count is an estimate, not a tokenizer-specific exact count. The current
 * estimator uses approximately four UTF-8 bytes per token.
 */
data class PreviewStats(
    val totalFiles: Int = 0,
    val totalTokens: Long = 0L,
    val totalBytes: Long = 0L,
    val byExtension: List<ExtensionStats> = emptyList()
)
