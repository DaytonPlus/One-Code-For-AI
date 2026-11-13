package com.devdayton.onecodeforai.ui

import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.ExportAction
import com.devdayton.onecodeforai.android.ExportCoordinator
import com.devdayton.onecodeforai.android.ExportStateHolder
import com.devdayton.onecodeforai.android.LanguageHelper
import com.devdayton.onecodeforai.android.PluginSettings
import com.devdayton.onecodeforai.core.FileFilter
import com.devdayton.onecodeforai.core.GitUtils
import com.devdayton.onecodeforai.core.FilterConfig
import com.devdayton.onecodeforai.core.NodeState
import com.devdayton.onecodeforai.core.PresetManager
import com.devdayton.onecodeforai.core.TreeConfigStore
import com.devdayton.onecodeforai.core.TreeOverrides
import com.devdayton.onecodeforai.ui.FlowLayout
import com.devdayton.onecodeforai.ui.UiFactory
import com.devdayton.onecodeforai.ui.CodeAssistTheme
import com.devdayton.onecodeforai.ui.ProjectTreeView
import com.devdayton.onecodeforai.ui.GitDiffPreviewDialog
import com.devdayton.onecodeforai.ui.GitReferencePickerDialog

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.devdayton.onecodeforai.ui.UiFactory.actionText
import com.devdayton.onecodeforai.ui.UiFactory.checkBox
import com.devdayton.onecodeforai.ui.UiFactory.editText
import com.devdayton.onecodeforai.ui.UiFactory.iconButton
import com.devdayton.onecodeforai.ui.UiFactory.loadPluginDrawable
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import com.devdayton.onecodeforai.ui.UiFactory.pluginQuantityString
import com.devdayton.onecodeforai.ui.UiFactory.sectionTitle
import com.devdayton.onecodeforai.ui.UiFactory.spinnerAdapter
import com.devdayton.onecodeforai.ui.UiFactory.textView

class ConfigurationDialog(
    private val activity: Activity,
    private val projectRoot: java.io.File,
    private val gitEnabledGlobally: Boolean,
    private val initialConfig: FilterConfig = FilterConfig(),
    private val logInfo: (String) -> Unit = {},
    private val onDismiss: () -> Unit = {},
    private val onChangeRoot: (java.io.File) -> Unit = {},
) {
    private val presetManager = PresetManager(activity)
    private var activeDialog: Dialog? = null
    private var currentConfig = FilterConfig()
    private var currentOverrides = TreeOverrides()
    private var currentPresetName: String = ""
    private var isCustomMode = false
    private var isUpdatingUI = false
    private var gitignoreTags: List<String> = emptyList()

    private var currentStatesCache: Map<String, NodeState> = emptyMap()

    private var uiBuilt = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rescanScheduled = false

    private val animationsEnabled: Boolean
        get() = PluginSettings.areAnimationsEnabled(activity)

    private lateinit var spinnerPresets: Spinner
    private lateinit var presetAdapter: ArrayAdapter<String>
    private lateinit var btnSavePreset: ImageButton
    private lateinit var btnDeletePreset: ImageButton

    private lateinit var cbBinaries: CheckBox
    private lateinit var cbMaxSize: CheckBox
    private lateinit var etMaxSize: EditText

    private lateinit var cbInclusions: CheckBox
    private lateinit var cbExclusions: CheckBox
    private lateinit var cbStubPatterns: CheckBox
    private lateinit var cbGitignore: CheckBox
    private lateinit var flowInclusions: FlowLayout
    private lateinit var flowExclusions: FlowLayout
    private lateinit var flowStubPatterns: FlowLayout
    private lateinit var flowGitignore: FlowLayout
    private lateinit var btnAddInclusion: TextView
    private lateinit var btnAddExclusion: TextView
    private lateinit var btnAddStubPattern: TextView

    private lateinit var btnEmptyInclusions: TextView
    private lateinit var btnEmptyExclusions: TextView
    private lateinit var btnEmptyStubPattern: TextView

    private lateinit var cbUseGit: CheckBox
    private lateinit var etGitRef: EditText
    private lateinit var tvResolvedRef: TextView
    private lateinit var tvGitInfo: TextView
    private lateinit var tvGitWarning: TextView
    private lateinit var gitDetailsBox: LinearLayout
    private lateinit var gitContentContainer: LinearLayout
    private val resolveHandler = Handler(Looper.getMainLooper())
    private var pendingResolve: Runnable? = null

    private var treeVisible = false
    private lateinit var cbShowTree: CheckBox
    private lateinit var cbShowLocked: CheckBox
    private lateinit var treeActionsRow: LinearLayout
    private lateinit var treeWrapper: FrameLayout
    private lateinit var treeView: ProjectTreeView
    private lateinit var treeLoadingPanel: LinearLayout
    private lateinit var treeLoadingProgress: ProgressBar
    private lateinit var treeLoadingText: TextView
    private lateinit var treeLoadingCancelButton: Button
    private lateinit var treeViewContainer: View

    private lateinit var configContainer: LinearLayout
    private lateinit var progressContainer: LinearLayout
    private lateinit var progressIcon: ImageView
    private lateinit var progressTitle: TextView
    private lateinit var progressSubtitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressMinimizeHint: TextView
    private lateinit var progressStats: TextView
    private lateinit var progressCancelBtn: Button
    private lateinit var progressActionsRow: LinearLayout
    private lateinit var progressBackLink: TextView
    private var stateUnsubscribe: (() -> Unit)? = null

    private val exportCoordinator: ExportCoordinator by lazy {
        ExportCoordinator(
            contextProvider = com.devdayton.onecodeforai.android.HostEnvironment::applicationContext,
            logger = { message -> logInfo(message) }
        )
    }

    private val tagViews = mutableMapOf<String, TextView>()

    private enum class TreeUiState { LOADING, TREE, ERROR }

    private fun customPresetLabel(): String = activity.pluginString(R.string.custom_preset)

    private fun resolveInitialPresetIndex(lastStatePreset: String?): Int {
        val customLabel = customPresetLabel()
        return when {
            lastStatePreset == null -> 0
            lastStatePreset == customLabel -> 0
            lastStatePreset == "Personalizado" -> 0
            lastStatePreset == "Custom" -> 0
            else -> presetAdapter.getPosition(lastStatePreset).takeIf { it >= 0 } ?: 0
        }
    }

    fun show() {

        CodeAssistTheme.applyTheme(activity)
        requestLegacyStoragePermissionIfNeeded()

        val dialog = Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCancelable(true)
        }
        activeDialog = dialog

        dialog.setOnDismissListener { handleDismiss(dialog) }
        dialog.setContentView(buildLoadingView())
        configureDialogWindow(dialog)
        dialog.show()

        Thread({
            val gitignore = runCatching { FileFilter(projectRoot).loadGitignoreRules() }
                .getOrDefault(emptyList())
            val projectConfig = runCatching { TreeConfigStore.load(projectRoot) }.getOrNull()
            activity.runOnUiThread {
                if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                gitignoreTags = gitignore
                buildFullUI(dialog, projectConfig)
            }
        }, "OneCode-dialog-init").apply { isDaemon = true; start() }
    }

    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val alreadyGranted = activity.checkSelfPermission(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) return
        runCatching {
            activity.requestPermissions(
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERMISSION
            )
        }
    }

    private fun buildLoadingView(): View {
        val outer = FrameLayout(activity).apply {
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.BG, 15f, CodeAssistTheme.OUTLINE
            )
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleColumn.addView(skeletonBar(dp(140), dp(16), radius = 4f))
        titleColumn.addView(skeletonBar(dp(200), dp(10), radius = 3f, topMargin = dp(8)))
        header.addView(titleColumn)
        header.addView(ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        content.addView(header)

        content.addView(View(activity).apply {
            setBackgroundColor(CodeAssistTheme.OUTLINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply {
                topMargin = dp(13)
                bottomMargin = dp(3)
            }
        })

        repeat(2) {
            content.addView(skeletonBar(dp(110), dp(10), radius = 3f, topMargin = dp(18)))
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = CodeAssistTheme.roundedBg(activity, CodeAssistTheme.SURFACE, 10f, null)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            panel.addView(skeletonBar(ViewGroup.LayoutParams.MATCH_PARENT, dp(14), radius = 4f))
            panel.addView(skeletonBar(dp(180), dp(14), radius = 4f, topMargin = dp(10)))
            content.addView(panel)
        }

        content.addView(skeletonBar(dp(110), dp(10), radius = 3f, topMargin = dp(18)))
        val flexPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = CodeAssistTheme.roundedBg(activity, CodeAssistTheme.SURFACE, 10f, null)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
        }
        flexPanel.addView(skeletonBar(ViewGroup.LayoutParams.MATCH_PARENT, dp(14), radius = 4f))
        flexPanel.addView(skeletonBar(dp(180), dp(14), radius = 4f, topMargin = dp(10)))
        content.addView(flexPanel)

        outer.addView(content)
        return outer
    }

    private fun skeletonBar(width: Int, height: Int, radius: Float, topMargin: Int = 0): View = View(activity).apply {
        background = CodeAssistTheme.roundedBg(activity, CodeAssistTheme.SURFACE_RAISED, radius)
        layoutParams = LinearLayout.LayoutParams(width, height).apply {
            this.topMargin = topMargin
        }
    }

    private fun dp(value: Int): Int = CodeAssistTheme.dp(activity, value)

    private fun configureDialogWindow(dialog: Dialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
            )
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun handleDismiss(dialog: Dialog) {
        if (activeDialog === dialog) activeDialog = null
        stateUnsubscribe?.invoke()
        stateUnsubscribe = null
        try {
            pendingResolve?.let { resolveHandler.removeCallbacks(it) }
            resolveHandler.removeCallbacksAndMessages(null)
            if (::treeView.isInitialized) treeView.cancelScan()
            if (uiBuilt && ::spinnerPresets.isInitialized) {
                readUIValues()
            }
        } finally {
            onDismiss()
        }
    }

    private fun buildFullUI(
        dialog: Dialog,
        projectConfig: TreeConfigStore.LoadedConfig?
    ) {
        val savedPresetName = projectConfig?.presetName
        val savedFilterConfig = projectConfig?.filterConfig
        val savedOverrides = projectConfig?.overrides ?: TreeOverrides()

        currentOverrides = savedOverrides

        val resolvedPresetConfig = savedPresetName?.let { presetManager.loadPreset(it) }
        currentConfig = when {
            resolvedPresetConfig != null -> resolvedPresetConfig
            savedFilterConfig != null -> savedFilterConfig.normalized()
            else -> initialConfig.normalized()
        }

        isCustomMode = resolvedPresetConfig == null
        currentPresetName = when {
            savedPresetName != null && resolvedPresetConfig != null -> savedPresetName
            savedFilterConfig != null -> savedPresetName.orEmpty().ifBlank { customPresetLabel() }
            else -> customPresetLabel()
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = CodeAssistTheme.roundedBg(activity, CodeAssistTheme.BG, 15f, CodeAssistTheme.OUTLINE)
        }
        scroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(activity.textView(
            activity.pluginString(R.string.app_name), 17f, CodeAssistTheme.TEXT_PRIMARY, true
        ))
        headerText.addView(activity.textView(
            activity.pluginString(R.string.dialog_subtitle, projectRoot.name),
            10f, CodeAssistTheme.TEXT_SECONDARY
        ).apply { setPadding(0, dp(5), 0, 0) })
        header.addView(headerText)
        header.addView(activity.iconButton(
            R.drawable.ic_tech_close, activity.pluginString(R.string.close)
        ) { dialog.dismiss() })
        root.addView(header)
        root.addView(divider())

        configContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(configContainer)

        configContainer.addView(sectionTitleWithHelp(
            R.string.section_presets,
            R.string.help_presets_title,
            R.string.help_presets_body
        ))
        val presetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        presetAdapter = activity.spinnerAdapter(getPresetList())
        spinnerPresets = Spinner(activity).apply {
            adapter = presetAdapter
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.SURFACE_RAISED, 8f, CodeAssistTheme.OUTLINE
            )
            dropDownVerticalOffset = dp(4)
            setPopupBackgroundDrawable(ColorDrawable(CodeAssistTheme.SURFACE_RAISED))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        }
        btnSavePreset = activity.iconButton(
            R.drawable.ic_tech_save, activity.pluginString(R.string.save_preset)
        ) { showSavePresetDialog() }.apply {
            visibility = if (isCustomMode) View.VISIBLE else View.GONE
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(8) }
        }
        btnDeletePreset = activity.iconButton(
            R.drawable.ic_tech_trash, activity.pluginString(R.string.delete_preset)
        ) { confirmDeletePreset() }.apply {
            visibility = View.GONE
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(8) }
        }
        presetRow.addView(spinnerPresets)
        presetRow.addView(btnSavePreset)
        presetRow.addView(btnDeletePreset)
        configContainer.addView(presetRow)

        configContainer.addView(sectionSeparator(topDp = 12, bottomDp = 4))
        configContainer.addView(activity.sectionTitle(activity.pluginString(R.string.section_general)))

        cbBinaries = activity.checkBox(activity.pluginString(R.string.exclude_binaries)).apply {
            isChecked = currentConfig.excludeBinaries
            setOnCheckedChangeListener { _, _ -> if (!isUpdatingUI) markAsCustom() }
        }
        configContainer.addView(rowWithHelp(cbBinaries, R.string.help_binaries_title, R.string.help_binaries_body))

        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        cbMaxSize = activity.checkBox(activity.pluginString(R.string.max_size)).apply {
            isChecked = currentConfig.useMaxSize
            minHeight = dp(40)
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                etMaxSize.isEnabled = checked
                etMaxSize.alpha = if (checked) 1f else 0.4f
                if (!isUpdatingUI) markAsCustom()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        sizeRow.addView(cbMaxSize)
        etMaxSize = activity.editText(
            currentConfig.maxSizeKb.toString(),
            InputType.TYPE_CLASS_NUMBER
        ).apply {
            gravity = Gravity.CENTER
            isEnabled = currentConfig.useMaxSize
            alpha = if (currentConfig.useMaxSize) 1f else 0.4f
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && !isUpdatingUI) markAsCustom()
            }
            layoutParams = LinearLayout.LayoutParams(dp(78), dp(40)).apply { marginStart = dp(6) }
        }
        sizeRow.addView(etMaxSize)
        sizeRow.addView(activity.textView(
            activity.pluginString(R.string.kb_suffix), 11f, CodeAssistTheme.TEXT_SECONDARY
        ).apply { setPadding(dp(6), 0, dp(4), 0) })
        sizeRow.addView(helpIcon(
            R.string.help_max_size_title,
            R.string.help_max_size_body,
            Gravity.END or Gravity.CENTER_VERTICAL
        ))
        configContainer.addView(sizeRow)

        cbInclusions = sectionCheckBox(R.string.section_inclusions).apply {
            isChecked = currentConfig.enableInclusions
        }
        btnAddInclusion = activity.actionText(activity.pluginString(R.string.action_add)) {
            showAddTagDialog(activity.pluginString(R.string.add_inclusion_title)) {
                addTag(it, TagKind.INCLUSION)
            }
        }.apply {
            visibility = if (currentConfig.enableInclusions) View.VISIBLE else View.GONE
        }
        btnEmptyInclusions = activity.actionText(activity.pluginString(R.string.action_clear)) {
            if (isUpdatingUI) return@actionText
            if (currentConfig.inclusions.isEmpty()) return@actionText

            currentConfig = currentConfig.copy(inclusions = emptyList())
            markAsCustom()
            updateTagsUI(animate = false)

            btnEmptyInclusions.visibility = View.GONE
        }.apply {
            visibility =
                if (currentConfig.enableInclusions && currentConfig.inclusions.isNotEmpty())
                    View.VISIBLE else View.GONE
        }
        val incRow = sectionToggleRow(
            cb = cbInclusions,
            helpTitleRes = R.string.help_inclusions_title,
            helpBodyRes = R.string.help_inclusions_body,
            addButton = btnAddInclusion,
            emptyButton = btnEmptyInclusions
        )
        cbInclusions.setOnCheckedChangeListener { _, checked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            currentConfig = currentConfig.copy(enableInclusions = checked)
            animateToggle(flowInclusions, checked)
            btnAddInclusion.visibility = if (checked) View.VISIBLE else View.GONE
            btnEmptyInclusions.visibility =
                if (checked && currentConfig.inclusions.isNotEmpty()) View.VISIBLE else View.GONE
            markAsCustom()
        }
        configContainer.addView(incRow)
        flowInclusions = createFlow().apply {
            visibility = if (currentConfig.enableInclusions) View.VISIBLE else View.GONE
        }
        configContainer.addView(flowInclusions)

        cbExclusions = sectionCheckBox(R.string.section_exclusions).apply {
            isChecked = currentConfig.enableExclusions
        }
        btnAddExclusion = activity.actionText(activity.pluginString(R.string.action_add)) {
            showAddTagDialog(activity.pluginString(R.string.add_exclusion_title)) {
                addTag(it, TagKind.EXCLUSION)
            }
        }.apply {
            visibility = if (currentConfig.enableExclusions) View.VISIBLE else View.GONE
        }
        btnEmptyExclusions = activity.actionText(activity.pluginString(R.string.action_clear)) {
            if (isUpdatingUI) return@actionText
            if (currentConfig.exclusions.isEmpty()) return@actionText
            currentConfig = currentConfig.copy(exclusions = emptyList())
            markAsCustom()
            updateTagsUI(animate = false)
            btnEmptyExclusions.visibility = View.GONE
        }.apply {
            visibility =
                if (currentConfig.enableExclusions && currentConfig.exclusions.isNotEmpty())
                    View.VISIBLE else View.GONE
        }
        val excRow = sectionToggleRow(
            cb = cbExclusions,
            helpTitleRes = R.string.help_exclusions_title,
            helpBodyRes = R.string.help_exclusions_body,
            addButton = btnAddExclusion,
            emptyButton = btnEmptyExclusions
        )
        cbExclusions.setOnCheckedChangeListener { _, checked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            currentConfig = currentConfig.copy(enableExclusions = checked)
            animateToggle(flowExclusions, checked)
            btnAddExclusion.visibility = if (checked) View.VISIBLE else View.GONE
            btnEmptyExclusions.visibility =
                if (checked && currentConfig.exclusions.isNotEmpty()) View.VISIBLE else View.GONE
            markAsCustom()
        }
        configContainer.addView(excRow)
        flowExclusions = createFlow().apply {
            visibility = if (currentConfig.enableExclusions) View.VISIBLE else View.GONE
        }
        configContainer.addView(flowExclusions)

        cbStubPatterns = sectionCheckBox(R.string.section_stub_patterns).apply {
            isChecked = currentConfig.enableStubPatterns
        }
        btnAddStubPattern = activity.actionText(activity.pluginString(R.string.action_add)) {
            showAddTagDialog(activity.pluginString(R.string.add_stub_title)) {
                addTag(it, TagKind.STUB)
            }
        }.apply {
            visibility = if (currentConfig.enableStubPatterns) View.VISIBLE else View.GONE
        }
        btnEmptyStubPattern = activity.actionText(activity.pluginString(R.string.action_clear)) {
            if (isUpdatingUI) return@actionText
            if (currentConfig.stubPatterns.isEmpty()) return@actionText
            currentConfig = currentConfig.copy(stubPatterns = emptyList())
            markAsCustom()
            updateTagsUI(animate = false)
            btnEmptyStubPattern.visibility = View.GONE
        }.apply {
            visibility =
                if (currentConfig.enableStubPatterns && currentConfig.stubPatterns.isNotEmpty())
                    View.VISIBLE else View.GONE
        }
        val stubRow = sectionToggleRow(
            cb = cbStubPatterns,
            helpTitleRes = R.string.help_stub_title,
            helpBodyRes = R.string.help_stub_body,
            addButton = btnAddStubPattern,
            emptyButton = btnEmptyStubPattern
        )
        cbStubPatterns.setOnCheckedChangeListener { _, checked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            currentConfig = currentConfig.copy(enableStubPatterns = checked)
            animateToggle(flowStubPatterns, checked)
            btnAddStubPattern.visibility = if (checked) View.VISIBLE else View.GONE
            btnEmptyStubPattern.visibility =
                if (checked && currentConfig.stubPatterns.isNotEmpty()) View.VISIBLE else View.GONE
            markAsCustom()
        }
        configContainer.addView(stubRow)
        flowStubPatterns = createFlow().apply {
            visibility = if (currentConfig.enableStubPatterns) View.VISIBLE else View.GONE
        }
        configContainer.addView(flowStubPatterns)

        cbGitignore = sectionCheckBox(R.string.section_gitignore).apply {
            isChecked = currentConfig.respectGitignore
        }
        val giRow = sectionToggleRow(
            cb = cbGitignore,
            helpTitleRes = R.string.help_gitignore_title,
            helpBodyRes = R.string.help_gitignore_body,
            addButton = null,
            emptyButton = null
        )
        cbGitignore.setOnCheckedChangeListener { _, checked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            currentConfig = currentConfig.copy(respectGitignore = checked)
            animateToggle(flowGitignore, checked)
            if (checked) updateGitignoreFlowOnly(true)
            markAsCustom()
        }
        configContainer.addView(giRow)
        flowGitignore = createFlow().apply {
            visibility = if (currentConfig.respectGitignore) View.VISIBLE else View.GONE
        }
        configContainer.addView(flowGitignore)

        if (gitEnabledGlobally) {

            cbUseGit = sectionCheckBox(R.string.section_version_control).apply {
                isChecked = currentConfig.useGit
            }
            val gitRow = sectionToggleRow(
                cb = cbUseGit,
                helpTitleRes = R.string.help_version_control_title,
                helpBodyRes = R.string.help_version_control_body,
                addButton = null,
                emptyButton = null
            )
            cbUseGit.setOnCheckedChangeListener { _, checked ->
                if (isUpdatingUI) return@setOnCheckedChangeListener
                currentConfig = currentConfig.copy(useGit = checked)
                if (::gitContentContainer.isInitialized) {
                    animateToggle(gitContentContainer, checked)
                }
                if (checked) updateResolvedRef()
                markAsCustom()
            }
            configContainer.addView(gitRow)

            gitContentContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(2), 0, dp(2))
                visibility = if (currentConfig.useGit) View.VISIBLE else View.GONE
            }
            configContainer.addView(gitContentContainer)
            setupGitUIAsync()
        } else {

            configContainer.addView(sectionTitleWithHelp(
                R.string.section_version_control,
                R.string.help_version_control_title,
                R.string.help_version_control_body
            ))
            configContainer.addView(activity.textView(
                activity.pluginString(R.string.git_disabled_globally),
                11f, CodeAssistTheme.TEXT_MUTED
            ).apply { setPadding(0, dp(4), 0, dp(6)) })

            gitContentContainer = LinearLayout(activity)
        }

        cbShowTree = sectionCheckBox(R.string.section_tree).apply {
            isChecked = true
        }
        val treeRow = sectionToggleRow(
            cb = cbShowTree,
            helpTitleRes = R.string.help_tree_title,
            helpBodyRes = R.string.help_tree_body,
            addButton = null,
            emptyButton = null
        )
        cbShowTree.setOnCheckedChangeListener { _, checked -> setTreeVisibility(checked) }
        configContainer.addView(treeRow)
        treeVisible = true

        treeActionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            visibility = View.VISIBLE
        }
        cbShowLocked = activity.checkBox(activity.pluginString(R.string.tree_show_locked)).apply {
            isChecked = PluginSettings.isTreeShowLockedEnabled(activity)
            minHeight = dp(32)
            setOnCheckedChangeListener { _, checked ->
                if (::treeView.isInitialized) treeView.toggleShowLocked(checked)
            }
        }
        treeActionsRow.addView(cbShowLocked)
        treeActionsRow.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        treeActionsRow.addView(activity.actionText(activity.pluginString(R.string.action_reload)) { reloadTree() })
        treeActionsRow.addView(activity.actionText(activity.pluginString(R.string.action_expand)) {
            if (::treeView.isInitialized) treeView.expandAll()
        })
        treeActionsRow.addView(activity.actionText(activity.pluginString(R.string.action_collapse)) {
            if (::treeView.isInitialized) treeView.collapseAll()
        })
        configContainer.addView(treeActionsRow)

        treeWrapper = FrameLayout(activity).apply {
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.SURFACE, 10f, CodeAssistTheme.OUTLINE
            )
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)
            ).apply { topMargin = dp(4) }
            visibility = View.VISIBLE
        }
        treeView = ProjectTreeView(
            activity = activity,
            projectRoot = projectRoot,
            onStatesChanged = { states -> currentStatesCache = states },
            onStatsChanged = {  }
        )

        treeView.setShowLockedPreference(cbShowLocked.isChecked)
        treeViewContainer = treeView.getView().apply { visibility = View.GONE }
        treeWrapper.addView(treeViewContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        treeLoadingPanel = buildTreeLoadingPanel()
        treeWrapper.addView(treeLoadingPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        configContainer.addView(treeWrapper)

        val generateButton = Button(activity).apply {
            text = activity.pluginString(R.string.action_generate)
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.parseColor("#21170F"))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = CodeAssistTheme.selectableBg(
                activity, normalColor = CodeAssistTheme.ACCENT,
                pressedColor = CodeAssistTheme.ACCENT_PRESSED, radiusDp = 9f, strokeColor = null
            )
            minHeight = dp(46)
            setOnClickListener {
                readUIValues()
                startExport(ExportAction.GENERATE)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { topMargin = dp(22) }
        }
        attachPressScale(generateButton)
        configContainer.addView(generateButton)

        configContainer.addView(activity.textView(
            activity.pluginString(R.string.generate_description),
            10f, CodeAssistTheme.TEXT_MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(9), dp(8), 0)
        })

        progressContainer = buildProgressContainer()
        progressContainer.visibility = View.GONE
        root.addView(progressContainer)

        dialog.setContentView(scroll)

        stateUnsubscribe = ExportStateHolder.subscribe { snapshot ->
            if (activity.isFinishing || activity.isDestroyed) return@subscribe
            applyState(snapshot)
        }

        val initialIndex = resolveInitialPresetIndex(currentPresetName)
        spinnerPresets.setSelection(initialIndex, false)
        updateTagsUI(animate = false)

        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUI) return
                val selected = parent?.getItemAtPosition(position)?.toString() ?: return
                isCustomMode = selected == customPresetLabel()
                btnSavePreset.visibility = if (isCustomMode) View.VISIBLE else View.GONE
                btnDeletePreset.visibility = if (presetManager.isUserPreset(selected)) View.VISIBLE else View.GONE
                if (!isCustomMode) presetManager.loadPreset(selected)?.let { applyConfigToUI(it) }
                currentPresetName = selected

                currentOverrides = TreeOverrides()
                if (treeVisible) startTreeScan()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        uiBuilt = true
        scroll.post { playEntrance(configContainer) }

        if (treeVisible) {
            treeWrapper.post { startTreeScan() }
        }
    }

    private fun sectionToggleRow(
        cb: CheckBox,
        helpTitleRes: Int,
        helpBodyRes: Int,
        addButton: TextView?,
        emptyButton: TextView? = null
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        cb.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(cb)
        if (emptyButton != null) {
            emptyButton.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8); marginEnd = dp(4) }
            row.addView(emptyButton)
        }
        if (addButton != null) {
            addButton.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8); marginEnd = dp(4) }
            row.addView(addButton)
        }
        row.addView(helpIcon(helpTitleRes, helpBodyRes, Gravity.END or Gravity.CENTER_VERTICAL))
        return row
    }

    private fun sectionCheckBox(labelRes: Int): CheckBox {
        val cb = activity.checkBox(
            activity.pluginString(labelRes).uppercase(LanguageHelper.detectIdeLocale(activity))
        )
        cb.textSize = 11f
        cb.setTextColor(CodeAssistTheme.TEXT_SECONDARY)
        cb.typeface = android.graphics.Typeface.create(
            "sans-serif-medium", android.graphics.Typeface.NORMAL
        )
        cb.letterSpacing = 0.15f
        return cb
    }

    private fun sectionSeparator(topDp: Int = 16, bottomDp: Int = 4): View = View(activity).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE.withAlpha(90))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply {
            topMargin = dp(topDp)
            bottomMargin = dp(bottomDp)
        }
    }

    private fun animateToggle(target: View, visible: Boolean, duration: Long = 200) {
        if (!animationsEnabled) {
            target.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        if (visible && target.visibility == View.VISIBLE) return
        if (!visible && target.visibility == View.GONE) return

        target.animate().cancel()
        if (visible) {
            target.visibility = View.VISIBLE
            target.alpha = 0f
            target.translationY = -dp(6).toFloat()
            target.animate().alpha(1f).translationY(0f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            target.animate().alpha(0f).translationY(-dp(6).toFloat())
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    target.visibility = View.GONE
                    target.alpha = 1f
                    target.translationY = 0f
                }
                .start()
        }
    }

    private fun helpIcon(
        helpTitleRes: Int,
        helpBodyRes: Int,
        iconGravity: Int = Gravity.CENTER
    ): View {
        val touchSize = dp(40)
        val iconSize = dp(18)

        val container = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(touchSize, touchSize).apply {
                marginStart = dp(2)
            }
            contentDescription = activity.pluginString(R.string.help_icon_description)
            isClickable = true
            isFocusable = true
            setOnClickListener { showSectionHelp(helpTitleRes, helpBodyRes) }
        }
        container.addView(
            ImageView(activity).apply {
                setImageDrawable(activity.loadPluginDrawable(R.drawable.ic_tech_help))
                setColorFilter(CodeAssistTheme.ACCENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, iconGravity)
            }
        )
        return container
    }

    private fun rowWithHelp(cb: CheckBox, helpTitleRes: Int, helpBodyRes: Int): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cb.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(cb)
        row.addView(helpIcon(helpTitleRes, helpBodyRes, Gravity.END or Gravity.CENTER_VERTICAL))
        return row
    }

    private fun showSectionHelp(titleRes: Int, bodyRes: Int) {
        AlertDialog.Builder(activity)
            .setTitle(activity.pluginString(titleRes))
            .setMessage(activity.pluginString(bodyRes))
            .setPositiveButton(activity.pluginString(R.string.accept), null)
            .show()
            .styleDialog()
    }

    private fun sectionTitleWithHelp(titleRes: Int, helpTitleRes: Int, helpBodyRes: Int): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(18), 0, dp(9))
        addView(activity.textView(
            activity.pluginString(titleRes).uppercase(LanguageHelper.detectIdeLocale(activity)),
            11f, CodeAssistTheme.TEXT_SECONDARY, true
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(helpIcon(helpTitleRes, helpBodyRes, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private fun buildProgressContainer(): LinearLayout {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        progressIcon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(24)
            }
        }
        panel.addView(progressIcon)

        progressTitle = activity.textView(
            activity.pluginString(R.string.generating_context), 18f, CodeAssistTheme.TEXT_PRIMARY, true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(6))
        }
        panel.addView(progressTitle)

        progressSubtitle = activity.textView(
            activity.pluginString(R.string.preparing_export), 12f, CodeAssistTheme.TEXT_SECONDARY
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(12))
        }
        panel.addView(progressSubtitle)

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
            ).apply {
                marginStart = dp(24)
                marginEnd = dp(24)
                bottomMargin = dp(12)
            }
        }
        panel.addView(progressBar)

        progressMinimizeHint = activity.textView(
            activity.pluginString(R.string.generation_minimize_hint), 10f, CodeAssistTheme.TEXT_MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(16))
        }
        panel.addView(progressMinimizeHint)

        progressStats = activity.textView("", 12f, CodeAssistTheme.TEXT_SECONDARY).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
            visibility = View.GONE
        }
        panel.addView(progressStats)

        progressCancelBtn = Button(activity).apply {
            text = activity.pluginString(R.string.cancel)
            textSize = 13f
            isAllCaps = false
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = CodeAssistTheme.selectableBg(
                activity, CodeAssistTheme.SURFACE_RAISED, CodeAssistTheme.SURFACE,
                9f, CodeAssistTheme.OUTLINE
            )
            minHeight = dp(42)
            setOnClickListener { ExportCoordinator.cancelCurrent() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        attachPressScale(progressCancelBtn)
        panel.addView(progressCancelBtn)

        progressActionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        progressActionsRow.addView(makeActionChip(R.drawable.ic_tech_share, R.string.action_share) { onShareResultClicked() })
        progressActionsRow.addView(makeActionChip(R.drawable.ic_tech_download, R.string.action_save) { onSaveResultClicked() })
        progressActionsRow.addView(makeActionChip(R.drawable.ic_tech_refresh, R.string.action_regenerate) { onRegenerateClicked() })
        panel.addView(progressActionsRow)

        progressBackLink = activity.actionText(activity.pluginString(R.string.action_edit_settings)) {
            ExportStateHolder.reset()
        }.apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        }
        panel.addView(progressBackLink)
        return panel
    }

    private fun makeActionChip(iconRes: Int, labelRes: Int, onClick: () -> Unit): LinearLayout {
        val chip = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = CodeAssistTheme.selectableBg(
                activity, CodeAssistTheme.SURFACE_RAISED, CodeAssistTheme.SURFACE,
                9f, CodeAssistTheme.OUTLINE
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
        chip.addView(ImageView(activity).apply {
            setImageDrawable(activity.loadPluginDrawable(iconRes))
            setColorFilter(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
        chip.addView(activity.textView(
            activity.pluginString(labelRes), 11f, CodeAssistTheme.TEXT_PRIMARY, true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        attachPressScale(chip)
        return chip
    }

    private fun applyState(snapshot: ExportStateHolder.Snapshot) {
        if (!::configContainer.isInitialized || !::progressContainer.isInitialized) return

        val belongsToOtherProject = snapshot.state != ExportStateHolder.State.IDLE &&
            snapshot.rootPath != null &&
            snapshot.rootPath != projectRoot.absolutePath
        if (belongsToOtherProject) { showConfigInline(); return }

        when (snapshot.state) {
            ExportStateHolder.State.IDLE -> showConfigInline()
            ExportStateHolder.State.RUNNING -> showProgressInline(snapshot)
            ExportStateHolder.State.SUCCESS -> showSuccessInline(snapshot)
            ExportStateHolder.State.ERROR -> showErrorInline(snapshot)
        }
    }

    private fun showConfigInline() {
        configContainer.visibility = View.VISIBLE
        progressContainer.visibility = View.GONE
    }

    private fun showProgressInline(snapshot: ExportStateHolder.Snapshot) {
        configContainer.visibility = View.GONE
        progressContainer.visibility = View.VISIBLE

        progressIcon.setImageDrawable(activity.loadPluginDrawable(R.drawable.ic_tech_rocket))
        progressIcon.setColorFilter(CodeAssistTheme.ACCENT)
        progressTitle.text = activity.pluginString(R.string.generating_context)
        val baseMessage = snapshot.message.ifBlank { activity.pluginString(R.string.preparing_export) }
        progressSubtitle.text = if (snapshot.bytesTotal > 0L && snapshot.bytesProcessed > 0L) {
            "$baseMessage  ·  ${formatSize(snapshot.bytesProcessed)} / ${formatSize(snapshot.bytesTotal)}"
        } else baseMessage

        progressBar.visibility = View.VISIBLE
        progressMinimizeHint.visibility = View.VISIBLE
        progressStats.visibility = View.GONE

        if (snapshot.bytesTotal > 0L && snapshot.bytesProcessed > 0L) {
            val scale = 10_000
            val target = ((snapshot.bytesProcessed.coerceAtMost(snapshot.bytesTotal) * scale) / snapshot.bytesTotal).toInt()
            progressBar.isIndeterminate = false
            if (progressBar.max != scale) progressBar.max = scale
            progressBar.setProgress(target,  animationsEnabled)
        } else if (snapshot.total > 0 && snapshot.processed > 0) {
            progressBar.isIndeterminate = false
            if (progressBar.max != snapshot.total) progressBar.max = snapshot.total
            progressBar.setProgress(snapshot.processed,  animationsEnabled)
        } else {
            progressBar.isIndeterminate = true
            progressBar.progress = 0
        }

        progressCancelBtn.visibility = View.VISIBLE
        progressActionsRow.visibility = View.GONE
        progressBackLink.visibility = View.GONE
    }

    private fun showSuccessInline(snapshot: ExportStateHolder.Snapshot) {
        configContainer.visibility = View.GONE
        progressContainer.visibility = View.VISIBLE

        progressIcon.setImageDrawable(activity.loadPluginDrawable(R.drawable.ic_tech_check))
        progressIcon.setColorFilter(CodeAssistTheme.TAG_INCLUDE_TEXT)
        progressTitle.text = activity.pluginString(R.string.generation_complete)
        progressSubtitle.text = snapshot.fileName?.let {
            activity.pluginString(R.string.saved_to_downloads, it)
        } ?: ""
        progressBar.visibility = View.GONE
        progressMinimizeHint.visibility = View.GONE
        progressCancelBtn.visibility = View.GONE

        val statsText = formatResultStats(snapshot)
        if (statsText.isNullOrBlank()) {
            progressStats.visibility = View.GONE
        } else {
            progressStats.text = statsText
            progressStats.visibility = View.VISIBLE
        }
        progressActionsRow.visibility = View.VISIBLE
        progressBackLink.visibility = View.VISIBLE
    }

    private fun showErrorInline(snapshot: ExportStateHolder.Snapshot) {
        configContainer.visibility = View.GONE
        progressContainer.visibility = View.VISIBLE

        progressIcon.setImageDrawable(activity.loadPluginDrawable(R.drawable.ic_tech_close))
        progressIcon.setColorFilter(CodeAssistTheme.TAG_EXCLUDE_TEXT)
        progressTitle.text = activity.pluginString(R.string.generation_failed)
        progressSubtitle.text = snapshot.error ?: activity.pluginString(R.string.unknown_error)
        progressBar.visibility = View.GONE
        progressMinimizeHint.visibility = View.GONE
        progressStats.visibility = View.GONE
        progressCancelBtn.visibility = View.GONE
        progressActionsRow.visibility = View.VISIBLE
        progressBackLink.visibility = View.VISIBLE
    }

    private fun formatResultStats(snapshot: ExportStateHolder.Snapshot): String? {
        if (snapshot.filesCount <= 0 && snapshot.totalBytes <= 0L) return null
        val filesLabel = activity.pluginQuantityString(
            R.plurals.stats_files,
            snapshot.filesCount,
            snapshot.filesCount
        )
        val tokensLabel = activity.pluginString(
            R.string.stats_tokens, formatNumber(snapshot.estimatedTokens)
        )
        val sizeLabel = formatSize(snapshot.totalBytes)
        return "$filesLabel  ·  $tokensLabel  ·  $sizeLabel"
    }

    private fun isSnapshotUriReadable(snap: ExportStateHolder.Snapshot): Boolean {
        val uri = snap.uri ?: return false
        return runCatching {
            activity.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                null, null, null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private fun handleStaleResult(messageRes: Int) {
        Toast.makeText(activity, activity.pluginString(messageRes), Toast.LENGTH_LONG).show()
        ExportStateHolder.reset()
    }

    private fun startExport(action: ExportAction) {
        val configSnapshot = currentConfig
        val statesSnapshot = currentStatesCache
        val ref = configSnapshot.gitRef?.trim().orEmpty()
        if (!configSnapshot.useGit || ref.isEmpty()) {
            launchExport(configSnapshot, statesSnapshot, action)
            return
        }
        Toast.makeText(
            activity,
            activity.pluginString(R.string.git_resolving),
            Toast.LENGTH_SHORT
        ).show()
        Thread {
            val valid = runCatching { GitUtils.refExists(projectRoot, ref) }
                .getOrDefault(false)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!valid) {
                    Toast.makeText(
                        activity,
                        activity.pluginString(R.string.git_ref_invalid),
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                launchExport(configSnapshot, statesSnapshot, action)
            }
        }.apply { isDaemon = true; name = "OneCode-ref-validate"; start() }
    }

    private fun launchExport(
        config: FilterConfig,
        states: Map<String, NodeState>,
        action: ExportAction
    ) {
        runCatching {
            TreeConfigStore.saveDiff(
                projectRoot = projectRoot,
                currentStates = states,
                filter = FileFilter(projectRoot, config),
                presetName = currentPresetName
            )
        }.onFailure {
            logInfo("[WARN] Failed to persist tree config: ${it.message}")
        }

        if (!exportCoordinator.export(projectRoot, states, action)) {
            Toast.makeText(
                activity, activity.pluginString(R.string.export_already_running), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun onShareResultClicked() {
        val snap = ExportStateHolder.snapshot
        if (!isSnapshotUriReadable(snap)) {
            handleStaleResult(R.string.error_source_file_missing)
            return
        }
        val uri = snap.uri ?: return
        val name = snap.fileName ?: ExportCoordinator.SHARE_FILE_NAME
        runCatching { ExportCoordinator.shareFile(activity, uri, name) }
            .onFailure {
                Toast.makeText(
                    activity,
                    activity.pluginString(R.string.share_error, it.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun onSaveResultClicked() {
        val snap = ExportStateHolder.snapshot
        if (!isSnapshotUriReadable(snap)) {
            handleStaleResult(R.string.error_source_file_missing)
            return
        }
        val uri = snap.uri ?: return
        if (!exportCoordinator.export(
                root = projectRoot,
                states = currentStatesCache,
                action = ExportAction.SAVE,
                sourceUri = uri,
                sourceFilesCount = snap.filesCount,
                sourceTotalBytes = snap.totalBytes
            )
        ) {
            Toast.makeText(
                activity, activity.pluginString(R.string.export_already_running), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun onRegenerateClicked() {
        readUIValues()
        startExport(ExportAction.GENERATE)
    }

    private fun setTreeUiState(state: TreeUiState) {
        if (!::treeLoadingPanel.isInitialized || !::treeViewContainer.isInitialized) return
        when (state) {
            TreeUiState.LOADING -> {
                treeLoadingPanel.visibility = View.VISIBLE
                treeViewContainer.visibility = View.GONE
                treeLoadingText.text = activity.pluginString(R.string.analyzing)
                if (::treeLoadingProgress.isInitialized) {
                    treeLoadingProgress.visibility = View.VISIBLE
                }
                if (::treeLoadingCancelButton.isInitialized) {
                    treeLoadingCancelButton.visibility = View.VISIBLE
                }
            }
            TreeUiState.TREE -> {
                treeLoadingPanel.visibility = View.GONE
                treeViewContainer.visibility = View.VISIBLE
            }
            TreeUiState.ERROR -> {
                treeLoadingPanel.visibility = View.VISIBLE
                treeViewContainer.visibility = View.GONE
                if (::treeLoadingProgress.isInitialized) {
                    treeLoadingProgress.visibility = View.GONE
                }
                if (::treeLoadingCancelButton.isInitialized) {
                    treeLoadingCancelButton.visibility = View.GONE
                }
            }
        }
    }

    private fun setTreeVisibility(visible: Boolean) {
        treeVisible = visible
        if (::treeActionsRow.isInitialized) animateToggle(treeActionsRow, visible)
        if (::treeWrapper.isInitialized) animateToggle(treeWrapper, visible)
        if (visible) {
            startTreeScan()
        } else if (::treeView.isInitialized) {
            treeView.cancelScan()
            setTreeUiState(TreeUiState.TREE)
        }
    }

    private fun reloadTree() {
        if (!treeVisible) {
            setTreeVisibility(true)
            return
        }
        startTreeScan()
    }

    private fun buildTreeLoadingPanel(): LinearLayout {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.SURFACE, 10f, CodeAssistTheme.OUTLINE
            )
        }
        treeLoadingProgress = ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }
        panel.addView(treeLoadingProgress)
        treeLoadingText = activity.textView(
            activity.pluginString(R.string.analyzing), 12f, CodeAssistTheme.TEXT_SECONDARY
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        panel.addView(treeLoadingText)
        treeLoadingCancelButton = Button(activity).apply {
            text = activity.pluginString(R.string.cancel)
            textSize = 12f
            isAllCaps = false
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            background = CodeAssistTheme.selectableBg(
                activity, CodeAssistTheme.SURFACE_RAISED, CodeAssistTheme.SURFACE,
                8f, CodeAssistTheme.OUTLINE
            )
            minHeight = dp(36)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener {
                if (::treeView.isInitialized) treeView.cancelScan()
                setTreeUiState(TreeUiState.ERROR)
                treeLoadingText.text = activity.pluginString(R.string.canceled_retry)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
            ).apply {
                topMargin = dp(12)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        panel.addView(treeLoadingCancelButton)
        return panel
    }

    private fun startTreeScan() {
        if (!treeVisible || !::treeView.isInitialized) return
        readUIValues()

        if (::cbShowLocked.isInitialized) {
            treeView.setShowLockedPreference(cbShowLocked.isChecked)
        }
        setTreeUiState(TreeUiState.LOADING)
        treeView.scanAsync(
            config = currentConfig,
            overrides = currentOverrides,
            onProgress = { count ->
                if (::treeLoadingText.isInitialized) {
                    treeLoadingText.text = activity.pluginString(R.string.analyzing_files, count)
                }
            },
            onComplete = { root ->
                setTreeUiState(TreeUiState.TREE)
                treeView.renderTree(root)

                currentStatesCache = treeView.currentStates()
            },
            onError = { error ->
                setTreeUiState(TreeUiState.ERROR)
                treeLoadingText.text = activity.pluginString(
                    R.string.tree_scan_error,
                    error.message ?: error.javaClass.simpleName
                )
            }
        )
    }

    private fun setupGitUIAsync() {
        val loading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        loading.addView(ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        loading.addView(activity.textView(
            activity.pluginString(R.string.git_resolving), 11f, CodeAssistTheme.TEXT_MUTED
        ).apply { setPadding(dp(8), 0, 0, 0) })
        gitContentContainer.addView(loading)

        Thread({
            val repoInfo = runCatching { GitUtils.getRepoInfo(projectRoot) }.getOrNull()
            val uncommitted = if (repoInfo?.hasUncommittedChanges == true) {
                runCatching { GitUtils.countUncommittedChanges(projectRoot) }.getOrDefault(0)
            } else 0
            activity.runOnUiThread {
                if (!::gitContentContainer.isInitialized || !uiBuilt) return@runOnUiThread
                gitContentContainer.removeAllViews()
                if (repoInfo == null) {
                    renderNoRepoState()
                } else {
                    renderGitInfoState(repoInfo, uncommitted)
                }
            }
        }, "OneCode-git-info").apply { isDaemon = true; start() }
    }

    private fun renderNoRepoState() {
        gitContentContainer.addView(activity.textView(
            activity.pluginString(R.string.git_not_repo), 11f, CodeAssistTheme.TEXT_MUTED
        ).apply { setPadding(0, dp(4), 0, dp(4)) })
        val parentRoot = GitUtils.findGitRoot(projectRoot.parentFile ?: projectRoot)
        if (parentRoot != null && parentRoot.absolutePath != projectRoot.absolutePath) {
            gitContentContainer.addView(activity.actionText(
                activity.pluginString(R.string.git_open_parent_root, parentRoot.name)
            ) { activeDialog?.dismiss(); onChangeRoot(parentRoot) }.apply {
                setPadding(0, dp(6), 0, dp(6))
            })
        }
    }

    private fun renderGitInfoState(repoInfo: GitUtils.RepoInfo, uncommitted: Int) {
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        tvGitInfo = activity.textView(
            activity.pluginString(
                R.string.git_info,
                repoInfo.currentBranch ?: activity.pluginString(R.string.no_branch),
                repoInfo.currentCommit ?: activity.pluginString(R.string.no_commit)
            ), 11f, CodeAssistTheme.TEXT_SECONDARY
        ).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        headerRow.addView(tvGitInfo)
        headerRow.addView(activity.iconButton(
            R.drawable.ic_tech_refresh, activity.pluginString(R.string.git_refresh)
        ) { refreshGitInfo() })
        gitContentContainer.addView(headerRow)

        tvGitWarning = activity.textView(
            activity.pluginString(R.string.git_uncommitted_changes, uncommitted),
            10f, CodeAssistTheme.TAG_CONFLICT_TEXT
        ).apply {
            setPadding(0, dp(2), 0, dp(2))
            visibility = if (repoInfo.hasUncommittedChanges) View.VISIBLE else View.GONE
        }
        gitContentContainer.addView(tvGitWarning)

        gitDetailsBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val refRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        etGitRef = activity.editText(currentConfig.gitRef ?: "").apply {
            hint = activity.pluginString(R.string.git_ref_hint)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!isUpdatingUI) scheduleResolve() }
            })
        }
        refRow.addView(etGitRef)
        refRow.addView(activity.iconButton(
            R.drawable.ic_tech_search, activity.pluginString(R.string.git_browse_ref)
        ) { showRefPickerDialog() }.apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = dp(8)
            }
        })
        gitDetailsBox.addView(refRow)

        tvResolvedRef = activity.textView("", 10f, CodeAssistTheme.TEXT_MUTED).apply {
            setPadding(0, dp(4), 0, dp(4))
            visibility = View.GONE
        }
        gitDetailsBox.addView(tvResolvedRef)

        gitDetailsBox.addView(Button(activity).apply {
            text = activity.pluginString(R.string.git_preview_diff)
            textSize = 12f; isAllCaps = false
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = CodeAssistTheme.selectableBg(
                activity, CodeAssistTheme.SURFACE_RAISED, CodeAssistTheme.SURFACE, 8f, CodeAssistTheme.OUTLINE
            )
            minHeight = dp(38)
            setOnClickListener { showDiffPreview() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
            ).apply { topMargin = dp(8) }
        })

        gitContentContainer.addView(gitDetailsBox)
        updateResolvedRef()
    }

    private fun refreshGitInfo() {
        Thread({
            val info = runCatching { GitUtils.getRepoInfo(projectRoot) }.getOrNull() ?: return@Thread
            val count = runCatching { GitUtils.countUncommittedChanges(projectRoot) }.getOrDefault(0)
            activity.runOnUiThread {
                if (!::tvGitInfo.isInitialized) return@runOnUiThread
                tvGitInfo.text = activity.pluginString(
                    R.string.git_info,
                    info.currentBranch ?: activity.pluginString(R.string.no_branch),
                    info.currentCommit ?: activity.pluginString(R.string.no_commit)
                )
                if (::tvGitWarning.isInitialized) {
                    if (count > 0) {
                        tvGitWarning.text = activity.pluginString(R.string.git_uncommitted_changes, count)
                        tvGitWarning.visibility = View.VISIBLE
                    } else tvGitWarning.visibility = View.GONE
                }
            }
        }, "OneCode-git-refresh").apply { isDaemon = true; start() }
    }

    private fun scheduleResolve() {
        pendingResolve?.let { resolveHandler.removeCallbacks(it) }
        val r = Runnable { updateResolvedRef() }
        pendingResolve = r
        resolveHandler.postDelayed(r, 300)
    }

    private fun updateResolvedRef() {
        if (!::tvResolvedRef.isInitialized || !::etGitRef.isInitialized) return
        val ref = etGitRef.text.toString().trim()
        if (ref.isEmpty()) { tvResolvedRef.visibility = View.GONE; return }
        tvResolvedRef.text = activity.pluginString(R.string.git_resolving)
        tvResolvedRef.setTextColor(CodeAssistTheme.TEXT_MUTED)
        tvResolvedRef.visibility = View.VISIBLE
        Thread {
            val short = GitUtils.resolveRefShort(projectRoot, ref)
            activity.runOnUiThread {
                if (!::tvResolvedRef.isInitialized) return@runOnUiThread
                if (etGitRef.text.toString().trim() != ref) return@runOnUiThread
                if (short == null) {
                    tvResolvedRef.text = activity.pluginString(R.string.git_ref_invalid)
                    tvResolvedRef.setTextColor(CodeAssistTheme.TAG_EXCLUDE_TEXT)
                } else {
                    tvResolvedRef.text = activity.pluginString(R.string.git_resolved_as, short)
                    tvResolvedRef.setTextColor(CodeAssistTheme.TEXT_MUTED)
                }
            }
        }.start()
    }

    private fun showRefPickerDialog() {
        GitReferencePickerDialog(activity, projectRoot) { selectedRef ->
            isUpdatingUI = true
            etGitRef.setText(selectedRef)
            isUpdatingUI = false
            updateResolvedRef()
        }.show()
    }

    private fun showDiffPreview() { GitDiffPreviewDialog(activity, projectRoot).show(etGitRef.text.toString()) }

    private fun playEntrance(root: ViewGroup) {
        if (!animationsEnabled) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.alpha = 0f
            child.translationY = dp(10).toFloat()
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 30L).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun animateTagIn(view: View) {
        if (!animationsEnabled) return
        view.animate().cancel()
        view.scaleX = 0.5f; view.scaleY = 0.5f; view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(240).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun animateTagOut(view: View, onEnd: () -> Unit) {
        if (!animationsEnabled) { onEnd(); return }
        view.animate().cancel()
        view.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f)
            .setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction(onEnd).start()
    }

    private fun attachPressScale(button: View) {
        if (!animationsEnabled) return
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                        .setInterpolator(DecelerateInterpolator()).start()
            }
            false
        }
    }

    private fun formatNumber(n: Long): String = when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> "%.1fK".format(n / 1000.0)
        else -> "%.2fM".format(n / 1_000_000.0)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply {
            topMargin = dp(13)
            bottomMargin = dp(3)
        }
    }

    private fun createFlow(): FlowLayout = FlowLayout(activity).apply {
        setPadding(0, dp(2), 0, dp(5))
        minimumHeight = dp(16)
    }

    private fun applyConfigToUI(config: FilterConfig) {
        isUpdatingUI = true
        currentConfig = config
        cbBinaries.isChecked = config.excludeBinaries
        cbMaxSize.isChecked = config.useMaxSize
        etMaxSize.setText(config.maxSizeKb.toString())
        etMaxSize.isEnabled = config.useMaxSize
        etMaxSize.alpha = if (config.useMaxSize) 1f else 0.4f

        if (::cbInclusions.isInitialized) {
            cbInclusions.isChecked = config.enableInclusions
            if (::flowInclusions.isInitialized) animateToggle(flowInclusions, config.enableInclusions)
            if (::btnAddInclusion.isInitialized) {
                btnAddInclusion.visibility = if (config.enableInclusions) View.VISIBLE else View.GONE
            }
            if (::btnEmptyInclusions.isInitialized) {
                btnEmptyInclusions.visibility = if (
                    config.enableInclusions && config.inclusions.isNotEmpty()
                ) View.VISIBLE else View.GONE
            }
        }

        if (::cbExclusions.isInitialized) {
            cbExclusions.isChecked = config.enableExclusions
            if (::flowExclusions.isInitialized) animateToggle(flowExclusions, config.enableExclusions)
            if (::btnAddExclusion.isInitialized) {
                btnAddExclusion.visibility = if (config.enableExclusions) View.VISIBLE else View.GONE
            }
            if (::btnEmptyExclusions.isInitialized) {
                btnEmptyExclusions.visibility = if (
                    config.enableExclusions && config.exclusions.isNotEmpty()
                ) View.VISIBLE else View.GONE
            }
        }

        if (::cbStubPatterns.isInitialized) {
            cbStubPatterns.isChecked = config.enableStubPatterns
            if (::flowStubPatterns.isInitialized) animateToggle(flowStubPatterns, config.enableStubPatterns)
            if (::btnAddStubPattern.isInitialized) {
                btnAddStubPattern.visibility = if (config.enableStubPatterns) View.VISIBLE else View.GONE
            }
            if (::btnEmptyStubPattern.isInitialized) {
                btnEmptyStubPattern.visibility = if (
                    config.enableStubPatterns && config.stubPatterns.isNotEmpty()
                ) View.VISIBLE else View.GONE
            }
        }

        if (::cbGitignore.isInitialized) {
            cbGitignore.isChecked = config.respectGitignore
            if (::flowGitignore.isInitialized) animateToggle(flowGitignore, config.respectGitignore)
        }

        if (gitEnabledGlobally && ::cbUseGit.isInitialized) {
            cbUseGit.isChecked = config.useGit
            if (::gitContentContainer.isInitialized) {
                animateToggle(gitContentContainer, config.useGit)
            }
            if (::etGitRef.isInitialized) etGitRef.setText(config.gitRef ?: "")
            updateResolvedRef()
        }

        updateTagsUI(animate = true)
        isUpdatingUI = false
        if (treeVisible) startTreeScan()
    }

    private fun markAsCustomOnly() {
        if (!isCustomMode) {
            isCustomMode = true
            currentPresetName = customPresetLabel()
            btnSavePreset.visibility = View.VISIBLE
            val customLabel = customPresetLabel()
            val customIndex = presetAdapter.getPosition(customLabel)
            if (customIndex >= 0 && spinnerPresets.selectedItemPosition != customIndex) {
                isUpdatingUI = true
                spinnerPresets.setSelection(customIndex)
                isUpdatingUI = false
            }
        }
    }

    private fun markAsCustom() { markAsCustomOnly(); scheduleRescan() }

    private fun scheduleRescan() {
        if (rescanScheduled) return
        rescanScheduled = true
        mainHandler.postDelayed({
            rescanScheduled = false
            if (treeVisible) startTreeScan()
        }, 220)
    }

    private fun readUIValues() {
        if (!::etMaxSize.isInitialized || !::cbBinaries.isInitialized ||
            !::cbMaxSize.isInitialized ||
            !::cbInclusions.isInitialized || !::cbExclusions.isInitialized ||
            !::cbStubPatterns.isInitialized || !::cbGitignore.isInitialized) return
        val size = etMaxSize.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 512
        var useGit = false
        var gitRef: String? = null
        if (gitEnabledGlobally && ::cbUseGit.isInitialized) {
            useGit = cbUseGit.isChecked
            if (useGit && ::etGitRef.isInitialized) {
                val ref = etGitRef.text.toString().trim()
                if (ref.isNotEmpty()) gitRef = ref else useGit = false
            }
        }
        currentConfig = currentConfig.copy(
            excludeBinaries = cbBinaries.isChecked,
            respectGitignore = cbGitignore.isChecked,
            useMaxSize = cbMaxSize.isChecked,
            maxSizeKb = size,
            enableInclusions = cbInclusions.isChecked,
            enableExclusions = cbExclusions.isChecked,
            enableStubPatterns = cbStubPatterns.isChecked,
            useGit = useGit,
            gitRef = gitRef
        )
    }

    private fun tagKey(tag: String, kind: TagKind) = "${kind.prefix}:$tag"

    private enum class TagKind(val prefix: String) {
        INCLUSION("I"),
        EXCLUSION("E"),
        STUB("S")
    }

    private fun updateTagsUI(animate: Boolean = false) {
        if (!::flowInclusions.isInitialized) return
        flowInclusions.removeAllViews()
        flowExclusions.removeAllViews()
        flowStubPatterns.removeAllViews()
        tagViews.clear()

        if (currentConfig.inclusions.isEmpty()) addEmptyLabel(flowInclusions, activity.pluginString(R.string.no_inclusions))
        else currentConfig.inclusions.forEach { addTagToFlow(flowInclusions, it, TagKind.INCLUSION) }

        if (currentConfig.exclusions.isEmpty()) addEmptyLabel(flowExclusions, activity.pluginString(R.string.no_exclusions))
        else currentConfig.exclusions.forEach { addTagToFlow(flowExclusions, it, TagKind.EXCLUSION) }

        if (currentConfig.stubPatterns.isEmpty()) addEmptyLabel(flowStubPatterns, activity.pluginString(R.string.no_stub_patterns))
        else currentConfig.stubPatterns.forEach { addTagToFlow(flowStubPatterns, it, TagKind.STUB) }

        updateGitignoreFlowOnly(currentConfig.respectGitignore)

        if (::btnEmptyInclusions.isInitialized && ::cbInclusions.isInitialized) {
            btnEmptyInclusions.visibility = if (
                cbInclusions.isChecked && currentConfig.inclusions.isNotEmpty()
            ) View.VISIBLE else View.GONE
        }
        if (::btnEmptyExclusions.isInitialized && ::cbExclusions.isInitialized) {
            btnEmptyExclusions.visibility = if (
                cbExclusions.isChecked && currentConfig.exclusions.isNotEmpty()
            ) View.VISIBLE else View.GONE
        }
        if (::btnEmptyStubPattern.isInitialized && ::cbStubPatterns.isInitialized) {
            btnEmptyStubPattern.visibility = if (
                cbStubPatterns.isChecked && currentConfig.stubPatterns.isNotEmpty()
            ) View.VISIBLE else View.GONE
        }

        if (animate && animationsEnabled) {
            val all = listOf(flowInclusions, flowExclusions, flowStubPatterns, flowGitignore)
            var i = 0
            all.forEach { flow ->
                if (flow.visibility != View.VISIBLE) return@forEach
                for (k in 0 until flow.childCount) {
                    val child = flow.getChildAt(k)
                    child.alpha = 0f; child.scaleX = 0.85f; child.scaleY = 0.85f
                    child.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay(i * 20L).setDuration(180)
                        .setInterpolator(DecelerateInterpolator()).start()
                    i++
                }
            }
        }
    }

    private fun updateGitignoreFlowOnly(enabled: Boolean) {
        if (!::flowGitignore.isInitialized) return
        flowGitignore.removeAllViews()
        if (!enabled) return
        if (gitignoreTags.isEmpty()) return
        gitignoreTags.forEach { rawTag ->
            val isNegation = rawTag.startsWith("!")
            val cleanTag = if (isNegation) rawTag.removePrefix("!") else rawTag
            val overridden = !isNegation && isGitignoreRuleOverridden(cleanTag)
            val bg = when {
                isNegation -> CodeAssistTheme.SURFACE_RAISED
                overridden -> CodeAssistTheme.TAG_CONFLICT
                else -> CodeAssistTheme.TAG_EXCLUDE
            }
            val fg = when {
                isNegation -> CodeAssistTheme.TEXT_SECONDARY
                overridden -> CodeAssistTheme.TAG_CONFLICT_TEXT
                else -> CodeAssistTheme.TAG_EXCLUDE_TEXT
            }
            val displayText = if (isNegation) "!$cleanTag" else cleanTag
            flowGitignore.addView(createTagView(displayText, bg, fg, null))
        }
    }

    private fun addEmptyLabel(flow: FlowLayout, label: String) {
        flow.addView(activity.textView(label, 11f, CodeAssistTheme.TEXT_MUTED).apply {
            setPadding(dp(2), dp(5), dp(2), dp(5))
        })
    }

    private fun addTagToFlow(flow: FlowLayout, tag: String, kind: TagKind) {
        val isInclusion = kind == TagKind.INCLUSION
        val isStub = kind == TagKind.STUB
        val gitignoreOverride = userRuleOverridesGitignore(tag)
        val listConflict = when (kind) {
            TagKind.INCLUSION -> currentConfig.exclusions.any { it.equals(tag, ignoreCase = true) }
            TagKind.EXCLUSION -> currentConfig.inclusions.any { it.equals(tag, ignoreCase = true) }
            TagKind.STUB -> false
        }
        val conflict = gitignoreOverride || listConflict
        val bg = when {
            conflict -> CodeAssistTheme.TAG_CONFLICT
            isInclusion -> CodeAssistTheme.TAG_INCLUDE
            isStub -> CodeAssistTheme.SURFACE_RAISED
            else -> CodeAssistTheme.TAG_EXCLUDE
        }
        val fg = when {
            conflict -> CodeAssistTheme.TAG_CONFLICT_TEXT
            isInclusion -> CodeAssistTheme.TAG_INCLUDE_TEXT
            isStub -> CodeAssistTheme.TEXT_SECONDARY
            else -> CodeAssistTheme.TAG_EXCLUDE_TEXT
        }
        val view = createTagView(tag, bg, fg) { showTagOptions(tag, kind) }
        flow.addView(view)
        tagViews[tagKey(tag, kind)] = view
    }

    private fun isGitignoreRuleOverridden(gitignoreRule: String): Boolean {
        val cleanTag = gitignoreRule.removePrefix("!")
        return currentConfig.inclusions.any { ruleMatchesGitignoreTag(it, cleanTag) } ||
            currentConfig.exclusions.any { ruleMatchesGitignoreTag(it, cleanTag) } ||
            currentConfig.stubPatterns.any { ruleMatchesGitignoreTag(it, cleanTag) }
    }

    private fun userRuleOverridesGitignore(rule: String): Boolean =
        gitignoreTags.any { gitignoreTag ->
            ruleMatchesGitignoreTag(rule, gitignoreTag.removePrefix("!"))
        }

    private fun ruleMatchesGitignoreTag(userRule: String, gitignoreTag: String): Boolean {
        val normalizedTag = gitignoreTag.removePrefix("/").removeSuffix("/")
        val tagExtension = normalizedTag.removePrefix("*.").lowercase()
        val rule = userRule.trim()
        if (rule.isEmpty()) return false
        val ruleExtension = rule.removePrefix(".").lowercase()
        return rule.equals(tagExtension, ignoreCase = true) ||
            rule.equals(normalizedTag, ignoreCase = true) ||
            rule.equals(gitignoreTag, ignoreCase = true) ||
            ruleExtension.equals(tagExtension, ignoreCase = true) ||
            globMatchTag(rule, normalizedTag)
    }

    private fun globMatchTag(pattern: String, value: String): Boolean {
        if (!pattern.contains('*') && !pattern.contains('?')) return false
        return runCatching {
            val regex = buildString {
                append('^')
                var i = 0
                while (i < pattern.length) {
                    when (val c = pattern[i]) {
                        '*' -> {
                            if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                                if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                                    append("(?:.*/)?"); i += 2
                                } else { append(".*"); i++ }
                            } else append("[^/]*")
                        }
                        '?' -> append("[^/]")
                        '.', '(', ')', '+', '|', '^', '$', '\\', '{', '}', '[', ']' ->
                            append('\\').append(c)
                        else -> append(c)
                    }
                    i++
                }
                append('$')
            }
            Regex(regex, RegexOption.IGNORE_CASE).matches(value)
        }.getOrDefault(false)
    }

    private fun createTagView(label: String, bg: Int, fg: Int, onClick: (() -> Unit)?): TextView =
        activity.textView(label, 11f, fg, true).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = CodeAssistTheme.roundedBg(activity, bg, 7f)
            if (onClick != null) {
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }
        }

    private fun addTag(value: String, kind: TagKind) {
        val tag = value.trim().lowercase()
        if (tag.isEmpty()) return
        val existing = when (kind) {
            TagKind.INCLUSION -> currentConfig.inclusions
            TagKind.EXCLUSION -> currentConfig.exclusions
            TagKind.STUB -> currentConfig.stubPatterns
        }
        if (existing.any { it.equals(tag, ignoreCase = true) }) return
        currentConfig = when (kind) {
            TagKind.INCLUSION -> currentConfig.copy(inclusions = currentConfig.inclusions + tag)
            TagKind.EXCLUSION -> currentConfig.copy(exclusions = currentConfig.exclusions + tag)
            TagKind.STUB -> currentConfig.copy(stubPatterns = currentConfig.stubPatterns + tag)
        }
        markAsCustom()
        updateTagsUI(animate = false)
        tagViews[tagKey(tag, kind)]?.let { animateTagIn(it) }
    }

    private fun showTagOptions(tag: String, kind: TagKind) {
        AlertDialog.Builder(activity)
            .setTitle(tag)
            .setItems(arrayOf(
                activity.pluginString(R.string.edit_extension),
                activity.pluginString(R.string.delete_rule)
            )) { _, which ->
                if (which == 0) {
                    showAddTagDialog(activity.pluginString(R.string.edit_extension), tag) { updated ->
                        val normalized = updated.trim().lowercase()
                        if (normalized.isNotEmpty() && normalized != tag) {
                            currentConfig = when (kind) {
                                TagKind.INCLUSION -> currentConfig.copy(
                                    inclusions = currentConfig.inclusions
                                        .map { if (it == tag) normalized else it }
                                        .distinctBy { it.lowercase() }
                                )
                                TagKind.EXCLUSION -> currentConfig.copy(
                                    exclusions = currentConfig.exclusions
                                        .map { if (it == tag) normalized else it }
                                        .distinctBy { it.lowercase() }
                                )
                                TagKind.STUB -> currentConfig.copy(
                                    stubPatterns = currentConfig.stubPatterns
                                        .map { if (it == tag) normalized else it }
                                        .distinctBy { it.lowercase() }
                                )
                            }
                            markAsCustom()
                            updateTagsUI(animate = false)
                            tagViews[tagKey(normalized, kind)]?.let { animateTagIn(it) }
                        }
                    }
                } else {
                    val view = tagViews[tagKey(tag, kind)]
                    val applyDelete = {
                        currentConfig = when (kind) {
                            TagKind.INCLUSION -> currentConfig.copy(inclusions = currentConfig.inclusions - tag)
                            TagKind.EXCLUSION -> currentConfig.copy(exclusions = currentConfig.exclusions - tag)
                            TagKind.STUB -> currentConfig.copy(stubPatterns = currentConfig.stubPatterns - tag)
                        }
                        markAsCustom()
                        updateTagsUI(animate = false)
                    }
                    if (view != null) animateTagOut(view, applyDelete) else applyDelete()
                }
            }
            .show().styleDialog()
    }

    private fun showAddTagDialog(title: String, initial: String = "", onAccept: (String) -> Unit) {
        val input = activity.editText(initial).apply {
            hint = activity.pluginString(R.string.extension_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(activity.pluginString(R.string.accept)) { _, _ ->
                input.text.toString().trim().lowercase().takeIf { it.isNotEmpty() }?.let(onAccept)
            }
            .setNegativeButton(activity.pluginString(R.string.cancel), null)
            .show().styleDialog()
    }

    private fun getPresetList(): MutableList<String> = mutableListOf(customPresetLabel()).apply {
        addAll(presetManager.getDefaultPresets().keys)
        addAll(presetManager.getUserPresetNames())
    }

    private fun showSavePresetDialog() {
        val input = activity.editText("").apply { hint = activity.pluginString(R.string.preset_name_hint) }
        AlertDialog.Builder(activity)
            .setTitle(activity.pluginString(R.string.save_preset))
            .setView(input)
            .setPositiveButton(activity.pluginString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                val caseConflict = presetManager.getUserPresetNames()
                    .firstOrNull { it.equals(name, ignoreCase = true) && it != name }
                when {
                    name.isBlank() || name.equals(customPresetLabel(), ignoreCase = true) -> Unit
                    presetManager.isBuiltInPresetName(name) ->
                        Toast.makeText(activity,
                            activity.pluginString(R.string.preset_name_reserved),
                            Toast.LENGTH_LONG).show()
                    caseConflict != null ->
                        Toast.makeText(activity,
                            activity.pluginString(R.string.preset_name_duplicate, name),
                            Toast.LENGTH_LONG).show()
                    else -> runCatching {
                        readUIValues()
                        presetManager.savePreset(name, currentConfig)
                    }.onSuccess {
                        presetAdapter = activity.spinnerAdapter(getPresetList())
                        spinnerPresets.adapter = presetAdapter
                        isUpdatingUI = true
                        spinnerPresets.setSelection(presetAdapter.getPosition(name))
                        isUpdatingUI = false
                        isCustomMode = false
                        currentPresetName = name
                        btnSavePreset.visibility = View.GONE
                        btnDeletePreset.visibility = if (presetManager.isUserPreset(name)) View.VISIBLE else View.GONE
                        animatePresetRow(saved = true)
                    }.onFailure { error ->
                        Toast.makeText(activity,
                            error.message ?: activity.pluginString(R.string.unknown_error),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(activity.pluginString(R.string.cancel), null)
            .show().styleDialog()
    }

    private fun confirmDeletePreset() {
        val selected = spinnerPresets.selectedItem?.toString() ?: return
        if (!presetManager.isUserPreset(selected)) return
        AlertDialog.Builder(activity)
            .setTitle(activity.pluginString(R.string.delete_preset))
            .setMessage(activity.pluginString(R.string.delete_preset_message, selected))
            .setPositiveButton(activity.pluginString(R.string.delete)) { _, _ ->
                presetManager.deleteUserPreset(selected)
                animatePresetRow(saved = false) {
                    presetAdapter = activity.spinnerAdapter(getPresetList())
                    spinnerPresets.adapter = presetAdapter
                    isUpdatingUI = true
                    spinnerPresets.setSelection(0, false)
                    isUpdatingUI = false
                    isCustomMode = true
                    currentPresetName = customPresetLabel()
                    btnSavePreset.visibility = View.VISIBLE
                    btnDeletePreset.visibility = View.GONE
                }
            }
            .setNegativeButton(activity.pluginString(R.string.cancel), null)
            .show().styleDialog()
    }

    private fun animatePresetRow(saved: Boolean, onEnd: (() -> Unit)? = null) {
        if (!animationsEnabled) { onEnd?.invoke(); return }
        val target = if (saved) btnSavePreset else btnDeletePreset
        target.animate().cancel()
        target.scaleX = 0.6f; target.scaleY = 0.6f; target.alpha = 0f
        target.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(220).setInterpolator(OvershootInterpolator(1.4f))
            .withEndAction { onEnd?.invoke() }.start()
        spinnerPresets.animate().cancel()
        spinnerPresets.alpha = 0.4f
        spinnerPresets.animate().alpha(1f).setDuration(240)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun AlertDialog.styleDialog() {
        window?.setBackgroundDrawable(CodeAssistTheme.roundedBg(
            activity, CodeAssistTheme.SURFACE_RAISED, 12f, CodeAssistTheme.OUTLINE
        ))
        findViewById<TextView>(android.R.id.title)?.setTextColor(CodeAssistTheme.TEXT_PRIMARY)
        findViewById<TextView>(android.R.id.message)?.setTextColor(CodeAssistTheme.TEXT_SECONDARY)
        getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(CodeAssistTheme.ACCENT)
        getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(CodeAssistTheme.TEXT_SECONDARY)
        getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(CodeAssistTheme.TEXT_SECONDARY)
    }

    private companion object {

        const val REQUEST_STORAGE_PERMISSION = 0x0C0DE
    }
}