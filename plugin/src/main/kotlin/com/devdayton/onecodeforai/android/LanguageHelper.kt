package com.devdayton.onecodeforai.android

import android.content.Context
import android.content.res.Configuration
import com.devdayton.onecodeforai.android.HostEnvironment
import java.util.Locale

object LanguageHelper {

    @Volatile
    private var cachedIdeLocale: Locale? = null

    fun detectIdeLocale(context: Context): Locale {

        val selectedCode = PluginSettings.getUiLanguage(context)
        localeForLanguageCode(selectedCode)?.let { return it }

        cachedIdeLocale?.let { return it }

        val host = findHostActivity()
        if (host != null) {
            val locale = localeOf(host.resources.configuration)
            cachedIdeLocale = locale
            return locale
        }

        return localeOf(context.resources.configuration)
    }

    fun invalidateCache() {
        cachedIdeLocale = null
    }

    fun wrapContextWithIdeLocale(base: Context): Context {
        val locale = detectIdeLocale(base)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    fun findHostActivity() = HostEnvironment.currentActivity()

    private fun localeOf(config: Configuration): Locale =
        config.locales[0] ?: Locale.getDefault()

    @Suppress("DEPRECATION")
    private fun localeForLanguageCode(code: String): Locale? = when (code) {

        PluginSettings.LANGUAGE_AUTO -> null

        "en" -> Locale.ENGLISH
        "es" -> Locale("es", "")
        "fr" -> Locale.FRENCH
        "de" -> Locale.GERMAN
        "it" -> Locale.ITALIAN
        "pt" -> Locale("pt", "BR")
        "ru" -> Locale("ru", "")
        "pl" -> Locale("pl", "")
        "cs" -> Locale("cs", "")
        "ro" -> Locale("ro", "")
        "el" -> Locale("el", "")
        "tr" -> Locale("tr", "")
        "ar" -> Locale("ar", "")
        "hi" -> Locale("hi", "")
        "in" -> Locale("in", "ID")
        "vi" -> Locale("vi", "")
        "ja" -> Locale.JAPANESE
        "ko" -> Locale.KOREAN
        "zh" -> Locale("zh", "CH")

        "bn" -> Locale("bn", "")
        "ca" -> Locale("ca", "")
        "fa" -> Locale("fa", "")
        "he" -> Locale("he", "")
        "nl" -> Locale("nl", "")
        "sv" -> Locale("sv", "")
        "th" -> Locale("th", "")
        "uk" -> Locale("uk", "")

        else -> null
    }
}