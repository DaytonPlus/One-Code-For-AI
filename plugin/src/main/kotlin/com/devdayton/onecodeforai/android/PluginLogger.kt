package com.devdayton.onecodeforai.android

import android.content.pm.ApplicationInfo

object PluginLogger {

    private const val FORCE_DEBUG = false

    private var hostLogger: Any? = null

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

            false
        }
    }

    fun init(logger: Any?) {
        hostLogger = logger
    }

    fun d(tag: String, message: String) {
        if (isDebug) logToHost("info", tag, message, null)
    }

    fun i(tag: String, message: String) {
        if (isDebug) logToHost("info", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (isDebug) logToHost("warn", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (isDebug) logToHost("error", tag, message, throwable)
    }

    private fun logToHost(level: String, tag: String, message: String, throwable: Throwable?) {
        val logger = hostLogger ?: return

        val finalMessage = "[$tag] $message"

        try {

            if (throwable != null) {
                val methodWithThrowable = logger.javaClass.methods.firstOrNull {
                    it.name.equals(level, ignoreCase = true) &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        Throwable::class.java.isAssignableFrom(it.parameterTypes[1])
                }
                if (methodWithThrowable != null) {
                    methodWithThrowable.invoke(logger, finalMessage, throwable)
                    return
                }
            }

            val enrichedMessage = if (throwable != null) {
                "$finalMessage | ${throwable.javaClass.simpleName}: ${throwable.message}"
            } else {
                finalMessage
            }
            val methodStringOnly = logger.javaClass.methods.firstOrNull {
                it.name.equals(level, ignoreCase = true) &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            }
            methodStringOnly?.invoke(logger, enrichedMessage)
        } catch (_: Exception) {

        }
    }
}