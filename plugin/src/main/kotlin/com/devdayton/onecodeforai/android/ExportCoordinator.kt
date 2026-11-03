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
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.core.CodeDumpGenerator
import com.devdayton.onecodeforai.core.FileFilter
import com.devdayton.onecodeforai.core.FilterConfig
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coordinates long-running export operations and Android-specific delivery mechanisms.
 * This class does not own any UI state and can be reused across different plugin invocations.
 */
class ExportCoordinator(
    private val contextProvider: () -> Context?,
    private val logger: (String) -> Unit
) {
    /**
     * Starts the export process in a background worker thread to avoid blocking the UI thread.
     *
     * @param root The root directory of the project to be exported.
     * @param config The filter configuration defining which files to include or exclude.
     * @param shareAfterExport If true, the file is generated with a static name and shared via an Intent.
     * If false, a unique filename is generated and saved to the Downloads directory.
     */
    fun export(root: File, config: FilterConfig, shareAfterExport: Boolean) {
        Thread({ runExport(root, config, shareAfterExport) }, WORKER_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Executes the core export logic: handles file generation, storage delivery, and user notifications.
     * This method runs on a background thread.
     */
    private fun runExport(root: File, config: FilterConfig, shareAfterExport: Boolean) {
        val context = contextProvider()
        if (context == null) {
            logger("❌ Critical error: Unable to obtain application context.")
            return
        }

        try {
            val actionLabel = context.pluginString(
                if (shareAfterExport) R.string.action_save_share_share else R.string.action_save_share
            )
            logger(context.pluginString(R.string.starting_generation, actionLabel))
            
            // Notifications are optional on newer Android versions; export must still work
            // when the host application has not been granted notification permission.
            runCatching {
                showProgressNotification(context, context.pluginString(R.string.generating_dump))
            }

            // If sharing, use a static filename to overwrite the previous temporary file.
            // If saving, generate a unique filename based on project metadata and timestamp.
            val fileName = if (shareAfterExport) {
                SHARE_FILE_NAME
            } else {
                generateSmartFileName(root)
            }
            
            logger("📄 Preparing file: $fileName")

            // Write to Downloads using MediaStore
            val outputUri = writeToDownloads(context, fileName) { writer ->
                generateContent(root, config, writer)
            }

            runCatching { hideProgressNotification(context) }
            
            if (outputUri == null) {
                logger("❌ Error: Generated URI is null. File write failed.")
                showToast(context, context.pluginString(R.string.could_not_create))
                return
            }

            logger("✅ File successfully created/updated. URI: $outputUri")

            mainHandler.post {
                if (shareAfterExport) {
                    logger("🚀 Launching Share Intent...")
                    shareFile(context, outputUri, fileName)
                } else {
                    val message = context.pluginString(R.string.saved_to_downloads, fileName)
                    logger(message)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        } catch (error: Exception) {
            runCatching { hideProgressNotification(context) }
            val message = context.pluginString(
                R.string.general_error,
                error.message ?: context.pluginString(R.string.unknown_error)
            )
            logger("💥 General exception in runExport: $message")
            error.printStackTrace()
            runCatching {
                showErrorNotification(context, error.message ?: context.pluginString(R.string.unknown_error))
            }
        }
    }

    /**
     * Extracts the project dump generation logic for reuse across different storage methods.
     */
    private fun generateContent(root: File, config: FilterConfig, writer: Writer) {
        val filter = FileFilter(root, config)
        val selectedRef = if (config.useGit) {
            config.gitRef?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalStateException("Git export is enabled but no reference was selected.")
        } else {
            null
        }
        CodeDumpGenerator(root, filter, selectedRef).generateTo(writer)
    }

    /**
     * Writes atomically to the public Downloads directory.
     * If the file already exists, it overwrites the existing file directly using "wt" (write-truncate) mode.
     * This strategy prevents Android from creating unwanted "(1)", "(2)" suffixes.
     *
     * @return The Uri of the saved file, or null if the operation failed.
     */
    private fun writeToDownloads(
        context: Context,
        fileName: String,
        content: (Writer) -> Unit
    ): Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIRECTORY
            
            // 1. Check if the file already exists.
            // IMPORTANT: We query ONLY by DISPLAY_NAME. Matching RELATIVE_PATH fails because
            // MediaStore appends a trailing slash (e.g., "Download/OneCodeForAI/") to the internal path.
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(fileName)
            
            var existingUri: Uri? = null
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(idColumn)
                    existingUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                }
            }

            if (existingUri != null) {
                // 2a. If it exists, OVERWRITE it directly using "wt" (write-truncate) mode
                try {
                    logger("♻️ Overwriting existing file in Downloads...")
                    resolver.openOutputStream(existingUri!!, "wt")?.use { stream ->
                        OutputStreamWriter(stream, Charsets.UTF_8).use(content)
                    }
                    // Update modified time so file managers show the new date
                    val updateValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    }
                    resolver.update(existingUri!!, updateValues, null, null)
                    return existingUri
                } catch (e: Exception) {
                    logger("⚠️ Failed to overwrite, deleting and recreating: ${e.message}")
                    // If overwrite fails (e.g., file was deleted externally), fall back to delete+insert
                    resolver.delete(existingUri!!, null, null)
                }
            }

            // 2b. If it doesn't exist (or overwrite failed), CREATE a new file
            logger("✨ Creating new file in Downloads...")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TEXT)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                val stream = resolver.openOutputStream(uri)
                    ?: throw IOException("MediaStore did not provide an output stream.")
                stream.use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use(content)
                }
                val completed = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                if (resolver.update(uri, completed, null, null) <= 0) {
                    throw IOException("MediaStore could not finalize the exported file.")
                }
                uri
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            // Fallback for Android 9 (API 28) and below (Java File API)
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDirectory = File(downloads, DOWNLOAD_DIRECTORY)
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                throw IOException("Unable to create ${targetDirectory.path}")
            }

            val file = File(targetDirectory, fileName)
            try {
                // Java File outputStream overwrites by default
                file.outputStream().use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use(content)
                }
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
     * Launches the system share sheet with the generated file.
     * Grants temporary read permission to the receiving application.
     */
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
                // NEW_TASK is necessary when launching from an Application context.
                // Note: This intentionally moves the host app to the background while the share sheet is open.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            logger("✅ Share Intent launched successfully.")
        } catch (error: Exception) {
            logger("❌ Error launching Share Intent: ${error.message}")
            showToast(context, context.pluginString(R.string.share_error, error.message ?: ""))
        }
    }

    /**
     * Generates a unique filename based on the project identifier and a timestamp.
     * Falls back to the folder name if no app identifier (package/namespace) is found.
     */
    private fun generateSmartFileName(folder: File): String {
        val appIdentifier = findAppIdentifier(folder)
        val folderName = sanitizeFileName(folder.name)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val prefix = if (appIdentifier.isBlank() || appIdentifier == folderName) {
            folderName
        } else {
            "${sanitizeFileName(appIdentifier)}_$folderName"
        }
        return "${prefix}_$timestamp.txt"
    }

    /**
     * Attempts to find a package name or namespace within the project structure
     * to use as a descriptive prefix for the export file.
     */
    private fun findAppIdentifier(folder: File): String {
        var current: File? = folder
        repeat(MAX_IDENTIFIER_PARENT_LEVELS) {
            val directory = current ?: return ""
            val manifestCandidates = listOf(
                File(directory, "src/main/AndroidManifest.xml"),
                File(directory, "AndroidManifest.xml")
            )
            manifestCandidates.firstOrNull(File::isFile)?.let { manifest ->
                runCatching { manifest.readText(Charsets.UTF_8) }.getOrNull()?.let { source ->
                    PACKAGE_PATTERN.find(source)?.groupValues?.getOrNull(1)?.let { return it }
                }
            }

            listOf("module.toml", "build.gradle.kts", "build.gradle")
                .map { File(directory, it) }
                .firstOrNull(File::isFile)
                ?.let { configFile ->
                    runCatching { configFile.readText(Charsets.UTF_8) }.getOrNull()?.let { source ->
                        NAMESPACE_PATTERN.find(source)?.groupValues?.getOrNull(1)?.let { return it }
                    }
                }
            current = directory.parentFile
        }
        return ""
    }

    /** Sanitizes a string to ensure it is a valid filename across common filesystems. */
    private fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("""[\\/:*?"<>|]"""), "")
        .ifBlank { "dump" }

    /** Displays an ongoing progress notification to inform the user about the export status. */
    private fun showProgressNotification(context: Context, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    PROGRESS_CHANNEL_ID,
                    context.pluginString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, PROGRESS_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        manager.notify(
            PROGRESS_NOTIFICATION_ID,
            builder.setContentTitle(context.pluginString(R.string.notification_progress_title))
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build()
        )
    }

    /** Dismisses the progress notification. */
    private fun hideProgressNotification(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(PROGRESS_NOTIFICATION_ID)
    }

    /** Displays a high-priority notification in case the export fails. */
    private fun showErrorNotification(context: Context, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ERROR_CHANNEL_ID,
                    context.pluginString(R.string.notification_channel_err),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ERROR_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        manager.notify(
            ERROR_NOTIFICATION_ID,
            builder.setContentTitle(context.pluginString(R.string.error_notification_title))
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build()
        )
    }

    /** Displays a Toast message on the main thread. */
    private fun showToast(context: Context, message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        const val MIME_TEXT = "text/plain"
        const val DOWNLOAD_DIRECTORY = "OneCodeForAI"
        const val SHARE_FILE_NAME = "OneCodeForAI_Share_Latest.txt"
        const val PROGRESS_CHANNEL_ID = "onecode_progress"
        const val ERROR_CHANNEL_ID = "onecode_progress_error"
        const val PROGRESS_NOTIFICATION_ID = 1001
        const val ERROR_NOTIFICATION_ID = PROGRESS_NOTIFICATION_ID + 1
        const val WORKER_THREAD_NAME = "OneCodeForAI-export"
        const val MAX_IDENTIFIER_PARENT_LEVELS = 8

        val mainHandler = Handler(Looper.getMainLooper())
        val PACKAGE_PATTERN = Regex("""package\s*=\s*[\"']([^\"']+)[\"']""")
        val NAMESPACE_PATTERN = Regex("""namespace\s*=\s*[\"']([^\"']+)[\"']""")
    }
}