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
 * Primary entry point for the One Code For AI plugin within the CodeAssist
 * host.
 *
 * This class manages the lifecycle of the configuration flow, ensuring that
 * potentially blocking I/O operations (such as resolving a Git repository
 * root) are executed on background threads, while all UI interactions are
 * safely marshaled back to the main thread.
 *
 * ### Git Integration State Management
 *
 * The plugin supports a global "kill switch" for Git functionality via
 * PluginSettings.isGitEnabled. When this switch is disabled:
 *
 * 1. Repository detection via JGit is entirely bypassed to save resources.
 * 2. The selected folder is treated as the project root, after the parent
 *    search heuristic has had a chance to find a .gitignore or the
 *    CodeAssist project boundary.
 * 3. The plugin attempts to locate an ancestor .gitignore file to maintain
 *    filtering integrity without requiring a full Git repository.
 *
 * Note: the .gitignore filtering mechanism is decoupled from Git repository
 * detection. It remains active as long as FilterConfig.respectGitignore is
 * true, regardless of the global Git state.
 *
 * ### Project root resolution
 *
 * When the user opens the plugin from a file or folder that lives inside a
 * project, the plugin needs to walk up to the project root before it can
 * scan the tree or read the per-project config. The walk is performed by
 * [resolveProjectRoot] and is bounded by
 * PluginSettings.getParentSearchLimit, which the user can raise from the
 * settings page for very deep package trees or set to 0 to keep the
 * plugin strictly scoped to the selected folder.
 *
 * The heuristic has three stages:
 *
 *  1. Walk up to the configured limit looking for a .gitignore. This is
 *     the most accurate signal because the filtering rules the user
 *     maintains live next to it.
 *  2. If no .gitignore is found, look for the CodeAssist project boundary
 *     in the absolute path (the "/codeassist/projects/<name>/" segment
 *     that CodeAssist uses on Android). This covers projects that do not
 *     have a .gitignore yet but still have a well-defined root.
 *  3. If neither is found, return the selected folder unchanged, so the
 *     plugin never leaves the folder the user explicitly chose.
 *
 * The resolved root is then used consistently by the configuration dialog
 * for four things that must agree with each other:
 *
 *  - reading the .gitignore rules,
 *  - loading and saving the per-project config under
 *    .platform/plugins/com.devdayton.onecodeforai/config.json,
 *  - scanning the tree (relative paths are computed against this root),
 *  - and resolving the File objects the dump generator will read.
 *
 * ### Tree v2: project config is per-project
 *
 * Starting with Tree v2 the per-path selection made in the tree selector
 * is stored per project under
 * projectRoot/.platform/plugins/com.devdayton.onecodeforai/config.json and
 * is read by ConfigurationDialog when it opens. This entry point does not
 * need to know about that config: the dialog loads it, applies it to the
 * tree, and writes it back on Generate. The plugin class remains
 * responsible only for opening the dialog for the correct project root
 * and for coordinating the single-dialog lock.
 *
 * ### Context-menu label refresh
 *
 * The plugin's context-menu label is resolved from R.string.action_label
 * at registration time, using the effective locale from
 * LanguageHelper.detectIdeLocale. When the user changes the UI language
 * from the settings page, the label in the context menu does not update
 * until the next CodeAssist restart. This is a deliberate limitation:
 * PluginRegistration.register is additive, not replacing, so re-invoking
 * it to pick up a new label would duplicate the action in the menu. There
 * is no portable way to force an in-place update across CodeAssist builds,
 * so the plugin chooses not to re-register at all. Every other surface
 * (dialogs, settings page, info screen, notifications, error messages)
 * does pick up the new language on the next newly-opened window, matching
 * the standard Android behavior for runtime locale changes.
 */
class OneCodeForAIPlugin : Plugin {

    /**
     * Ensures that only one configuration dialog can be active at a time.
     * This prevents multiple concurrent invocations if the user rapidly
     * taps the action menu item.
     */
    private val dialogVisible = AtomicBoolean(false)

    /**
     * A handler bound to the main looper, used to marshal dialog creation
     * and UI updates back to the main thread.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * An in-memory cache mapping a selected directory path to its resolved
     * project root.
     *
     * Resolving a Git root involves traversing parent directories and
     * reading from the filesystem, which can be slow on network storage or
     * deeply nested structures. Caching the result prevents redundant
     * lookups when the user repeatedly opens the dialog for the same
     * project during a session.
     *
     * Backed by WeakHashMap so that a long IDE session that opens many
     * projects does not accumulate entries for projects the rest of the
     * process has already forgotten. The values are File instances whose
     * only strong reference is this map, so they are collected together
     * with the key.
     */
    private val rootCache = WeakHashMap<String, File>()

    /**
     * Registers the plugin's components with the CodeAssist host.
     *
     * This method initializes the global PluginLogger, registers the UI
     * action for generating context, and registers the settings page.
     *
     * @param reg The PluginRegistration object provided by the host.
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

        registerAction(reg, actionLabel) { message -> logger.info(message) }

        reg.register(SETTINGS_PAGE_EP, OneCodeForAISettingsPage())
    }

    /**
     * Registers the plugin's primary UI action with the supplied
     * actionLabel.
     *
     * Extracted from register so the construction of the SimpleAction (same
     * ID, same places, same icon) lives in a single place. The click
     * handler closes over the supplied logCallback, not over the host
     * logger object, which keeps the method usable from tests without
     * needing a real PluginRegistration.
     *
     * The method is called exactly once per process lifetime. It is
     * deliberately not re-invoked when the UI language changes: the host
     * treats each register(...) call as an addition rather than a
     * replacement, so re-registering would duplicate the action in the
     * context menu. See OneCodeForAISettingsPage for the user-facing
     * consequence (the label refreshes after the next IDE restart).
     *
     * @param reg the host registration object to register the action with.
     * @param actionLabel localized label to display in the context menu and
     *   command palette. Resolved by the caller so this method stays
     *   locale-agnostic.
     * @param logCallback forwards a message to the host's logger. Passed as
     *   a parameter rather than read from a field so this method has no
     *   hidden dependency on companion state.
     */
    private fun registerAction(
        reg: PluginRegistration,
        actionLabel: String,
        logCallback: (String) -> Unit
    ) {
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
     * Resolves the appropriate project root and initiates the
     * configuration flow.
     *
     * This method implements a performance-conscious strategy by avoiding
     * filesystem I/O on the main thread. It follows three distinct
     * execution paths based on the global Git settings and cache state:
     *
     * 1. Git Disabled: bypasses repository detection entirely. Uses the
     *    resolveProjectRoot heuristic (.gitignore search, then CodeAssist
     *    project boundary, then the selected folder itself).
     * 2. Git Enabled, Cache Hit: uses a previously resolved Git root,
     *    executing immediately.
     * 3. Git Enabled, Cache Miss: dispatches a background thread to perform
     *    the repository detection before opening the dialog.
     *
     * @param actionContext The context of the action invocation, containing
     *                      the selected path.
     * @param logInfo A callback to log informational messages to the host.
     * @return An ActionResult indicating the outcome of the action invocation.
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
        // We bypass Git detection entirely. The project root is resolved by
        // the heuristic (.gitignore search, CodeAssist project boundary,
        // fallback to the selected folder), bounded by the user-configured
        // parent search limit.
        if (!gitEnabled) {
            val projectRoot = resolveProjectRoot(selectedCanonical, context)
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
        // blocking the UI. When Git is enabled the detection tries JGit
        // first (which understands worktrees and submodules) and falls
        // back to the parent-search heuristic if JGit cannot find a
        // repository (e.g. a plain folder without a .git marker).
        Thread({
            val projectRoot: File = runCatching {
                GitUtils.findGitRoot(selected) ?: resolveProjectRoot(selected, context)
            }.getOrNull() ?: selected

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
     * This method reads the current plugin settings to initialize the
     * dialog's state. It respects the alreadyReserved flag to maintain
     * state consistency across asynchronous root resolution.
     *
     * ### Tree v2: overrides come from the dialog, not from here
     *
     * The per-path tree selection is loaded by ConfigurationDialog itself
     * from TreeConfigStore (projectRoot/.platform/plugins/com.devdayton.
     * onecodeforai/config.json). This method does not need to touch it: it
     * only passes the project root and the initial config, and the dialog
     * decides what to do with its own config file.
     *
     * @param projectRoot The resolved root directory of the project.
     * @param logInfo A callback to log informational messages to the host.
     * @param alreadyReserved If true, indicates that the dialogVisible lock
     *                        has already been acquired by the caller.
     * @return An ActionResult indicating the outcome of the operation.
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

        // Determine the runtime behavior of Git based on the global switch.
        val gitEnabled = PluginSettings.isGitEnabled(activity)

        // The initial config is intentionally minimal: the dialog resolves
        // the effective preset + filter config from the project's saved
        // config.json (Tree v2) and from the last-state fallback, and only
        // uses this one for the case where neither is available. The
        // previous implementation read exclude_binaries and
        // respect_gitignore from SharedPreferences here, but those keys
        // were never written by the settings page (only by the dialog's
        // own controls), so they always came back as their defaults and
        // were immediately overwritten by the resolved config anyway.
        val initialConfig = FilterConfig()

        mainHandler.post {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    dialogVisible.set(false)
                    return@post
                }

                ConfigurationDialog(
                    activity = activity,
                    projectRoot = projectRoot,
                    // The dialog uses this flag to decide whether to render
                    // the interactive Git controls or a static "disabled
                    // globally" notice.
                    gitEnabledGlobally = gitEnabled,
                    initialConfig = initialConfig,
                    logInfo = logInfo,
                    onDismiss = { dialogVisible.set(false) },
                    onChangeRoot = { newRoot ->
                        // The dialog self-dismisses before invoking this
                        // callback. We release the lock and schedule the
                        // new dialog.
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

    // ------------------------------------------------------------------
    // Project root resolution
    // ------------------------------------------------------------------

    /**
     * Resolves the project root for [startDir] using a three-step
     * heuristic.
     *
     * The heuristic exists because the plugin API does not expose the
     * host's Project model (it lives in the project-model module, which is
     * not a dependency of the plugin), so the root has to be inferred
     * from the filesystem. The inference is bounded by
     * PluginSettings.getParentSearchLimit so a deeply nested package tree
     * does not force an unbounded walk.
     *
     * 1. **.gitignore search.** Walks up to the configured limit looking
     *    for a directory that contains a .gitignore. This is the most
     *    accurate signal because the user's filtering rules live next to
     *    it, and its presence almost always implies "this is a project".
     * 2. **CodeAssist project boundary.** If no .gitignore is found,
     *    looks for the "/codeassist/projects/<name>/" segment in the
     *    absolute path. This covers projects that do not have a
     *    .gitignore yet but still have a well-defined root — the same
     *    segment is what the host uses to scope its own project view.
     * 3. **Fallback.** Returns [startDir] unchanged so the plugin never
     *    leaves the folder the user explicitly selected. This is
     *    important: opening the plugin on a folder outside any project
     *    should still produce a working dialog, just without the parent
     *    search finding anything.
     *
     * The resolved root is what ConfigurationDialog uses for four things
     * that must agree with each other: reading the .gitignore rules,
     * loading and saving the per-project config, scanning the tree
     * (relative paths are computed against this root), and resolving the
     * File objects the dump generator will read. If the four disagreed,
     * the per-path overrides saved under TreeOverrides would not match
     * the paths the scanner produced on the next open, and the config
     * would silently be lost.
     *
     * @param startDir the folder the user selected, already canonicalized.
     * @param context any Context; used only to read the parent search
     *   limit. May be null, in which case the default limit is used.
     * @return the resolved project root, never null.
     */
    private fun resolveProjectRoot(startDir: File, context: Context?): File {
        val limit = context?.let { PluginSettings.getParentSearchLimit(it) }
            ?: DEFAULT_PARENT_SEARCH_LIMIT

        // Step 1: .gitignore.
        findAncestorWithMarker(startDir, limit) { File(it, ".gitignore").isFile }?.let {
            return it
        }

        // Step 2: CodeAssist project boundary.
        findCodeAssistProjectRoot(startDir)?.let { return it }

        // Step 3: fallback.
        return startDir
    }

    /**
     * Walks up to [limit] parents of [startDir] and returns the first one
     * whose predicate holds, or null when the limit is reached or the
     * filesystem root is hit.
     *
     * The `levels <= limit` condition means the walk visits [startDir]
     * itself plus [limit] ancestors, which is the intuitive reading of
     * "search this folder and up to N parents". Setting [limit] to 0
     * therefore still checks [startDir] itself, but no parent — that is
     * the behaviour a user expects when they choose "never leave the
     * selected folder".
     *
     * @param startDir the folder to begin the walk from.
     * @param limit maximum number of parents to visit, after [startDir].
     * @param predicate the test to apply at each level.
     * @return the first matching ancestor, or null.
     */
    private fun findAncestorWithMarker(
        startDir: File,
        limit: Int,
        predicate: (File) -> Boolean
    ): File? {
        var current: File? = startDir
        var levels = 0
        while (current != null && levels <= limit) {
            if (predicate(current)) return current
            current = current.parentFile
            levels++
        }
        return null
    }

    /**
     * Detects the CodeAssist project boundary by looking for the segment
     * "/codeassist/projects/<name>" in the absolute path.
     *
     * The check is done on the normalized absolute path rather than by
     * walking parentFile repeatedly, because the layout uses a fixed depth
     * under /codeassist/projects/ and walking would be unnecessary. It
     * also naturally handles the Android and desktop layouts without
     * hardcoding the leading "/storage/emulated/0/Android/data/..." part,
     * since only the trailing segment is checked.
     *
     * Returns null when the pattern is not present, so the caller can
     * fall through to the selected folder unchanged.
     */
    private fun findCodeAssistProjectRoot(startDir: File): File? {
        val path = startDir.absolutePath.replace('\\', '/')
        val marker = "/codeassist/projects/"
        val index = path.indexOf(marker)
        if (index < 0) return null
        val afterMarker = path.substring(index + marker.length)
        val projectName = afterMarker.substringBefore('/')
        if (projectName.isBlank()) return null
        val rootPath = path.substring(0, index + marker.length + projectName.length)
        return File(rootPath)
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
         * Default parent search limit used when no Context is available to
         * read the user preference. Matches PluginSettings's default so a
         * null context produces the same behaviour as a fresh install.
         */
        private const val DEFAULT_PARENT_SEARCH_LIMIT = 10
    }
}