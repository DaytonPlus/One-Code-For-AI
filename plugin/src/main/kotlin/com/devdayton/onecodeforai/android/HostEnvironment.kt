package com.devdayton.onecodeforai.android

import android.app.Activity
import android.content.Context

/** Isolates the reflection required to access CodeAssist's host Application/Activity. */
object HostEnvironment {

    fun applicationContext(): Context? = runCatching {
        Class.forName(ACTIVITY_THREAD_CLASS)
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()?.applicationContext

    /** Returns the first usable host Activity, or null when the IDE is not foregrounded. */
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
    }.getOrNull()

    private const val ACTIVITY_THREAD_CLASS = "android.app.ActivityThread"
}
