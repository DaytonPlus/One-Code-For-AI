package com.devdayton.onecodeforai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.devdayton.onecodeforai.android.ExportAction
import com.devdayton.onecodeforai.android.ExportCoordinator
import com.devdayton.onecodeforai.android.HostEnvironment
import com.devdayton.onecodeforai.core.FilterConfigCodec
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import java.io.File

/**
 * Handles the completion notification's action buttons.
 *
 * Every action funnels through [ExportCoordinator.export] or
 * [ExportCoordinator.shareFile]; both paths keep [ExportStateHolder] in sync,
 * so any open [com.devdayton.onecodeforai.ui.ConfigurationDialog] updates
 * automatically.
 */
class ExportActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val rootPath = intent.getStringExtra(EXTRA_ROOT_PATH) ?: return
        val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: return
        val lastActionName = intent.getStringExtra(EXTRA_LAST_ACTION) ?: ExportAction.SAVE.name
        val fileUriString = intent.getStringExtra(EXTRA_FILE_URI)

        val root = File(rootPath)
        if (!root.isDirectory) {
            toast(context, "Export root no longer exists.")
            return
        }
        val config = FilterConfigCodec.fromJson(configJson)
        val lastAction = runCatching { ExportAction.valueOf(lastActionName) }
            .getOrDefault(ExportAction.SAVE)

        when (action) {
            ACTION_SHARE -> {
                val uri = fileUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
                val exists = uri != null && runCatching {
                    context.contentResolver.openInputStream(uri)?.close()
                    true
                }.getOrDefault(false)
                if (exists) {
                    runCatching { ExportCoordinator.shareFile(context, uri, "OneCodeForAI_Share_Latest.txt") }
                        .onFailure { toast(context, it.message ?: "Share failed") }
                } else {
                    // File was deleted; regenerate as SHARE.
                    reRunExport(context, root, config, ExportAction.SHARE)
                }
            }
            ACTION_SAVE -> reRunExport(context, root, config, ExportAction.SAVE)
            ACTION_REGENERATE -> reRunExport(context, root, config, lastAction)
        }
    }

    private fun reRunExport(context: Context, root: File, config: com.devdayton.onecodeforai.core.FilterConfig, action: ExportAction) {
        val coordinator = ExportCoordinator(
            contextProvider = { HostEnvironment.applicationContext() ?: context.applicationContext },
            logger = { msg -> println("[ExportActionReceiver] $msg") }
        )
        if (!coordinator.export(root, config, action)) {
            toast(context, context.pluginString(R.string.export_already_running))
        }
    }

    private fun toast(context: Context, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION_SHARE = "com.devdayton.onecodeforai.action.SHARE"
        const val ACTION_SAVE = "com.devdayton.onecodeforai.action.SAVE"
        const val ACTION_REGENERATE = "com.devdayton.onecodeforai.action.REGENERATE"

        const val EXTRA_FILE_URI = "file_uri"
        const val EXTRA_ROOT_PATH = "root_path"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_LAST_ACTION = "last_action"
    }
}