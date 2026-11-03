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
 * This class is deliberately limited to host integration and orchestration. File traversal,
 * Git operations, export and Android delivery live in independent components.
 *
 * Export orchestration is *not* done here: [ConfigurationDialog] owns its own
 * [com.devdayton.onecodeforai.android.ExportCoordinator] and publishes progress
 * through [com.devdayton.onecodeforai.android.ExportStateHolder]. This entry
 * point just resolves the target folder and opens the configuration screen.
 */
class OneCodeForAIPlugin : Plugin {

    private val dialogVisible = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /** Resolves the selected file/folder and opens the configuration flow. */
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

        // Prefer the repository root so Git history and ignore rules cover the whole project.
        val projectRoot = GitUtils.findGitRoot(selected) ?: selected
        if (projectRoot.canonicalFile != selected.canonicalFile) {
            logInfo("📍 Raíz del proyecto detectada automáticamente: ${projectRoot.name}")
        }

        return showConfigurationDialog(projectRoot, logInfo)
    }

    /**
     * Creates the configuration screen on the main thread and prevents duplicate
     * dialog instances. Export is driven entirely by the dialog itself, so this
     * method no longer wires an [com.devdayton.onecodeforai.android.ExportCoordinator]
     * callback — it only forwards [logInfo] so the dialog can log through the
     * plugin's logger.
     */
    private fun showConfigurationDialog(
        projectRoot: File,
        logInfo: (String) -> Unit
    ): ActionResult {
        val context = HostEnvironment.applicationContext()
        fun message(resourceId: Int): String = context?.pluginString(resourceId).orEmpty()

        val activity = HostEnvironment.currentActivity()
            ?: return ActionResult.message(message(R.string.error_opening_dialog))

        if (!dialogVisible.compareAndSet(false, true)) {
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
                logInfo("Unable to open configuration dialog: ${error.message ?: error.javaClass.simpleName}")
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