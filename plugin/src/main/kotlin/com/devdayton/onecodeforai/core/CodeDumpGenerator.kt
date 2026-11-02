package com.devdayton.onecodeforai.core

import com.devdayton.onecodeforai.core.FileFilter
import com.devdayton.onecodeforai.core.GitUtils
import com.devdayton.onecodeforai.core.DirectoryTree
import java.io.File
import java.io.IOException
import java.io.Writer

/**
 * Generates the textual project snapshot.
 *
 * File contents are copied through bounded buffers; the complete project is never
 * held in memory. When [gitRef] is supplied, both the tree and the file contents
 * come from that Git snapshot rather than the current working directory.
 */
class CodeDumpGenerator(
    private val root: File,
    private val filter: FileFilter = FileFilter(root),
    private val gitRef: String? = null
) {
    companion object {
        private const val SEPARATOR = "================================================\n"
        private const val BUFFER_SIZE = 8 * 1024
    }

    /** Writes a complete dump to [writer], leaving stream ownership to the caller. */
    @Throws(IOException::class)
    fun generateTo(writer: Writer) {
        require(root.isDirectory) { "Project root is not a directory: ${root.path}" }
        val selectedRef = gitRef?.trim()?.takeIf(String::isNotEmpty)

        if (selectedRef == null) {
            generateWorkingTree(writer)
        } else {
            generateGitSnapshot(writer, selectedRef)
        }
    }

    private fun generateWorkingTree(writer: Writer) {
        DirectoryTree(root, filter).writeTo(writer)

        filter.walkIncludedFiles()
            .forEach { file ->
                ensureActive()
                val relativePath = relativePath(file)
                writeFileHeader(writer, relativePath)
                try {
                    file.bufferedReader(Charsets.UTF_8, BUFFER_SIZE).use { reader ->
                        copyNormalizedText(reader, writer)
                    }
                } catch (error: Exception) {
                    writer.write("[File read error: ${error.message ?: error.javaClass.simpleName}]\n")
                }
            }
    }

    private fun generateGitSnapshot(writer: Writer, ref: String) {
        GitUtils.withSnapshot(root, ref) { snapshot ->
            val includedFiles = snapshot.listFiles().filter { file ->
                filter.includePath(
                    relativePath = file.path,
                    isDirectory = false,
                    sizeBytes = file.size
                )
            }

            DirectoryTree(root, filter).writePathsTo(writer, includedFiles.map { it.path })

            includedFiles.forEach { file ->
                ensureActive()
                writeFileHeader(writer, file.path)
                if (!snapshot.writeFileContent(file.path, writer)) {
                    writer.write("[Git read error: ${file.path}]\n")
                }
            }
        }
    }

    private fun writeFileHeader(writer: Writer, relativePath: String) {
        writer.write("\n")
        writer.write(SEPARATOR)
        writer.write("FILE: ")
        writer.write(relativePath)
        writer.write("\n")
        writer.write(SEPARATOR)
    }

    private fun relativePath(file: File): String = runCatching {
        root.canonicalFile.toPath().relativize(file.canonicalFile.toPath())
            .toString()
            .replace('\\', '/')
    }.getOrElse { file.relativeTo(root).path.replace('\\', '/') }

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Dump generation was interrupted.")
        }
    }

    private fun copyNormalizedText(reader: java.io.Reader, writer: Writer) {
        val buffer = CharArray(BUFFER_SIZE)
        var pendingCarriageReturn = false
        var lastWasNewline = false

        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val char = buffer[index]
                if (pendingCarriageReturn) {
                    writer.write('\n'.code)
                    lastWasNewline = true
                    pendingCarriageReturn = false
                    if (char == '\n') continue
                }
                if (char == '\r') {
                    pendingCarriageReturn = true
                } else {
                    writer.write(char.code)
                    lastWasNewline = char == '\n'
                }
            }
        }

        if (pendingCarriageReturn) {
            writer.write('\n'.code)
            lastWasNewline = true
        }
        if (!lastWasNewline) writer.write('\n'.code)
    }
}
