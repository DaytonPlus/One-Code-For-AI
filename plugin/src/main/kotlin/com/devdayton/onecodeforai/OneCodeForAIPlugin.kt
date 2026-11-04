package com.devdayton.onecodeforai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.devdayton.onecodeforai.R
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
 * CodeAssist entry point.
 *
 * [PERF] `startConfigurationFlow` now resolves the Git root on a background
 * thread. Previously it called `GitUtils.findGitRoot(selected)` on the UI
 * thread, which could block for hundreds of milliseconds on slow storage or
 * when the selected folder sits deep inside a large repository.
 *
 * The dialog is only opened on the main thread after the root is resolved,
 * and `dialogVisible` is always released if the background work fails.
 */
class OneCodeForAIPlugin : Plugin {

    private val dialogVisible = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // [PERF] Optional cache of the resolved project root, keyed by the selected
    // path. Avoids repeating `findGitRoot` when the user opens the dialog
    // repeatedly on the same project.
    private val rootCache = mutableMapOf<String, File>()

    override fun register(reg: PluginRegistration) {
        val logger = reg.logger(TAG)
        logger.info("loaded")

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
     * Resolves the selected file/folder and opens the configuration flow.
     *
     * [PERF] The potentially expensive `GitUtils.findGitRoot` call runs on a
     * background thread. The dialog is built on the main thread only after the
     * root is known.
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

        // [PERF] `contextPath` is the canonical field in the plugin API.
        // If it is null, fail early without touching the disk.
        val selectedPath = actionContext.contextPath
            ?: return ActionResult.message(message(R.string.no_folder_selected))

        var selected = File(selectedPath)
        if (selected.isFile) selected = selected.parentFile ?: selected
        if (!selected.isDirectory) {
            return ActionResult.message(message(R.string.select_valid_folder))
        }

        // [PERF] Resolve the Git root off the UI thread.
        // `findGitRoot` walks parent directories and touches the filesystem,
        // which is fast on local storage but can block on network-backed
        // directories or very deep trees.
        val selectedCanonical = runCatching { selected.canonicalFile }.getOrDefault(selected)
        rootCache[selectedCanonical.absolutePath]?.let { cached ->
            logInfo("📍 Raíz del proyecto (cacheada): ${cached.name}")
            return showConfigurationDialog(cached, logInfo)
        }

        // Reserve the dialog slot now to prevent concurrent taps while the
        // background root resolution is running.
        if (!dialogVisible.compareAndSet(false, true)) {
            return ActionResult.message(message(R.string.dialog_already_open))
        }

        val activity = HostEnvironment.currentActivity()
        if (activity == null) {
            dialogVisible.set(false)
            return ActionResult.message(message(R.string.error_opening_dialog))
        }

        Thread({
            // `findGitRoot` returns null when no repository is found, so fall back
            // to the user-selected folder. `getOrNull() ?: selected` keeps the
            // type non-null through the whole chain.
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
                    logInfo("📍 Project root auto-detected: ${projectRoot.name}")
                }
                try {
                    showConfigurationDialog(projectRoot, logInfo, alreadyReserved = true)
                } catch (error: Exception) {
                    dialogVisible.set(false)
                    logInfo(
                        "Unable to open configuration dialog: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                }
            }
        }, "OneCode-root-resolve").apply { isDaemon = true; start() }


        return ActionResult.message(message(R.string.opening_config))
    }

    /**
     * Creates the configuration screen on the main thread.
     *
     * [PERF] `alreadyReserved` lets the caller keep the `dialogVisible` slot
     * across the background root resolution. The flag is cleared by
     * `onDismiss` when the dialog closes.
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
        val gitEnabled = preferences?.getBoolean(KEY_GIT_ENABLED, true) ?: true
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
                    gitEnabledGlobally = gitEnabled,
                    treeEnabledGlobally = treeEnabled,
                    initialConfig = initialConfig,
                    logInfo = logInfo,
                    onDismiss = { dialogVisible.set(false) },
                    onChangeRoot = { newRoot ->
                        // The current dialog dismisses itself before this callback is invoked.
                        dialogVisible.set(false)
                        mainHandler.postDelayed({
                            showConfigurationDialog(newRoot, logInfo)
                        }, ROOT_SWITCH_DELAY_MS)
                    },
                ).show()
            } catch (error: Exception) {
                dialogVisible.set(false)
                logInfo(
                    "Unable to open configuration dialog: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }
        }

        return ActionResult.message(message(R.string.opening_config))
    }

    private companion object {
        const val TAG = "OneCodeForAIPlugin"
        const val ACTION_ID = "com.devdayton.onecodeforai.dump"
        const val SETTINGS_FILE = "onecode_settings"
        const val KEY_RESPECT_GITIGNORE = "exclude_gitignored"
        const val KEY_EXCLUDE_BINARIES = "exclude_binaries"
        const val KEY_GIT_ENABLED = "git_enabled"
        const val KEY_TREE_ENABLED = "tree_enabled"
        const val DEFAULT_ACTION_LABEL = "Generate Context TXT (Share/Export)"
        const val ROOT_SWITCH_DELAY_MS = 250L
    }
}