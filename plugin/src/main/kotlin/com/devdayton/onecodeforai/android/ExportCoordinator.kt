package com.devdayton.onecodeforai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
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
 * Cancellation is cooperative: [cancel] interrupts the worker thread and
 * [CodeDumpGenerator] checks `Thread.isInterrupted` between files, so large
 * exports unwind promptly without leaving partial files behind.
 *
 * A single process-wide lock ([Companion.globalExportLock]) prevents concurrent
 * runs regardless of whether the request originates from the dialog, a
 * notification action, or a future entry point.
 *
 * Logging uses bracketed severity tags ([INFO], [OK], [WARN], [ERROR],
 * [CANCEL], [CLEANUP]) so log lines can be filtered by severity in the IDE.
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
     * When [sourceUri] is provided, the operation copies that file into a new
     * timestamped destination instead of re-scanning the project — used by the
     * "Save" action after generation completes.
     */
    fun export(
        root: File, 
        config: FilterConfig, 
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
            try { runExport(root, config, action, sourceUri, sourceFilesCount, sourceTotalBytes) }
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
     */
    private fun runExport(
        root: File, 
        config: FilterConfig, 
        action: ExportAction,
        sourceUri: Uri?,
        sourceFilesCount: Int,
        sourceTotalBytes: Long
    ) {
        val context = contextProvider()
        if (context == null) {
            ExportStateHolder.fail("Unable to obtain application context.")
            logger("[ERROR] Application context unavailable; aborting export.")
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

            val initialMessage = if (sourceUri != null) context.pluginString(R.string.preparing_export) else context.pluginString(R.string.generating_dump)
            runCatching { showProgressNotification(context, initialMessage) }
            
            // When generating from scratch, remove any leftover duplicate share
            // files before writing so the user only ever sees a single file in
            // Downloads/OneCodeForAI after a SHARE run.
            if (sourceUri == null) {
                runCatching { cleanupDuplicateShareFiles(context) }
            }

            val fileName = if (action == ExportAction.GENERATE) {
                SHARE_FILE_NAME
            } else {
                generateSmartFileName(root)
            }
            logger("[INFO] Target filename: $fileName")

            var lastReportedAt = 0L
            var finalFiles = sourceFilesCount
            var finalBytes = sourceTotalBytes

            val outputUri = writeToDownloads(context, fileName) { writer ->
                if (sourceUri != null) {
                    // Copy mode: the source file was already produced, so we
                    // only stream it into the new destination without
                    // re-scanning the project.
                    copyFromUri(context, sourceUri, writer)
                } else {
                    // Generation mode: walk the working tree (or the selected
                    // Git snapshot) and write the dump incrementally.
                    generateContent(root, config, writer) { processed, total, _, bytesProcessed, bytesTotal ->
                        if (isCancelled.get()) throw InterruptedException("Export cancelled by user.")
                        finalFiles = processed
                        finalBytes = bytesProcessed
                        val now = System.currentTimeMillis()
                        if (now - lastReportedAt > PROGRESS_THROTTLE_MS || processed == total) {
                            lastReportedAt = now
                            val message = if (total > 0) {
                                context.pluginString(R.string.processing_progress, processed, total)
                            } else context.pluginString(R.string.preparing_export)
                            ExportStateHolder.updateProgress(message, processed, total, bytesProcessed, bytesTotal)
                        }
                    }
                }
            }

            if (isCancelled.get()) {
                runCatching { hideProgressNotification(context) }
                ExportStateHolder.reset()
                return
            }

            runCatching { hideProgressNotification(context) }

            if (outputUri == null) {
                ExportStateHolder.fail(context.pluginString(R.string.error_file_write_failed))
                logger("[ERROR] MediaStore returned a null URI; export aborted.")
                return
            }

            logger("[OK] File written: $outputUri")
            runCatching {
                showCompletionNotification(context, fileName)
            }
            ExportStateHolder.complete(outputUri, fileName, finalFiles, finalBytes)

            if (action == ExportAction.SHARE) {
                logger("[INFO] Launching system share chooser.")
                shareFile(context, outputUri, fileName)
            }
        } catch (error: InterruptedException) {
            runCatching { hideProgressNotification(context) }
            logger("[CANCEL] Export cancelled before completion.")
            ExportStateHolder.reset()
        } catch (error: Exception) {
            runCatching { hideProgressNotification(context) }
            val message = resolveErrorMessage(context, error)
            logger("[ERROR] Unhandled exception during export: ${error.message ?: message}")
            error.printStackTrace()
            runCatching { showErrorNotification(context, message) }
            ExportStateHolder.fail(message)
        }
    }

    /**
     * Streams the filtered project into [writer].
     *
     * When [FilterConfig.useGit] is enabled, the selected ref is resolved to a
     * snapshot before generation starts; an invalid or empty ref aborts the
     * export rather than silently falling back to the working tree, so a
     * misconfigured profile cannot produce a dump that does not match the
     * branch the user selected.
     */
    private fun generateContent(
        root: File,
        config: FilterConfig,
        writer: Writer,
        onFileProgress: FileProgressListener
    ) {
        val filter = FileFilter(root, config)
        val selectedRef = if (config.useGit) {
            config.gitRef?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalStateException("Git export enabled but no reference selected.")
        } else null
        CodeDumpGenerator(root, filter, selectedRef, onFileProgress).generateTo(writer)
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
     * The inline `[File read error: …]` and `[Git read error: …]` markers that are
     * written **inside the `.txt` dump** deliberately bypass this method: they are
     * part of the generated content and stay in English for the consuming model.
     */
    private fun resolveErrorMessage(context: Context, error: Exception): String {
        val msg = error.message.orEmpty()
        return when {
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
            msg == "MediaStore did not provide an output stream." ->
                context.pluginString(R.string.error_output_stream_unavailable)
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
    // MediaStore write + cleanup
    // ------------------------------------------------------------------

    /**
     * Writes [content] to `Downloads/OneCodeForAI/<fileName>` through
     * MediaStore on API 29+, and through the legacy public-Downloads path on
     * older devices.
     *
     * On API 29+, if a file with the same name already exists it is truncated
     * in place rather than deleted first, because the delete+insert fallback
     * can create media entries that are briefly visible to other apps.
     */
    private fun writeToDownloads(
        context: Context,
        fileName: String,
        content: (Writer) -> Unit
    ): Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIRECTORY
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(fileName)

            var existingUri: Uri? = null
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    existingUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                }
            }

            if (existingUri != null) {
                try {
                    logger("[INFO] Overwriting existing file in Downloads.")
                    resolver.openOutputStream(existingUri, "wt")?.use { stream ->
                        OutputStreamWriter(stream, Charsets.UTF_8).use(content)
                    }
                    resolver.update(existingUri, ContentValues().apply {
                        put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    }, null, null)
                    return existingUri
                } catch (e: Exception) {
                    logger("[WARN] Overwrite failed; falling back to delete+insert. Cause: ${e.message}")
                    resolver.delete(existingUri, null, null)
                }
            }

            logger("[INFO] Creating new file in Downloads.")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TEXT)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use(content)
                } ?: throw IOException("MediaStore did not provide an output stream.")
                if (resolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null) <= 0) {
                    throw IOException("MediaStore could not finalize the file.")
                }
                uri
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDirectory = File(downloads, DOWNLOAD_DIRECTORY)
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                throw IOException("Unable to create ${targetDirectory.path}")
            }
            val file = File(targetDirectory, fileName)
            try {
                file.outputStream().use { output -> OutputStreamWriter(output, Charsets.UTF_8).use(content) }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, file.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, MIME_TEXT)
                }
                @Suppress("DEPRECATION")
                resolver.insert(MediaStore.Files.getContentUri("external"), values)
            } catch (error: Exception) {
                runCatching { file.delete() }
                throw error
            }
        }
    }

    /**
     * Removes any `OneCodeForAI_Share_Latest*.txt` files left behind by previous
     * SHARE runs so the canonical file is the only one visible.
     *
     * Only files matching the base name (optionally suffixed by ` (n)`, which is
     * how MediaStore de-duplicates names) are removed; timestamped SAVE files
     * are intentionally preserved.
     */
    private fun cleanupDuplicateShareFiles(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("$SHARE_FILE_BASE%")
        val duplicatePattern = Regex("""^${Regex.escape(SHARE_FILE_BASE)}( \(\d+\))?\.txt$""")

        val toDelete = mutableListOf<Uri>()
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
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
                logger("[CLEANUP] Removed duplicate share file: $uri")
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

    /** Opens the system share chooser for [uri]. Logs and toasts on failure. */
    private fun shareFile(context: Context, uri: Uri, fileName: String) {
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
            logger("[OK] Share chooser launched.")
        } catch (error: Exception) {
            logger("[ERROR] Failed to launch share chooser: ${error.message}")
            Toast.makeText(
                context,
                context.pluginString(R.string.share_error, error.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ------------------------------------------------------------------
    // Notifications
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

    private fun showCompletionNotification(context: Context, fileName: String) {
        ensureChannel(context, ACTIONS_CHANNEL_ID, R.string.notification_channel_actions, NotificationManager.IMPORTANCE_DEFAULT)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ACTIONS_CHANNEL_ID)
        } else { @Suppress("DEPRECATION") Notification.Builder(context) }
        manager.notify(
            COMPLETION_NOTIFICATION_ID,
            builder.setContentTitle(context.pluginString(R.string.notification_ready_title))
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
         * Static share helper used by callers that already hold a valid
         * `content://` URI (e.g. the "Share" chip in the result panel).
         */
        fun shareFile(context: Context, uri: Uri, fileName: String) {
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
        }

        private const val MIME_TEXT = "text/plain"
        private const val DOWNLOAD_DIRECTORY = "OneCodeForAI"
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