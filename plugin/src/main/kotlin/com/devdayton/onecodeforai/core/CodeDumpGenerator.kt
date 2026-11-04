package com.devdayton.onecodeforai.core

import java.io.File
import java.io.IOException
import java.io.Writer

/**
 * Callback contract for per-file progress.
 *
 * Invoked once per file **after** that file's content has been fully written
 * to the destination. [bytesProcessed] is cumulative (the sum of file lengths
 * seen so far) and [totalBytes] is the sum of every eligible file length, so
 * callers can display a byte-accurate progress bar alongside the file counter.
 *
 * The callback runs on the export worker thread; callers must marshal to the
 * main looper before touching views.
 */
fun interface FileProgressListener {
    fun onProgress(processed: Int, total: Int, currentPath: String, bytesProcessed: Long, totalBytes: Long)
}

/**
 * Serializes a project (or a Git snapshot) into the plugin's dump format.
 *
 * The output has two sections:
 *  1. A directory tree of the included entries, produced by [DirectoryTree].
 *  2. One block per included file, separated by a banner of the form
 *     `================================================` / `FILE: <path>` /
 *     `================================================`.
 *
 * Files are streamed incrementally: the writer is fed in bounded chunks and
 * no whole-file buffer is ever materialized. This matters for two reasons:
 *  - The output itself can be tens of megabytes on large repositories, so
 *    building a `String` first would risk OOM on Android.
 *  - The worker can be cancelled between files, which is what allows
 *    [ExportCoordinator] to interrupt a running export promptly.
 *
 * Source selection
 * ----------------
 * When [gitRef] is `null` or blank the generator walks the working tree. When
 * it is set, the generator uses the resolved Git tree instead, so the dump
 * always reflects the requested branch/tag/commit regardless of what is
 * checked out or modified locally.
 */
class CodeDumpGenerator(
    private val root: File,
    private val filter: FileFilter = FileFilter(root),
    private val gitRef: String? = null,
    private val onProgress: FileProgressListener? = null
) {
    companion object {
        private const val SEPARATOR = "================================================\n"
        private const val BUFFER_SIZE = 8 * 1024
    }

    /**
     * Writes the complete dump to [writer].
     *
     * The choice between working-tree and Git modes is made here so callers
     * do not have to know which generator path applies to their configuration.
     *
     * @throws IOException when [root] is not a directory or when the Git
     *   reference cannot be resolved. Per-file read failures are handled
     *   internally (see [generateWorkingTree] and [generateGitSnapshot]) and
     *   do not abort the whole export.
     */
    @Throws(IOException::class)
    fun generateTo(writer: Writer) {
        require(root.isDirectory) { "Project root is not a directory: ${root.path}" }
        val selectedRef = gitRef?.trim()?.takeIf(String::isNotEmpty)
        if (selectedRef == null) generateWorkingTree(writer) else generateGitSnapshot(writer, selectedRef)
    }

    /**
     * Working-tree mode: writes the tree header, then every eligible file in
     * deterministic depth-first order.
     *
     * Per-file read errors are reported inline as
     * `[File read error: ...]` and do not abort the export — a single
     * unreadable file (permissions, transient I/O) should not lose the rest
     * of the dump. Cancellation, however, is not caught: [ensureActive]
     * throws [InterruptedException] which propagates up and terminates the
     * export.
     */
    private fun generateWorkingTree(writer: Writer) {
        DirectoryTree(root, filter).writeTo(writer)
        val files = filter.walkIncludedFiles().toList()
        val total = files.size
        val totalBytes = files.sumOf { it.length() }

        var processed = 0
        var bytesAccum = 0L
        files.forEach { file ->
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
            processed++
            bytesAccum += file.length()
            onProgress?.onProgress(processed, total, relativePath, bytesAccum, totalBytes)
        }
    }

    /**
     * Git mode: opens a single repository session (via
     * [GitUtils.withSnapshot]) and streams the blobs of the requested tree.
     *
     * The tree header is built from the snapshot's file list rather than from
     * the working directory, so the output accurately reflects the state of
     * the requested reference even if the working tree diverged.
     *
     * A blob that cannot be read is reported inline as
     * `[Git read error: <path>]`; the export continues with the remaining
     * files, mirroring the working-tree behaviour.
     */
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

            val total = includedFiles.size
            val totalBytes = includedFiles.sumOf { it.size }
            var processed = 0
            var bytesAccum = 0L
            includedFiles.forEach { file ->
                ensureActive()
                writeFileHeader(writer, file.path)
                if (!snapshot.writeFileContent(file.path, writer)) {
                    writer.write("[Git read error: ${file.path}]\n")
                }
                processed++
                bytesAccum += file.size
                onProgress?.onProgress(processed, total, file.path, bytesAccum, totalBytes)
            }
        }
    }

    /**
     * Emits the per-file banner that separates consecutive file contents.
     *
     * A leading newline guarantees that the previous file's content cannot
     * run into the separator even when the source file lacked a trailing
     * newline (though [copyNormalizedText] already ensures one).
     */
    private fun writeFileHeader(writer: Writer, relativePath: String) {
        writer.write("\n")
        writer.write(SEPARATOR)
        writer.write("FILE: ")
        writer.write(relativePath)
        writer.write("\n")
        writer.write(SEPARATOR)
    }

    /**
     * Returns [file]'s path relative to [root], normalized to forward slashes.
     *
     * Uses the canonical form so that symlink aliases inside the project do
     * not produce duplicate entries with different path strings. Falls back
     * to the plain relative path when canonicalization fails (e.g. a broken
     * symlink).
     */
    private fun relativePath(file: File): String = runCatching {
        root.canonicalFile.toPath().relativize(file.canonicalFile.toPath())
            .toString()
            .replace('\\', '/')
    }.getOrElse { file.relativeTo(root).path.replace('\\', '/') }

    /**
     * Cooperative cancellation check. Called once per file; the worker
     * thread's interrupt flag is set by
     * [com.devdayton.onecodeforai.android.ExportCoordinator.cancel] and
     * surfaces here as a thrown exception so the export unwinds promptly.
     */
    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Dump generation was interrupted.")
        }
    }

    /**
     * Copies [reader] to [writer] while normalizing line endings to `\n`.
     *
     * Two invariants are guaranteed on output:
     *  - CRLF, LF, and lone CR all become `\n`.
     *  - The result always ends with exactly one newline, so consecutive
     *    file blocks never bleed into each other.
     *
     * The implementation buffers a single character of lookahead to detect a
     * lone `\r` at the end of the stream and emit it as `\n` before closing,
     * rather than dropping it.
     */
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
                if (char == '\r') pendingCarriageReturn = true
                else { writer.write(char.code); lastWasNewline = char == '\n' }
            }
        }
        if (pendingCarriageReturn) { writer.write('\n'.code); lastWasNewline = true }
        if (!lastWasNewline) writer.write('\n'.code)
    }
}