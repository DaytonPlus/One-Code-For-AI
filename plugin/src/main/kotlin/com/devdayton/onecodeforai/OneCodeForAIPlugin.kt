package com.devdayton.onecodeforai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.PluginLogger
import com.devdayton.onecodeforai.android.PluginSettings
import com.devdayton.onecodeforai.core.GitUtils
import com.devdayton.onecodeforai.core.FilterConfig
import com.devdayton.onecodeforai.android.HostEnvironment
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import com.devdayton.onecodeforai.ui.ConfigurationDialog
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.UI_ACTION_EP
import dev.ide.platform.settings.SETTINGS_PAGE_EP
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Primary entry point for the One Code For AI plugin within the CodeAssist host.
 *
 * This class manages the lifecycle of the configuration flow, ensuring that
 * potentially blocking I/O operations (such as resolving a Git repository root)
 * are executed on background threads, while all UI interactions are safely
 * marshaled back to the main thread.
 *
 * ### Git Integration State Management
 * The plugin supports a global "kill switch" for Git functionality via
 * [PluginSettings.isGitEnabled]. When this switch is disabled:
 * 1. Repository detection via JGit is entirely bypassed to save resources.
 * 2. The selected folder is treated as the project root.
 * 3. The plugin attempts to locate an ancestor `.gitignore` file to maintain
 *    filtering integrity without requiring a full Git repository.
 *
 * Note: The `.gitignore` filtering mechanism is decoupled from Git repository
 * detection. It remains active as long as [FilterConfig.respectGitignore] is true,
 * regardless of the global Git state.
 *
 * ### Tree visibility
 * The interactive file-tree preview is always available in this version. The
 * `tree_enabled` preference that a previous build read here was never exposed
 * in the settings UI, so it was always `true`. Removing the dead branch keeps
 * the code honest; if a future version wants to add the toggle, it should be
 * added to [com.devdayton.onecodeforai.OneCodeForAISettingsPage] at the same
 * time as it is read here.
 *
 * ### Context-menu label refresh
 * The plugin's context-menu label is resolved from
 * [R.string.action_label] at registration time, using the effective locale
 * from [com.devdayton.onecodeforai.android.LanguageHelper.detectIdeLocale].
 * When the user changes the UI language from the settings page, the settings
 * page calls [refreshActionLabel], which re-registers the action with the
 * newly-localized label. Whether the host *actually* refreshes the visible
 * menu without an IDE restart is a host-side concern: some CodeAssist
 * versions treat `PluginRegistration.register(...)` for an already-registered
 * action ID as an in-place update, and others cache the label and only
 * re-read it on the next process start. [refreshActionLabel] performs the
 * best-effort update and logs a warning if the host throws, but it cannot
 * force a label refresh on hosts that do not support it. See the KDoc on
 * [refreshActionLabel] for the full rationale.
 */
class OneCodeForAIPlugin : Plugin {

    /**
     * Ensures that only one configuration dialog can be active at a time.
     * This prevents multiple concurrent invocations if the user rapidly taps the
     * action menu item.
     */
    private val dialogVisible = AtomicBoolean(false)

    /**
     * A handler bound to the main looper, used to marshal dialog creation and
     * UI updates back to the main thread.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * An in-memory cache mapping a selected directory path to its resolved
     * project root.
     *
     * Resolving a Git root involves traversing parent directories and reading
     * from the filesystem, which can be slow on network storage or deeply nested
     * structures. Caching the result prevents redundant lookups when the user
     * repeatedly opens the dialog for the same project during a session.
     *
     * Backed by [WeakHashMap] so that a long IDE session that opens many
     * projects does not accumulate entries for projects the rest of the
     * process has already forgotten. The values are [File] instances whose
     * only strong reference is this map, so they are collected together with
     * the key.
     */
    private val rootCache = WeakHashMap<String, File>()

    /**
     * Registers the plugin's components with the CodeAssist host.
     *
     * This method initializes the global [PluginLogger], registers the UI action
     * for generating context, and registers the settings page.
     *
     * The plugin stores a reference to itself, to the host's
     * [PluginRegistration], and to the host-logger callback in the companion
     * object so that [refreshActionLabel] can re-register the action later,
     * without requiring the caller to thread those objects through. See the
     * class-level *Context-menu label refresh* note for the full rationale.
     *
     * @param reg The [PluginRegistration] object provided by the host.
     */
    override fun register(reg: PluginRegistration) {
        val logger = reg.logger(TAG)

        // Wire the global logger to the host's logging system so that all
        // internal components can emit messages to the IDE console.
        PluginLogger.init(logger)

        PluginLogger.i(TAG, "Plugin loaded and logger initialized.")

        // Publish the plugin instance and registration to the companion so
        // refreshActionLabel can rebuild the action without the settings
        // page having to know about either. The log callback is stored
        // separately from the raw `logger` object so re-registration does
        // not need to re-resolve the host's logger API.
        pluginInstance = this
        activeRegistration = reg
        activeLogCallback = { message -> logger.info(message) }

        val context = HostEnvironment.applicationContext()
        val actionLabel = context?.pluginString(R.string.action_label)
            ?: DEFAULT_ACTION_LABEL

        registerAction(reg, actionLabel)

        reg.register(SETTINGS_PAGE_EP, OneCodeForAISettingsPage())
    }

    /**
     * Registers (or re-registers) the plugin's primary UI action with the
     * supplied [actionLabel].
     *
     * Extracted from [register] so that [refreshActionLabel] can reuse the
     * exact same [SimpleAction] construction (same ID, same places, same
     * icon) when the UI language changes. Keeping the construction in one
     * place is what guarantees that a language refresh cannot accidentally
     * drop the action from one of its registered places or change its
     * icon.
     *
     * The action's click handler is captured as a closure over the current
     * plugin instance and the supplied [activeLogCallback], not over
     * `reg` or a local `logger` variable. That is what makes the closure
     * stable across re-registrations: it does not depend on any state that
     * changes between calls.
     *
     * @param reg the host registration object to register the action with.
     * @param actionLabel localized label to display in the context menu and
     *   command palette. Resolved by the caller so this method stays
     *   locale-agnostic.
     */
    private fun registerAction(reg: PluginRegistration, actionLabel: String) {
        val logCallback = activeLogCallback ?: return
        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = ACTION_ID,
                text = actionLabel,
                places = setOf(ActionPlaces.FILE_CONTEXT, ActionPlaces.COMMAND_PALETTE),
                iconId = "sparkle",
            ) { actionContext ->
                startConfigurationFlow(actionContext, logCallback)
            }
        )
    }

    /**
     * Resolves the appropriate project root and initiates the configuration flow.
     *
     * This method implements a performance-conscious strategy by avoiding
     * filesystem I/O on the main thread. It follows three distinct execution paths
     * based on the global Git settings and cache state:
     *
     * 1. **Git Disabled:** Bypasses repository detection entirely. Uses the
     *    selected folder or its nearest `.gitignore` ancestor as the root.
     * 2. **Git Enabled, Cache Hit:** Uses a previously resolved Git root,
     *    executing immediately.
     * 3. **Git Enabled, Cache Miss:** Dispatches a background thread to perform
     *    the repository detection before opening the dialog.
     *
     * @param actionContext The context of the action invocation, containing the
     *                      selected path.
     * @param logInfo A callback to log informational messages to the host.
     * @return An [ActionResult] indicating the outcome of the action invocation.
     */
    private fun startConfigurationFlow(
        actionContext: ActionContext,
        logInfo: (String) -> Unit
    ): ActionResult {
        val context = HostEnvironment.applicationContext()
        fun message(resourceId: Int): String = context?.pluginString(resourceId).orEmpty()

        if (dialogVisible.get()) {
            return ActionResult.message(message(R.string.dialog_already_open))
        }

        val selectedPath = actionContext.contextPath
            ?: return ActionResult.message(message(R.string.no_folder_selected))

        var selected = File(selectedPath)
        if (selected.isFile) selected = selected.parentFile ?: selected
        if (!selected.isDirectory) {
            return ActionResult.message(message(R.string.select_valid_folder))
        }

        val selectedCanonical = runCatching { selected.canonicalFile }.getOrDefault(selected)

        // Evaluate the global Git switch. This determines whether we perform
        // expensive repository detection.
        val gitEnabled = context?.let { PluginSettings.isGitEnabled(it) } ?: true

        // Path 1: Git functionality is globally disabled.
        // We bypass Git detection entirely. However, to preserve .gitignore
        // filtering, we search for an ancestor .gitignore file to act as the root.
        if (!gitEnabled) {
            val projectRoot = findGitignoreRoot(selectedCanonical) ?: selectedCanonical
            logInfo("Git disabled globally. Using project root: ${projectRoot.name}")
            return showConfigurationDialog(projectRoot, logInfo)
        }

        // Path 2: Git is enabled and the root is cached.
        rootCache[selectedCanonical.absolutePath]?.let { cached ->
            logInfo("Using cached project root: ${cached.name}")
            return showConfigurationDialog(cached, logInfo)
        }

        // Path 3: Git is enabled but the root is unknown.
        // Reserve the dialog slot to prevent concurrent invocations.
        if (!dialogVisible.compareAndSet(false, true)) {
            return ActionResult.message(message(R.string.dialog_already_open))
        }

        val activity = HostEnvironment.currentActivity()
        if (activity == null) {
            dialogVisible.set(false)
            return ActionResult.message(message(R.string.error_opening_dialog))
        }

        // Dispatch repository detection to a background thread to prevent
        // blocking the UI.
        Thread({
            val projectRoot: File = runCatching { GitUtils.findGitRoot(selected) }
                .getOrNull()
                ?: selected

            rootCache[selectedCanonical.absolutePath] = projectRoot

            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) {
                    dialogVisible.set(false)
                    return@runOnUiThread
                }
                if (projectRoot.canonicalFile != selectedCanonical) {
                    logInfo("Auto-detected project root: ${projectRoot.name}")
                }
                try {
                    showConfigurationDialog(projectRoot, logInfo, alreadyReserved = true)
                } catch (error: Exception) {
                    dialogVisible.set(false)
                    logInfo(
                        "Failed to open configuration dialog: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                }
            }
        }, "OneCode-root-resolve").apply { isDaemon = true; start() }

        return ActionResult.message(message(R.string.opening_config))
    }

    /**
     * Constructs and displays the configuration dialog on the main thread.
     *
     * This method reads the current plugin settings to initialize the dialog's
     * state. It respects the [alreadyReserved] flag to maintain state consistency
     * across asynchronous root resolution.
     *
     * @param projectRoot The resolved root directory of the project.
     * @param logInfo A callback to log informational messages to the host.
     * @param alreadyReserved If true, indicates that the [dialogVisible] lock has
     *                        already been acquired by the caller.
     * @return An [ActionResult] indicating the outcome of the operation.
     */
    private fun showConfigurationDialog(
        projectRoot: File,
        logInfo: (String) -> Unit,
        alreadyReserved: Boolean = false
    ): ActionResult {
        val context = HostEnvironment.applicationContext()
        fun message(resourceId: Int): String = context?.pluginString(resourceId).orEmpty()

        val activity = HostEnvironment.currentActivity()
            ?: run {
                dialogVisible.set(false)
                return ActionResult.message(message(R.string.error_opening_dialog))
            }

        if (!alreadyReserved && !dialogVisible.compareAndSet(false, true)) {
            return ActionResult.message(message(R.string.dialog_already_open))
        }

        val preferences = context?.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)

        // Determine the runtime behavior of Git based on the global switch.
        val gitEnabled = PluginSettings.isGitEnabled(activity)

        val initialConfig = FilterConfig(
            excludeBinaries = preferences?.getBoolean(KEY_EXCLUDE_BINARIES, true) ?: true,
            respectGitignore = preferences?.getBoolean(KEY_RESPECT_GITIGNORE, true) ?: true,
        )

        mainHandler.post {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    dialogVisible.set(false)
                    return@post
                }

                ConfigurationDialog(
                    activity = activity,
                    projectRoot = projectRoot,
                    // The dialog uses this flag to decide whether to render the
                    // interactive Git controls or a static "disabled globally" notice.
                    gitEnabledGlobally = gitEnabled,
                    initialConfig = initialConfig,
                    logInfo = logInfo,
                    onDismiss = { dialogVisible.set(false) },
                    onChangeRoot = { newRoot ->
                        // The dialog self-dismisses before invoking this callback.
                        // We release the lock and schedule the new dialog.
                        dialogVisible.set(false)
                        mainHandler.postDelayed({
                            showConfigurationDialog(newRoot, logInfo)
                        }, ROOT_SWITCH_DELAY_MS)
                    },
                ).show()
            } catch (error: Exception) {
                dialogVisible.set(false)
                logInfo(
                    "Failed to open configuration dialog: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }
        }

        return ActionResult.message(message(R.string.opening_config))
    }

    /**
     * Searches for a `.gitignore` file in the specified directory and its
     * parent directories, up to a maximum depth.
     *
     * This method acts as a fallback mechanism to preserve `.gitignore` filtering
     * capabilities when Git is globally disabled. By identifying the directory
     * that contains the `.gitignore` file, we can correctly resolve relative paths
     * within the ignore rules, even if the user initially selected a subfolder.
     *
     * The search is intentionally bounded to prevent excessive directory traversal
     * on deeply nested or mistakenly configured paths.
     *
     * @param startDir The directory selected by the user.
     * @return The directory containing the `.gitignore`, or `null` if not found.
     */
    private fun findGitignoreRoot(startDir: File): File? {
        var current: File? = startDir
        var levels = 0
        val maxLevels = 5

        while (current != null && levels < maxLevels) {
            if (File(current, ".gitignore").isFile) {
                return current
            }
            current = current.parentFile
            levels++
        }
        return null
    }

    companion object {
        const val TAG = "OneCodeForAIPlugin"
        const val ACTION_ID = "com.devdayton.onecodeforai.dump"
        const val SETTINGS_FILE = "onecode_settings"
        const val KEY_RESPECT_GITIGNORE = "exclude_gitignored"
        const val KEY_EXCLUDE_BINARIES = "exclude_binaries"
        const val DEFAULT_ACTION_LABEL = "Generate Context TXT (Share/Export)"
        const val ROOT_SWITCH_DELAY_MS = 250L

        /**
         * Reference to the currently-active plugin instance.
         *
         * Set once in [register] and never cleared, because the host keeps
         * the plugin registered for the lifetime of the process. Stored on
         * the companion object so [refreshActionLabel] can invoke the
         * instance-scoped [registerAction] helper without the settings page
         * having to hold a plugin reference of its own.
         *
         * `@Volatile` because [refreshActionLabel] can be called from a
         * different thread than the one that ran [register]; in practice
         * both are the main thread, but the annotation makes the
         * visibility guarantee explicit and costs nothing.
         */
        @Volatile
        private var pluginInstance: OneCodeForAIPlugin? = null

        /**
         * The [PluginRegistration] the host handed to [register].
         *
         * Retained so [refreshActionLabel] can re-register the action with a
         * new label. The reference is process-scoped by design — the host
         * outlives the plugin, not the other way around — so there is no
         * leak here.
         */
        @Volatile
        private var activeRegistration: PluginRegistration? = null

        /**
         * Callback that forwards a message to the host's logger.
         *
         * Stored as a `(String) -> Unit` rather than as the raw host logger
         * object so that [registerAction] does not need to re-resolve the
         * host's logger API on every re-registration. The lambda is created
         * once in [register] and captures the host logger by closure.
         */
        @Volatile
        private var activeLogCallback: ((String) -> Unit)? = null

        /**
         * Re-registers the plugin's context-menu (and command-palette) action
         * with the label that matches the currently-selected UI language.
         *
         * ### Why this exists
         * The action's label is captured at registration time from
         * [R.string.action_label]. Because Android resolves that string
         * against the *current* locale, a user who changes the language
         * from the settings page would otherwise see the context menu
         * continue to display the old-language label until the next process
         * start. This method is the bridge: it is called from
         * [com.devdayton.onecodeforai.OneCodeForAISettingsPage.onChanged]
         * immediately after the new language code is persisted, and it
         * re-runs the same [SimpleAction] registration with a freshly
         * resolved label.
         *
         * ### What actually happens on the host side
         * Whether the visible menu label updates **without an IDE restart**
         * depends entirely on the host:
         *
         *  - Some CodeAssist versions treat a second
         *    `PluginRegistration.register(...)` for an already-registered
         *    action ID as an in-place update. On those builds, the label
         *    refreshes live, as the user expects.
         *  - Other versions cache the action and only re-read its label on
         *    the next process start. On those builds, the label in the
         *    context menu keeps showing the old language until the IDE is
         *    restarted, even though every other surface of the plugin
         *    (settings page, configuration dialog, info activity, result
         *    notifications, error messages) has already switched to the
         *    new language.
         *  - A third group may throw on the second registration because the
         *    ID is already in use. That case is caught and logged; it does
         *    not break anything, because the original registration remains
         *    in effect.
         *
         * There is no portable way to force the first case on a build that
         * implements the second, so this method deliberately does **not**
         * attempt to fake it (e.g. by unregistering and re-registering,
         * which some hosts do not support either, and which would briefly
         * remove the action from the menu). Doing the best-effort
         * re-registration and logging the outcome is the honest trade-off.
         *
         * ### Failure handling
         * Any exception thrown by the host's `register` call is caught and
         * logged at WARN level. The method never throws, because the
         * language preference has already been persisted by the time it is
         * called — a failure here must not roll back the user's choice or
         * surface as an error in the settings page.
         *
         * ### No-op conditions
         * Silently returns when any of the following is `null`:
         *  - [pluginInstance] — the plugin has not been registered yet, which
         *    can only happen if the settings page is somehow rendered before
         *    the plugin's `register` method runs.
         *  - [activeRegistration] — same.
         *  - [activeLogCallback] — same.
         *
         * None of these are reachable in normal CodeAssist flows (the plugin
         * is registered before the settings page can be opened), but
         * guarding them keeps the method safe to call from a defensive
         * `runCatching` block in the settings page.
         *
         * @param context any `Context`; only its resources are read, to
         *   resolve [R.string.action_label] against the plugin's currently
         *   selected locale. Using the host activity's context here would
         *   resolve against the *host's* resources and produce the wrong
         *   label — the settings page passes the application context, and
         *   [pluginString] internally re-resolves the label against the
         *   plugin's own locale-aware `Resources`, so either choice works.
         */
        fun refreshActionLabel(context: Context) {
            val plugin = pluginInstance ?: run {
                PluginLogger.d(
                    TAG,
                    "refreshActionLabel: plugin instance not available yet, skipping."
                )
                return
            }
            val reg = activeRegistration ?: run {
                PluginLogger.d(
                    TAG,
                    "refreshActionLabel: no active registration, skipping."
                )
                return
            }
            if (activeLogCallback == null) {
                PluginLogger.d(
                    TAG,
                    "refreshActionLabel: no log callback, skipping."
                )
                return
            }

            val newLabel = context.pluginString(R.string.action_label)
                .ifEmpty { DEFAULT_ACTION_LABEL }

            runCatching {
                plugin.registerAction(reg, newLabel)
                PluginLogger.i(TAG, "Context-menu label refreshed to: $newLabel")
            }.onFailure { error ->
                PluginLogger.w(
                    TAG,
                    "Could not refresh context-menu label. The old label will remain " +
                        "until the next IDE restart. Cause: " +
                        (error.message ?: error.javaClass.simpleName),
                    error
                )
            }
        }
    }
}