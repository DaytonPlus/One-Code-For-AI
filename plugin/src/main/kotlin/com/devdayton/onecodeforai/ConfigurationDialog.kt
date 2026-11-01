package com.devdayton.onecodeforai

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
import android.text.TextUtils
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
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.devdayton.onecodeforai.UiFactory.actionText
import com.devdayton.onecodeforai.UiFactory.checkBox
import com.devdayton.onecodeforai.UiFactory.editText
import com.devdayton.onecodeforai.UiFactory.iconButton
import com.devdayton.onecodeforai.UiFactory.pluginString
import com.devdayton.onecodeforai.UiFactory.sectionTitle
import com.devdayton.onecodeforai.UiFactory.spinnerAdapter
import com.devdayton.onecodeforai.UiFactory.textView

class ConfigurationDialog(
    private val activity: Activity,
    private val projectRoot: java.io.File,
    private val gitEnabledGlobally: Boolean,
    private val onExport: (config: FilterConfig, isShare: Boolean) -> Unit,
    private val treeEnabledGlobally: Boolean = true,
    private val onDismiss: () -> Unit = {},
    private val onChangeRoot: (java.io.File) -> Unit = {} // <-- NUEVO PARÁMETRO
) {
    private val presetManager = PresetManager(activity)
    private var currentConfig = FilterConfig()
    private var isCustomMode = false
    private var isUpdatingUI = false
    private var gitignoreTags: List<String> = emptyList()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rescanScheduled = false

    private lateinit var spinnerPresets: Spinner
    private lateinit var presetAdapter: ArrayAdapter<String>
    private lateinit var btnSavePreset: ImageButton
    private lateinit var btnDeletePreset: ImageButton
    private lateinit var cbBinaries: CheckBox
    private lateinit var cbGitignore: CheckBox
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

    // Tree
    private var treeVisible = false
    private lateinit var cbShowTree: CheckBox
    private lateinit var treeActionsRow: LinearLayout
    private lateinit var treeWrapper: FrameLayout
    private lateinit var treeView: ProjectTreeView
    private lateinit var treeLoadingPanel: LinearLayout
    private lateinit var treeLoadingText: TextView
    private lateinit var treeViewContainer: View

    // Stats
    private lateinit var statsContainer: LinearLayout
    private lateinit var tvStatsTotal: TextView
    private lateinit var flowStatsExt: FlowLayout

    private val tagViews = mutableMapOf<String, TextView>()

    // ------------------------------------------------------------------
    // Helpers de localización
    // ------------------------------------------------------------------

    private fun customPresetLabel(): String =
        activity.pluginString(R.string.custom_preset)

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

    // ------------------------------------------------------------------

    fun show() {
        gitignoreTags = FileFilter(projectRoot).loadGitignoreRules()
        val lastState = presetManager.loadLastState()
        currentConfig = lastState?.first ?: FilterConfig()
        isCustomMode = (lastState?.second ?: customPresetLabel()) == customPresetLabel()

        val dialog = Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCancelable(true)
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                CodeAssistTheme.dp(activity, 18),
                CodeAssistTheme.dp(activity, 16),
                CodeAssistTheme.dp(activity, 18),
                CodeAssistTheme.dp(activity, 18)
            )
            background = CodeAssistTheme.roundedBg(
                activity,
                CodeAssistTheme.BG,
                15f,
                CodeAssistTheme.OUTLINE
            )
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // --- HEADER ---
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(
            activity.textView(
                activity.pluginString(R.string.app_name),
                17f,
                CodeAssistTheme.TEXT_PRIMARY,
                true
            )
        )
        headerText.addView(
            activity.textView(
                activity.pluginString(R.string.dialog_subtitle, projectRoot.name),
                10f,
                CodeAssistTheme.TEXT_SECONDARY
            ).apply {
                setPadding(0, CodeAssistTheme.dp(activity, 5), 0, 0)
            }
        )
        header.addView(headerText)
        header.addView(
            activity.iconButton(
                R.drawable.ic_tech_close,
                activity.pluginString(R.string.close)
            ) { dialog.dismiss() }
        )
        root.addView(header)
        root.addView(divider())

        // --- PRESETS ---
        root.addView(activity.sectionTitle(activity.pluginString(R.string.section_presets)))
        val presetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        presetAdapter = activity.spinnerAdapter(getPresetList())
        spinnerPresets = Spinner(activity).apply {
            adapter = presetAdapter
            background = CodeAssistTheme.roundedBg(
                activity,
                CodeAssistTheme.SURFACE_RAISED,
                8f,
                CodeAssistTheme.OUTLINE
            )
            dropDownVerticalOffset = CodeAssistTheme.dp(activity, 4)
            setPopupBackgroundDrawable(ColorDrawable(CodeAssistTheme.SURFACE_RAISED))
            layoutParams = LinearLayout.LayoutParams(0, CodeAssistTheme.dp(activity, 42), 1f)
        }
        btnSavePreset = activity.iconButton(
            R.drawable.ic_tech_save,
            activity.pluginString(R.string.save_preset)
        ) { showSavePresetDialog() }.apply {
            visibility = if (isCustomMode) View.VISIBLE else View.GONE
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = CodeAssistTheme.dp(activity, 8)
            }
        }
        btnDeletePreset = activity.iconButton(
            R.drawable.ic_tech_trash,
            activity.pluginString(R.string.delete_preset)
        ) { confirmDeletePreset() }.apply {
            visibility = View.GONE
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = CodeAssistTheme.dp(activity, 8)
            }
        }
        presetRow.addView(spinnerPresets)
        presetRow.addView(btnSavePreset)
        presetRow.addView(btnDeletePreset)
        root.addView(presetRow)

        // --- FILTROS BASE ---
        root.addView(activity.sectionTitle(activity.pluginString(R.string.section_base_filters)))
        val basePanel = panel()
        cbBinaries = activity.checkBox(activity.pluginString(R.string.exclude_binaries)).apply {
            isChecked = currentConfig.excludeBinaries
            setOnCheckedChangeListener { _, _ ->
                if (!isUpdatingUI) markAsCustom()
            }
        }
        cbGitignore = activity.checkBox(activity.pluginString(R.string.respect_gitignore)).apply {
            isChecked = currentConfig.respectGitignore
            setOnCheckedChangeListener { _, _ ->
                if (!isUpdatingUI) {
                    markAsCustom()
                    updateTagsUI(animate = true)
                }
            }
        }
        basePanel.addView(cbBinaries)
        basePanel.addView(cbGitignore)

        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, CodeAssistTheme.dp(activity, 8), 0, 0)
        }
        sizeRow.addView(
            activity.textView(
                activity.pluginString(R.string.max_size),
                12f,
                CodeAssistTheme.TEXT_SECONDARY
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        etMaxSize = activity.editText(
            currentConfig.maxSizeKb.toString(),
            InputType.TYPE_CLASS_NUMBER
        ).apply {
            gravity = Gravity.CENTER
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && !isUpdatingUI) markAsCustom()
            }
            layoutParams = LinearLayout.LayoutParams(
                CodeAssistTheme.dp(activity, 104),
                CodeAssistTheme.dp(activity, 40)
            )
        }
        sizeRow.addView(etMaxSize)
        sizeRow.addView(
            activity.textView(
                activity.pluginString(R.string.kb_suffix),
                11f,
                CodeAssistTheme.TEXT_SECONDARY
            ).apply {
                setPadding(CodeAssistTheme.dp(activity, 7), 0, 0, 0)
            }
        )
        basePanel.addView(sizeRow)
        root.addView(basePanel)

        // --- TAGS ---
        root.addView(
            tagSectionHeader(
                activity.pluginString(R.string.section_inclusions),
                activity.pluginString(R.string.action_add)
            ) {
                showAddTagDialog(
                    activity.pluginString(R.string.add_inclusion_title)
                ) { addTag(it, true) }
            }
        )
        flowInclusions = createFlow()
        root.addView(flowInclusions)

        root.addView(
            tagSectionHeader(
                activity.pluginString(R.string.section_exclusions),
                activity.pluginString(R.string.action_add)
            ) {
                showAddTagDialog(
                    activity.pluginString(R.string.add_exclusion_title)
                ) { addTag(it, false) }
            }
        )
        flowExclusions = createFlow()
        root.addView(flowExclusions)

        gitignoreSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        gitignoreSection.addView(
            activity.sectionTitle(activity.pluginString(R.string.section_gitignore_rules))
        )
        flowGitignore = createFlow()
        gitignoreSection.addView(flowGitignore)
        root.addView(gitignoreSection)

        // --- GIT ---
        if (gitEnabledGlobally) {
            root.addView(activity.sectionTitle(activity.pluginString(R.string.section_version_control)))
            layoutGitBox = panel()
            root.addView(layoutGitBox)
            setupGitUI()
        }

        // --- TREE ---
        if (treeEnabledGlobally) {
            root.addView(activity.sectionTitle(activity.pluginString(R.string.section_tree)))

            val treeTogglePanel = panel()
            cbShowTree = activity.checkBox(activity.pluginString(R.string.show_tree)).apply {
                isChecked = false
                setOnCheckedChangeListener { _, checked -> setTreeVisibility(checked) }
            }
            treeTogglePanel.addView(cbShowTree)
            root.addView(treeTogglePanel)

            treeActionsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    0,
                    CodeAssistTheme.dp(activity, 4),
                    0,
                    CodeAssistTheme.dp(activity, 4)
                )
                visibility = View.GONE
            }
            treeActionsRow.addView(
                View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }
            )
            treeActionsRow.addView(
                activity.actionText(activity.pluginString(R.string.action_reload)) {
                    reloadTree()
                }
            )
            treeActionsRow.addView(
                activity.actionText(activity.pluginString(R.string.action_expand)) {
                    if (::treeView.isInitialized) treeView.expandAll()
                }
            )
            treeActionsRow.addView(
                activity.actionText(activity.pluginString(R.string.action_collapse)) {
                    if (::treeView.isInitialized) treeView.collapseAll()
                }
            )
            root.addView(treeActionsRow)

            treeWrapper = FrameLayout(activity).apply {
                background = CodeAssistTheme.roundedBg(
                    activity, CodeAssistTheme.SURFACE, 10f, CodeAssistTheme.OUTLINE
                )
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    CodeAssistTheme.dp(activity, 280)
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
                onStatsChanged = { stats -> updateStatsUI(stats) }
            )
            treeViewContainer = treeView.getView().apply { visibility = View.GONE }
            treeWrapper.addView(
                treeViewContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            treeLoadingPanel = buildTreeLoadingPanel()
            treeWrapper.addView(
                treeLoadingPanel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            root.addView(treeWrapper)
        }

        // --- BOTÓN EXPORTAR ---
        val exportButton = Button(activity).apply {
            text = activity.pluginString(R.string.action_export)
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.parseColor("#21170F"))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = CodeAssistTheme.selectableBg(
                activity,
                normalColor = CodeAssistTheme.ACCENT,
                pressedColor = CodeAssistTheme.ACCENT_PRESSED,
                radiusDp = 9f,
                strokeColor = null
            )
            minHeight = CodeAssistTheme.dp(activity, 46)
            setOnClickListener {
                readUIValues()
                presetManager.saveLastState(
                    currentConfig,
                    spinnerPresets.selectedItem?.toString() ?: customPresetLabel()
                )
                dialog.dismiss()
                onExport(currentConfig, false)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                CodeAssistTheme.dp(activity, 46)
            ).apply {
                topMargin = CodeAssistTheme.dp(activity, 22)
            }
        }
        attachPressScale(exportButton)
        root.addView(exportButton)

        // --- BOTÓN COMPARTIR ---
        val shareButton = Button(activity).apply {
            text = activity.pluginString(R.string.action_share)
            textSize = 13f
            isAllCaps = false
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = CodeAssistTheme.selectableBg(
                activity,
                normalColor = CodeAssistTheme.SURFACE_RAISED,
                pressedColor = CodeAssistTheme.SURFACE,
                radiusDp = 9f,
                strokeColor = CodeAssistTheme.OUTLINE
            )
            minHeight = CodeAssistTheme.dp(activity, 46)
            setOnClickListener {
                readUIValues()
                presetManager.saveLastState(
                    currentConfig,
                    spinnerPresets.selectedItem?.toString() ?: customPresetLabel()
                )
                dialog.dismiss()
                onExport(currentConfig, true)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                CodeAssistTheme.dp(activity, 46)
            ).apply {
                topMargin = CodeAssistTheme.dp(activity, 8)
            }
        }
        attachPressScale(shareButton)
        root.addView(shareButton)

        root.addView(
            activity.textView(
                activity.pluginString(R.string.export_description),
                10f,
                CodeAssistTheme.TEXT_MUTED
            ).apply {
                gravity = Gravity.CENTER
                setPadding(
                    CodeAssistTheme.dp(activity, 8),
                    CodeAssistTheme.dp(activity, 9),
                    CodeAssistTheme.dp(activity, 8),
                    0
                )
            }
        )

        // --- STATS ---
        if (treeEnabledGlobally) {
            statsContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            statsContainer.addView(
                activity.sectionTitle(activity.pluginString(R.string.section_stats))
            )
            val statsPanel = panel().apply {
                minimumHeight = CodeAssistTheme.dp(activity, 64)
            }
            tvStatsTotal = activity.textView(
                activity.pluginString(R.string.calculating),
                13f,
                CodeAssistTheme.TEXT_PRIMARY,
                true
            )
            statsPanel.addView(tvStatsTotal)
            flowStatsExt = FlowLayout(activity).apply {
                setPadding(0, CodeAssistTheme.dp(activity, 4), 0, 0)
            }
            statsPanel.addView(flowStatsExt)
            statsContainer.addView(statsPanel)
            root.addView(statsContainer)
        }

        dialog.setContentView(scroll)

        dialog.setOnDismissListener {
            try {
                pendingResolve?.let { resolveHandler.removeCallbacks(it) }
                resolveHandler.removeCallbacksAndMessages(null)
                if (::treeView.isInitialized) treeView.cancelScan()
                readUIValues()
                presetManager.saveLastState(
                    currentConfig,
                    spinnerPresets.selectedItem?.toString() ?: customPresetLabel()
                )
            } finally {
                onDismiss()
            }
        }

        val initialIndex = resolveInitialPresetIndex(lastState?.second)
        spinnerPresets.setSelection(initialIndex, false)
        updateTagsUI(animate = false)

        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (isUpdatingUI) return
                val selected = parent?.getItemAtPosition(position)?.toString() ?: return
                isCustomMode = selected == customPresetLabel()
                btnSavePreset.visibility = if (isCustomMode) View.VISIBLE else View.GONE
                btnDeletePreset.visibility =
                    if (presetManager.isUserPreset(selected)) View.VISIBLE else View.GONE
                if (!isCustomMode) {
                    presetManager.loadPreset(selected)?.let { applyConfigToUI(it) }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        scroll.post { playEntrance(root) }
    }

    // ==================================================================
    // Tree visibility
    // ==================================================================

    private fun setTreeVisibility(visible: Boolean) {
        treeVisible = visible
        if (::treeActionsRow.isInitialized) {
            treeActionsRow.visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (::treeWrapper.isInitialized) {
            treeWrapper.visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (::statsContainer.isInitialized) {
            statsContainer.visibility = if (visible) View.VISIBLE else View.GONE
        }

        if (visible) {
            startTreeScan()
        } else if (::treeView.isInitialized) {
            treeView.cancelScan()
        }
    }

    private fun reloadTree() {
        if (!treeVisible) return
        startTreeScan()
    }

    // ==================================================================
    // Tree loading panel + scan
    // ==================================================================

    private fun buildTreeLoadingPanel(): LinearLayout {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                CodeAssistTheme.dp(activity, 16),
                CodeAssistTheme.dp(activity, 16),
                CodeAssistTheme.dp(activity, 16),
                CodeAssistTheme.dp(activity, 16)
            )
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.SURFACE, 10f, CodeAssistTheme.OUTLINE
            )
        }

        val spinner = ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                CodeAssistTheme.dp(activity, 34),
                CodeAssistTheme.dp(activity, 34)
            )
        }
        panel.addView(spinner)

        treeLoadingText = activity.textView(
            activity.pluginString(R.string.analyzing),
            12f,
            CodeAssistTheme.TEXT_SECONDARY
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, CodeAssistTheme.dp(activity, 10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        panel.addView(treeLoadingText)

        val cancelBtn = Button(activity).apply {
            text = activity.pluginString(R.string.cancel)
            textSize = 12f
            isAllCaps = false
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            background = CodeAssistTheme.selectableBg(
                activity,
                CodeAssistTheme.SURFACE_RAISED,
                CodeAssistTheme.SURFACE,
                8f,
                CodeAssistTheme.OUTLINE
            )
            minHeight = CodeAssistTheme.dp(activity, 36)
            setPadding(
                CodeAssistTheme.dp(activity, 16),
                CodeAssistTheme.dp(activity, 6),
                CodeAssistTheme.dp(activity, 16),
                CodeAssistTheme.dp(activity, 6)
            )
            setOnClickListener {
                if (::treeView.isInitialized) treeView.cancelScan()
                treeLoadingText.text = activity.pluginString(R.string.canceled_retry)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                CodeAssistTheme.dp(activity, 36)
            ).apply {
                topMargin = CodeAssistTheme.dp(activity, 12)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        panel.addView(cancelBtn)

        return panel
    }

    private fun startTreeScan() {
        if (!treeVisible) return
        if (!::treeView.isInitialized) return
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
            }
        )
    }

    // ==================================================================
    // Git UI
    // ==================================================================

    private fun setupGitUI() {
        val repoInfo = GitUtils.getRepoInfo(projectRoot)
        if (repoInfo == null) {
            layoutGitBox.addView(
                activity.textView(
                    activity.pluginString(R.string.git_not_repo),
                    11f, CodeAssistTheme.TEXT_MUTED
                ).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 4), 0, CodeAssistTheme.dp(activity, 4))
                }
            )
            
            // --- NUEVA ACCIÓN PARA IR A LA RAÍZ CON GIT ---
            val parentRoot = GitUtils.findGitRoot(projectRoot.parentFile ?: projectRoot)
            if (parentRoot != null && parentRoot.absolutePath != projectRoot.absolutePath) {
                layoutGitBox.addView(
                    activity.actionText(
                        activity.pluginString(R.string.git_open_parent_root, parentRoot.name)
                    ) {
                        onChangeRoot(parentRoot)
                    }.apply {
                        setPadding(0, CodeAssistTheme.dp(activity, 6), 0, CodeAssistTheme.dp(activity, 6))
                    }
                )
            }
            // ----------------------------------------------
            
            return
        }

        // Cabecera: rama + commit + refresh
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvGitInfo = activity.textView(
            activity.pluginString(
                R.string.git_info,
                repoInfo.currentBranch ?: activity.pluginString(R.string.no_branch),
                repoInfo.currentCommit ?: activity.pluginString(R.string.no_commit)
            ),
            11f, CodeAssistTheme.TEXT_SECONDARY
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvGitInfo)
        headerRow.addView(
            activity.iconButton(
                R.drawable.ic_tech_refresh,
                activity.pluginString(R.string.git_refresh)
            ) { refreshGitInfo() }
        )
        layoutGitBox.addView(headerRow)

        // Aviso de cambios sin commitear
        tvGitWarning = activity.textView(
            activity.pluginString(
                R.string.git_uncommitted_changes,
                GitUtils.countUncommittedChanges(projectRoot)
            ),
            10f, CodeAssistTheme.TAG_CONFLICT_TEXT
        ).apply {
            setPadding(0, CodeAssistTheme.dp(activity, 2), 0, CodeAssistTheme.dp(activity, 2))
            visibility = if (repoInfo.hasUncommittedChanges) View.VISIBLE else View.GONE
        }
        layoutGitBox.addView(tvGitWarning)

        // Toggle "Leer desde Git"
        cbUseGit = activity.checkBox(activity.pluginString(R.string.read_from_git)).apply {
            isChecked = currentConfig.useGit
            setOnCheckedChangeListener { _, checked ->
                if (::gitDetailsBox.isInitialized) {
                    gitDetailsBox.visibility = if (checked) View.VISIBLE else View.GONE
                }
                if (checked) updateResolvedRef()
                if (!isUpdatingUI) markAsCustom()
            }
        }
        layoutGitBox.addView(cbUseGit)

        // Panel de detalles
        gitDetailsBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentConfig.useGit) View.VISIBLE else View.GONE
            setPadding(0, CodeAssistTheme.dp(activity, 4), 0, 0)
        }

        // Campo ref + botón explorar
        val refRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        etGitRef = activity.editText(currentConfig.gitRef ?: "").apply {
            hint = activity.pluginString(R.string.git_ref_hint)
            layoutParams = LinearLayout.LayoutParams(0, CodeAssistTheme.dp(activity, 42), 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdatingUI) return
                    scheduleResolve()
                }
            })
        }
        refRow.addView(etGitRef)
        refRow.addView(
            activity.iconButton(
                R.drawable.ic_tech_search,
                activity.pluginString(R.string.git_browse_ref)
            ) { showRefPickerDialog() }.apply {
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = CodeAssistTheme.dp(activity, 8)
                }
            }
        )
        gitDetailsBox.addView(refRow)

        // Ref resuelto
        tvResolvedRef = activity.textView("", 10f, CodeAssistTheme.TEXT_MUTED).apply {
            setPadding(0, CodeAssistTheme.dp(activity, 4), 0, CodeAssistTheme.dp(activity, 4))
            visibility = View.GONE
        }
        gitDetailsBox.addView(tvResolvedRef)

        // Botón diff
        gitDetailsBox.addView(Button(activity).apply {
            text = activity.pluginString(R.string.git_preview_diff)
            textSize = 12f
            isAllCaps = false
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = CodeAssistTheme.selectableBg(
                activity, CodeAssistTheme.SURFACE_RAISED, CodeAssistTheme.SURFACE,
                8f, CodeAssistTheme.OUTLINE
            )
            minHeight = CodeAssistTheme.dp(activity, 38)
            setOnClickListener { showDiffPreview() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                CodeAssistTheme.dp(activity, 38)
            ).apply {
                topMargin = CodeAssistTheme.dp(activity, 8)
            }
        })

        layoutGitBox.addView(gitDetailsBox)
        updateResolvedRef()
    }

    private fun refreshGitInfo() {
        val info = GitUtils.getRepoInfo(projectRoot) ?: return
        if (::tvGitInfo.isInitialized) {
            tvGitInfo.text = activity.pluginString(
                R.string.git_info,
                info.currentBranch ?: activity.pluginString(R.string.no_branch),
                info.currentCommit ?: activity.pluginString(R.string.no_commit)
            )
        }
        if (::tvGitWarning.isInitialized) {
            val count = GitUtils.countUncommittedChanges(projectRoot)
            if (count > 0) {
                tvGitWarning.text = activity.pluginString(R.string.git_uncommitted_changes, count)
                tvGitWarning.visibility = View.VISIBLE
            } else {
                tvGitWarning.visibility = View.GONE
            }
        }
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
        if (ref.isEmpty()) {
            tvResolvedRef.visibility = View.GONE
            return
        }
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

    // ------------------------------------------------------------------
    // Selector de referencias Git
    // ------------------------------------------------------------------

    private fun showRefPickerDialog() {
        val dialog = Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCancelable(true)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 14),
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 14)
            )
            background = CodeAssistTheme.roundedBg(activity, CodeAssistTheme.BG, 14f, CodeAssistTheme.OUTLINE)
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            activity.textView(
                activity.pluginString(R.string.git_pick_ref_title), 15f,
                CodeAssistTheme.TEXT_PRIMARY, true
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        header.addView(
            activity.iconButton(
                R.drawable.ic_tech_close, activity.pluginString(R.string.close)
            ) { dialog.dismiss() }
        )
        root.addView(header)

        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, CodeAssistTheme.dp(activity, 10), 0, CodeAssistTheme.dp(activity, 6))
        }
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = true }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(tabRow)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val tabs = mutableListOf<TextView>()
        fun selectTab(index: Int) {
            tabs.forEachIndexed { i, t ->
                t.setTextColor(if (i == index) CodeAssistTheme.ACCENT else CodeAssistTheme.TEXT_SECONDARY)
                t.background = if (i == index) CodeAssistTheme.roundedBg(
                    activity, CodeAssistTheme.SURFACE_RAISED, 8f, CodeAssistTheme.OUTLINE
                ) else null
            }
            content.removeAllViews()
            when (index) {
                0 -> renderBranchesTab(content, dialog)
                1 -> renderTagsTab(content, dialog)
                2 -> renderCommitsTab(content, dialog)
            }
        }

        listOf(
            activity.pluginString(R.string.git_tab_branches),
            activity.pluginString(R.string.git_tab_tags),
            activity.pluginString(R.string.git_tab_commits)
        ).forEachIndexed { i, label ->
            val t = activity.textView(label, 12f, CodeAssistTheme.TEXT_SECONDARY, true).apply {
                gravity = Gravity.CENTER
                setPadding(
                    CodeAssistTheme.dp(activity, 12), CodeAssistTheme.dp(activity, 8),
                    CodeAssistTheme.dp(activity, 12), CodeAssistTheme.dp(activity, 8)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(i) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            tabs.add(t)
            tabRow.addView(t)
        }

        selectTab(0)
        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                    (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
                )
            }
        }
        dialog.show()
    }

    private fun renderBranchesTab(container: LinearLayout, dialog: Dialog) {
        val branches = GitUtils.getBranches(projectRoot)
        if (branches.isEmpty()) {
            container.addView(emptyMsg(activity.pluginString(R.string.git_no_branches)))
            return
        }
        val locals = branches.filter { !it.isRemote }
        val remotes = branches.filter { it.isRemote }
        if (locals.isNotEmpty()) {
            container.addView(refSubtitle(activity.pluginString(R.string.git_local_branches)))
            locals.forEach { b ->
                container.addView(
                    refRow(
                        b.name,
                        if (b.isCurrent) activity.pluginString(R.string.git_current_marker) else null,
                        CodeAssistTheme.TAG_INCLUDE_TEXT
                    ) { applyRef(b.name, dialog) }
                )
            }
        }
        if (remotes.isNotEmpty()) {
            container.addView(refSubtitle(activity.pluginString(R.string.git_remote_branches)))
            remotes.forEach { b ->
                container.addView(
                    refRow(
                        b.name,
                        activity.pluginString(R.string.git_remote_marker),
                        CodeAssistTheme.TEXT_SECONDARY
                    ) { applyRef(b.name, dialog) }
                )
            }
        }
    }

    private fun renderTagsTab(container: LinearLayout, dialog: Dialog) {
        val tags = GitUtils.getTags(projectRoot)
        if (tags.isEmpty()) {
            container.addView(emptyMsg(activity.pluginString(R.string.git_no_tags)))
            return
        }
        tags.forEach { t ->
            container.addView(
                refRow(t.name, t.shortHash, CodeAssistTheme.TEXT_MUTED) {
                    applyRef(t.name, dialog)
                }
            )
        }
    }

    private fun renderCommitsTab(container: LinearLayout, dialog: Dialog) {
        val commits = GitUtils.getRecentCommits(projectRoot, null, 60)
        if (commits.isEmpty()) {
            container.addView(emptyMsg(activity.pluginString(R.string.git_no_commits)))
            return
        }
        commits.forEach { c ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 8),
                    CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 8)
                )
                background = CodeAssistTheme.selectableBg(activity)
                isClickable = true
                isFocusable = true
                setOnClickListener { applyRef(c.hash, dialog) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = CodeAssistTheme.dp(activity, 4)
                }
            }
            val head = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(
                activity.textView(c.shortHash, 11f, CodeAssistTheme.ACCENT, true).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            )
            head.addView(
                activity.textView("  ·  ${c.date}", 10f, CodeAssistTheme.TEXT_MUTED).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            row.addView(head)
            row.addView(
                activity.textView(c.author, 10f, CodeAssistTheme.TEXT_SECONDARY).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 2), 0, 0)
                }
            )
            row.addView(
                activity.textView(c.message, 12f, CodeAssistTheme.TEXT_PRIMARY).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 2), 0, 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
            container.addView(row)
        }
    }

    private fun applyRef(ref: String, dialog: Dialog) {
        isUpdatingUI = true
        etGitRef.setText(ref)
        isUpdatingUI = false
        updateResolvedRef()
        dialog.dismiss()
    }

    private fun refSubtitle(text: String) = activity.textView(
        text.uppercase(), 10f, CodeAssistTheme.TEXT_MUTED, true
    ).apply {
        setPadding(0, CodeAssistTheme.dp(activity, 10), 0, CodeAssistTheme.dp(activity, 4))
    }

    private fun emptyMsg(text: String) = activity.textView(text, 12f, CodeAssistTheme.TEXT_MUTED).apply {
        gravity = Gravity.CENTER
        setPadding(0, CodeAssistTheme.dp(activity, 24), 0, CodeAssistTheme.dp(activity, 24))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun refRow(
        label: String,
        badge: String?,
        badgeColor: Int,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 9),
                CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 9)
            )
            background = CodeAssistTheme.selectableBg(activity)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = CodeAssistTheme.dp(activity, 4)
            }

            addView(
                activity.textView(label, 12f, CodeAssistTheme.TEXT_PRIMARY).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            )
            if (badge != null) {
                addView(
                    activity.textView(badge, 9f, badgeColor, true).apply {
                        setPadding(
                            CodeAssistTheme.dp(activity, 6), CodeAssistTheme.dp(activity, 2),
                            CodeAssistTheme.dp(activity, 6), CodeAssistTheme.dp(activity, 2)
                        )
                        background = CodeAssistTheme.roundedBg(
                            activity, CodeAssistTheme.SURFACE_RAISED, 5f
                        )
                    }
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Preview diff
    // ------------------------------------------------------------------

    private fun showDiffPreview() {
        val ref = etGitRef.text.toString().trim()
        if (ref.isEmpty()) {
            Toast.makeText(activity, activity.pluginString(R.string.git_ref_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val resolved = GitUtils.resolveRefShort(projectRoot, ref)
        if (resolved == null) {
            Toast.makeText(activity, activity.pluginString(R.string.git_ref_invalid), Toast.LENGTH_SHORT).show()
            return
        }

        val stats = GitUtils.getDiffStats(projectRoot, ref)
        val diff = GitUtils.getDiff(projectRoot, ref)

        val dialog = Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCancelable(true)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 14),
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 14)
            )
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.BG, 14f, CodeAssistTheme.OUTLINE
            )
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(
            activity.textView(
                activity.pluginString(R.string.git_diff_title, resolved), 14f,
                CodeAssistTheme.TEXT_PRIMARY, true
            )
        )
        if (stats != null) {
            headerText.addView(
                activity.textView(
                    activity.pluginString(
                        R.string.git_diff_stats,
                        stats.filesChanged, stats.insertions, stats.deletions
                    ),
                    10f, CodeAssistTheme.TEXT_SECONDARY
                ).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 4), 0, 0)
                }
            )
        }
        header.addView(headerText)
        header.addView(
            activity.iconButton(
                R.drawable.ic_tech_close, activity.pluginString(R.string.close)
            ) { dialog.dismiss() }
        )
        root.addView(header)

        if (stats == null || stats.filesChanged == 0) {
            root.addView(emptyMsg(activity.pluginString(R.string.git_diff_no_changes)))
        } else {
            val filesScroll = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, CodeAssistTheme.dp(activity, 150)
                )
            }
            val filesCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, CodeAssistTheme.dp(activity, 8), 0, CodeAssistTheme.dp(activity, 8))
            }
            filesScroll.addView(filesCol)
            stats.files.forEach { fc ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        CodeAssistTheme.dp(activity, 4), CodeAssistTheme.dp(activity, 4),
                        CodeAssistTheme.dp(activity, 4), CodeAssistTheme.dp(activity, 4)
                    )
                }
                val (label, color) = when (fc.status) {
                    'A' -> "A" to CodeAssistTheme.TAG_INCLUDE_TEXT
                    'D' -> "D" to CodeAssistTheme.TAG_EXCLUDE_TEXT
                    'R' -> "R" to CodeAssistTheme.TAG_CONFLICT_TEXT
                    else -> "M" to CodeAssistTheme.TEXT_SECONDARY
                }
                row.addView(
                    activity.textView(label, 10f, color, true).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        layoutParams = LinearLayout.LayoutParams(
                            CodeAssistTheme.dp(activity, 18), ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                )
                row.addView(
                    activity.textView(fc.path, 10f, CodeAssistTheme.TEXT_PRIMARY).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    }
                )
                if (fc.insertions > 0 || fc.deletions > 0) {
                    row.addView(
                        activity.textView(
                            "+${fc.insertions} −${fc.deletions}",
                            9f, CodeAssistTheme.TEXT_MUTED
                        ).apply {
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                    )
                }
                filesCol.addView(row)
            }
            root.addView(filesScroll)

            root.addView(
                activity.textView(
                    activity.pluginString(R.string.git_diff_content).uppercase(),
                    10f, CodeAssistTheme.TEXT_MUTED, true
                ).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 10), 0, CodeAssistTheme.dp(activity, 4))
                }
            )

            val diffScroll = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = true
                background = CodeAssistTheme.roundedBg(
                    activity, CodeAssistTheme.SURFACE, 8f, CodeAssistTheme.OUTLINE
                )
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val diffText = TextView(activity).apply {
                text = diff ?: ""
                textSize = 10f
                setTextColor(CodeAssistTheme.TEXT_PRIMARY)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(
                    CodeAssistTheme.dp(activity, 8), CodeAssistTheme.dp(activity, 8),
                    CodeAssistTheme.dp(activity, 8), CodeAssistTheme.dp(activity, 8)
                )
                setHorizontallyScrolling(true)
            }
            diffScroll.addView(
                HorizontalScrollView(activity).apply { addView(diffText) }
            )
            root.addView(diffScroll)
        }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.95f).toInt(),
                    (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
                )
            }
        }
        dialog.show()
    }

    // ==================================================================
    // Animaciones
    // ==================================================================

    private fun playEntrance(root: ViewGroup) {
        val n = root.childCount
        for (i in 0 until n) {
            val child = root.getChildAt(i)
            child.alpha = 0f
            child.translationY = CodeAssistTheme.dp(activity, 10).toFloat()
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(i * 30L)
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun animateTagIn(view: View) {
        view.animate().cancel()
        view.scaleX = 0.5f
        view.scaleY = 0.5f
        view.alpha = 0f
        view.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(240)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()
    }

    private fun animateTagOut(view: View, onEnd: () -> Unit) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.5f).scaleY(0.5f).alpha(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(onEnd)
            .start()
    }

    private fun attachPressScale(button: View) {
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                        .setInterpolator(DecelerateInterpolator()).start()
            }
            false
        }
    }

    // ==================================================================
    // Stats
    // ==================================================================

    private fun updateStatsUI(stats: PreviewStats) {
        if (!::tvStatsTotal.isInitialized) return

        val newText = if (stats.totalFiles == 0) {
            activity.pluginString(R.string.stats_empty)
        } else {
            val filesLabel = if (stats.totalFiles == 1) {
                activity.pluginString(R.string.stats_files_one, stats.totalFiles)
            } else {
                activity.pluginString(R.string.stats_files_many, stats.totalFiles)
            }
            val tokensLabel = activity.pluginString(
                R.string.stats_tokens,
                formatNumber(stats.totalTokens)
            )
            "$filesLabel  ·  $tokensLabel  ·  ${formatSize(stats.totalBytes)}"
        }

        tvStatsTotal.animate().cancel()
        tvStatsTotal.animate()
            .alpha(0.25f)
            .setDuration(110)
            .withEndAction {
                tvStatsTotal.text = newText
                tvStatsTotal.animate().alpha(1f).setDuration(160).start()
            }
            .start()

        flowStatsExt.animate().cancel()
        flowStatsExt.animate()
            .alpha(0.25f)
            .setDuration(110)
            .withEndAction {
                flowStatsExt.removeAllViews()
                stats.byExtension.take(12).forEach { flowStatsExt.addView(createStatChip(it)) }
                flowStatsExt.animate().alpha(1f).setDuration(160).start()
            }
            .start()
    }

    private fun createStatChip(stat: ExtensionStats) = TextView(activity).apply {
        text = "${stat.extension} · ${stat.files} · ${formatNumber(stat.tokens)} tok"
        textSize = 10f
        setTextColor(CodeAssistTheme.TAG_INCLUDE_TEXT)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding(
            CodeAssistTheme.dp(activity, 9),
            CodeAssistTheme.dp(activity, 6),
            CodeAssistTheme.dp(activity, 9),
            CodeAssistTheme.dp(activity, 6)
        )
        background = CodeAssistTheme.roundedBg(activity, CodeAssistTheme.TAG_INCLUDE, 6f)
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = CodeAssistTheme.dp(activity, 6)
            bottomMargin = CodeAssistTheme.dp(activity, 6)
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

    // ==================================================================
    // Helpers de UI
    // ==================================================================

    private fun panel(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            CodeAssistTheme.dp(activity, 12),
            CodeAssistTheme.dp(activity, 7),
            CodeAssistTheme.dp(activity, 12),
            CodeAssistTheme.dp(activity, 10)
        )
        background = CodeAssistTheme.roundedBg(
            activity,
            CodeAssistTheme.SURFACE,
            10f,
            CodeAssistTheme.OUTLINE
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            CodeAssistTheme.dp(activity, 1)
        ).apply {
            topMargin = CodeAssistTheme.dp(activity, 13)
            bottomMargin = CodeAssistTheme.dp(activity, 3)
        }
    }

    private fun createFlow(): FlowLayout = FlowLayout(activity).apply {
        setPadding(0, CodeAssistTheme.dp(activity, 2), 0, CodeAssistTheme.dp(activity, 5))
        minimumHeight = CodeAssistTheme.dp(activity, 16)
    }

    private fun tagSectionHeader(
        title: String,
        action: String,
        onAction: () -> Unit
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, CodeAssistTheme.dp(activity, 8), 0, CodeAssistTheme.dp(activity, 4))
        addView(
            activity.textView(title, 13f, CodeAssistTheme.TEXT_PRIMARY, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        addView(activity.actionText(action, onAction))
    }

    private fun applyConfigToUI(config: FilterConfig) {
        isUpdatingUI = true
        currentConfig = config
        cbBinaries.isChecked = config.excludeBinaries
        cbGitignore.isChecked = config.respectGitignore
        etMaxSize.setText(config.maxSizeKb.toString())

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

    private fun markAsCustom() {
        markAsCustomOnly()
        scheduleRescan()
    }

    private fun scheduleRescan() {
        if (rescanScheduled) return
        rescanScheduled = true
        mainHandler.postDelayed({
            rescanScheduled = false
            if (treeVisible) startTreeScan()
        }, 220)
    }

    private fun readUIValues() {
        val size = etMaxSize.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 512
        var useGit = false
        var gitRef: String? = null

        if (gitEnabledGlobally && ::cbUseGit.isInitialized) {
            useGit = cbUseGit.isChecked
            if (useGit && ::etGitRef.isInitialized) {
                val ref = etGitRef.text.toString().trim()
                if (ref.isNotEmpty() && GitUtils.refExists(projectRoot, ref)) {
                    gitRef = ref
                } else {
                    useGit = false
                }
            }
        }

        currentConfig = currentConfig.copy(
            excludeBinaries = cbBinaries.isChecked,
            respectGitignore = cbGitignore.isChecked,
            maxSizeKb = size,
            useGit = useGit,
            gitRef = gitRef
        )
    }

    private fun tagKey(tag: String, isInclusion: Boolean) =
        "${if (isInclusion) "I" else "E"}:$tag"

    private fun updateTagsUI(animate: Boolean = false) {
        if (!::flowInclusions.isInitialized) return

        flowInclusions.removeAllViews()
        flowExclusions.removeAllViews()
        flowGitignore.removeAllViews()
        tagViews.clear()

        if (currentConfig.inclusions.isEmpty()) {
            addEmptyLabel(flowInclusions, activity.pluginString(R.string.no_inclusions))
        } else {
            currentConfig.inclusions.forEach { addTagToFlow(flowInclusions, it, true) }
        }

        if (currentConfig.exclusions.isEmpty()) {
            addEmptyLabel(flowExclusions, activity.pluginString(R.string.no_exclusions))
        } else {
            currentConfig.exclusions.forEach { addTagToFlow(flowExclusions, it, false) }
        }

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
                    child.alpha = 0f
                    child.scaleX = 0.85f
                    child.scaleY = 0.85f
                    child.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay(i * 20L)
                        .setDuration(180)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    i++
                }
            }
        }
    }

    private fun addEmptyLabel(flow: FlowLayout, label: String) {
        flow.addView(
            activity.textView(label, 11f, CodeAssistTheme.TEXT_MUTED).apply {
                setPadding(
                    CodeAssistTheme.dp(activity, 2),
                    CodeAssistTheme.dp(activity, 5),
                    CodeAssistTheme.dp(activity, 2),
                    CodeAssistTheme.dp(activity, 5)
                )
            }
        )
    }

    private fun addTagToFlow(flow: FlowLayout, tag: String, isInclusion: Boolean) {
        val conflict = gitignoreTags.contains(tag) ||
            (if (isInclusion) currentConfig.exclusions.contains(tag) else currentConfig.inclusions.contains(tag))
        val backgroundColor = when {
            conflict -> CodeAssistTheme.TAG_CONFLICT
            isInclusion -> CodeAssistTheme.TAG_INCLUDE
            else -> CodeAssistTheme.TAG_EXCLUDE
        }
        val foregroundColor = when {
            conflict -> CodeAssistTheme.TAG_CONFLICT_TEXT
            isInclusion -> CodeAssistTheme.TAG_INCLUDE_TEXT
            else -> CodeAssistTheme.TAG_EXCLUDE_TEXT
        }
        val view = createTagView(tag, backgroundColor, foregroundColor) {
            showTagOptions(tag, isInclusion)
        }
        flow.addView(view)
        tagViews[tagKey(tag, isInclusion)] = view
    }

    private fun createTagView(
        label: String,
        backgroundColor: Int,
        foregroundColor: Int,
        onClick: (() -> Unit)?
    ): TextView = activity.textView(label, 11f, foregroundColor, true).apply {
        setPadding(
            CodeAssistTheme.dp(activity, 10),
            CodeAssistTheme.dp(activity, 7),
            CodeAssistTheme.dp(activity, 10),
            CodeAssistTheme.dp(activity, 7)
        )
        background = CodeAssistTheme.roundedBg(activity, backgroundColor, 7f)
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun addTag(value: String, isInclusion: Boolean) {
        val tag = value.trim().lowercase()
        if (tag.isEmpty()) return

        val existing = if (isInclusion) currentConfig.inclusions else currentConfig.exclusions
        if (existing.any { it.equals(tag, ignoreCase = true) }) return

        currentConfig = if (isInclusion) {
            currentConfig.copy(inclusions = currentConfig.inclusions + tag)
        } else {
            currentConfig.copy(exclusions = currentConfig.exclusions + tag)
        }
        markAsCustom()
        updateTagsUI(animate = false)
        tagViews[tagKey(tag, isInclusion)]?.let { animateTagIn(it) }
    }

    private fun showTagOptions(tag: String, isInclusion: Boolean) {
        AlertDialog.Builder(activity)
            .setTitle(tag)
            .setItems(
                arrayOf(
                    activity.pluginString(R.string.edit_extension),
                    activity.pluginString(R.string.delete_rule)
                )
            ) { _, which ->
                if (which == 0) {
                    showAddTagDialog(
                        activity.pluginString(R.string.edit_extension),
                        tag
                    ) { updated ->
                        val normalized = updated.trim().lowercase()
                        if (normalized.isNotEmpty() && normalized != tag) {
                            currentConfig = if (isInclusion) {
                                currentConfig.copy(
                                    inclusions = currentConfig.inclusions.map {
                                        if (it == tag) normalized else it
                                    }.distinct()
                                )
                            } else {
                                currentConfig.copy(
                                    exclusions = currentConfig.exclusions.map {
                                        if (it == tag) normalized else it
                                    }.distinct()
                                )
                            }
                            markAsCustom()
                            updateTagsUI(animate = false)
                            tagViews[tagKey(normalized, isInclusion)]?.let { animateTagIn(it) }
                        }
                    }
                } else {
                    val view = tagViews[tagKey(tag, isInclusion)]
                    val applyDelete = {
                        currentConfig = if (isInclusion) {
                            currentConfig.copy(inclusions = currentConfig.inclusions - tag)
                        } else {
                            currentConfig.copy(exclusions = currentConfig.exclusions - tag)
                        }
                        markAsCustom()
                        updateTagsUI(animate = false)
                    }
                    if (view != null) {
                        animateTagOut(view, applyDelete)
                    } else {
                        applyDelete()
                    }
                }
            }
            .show()
            .styleDialog()
    }

    private fun showAddTagDialog(
        title: String,
        initial: String = "",
        onAccept: (String) -> Unit
    ) {
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
            .show()
            .styleDialog()
    }

    private fun getPresetList(): MutableList<String> = mutableListOf(customPresetLabel()).apply {
        addAll(presetManager.getDefaultPresets().keys)
        addAll(presetManager.getUserPresetNames())
    }

    private fun showSavePresetDialog() {
        val input = activity.editText("").apply {
            hint = activity.pluginString(R.string.preset_name_hint)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.pluginString(R.string.save_preset))
            .setView(input)
            .setPositiveButton(activity.pluginString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != customPresetLabel()) {
                    readUIValues()
                    presetManager.savePreset(name, currentConfig)
                    presetManager.saveLastState(currentConfig, name)
                    presetAdapter = activity.spinnerAdapter(getPresetList())
                    spinnerPresets.adapter = presetAdapter
                    isUpdatingUI = true
                    spinnerPresets.setSelection(presetAdapter.getPosition(name))
                    isUpdatingUI = false
                    isCustomMode = false
                    btnSavePreset.visibility = View.GONE
                    btnDeletePreset.visibility =
                        if (presetManager.isUserPreset(name)) View.VISIBLE else View.GONE
                    animatePresetRow(saved = true)
                }
            }
            .setNegativeButton(activity.pluginString(R.string.cancel), null)
            .show()
            .styleDialog()
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
            .show()
            .styleDialog()
    }

    private fun animatePresetRow(saved: Boolean, onEnd: (() -> Unit)? = null) {
        val target = if (saved) btnSavePreset else btnDeletePreset
        target.animate().cancel()
        target.scaleX = 0.6f
        target.scaleY = 0.6f
        target.alpha = 0f
        target.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.4f))
            .withEndAction { onEnd?.invoke() }
            .start()

        spinnerPresets.animate().cancel()
        spinnerPresets.alpha = 0.4f
        spinnerPresets.animate()
            .alpha(1f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun android.app.AlertDialog.styleDialog() {
        window?.setBackgroundDrawable(
            CodeAssistTheme.roundedBg(activity, CodeAssistTheme.SURFACE_RAISED, 12f, CodeAssistTheme.OUTLINE)
        )
        findViewById<TextView>(android.R.id.title)?.setTextColor(CodeAssistTheme.TEXT_PRIMARY)
        findViewById<TextView>(android.R.id.message)?.setTextColor(CodeAssistTheme.TEXT_SECONDARY)
        getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(CodeAssistTheme.ACCENT)
        getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(CodeAssistTheme.TEXT_SECONDARY)
        getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(CodeAssistTheme.TEXT_SECONDARY)
    }
}