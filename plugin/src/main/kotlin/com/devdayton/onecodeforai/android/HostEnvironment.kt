package com.devdayton.onecodeforai.android

import android.app.Activity
import android.content.Context

/**
 * Centralized access to the CodeAssist host process.
 *
 * The plugin API does not hand an `Application` or `Activity` reference to
 * plain entry points (a `Plugin` registers actions but is not invoked with a
 * `Context`). The only way to reach the host at runtime is the hidden
 * `android.app.ActivityThread`, which is exposed here behind a small, typed
 * facade so no other class has to repeat the reflection dance.
 *
 * All operations are best-effort: on Android versions or host builds where the
 * reflective calls fail, they return `null` rather than throwing. Callers are
 * expected to handle the null case (e.g. surface a toast or bail out of the
 * flow).
 */
object HostEnvironment {

    /**
     * Returns the host application's `Context`, or `null` when the reflective
     * lookup fails.
     *
     * The result is the *application* context (already unwrapped from the
     * `ActivityThread`), which is safe to retain beyond an Activity's lifetime
     * and is what [com.devdayton.onecodeforai.android.ExportCoordinator] uses
     * for notifications, `MediaStore` and share intents.
     */
    fun applicationContext(): Context? = runCatching {
        Class.forName(ACTIVITY_THREAD_CLASS)
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()?.applicationContext

    /**
     * Returns the foreground host activity, or `null` when the IDE is not
     * currently showing one.
     *
     * The lookup walks the `mActivities` map exposed by `ActivityThread` and
     * returns the first entry whose `Activity` is neither finishing nor
     * destroyed. That is the closest available approximation of "the activity
     * the user is looking at" without depending on the host's internal
     * foreground-tracking logic.
     *
     * Used by [com.devdayton.onecodeforai.ui.ConfigurationDialog] to build its
     * views and by [com.devdayton.onecodeforai.OneCodeForAIPlugin] to post the
     * dialog-creation runnable. Returning `null` here means the plugin cannot
     * open UI right now; callers should defer or notify the user.
     */
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