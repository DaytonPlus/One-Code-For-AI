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
 *  - The worker can be cancelled between chunks and between files, which is
 *    what allows [com.devdayton.onecodeforai.android.ExportCoordinator] to
 *    interrupt a running export promptly — even in the middle of a single
 *    multi-megabyte file.
 *
 * Read errors vs. write errors
 * ----------------------------
 * The two failure classes have deliberately different handling:
 *
 *  - **Read errors** (one source file cannot be opened, permissions, transient
 *    I/O) are *per-file*: the failure is written inline as
 *    `[File read error: …]` (or `[Git read error: …]`), [onReadError] is
 *    invoked with the offending path, and generation continues. A single
 *    unreadable file must never cost the rest of the dump.
 *  - **Write errors** (the destination writer throws) and **cancellation**
 *    (the worker thread is interrupted) are *fatal*: they propagate out of
 *    [generateTo] unchanged so the caller can abort, discard the partial
 *    output, and report a real failure. [InterruptedException] is never
 *    caught and re-wrapped as a read error.
 *
 * Source selection
 * ----------------
 * When [gitRef] is `null` or blank the generator walks the working tree. When
 * it is set, the generator uses the resolved Git tree instead, so the dump
 * always reflects the requested branch/tag/commit regardless of what is
 * checked out or modified locally.
 *
 * @param onProgress optional per-file progress callback; runs on the worker
 *   thread and is never invoked for files that failed to read.
 * @param onReadError optional callback invoked once for every file whose
 *   content could not be read. The path passed here is the same one written
 *   in the inline marker, so callers can aggregate it into a summary (e.g.
 *   the completion notification's "N read errors" line) without parsing the
 *   dump. Cancellation does **not** trigger this callback.
 */
class CodeDumpGenerator(
    private val root: File,
    private val filter: FileFilter = FileFilter(root),
    private val gitRef: String? = null,
    private val onProgress: FileProgressListener? = null,
    private val onReadError: ((String) -> Unit)? = null
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
     * @throws InterruptedException when the worker thread is interrupted. This
     *   propagates unchanged so the caller can distinguish a user cancellation
     *   from a genuine failure.
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
     * `[File read error: ...]`, forwarded to [onReadError], and do not abort
     * the export — a single unreadable file (permissions, transient I/O)
     * should not lose the rest of the dump. Cancellation, however, is not
     * caught: [ensureActive] throws [InterruptedException] which propagates
     * up and terminates the export.
     *
     * Cancellation checkpoints
     * ------------------------
     * [ensureActive] is called at four distinct phases so that Cancel stays
     * responsive even when the tree walk itself is slow:
     *
     *  - before writing the tree header (the initial `filter.walkIncludedFiles`
     *    scan can take seconds on very wide directory trees),
     *  - immediately after the file list is materialized,
     *  - before and after the `sumOf` size pass (touches every file's
     *    `length()`, which can block on network mounts),
     *  - once per file inside the streaming loop.
     *
     * Inside a single file the chunk-level check in [copyNormalizedText] takes
     * over, so a multi-megabyte file never stalls the Cancel button.
     */
    private fun generateWorkingTree(writer: Writer) {
        ensureActive()
        DirectoryTree(root, filter).writeTo(writer)
        ensureActive()

        val files = filter.walkIncludedFiles().toList()
        ensureActive()
        val total = files.size
        val totalBytes = files.sumOf { it.length() }
        ensureActive()

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
            } catch (interrupted: InterruptedException) {
                // Cancellation must never be mistaken for a read error. Propagate
                // unchanged so the caller aborts the whole export.
                throw interrupted
            } catch (error: Exception) {
                onReadError?.invoke(relativePath)
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
     * `[Git read error: <path>]`, forwarded to [onReadError], and the export
     * continues with the remaining files, mirroring the working-tree
     * behaviour. Cancellation propagates out of [GitUtils.withSnapshot]
     * unchanged because [GitUtils.copyNormalizedText] throws
     * [InterruptedException] on an interrupted worker thread.
     */
    private fun generateGitSnapshot(writer: Writer, ref: String) {
        GitUtils.withSnapshot(root, ref) { snapshot ->
            ensureActive()
            val includedFiles = snapshot.listFiles().filter { file ->
                filter.includePath(
                    relativePath = file.path,
                    isDirectory = false,
                    sizeBytes = file.size
                )
            }
            ensureActive()
            DirectoryTree(root, filter).writePathsTo(writer, includedFiles.map { it.path })
            ensureActive()

            val total = includedFiles.size
            val totalBytes = includedFiles.sumOf { it.size }
            var processed = 0
            var bytesAccum = 0L
            includedFiles.forEach { file ->
                ensureActive()
                writeFileHeader(writer, file.path)
                try {
                    if (!snapshot.writeFileContent(file.path, writer)) {
                        onReadError?.invoke(file.path)
                        writer.write("[Git read error: ${file.path}]\n")
                    }
                } catch (interrupted: InterruptedException) {
                    // Same rule as the working-tree branch: cancellation is
                    // fatal and must not be downgraded to a per-file read error.
                    throw interrupted
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
     * Cooperative cancellation check performed at every phase boundary —
     * before the tree header, after the initial file scan, before and after
     * the byte-count pass, and once per file inside the streaming loop.
     *
     * The worker thread's interrupt flag is set by
     * [com.devdayton.onecodeforai.android.ExportCoordinator.cancel], so this
     * surfaces as a thrown exception that unwinds the export promptly. The
     * per-chunk check inside [copyNormalizedText] covers cancellation that
     * arrives in the middle of a single large file.
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
     *
     * Cancellation
     * ------------
     * The worker thread's interrupt flag is checked **before every read**
     * (roughly every [BUFFER_SIZE] characters). This is what makes Cancel
     * respond within a few kilobytes of the current file instead of having to
     * wait for a potentially multi-megabyte file to finish streaming. The
     * thrown [InterruptedException] propagates unchanged so callers can
     * distinguish it from a normal I/O failure.
     */
    private fun copyNormalizedText(reader: java.io.Reader, writer: Writer) {
        val buffer = CharArray(BUFFER_SIZE)
        var pendingCarriageReturn = false
        var lastWasNewline = false
        while (true) {
            // Check every chunk so that a single large file cannot block the
            // Cancel button. The outer `forEach` in `generateWorkingTree` /
            // `generateGitSnapshot` only checks between files, which is not
            // enough for files bigger than a few hundred KiB.
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Dump generation was interrupted.")
            }
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