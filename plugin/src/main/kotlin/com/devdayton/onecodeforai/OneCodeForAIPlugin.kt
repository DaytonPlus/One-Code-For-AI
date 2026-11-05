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
     */
    private val rootCache = mutableMapOf<String, File>()

    /**
     * Registers the plugin's components with the CodeAssist host.
     *
     * This method initializes the global [PluginLogger], registers the UI action
     * for generating context, and registers the settings page.
     *
     * @param reg The [PluginRegistration] object provided by the host.
     */
    override fun register(reg: PluginRegistration) {
        val logger = reg.logger(TAG)
        
        // Wire the global logger to the host's logging system so that all
        // internal components can emit messages to the IDE console.
        PluginLogger.init(logger)
        
        PluginLogger.i(TAG, "Plugin loaded and logger initialized.")

        val context = HostEnvironment.applicationContext()
        val actionLabel = context?.pluginString(R.string.action_label)
            ?: DEFAULT_ACTION_LABEL

        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = ACTION_ID,
                text = actionLabel,
                places = setOf(ActionPlaces.FILE_CONTEXT, ActionPlaces.COMMAND_PALETTE),
                iconId = "sparkle",
            ) { actionContext ->
                startConfigurationFlow(actionContext) { message -> logger.info(message) }
            }
        )

        reg.register(SETTINGS_PAGE_EP, OneCodeForAISettingsPage())
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
            logInfo("🔒 Git disabled globally. Using project root: ${projectRoot.name}")
            return showConfigurationDialog(projectRoot, logInfo)
        }

        // Path 2: Git is enabled and the root is cached.
        rootCache[selectedCanonical.absolutePath]?.let { cached ->
            logInfo("📍 Using cached project root: ${cached.name}")
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
                    logInfo("📍 Auto-detected project root: ${projectRoot.name}")
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
        
        val treeEnabled = preferences?.getBoolean(KEY_TREE_ENABLED, true) ?: true
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
                    treeEnabledGlobally = treeEnabled,
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

    private companion object {
        const val TAG = "OneCodeForAIPlugin"
        const val ACTION_ID = "com.devdayton.onecodeforai.dump"
        const val SETTINGS_FILE = "onecode_settings"
        const val KEY_RESPECT_GITIGNORE = "exclude_gitignored"
        const val KEY_EXCLUDE_BINARIES = "exclude_binaries"
        const val KEY_TREE_ENABLED = "tree_enabled"
        const val DEFAULT_ACTION_LABEL = "Generate Context TXT (Share/Export)"
        const val ROOT_SWITCH_DELAY_MS = 250L
    }
}