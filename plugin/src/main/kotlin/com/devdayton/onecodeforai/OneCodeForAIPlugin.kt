package com.devdayton.onecodeforai

import android.app.Activity
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
import com.devdayton.onecodeforai.UiFactory.pluginString
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SETTINGS_PAGE_EP
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.UI_ACTION_EP
import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OneCodeForAIPlugin : Plugin {

    companion object {
        private const val NOTIFICATION_ID_PROGRESS = 1001
        private const val CHANNEL_ID = "onecode_progress"
    }

    @Volatile
    private var isDialogShowing = false

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("OneCodeForAIPlugin")
        log.info("loaded")

        // El texto del SimpleAction se resuelve una sola vez en register().
        // Si el idioma del IDE cambia después, el label se actualizará al reiniciar.
        val appCtx = getAndroidContext()
        val actionText = appCtx?.pluginString(R.string.action_label)
            ?: "One Code For AI: generate .txt"

        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "com.devdayton.onecodeforai.dump",
                text = actionText,
                places = setOf(ActionPlaces.FILE_CONTEXT, ActionPlaces.COMMAND_PALETTE),
                iconId = "sparkle",
            ) { ctx ->
                startConfigurationFlow(ctx) { msg -> log.info(msg) }
            },
        )

        reg.register(SETTINGS_PAGE_EP, OneCodeForAIPluginSettingsPage())
    }

    // ------------------------------------------------------------------
    // Flow de configuración
    // ------------------------------------------------------------------

    private fun startConfigurationFlow(
        ctx: ActionContext,
        logInfo: (String) -> Unit
    ): ActionResult {
        // Context para resolver strings (Application del IDE host).
        // Necesario porque ActionContext no expone un Context público.
        val msgCtx = getAndroidContext()
        fun msg(resId: Int): String =
            msgCtx?.pluginString(resId) ?: ""

        if (isDialogShowing) {
            return ActionResult.message(msg(R.string.dialog_already_open))
        }

        val folderPath = ctx.contextPath
            ?: return ActionResult.message(msg(R.string.no_folder_selected))

        var selectedDir = File(folderPath)
        
        // Si el usuario seleccionó un archivo, tomamos su carpeta contenedora
        if (selectedDir.isFile) {
            selectedDir = selectedDir.parentFile ?: selectedDir
        }

        if (!selectedDir.isDirectory) {
            return ActionResult.message(msg(R.string.select_valid_folder))
        }

        // --- AUTO-DETECCIÓN DE LA RAÍZ DEL PROYECTO ---
        // Busca la carpeta superior que contenga el .git
        val detectedRoot = GitUtils.findGitRoot(selectedDir) ?: selectedDir
        if (detectedRoot.absolutePath != selectedDir.absolutePath) {
            logInfo("📍 Raíz del proyecto detectada automáticamente: ${detectedRoot.name}")
        }

        return showConfigurationDialog(detectedRoot, logInfo)
    }

    private fun showConfigurationDialog(root: File, logInfo: (String) -> Unit): ActionResult {
        val msgCtx = getAndroidContext()
        fun msg(resId: Int): String = msgCtx?.pluginString(resId) ?: ""

        val activity = getCurrentActivity()
        if (activity == null) {
            logInfo("❌ No se pudo obtener la Activity.")
            return ActionResult.message(msg(R.string.error_opening_dialog))
        }

        isDialogShowing = true

        val prefs = activity.getSharedPreferences("onecode_settings", Context.MODE_PRIVATE)
        val gitEnabled = prefs.getBoolean("git_enabled", true)
        val treeEnabled = prefs.getBoolean("tree_enabled", true)

        Handler(Looper.getMainLooper()).post {
            try {
                ConfigurationDialog(
                    activity = activity,
                    projectRoot = root,
                    gitEnabledGlobally = gitEnabled,
                    onExport = { config, isShare ->
                        executeExport(root, config, isShare, logInfo)
                    },
                    treeEnabledGlobally = treeEnabled,
                    onDismiss = { isDialogShowing = false },
                    onChangeRoot = { newRoot ->
                        // Si el usuario pide cambiar la raíz (ej. con el botón de Git),
                        // cerramos este diálogo y abrimos uno nuevo apuntando a la nueva ruta.
                        isDialogShowing = false
                        // Pequeño delay para evitar problemas con el WindowManager al reemplazar diálogos
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!isDialogShowing) {
                                showConfigurationDialog(newRoot, logInfo)
                            }
                        }, 250)
                    }
                ).show()
            } catch (e: Exception) {
                isDialogShowing = false
                logInfo("❌ Error al mostrar el diálogo: ${e.message}")
            }
        }

        return ActionResult.message(msg(R.string.opening_config))
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private fun executeExport(
        root: File,
        config: FilterConfig,
        isShare: Boolean,
        logInfo: (String) -> Unit
    ) {
        Thread {
            try {
                val appCtx = getAndroidContext()
                fun msg(resId: Int): String = appCtx?.pluginString(resId) ?: ""
                fun msg(resId: Int, vararg args: Any): String =
                    appCtx?.pluginString(resId, *args) ?: ""

                val actionLabel = if (isShare) msg(R.string.action_save_share_share)
                                  else msg(R.string.action_save_share)

                logInfo(msg(R.string.starting_generation, actionLabel))
                showProgressNotification(msg(R.string.generating_dump))

                val context = appCtx ?: run {
                    hideProgressNotification()
                    return@Thread
                }

                val fileName = generateSmartFileName(root)
                logInfo(msg(R.string.filename_log, fileName))

                val mediaUri = writeToMediaStore(context, fileName) { writer ->
                    val filter = FileFilter(root, config)
                    val gitRef = if (config.useGit) config.gitRef else null
                    CodeDumpGenerator(root, filter, gitRef).generateTo(writer)
                }

                hideProgressNotification()

                if (mediaUri == null) {
                    logInfo(msg(R.string.failed_creation))
                    showToast(context, msg(R.string.could_not_create))
                    return@Thread
                }

                Handler(Looper.getMainLooper()).post {
                    if (isShare) {
                        shareMediaUri(context, mediaUri, fileName)
                        logInfo(msg(R.string.sharing, fileName))
                    } else {
                        val successMsg = msg(R.string.saved_to_downloads, fileName)
                        logInfo(successMsg)
                        Toast.makeText(context, successMsg, Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Throwable) {
                hideProgressNotification()
                val appCtx = getAndroidContext()
                val generalErr = appCtx?.pluginString(R.string.general_error, e.message ?: "")
                    ?: "❌ ${e.message}"
                logInfo(generalErr)
                val unknown = appCtx?.pluginString(R.string.unknown_error)
                    ?: "Unknown error"
                showErrorNotification(e.message ?: unknown)
            }
        }.start()
    }

    private fun writeToMediaStore(
        context: Context,
        fileName: String,
        content: (Writer) -> Unit
    ): Uri? {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OneCodeForAI")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { stream ->
                val writer = OutputStreamWriter(stream, Charsets.UTF_8)
                content(writer)
                writer.flush()
            }
            return uri
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, "OneCodeForAI").apply { mkdirs() }
            val file = File(targetDir, fileName)
            file.outputStream().use { stream ->
                val writer = OutputStreamWriter(stream, Charsets.UTF_8)
                content(writer)
                writer.flush()
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, file.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }
            @Suppress("DEPRECATION")
            return resolver.insert(MediaStore.Files.getContentUri("external"), values)
        }
    }

    private fun shareMediaUri(context: Context, uri: Uri, fileName: String) {
        try {
            val chooserTitle = context.pluginString(R.string.share_with)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                clipData = android.content.ClipData.newUri(context.contentResolver, fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, chooserTitle)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            val errMsg = context.pluginString(R.string.share_error, e.message ?: "")
            Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // Nombre de archivo inteligente
    // ------------------------------------------------------------------

    private fun generateSmartFileName(folder: File): String {
        val appId = findAppIdentifier(folder)
        val folderName = sanitizeFileName(folder.name)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        val baseName = if (appId.isBlank() || appId == folderName) {
            "${folderName}_${timestamp}"
        } else {
            "${appId}_${folderName}_${timestamp}"
        }
        return "${baseName}.txt"
    }

    private fun findAppIdentifier(folder: File): String {
        var current: File? = folder
        var levelsUp = 0

        while (current != null && levelsUp < 8) {
            val manifest = File(current, "src/main/AndroidManifest.xml")
            val manifestAlt = File(current, "AndroidManifest.xml")
            val targetManifest = if (manifest.exists()) manifest else if (manifestAlt.exists()) manifestAlt else null

            if (targetManifest != null) {
                val content = targetManifest.readText()
                Regex("""package="([^"]+)"""").find(content)?.let { return it.groupValues[1] }
            }

            val toml = File(current, "module.toml")
            if (toml.exists()) {
                val content = toml.readText()
                Regex("""namespace\s*=\s*"([^"]+)"""").find(content)?.let { return it.groupValues[1] }
            }

            val gradleKts = File(current, "build.gradle.kts")
            val gradle = File(current, "build.gradle")
            val targetGradle = if (gradleKts.exists()) gradleKts else if (gradle.exists()) gradle else null

            if (targetGradle != null) {
                val content = targetGradle.readText()
                Regex("""namespace\s*=?\s*['"]([^'"]+)['"]""").find(content)?.let { return it.groupValues[1] }
            }

            current = current.parentFile
            levelsUp++
        }
        return ""
    }

    private fun sanitizeFileName(name: String): String {
        return name.trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("""[\\/:*?"<>|]"""), "")
            .ifBlank { "dump" }
    }

    // ------------------------------------------------------------------
    // Helpers de Context / Activity
    // ------------------------------------------------------------------

    private fun getAndroidContext(): Context? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Exception) { null }
    }

    private fun getCurrentActivity(): Activity? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(activityThread) as Map<*, *>
            for (record in activities.values) {
                val activityField = record?.javaClass?.getDeclaredField("activity")
                activityField?.isAccessible = true
                val activity = activityField?.get(record) as? Activity
                if (activity != null && !activity.isFinishing) return activity
            }
            null
        } catch (e: Exception) { null }
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // Notificaciones
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun showProgressNotification(message: String) {
        val context = getAndroidContext() ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelName = context.pluginString(R.string.notification_channel)
        val notifTitle = context.pluginString(R.string.notification_progress_title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        manager.notify(
            NOTIFICATION_ID_PROGRESS,
            builder.setContentTitle(notifTitle)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build()
        )
    }

    private fun hideProgressNotification() {
        val context = getAndroidContext() ?: return
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID_PROGRESS)
    }

    @Suppress("DEPRECATION")
    private fun showErrorNotification(message: String) {
        val context = getAndroidContext() ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelName = context.pluginString(R.string.notification_channel_err)
        val notifTitle = context.pluginString(R.string.error_notification_title)
        val errorChannelId = "${CHANNEL_ID}_err"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(errorChannelId, channelName, NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, errorChannelId)
        } else {
            Notification.Builder(context)
        }
        manager.notify(
            NOTIFICATION_ID_PROGRESS + 1,
            builder.setContentTitle(notifTitle)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build()
        )
    }
}

// ======================================================================
// Settings page
// ======================================================================

private class OneCodeForAIPluginSettingsPage : SettingsPage {
    override val id = "com.devdayton.onecodeforai"
    override val title = "One Code For AI"
    override val iconId = "sparkle"

    /** Application context del IDE (para resolver strings localizadas). */
    private fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Exception) { null }

    override fun controls(): List<SettingControl> {
        val ctx = appContext()
        fun s(resId: Int, fallback: String): String =
            ctx?.pluginString(resId) ?: fallback

        return listOf(
            SettingControl.Toggle(
                "exclude_gitignored",
                s(R.string.settings_gitignore, "Respect .gitignore"),
                s(R.string.settings_gitignore_desc, "Ignores files listed in .gitignore"),
                true
            ),
            SettingControl.Toggle(
                "exclude_binaries",
                s(R.string.settings_binaries, "Exclude binaries"),
                s(R.string.settings_binaries_desc, "Filters out images, videos, etc."),
                true
            ),
            SettingControl.Toggle(
                "git_enabled",
                s(R.string.settings_git, "Git integration"),
                s(R.string.settings_git_desc, "Lets you pick a branch or commit"),
                true
            ),
            SettingControl.Toggle(
                "tree_enabled",
                s(R.string.settings_tree, "File tree & stats"),
                s(
                    R.string.settings_tree_desc,
                    "Shows the file tree and token count. Disable it on large projects if you don't need it."
                ),
                true
            ),
        )
    }

    override fun onChanged(key: String, values: PreferenceReader) {
        try {
            val context = appContext() ?: return
            val boolValue = readBooleanSafely(values, key)
            val prefs = context.getSharedPreferences("onecode_settings", Context.MODE_PRIVATE).edit()
            prefs.putBoolean(key, boolValue)
            prefs.apply()
        } catch (_: Exception) { }
    }

    private fun readBooleanSafely(reader: PreferenceReader, key: String): Boolean {
        return try {
            val methodGet = reader.javaClass.methods.firstOrNull {
                it.name == "get" && it.parameterTypes.size == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            if (methodGet != null) return methodGet.invoke(reader, key, true) as? Boolean ?: true

            val methodGetBool = reader.javaClass.methods.firstOrNull {
                it.name == "getBoolean" && it.parameterTypes.size == 1
            }
            if (methodGetBool != null) return methodGetBool.invoke(reader, key) as? Boolean ?: true

            val methodGetAny = reader.javaClass.methods.firstOrNull {
                it.name == "get" && it.parameterTypes.size == 1
            }
            if (methodGetAny != null) return methodGetAny.invoke(reader, key) as? Boolean ?: true

            true
        } catch (_: Exception) { true }
    }
}