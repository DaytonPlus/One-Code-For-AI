package com.devdayton.onecodeforai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
 * Coordinates long-running export work and Android-specific delivery mechanisms.
 * It owns no dialog state and can be reused by every plugin action invocation.
 */
class ExportCoordinator(
    private val contextProvider: () -> Context?,
    private val logger: (String) -> Unit
) {
    fun export(root: File, config: FilterConfig, shareAfterExport: Boolean) {
        Thread({ runExport(root, config, shareAfterExport) }, WORKER_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    private fun runExport(root: File, config: FilterConfig, shareAfterExport: Boolean) {
        val context = contextProvider()
        if (context == null) {
            logger("Unable to access the CodeAssist application context.")
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

            val fileName = generateSmartFileName(root)
            logger(context.pluginString(R.string.filename_log, fileName))

            val outputUri = writeToDownloads(context, fileName) { writer ->
                val filter = FileFilter(root, config)
                val selectedRef = if (config.useGit) {
                    config.gitRef?.trim()?.takeIf(String::isNotEmpty)
                        ?: throw IllegalStateException("Git export is enabled but no reference was selected.")
                } else {
                    null
                }
                CodeDumpGenerator(root, filter, selectedRef).generateTo(writer)
            }

            runCatching { hideProgressNotification(context) }
            if (outputUri == null) {
                logger(context.pluginString(R.string.failed_creation))
                showToast(context, context.pluginString(R.string.could_not_create))
                return
            }

            mainHandler.post {
                if (shareAfterExport) {
                    shareFile(context, outputUri, fileName)
                    logger(context.pluginString(R.string.sharing, fileName))
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
            logger(message)
            runCatching {
                showErrorNotification(context, error.message ?: context.pluginString(R.string.unknown_error))
            }
        }
    }

    /** Writes atomically from the perspective of MediaStore on Android 10+. */
    private fun writeToDownloads(
        context: Context,
        fileName: String,
        content: (Writer) -> Unit
    ): Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TEXT)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIRECTORY
                )
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
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDirectory = File(downloads, DOWNLOAD_DIRECTORY)
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                throw IOException("Unable to create ${targetDirectory.path}")
            }

            val file = File(targetDirectory, fileName)
            try {
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
        } catch (error: Exception) {
            showToast(context, context.pluginString(R.string.share_error, error.message ?: ""))
        }
    }

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

    private fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("""[\\/:*?"<>|]"""), "")
        .ifBlank { "dump" }

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

    private fun hideProgressNotification(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(PROGRESS_NOTIFICATION_ID)
    }

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

    private fun showToast(context: Context, message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        const val MIME_TEXT = "text/plain"
        const val DOWNLOAD_DIRECTORY = "OneCodeForAI"
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
