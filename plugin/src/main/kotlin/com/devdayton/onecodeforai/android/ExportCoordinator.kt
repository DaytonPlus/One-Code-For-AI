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

enum class ExportAction {

    GENERATE,

    SAVE,

    SHARE
}

class ExportCoordinator(
    private val contextProvider: () -> Context?,
    private val logger: (String) -> Unit
) {
    private val isCancelled = AtomicBoolean(false)

    @Volatile
    private var workerThread: Thread? = null

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

    fun cancel() {
        if (!globalExportLock.get()) return
        isCancelled.set(true)
        workerThread?.interrupt()
        logger("[CANCEL] Cancellation requested by user.")
    }

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

            val totalLimitKb = PluginSettings.getTotalExportLimitKb(context)
            val totalLimitBytes = if (sourceUri == null && totalLimitKb > 0) {
                totalLimitKb.toLong() * FilterConfig.BYTES_PER_KB
            } else {
                0L
            }
            if (totalLimitBytes > 0L) {
                logger("[INFO] Total export size limit: ${totalLimitKb} KB (${totalLimitBytes} bytes).")
            }

            val tempDir = File(context.cacheDir, TEMP_SUBDIR).apply { mkdirs() }
            val tempFile = File(tempDir, "dump-${System.currentTimeMillis()}.txt")

            var lastReportedAt = 0L
            var finalFiles = sourceFilesCount
            var finalBytes = sourceTotalBytes

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

                tempFile.delete()
                throw e
            }

            if (isCancelled.get()) {
                tempFile.delete()
                runCatching { hideProgressNotification(context) }
                ExportStateHolder.reset()
                return
            }

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

                tempFile.delete()
            }

            runCatching { hideProgressNotification(context) }

            logger("[OK] File written: $outputUri (readErrors=${readErrors.size})")

            runCatching { cleanupDuplicateShareFiles(context, preserveFileName = fileName) }

            notifyIfEnabled(context) { showCompletionNotification(context, fileName, readErrors.size) }
            ExportStateHolder.complete(outputUri, fileName, finalFiles, finalBytes)

            if (action == ExportAction.SHARE) {
                logger("[INFO] Launching system share chooser.")
                shareFile(context, outputUri, fileName)
            }
        } catch (e: InterruptedException) {

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

    private inline fun notifyIfEnabled(context: Context, block: () -> Unit) {
        if (!PluginSettings.areNotificationsEnabled(context)) return
        runCatching { block() }
    }

    private fun generateContent(
        root: File,
        states: Map<String, NodeState>,
        writer: Writer,
        totalLimitBytes: Long = 0L,
        onFileProgress: FileProgressListener,
        onReadError: ((String) -> Unit)? = null
    ) {

        val selectedRef = states.entries
            .asSequence()
            .mapNotNull { (_, _) -> null as String? }
            .firstOrNull()

        CodeDumpGenerator(
            root = root,
            nodes = states,
            gitRef = null,
            onProgress = onFileProgress,
            onReadError = onReadError,
            totalLimitBytes = totalLimitBytes
        ).generateTo(writer)
    }

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

    private fun writeToDownloads(
        context: Context,
        fileName: String,
        content: (Writer) -> Unit
    ): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        writeToDownloadsApi29(context, fileName, content)
    } else {
        writeToDownloadsLegacy(context, fileName, content)
    }

    private fun writeToDownloadsApi29(
        context: Context,
        fileName: String,
        content: (Writer) -> Unit
    ): Uri {
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIRECTORY

        val existingUri = findExistingDownloadFile(resolver, fileName)

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

            resolver.openOutputStream(newUri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use(content)
            } ?: throw IOException("MediaStore did not provide an output stream.")

            if (existingUri != null && existingUri != newUri) {
                runCatching { resolver.delete(existingUri, null, null) }
                    .onSuccess { logger("[INFO] Deleted previous file: $existingUri") }
                    .onFailure { logger("[WARN] Could not delete previous file: ${it.message}") }
            }

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

            runCatching { resolver.delete(newUri, null, null) }
            throw e
        }
    }

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

            tempFile.outputStream().use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use(content)
            }

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

    private fun generateSmartFileName(folder: File): String {
        val appIdentifier = findAppIdentifier(folder)
        val folderName = sanitizeFileName(folder.name)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val prefix = if (appIdentifier.isBlank() || appIdentifier == folderName) {
            folderName
        } else "${sanitizeFileName(appIdentifier)}_$folderName"
        return "${prefix}_$timestamp.txt"
    }

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

    private fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("""[\\/:*?"<>|]"""), "")
        .ifBlank { "dump" }

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

        fun cancelCurrent() { activeCoordinator?.cancel() }

        fun isBusy(): Boolean = globalExportLock.get()

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