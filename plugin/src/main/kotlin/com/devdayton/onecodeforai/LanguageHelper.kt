package com.devdayton.onecodeforai

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Detecta el idioma del IDE host (CodeAssist) y ofrece helpers para:
 *  - resolver strings del APK del plugin en el locale del IDE
 *  - aplicar el locale del IDE a un Context propio (p.ej. PluginInfoActivity)
 */
object LanguageHelper {

    /** Devuelve el locale del IDE. Si no se puede leer, cae al sistema. */
    fun detectIdeLocale(context: Context): Locale {
        // 1) La Activity del host (CodeAssist) tiene la configuración real del IDE
        val host = findHostActivity()
        if (host != null) {
            val config = host.resources.configuration
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.locales[0] ?: Locale.getDefault()
            } else {
                @Suppress("DEPRECATION")
                config.locale ?: Locale.getDefault()
            }
        }
        // 2) Fallback: el propio contexto (puede ser el del plugin) o el sistema
        val config = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0] ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            config.locale ?: Locale.getDefault()
        }
    }

    /**
     * Devuelve un Context "envuelto" con el locale del IDE. Útil en
     * attachBaseContext() de Activities propias del plugin.
     */
    fun wrapContextWithIdeLocale(base: Context): Context {
        val locale = detectIdeLocale(base)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Encuentra la Activity del host vía reflexión (mismo patrón que ya usa
     * OneCodeForAIPlugin.getCurrentActivity()).
     */
    fun findHostActivity(): Activity? = try {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = atClass.getMethod("currentActivityThread").invoke(null)
        val field = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val activities = field.get(at) as Map<*, *>
        var found: Activity? = null
        for (record in activities.values) {
            val actField = record?.javaClass?.getDeclaredField("activity")
            actField?.isAccessible = true
            val activity = actField?.get(record) as? Activity
            if (activity != null && !activity.isFinishing) { found = activity; break }
        }
        found
    } catch (_: Exception) { null }
}