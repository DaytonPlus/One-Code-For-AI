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

class OneCodeForAIPlugin : Plugin {

    private val dialogVisible = AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val rootCache = WeakHashMap<String, File>()

    override fun register(reg: PluginRegistration) {
        val logger = reg.logger(TAG)

        PluginLogger.init(logger)

        PluginLogger.i(TAG, "Plugin loaded and logger initialized.")

        val context = HostEnvironment.applicationContext()
        val actionLabel = context?.pluginString(R.string.action_label)
            ?: DEFAULT_ACTION_LABEL

        registerAction(reg, actionLabel) { message -> logger.info(message) }

        reg.register(SETTINGS_PAGE_EP, OneCodeForAISettingsPage())
    }

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

        val gitEnabled = context?.let { PluginSettings.isGitEnabled(it) } ?: true

        if (!gitEnabled) {
            val projectRoot = resolveProjectRoot(selectedCanonical, context)
            logInfo("Git disabled globally. Using project root: ${projectRoot.name}")
            return showConfigurationDialog(projectRoot, logInfo)
        }

        rootCache[selectedCanonical.absolutePath]?.let { cached ->
            logInfo("Using cached project root: ${cached.name}")
            return showConfigurationDialog(cached, logInfo)
        }

        if (!dialogVisible.compareAndSet(false, true)) {
            return ActionResult.message(message(R.string.dialog_already_open))
        }

        val activity = HostEnvironment.currentActivity()
        if (activity == null) {
            dialogVisible.set(false)
            return ActionResult.message(message(R.string.error_opening_dialog))
        }

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

        val gitEnabled = PluginSettings.isGitEnabled(activity)

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

                    gitEnabledGlobally = gitEnabled,
                    initialConfig = initialConfig,
                    logInfo = logInfo,
                    onDismiss = { dialogVisible.set(false) },
                    onChangeRoot = { newRoot ->

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

    private fun resolveProjectRoot(startDir: File, context: Context?): File {
        val limit = context?.let { PluginSettings.getParentSearchLimit(it) }
            ?: DEFAULT_PARENT_SEARCH_LIMIT

        findAncestorWithMarker(startDir, limit) { File(it, ".gitignore").isFile }?.let {
            return it
        }

        findCodeAssistProjectRoot(startDir)?.let { return it }

        return startDir
    }

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

        private const val DEFAULT_PARENT_SEARCH_LIMIT = 10
    }
}