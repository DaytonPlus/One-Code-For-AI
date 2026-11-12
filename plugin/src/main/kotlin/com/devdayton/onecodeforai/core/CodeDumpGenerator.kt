package com.devdayton.onecodeforai.core

import java.io.File
import java.io.IOException
import java.io.Writer

/**
 * Callback contract for per-file progress.
 *
 * Invoked once per file **after** that file has been fully written to
 * the destination. [bytesProcessed] is cumulative and reflects the
 * actual number of bytes written to the dump so far — **not** the sum
 * of source file lengths. This distinction matters in Tree v2: a
 * [NodeState.STUB] entry contributes only the bytes of its header and
 * marker to the dump, even though the source file on disk may be
 * hundreds of kilobytes. Reporting the source size would make the
 * progress bar advance while the dump barely grows, which contradicts
 * what the user sees on disk and in the result panel.
 *
 * [totalBytes] is the sum of the *expected* output bytes for every
 * file that will be processed — full source length for
 * [NodeState.INCLUDE], estimated header + marker length for
 * [NodeState.STUB]. It is therefore an upper bound, not an exact
 * figure, because the actual content of an INCLUDE file is normalized
 * (CRLF → LF) as it is written; the difference is negligible for
 * progress purposes.
 *
 * The callback runs on the export worker thread; callers must marshal
 * to the main looper before touching views.
 */
fun interface FileProgressListener {
    fun onProgress(processed: Int, total: Int, currentPath: String, bytesProcessed: Long, totalBytes: Long)
}

/**
 * Serializes a project (or a Git snapshot) into the plugin's dump format.
 *
 * The output has two sections:
 *  1. A directory tree of the included entries, produced by [DirectoryTree].
 *     Stub entries are marked with a trailing reference tag so the
 *     consuming model can tell at a glance which entries it has content
 *     for and which it only has metadata for.
 *  2. One block per included or stubbed file, separated by a banner of
 *     the form `====…` / `FILE: <path>` / `====…`. Included files
 *     contribute their normalized content; stubs contribute a one-line
 *     marker.
 *
 * ### Tree v2: states, not a boolean
 *
 * Prior to Tree v2 the generator received a [FileFilter] and decided for
 * itself whether each file should be included, based on the boolean
 * `include`/`includePath` API. That made the generator the only place
 * that could distinguish "this file is in the dump" from "this file is
 * not", which was fine for two states but breaks down once the tree
 * selector introduces stubs and locks.
 *
 * The generator now receives a **snapshot** of the tree's per-path
 * states through the [nodes] parameter. The caller (the export
 * coordinator, fed by the configuration dialog) is responsible for:
 *
 *  - running the tree scanner,
 *  - applying per-path overrides from [TreeOverrides],
 *  - applying the toggle for locked entries (if the user enabled it),
 *  - and producing the final map.
 *
 * The generator's job is therefore narrow and honest: iterate the map,
 * write what each state asks for, report progress. It never inspects
 * the filesystem to decide inclusion; it only reads file sizes to
 * format a stub's marker and file content to copy an INCLUDE body.
 *
 * This split is what allows the tree to be the authoritative source of
 * truth without duplicating the filtering logic across two layers.
 *
 * ### States handled by the generator
 *
 *  - [NodeState.INCLUDE] — file header plus normalized content. This is
 *    the only state that reads the source file's body.
 *  - [NodeState.STUB] — file header plus a `[content omitted: N B —
 *    reference only]` marker. The source file is opened only through
 *    `File.length()`, never through its input stream. A file that has
 *    disappeared between scan and export is silently omitted: the
 *    model would not learn anything useful from a "read error" for a
 *    file it was not going to receive the content of anyway.
 *  - [NodeState.EXCLUDE] — not written.
 *  - [NodeState.LOCKED] — not written. Locked entries are a UI concept
 *    (visible but disabled in the tree); the export treats them as
 *    excluded, since the user has not opted into seeing them yet.
 *
 * The map is expected to be the *complete* set of files the export
 * should consider; any file not in the map is not visited. This is why
 * the caller must include EXCLUDE and LOCKED entries in the map rather
 * than filtering them out beforehand — the generator needs the total
 * count for progress reporting to match the tree the user is looking
 * at.
 *
 * ### Files are streamed incrementally
 *
 * The writer is fed in bounded chunks and no whole-file buffer is ever
 * materialized. This matters for two reasons:
 *
 *  - The output itself can be tens of megabytes on large repositories,
 *    so building a `String` first would risk OOM on Android.
 *  - The worker can be cancelled between chunks and between files,
 *    which is what allows the export coordinator to interrupt a
 *    running export promptly — even in the middle of a single
 *    multi-megabyte file.
 *
 * ### Read errors vs. write errors
 *
 * The two failure classes have deliberately different handling:
 *
 *  - **Read errors** (one source file cannot be opened, permissions,
 *    transient I/O) are *per-file* for INCLUDE entries: the failure is
 *    written inline as `[File read error: …]` (or `[Git read error:
 *    …]`), [onReadError] is invoked with the offending path, and
 *    generation continues. A single unreadable file must never cost
 *    the rest of the dump. Stubs do not generate read errors: they
 *    never read the file's content, so the only failure mode is a
 *    missing file, which is handled by silent omission.
 *  - **Write errors** (the destination writer throws) and
 *    **cancellation** (the worker thread is interrupted) are *fatal*:
 *    they propagate out of [generateTo] unchanged so the caller can
 *    abort, discard the partial output, and report a real failure.
 *    [InterruptedException] is never caught and re-wrapped as a read
 *    error.
 *
 * ### Total export size limit
 *
 * When [totalLimitBytes] is greater than zero, the generator enforces a
 * hard cap on the accumulated **output** bytes. The cap counts what
 * actually gets written, not what would have been written if every
 * file were included:
 *
 *  - an INCLUDE file that would push the accumulator past the cap is
 *    skipped whole (not written partially — a partial file block would
 *    be worse than no file block, because the model has no way to
 *    know the content was cut mid-stream), and
 *  - a STUB always fits within the cap, because its contribution is a
 *    single short marker (~50 bytes). The check is nevertheless
 *    performed, so a pathological project with thousands of stubs
 *    still respects the cap.
 *
 * Once the cap is exceeded, every subsequent entry is also skipped so
 * the cut point is stable regardless of the traversal order. The final
 * truncation notice records how many files were skipped.
 *
 * The cap is enforced here rather than in the caller because it needs
 * to interact with the generator's own progress reporting and
 * read-error bookkeeping, and because it is applied identically to
 * working-tree and Git modes. A post-write size check in the caller
 * would waste the entire scan/write time on a dump destined to be
 * discarded.
 *
 * ### Source selection
 *
 * When [gitRef] is null or blank the generator walks the working tree.
 * When it is set, the generator uses the resolved Git tree instead, so
 * the dump always reflects the requested branch/tag/commit regardless
 * of what is checked out or modified locally.
 *
 * In both modes the traversal is driven by the same [nodes] map. The
 * map's keys are relative paths; the working-tree and Git paths look
 * them up the same way, which is what allows a single code path to
 * serve both without a mode-specific traversal.
 *
 * @param onProgress optional per-file progress callback; runs on the
 *   worker thread and is never invoked for entries that failed to
 *   read or for entries that were skipped by the size cap. Stubs do
 *   invoke it — they are "processed" from the user's point of view
 *   even though no content was read.
 * @param onReadError optional callback invoked once for every file
 *   whose content could not be read. The path passed here is the same
 *   one written in the inline marker, so callers can aggregate it into
 *   a summary (e.g. the completion notification's "N read errors"
 *   line) without parsing the dump. Cancellation and missing stubs do
 *   not trigger this callback.
 * @param totalLimitBytes hard cap on the total bytes written, 0 meaning
 *   "no limit". See the class-level *Total export size limit* note for
 *   the full rationale.
 */
class CodeDumpGenerator(
    private val root: File,
    private val nodes: Map<String, NodeState>,
    private val gitRef: String? = null,
    private val onProgress: FileProgressListener? = null,
    private val onReadError: ((String) -> Unit)? = null,
    private val totalLimitBytes: Long = 0L
) {
    companion object {
        private const val SEPARATOR = "================================================\n"
        private const val BUFFER_SIZE = 8 * 1024

        /**
         * Estimated length of a stub's marker line, in bytes.
         *
         * Used only to pre-compute [FileProgressListener.totalBytes];
         * the actual marker is formatted with the real file size and
         * can be a few bytes longer or shorter. The estimate is a
         * placeholder that gives the progress bar a non-zero
         * denominator when the export is made entirely of stubs, which
         * would otherwise divide by zero in the fraction calculation.
         */
        private const val STUB_MARKER_ESTIMATED_BYTES = 64L
    }

    /**
     * Writes the complete dump to [writer].
     *
     * The choice between working-tree and Git modes is made here so
     * callers do not have to know which generator path applies to their
     * configuration.
     *
     * @throws IOException when [root] is not a directory or when the
     *   Git reference cannot be resolved. Per-file read failures are
     *   handled internally (see [generateWorkingTree] and
     *   [generateGitSnapshot]) and do not abort the whole export.
     * @throws InterruptedException when the worker thread is
     *   interrupted. This propagates unchanged so the caller can
     *   distinguish a user cancellation from a genuine failure.
     */
    @Throws(IOException::class)
    fun generateTo(writer: Writer) {
        require(root.isDirectory) { "Project root is not a directory: ${root.path}" }
        val selectedRef = gitRef?.trim()?.takeIf(String::isNotEmpty)
        if (selectedRef == null) generateWorkingTree(writer) else generateGitSnapshot(writer, selectedRef)
    }

    /**
     * Working-tree mode: writes the tree header, then every INCLUDE and
     * STUB entry in deterministic depth-first order.
     *
     * Per-file read errors on INCLUDE entries are reported inline as
     * `[File read error: ...]`, forwarded to [onReadError], and do not
     * abort the export — a single unreadable file (permissions,
     * transient I/O) should not lose the rest of the dump. STUB
     * entries never generate a read error: they are written from a
     * single stat call, and a missing file is silently omitted (see
     * [writeStubMarker]).
     *
     * ### Cancellation checkpoints
     *
     * [ensureActive] is called at four distinct phases so that Cancel
     * stays responsive even when the tree walk itself is slow:
     *
     *  - before writing the tree header (the initial header build can
     *    take a moment on very wide directory trees),
     *  - immediately after the file list is materialized,
     *  - before and after the size pass that pre-computes the progress
     *    denominator (touches every file's `length()`, which can block
     *    on network mounts),
     *  - once per file inside the streaming loop.
     *
     * Inside a single file the chunk-level check in
     * [copyNormalizedText] takes over, so a multi-megabyte file never
     * stalls the Cancel button.
     *
     * ### Total size limit
     *
     * When [totalLimitBytes] > 0, each entry's *output contribution* is
     * compared against the remaining budget before its content is
     * written. An INCLUDE file that would overflow the budget is
     * skipped whole; a STUB always fits and is always written. Once
     * the cap is crossed, every subsequent entry is also skipped so
     * the cut point is stable regardless of traversal order.
     *
     * ### Path resolution
     *
     * The order of iteration follows the map's insertion order, which
     * the caller is expected to produce in the same deterministic
     * depth-first order the tree uses. This keeps the dump's layout
     * identical to what the user saw in the tree, which is important
     * for the model's ability to reason about directory structure.
     */
    private fun generateWorkingTree(writer: Writer) {
        ensureActive()

        // The tree header reflects the *included* paths plus a marker
        // for stubs, so the consuming model can see at a glance which
        // entries it has content for and which it only has metadata for.
        val includedPaths = nodes.entries
            .filter { it.value == NodeState.INCLUDE || it.value == NodeState.STUB }
            .map { it.key }
        DirectoryTree(root, /* filter = */ null, stubPaths = stubPathsFromNodes())
            .writePathsTo(writer, includedPaths)
        ensureActive()

        val entries = nodes.entries.toList()
        val total = entries.size
        val totalBytes = entries.sumOf { (path, state) ->
            val file = File(root, path)
            when (state) {
                NodeState.INCLUDE -> file.length()
                NodeState.STUB -> STUB_MARKER_ESTIMATED_BYTES
                NodeState.EXCLUDE, NodeState.LOCKED -> 0L
            }
        }
        ensureActive()

        var processed = 0
        var bytesAccum = 0L
        var truncated = false
        var skippedFiles = 0

        entries.forEach { (path, state) ->
            ensureActive()

            when (state) {
                NodeState.EXCLUDE, NodeState.LOCKED -> {
                    // Not written, but still counted toward `processed`
                    // so the progress figure matches "how many entries
                    // the tree has", not "how many entries this run
                    // happened to include". This is what makes the
                    // progress feel stable across filter changes.
                    processed++
                }

                NodeState.STUB -> {
                    val file = File(root, path)
                    val stubBytes = estimateStubBytes(file)
                    if (totalLimitBytes > 0L && bytesAccum + stubBytes > totalLimitBytes) {
                        truncated = true
                        skippedFiles++
                        processed++
                        return@forEach
                    }
                    writeFileHeader(writer, path)
                    writeStubMarker(writer, file)
                    bytesAccum += stubBytes
                    processed++
                    onProgress?.onProgress(processed, total, path, bytesAccum, totalBytes)
                }

                NodeState.INCLUDE -> {
                    val file = File(root, path)
                    if (totalLimitBytes > 0L && bytesAccum + file.length() > totalLimitBytes) {
                        truncated = true
                        skippedFiles++
                        processed++
                        return@forEach
                    }
                    writeFileHeader(writer, path)
                    try {
                        file.bufferedReader(Charsets.UTF_8, BUFFER_SIZE).use { reader ->
                            copyNormalizedText(reader, writer)
                        }
                    } catch (interrupted: InterruptedException) {
                        // Cancellation must never be mistaken for a read
                        // error. Propagate unchanged so the caller aborts
                        // the whole export.
                        throw interrupted
                    } catch (error: Exception) {
                        onReadError?.invoke(path)
                        writer.write("[File read error: ${error.message ?: error.javaClass.simpleName}]\n")
                    }
                    bytesAccum += file.length()
                    processed++
                    onProgress?.onProgress(processed, total, path, bytesAccum, totalBytes)
                }
            }
        }
        if (truncated) writeTruncationNotice(writer, skippedFiles)
    }

    /**
     * Git mode: opens a single repository session (via
     * [GitUtils.withSnapshot]) and streams the blobs of the requested
     * tree.
     *
     * The tree header is built from the snapshot's file list rather
     * than from the working directory, so the output accurately
     * reflects the state of the requested reference even if the working
     * tree diverged.
     *
     * A blob that cannot be read is reported inline as `[Git read
     * error: <path>]`, forwarded to [onReadError], and the export
     * continues with the remaining files, mirroring the working-tree
     * behaviour. A blob that does not resolve to a size (rare, and
     * usually a shallow/partial clone artefact) is treated as a
     * zero-byte stub: the header and marker are still written so the
     * model knows the file exists, but no content is attempted.
     *
     * ### Cancellation
     *
     * Cancellation propagates out of [GitUtils.withSnapshot] unchanged
     * because [GitUtils.copyNormalizedText] throws
     * [InterruptedException] on an interrupted worker thread.
     *
     * ### Total size limit
     *
     * Same contract as [generateWorkingTree]: each entry's output
     * contribution is compared against the remaining budget before its
     * content is streamed. Stubs always fit.
     *
     * ### Path resolution
     *
     * The iteration uses the [nodes] map's keys to look up the
     * corresponding blob in the snapshot. Missing keys are ignored:
     * this can happen if the snapshot changed between the scan and the
     * export (a force-push of the same branch while the user was
     * reading the diff). The export degrades gracefully rather than
     * aborting, which is the same policy the working-tree path uses
     * for files that disappear between scan and export.
     */
    private fun generateGitSnapshot(writer: Writer, ref: String) {
        GitUtils.withSnapshot(root, ref) { snapshot ->
            ensureActive()

            val includedPaths = nodes.entries
                .filter { it.value == NodeState.INCLUDE || it.value == NodeState.STUB }
                .map { it.key }
            val snapshotFilesByPath = snapshot.listFiles().associateBy { it.path }

            DirectoryTree(root, /* filter = */ null, stubPaths = stubPathsFromNodes())
                .writePathsTo(writer, includedPaths)
            ensureActive()

            val entries = nodes.entries.toList()
            val total = entries.size
            val totalBytes = entries.sumOf { (path, state) ->
                when (state) {
                    NodeState.INCLUDE -> snapshotFilesByPath[path]?.size ?: 0L
                    NodeState.STUB -> STUB_MARKER_ESTIMATED_BYTES
                    NodeState.EXCLUDE, NodeState.LOCKED -> 0L
                }
            }

            var processed = 0
            var bytesAccum = 0L
            var truncated = false
            var skippedFiles = 0

            entries.forEach { (path, state) ->
                ensureActive()

                when (state) {
                    NodeState.EXCLUDE, NodeState.LOCKED -> {
                        processed++
                    }

                    NodeState.STUB -> {
                        val size = snapshotFilesByPath[path]?.size ?: 0L
                        val stubBytes = estimateStubBytesBySize(size)
                        if (totalLimitBytes > 0L && bytesAccum + stubBytes > totalLimitBytes) {
                            truncated = true
                            skippedFiles++
                            processed++
                            return@forEach
                        }
                        writeFileHeader(writer, path)
                        writeStubMarkerBySize(writer, size)
                        bytesAccum += stubBytes
                        processed++
                        onProgress?.onProgress(processed, total, path, bytesAccum, totalBytes)
                    }

                    NodeState.INCLUDE -> {
                        val size = snapshotFilesByPath[path]?.size ?: 0L
                        if (totalLimitBytes > 0L && bytesAccum + size > totalLimitBytes) {
                            truncated = true
                            skippedFiles++
                            processed++
                            return@forEach
                        }
                        writeFileHeader(writer, path)
                        try {
                            if (!snapshot.writeFileContent(path, writer)) {
                                onReadError?.invoke(path)
                                writer.write("[Git read error: ${path}]\n")
                            }
                        } catch (interrupted: InterruptedException) {
                            throw interrupted
                        }
                        bytesAccum += size
                        processed++
                        onProgress?.onProgress(processed, total, path, bytesAccum, totalBytes)
                    }
                }
            }
            if (truncated) writeTruncationNotice(writer, skippedFiles)
        }
    }

    /**
     * Emits the per-file banner that separates consecutive file
     * contents.
     *
     * A leading newline guarantees that the previous file's content
     * cannot run into the separator even when the source file lacked a
     * trailing newline (though [copyNormalizedText] already ensures
     * one).
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
     * Writes the body of a stub: a single marker line explaining that
     * the file exists, its size, and why its content was omitted.
     *
     * The marker format is deliberately chosen for the *consuming
     * model* rather than for the user reading the dump in an editor:
     *
     *  - `content omitted` names the reason explicitly, so the model
     *    does not assume the file's content was empty.
     *  - `N B` gives the size in bytes, which lets the model reason
     *    about relative sizes (a 200-byte stub is likely a config
     *    file; a 5 MB stub is likely a lockfile or generated asset)
     *    even without the content.
     *  - `reference only` names the state, so a reader that does not
     *    understand the size suffix still knows the omission was
     *    intentional.
     *
     * The wording is English-only and bypasses `R.string.*`:
     * `core/` has no `android.*` imports and must stay unit-testable
     * on the JVM, and the marker is part of the generated content that
     * goes to the model, not part of the user-facing UI.
     *
     * A missing file is silently omitted: the header has already been
     * written, and writing a stub for a file that no longer exists
     * would make the dump inconsistent with the tree the user is
     * looking at. The generator has no way to retract the header, so
     * the omission is handled at this level by writing nothing, which
     * leaves the dump with a header and no body — visually ugly, but
     * strictly better than a false marker, and consistent with the
     * class-level read-error policy.
     */
    private fun writeStubMarker(writer: Writer, file: File) {
        if (!file.exists()) {
            // See KDoc: header is already written, but a missing file
            // must not produce a false marker.
            return
        }
        writeStubMarkerBySize(writer, file.length())
    }

    /**
     * Writes the stub marker with an explicit size.
     *
     * Split from [writeStubMarker] so the Git path, which gets the size
     * from the tree blob rather than from a stat call, can share the
     * exact same marker text. Keeping the format in one place is what
     * guarantees that a stub in a working-tree dump and a stub in a
     * Git dump are indistinguishable to the model.
     */
    private fun writeStubMarkerBySize(writer: Writer, sizeBytes: Long) {
        writer.write("[content omitted: ${formatSize(sizeBytes)} — reference only]\n")
    }

    /**
     * Approximates the number of bytes a stub's marker will add to the
     * dump, including the header that precedes it.
     *
     * Used only to pre-compute the progress denominator. The estimate
     * does not need to be exact — the progress bar tolerates a few
     * percent of error and the size shown in the marker is rounded to
     * human-readable units anyway.
     */
    private fun estimateStubBytes(file: File): Long {
        if (!file.exists()) return 0L
        return estimateStubBytesBySize(file.length())
    }

    /**
     * Approximates a stub's output size from the source file size.
     *
     * See [estimateStubBytes].
     */
    private fun estimateStubBytesBySize(sizeBytes: Long): Long {
        // 64 is a conservative lower bound that covers the header
        // banner, the "FILE: <path>\n" line, and a typical marker.
        // Real markers can be slightly longer for very large paths or
        // very large sizes, but the difference is a handful of bytes
        // and does not affect the progress display.
        return STUB_MARKER_ESTIMATED_BYTES + sizeBytes.coerceAtMost(0L)
    }

    /**
     * Appends a truncation marker to the dump when [totalLimitBytes]
     * caused one or more entries to be skipped.
     *
     * The notice is written **inside the generated content** (like the
     * `[File read error: …]` markers) rather than as a user-facing
     * error, so the consuming model can tell why the dump is
     * incomplete. It is deliberately English-only and bypasses
     * `R.string.*`: `core/` has no `android.*` imports and must stay
     * unit-testable on the JVM.
     *
     * [skippedFiles] records how many entries were dropped. This is
     * useful for the reviewer: a single skipped file is probably fine,
     * but a large number suggests the limit should be raised or the
     * filters narrowed.
     */
    private fun writeTruncationNotice(writer: Writer, skippedFiles: Int) {
        writer.write("\n")
        writer.write(SEPARATOR)
        writer.write(
            "[Export truncated: total size limit reached. " +
                "$skippedFiles file(s) not included.]\n"
        )
        writer.write(SEPARATOR)
    }

    /**
     * Returns the subset of [nodes] whose state is
     * [NodeState.STUB], keyed by relative path, for
     * [DirectoryTree.writePathsTo] to render the `[ref]` suffix on
     * stub entries.
     *
     * Materialized once per run rather than rebuilt inside the tree
     * writer so the tree can answer `path in stubPaths` in O(1). The
     * set is small (typically zero to a handful of entries), so even a
     * linear scan would be fine, but the explicit set keeps the tree
     * writer simple.
     */
    private fun stubPathsFromNodes(): Set<String> =
        nodes.entries.filter { it.value == NodeState.STUB }.map { it.key }.toSet()

    /**
     * Cooperative cancellation check performed at every phase boundary
     * — before the tree header, after the initial file list, before
     * and after the byte-count pass, and once per file inside the
     * streaming loop.
     *
     * The worker thread's interrupt flag is set by
     * [com.devdayton.onecodeforai.android.ExportCoordinator.cancel], so
     * this surfaces as a thrown exception that unwinds the export
     * promptly. The per-chunk check inside [copyNormalizedText] covers
     * cancellation that arrives in the middle of a single large file.
     */
    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Dump generation was interrupted.")
        }
    }

    /**
     * Formats a byte count as a compact human-readable string.
     *
     * The exact thresholds and precision are chosen to match the
     * formatting the export coordinator uses in the result panel and
     * notification, so a user comparing the stub marker in the dump to
     * the size in the result panel does not see a mismatch.
     *
     * The unit suffixes (B, KB, MB) are English-only for the same
     * reason the stub marker is: this string is part of the generated
     * content, not part of the UI.
     */
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }

    /**
     * Copies [reader] to [writer] while normalizing line endings to
     * `\n`.
     *
     * Two invariants are guaranteed on output:
     *  - CRLF, LF, and lone CR all become `\n`.
     *  - The result always ends with exactly one newline, so
     *    consecutive file blocks never bleed into each other.
     *
     * The implementation buffers a single character of lookahead to
     * detect a lone `\r` at the end of the stream and emit it as `\n`
     * before closing, rather than dropping it.
     *
     * ### Cancellation
     *
     * The worker thread's interrupt flag is checked **before every
     * read** (roughly every [BUFFER_SIZE] characters). This is what
     * makes Cancel respond within a few kilobytes of the current file
     * instead of having to wait for a potentially multi-megabyte file
     * to finish streaming. The thrown [InterruptedException]
     * propagates unchanged so callers can distinguish it from a
     * normal I/O failure.
     */
    private fun copyNormalizedText(reader: java.io.Reader, writer: Writer) {
        val buffer = CharArray(BUFFER_SIZE)
        var pendingCarriageReturn = false
        var lastWasNewline = false
        while (true) {
            // Check every chunk so that a single large file cannot block
            // the Cancel button. The outer `forEach` only checks between
            // files, which is not enough for files bigger than a few
            // hundred KiB.
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