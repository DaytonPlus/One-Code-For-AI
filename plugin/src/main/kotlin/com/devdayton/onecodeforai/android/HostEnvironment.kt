package com.devdayton.onecodeforai.android

import android.app.Activity
import android.content.Context

object HostEnvironment {

    fun applicationContext(): Context? = runCatching {
        Class.forName(ACTIVITY_THREAD_CLASS)
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

    fun currentActivity(): Activity? = runCatching {
        val activityThreadClass = Class.forName(ACTIVITY_THREAD_CLASS)
        val activityThread = activityThreadClass
            .getMethod("currentActivityThread")
            .invoke(null)
        val activitiesField = activityThreadClass.getDeclaredField("mActivities").apply {
            isAccessible = true
        }
        val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null

        activities.values.firstNotNullOfOrNull { record ->
            val activityField = record?.javaClass?.getDeclaredField("activity") ?: return@firstNotNullOfOrNull null
            activityField.isAccessible = true
            (activityField.get(record) as? Activity)?.takeIf { activity ->
                !activity.isFinishing && !activity.isDestroyed
            }
        }
    }.onFailure { error ->
        PluginLogger.w(
            "HostEnvironment",
            "Failed to resolve current activity via ActivityThread reflection; " +
                "UI entry points may be unavailable on this host build.",
            error
        )
    }.getOrNull()

    private const val ACTIVITY_THREAD_CLASS = "android.app.ActivityThread"
}