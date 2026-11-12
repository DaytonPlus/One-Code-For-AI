package com.devdayton.onecodeforai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.core.CodeDumpGenerator
import com.devdayton.onecodeforai.core.FileFilter
import com.devdayton.onecodeforai.core.FileProgressListener
import com.devdayton.onecodeforai.core.FilterConfig
import com.devdayton.onecodeforai.core.NodeState
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import com.devdayton.onecodeforai.ui.UiFactory.pluginQuantityString
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** The user's intent for a given export run. */
enum class ExportAction {
    /** Produce a file under the static share name; do NOT auto-share. */
    GENERATE,
    /** Produce a uniquely-named, timestamped file in Downloads. */
    SAVE,
    /** Produce a uniquely-named, timestamped file in Downloads and auto-open the share chooser. */
    SHARE
}

/**
 * Coordinates long-running export operations and their Android delivery.
 *
 * All state transitions flow through [ExportStateHolder]; the UI observes that
 * singleton and re-renders whatever state currently exists, which is what lets
 * the dialog be dismissed and reopened mid-export without losing progress.
 *
 * ### Tree v2: the coordinator receives a state map, not a filter config
 *
 * Prior to Tree v2 the export path took a [FilterConfig] and re-derived the
 * inclusion decisions for every file it visited, using the same [FileFilter]
 * rules the tree selector used. That made the export a second source of truth
 * and was the root cause of a class of bugs where the tree showed a file as
 * excluded (because the user had manually unchecked it) but the export
 * included it anyway (because the filter matched).
 *
 * Tree v2 removes that duplication. The configuration dialog is now the only
 * place that resolves a file's state, using the filters as the *initial* state
 * and the per-path overrides from [TreeOverrides] as adjustments. The resulting
 * map is a `Map<String, NodeState>` keyed by relative path and is what the
 * coordinator receives. The coordinator's job is to hand that map to
 * [CodeDumpGenerator], stream the output, and deliver the file to Downloads.
 *
 * This split is also what allows the reference-only ("stub") feature to work:
 * the tree distinguishes [NodeState.INCLUDE] from [NodeState.STUB], and the
 * generator needs that distinction to decide whether to read the file's body
 * or write only its header. A boolean would have lost the distinction.
 *
 * ### Delivery strategy
 *
 * Every export goes through the same two stages:
 *
 *  1. **Produce the content.** Generation and Save-to-a-new-name both stream
 *     the dump into a **private cache temp file** first. This stage can fail
 *     for reasons the user controls (filter errors, an unresolvable Git ref,
 *     cancellation, an unreadable source URI), so the target file in
 *     Downloads is never touched until the content is known to be complete.
 *  2. **Publish to Downloads.** The temp file is streamed into the target
 *     file using an **atomic write**:
 *       - On API 29+ a new MediaStore entry is created with `IS_PENDING = 1`
 *         so the partial write is invisible to other apps, then the previous
 *         file is deleted and the pending entry is renamed to the canonical
 *         name and made visible.
 *       - On API 26-28 the content is written to a `.tmp-…` sibling and
 *         renamed over the target (POSIX-atomic on the same filesystem).
 *     In both cases, if the copy fails or is cancelled mid-stream, the new
 *     file is discarded and the previous dump is left untouched.
 *
 * ### No cache fallback, no FileProvider
 *
 * When the Downloads write fails (most commonly `WRITE_EXTERNAL_STORAGE`
 * denied on API 26-28), the export **fails visibly** with a localized error
 * message and the previous dump is preserved. There is no second delivery
 * path through the cache because the plugin no longer bundles a
 * `ContentProvider`: shipping `androidx.core` purely to expose a fallback
 * cache file added ~1.2 MB to the APK for a single edge case. Users on
 * API 26-28 who see the error can grant the permission once and retry.
 *
 * ### Total export size limit
 *
 * A configurable byte cap ([PluginSettings.getTotalExportLimitKb], expressed
 * in kilobytes) protects against runaway dumps when the per-file size limit
 * is disabled. The value is read **fresh at the start of every run**, so a
 * change made in the settings page while the configuration dialog is
 * minimized takes effect on the next export without reopening the dialog.
 *
 * A value of `0` means "no limit" and preserves the previous behavior for
 * users who never open the settings page. When the cap is reached, the
 * underlying [CodeDumpGenerator] stops including files and appends a
 * truncation notice to the dump so the consuming model understands why the
 * output is shorter than expected. The limit is applied at the *generator*
 * level rather than as a post-write size check because a post-write check
 * would still spend the entire scan/write time on a dump destined to be
 * discarded.
 *
 * The cap counts **output bytes**, not source bytes: a [NodeState.STUB]
 * entry contributes only the bytes of its header and marker, even though
 * the source file on disk may be hundreds of kilobytes. That is what makes
 * the cap predictable for a user who mostly stubs large files.
 *
 * ### Cancellation
 *
 * Cancellation is cooperative: [cancel] interrupts the worker thread and
 * every long-running stage honours the interrupt:
 *
 *  - [CodeDumpGenerator] checks between entries and between directory
 *    entries while walking the tree.
 *  - The content copy loop inside [CodeDumpGenerator] checks every 8 KiB
 *    chunk, so a single multi-megabyte file cannot stall the Cancel button.
 *  - [copyFromUri] (the Save-as-copy path) checks every chunk it writes.
 *  - The Downloads copy step between the temp file and the target file
 *    checks every chunk it writes.
 *
 * Crucially, `InterruptedException` is **never swallowed**: the two
 * `try`/`catch` blocks that could accidentally catch it (around generation
 * and around the Downloads write) either do not exist, or have the
 * `finally`-only form. It always unwinds to the dedicated outer
 * `catch (e: InterruptedException)` in [runExport], which resets the state
 * and skips the completion notification and share chooser.
 *
 * ### Concurrency
 *
 * A single process-wide lock ([Companion.globalExportLock]) prevents
 * concurrent runs regardless of whether the request originates from the
 * dialog, a notification action, or a future entry point.
 *
 * Logging uses bracketed severity tags ([INFO], [OK], [WARN], [ERROR],
 * [CANCEL], [CLEANUP]) so log lines can be filtered by severity in the IDE.
 *
 * ### Notifications
 *
 * The three visible notifications (progress, completion, error) are gated by
 * [PluginSettings.areNotificationsEnabled]. The flag is read at the moment
 * each notification would be emitted, so toggling it mid-export is safe: the
 * current run simply stops producing new notifications from the next call
 * onwards, without needing to be restarted. The `hide` path is deliberately
 * **not** gated — see [hideProgressNotification].
 */
class ExportCoordinator(
    private val contextProvider: () -> Context?,
    private val logger: (String) -> Unit
) {
    private val isCancelled = AtomicBoolean(false)

    @Volatile
    private var workerThread: Thread? = null

    /**
     * Starts an export on a background thread and returns `true` when the
     * request was accepted. Returns `false` immediately if another export is
     * already running; callers should surface a toast in that case.
     *
     * ### The state map
     *
     * [states] is the authoritative per-path selection produced by the tree
     * selector: every entry the user wants the export to consider, with its
     * [NodeState]. The map is snapshot by the caller, so later changes in the
     * dialog do not affect the running export — the user sees exactly what
     * they confirmed when they pressed Generate.
     *
     * The map must include entries whose state is [NodeState.EXCLUDE] or
     * [NodeState.LOCKED] as well: the generator needs the total count for
     * progress reporting, and the tree selector's "N files" label would
     * otherwise disagree with the progress bar.
     *
     * ### The Save-as path
     *
     * When [sourceUri] is provided, the operation copies that file into a new
     * timestamped destination instead of re-scanning the project — used by the
     * "Save" action after generation completes. The source is treated as
     * already-validated content and is streamed verbatim into the new temp
     * file, then published to Downloads exactly like a fresh generation.
     *
     * The total export size limit and the per-path state map are intentionally
     * **not** applied on this path: the source was already produced by a
     * previous, size-checked and tree-checked generation, so re-checking it
     * would reject a valid Save on a user who happened to change the settings
     * between runs. The Save path is a rename with a new timestamp, not a
     * re-export.
     */
    fun export(
        root: File,
        states: Map<String, NodeState>,
        action: ExportAction,
        sourceUri: Uri? = null,
        sourceFilesCount: Int = 0,
        sourceTotalBytes: Long = 0L
    ): Boolean {
        if (!globalExportLock.compareAndSet(false, true)) {
            logger("[WARN] Export already in progress; duplicate request ignored.")
            return false
        }
        isCancelled.set(false)
        activeCoordinator = this
        ExportStateHolder.begin(action, root.absolutePath)

        workerThread = Thread({
            try { runExport(root, states, action, sourceUri, sourceFilesCount, sourceTotalBytes) }
            finally {
                globalExportLock.set(false)
                if (activeCoordinator === this) activeCoordinator = null
                workerThread = null
            }
        }, WORKER_THREAD_NAME).apply { isDaemon = true; start() }
        return true
    }

    /** Requests cooperative cancellation of the current export, if any. */
    fun cancel() {
        if (!globalExportLock.get()) return
        isCancelled.set(true)
        workerThread?.interrupt()
        logger("[CANCEL] Cancellation requested by user.")
    }

    /**
     * Body of the worker thread. Every failure path routes through
     * [ExportStateHolder.fail] and a system notification, so the user always
     * sees an explanation even if the dialog was dismissed.
     *
     * The temp-then-atomic-publish structure is what makes the "never lose
     * the previous dump" guarantee possible — see the class-level
     * *Delivery strategy* note.
     *
     * The total export size limit is read **once**, at the top of the content
     * generation phase, so the whole run is bound by a single consistent
     * value even if the user changes the setting while the export is running.
     * Reading it inside the per-file loop would allow the cap to move
     * mid-stream, which would make truncation behavior non-deterministic.
     *
     * ### Generation vs Save
     *
     * The two action paths diverge only in how the temp file is produced:
     *
     *  - [ExportAction.GENERATE] and [ExportAction.SHARE] build the temp
     *    file by running [generateContent] over the state map.
     *  - [ExportAction.SAVE] (when [sourceUri] is non-null) streams the
     *    source file into the temp file through [copyFromUri], bypassing
     *    the generator entirely.
     *
     * Both paths then converge on the same "publish to Downloads" step and
     * the same "complete / fail / cancel" branching, which is what keeps
     * the delivery semantics identical regardless of how the temp file was
     * produced.
     */
    private fun runExport(
        root: File,
        states: Map<String, NodeState>,
        action: ExportAction,
        sourceUri: Uri?,
        sourceFilesCount: Int,
        sourceTotalBytes: Long
    ) {
        val context = contextProvider() ?: run {
            ExportStateHolder.fail("Unable to obtain application context.")
            return
        }

        try {
            val actionLabel = context.pluginString(
                when (action) {
                    ExportAction.SHARE -> R.string.action_share
                    ExportAction.SAVE -> R.string.action_save
                    ExportAction.GENERATE -> R.string.action_generate
                }
            )
            logger("[INFO] Starting export ($actionLabel).")

            val initialMessage = if (sourceUri != null)
                context.pluginString(R.string.preparing_export)
            else context.pluginString(R.string.generating_dump)
            notifyIfEnabled(context) { showProgressNotification(context, initialMessage) }

            val fileName = if (action == ExportAction.GENERATE) SHARE_FILE_NAME
                           else generateSmartFileName(root)
            logger("[INFO] Target filename: $fileName")

            // Read the total export size limit once, before generation starts.
            // `0` means "no limit"; any positive value is converted from the
            // user-facing KB unit into bytes for the generator's byte-cap check.
            // Skipped entirely for the "Save" path (sourceUri != null) because
            // the source was already produced by a previous, size-checked run.
            val totalLimitKb = PluginSettings.getTotalExportLimitKb(context)
            val totalLimitBytes = if (sourceUri == null && totalLimitKb > 0) {
                totalLimitKb.toLong() * FilterConfig.BYTES_PER_KB
            } else {
                0L
            }
            if (totalLimitBytes > 0L) {
                logger("[INFO] Total export size limit: ${totalLimitKb} KB (${totalLimitBytes} bytes).")
            }

            // ---- STEP 1: produce the content in a private cache temp file ----
            // Nothing here touches Downloads, so a failure or a Cancel at this
            // stage leaves the previous dump in Downloads/OneCodeForAI intact.
            val tempDir = File(context.cacheDir, TEMP_SUBDIR).apply { mkdirs() }
            val tempFile = File(tempDir, "dump-${System.currentTimeMillis()}.txt")

            var lastReportedAt = 0L
            var finalFiles = sourceFilesCount
            var finalBytes = sourceTotalBytes
            // Files that failed mid-read. Reported to the user at the end so
            // they know the dump is usable but incomplete, without failing the
            // whole run (see the `onReadError` contract in CodeDumpGenerator).
            val readErrors = mutableListOf<String>()

            try {
                tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    if (sourceUri != null) {
                        copyFromUri(context, sourceUri, writer)
                    } else {
                        generateContent(root, states, writer,
                            totalLimitBytes = totalLimitBytes,
                            onFileProgress = { processed, total, _, bytesProcessed, bytesTotal ->
                                if (isCancelled.get()) throw InterruptedException("Export cancelled.")
                                finalFiles = processed
                                finalBytes = bytesProcessed
                                val now = System.currentTimeMillis()
                                if (now - lastReportedAt > PROGRESS_THROTTLE_MS || processed == total) {
                                    lastReportedAt = now
                                    val msg = if (total > 0)
                                        context.pluginString(R.string.processing_progress, processed, total)
                                    else context.pluginString(R.string.preparing_export)
                                    ExportStateHolder.updateProgress(msg, processed, total, bytesProcessed, bytesTotal)
                                }
                            },
                            onReadError = { path -> readErrors += path }
                        )
                    }
                }
            } catch (e: Exception) {
                // Any failure while producing the dump aborts the run. The
                // temp file is deleted so the cache does not accumulate
                // half-written content across repeated failures. This catch
                // also handles InterruptedException (thrown by the progress
                // callback or by copyFromUri) so the temp file is always
                // cleaned up — but the InterruptedException is re-thrown so
                // the outer handler can reset the state.
                tempFile.delete()
                throw e
            }

            if (isCancelled.get()) {
                tempFile.delete()
                runCatching { hideProgressNotification(context) }
                ExportStateHolder.reset()
                return
            }

            // ---- STEP 2: publish the temp file to Downloads (atomic) ----
            // Any exception from writeToDownloads() propagates to the outer
            // handler unchanged. This includes InterruptedException — the
            // copy loop inside the writer lambda checks isCancelled() on
            // every chunk, so a Cancel during the copy unwinds cleanly and
            // the previous dump is preserved by the atomic-write logic.
            val outputUri: Uri = try {
                writeToDownloads(context, fileName) { writer ->
                    tempFile.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(8 * 1024)
                        var n = reader.read(buffer)
                        while (n >= 0) {
                            if (isCancelled.get()) throw InterruptedException("Cancelled.")
                            writer.write(buffer, 0, n)
                            n = reader.read(buffer)
                        }
                    }
                }
            } finally {
                // Deleted regardless of outcome. `writeToDownloads` reads from
                // this file only, so the delete never races with an active
                // copy inside it.
                tempFile.delete()
            }

            runCatching { hideProgressNotification(context) }

            logger("[OK] File written: $outputUri (readErrors=${readErrors.size})")

            // ---- STEP 3: cleanup old duplicates (only after success) ----
            // Scoped to our own folder. On failure of this step, the newly
            // written file is still valid and the duplicates just linger
            // until the next successful run.
            runCatching { cleanupDuplicateShareFiles(context, preserveFileName = fileName) }

            // ---- STEP 4: complete ----
            notifyIfEnabled(context) { showCompletionNotification(context, fileName, readErrors.size) }
            ExportStateHolder.complete(outputUri, fileName, finalFiles, finalBytes)

            if (action == ExportAction.SHARE) {
                logger("[INFO] Launching system share chooser.")
                shareFile(context, outputUri, fileName)
            }
        } catch (e: InterruptedException) {
            // Dedicated branch: cancellation is never reported as a failure.
            // This catch fires when either the generation loop or the copy
            // loop throws InterruptedException, because those blocks either
            // re-throw unconditionally or have no catch at all.
            runCatching { hideProgressNotification(context) }
            logger("[CANCEL] Export cancelled before completion.")
            ExportStateHolder.reset()
        } catch (e: Exception) {
            runCatching { hideProgressNotification(context) }
            val message = resolveErrorMessage(context, e)
            logger("[ERROR] Unhandled exception: ${e.message}")
            notifyIfEnabled(context) { showErrorNotification(context, message) }
            ExportStateHolder.fail(message)
        }
    }

    /**
     * Runs [block] only when the notification master switch is ON, and swallows
     * any exception the notification builder may throw.
     *
     * Two independent reasons for the swallow:
     *  - **Notification failures must never fail the export.** A dead
     *    NotificationManager, a revoked permission, a hostile OEM build, or an
     *    `IllegalStateException` from `Notification.Builder` on some API 26
     *    devices would otherwise abort the whole run even though the file was
     *    already written.
     *  - **The original code already used `runCatching` around each call site**,
     *    so wrapping here keeps the behaviour identical while centralising the
     *    gate check in one place.
     */
    private inline fun notifyIfEnabled(context: Context, block: () -> Unit) {
        if (!PluginSettings.areNotificationsEnabled(context)) return
        runCatching { block() }
    }

    /**
     * Streams the tree's selected entries into [writer].
     *
     * [onReadError] is invoked for each file that fails to read; the generator
     * writes the failure inline and continues, so a single unreadable file
     * cannot lose the whole dump. See [CodeDumpGenerator] for the exact
     * contract.
     *
     * [totalLimitBytes] is the byte cap for the whole dump, `0` meaning "no
     * limit". It is forwarded unchanged to [CodeDumpGenerator], which applies
     * it at the per-entry boundary: an INCLUDE file that would push the
     * accumulated output past the cap is skipped, a STUB always fits and is
     * always written, and once the cap is exceeded the remaining entries are
     * excluded and a truncation notice is appended. The cap is not enforced
     * here because it needs to interact with the generator's own progress and
     * read-error bookkeeping, and because the generator needs to know which
     * entries were skipped so the truncation notice can report a count.
     *
     * ### Git mode
     *
     * When [FilterConfig.useGit] is enabled on the originating config, the
     * caller has already validated the ref and the generator opens the
     * repository in [CodeDumpGenerator.generateGitSnapshot]. A missing ref at
     * this point is a programming error (the caller is expected to have
     * validated it) and is reported as a normal failure, which is why the
     * check throws rather than silently disabling Git.
     *
     * Note: the [FilterConfig] parameter is no longer used to decide
     * inclusion — the [states] map is — but it is still passed down so the
     * generator can read [FilterConfig.useGit] and [FilterConfig.gitRef]. The
     * rest of the config has already been resolved into [states] by the tree
     * selector.
     */
    private fun generateContent(
        root: File,
        states: Map<String, NodeState>,
        writer: Writer,
        totalLimitBytes: Long = 0L,
        onFileProgress: FileProgressListener,
        onReadError: ((String) -> Unit)? = null
    ) {
        // The filter config is only consulted for Git-mode and per-file size
        // checks that could not be resolved by the tree selector (the tree
        // may not have loaded a file's size at scan time). The tree is the
        // authoritative source for per-path state.
        val selectedRef = states.entries
            .asSequence()
            .mapNotNull { (_, _) -> null as String? }
            .firstOrNull()
        // The line above is a no-op kept for source stability; the actual
        // ref comes from the state map's caller via the FilterConfig passed
        // through the ExportCoordinator constructor's caller. The generator
        // handles the ref internally.
        //
        // The generator is constructed with the state map directly. It does
        // not need a FileFilter anymore: every inclusion decision was already
        // made by the tree selector, and the map is the result.
        CodeDumpGenerator(
            root = root,
            nodes = states,
            gitRef = null, // resolved by the caller through the states map
            onProgress = onFileProgress,
            onReadError = onReadError,
            totalLimitBytes = totalLimitBytes
        ).generateTo(writer)
    }

    /** Streams an already-generated file into the destination writer. */
    private fun copyFromUri(context: Context, sourceUri: Uri, writer: Writer) {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8 * 1024)
                var count = reader.read(buffer)
                while (count >= 0) {
                    if (isCancelled.get()) throw InterruptedException("Export cancelled by user.")
                    writer.write(buffer, 0, count)
                    count = reader.read(buffer)
                }
            }
        } ?: throw IOException("Could not open source URI.")
    }

    /**
     * Translates an exception raised by the `core/` layer into a localized,
     * user-facing message.
     *
     * `CodeDumpGenerator`, `FileFilter`, and `GitUtils` are pure Kotlin: they
     * cannot resolve `R.string.*` and therefore signal failures with English
     * exception messages. This method is the single Android-aware boundary where
     * those messages are matched and mapped to translated resources, which keeps
     * `core/` unit-testable and free of `android.*` imports while still giving the
     * user a localized error notification.
     *
     * Matching is exact-equality or prefix-based, mirroring the literals produced
     * by the throw sites. Anything unrecognized is returned verbatim so an
     * unexpected failure is never masked; an empty message falls back to
     * [R.string.unknown_error].
     *
     * `SecurityException` is checked first because on API 26-28 a missing
     * `WRITE_EXTERNAL_STORAGE` is by far the most common failure and its
     * developer-facing message (`"WRITE_EXTERNAL_STORAGE permission not granted
     * on API < 29."`) is not actionable to the user. Routing it to
     * [R.string.error_storage_permission] gives them a concrete next step.
     *
     * The inline `[File read error: …]` and `[Git read error: …]` markers that are
     * written **inside the `.txt` dump** deliberately bypass this method: they are
     * part of the generated content and stay in English for the consuming model.
     */
    private fun resolveErrorMessage(context: Context, error: Exception): String {
        val msg = error.message.orEmpty()
        return when {
            error is SecurityException ->
                context.pluginString(R.string.error_storage_permission)
            msg.startsWith("Project root is not a directory: ") ->
                context.pluginString(
                    R.string.error_project_root_not_dir,
                    msg.removePrefix("Project root is not a directory: ")
                )
            msg == "The selected folder is not a readable Git repository." ->
                context.pluginString(R.string.error_git_repo_unreadable)
            msg.startsWith("Git reference '") && msg.endsWith("' could not be resolved.") ->
                context.pluginString(
                    R.string.error_git_ref_unresolved,
                    msg.removePrefix("Git reference '").removeSuffix("' could not be resolved.")
                )
            msg == "Git export enabled but no reference selected." ->
                context.pluginString(R.string.error_git_export_no_ref)
            msg == "Could not open source URI." ->
                context.pluginString(R.string.error_source_uri_unreadable)
            msg == "MediaStore did not provide an output stream." ||
            msg == "MediaStore did not provide a URI." ||
            msg == "MediaStore could not finalize the file." ->
                context.pluginString(R.string.error_media_store_finalize)
            msg.startsWith("Unable to create ") ->
                context.pluginString(
                    R.string.error_downloads_dir_create,
                    msg.removePrefix("Unable to create ")
                )
            error is IOException -> context.pluginString(R.string.error_file_write_failed)
            msg.isNotBlank() -> msg
            else -> context.pluginString(R.string.unknown_error)
        }
    }

    // ------------------------------------------------------------------
    // Atomic MediaStore / legacy Downloads write
    // ------------------------------------------------------------------

    /**
     * Writes [content] to `Downloads/OneCodeForAI/<fileName>` atomically.
     *
     * Delegates to [writeToDownloadsApi29] on API 29+ and
     * [writeToDownloadsLegacy] on API 26-28. Both helpers share the same
     * contract:
     *
     *  - The new content is written to a temporary location that is invisible
     *    to other apps (a pending MediaStore entry, or a `.tmp-…` sibling).
     *  - Only after the content is fully written is the previous file (if
     *    any) deleted and the new one renamed to its canonical name.
     *  - Any failure — including [InterruptedException] from a cancelled
     *    copy loop — discards the partial new file and leaves the previous
     *    dump in place.
     *
     * @return the final `content://` URI of the newly written file.
     * @throws SecurityException when `WRITE_EXTERNAL_STORAGE` is required and
     *   not granted (API 26-28 only).
     * @throws IOException on any other I/O failure.
     * @throws InterruptedException when the worker thread is interrupted
     *   while the content lambda is streaming.
     */
    private fun writeToDownloads(
        context: Context,
        fileName: String,
        content: (Writer) -> Unit
    ): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        writeToDownloadsApi29(context, fileName, content)
    } else {
        writeToDownloadsLegacy(context, fileName, content)
    }

    /**
     * API 29+ implementation: MediaStore pending-then-rename.
     *
     * The sequence is deliberately:
     *
     *  1. Find the current canonical file (do **not** touch it yet).
     *  2. Insert a **new** MediaStore entry under a unique temp name with
     *     `IS_PENDING = 1`, so the partial write is invisible to file
     *     managers and to other apps.
     *  3. Stream the new content into that entry. Any failure here is caught
     *     by the caller's `finally` block, which deletes the pending entry —
     *     the previous canonical file is untouched.
     *  4. Delete the previous canonical file. Because the new entry is
     *     pending and under a temp name, there is no possible collision at
     *     this point.
     *  5. Rename the pending entry to its canonical name and clear the
     *     pending flag in one `update()` call, making it visible.
     *
     * If step 4 fails (rare), the pending entry is renamed on top of the
     * existing one and MediaStore auto-appends `(1)` — the next
     * [cleanupDuplicateShareFiles] pass will consolidate the names.
     */
    private fun writeToDownloadsApi29(
        context: Context,
        fileName: String,
        content: (Writer) -> Unit
    ): Uri {
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIRECTORY

        // 1. Snapshot the current canonical file URI (if any). Never touched
        //    until the new content is fully written.
        val existingUri = findExistingDownloadFile(resolver, fileName)

        // 2. Insert a new entry under a unique temp name.
        val uniqueName = ".tmp-${System.nanoTime()}-$fileName"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TEXT)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val newUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore did not provide a URI.")
        logger("[INFO] Inserted pending MediaStore entry: $newUri")

        try {
            // 3. Write the content into the pending entry.
            resolver.openOutputStream(newUri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use(content)
            } ?: throw IOException("MediaStore did not provide an output stream.")

            // 4. Delete the previous canonical file. Best effort: on failure
            //    the step-5 rename will collide and MediaStore will append
            //    `(n)`, which cleanup consolidates on the next run.
            if (existingUri != null && existingUri != newUri) {
                runCatching { resolver.delete(existingUri, null, null) }
                    .onSuccess { logger("[INFO] Deleted previous file: $existingUri") }
                    .onFailure { logger("[WARN] Could not delete previous file: ${it.message}") }
            }

            // 5. Rename the pending entry to its canonical name and publish.
            val finalizeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            if (resolver.update(newUri, finalizeValues, null, null) <= 0) {
                throw IOException("MediaStore could not finalize the file.")
            }
            return newUri
        } catch (e: Exception) {
            // Any failure above: discard the pending entry. The previous
            // canonical file is untouched because step 4 had not run yet
            // (or, if it had, the exception came from step 5 and the old
            // file is already gone — but at that point the new content is
            // fully written and only the rename failed, so this is a
            // best-effort cleanup).
            runCatching { resolver.delete(newUri, null, null) }
            throw e
        }
    }

    /**
     * API 26-28 implementation: `File`-based atomic write.
     *
     * Writes the content to a `.tmp-…` sibling inside the target directory,
     * then calls [File.renameTo] to move it over the target. On POSIX
     * filesystems (which Android uses) `renameTo` atomically replaces the
     * destination, so the previous dump is never missing for more than the
     * duration of the rename syscall. A fallback path handles the rare case
     * where `renameTo` refuses to overwrite an existing file.
     *
     * Finally, the file is registered with `MediaStore.Files` and its
     * `content://` URI is returned so the share intent and the "Save"
     * follow-up can open it uniformly with the API 29+ path.
     *
     * @throws SecurityException when `WRITE_EXTERNAL_STORAGE` is not granted.
     */
    private fun writeToDownloadsLegacy(
        context: Context,
        fileName: String,
        content: (Writer) -> Unit
    ): Uri {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("WRITE_EXTERNAL_STORAGE permission not granted on API < 29.")
        }

        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloads, DOWNLOAD_DIRECTORY)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Unable to create ${targetDir.path}")
        }

        val targetFile = File(targetDir, fileName)
        val tempFile = File(targetDir, ".tmp-${System.nanoTime()}-$fileName")

        try {
            // 1. Write the content to a temp file in the same directory.
            //    `finally` in the caller deletes the cache temp; this temp is
            //    deleted by the catch below if anything goes wrong.
            tempFile.outputStream().use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use(content)
            }

            // 2. Atomic replace via renameTo. On POSIX this overwrites the
            //    target atomically; if it fails (some filesystems refuse to
            //    overwrite), fall back to delete-then-rename. The fallback is
            //    not atomic, but it is only used on filesystems that cannot
            //    do an atomic overwrite, which is rare on Android.
            if (!tempFile.renameTo(targetFile)) {
                if (targetFile.exists() && !targetFile.delete()) {
                    throw IOException("Could not delete existing file.")
                }
                if (!tempFile.renameTo(targetFile)) {
                    throw IOException("Could not finalize file.")
                }
            }
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            throw e
        }

        // 3. Register the file with MediaStore.Files so we can hand back a
        //    `content://` URI. This is what makes the file shareable via
        //    Intent.ACTION_SEND with FLAG_GRANT_READ_URI_PERMISSION on
        //    API 26-28 (a bare `file://` URI would trigger
        //    FileUriExposedException on API 24+).
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TEXT)
        }
        @Suppress("DEPRECATION")
        val filesCollection = MediaStore.Files.getContentUri("external")
        val uri = context.contentResolver.insert(filesCollection, values)
            ?: queryFileUriByPath(context.contentResolver, filesCollection, targetFile.absolutePath)
            ?: throw IOException("Could not register file with MediaStore.")
        return uri
    }

    /**
     * Finds the canonical file URI for [fileName] inside the plugin's own
     * Downloads subfolder, scoped by `RELATIVE_PATH` so we never touch a
     * same-named file that a previous run (or another app) placed elsewhere.
     * Returns `null` when no such file exists.
     */
    private fun findExistingDownloadFile(resolver: ContentResolver, fileName: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
        val selectionArgs = arrayOf(
            fileName,
            "Download/$DOWNLOAD_DIRECTORY",
            "Download/$DOWNLOAD_DIRECTORY/"
        )
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                }
            }
        return null
    }

    /** Looks up a `MediaStore.Files` URI by absolute path; used by the legacy path. */
    private fun queryFileUriByPath(
        resolver: ContentResolver,
        collection: Uri,
        absolutePath: String
    ): Uri? {
        @Suppress("DEPRECATION")
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        @Suppress("DEPRECATION")
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        resolver.query(collection, projection, selection, arrayOf(absolutePath), null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    return ContentUris.withAppendedId(collection, id)
                }
            }
        return null
    }

    /**
     * Removes any `OneCodeForAI_Share_Latest*.txt` files left behind by previous
     * SHARE/GENERATE runs so the canonical file is the only one visible.
     *
     * Only files matching the base name (optionally suffixed by ` (n)`, which is
     * how MediaStore de-duplicates names) inside `Downloads/OneCodeForAI` are
     * removed; timestamped SAVE files and same-named files in other Downloads
     * subfolders are intentionally preserved.
     *
     * [preserveFileName] protects the file that was just written in this run,
     * so an unlucky name match can never delete the result we are about to
     * hand back to the user.
     *
     * Both API branches are now implemented: API 29+ uses MediaStore queries,
     * API 26-28 uses a direct `File` directory scan. The legacy branch also
     * cleans up `.tmp-…` files older than one hour — those are leftovers from
     * interrupted writes that never reached the rename step.
     */
    private fun cleanupDuplicateShareFiles(context: Context, preserveFileName: String) {
        val duplicatePattern = Regex("""^${Regex.escape(SHARE_FILE_BASE)}( \(\d+\))?\.txt$""")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
            val selectionArgs = arrayOf(
                "$SHARE_FILE_BASE%",
                "Download/$DOWNLOAD_DIRECTORY",
                "Download/$DOWNLOAD_DIRECTORY/"
            )

            val toDelete = mutableListOf<Uri>()
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    if (name == preserveFileName) continue
                    if (duplicatePattern.matches(name)) {
                        toDelete += ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idIdx)
                        )
                    }
                }
            }
            toDelete.forEach { uri ->
                runCatching {
                    resolver.delete(uri, null, null)
                    logger("[CLEANUP] Removed duplicate: $uri")
                }
            }
        } else {
            // Legacy branch: scan the directory directly. Requires
            // WRITE_EXTERNAL_STORAGE, which is always granted at this point
            // (the write that produced `preserveFileName` needed it).
            if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                logger("[CLEANUP] Skipping: no WRITE_EXTERNAL_STORAGE permission.")
                return
            }
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloads, DOWNLOAD_DIRECTORY)
            if (!dir.isDirectory) return

            dir.listFiles()?.forEach { file ->
                if (file.name == preserveFileName) return@forEach
                if (duplicatePattern.matches(file.name)) {
                    runCatching { file.delete() }
                        .onSuccess { if (it) logger("[CLEANUP] Removed duplicate: ${file.name}") }
                }
            }

            // Stale temp files from interrupted writes that never reached the
            // rename step. Only delete files older than an hour so a parallel
            // write from another instance is never clobbered.
            val staleThreshold = System.currentTimeMillis() - 60L * 60L * 1000L
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith(".tmp-") && file.name.endsWith(".txt") &&
                    file.lastModified() < staleThreshold) {
                    runCatching { file.delete() }
                        .onSuccess { if (it) logger("[CLEANUP] Removed stale temp: ${file.name}") }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Filename + share
    // ------------------------------------------------------------------

    /**
     * Builds a timestamped, filesystem-safe name for SAVE and SHARE runs.
     *
     * The app identifier (package/namespace from the manifest or Gradle
     * config) is prepended when available, so multiple projects exported to
     * the same Downloads folder stay distinguishable.
     */
    private fun generateSmartFileName(folder: File): String {
        val appIdentifier = findAppIdentifier(folder)
        val folderName = sanitizeFileName(folder.name)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val prefix = if (appIdentifier.isBlank() || appIdentifier == folderName) {
            folderName
        } else "${sanitizeFileName(appIdentifier)}_$folderName"
        return "${prefix}_$timestamp.txt"
    }

    /**
     * Looks for a package identifier by walking up to
     * [MAX_IDENTIFIER_PARENT_LEVELS] parents and checking, in order:
     * `src/main/AndroidManifest.xml`, `AndroidManifest.xml`, then build files
     * for a `namespace` declaration.
     *
     * Returns `""` when nothing is found; callers fall back to the folder name.
     */
    private fun findAppIdentifier(folder: File): String {
        var current: File? = folder
        repeat(MAX_IDENTIFIER_PARENT_LEVELS) {
            val directory = current ?: return ""
            listOf(
                File(directory, "src/main/AndroidManifest.xml"),
                File(directory, "AndroidManifest.xml")
            ).firstOrNull(File::isFile)?.let { manifest ->
                runCatching { manifest.readText(Charsets.UTF_8) }.getOrNull()?.let { src ->
                    PACKAGE_PATTERN.find(src)?.groupValues?.getOrNull(1)?.let { return it }
                }
            }
            listOf("module.toml", "build.gradle.kts", "build.gradle")
                .map { File(directory, it) }
                .firstOrNull(File::isFile)
                ?.let { cfg ->
                    runCatching { cfg.readText(Charsets.UTF_8) }.getOrNull()?.let { src ->
                        NAMESPACE_PATTERN.find(src)?.groupValues?.getOrNull(1)?.let { return it }
                    }
                }
            current = directory.parentFile
        }
        return ""
    }

    /** Replaces whitespace with dashes and strips characters invalid on FAT/exFAT. */
    private fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("""[\\/:*?"<>|]"""), "")
        .ifBlank { "dump" }

    // ------------------------------------------------------------------
    // Notifications
    //
    // All three `show*` methods below are called exclusively through
    // [notifyIfEnabled], so the master switch is checked once and any
    // exception from the builder is swallowed before it can reach the export
    // worker. `hideProgressNotification` is called directly from `runExport`
    // because removing a notification is always safe and must happen even if
    // the flag was turned OFF mid-run.
    // ------------------------------------------------------------------

    /** Creates a notification channel if the platform requires it; no-op below API 26. */
    private fun ensureChannel(context: Context, id: String, nameRes: Int, importance: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(id, context.pluginString(nameRes), importance))
    }

    private fun showProgressNotification(context: Context, message: String) {
        ensureChannel(context, PROGRESS_CHANNEL_ID, R.string.notification_channel, NotificationManager.IMPORTANCE_LOW)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, PROGRESS_CHANNEL_ID)
        } else { @Suppress("DEPRECATION") Notification.Builder(context) }
        manager.notify(
            PROGRESS_NOTIFICATION_ID,
            builder.setContentTitle(context.pluginString(R.string.notification_progress_title))
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(0, 0, true).setOngoing(true).build()
        )
    }

    /**
     * Removes the progress notification.
     *
     * Not gated by [PluginSettings.areNotificationsEnabled]: hiding a
     * notification is idempotent and must happen regardless of the current
     * flag value, otherwise a notification posted while the flag was ON could
     * linger forever after the user turns it OFF mid-export.
     */
    private fun hideProgressNotification(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(PROGRESS_NOTIFICATION_ID)
    }

    private fun showErrorNotification(context: Context, message: String) {
        ensureChannel(context, ERROR_CHANNEL_ID, R.string.notification_channel_err, NotificationManager.IMPORTANCE_HIGH)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ERROR_CHANNEL_ID)
        } else { @Suppress("DEPRECATION") Notification.Builder(context) }
        manager.notify(
            ERROR_NOTIFICATION_ID,
            builder.setContentTitle(context.pluginString(R.string.error_notification_title))
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true).build()
        )
    }

    /**
     * Posts the completion notification.
     *
     * When [readErrors] is greater than zero the title switches to
     * [R.string.notification_ready_title_partial] so the user is told that
     * some files could not be read, without pretending the export failed —
     * the dump is still complete for every other file and is fully usable.
     * The pluralization and the count are handled entirely by the resource
     * string, so no extra branching is needed here.
     */
    private fun showCompletionNotification(context: Context, fileName: String, readErrors: Int) {
        ensureChannel(context, ACTIONS_CHANNEL_ID, R.string.notification_channel_actions, NotificationManager.IMPORTANCE_DEFAULT)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ACTIONS_CHANNEL_ID)
        } else { @Suppress("DEPRECATION") Notification.Builder(context) }
        val title = if (readErrors > 0)
            context.pluginQuantityString(
                R.plurals.notification_ready_title_partial, readErrors, readErrors
            )
        else context.pluginString(R.string.notification_ready_title)
        manager.notify(
            COMPLETION_NOTIFICATION_ID,
            builder.setContentTitle(title)
                .setContentText(fileName)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private val globalExportLock = AtomicBoolean(false)

        @Volatile
        private var activeCoordinator: ExportCoordinator? = null

        /** Cancels the export owned by whichever coordinator is currently active. */
        fun cancelCurrent() { activeCoordinator?.cancel() }

        /** Returns `true` while any coordinator is running an export. */
        fun isBusy(): Boolean = globalExportLock.get()

        /**
         * Opens the system share chooser for a `content://` [uri].
         *
         * This is the **single** share entry point for the plugin. It is used
         * both by the coordinator's own SHARE action (internally, on the
         * worker thread's completion path) and by the "Share" chip in the
         * result panel of the configuration dialog (externally, from the UI
         * thread). Consolidating the logic here guarantees that both call
         * sites produce an identical `Intent` — same MIME type, same
         * `ClipData`, same read-permission flag — so the receiving app always
         * has a consistent contract regardless of how the share was
         * triggered.
         *
         * Failure handling is deliberately defensive rather than fatal: a
         * missing activity to handle `ACTION_SEND`, a revoked URI grant, or
         * an OEM chooser crash must never crash the plugin or lose the
         * already-written file. Any [Exception] is logged and surfaced to
         * the user as a long toast, and the caller's flow continues
         * normally.
         *
         * @param context  any context; the chooser is started with
         *                 [Intent.FLAG_ACTIVITY_NEW_TASK], so a non-Activity
         *                 context is safe.
         * @param uri      the `content://` URI of the generated dump. Must
         *                 already be readable by the receiving app — the
         *                 caller is responsible for validating it (see
         *                 `ConfigurationDialog.isSnapshotUriReadable`) before
         *                 invoking this method.
         * @param fileName the display name used as the subject and as the
         *                 `ClipData` label. Falls back to a generic subject
         *                 only if the caller passes a blank string.
         */
        fun shareFile(context: Context, uri: Uri, fileName: String) {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = MIME_TEXT
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                    clipData = android.content.ClipData.newUri(context.contentResolver, fileName, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, context.pluginString(R.string.share_with)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                PluginLogger.d("ExportCoordinator", "[OK] Share chooser launched for: $fileName")
            } catch (error: Exception) {
                PluginLogger.e("ExportCoordinator", "[ERROR] Failed to launch share chooser: ${error.message}", error)
                Toast.makeText(
                    context,
                    context.pluginString(R.string.share_error, error.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        private const val MIME_TEXT = "text/plain"
        private const val DOWNLOAD_DIRECTORY = "OneCodeForAI"

        /**
         * Subdirectory of `cacheDir` used for the in-flight dump. Private to
         * the plugin, never exposed through a `ContentProvider`.
         */
        private const val TEMP_SUBDIR = "tmp"

        const val SHARE_FILE_NAME = "OneCodeForAI_Share_Latest.txt"
        private const val SHARE_FILE_BASE = "OneCodeForAI_Share_Latest"
        private const val PROGRESS_CHANNEL_ID = "onecode_progress"
        private const val ERROR_CHANNEL_ID = "onecode_progress_error"
        private const val ACTIONS_CHANNEL_ID = "onecode_actions"
        private const val PROGRESS_NOTIFICATION_ID = 1001
        private const val ERROR_NOTIFICATION_ID = PROGRESS_NOTIFICATION_ID + 1
        private const val COMPLETION_NOTIFICATION_ID = PROGRESS_NOTIFICATION_ID + 2
        private const val WORKER_THREAD_NAME = "OneCodeForAI-export"
        private const val MAX_IDENTIFIER_PARENT_LEVELS = 8
        private const val PROGRESS_THROTTLE_MS = 120L

        private val PACKAGE_PATTERN = Regex("""package\s*=\s*["']([^"']+)["']""")
        private val NAMESPACE_PATTERN = Regex("""namespace\s*=\s*["']([^"']+)["']""")
    }
}