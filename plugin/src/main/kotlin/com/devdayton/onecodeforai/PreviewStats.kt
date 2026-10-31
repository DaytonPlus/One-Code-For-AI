package com.devdayton.onecodeforai

import java.io.File

data class ExtensionStats(
    val extension: String,
    var files: Int = 0,
    var tokens: Long = 0L,
    var bytes: Long = 0L
)

data class PreviewStats(
    val totalFiles: Int = 0,
    val totalTokens: Long = 0L,
    val totalBytes: Long = 0L,
    val byExtension: List<ExtensionStats> = emptyList()
)

/**
 * Estima estadísticas del volcado sin generar el archivo.
 * Los tokens se aproximan como bytes/4 (heurística común para inglés/código).
 */
object CodeStats {

    fun compute(root: File, filter: FileFilter): PreviewStats {
        var totalFiles = 0
        var totalTokens = 0L
        var totalBytes = 0L
        val byExt = mutableMapOf<String, ExtensionStats>()

        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (file in children) {
                if (file.isDirectory) {
                    if (filter.include(file)) walk(file)
                } else if (file.isFile && filter.include(file)) {
                    val bytes = file.length()
                    val tokens = if (bytes <= 0L) 0L else (bytes + 3L) / 4L
                    val ext = file.extension.lowercase().ifEmpty { "sin-ext" }

                    totalFiles++
                    totalTokens += tokens
                    totalBytes += bytes

                    val stat = byExt.getOrPut(ext) { ExtensionStats(ext) }
                    stat.files++
                    stat.tokens += tokens
                    stat.bytes += bytes
                }
            }
        }
        walk(root)

        return PreviewStats(
            totalFiles = totalFiles,
            totalTokens = totalTokens,
            totalBytes = totalBytes,
            byExtension = byExt.values.sortedByDescending { it.tokens }
        )
    }
}