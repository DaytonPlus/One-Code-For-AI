package com.devdayton.onecodeforai.ui

import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.ExportAction
import com.devdayton.onecodeforai.android.ExportCoordinator
import com.devdayton.onecodeforai.android.ExportStateHolder
import com.devdayton.onecodeforai.core.FileFilter
import com.devdayton.onecodeforai.core.GitUtils
import com.devdayton.onecodeforai.core.FilterConfig
import com.devdayton.onecodeforai.core.PresetManager
import com.devdayton.onecodeforai.ui.FlowLayout
import com.devdayton.onecodeforai.ui.UiFactory
import com.devdayton.onecodeforai.ui.CodeAssistTheme
import com.devdayton.onecodeforai.ui.ProjectTreeView
import com.devdayton.onecodeforai.ui.GitDiffPreviewDialog
import com.devdayton.onecodeforai.ui.GitReferencePickerDialog

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import com.devdayton.onecodeforai.ui.UiFactory.sectionTitle
import com.devdayton.onecodeforai.ui.UiFactory.spinnerAdapter
import com.devdayton.onecodeforai.ui.UiFactory.textView

/**
 * Configuration screen for the dump generator.
 *
 * [PERF] Opening flow:
 *  1. `show()` displays an immediate loading spinner so the dialog appears instantly.
 *  2. `.gitignore` rules + last saved state are read on a background thread.
 *  3. The real UI (`buildFullUI`) is constructed on the main thread only once data is ready.
 *  4. The Git section (JGit access) is rendered with its own loading placeholder and
 *     resolved asynchronously — the dialog is never blocked by JGit class loading.
 */
class ConfigurationDialog(
    private val activity: Activity,
    private val projectRoot: java.io.File,
    private val gitEnabledGlobally: Boolean,
    private val treeEnabledGlobally: Boolean = true,
    private val initialConfig: FilterConfig = FilterConfig(),
    private val logInfo: (String) -> Unit = {},
    private val onDismiss: () -> Unit = {},
    private val onChangeRoot: (java.io.File) -> Unit = {},
) {
    private val presetManager = PresetManager(activity)
    private var activeDialog: Dialog? = null
    private var currentConfig = FilterConfig()
    private var isCustomMode = false
    private var isUpdatingUI = false
    private var gitignoreTags: List<String> = emptyList()

    // [PERF] Distinguishes "UI built" from "still loading", so the dismiss handler
    // can safely skip readUIValues()/saveLastState() when the user closes the
    // dialog while the initial data is still being loaded.
    private var uiBuilt = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rescanScheduled = false

    private lateinit var spinnerPresets: Spinner
    private lateinit var presetAdapter: ArrayAdapter<String>
    private lateinit var btnSavePreset: ImageButton
    private lateinit var btnDeletePreset: ImageButton
    private lateinit var cbBinaries: CheckBox
    private lateinit var cbGitignore: CheckBox
    private lateinit var cbMaxSize: CheckBox
    private lateinit var etMaxSize: EditText
    private lateinit var flowInclusions: FlowLayout
    private lateinit var flowExclusions: FlowLayout
    private lateinit var flowGitignore: FlowLayout
    private lateinit var gitignoreSection: LinearLayout
    private lateinit var layoutGitBox: LinearLayout

    // ---- Git ----
    private lateinit var cbUseGit: CheckBox
    private lateinit var etGitRef: EditText
    private lateinit var tvResolvedRef: TextView
    private lateinit var tvGitInfo: TextView
    private lateinit var tvGitWarning: TextView
    private lateinit var gitDetailsBox: LinearLayout
    private val resolveHandler = Handler(Looper.getMainLooper())
    private var pendingResolve: Runnable? = null

    // ---- Tree ----
    private var treeVisible = false
    private lateinit var cbShowTree: CheckBox
    private lateinit var treeActionsRow: LinearLayout
    private lateinit var treeWrapper: FrameLayout
    private lateinit var treeView: ProjectTreeView
    private lateinit var treeLoadingPanel: LinearLayout
    private lateinit var treeLoadingText: TextView
    private lateinit var treeViewContainer: View

    // ---- Inline progress / result ----
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

    // [PERF] Lazy: avoids loading JGit/MediaStore-related classes at dialog-open time.
    private val exportCoordinator: ExportCoordinator by lazy {
        ExportCoordinator(
            contextProvider = com.devdayton.onecodeforai.android.HostEnvironment::applicationContext,
            logger = { message -> logInfo(message) }
        )
    }

    private val tagViews = mutableMapOf<String, TextView>()

    // ------------------------------------------------------------------

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

    // ==================================================================
    // [PERF] Public entry — shows loading immediately, then builds the UI.
    // ==================================================================

    fun show() {
        val dialog = Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCancelable(true)
        }
        activeDialog = dialog

        // Always attach the dismiss handler before we show anything, so a user
        // dismissing the loading state still runs cleanup.
        dialog.setOnDismissListener { handleDismiss(dialog) }
        // 1) Immediate loading view so the dialog appears at once.
        dialog.setContentView(buildLoadingView())
        configureDialogWindow(dialog)
        dialog.show()

        // 2) Load heavy data in background, then build the real UI on Main.
        Thread({
            val gitignore = runCatching { FileFilter(projectRoot).loadGitignoreRules() }
                .getOrDefault(emptyList())
            val lastState = runCatching { presetManager.loadLastState() }.getOrNull()
            activity.runOnUiThread {
                if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                gitignoreTags = gitignore
                buildFullUI(dialog, lastState)
            }
        }, "OneCode-dialog-init").apply { isDaemon = true; start() }
    }

    /**
     * Skeleton shown while the real dialog content loads in the background.
     *
     * Mirrors the rough geometry of the finished UI — header with two text bars,
     * a divider, and three stacked panels — so the transition to the real content
     * reads as a reveal rather than a layout jump. A single accent spinner sits
     * where the close button will land, which is the only piece of real UI the
     * skeleton hints at.
     *
     * Deliberately does **not** call `pluginString(...)`: resolving any string
     * forces creation of the plugin package context, which is precisely the cost
     * this loading state exists to hide. Shapes only, no text.
     */
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

        // --- Header skeleton: title bar + subtitle bar + spinner (close slot) ---
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

        // --- Divider ---
        content.addView(View(activity).apply {
            setBackgroundColor(CodeAssistTheme.OUTLINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply {
                topMargin = dp(13)
                bottomMargin = dp(3)
            }
        })

        // --- Two fixed-size section skeletons ---
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

        // --- Flex section: fills the remaining vertical space so the skeleton
        //     reaches the bottom of the dialog instead of leaving a dead gap. ---
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

    /** A single rounded gray bar used by the loading skeleton. */
    private fun skeletonBar(
        width: Int,
        height: Int,
        radius: Float,
        topMargin: Int = 0
    ): View = View(activity).apply {
        background = CodeAssistTheme.roundedBg(activity, CodeAssistTheme.SURFACE_RAISED, radius)
        layoutParams = LinearLayout.LayoutParams(width, height).apply {
            this.topMargin = topMargin
        }
    }

    /** Local shorthand for [CodeAssistTheme.dp] so the skeleton reads cleanly. */
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
            // [PERF] Only persist if the real UI was actually built.
            if (uiBuilt && ::spinnerPresets.isInitialized) {
                readUIValues()
                presetManager.saveLastState(
                    currentConfig,
                    spinnerPresets.selectedItem?.toString() ?: customPresetLabel()
                )
            }
        } finally {
            onDismiss()
        }
    }

    // ==================================================================
    // [PERF] Full UI construction — was `show()` body in the original file.
    // ==================================================================

    private fun buildFullUI(
        dialog: Dialog,
        lastState: Pair<FilterConfig, String>?
    ) {
        currentConfig = lastState?.first ?: initialConfig.normalized()
        val lastPresetName = lastState?.second
        isCustomMode = lastPresetName.isNullOrBlank() ||
            lastPresetName == customPresetLabel() ||
            presetManager.loadPreset(lastPresetName) == null

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                CodeAssistTheme.dp(activity, 18), CodeAssistTheme.dp(activity, 16),
                CodeAssistTheme.dp(activity, 18), CodeAssistTheme.dp(activity, 18)
            )
            background = CodeAssistTheme.roundedBg(activity, CodeAssistTheme.BG, 15f, CodeAssistTheme.OUTLINE)
        }
        scroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // --- HEADER ---
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
        ).apply { setPadding(0, CodeAssistTheme.dp(activity, 5), 0, 0) })
        header.addView(headerText)
        header.addView(activity.iconButton(
            R.drawable.ic_tech_close, activity.pluginString(R.string.close)
        ) { dialog.dismiss() })
        root.addView(header)
        root.addView(divider())

        // --- CONFIG CONTAINER ---
        configContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(configContainer)

        // ============ FORM ============

        // Presets
        configContainer.addView(activity.sectionTitle(activity.pluginString(R.string.section_presets)))
        val presetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        presetAdapter = activity.spinnerAdapter(getPresetList())
        spinnerPresets = Spinner(activity).apply {
            adapter = presetAdapter
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.SURFACE_RAISED, 8f, CodeAssistTheme.OUTLINE
            )
            dropDownVerticalOffset = CodeAssistTheme.dp(activity, 4)
            setPopupBackgroundDrawable(ColorDrawable(CodeAssistTheme.SURFACE_RAISED))
            layoutParams = LinearLayout.LayoutParams(0, CodeAssistTheme.dp(activity, 42), 1f)
        }
        btnSavePreset = activity.iconButton(
            R.drawable.ic_tech_save, activity.pluginString(R.string.save_preset)
        ) { showSavePresetDialog() }.apply {
            visibility = if (isCustomMode) View.VISIBLE else View.GONE
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = CodeAssistTheme.dp(activity, 8)
            }
        }
        btnDeletePreset = activity.iconButton(
            R.drawable.ic_tech_trash, activity.pluginString(R.string.delete_preset)
        ) { confirmDeletePreset() }.apply {
            visibility = View.GONE
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = CodeAssistTheme.dp(activity, 8)
            }
        }
        presetRow.addView(spinnerPresets)
        presetRow.addView(btnSavePreset)
        presetRow.addView(btnDeletePreset)
        configContainer.addView(presetRow)

        // Base filters
        configContainer.addView(activity.sectionTitle(activity.pluginString(R.string.section_base_filters)))
        val basePanel = panel()
        cbBinaries = activity.checkBox(activity.pluginString(R.string.exclude_binaries)).apply {
            isChecked = currentConfig.excludeBinaries
            setOnCheckedChangeListener { _, _ -> if (!isUpdatingUI) markAsCustom() }
        }
        cbGitignore = activity.checkBox(activity.pluginString(R.string.respect_gitignore)).apply {
            isChecked = currentConfig.respectGitignore
            setOnCheckedChangeListener { _, _ ->
                if (!isUpdatingUI) { markAsCustom(); updateTagsUI(animate = true) }
            }
        }
        basePanel.addView(cbBinaries)
        basePanel.addView(cbGitignore)

        // Max size row: [checkbox] Tamaño máximo [input] KB
        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, CodeAssistTheme.dp(activity, 4), 0, 0)
        }
        cbMaxSize = activity.checkBox(activity.pluginString(R.string.max_size)).apply {
            isChecked = currentConfig.useMaxSize
            minHeight = CodeAssistTheme.dp(activity, 40)
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
            layoutParams = LinearLayout.LayoutParams(
                CodeAssistTheme.dp(activity, 78), CodeAssistTheme.dp(activity, 40)
            )
        }
        sizeRow.addView(etMaxSize)
        sizeRow.addView(activity.textView(
            activity.pluginString(R.string.kb_suffix), 11f, CodeAssistTheme.TEXT_SECONDARY
        ).apply { setPadding(CodeAssistTheme.dp(activity, 6), 0, 0, 0) })
        basePanel.addView(sizeRow)
        configContainer.addView(basePanel)

        // Tags
        configContainer.addView(tagSectionHeader(
            activity.pluginString(R.string.section_inclusions),
            activity.pluginString(R.string.action_add)
        ) { showAddTagDialog(activity.pluginString(R.string.add_inclusion_title)) { addTag(it, true) } })
        flowInclusions = createFlow()
        configContainer.addView(flowInclusions)

        configContainer.addView(tagSectionHeader(
            activity.pluginString(R.string.section_exclusions),
            activity.pluginString(R.string.action_add)
        ) { showAddTagDialog(activity.pluginString(R.string.add_exclusion_title)) { addTag(it, false) } })
        flowExclusions = createFlow()
        configContainer.addView(flowExclusions)

        gitignoreSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        gitignoreSection.addView(activity.sectionTitle(activity.pluginString(R.string.section_gitignore_rules)))
        flowGitignore = createFlow()
        gitignoreSection.addView(flowGitignore)
        configContainer.addView(gitignoreSection)

        // Git
        if (gitEnabledGlobally) {
            configContainer.addView(activity.sectionTitle(activity.pluginString(R.string.section_version_control)))
            layoutGitBox = panel()
            configContainer.addView(layoutGitBox)
            setupGitUIAsync()
        }

        // Tree
        if (treeEnabledGlobally) {
            configContainer.addView(activity.sectionTitle(activity.pluginString(R.string.section_tree)))
            val treeTogglePanel = panel()
            cbShowTree = activity.checkBox(activity.pluginString(R.string.show_tree)).apply {
                isChecked = false
                setOnCheckedChangeListener { _, checked -> setTreeVisibility(checked) }
            }
            treeTogglePanel.addView(cbShowTree)
            configContainer.addView(treeTogglePanel)

            treeActionsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, CodeAssistTheme.dp(activity, 4), 0, CodeAssistTheme.dp(activity, 4))
                visibility = View.GONE
            }
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
                    ViewGroup.LayoutParams.MATCH_PARENT, CodeAssistTheme.dp(activity, 280)
                )
                visibility = View.GONE
            }
            treeView = ProjectTreeView(
                activity = activity,
                projectRoot = projectRoot,
                onExcludedPathsChanged = { excluded ->
                    currentConfig = currentConfig.copy(excludedPaths = excluded)
                    markAsCustomOnly()
                },
                onStatsChanged = { /* Stats are displayed in the result panel after generation. */ }
            )
            treeViewContainer = treeView.getView().apply { visibility = View.GONE }
            treeWrapper.addView(treeViewContainer, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            treeLoadingPanel = buildTreeLoadingPanel()
            treeWrapper.addView(treeLoadingPanel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            configContainer.addView(treeWrapper)
        }

        // Single "Generate" button — stats are shown after generation.
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
            minHeight = CodeAssistTheme.dp(activity, 46)
            setOnClickListener {
                readUIValues()
                presetManager.saveLastState(
                    currentConfig,
                    spinnerPresets.selectedItem?.toString() ?: customPresetLabel()
                )
                startExport(ExportAction.GENERATE)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, CodeAssistTheme.dp(activity, 46)
            ).apply { topMargin = CodeAssistTheme.dp(activity, 22) }
        }
        attachPressScale(generateButton)
        configContainer.addView(generateButton)

        configContainer.addView(activity.textView(
            activity.pluginString(R.string.generate_description),
            10f, CodeAssistTheme.TEXT_MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(
                CodeAssistTheme.dp(activity, 8), CodeAssistTheme.dp(activity, 9),
                CodeAssistTheme.dp(activity, 8), 0
            )
        })

        // --- PROGRESS CONTAINER ---
        progressContainer = buildProgressContainer()
        progressContainer.visibility = View.GONE
        root.addView(progressContainer)

        dialog.setContentView(scroll)

        stateUnsubscribe = ExportStateHolder.subscribe { snapshot ->
            if (activity.isFinishing || activity.isDestroyed) return@subscribe
            applyState(snapshot)
        }

        val initialIndex = resolveInitialPresetIndex(lastPresetName)
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
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        uiBuilt = true

        // Trigger entrance animation once the real content is laid out.
        scroll.post { playEntrance(configContainer) }
    }

    // ==================================================================
    // Inline progress / result section
    // ==================================================================

    private fun buildProgressContainer(): LinearLayout {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                CodeAssistTheme.dp(activity, 4), CodeAssistTheme.dp(activity, 8),
                CodeAssistTheme.dp(activity, 4), CodeAssistTheme.dp(activity, 8)
            )
        }

        progressIcon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                CodeAssistTheme.dp(activity, 56), CodeAssistTheme.dp(activity, 56)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = CodeAssistTheme.dp(activity, 24)
            }
        }
        panel.addView(progressIcon)

        progressTitle = activity.textView(
            activity.pluginString(R.string.generating_context), 18f, CodeAssistTheme.TEXT_PRIMARY, true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, CodeAssistTheme.dp(activity, 16), 0, CodeAssistTheme.dp(activity, 6))
        }
        panel.addView(progressTitle)

        progressSubtitle = activity.textView(
            activity.pluginString(R.string.preparing_export), 12f, CodeAssistTheme.TEXT_SECONDARY
        ).apply {
            gravity = Gravity.CENTER
            setPadding(
                CodeAssistTheme.dp(activity, 8), 0,
                CodeAssistTheme.dp(activity, 8), CodeAssistTheme.dp(activity, 12)
            )
        }
        panel.addView(progressSubtitle)

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, CodeAssistTheme.dp(activity, 6)
            ).apply {
                marginStart = CodeAssistTheme.dp(activity, 24)
                marginEnd = CodeAssistTheme.dp(activity, 24)
                bottomMargin = CodeAssistTheme.dp(activity, 12)
            }
        }
        panel.addView(progressBar)

        progressMinimizeHint = activity.textView(
            activity.pluginString(R.string.generation_minimize_hint), 10f, CodeAssistTheme.TEXT_MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(
                CodeAssistTheme.dp(activity, 12), 0,
                CodeAssistTheme.dp(activity, 12), CodeAssistTheme.dp(activity, 16)
            )
        }
        panel.addView(progressMinimizeHint)

        progressStats = activity.textView("", 12f, CodeAssistTheme.TEXT_SECONDARY).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, CodeAssistTheme.dp(activity, 12))
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
            minHeight = CodeAssistTheme.dp(activity, 42)
            setOnClickListener { ExportCoordinator.cancelCurrent() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, CodeAssistTheme.dp(activity, 42)
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        attachPressScale(progressCancelBtn)
        panel.addView(progressCancelBtn)

        progressActionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, CodeAssistTheme.dp(activity, 6), 0, 0)
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
                topMargin = CodeAssistTheme.dp(activity, 8)
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
            setPadding(
                CodeAssistTheme.dp(activity, 14), CodeAssistTheme.dp(activity, 10),
                CodeAssistTheme.dp(activity, 14), CodeAssistTheme.dp(activity, 10)
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = CodeAssistTheme.dp(activity, 4)
                marginEnd = CodeAssistTheme.dp(activity, 4)
            }
        }
        chip.addView(ImageView(activity).apply {
            setImageDrawable(activity.loadPluginDrawable(iconRes))
            setColorFilter(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                CodeAssistTheme.dp(activity, 20), CodeAssistTheme.dp(activity, 20)
            )
        })
        chip.addView(activity.textView(
            activity.pluginString(labelRes), 11f, CodeAssistTheme.TEXT_PRIMARY, true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, CodeAssistTheme.dp(activity, 6), 0, 0)
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
            // La barra avanza por bytes; la escala fija evita límites de Int en ProgressBar.
            val scale = 10_000L
            val ratio = (snapshot.bytesProcessed.coerceAtMost(snapshot.bytesTotal) * scale) / snapshot.bytesTotal
            progressBar.isIndeterminate = false
            progressBar.max = scale.toInt()
            progressBar.progress = ratio.toInt()
        } else if (snapshot.total > 0 && snapshot.processed > 0) {
            progressBar.isIndeterminate = false
            progressBar.max = snapshot.total
            progressBar.progress = snapshot.processed
        } else {
            progressBar.isIndeterminate = true
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

        // Stats — shown only after generation completes.
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
        val filesLabel = if (snapshot.filesCount == 1) {
            activity.pluginString(R.string.stats_files_one, snapshot.filesCount)
        } else {
            activity.pluginString(R.string.stats_files_many, snapshot.filesCount)
        }
        val tokensLabel = activity.pluginString(
            R.string.stats_tokens, formatNumber(snapshot.estimatedTokens)
        )
        return "$filesLabel  ·  $tokensLabel  ·  ${formatSize(snapshot.totalBytes)}"
    }

    private fun startExport(action: ExportAction) {
        if (!exportCoordinator.export(projectRoot, currentConfig, action)) {
            Toast.makeText(
                activity, activity.pluginString(R.string.export_already_running), Toast.LENGTH_SHORT
            ).show()
        }
    }

    // -------- Action chip handlers ---------

    private fun onShareResultClicked() {
        val snap = ExportStateHolder.snapshot
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
        val uri = snap.uri ?: return
        if (!exportCoordinator.export(
                root = projectRoot,
                config = currentConfig,
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

    // ==================================================================
    // Tree
    // ==================================================================

    private fun setTreeVisibility(visible: Boolean) {
        treeVisible = visible
        if (::treeActionsRow.isInitialized) treeActionsRow.visibility = if (visible) View.VISIBLE else View.GONE
        if (::treeWrapper.isInitialized) treeWrapper.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) startTreeScan() else if (::treeView.isInitialized) treeView.cancelScan()
    }

    private fun reloadTree() { if (treeVisible) startTreeScan() }

    private fun buildTreeLoadingPanel(): LinearLayout {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 16),
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 16)
            )
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.SURFACE, 10f, CodeAssistTheme.OUTLINE
            )
        }
        panel.addView(ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                CodeAssistTheme.dp(activity, 34), CodeAssistTheme.dp(activity, 34)
            )
        })
        treeLoadingText = activity.textView(
            activity.pluginString(R.string.analyzing), 12f, CodeAssistTheme.TEXT_SECONDARY
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, CodeAssistTheme.dp(activity, 10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        panel.addView(treeLoadingText)
        panel.addView(Button(activity).apply {
            text = activity.pluginString(R.string.cancel)
            textSize = 12f
            isAllCaps = false
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            background = CodeAssistTheme.selectableBg(
                activity, CodeAssistTheme.SURFACE_RAISED, CodeAssistTheme.SURFACE,
                8f, CodeAssistTheme.OUTLINE
            )
            minHeight = CodeAssistTheme.dp(activity, 36)
            setPadding(
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 6),
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 6)
            )
            setOnClickListener {
                if (::treeView.isInitialized) treeView.cancelScan()
                treeLoadingText.text = activity.pluginString(R.string.canceled_retry)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, CodeAssistTheme.dp(activity, 36)
            ).apply {
                topMargin = CodeAssistTheme.dp(activity, 12)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        return panel
    }

    private fun startTreeScan() {
        if (!treeVisible || !::treeView.isInitialized) return
        readUIValues()
        treeLoadingPanel.visibility = View.VISIBLE
        treeViewContainer.visibility = View.GONE
        treeLoadingText.text = activity.pluginString(R.string.analyzing)
        treeView.scanAsync(
            config = currentConfig,
            onProgress = { count ->
                if (::treeLoadingText.isInitialized) {
                    treeLoadingText.text = activity.pluginString(R.string.analyzing_files, count)
                }
            },
            onComplete = { root ->
                treeLoadingPanel.visibility = View.GONE
                treeViewContainer.visibility = View.VISIBLE
                treeView.renderTree(root)
            },
            onError = { error ->
                treeLoadingPanel.visibility = View.GONE
                treeViewContainer.visibility = View.VISIBLE
                Toast.makeText(
                    activity,
                    activity.pluginString(R.string.tree_scan_error, error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    // ==================================================================
    // [PERF] Git — async first load + async refresh
    // ==================================================================

    /**
     * Shows a small loading row in `layoutGitBox` while JGit resolves the
     * repository info + status in a background thread. This avoids blocking
     * the first open of the dialog with JGit class loading / disk I/O.
     */
    private fun setupGitUIAsync() {
        val loading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, CodeAssistTheme.dp(activity, 8), 0, CodeAssistTheme.dp(activity, 8))
        }
        loading.addView(ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                CodeAssistTheme.dp(activity, 18), CodeAssistTheme.dp(activity, 18)
            )
        })
        loading.addView(activity.textView(
            activity.pluginString(R.string.git_resolving), 11f, CodeAssistTheme.TEXT_MUTED
        ).apply { setPadding(CodeAssistTheme.dp(activity, 8), 0, 0, 0) })
        layoutGitBox.addView(loading)

        Thread({
            val repoInfo = runCatching { GitUtils.getRepoInfo(projectRoot) }.getOrNull()
            val uncommitted = if (repoInfo?.hasUncommittedChanges == true) {
                runCatching { GitUtils.countUncommittedChanges(projectRoot) }.getOrDefault(0)
            } else 0
            activity.runOnUiThread {
                if (!::layoutGitBox.isInitialized || !uiBuilt) return@runOnUiThread
                layoutGitBox.removeAllViews()
                if (repoInfo == null) {
                    renderNoRepoState()
                } else {
                    renderGitInfoState(repoInfo, uncommitted)
                }
            }
        }, "OneCode-git-info").apply { isDaemon = true; start() }
    }

    private fun renderNoRepoState() {
        layoutGitBox.addView(activity.textView(
            activity.pluginString(R.string.git_not_repo), 11f, CodeAssistTheme.TEXT_MUTED
        ).apply { setPadding(0, CodeAssistTheme.dp(activity, 4), 0, CodeAssistTheme.dp(activity, 4)) })
        val parentRoot = GitUtils.findGitRoot(projectRoot.parentFile ?: projectRoot)
        if (parentRoot != null && parentRoot.absolutePath != projectRoot.absolutePath) {
            layoutGitBox.addView(activity.actionText(
                activity.pluginString(R.string.git_open_parent_root, parentRoot.name)
            ) { activeDialog?.dismiss(); onChangeRoot(parentRoot) }.apply {
                setPadding(0, CodeAssistTheme.dp(activity, 6), 0, CodeAssistTheme.dp(activity, 6))
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
        layoutGitBox.addView(headerRow)

        tvGitWarning = activity.textView(
            activity.pluginString(R.string.git_uncommitted_changes, uncommitted),
            10f, CodeAssistTheme.TAG_CONFLICT_TEXT
        ).apply {
            setPadding(0, CodeAssistTheme.dp(activity, 2), 0, CodeAssistTheme.dp(activity, 2))
            visibility = if (repoInfo.hasUncommittedChanges) View.VISIBLE else View.GONE
        }
        layoutGitBox.addView(tvGitWarning)

        cbUseGit = activity.checkBox(activity.pluginString(R.string.read_from_git)).apply {
            isChecked = currentConfig.useGit
            setOnCheckedChangeListener { _, checked ->
                if (::gitDetailsBox.isInitialized) gitDetailsBox.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked) updateResolvedRef()
                if (!isUpdatingUI) markAsCustom()
            }
        }
        layoutGitBox.addView(cbUseGit)

        gitDetailsBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentConfig.useGit) View.VISIBLE else View.GONE
            setPadding(0, CodeAssistTheme.dp(activity, 4), 0, 0)
        }
        val refRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        etGitRef = activity.editText(currentConfig.gitRef ?: "").apply {
            hint = activity.pluginString(R.string.git_ref_hint)
            layoutParams = LinearLayout.LayoutParams(0, CodeAssistTheme.dp(activity, 42), 1f)
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
                marginStart = CodeAssistTheme.dp(activity, 8)
            }
        })
        gitDetailsBox.addView(refRow)

        tvResolvedRef = activity.textView("", 10f, CodeAssistTheme.TEXT_MUTED).apply {
            setPadding(0, CodeAssistTheme.dp(activity, 4), 0, CodeAssistTheme.dp(activity, 4))
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
            minHeight = CodeAssistTheme.dp(activity, 38)
            setOnClickListener { showDiffPreview() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, CodeAssistTheme.dp(activity, 38)
            ).apply { topMargin = CodeAssistTheme.dp(activity, 8) }
        })

        layoutGitBox.addView(gitDetailsBox)
        updateResolvedRef()
    }

    /**
     * [PERF] Was previously doing two JGit calls on the UI thread when the
     * user tapped the refresh icon. Now runs entirely in the background.
     */
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

    // ==================================================================
    // Animations
    // ==================================================================

    private fun playEntrance(root: ViewGroup) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.alpha = 0f
            child.translationY = CodeAssistTheme.dp(activity, 10).toFloat()
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 30L).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun animateTagIn(view: View) {
        view.animate().cancel()
        view.scaleX = 0.5f; view.scaleY = 0.5f; view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(240).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun animateTagOut(view: View, onEnd: () -> Unit) {
        view.animate().cancel()
        view.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f)
            .setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction(onEnd).start()
    }

    private fun attachPressScale(button: View) {
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

    // ==================================================================
    // Formatting
    // ==================================================================

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

    // ==================================================================
    // UI helpers
    // ==================================================================

    private fun panel(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            CodeAssistTheme.dp(activity, 12), CodeAssistTheme.dp(activity, 7),
            CodeAssistTheme.dp(activity, 12), CodeAssistTheme.dp(activity, 10)
        )
        background = CodeAssistTheme.roundedBg(
            activity, CodeAssistTheme.SURFACE, 10f, CodeAssistTheme.OUTLINE
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, CodeAssistTheme.dp(activity, 1)
        ).apply {
            topMargin = CodeAssistTheme.dp(activity, 13)
            bottomMargin = CodeAssistTheme.dp(activity, 3)
        }
    }

    private fun createFlow(): FlowLayout = FlowLayout(activity).apply {
        setPadding(0, CodeAssistTheme.dp(activity, 2), 0, CodeAssistTheme.dp(activity, 5))
        minimumHeight = CodeAssistTheme.dp(activity, 16)
    }

    private fun tagSectionHeader(title: String, action: String, onAction: () -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, CodeAssistTheme.dp(activity, 8), 0, CodeAssistTheme.dp(activity, 4))
            addView(activity.textView(title, 13f, CodeAssistTheme.TEXT_PRIMARY, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(activity.actionText(action, onAction))
        }

    private fun applyConfigToUI(config: FilterConfig) {
        isUpdatingUI = true
        currentConfig = config
        cbBinaries.isChecked = config.excludeBinaries
        cbGitignore.isChecked = config.respectGitignore
        cbMaxSize.isChecked = config.useMaxSize
        etMaxSize.setText(config.maxSizeKb.toString())
        etMaxSize.isEnabled = config.useMaxSize
        etMaxSize.alpha = if (config.useMaxSize) 1f else 0.4f

        if (gitEnabledGlobally && ::cbUseGit.isInitialized) {
            cbUseGit.isChecked = config.useGit
            if (::etGitRef.isInitialized) etGitRef.setText(config.gitRef ?: "")
            if (::gitDetailsBox.isInitialized) {
                gitDetailsBox.visibility = if (config.useGit) View.VISIBLE else View.GONE
            }
            updateResolvedRef()
        }
        updateTagsUI(animate = true)
        isUpdatingUI = false
        if (treeVisible) startTreeScan()
    }

    private fun markAsCustomOnly() {
        if (!isCustomMode) {
            isCustomMode = true
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
            !::cbGitignore.isInitialized || !::cbMaxSize.isInitialized) return
        val size = etMaxSize.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 512
        var useGit = false
        var gitRef: String? = null
        if (gitEnabledGlobally && ::cbUseGit.isInitialized) {
            useGit = cbUseGit.isChecked
            if (useGit && ::etGitRef.isInitialized) {
                val ref = etGitRef.text.toString().trim()
                if (ref.isNotEmpty() && GitUtils.refExists(projectRoot, ref)) gitRef = ref
                else useGit = false
            }
        }
        currentConfig = currentConfig.copy(
            excludeBinaries = cbBinaries.isChecked,
            respectGitignore = cbGitignore.isChecked,
            useMaxSize = cbMaxSize.isChecked,
            maxSizeKb = size,
            useGit = useGit,
            gitRef = gitRef
        )
    }

    private fun tagKey(tag: String, isInclusion: Boolean) = "${if (isInclusion) "I" else "E"}:$tag"

    private fun updateTagsUI(animate: Boolean = false) {
        if (!::flowInclusions.isInitialized) return
        flowInclusions.removeAllViews()
        flowExclusions.removeAllViews()
        flowGitignore.removeAllViews()
        tagViews.clear()

        if (currentConfig.inclusions.isEmpty()) addEmptyLabel(flowInclusions, activity.pluginString(R.string.no_inclusions))
        else currentConfig.inclusions.forEach { addTagToFlow(flowInclusions, it, true) }

        if (currentConfig.exclusions.isEmpty()) addEmptyLabel(flowExclusions, activity.pluginString(R.string.no_exclusions))
        else currentConfig.exclusions.forEach { addTagToFlow(flowExclusions, it, false) }

        gitignoreSection.visibility = if (currentConfig.respectGitignore) View.VISIBLE else View.GONE
        if (currentConfig.respectGitignore) {
            if (gitignoreTags.isEmpty()) {
                addEmptyLabel(flowGitignore, activity.pluginString(R.string.no_gitignore_rules))
            } else {
                gitignoreTags.forEach { tag ->
                    val overridden = currentConfig.inclusions.contains(tag) ||
                        currentConfig.exclusions.contains(tag)
                    val bg = if (overridden) CodeAssistTheme.TAG_CONFLICT else CodeAssistTheme.TAG_EXCLUDE
                    val fg = if (overridden) CodeAssistTheme.TAG_CONFLICT_TEXT else CodeAssistTheme.TAG_EXCLUDE_TEXT
                    flowGitignore.addView(createTagView(tag, bg, fg, null))
                }
            }
        }

        if (animate) {
            val all = listOf(flowInclusions, flowExclusions, flowGitignore)
            var i = 0
            all.forEach { flow ->
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

    private fun addEmptyLabel(flow: FlowLayout, label: String) {
        flow.addView(activity.textView(label, 11f, CodeAssistTheme.TEXT_MUTED).apply {
            setPadding(
                CodeAssistTheme.dp(activity, 2), CodeAssistTheme.dp(activity, 5),
                CodeAssistTheme.dp(activity, 2), CodeAssistTheme.dp(activity, 5)
            )
        })
    }

    private fun addTagToFlow(flow: FlowLayout, tag: String, isInclusion: Boolean) {
        val conflict = gitignoreTags.contains(tag) ||
            (if (isInclusion) currentConfig.exclusions.contains(tag) else currentConfig.inclusions.contains(tag))
        val bg = when { conflict -> CodeAssistTheme.TAG_CONFLICT; isInclusion -> CodeAssistTheme.TAG_INCLUDE; else -> CodeAssistTheme.TAG_EXCLUDE }
        val fg = when { conflict -> CodeAssistTheme.TAG_CONFLICT_TEXT; isInclusion -> CodeAssistTheme.TAG_INCLUDE_TEXT; else -> CodeAssistTheme.TAG_EXCLUDE_TEXT }
        val view = createTagView(tag, bg, fg) { showTagOptions(tag, isInclusion) }
        flow.addView(view)
        tagViews[tagKey(tag, isInclusion)] = view
    }

    private fun createTagView(label: String, bg: Int, fg: Int, onClick: (() -> Unit)?): TextView =
        activity.textView(label, 11f, fg, true).apply {
            setPadding(
                CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 7),
                CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 7)
            )
            background = CodeAssistTheme.roundedBg(activity, bg, 7f)
            if (onClick != null) {
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }
        }

    private fun addTag(value: String, isInclusion: Boolean) {
        val tag = value.trim().lowercase()
        if (tag.isEmpty()) return
        val existing = if (isInclusion) currentConfig.inclusions else currentConfig.exclusions
        if (existing.any { it.equals(tag, ignoreCase = true) }) return
        currentConfig = if (isInclusion) currentConfig.copy(inclusions = currentConfig.inclusions + tag)
                        else currentConfig.copy(exclusions = currentConfig.exclusions + tag)
        markAsCustom()
        updateTagsUI(animate = false)
        tagViews[tagKey(tag, isInclusion)]?.let { animateTagIn(it) }
    }

    private fun showTagOptions(tag: String, isInclusion: Boolean) {
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
                            currentConfig = if (isInclusion) currentConfig.copy(
                                inclusions = currentConfig.inclusions.map { if (it == tag) normalized else it }.distinct()
                            ) else currentConfig.copy(
                                exclusions = currentConfig.exclusions.map { if (it == tag) normalized else it }.distinct()
                            )
                            markAsCustom()
                            updateTagsUI(animate = false)
                            tagViews[tagKey(normalized, isInclusion)]?.let { animateTagIn(it) }
                        }
                    }
                } else {
                    val view = tagViews[tagKey(tag, isInclusion)]
                    val applyDelete = {
                        currentConfig = if (isInclusion) currentConfig.copy(inclusions = currentConfig.inclusions - tag)
                                        else currentConfig.copy(exclusions = currentConfig.exclusions - tag)
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
                        presetManager.saveLastState(currentConfig, name)
                    }.onSuccess {
                        presetAdapter = activity.spinnerAdapter(getPresetList())
                        spinnerPresets.adapter = presetAdapter
                        isUpdatingUI = true
                        spinnerPresets.setSelection(presetAdapter.getPosition(name))
                        isUpdatingUI = false
                        isCustomMode = false
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
                    btnSavePreset.visibility = View.VISIBLE
                    btnDeletePreset.visibility = View.GONE
                }
            }
            .setNegativeButton(activity.pluginString(R.string.cancel), null)
            .show().styleDialog()
    }

    private fun animatePresetRow(saved: Boolean, onEnd: (() -> Unit)? = null) {
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
}