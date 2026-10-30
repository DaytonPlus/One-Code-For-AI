package com.devdayton.onecodeforai

import java.io.File
import java.io.Writer

class CodeDumpGenerator(
    private val root: File,
    private val filter: FileFilter = FileFilter(root),
    private val gitRef: String? = null
) {
    companion object {
        private const val SEPARATOR = "================================================\n"
    }

    fun generateTo(writer: Writer) {
        // 1. Árbol de directorios
        writer.write(DirectoryTree(root, filter).build())

        // 2. Contenido (secuencia perezosa para no saturar memoria)
        collectFiles(root)
            .sortedBy { it.relativeTo(root).path.lowercase() }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace('\\', '/')
                writer.write("\n$SEPARATOR")
                writer.write("FILE: $rel\n")
                writer.write(SEPARATOR)

                val content = if (gitRef != null) {
                    GitUtils.getFileContent(root, gitRef, rel) ?: "[Error leyendo de Git]"
                } else {
                    runCatching { file.readText(Charsets.UTF_8) }.getOrElse { "[Error leyendo archivo: ${it.message}]" }
                }
                
                writer.write(content.replace("\r\n", "\n"))
                if (!content.endsWith("\n")) writer.write("\n")
            }
    }

    private fun collectFiles(dir: File): Sequence<File> = sequence {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                if (filter.include(file)) yieldAll(collectFiles(file))
            } else if (filter.include(file)) {
                yield(file)
            }
        }
    }
}