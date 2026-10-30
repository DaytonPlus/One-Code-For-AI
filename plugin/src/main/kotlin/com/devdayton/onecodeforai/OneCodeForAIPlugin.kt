package com.devdayton.onecodeforai

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OneCodeForAIPlugin : Plugin {

    companion object {
        private const val NOTIFICATION_ID_PROGRESS = 1001
        private const val CHANNEL_ID = "onecode_progress"
    }

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("OneCodeForAIPlugin")
        log.info("loaded")

        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "com.devdayton.onecodeforai.dump",
                text = "One Code For AI: generar .txt",
                places = setOf(ActionPlaces.FILE_CONTEXT, ActionPlaces.COMMAND_PALETTE),
                iconId = "sparkle",
            ) { ctx ->
                startConfigurationFlow(ctx) { msg -> log.info(msg) }
            },
        )

        reg.register(SETTINGS_PAGE_EP, OneCodeForAIPluginSettingsPage())
    }

    /**
     * Abre la ventana de configuración con los ajustes persistidos.
     */
    private fun startConfigurationFlow(
        ctx: ActionContext,
        logInfo: (String) -> Unit
    ): ActionResult {
        val folderPath = ctx.contextPath
            ?: return ActionResult.message("❌ No hay carpeta seleccionada.")

        val root = File(folderPath)
        if (!root.isDirectory) {
            return ActionResult.message("❌ Selecciona una carpeta válida.")
        }

        val activity = getCurrentActivity()
        if (activity == null) {
            logInfo("❌ No se pudo obtener la Activity para mostrar la UI.")
            return ActionResult.message("❌ Error: No se pudo abrir la ventana de configuración.")
        }

        // Leer el ajuste global de Git (sincronizado desde Settings)
        val prefs = activity.getSharedPreferences("onecode_settings", Context.MODE_PRIVATE)
        val gitEnabled = prefs.getBoolean("git_enabled", true)

        Handler(Looper.getMainLooper()).post {
            try {
                ConfigurationDialog(activity, root, gitEnabled) { config ->
                    // Callback cuando el usuario pulsa "Exportar"
                    executeExport(root, config, logInfo)
                }.show()
            } catch (e: Exception) {
                logInfo("❌ Error al mostrar el diálogo: ${e.message}")
            }
        }

        return ActionResult.message("⚙️ Abriendo configuración...")
    }

    /**
     * Ejecuta la generación en un hilo secundario usando BufferedWriter (vía OutputStreamWriter)
     * para evitar OutOfMemoryError con proyectos grandes.
     */
    private fun executeExport(
        root: File,
        config: FilterConfig,
        logInfo: (String) -> Unit
    ) {
        Thread {
            try {
                logInfo("Iniciando generación con filtros personalizados...")
                showProgressNotification("Generando volcado de código...")

                val context = getAndroidContext()
                if (context == null) {
                    hideProgressNotification()
                    logInfo("Error: No se pudo obtener el contexto de Android.")
                    return@Thread
                }

                val fileName = generateSmartFileName(root)
                logInfo("Nombre de archivo asignado: $fileName")

                // Obtenemos el stream de salida (MediaStore o File)
                val outputStream = getOutputStream(context, fileName)
                if (outputStream == null) {
                    hideProgressNotification()
                    logInfo("❌ Falló la creación del archivo de salida.")
                    showToast(context, "❌ No se pudo crear el archivo.")
                    return@Thread
                }

                // Escribimos en streaming directamente al archivo
                outputStream.use { stream ->
                    val writer = OutputStreamWriter(stream, Charsets.UTF_8)
                    val filter = FileFilter(root, config)
                    val gitRef = if (config.useGit) config.gitRef else null
                    
                    CodeDumpGenerator(root, filter, gitRef).generateTo(writer)
                    writer.flush()
                }

                hideProgressNotification()
                val msg = "✅ Guardado en Descargas: $fileName"
                logInfo(msg)
                showToast(context, msg)

            } catch (e: Throwable) {
                hideProgressNotification()
                logInfo("❌ Error general: ${e.message}")
                showErrorNotification(e.message ?: "Error desconocido")
            }
        }.start()
    }

    // ==========================================
    // GENERACIÓN DE NOMBRE Y METADATOS
    // ==========================================

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

    /**
     * Sube hasta 8 niveles en el árbol de directorios buscando el identificador de la app.
     */
    private fun findAppIdentifier(folder: File): String {
        var current: File? = folder
        var levelsUp = 0

        while (current != null && levelsUp < 8) {
            // 1. AndroidManifest.xml
            val manifest = File(current, "src/main/AndroidManifest.xml")
            val manifestAlt = File(current, "AndroidManifest.xml")
            val targetManifest = if (manifest.exists()) manifest else if (manifestAlt.exists()) manifestAlt else null

            if (targetManifest != null) {
                val content = targetManifest.readText()
                Regex("""package="([^"]+)"""").find(content)?.let { return it.groupValues[1] }
            }

            // 2. module.toml
            val toml = File(current, "module.toml")
            if (toml.exists()) {
                val content = toml.readText()
                Regex("""namespace\s*=\s*"([^"]+)"""").find(content)?.let { return it.groupValues[1] }
            }

            // 3. build.gradle.kts o build.gradle
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

    // ==========================================
    // MANEJO DE ARCHIVOS (API 26 - 36)
    // ==========================================

    private fun getOutputStream(context: Context, fileName: String): java.io.OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OneCodeForAI")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { context.contentResolver.openOutputStream(it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OneCodeForAI").apply { mkdirs() }
            File(dir, fileName).outputStream()
        }
    }

    // ==========================================
    // UTILIDADES Y REFLEXIÓN
    // ==========================================

    private fun getAndroidContext(): Context? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Exception) {
            null
        }
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
        } catch (e: Exception) {
            null
        }
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // ==========================================
    // NOTIFICACIONES
    // ==========================================

    @Suppress("DEPRECATION")
    private fun showProgressNotification(message: String) {
        val context = getAndroidContext() ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "One Code", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        manager.notify(NOTIFICATION_ID_PROGRESS, builder.setContentTitle("One Code For AI").setContentText(message).setSmallIcon(android.R.drawable.stat_sys_download).setProgress(0, 0, true).setOngoing(true).build())
    }

    private fun hideProgressNotification() {
        val context = getAndroidContext() ?: return
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID_PROGRESS)
    }

    @Suppress("DEPRECATION")
    private fun showErrorNotification(message: String) {
        val context = getAndroidContext() ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel("${CHANNEL_ID}_err", "One Code Err", NotificationManager.IMPORTANCE_HIGH))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, "${CHANNEL_ID}_err")
        } else {
            Notification.Builder(context)
        }
        manager.notify(NOTIFICATION_ID_PROGRESS + 1, builder.setContentTitle("Error en One Code").setContentText(message).setSmallIcon(android.R.drawable.stat_notify_error).setAutoCancel(true).build())
    }
}

/**
 * Ajustes globales del plugin. Los valores se sincronizan con SharedPreferences
 * para que el diálogo de configuración pueda leerlos en tiempo real.
 */
private class OneCodeForAIPluginSettingsPage : SettingsPage {
    override val id = "com.devdayton.onecodeforai"
    override val title = "One Code For AI"
    override val iconId = "sparkle"

    override fun controls(): List<SettingControl> = listOf(
        SettingControl.Toggle("exclude_gitignored", "Respetar .gitignore", "Ignora archivos de .gitignore", true),
        SettingControl.Toggle("exclude_binaries", "Excluir binarios", "Filtra imágenes, vídeos, etc.", true),
        SettingControl.Toggle("git_enabled", "Integración con Git", "Permite elegir rama o commit en la ventana de exportación", true),
    )

    override fun onChanged(key: String, values: PreferenceReader) {
        try {
            // 1. Obtener el contexto de Android
            val context = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context ?: return
            
            // 2. Leer el valor booleano usando reflexión (compatible con cualquier versión del SDK)
            val boolValue = readBooleanSafely(values, key)
            
            // 3. Guardar en SharedPreferences para que el diálogo lo use
            val prefs = context.getSharedPreferences("onecode_settings", Context.MODE_PRIVATE).edit()
            prefs.putBoolean(key, boolValue)
            prefs.apply()
            
        } catch (_: Exception) {
            // Silencioso si falla la reflexión
        }
    }

    /**
     * Intenta leer un booleano de PreferenceReader usando varios métodos posibles
     * para garantizar compatibilidad con distintas versiones del SDK.
     */
    private fun readBooleanSafely(reader: PreferenceReader, key: String): Boolean {
        return try {
            // Intento 1: método get(key, default: Boolean)
            val methodGet = reader.javaClass.methods.firstOrNull {
                it.name == "get" && it.parameterTypes.size == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            if (methodGet != null) {
                return methodGet.invoke(reader, key, true) as? Boolean ?: true
            }

            // Intento 2: método getBoolean(key)
            val methodGetBool = reader.javaClass.methods.firstOrNull {
                it.name == "getBoolean" && it.parameterTypes.size == 1
            }
            if (methodGetBool != null) {
                return methodGetBool.invoke(reader, key) as? Boolean ?: true
            }

            // Intento 3: método get(key) que devuelve Any?
            val methodGetAny = reader.javaClass.methods.firstOrNull {
                it.name == "get" && it.parameterTypes.size == 1
            }
            if (methodGetAny != null) {
                return methodGetAny.invoke(reader, key) as? Boolean ?: true
            }

            // Fallback: true
            true
        } catch (_: Exception) {
            true
        }
    }
}