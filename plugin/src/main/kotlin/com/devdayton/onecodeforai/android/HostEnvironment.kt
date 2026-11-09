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
 * **This is fragile by design.** `ActivityThread` is not part of the public
 * Android SDK; its field names (`mActivities`) have been stable for many
 * releases but are not contractually guaranteed and have already differed on
 * some OEM forks. Every operation here is wrapped in `runCatching` and logs a
 * `[WARN]` when reflection fails, so a breaking change on the host side shows
 * up in the IDE console instead of silently disabling every UI entry point.
 * Callers must handle the `null` return (e.g. surface a toast or bail out of
 * the flow) — there is no non-reflective fallback for this API surface.
 */
object HostEnvironment {

    /**
     * Returns the host application's `Context`, or `null` when the reflective
     * lookup fails.
     *
     * `ActivityThread.currentApplication()` already returns the `Application`
     * instance, which is itself a `Context`. Calling `.applicationContext` on
     * it (as a previous version did) is redundant — the extra call is removed.
     *
     * The returned instance is safe to retain beyond an Activity's lifetime
     * and is what [com.devdayton.onecodeforai.android.ExportCoordinator] uses
     * for notifications, `MediaStore` and share intents.
     */
    fun applicationContext(): Context? = runCatching {
        Class.forName(ACTIVITY_THREAD_CLASS)
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

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
     *
     * Failures (reflection unavailable, field renamed by an OEM fork, or the
     * host's `ActivityThread` not yet initialized) are logged once and
     * reported as `null`; see the class-level note about the fragility of
     * this API.
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