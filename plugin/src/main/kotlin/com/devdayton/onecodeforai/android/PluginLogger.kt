package com.devdayton.onecodeforai.android

import android.content.pm.ApplicationInfo

/**
 * Single source of truth for every runtime-visible preference owned by the plugin.
 *
 * ### Overview
 * CodeAssist provides a built-in logger that outputs directly to the IDE console.
 * Unlike Android's Log class (which is often hidden in release builds), the host
 * logger works regardless of the build type. This utility acts as a global wrapper
 * around the host logger, allowing any class in the plugin to emit messages
 * without needing the host logger instance passed down through every constructor.
 *
 * ### When does it output logs?
 * Logs are printed to the IDE console under two conditions:
 * 1. **Manual Override:** [FORCE_DEBUG] is explicitly set to `true`.
 * 2. **Host Debug Build:** The host application (CodeAssist) is running as a
 *    debuggable build (detected via `ApplicationInfo.FLAG_DEBUGGABLE`).
 *
 * Because CodeAssist is usually installed by users as a release APK, relying
 * solely on the host's debuggable flag would hide logs during plugin development.
 * Therefore, [FORCE_DEBUG] acts as a master switch.
 *
 * > **Important:** Remember to set [FORCE_DEBUG] to `false` before publishing a
 * > production release of the plugin to prevent IDE console pollution.
 *
 * ### Tagging Strategy
 * Every log message is prefixed with the provided tag in brackets.
 * Example output: [Settings] READ [git_enabled] -> false
 *
 * ### Usage Example
 * ```kotlin
 * PluginLogger.d("ExportWorker", "Starting export for folder: $folderName")
 * PluginLogger.e("GitUtils", "Failed to resolve ref", exception)
 */
 
object PluginLogger {
    
    /**
     * Master switch for development environments.
     *
     * When `true`, all logs are printed regardless of whether the host app is
     * running in a debuggable state. This is essential for testing plugins
     * against release builds of CodeAssist.
     *
     * **Set to `false` for production releases.**
     */
    private const val FORCE_DEBUG = true

    /**
     * The logger instance provided by CodeAssist during plugin registration.
     *
     * Stored as Any to avoid strict compile-time dependency on the host's
     * specific logger interface, which may change between CodeAssist versions.
     * Method invocation is handled safely via reflection.
     */
    private var hostLogger: Any? = null

    /**
     * Determines if logging is currently enabled.
     *
     * This value is computed lazily once per process lifetime to avoid the
     * overhead of checking `ApplicationInfo` on every single log call.
     *
     * Resolution order:
     * 1. If [FORCE_DEBUG] is true, returns `true` immediately.
     * 2. Otherwise, checks if the host application's context is available.
     * 3. If available, checks `ApplicationInfo.FLAG_DEBUGGABLE`.
     * 4. If context is null or an exception occurs, defaults to `false` (fail-safe).
     */
    private val isDebug: Boolean by lazy {
        if (FORCE_DEBUG) return@lazy true
        try {
            val context = HostEnvironment.applicationContext()
            if (context != null) {
                (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            } else {
                false
            }
        } catch (_: Exception) {
            // Fail-safe: if we can't determine the state, stay quiet to avoid
            // leaking internal data in unexpected environments.
            false
        }
    }

    /**
     * Initializes the global logger with the instance provided by CodeAssist.
     *
     * This must be called exactly once, inside OneCodeForAIPlugin.register().
     *
     * @param logger The logger instance retrieved via reg.logger(TAG).
     */
    fun init(logger: Any?) {
        hostLogger = logger
    }

    // ------------------------------------------------------------------
    // Public Logging Methods
    // ------------------------------------------------------------------

    /**
     * Logs a debug message.
     *
     * Use this for detailed flow tracking, variable states, or branch execution
     * that is only relevant during active development. Maps to the host's
     * "info" level to ensure visibility in the IDE console.
     *
     * @param tag Short identifier for the source component (e.g., "Settings").
     * @param message The message to log.
     */
    fun d(tag: String, message: String) {
        if (isDebug) logToHost("info", tag, message, null)
    }

    /**
     * Logs an informational message.
     *
     * Use this for high-level lifecycle events (e.g., "Export started",
     * "Dialog initialized") that explain what the plugin is doing without
     * overwhelming the logs.
     *
     * @param tag Short identifier for the source component.
     * @param message The message to log.
     */
    fun i(tag: String, message: String) {
        if (isDebug) logToHost("info", tag, message, null)
    }

    /**
     * Logs a warning message.
     *
     * Use this for non-fatal issues that indicate something unexpected happened
     * but the app can recover (e.g., a reflection fallback was used, a file was
     * skipped, or a preference was missing).
     *
     * @param tag Short identifier for the source component.
     * @param message The message to log.
     * @param throwable Optional exception to include the stack trace.
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (isDebug) logToHost("warn", tag, message, throwable)
    }

    /**
     * Logs an error message.
     *
     * Use this for exceptions, critical failures, or states that prevent the
     * plugin from completing its task (e.g., failed to write the export file).
     *
     * @param tag Short identifier for the source component.
     * @param message The message to log.
     * @param throwable Optional exception to include the stack trace.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (isDebug) logToHost("error", tag, message, throwable)
    }

    // ------------------------------------------------------------------
    // Internal Helpers
    // ------------------------------------------------------------------

    /**
     * Core logging implementation that invokes the host logger via reflection.
     *
     * The host logger API may change slightly between CodeAssist versions.
     * By using reflection to look for methods named "info", "warn", or "error"
     * that accept a single String, we ensure compatibility across versions.
     *
     * If a throwable is provided, its class name and message are appended to
     * the log string, since the host logger may not accept a Throwable directly.
     *
     * @param level The host logger method name ("info", "warn", "error").
     * @param tag The raw tag to include in brackets.
     * @param message The message to log.
     * @param throwable An optional exception whose details will be appended.
     */
    private fun logToHost(level: String, tag: String, message: String, throwable: Throwable?) {
        val logger = hostLogger ?: return

        val finalMessage = if (throwable != null) {
            "[$tag] $message | Exception: ${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            "[$tag] $message"
        }

        try {
            val method = logger.javaClass.methods.firstOrNull {
                it.name.equals(level, ignoreCase = true) &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
            }
            method?.invoke(logger, finalMessage)
        } catch (_: Exception) {
            // Fail silently to prevent logging from crashing the plugin
        }
    }
}