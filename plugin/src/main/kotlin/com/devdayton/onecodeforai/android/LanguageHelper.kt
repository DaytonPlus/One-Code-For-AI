package com.devdayton.onecodeforai.android

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.devdayton.onecodeforai.android.HostEnvironment
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

    /** Returns the host IDE activity through the centralized host adapter. */
    fun findHostActivity() = HostEnvironment.currentActivity()
}
