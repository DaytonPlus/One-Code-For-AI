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

/**
 * Configuration screen for the dump generator.
 *
 * ### Opening flow
 *
 * The dialog deliberately shows a lightweight skeleton first, then swaps
 * to the real form once the slow inputs are ready — that way it appears
 * instantly even on devices where JGit class-loading, .gitignore parsing,
 * or the project's config.json read take a few hundred milliseconds:
 *
 *  1. show displays the skeleton screen and requests the legacy storage
 *     permission (API 26-28 only) so the system prompt has time to appear
 *     while the UI is still loading.
 *  2. .gitignore rules and the project's TreeConfigStore config are read
 *     on a background thread.
 *  3. buildFullUI is invoked on the main thread once that data is
 *     available.
 *  4. The Git section (JGit access) is rendered with its own loading
 *     placeholder and resolved asynchronously, so the dialog is never
 *     blocked by repository I/O.
 *
 * ### Tree v2: the tree is the export source of truth
 *
 * This dialog is where the export's inclusion decisions actually live.
 * Before Tree v2 the dialog held a FilterConfig and the tree was a
 * preview. Now the dialog:
 *
 *  - builds a FilterConfig from the preset's rules and its own UI
 *    controls (binaries, gitignore, per-file size, inclusions,
 *    exclusions, stub patterns),
 *  - loads the project's persisted TreeOverrides from TreeConfigStore,
 *  - asks ProjectTreeView.scanAsync to build the tree using those
 *    inputs,
 *  - lets the user adjust individual nodes (INCLUDE / STUB / EXCLUDE
 *    cycle, plus the LOCKED unlock toggle for hard-excluded folders),
 *  - and, on Generate, takes a snapshot of the tree's states via
 *    ProjectTreeView.currentStates and forwards it to the
 *    ExportCoordinator.
 *
 * ### Tree config persistence
 *
 * The project's tree state is stored under
 * .platform/plugins/com.devdayton.onecodeforai/config.json. Only two
 * events write it:
 *
 *  - Generate (before the export launches), which saves only the diff
 *    between the tree's current states and the filter-derived states,
 *    tagged with the currently selected preset so a mismatched preset
 *    cannot be restored on the next open.
 *  - Never in any other path. Closing the dialog discards manual changes
 *    on purpose.
 *
 * ### Tree loading panel
 *
 * The tree section has three visual states, managed by [TreeUiState]:
 *
 *  - LOADING: the spinner and the Cancel button are visible, the tree is
 *    hidden. Entered by startTreeScan before the scan is dispatched.
 *  - TREE: the spinner, the cancel button and the error message are
 *    hidden, the tree is visible. Entered by the scan's onComplete and
 *    by collapsing the tree section.
 *  - ERROR: the spinner and Cancel button are hidden, the message text
 *    is visible, the tree is hidden. Entered by the scan's onError and
 *    by the Cancel button.
 *
 * The state machine is the single writer of the loading panel's
 * visibility, so the "Analyzing…" spinner can never end up orphaned when
 * the underlying scan is cancelled, superseded or fails.
 *
 * ### Gitignore section
 *
 * The "Respect .gitignore rules" checkbox used to live in the base
 * filters panel, separated from the rules it actually controls. It now
 * lives inside the .gitignore section itself, directly under the section
 * title and above the flow of chips, so the causal relationship is
 * obvious to the user.
 *
 * When the checkbox is toggled off, the flow of chips is cleared in
 * place — no dialog reload, no tree re-scan — and the section collapses
 * to just the title and the checkbox. When it is toggled back on, the
 * chips are repopulated from the gitignoreTags cache that was read once
 * when the dialog opened. No additional file I/O is performed because
 * the .gitignore file has not changed.
 *
 * @param activity          Host activity used to build the dialog.
 * @param projectRoot       Root of the project being exported.
 * @param gitEnabledGlobally Whether the global Git master switch is on.
 * @param initialConfig     The config the dialog starts with when the
 *                          project has no saved config.
 * @param logInfo           Callback used to forward lifecycle logs to the
 *                          host IDE console.
 * @param onDismiss         Invoked when the dialog is dismissed.
 * @param onChangeRoot      Invoked when the user taps the "go to project
 *                          root" affordance in the Git section.
 */
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

    /**
     * Cached snapshot of the tree's states, kept up to date by the
     * ProjectTreeView.onStatesChanged callback. Read on Generate to hand
     * the map to ExportCoordinator.
     */
    private var currentStatesCache: Map<String, NodeState> = emptyMap()

    // [PERF] Distinguishes "UI built" from "still loading", so the dismiss
    // handler can safely skip readUIValues when the user closes the dialog
    // while the initial data is still being loaded.
    private var uiBuilt = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rescanScheduled = false

    /**
     * Master gate for every fade / scale / rotation animation in this
     * dialog.
     */
    private val animationsEnabled: Boolean
        get() = PluginSettings.areAnimationsEnabled(activity)

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
    private lateinit var flowStubPatterns: FlowLayout
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
    private lateinit var cbShowLocked: CheckBox
    private lateinit var treeActionsRow: LinearLayout
    private lateinit var treeWrapper: FrameLayout
    private lateinit var treeView: ProjectTreeView
    private lateinit var treeLoadingPanel: LinearLayout
    private lateinit var treeLoadingProgress: ProgressBar
    private lateinit var treeLoadingText: TextView
    private lateinit var treeLoadingCancelButton: Button
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

    // [PERF] Lazy: avoids loading JGit/MediaStore-related classes at
    // dialog-open time.
    private val exportCoordinator: ExportCoordinator by lazy {
        ExportCoordinator(
            contextProvider = com.devdayton.onecodeforai.android.HostEnvironment::applicationContext,
            logger = { message -> logInfo(message) }
        )
    }

    private val tagViews = mutableMapOf<String, TextView>()

    /**
     * Visual state of the tree section. Managed exclusively through
     * [setTreeUiState] so the loading panel, the tree container, the
     * spinner, the cancel button and the message text can never end up in
     * an inconsistent combination.
     *
     * See the class-level "Tree loading panel" note for the full contract
     * of each state.
     */
    private enum class TreeUiState { LOADING, TREE, ERROR }

    // ------------------------------------------------------------------

    private fun customPresetLabel(): String = activity.pluginString(R.string.custom_preset)

    /**
     * Picks the initial spinner position from the last-saved preset name.
     *
     * lastStatePreset is the string that was stored in
     * TreeConfigStore.load(projectRoot)?.presetName. That value comes from
     * the spinner's selected item at the moment the user pressed Generate,
     * so it is already the localized custom label. The two literal values
     * ("Personalizado" and "Custom") are kept for backward compatibility
     * with profiles saved by earlier plugin versions.
     */
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
    // Public entry — shows loading immediately, then builds the UI.
    // ==================================================================

    /**
     * Opens the dialog. See the class-level "Opening flow" note for the
     * reasoning behind the skeleton-first approach.
     *
     * ### Theme resolution
     *
     * CodeAssistTheme.applyTheme(activity) is called before any view is
     * built, so every color used by the skeleton, the full form, the
     * loading panel and the progress / result panel comes from the
     * palette the user has selected in Settings. Already-open windows
     * keep their palette until reopened, which matches the settings
     * page's description of the theme selector.
     *
     * ### Tree v2: no last-state fallback
     *
     * The project's TreeConfigStore config is the only source of the
     * initial config and preset. The previous "last state" pair that
     * PresetManager used to persist has been removed in Tree v2: the
     * preset is now stored per project in config.json alongside the tree
     * overrides, so a fresh install or a project that has never generated
     * falls back to initialConfig and the "Custom" preset.
     */
    fun show() {
        // Resolve the palette for this dialog's lifetime. Called before
        // any view is built so all colors come from the correct theme.
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

    /**
     * Requests WRITE_EXTERNAL_STORAGE on the API range that still needs it
     * (26-28). No-op on API 29+.
     */
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

    /**
     * Skeleton shown while .gitignore + project config are read on the
     * background thread.
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

    /** Creates a rounded rectangle placeholder bar for the loading skeleton. */
    private fun skeletonBar(width: Int, height: Int, radius: Float, topMargin: Int = 0): View = View(activity).apply {
        background = CodeAssistTheme.roundedBg(activity, CodeAssistTheme.SURFACE_RAISED, radius)
        layoutParams = LinearLayout.LayoutParams(width, height).apply {
            this.topMargin = topMargin
        }
    }

    /** Converts density-independent pixels to actual screen pixels. */
    private fun dp(value: Int): Int = CodeAssistTheme.dp(activity, value)

    /** Applies the custom rounded, transparent background and sizing rules to the Dialog window. */
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

    /**
     * Cleans up resources when the dialog is dismissed.
     *
     * Manual tree changes are not persisted on dismiss. Writing them here
     * would silently pollute the project with decisions the user has not
     * committed to — Generate is the only operation that writes the
     * config, and only after the dump has been successfully published.
     */
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

    // ==================================================================
    // Full UI construction.
    // ==================================================================

    /**
     * Constructs the main configuration form.
     *
     * ### Preset resolution (Tree v2)
     *
     * The project's TreeConfigStore is consulted first. If it has a saved
     * config, its presetName determines which preset the spinner should
     * land on:
     *
     *  - The named preset still exists in PresetManager -> use its rules.
     *  - The named preset was deleted or is "Custom" -> use the saved
     *    filterConfig from the config.
     *  - No config exists -> use initialConfig and the "Custom" preset.
     *
     * The tree overrides from the config are then loaded into
     * currentOverrides so ProjectTreeView.scanAsync can apply them while
     * scanning.
     *
     * ### Gitignore section layout
     *
     * The .gitignore section is laid out as:
     *
     *   [TITLE]  (?)          <- sectionTitleWithHelp
     *   [ ] Respect .gitignore rules
     *   [chips flow]           <- populated only when the checkbox is on
     *
     * The checkbox controls exactly the flow below it, which is why it
     * lives here instead of in the base filters panel.
     */
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

        // Presets section — with its own help icon so the reusable-profile
        // concept is discoverable without leaving the dialog.
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

        // Base filters section. The "Respect .gitignore rules" checkbox
        // no longer lives here: it was moved inside the .gitignore section
        // below, where it can visually control the flow it governs.
        configContainer.addView(activity.sectionTitle(activity.pluginString(R.string.section_base_filters)))
        val basePanel = panel()
        cbBinaries = activity.checkBox(activity.pluginString(R.string.exclude_binaries)).apply {
            isChecked = currentConfig.excludeBinaries
            setOnCheckedChangeListener { _, _ -> if (!isUpdatingUI) markAsCustom() }
        }
        basePanel.addView(rowWithHelp(cbBinaries, R.string.help_binaries_title, R.string.help_binaries_body))

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

        configContainer.addView(tagSectionHeader(
            activity.pluginString(R.string.section_inclusions),
            R.string.help_inclusions_title,
            R.string.help_inclusions_body,
            activity.pluginString(R.string.action_add)
        ) { showAddTagDialog(activity.pluginString(R.string.add_inclusion_title)) { addTag(it, TagKind.INCLUSION) } })
        flowInclusions = createFlow()
        configContainer.addView(flowInclusions)

        configContainer.addView(tagSectionHeader(
            activity.pluginString(R.string.section_exclusions),
            R.string.help_exclusions_title,
            R.string.help_exclusions_body,
            activity.pluginString(R.string.action_add)
        ) { showAddTagDialog(activity.pluginString(R.string.add_exclusion_title)) { addTag(it, TagKind.EXCLUSION) } })
        flowExclusions = createFlow()
        configContainer.addView(flowExclusions)

        configContainer.addView(tagSectionHeader(
            activity.pluginString(R.string.section_stub_patterns),
            R.string.help_stub_title,
            R.string.help_stub_body,
            activity.pluginString(R.string.action_add)
        ) { showAddTagDialog(activity.pluginString(R.string.add_stub_title)) { addTag(it, TagKind.STUB) } })
        flowStubPatterns = createFlow()
        configContainer.addView(flowStubPatterns)

        // --- .gitignore section ---
        // Layout: [TITLE (?)] [checkbox] [chips flow].
        // The checkbox controls exactly the flow below it, which is why
        // it was moved here from the base filters panel.
        gitignoreSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        gitignoreSection.addView(sectionTitleWithHelp(
            R.string.section_gitignore_rules,
            R.string.help_gitignore_title,
            R.string.help_gitignore_body
        ))
        cbGitignore = activity.checkBox(activity.pluginString(R.string.respect_gitignore)).apply {
            isChecked = currentConfig.respectGitignore
            setOnCheckedChangeListener { _, checked ->
                if (isUpdatingUI) return@setOnCheckedChangeListener
                markAsCustom()
                // Rebuild only the flow in place: no dialog reload, no
                // tree re-scan. When unchecked, the chips disappear and
                // the section collapses to title + checkbox. When checked
                // again, the chips reappear from the cached rules, which
                // is why the .gitignore file does not need to be re-read.
                updateGitignoreFlowOnly(checked)
            }
        }
        gitignoreSection.addView(cbGitignore)
        flowGitignore = createFlow()
        gitignoreSection.addView(flowGitignore)
        configContainer.addView(gitignoreSection)

        // Version control section — with its own help icon so the branch /
        // tag / commit export flow and the ref picker are discoverable.
        if (gitEnabledGlobally) {
            configContainer.addView(sectionTitleWithHelp(
                R.string.section_version_control,
                R.string.help_version_control_title,
                R.string.help_version_control_body
            ))
            layoutGitBox = panel()
            configContainer.addView(layoutGitBox)
            setupGitUIAsync()
        } else {
            configContainer.addView(sectionTitleWithHelp(
                R.string.section_version_control,
                R.string.help_version_control_title,
                R.string.help_version_control_body
            ))
            layoutGitBox = panel()
            configContainer.addView(layoutGitBox)
            layoutGitBox.addView(activity.textView(
                activity.pluginString(R.string.git_disabled_globally),
                11f, CodeAssistTheme.TEXT_MUTED
            ).apply {
                setPadding(0, CodeAssistTheme.dp(activity, 4), 0, CodeAssistTheme.dp(activity, 4))
            })
        }

        configContainer.addView(sectionTitleWithHelp(
            R.string.section_tree,
            R.string.help_tree_title,
            R.string.help_tree_body
        ))
        val treeTogglePanel = panel()
        cbShowTree = activity.checkBox(activity.pluginString(R.string.show_tree)).apply {
            isChecked = true
            setOnCheckedChangeListener { _, checked -> setTreeVisibility(checked) }
        }
        treeTogglePanel.addView(cbShowTree)
        configContainer.addView(treeTogglePanel)

        // The checkbox starts checked but the listener was wired after
        // isChecked = true, so it never fired. Mark the section as visible
        // so the deferred startTreeScan() at the end of this method
        // actually runs; otherwise the loading spinner shown by
        // buildTreeLoadingPanel would be left orphaned with nothing to
        // cancel or complete it.
        treeVisible = true

        treeActionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, CodeAssistTheme.dp(activity, 4), 0, CodeAssistTheme.dp(activity, 4))
            visibility = View.VISIBLE
        }
        cbShowLocked = activity.checkBox(activity.pluginString(R.string.tree_show_locked)).apply {
            isChecked = PluginSettings.isTreeShowLockedEnabled(activity)
            minHeight = CodeAssistTheme.dp(activity, 32)
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
                ViewGroup.LayoutParams.MATCH_PARENT, CodeAssistTheme.dp(activity, 320)
            )
            visibility = View.VISIBLE
        }
        treeView = ProjectTreeView(
            activity = activity,
            projectRoot = projectRoot,
            onStatesChanged = { states -> currentStatesCache = states },
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
                // Tree v2: the project config only applies to its own
                // preset. Switching presets re-scans the tree with the
                // new rules; the previous project config is not applied.
                currentOverrides = TreeOverrides()
                if (treeVisible) startTreeScan()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        uiBuilt = true
        scroll.post { playEntrance(configContainer) }

        // Kick off the initial tree scan. Deferred until the form has been
        // laid out so the loading placeholder has a measured height and the
        // scan can report progress into the right views. treeVisible was
        // set to true earlier so this actually runs.
        if (treeVisible) {
            treeWrapper.post { startTreeScan() }
        }
    }

    // ==================================================================
    // Section help (?)
    // ==================================================================

    /**
     * Creates an inline help icon ("?") inside a 40 dp touch frame.
     *
     * The frame is what guarantees the minimum tap target; the icon
     * itself is 18 dp and sits inside the frame according to iconGravity:
     *
     *  - Gravity.CENTER (default): extra space on both sides of the
     *    icon. Good for help icons that sit at the end of a row where
     *    the row's own padding already provides breathing room.
     *  - Gravity.END or Gravity.CENTER_VERTICAL: extra space only on the
     *    start of the icon. Use this for icons inside section titles or
     *    panels, so the icon sits flush with the section's right edge
     *    instead of inheriting the frame's extra padding on top of the
     *    section's own.
     *  - Gravity.START or Gravity.CENTER_VERTICAL: extra space only on
     *    the end of the icon. Rare, but available for parity.
     *
     * FIT_CENTER is always applied to the ImageView's scaleType so the
     * 24 dp vector scales down inside the 18 dp icon bounds; using
     * CENTER would crop it.
     */
    private fun helpIcon(
        helpTitleRes: Int,
        helpBodyRes: Int,
        iconGravity: Int = Gravity.CENTER
    ): View {
        val touchSize = CodeAssistTheme.dp(activity, 40)
        val iconSize = CodeAssistTheme.dp(activity, 18)

        val container = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(touchSize, touchSize).apply {
                marginStart = CodeAssistTheme.dp(activity, 2)
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

    /**
     * Wraps a CheckBox in a horizontal row with a trailing help icon.
     *
     * Used for checkboxes that benefit from an inline "?" affordance
     * (binary exclusion today, potentially others in the future). The
     * checkbox is given weight = 1 so its touch target fills the
     * remaining width, and the help icon sits at the far right without
     * overlapping the checkbox's own hit area — that isolation is what
     * prevents a tap on the "?" from also toggling the value. The icon
     * uses Gravity.END so it sits flush with the panel's right edge
     * rather than inheriting the frame's extra padding on top of the
     * panel's own.
     */
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

    /** Opens the contextual help dialog with the given title and body resources. */
    private fun showSectionHelp(titleRes: Int, bodyRes: Int) {
        AlertDialog.Builder(activity)
            .setTitle(activity.pluginString(titleRes))
            .setMessage(activity.pluginString(bodyRes))
            .setPositiveButton(activity.pluginString(R.string.accept), null)
            .show()
            .styleDialog()
    }

    /**
     * Creates a section title with an inline help icon attached to the
     * right side.
     *
     * The icon uses Gravity.END so it sits flush with the section's right
     * edge. The extra touch area lives on the start of the icon only, so
     * the cumulative padding (root + section + icon frame) does not push
     * the icon visually away from the edge — this is the fix for the
     * "help icon has too much right margin when inside a section"
     * observation.
     */
    private fun sectionTitleWithHelp(titleRes: Int, helpTitleRes: Int, helpBodyRes: Int): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, CodeAssistTheme.dp(activity, 18), 0, CodeAssistTheme.dp(activity, 9))
        addView(activity.textView(
            activity.pluginString(titleRes).uppercase(LanguageHelper.detectIdeLocale(activity)),
            11f, CodeAssistTheme.TEXT_SECONDARY, true
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(helpIcon(helpTitleRes, helpBodyRes, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    // ==================================================================
    // Inline progress / result section
    // ==================================================================

    /**
     * Constructs the panel shown during RUNNING, SUCCESS, and ERROR states.
     * It replaces the configuration form via visibility toggles handled by
     * [applyState].
     */
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

    /** Creates a vertical icon+label chip used in the result screen (Share, Save, Regenerate). */
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

    /**
     * Renders the snapshot published by [ExportStateHolder].
     *
     * The guard that skips snapshots belonging to another project exists
     * because ExportStateHolder is a process-wide singleton.
     */
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

    /** Restores the form and hides the result panel. */
    private fun showConfigInline() {
        configContainer.visibility = View.VISIBLE
        progressContainer.visibility = View.GONE
    }

    /** Renders the ongoing export, showing a determinate or indeterminate progress bar. */
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
            progressBar.setProgress(target, /* animate = */ animationsEnabled)
        } else if (snapshot.total > 0 && snapshot.processed > 0) {
            progressBar.isIndeterminate = false
            if (progressBar.max != snapshot.total) progressBar.max = snapshot.total
            progressBar.setProgress(snapshot.processed, /* animate = */ animationsEnabled)
        } else {
            progressBar.isIndeterminate = true
            progressBar.progress = 0
        }

        progressCancelBtn.visibility = View.VISIBLE
        progressActionsRow.visibility = View.GONE
        progressBackLink.visibility = View.GONE
    }

    /** Renders the success state with file statistics and action chips. */
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

    /**
     * Error state. The three action chips remain visible for layout
     * stability, but "Share" and "Save" early-return when the snapshot's
     * uri is null.
     */
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

    /**
     * Formats the final file count, reference count, and estimated token
     * count into a single summary line.
     */
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

    /**
     * Verifies that the URI stored in the last success snapshot can still
     * be opened for reading.
     */
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

    /**
     * Called when the URI stored in the last success snapshot is no longer
     * readable.
     */
    private fun handleStaleResult(messageRes: Int) {
        Toast.makeText(activity, activity.pluginString(messageRes), Toast.LENGTH_LONG).show()
        ExportStateHolder.reset()
    }

    /**
     * Starts an export after asynchronously validating the Git reference,
     * if one is configured.
     */
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

    /**
     * Persists the tree config (preset + overrides diff) before launching
     * the export, then delegates to the coordinator.
     */
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

    /**
     * Shares the current success URI through the plugin's static share
     * helper.
     */
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

    /**
     * Re-runs the export with a timestamped filename, reusing the current
     * dump as the source.
     */
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

    /**
     * Triggers a fresh export run from the current configuration.
     */
    private fun onRegenerateClicked() {
        readUIValues()
        startExport(ExportAction.GENERATE)
    }

    // ==================================================================
    // Tree
    // ==================================================================

    /**
     * Sets the tree section's visual state in a single place, so the
     * loading panel, the tree view, the progress spinner, the cancel
     * button and the message text can never end up in an inconsistent
     * combination.
     *
     * In LOADING the spinner and Cancel button are both visible; in
     * ERROR only the message is actionable, so the spinner and Cancel are
     * hidden; in TREE the whole loading panel is hidden.
     */
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

    /** Manages the expansion and scanning of the project tree preview. */
    private fun setTreeVisibility(visible: Boolean) {
        treeVisible = visible
        if (::treeActionsRow.isInitialized) {
            treeActionsRow.visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (::treeWrapper.isInitialized) {
            treeWrapper.visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (visible) {
            startTreeScan()
        } else if (::treeView.isInitialized) {
            treeView.cancelScan()
            setTreeUiState(TreeUiState.TREE)
        }
    }

    /**
     * Reloads the tree. If the section is collapsed, expands it first so
     * the reload has somewhere to render into.
     */
    private fun reloadTree() {
        if (!treeVisible) {
            setTreeVisibility(true)
            return
        }
        startTreeScan()
    }

    /** Builds the loading panel shown while the project tree is being scanned. */
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
        treeLoadingProgress = ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                CodeAssistTheme.dp(activity, 34), CodeAssistTheme.dp(activity, 34)
            )
        }
        panel.addView(treeLoadingProgress)
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
        treeLoadingCancelButton = Button(activity).apply {
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
                setTreeUiState(TreeUiState.ERROR)
                treeLoadingText.text = activity.pluginString(R.string.canceled_retry)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, CodeAssistTheme.dp(activity, 36)
            ).apply {
                topMargin = CodeAssistTheme.dp(activity, 12)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        panel.addView(treeLoadingCancelButton)
        return panel
    }

    /**
     * Triggers the asynchronous project tree scan with current filters and
     * the project's persisted overrides applied.
     *
     * ### Cancellation safety
     *
     * ProjectTreeView.scanAsync cancels any previous scan silently: the
     * previous worker sees a stale token and returns without calling
     * onComplete / onError. That means the loading panel this method
     * shows can only be hidden by the callbacks of the scan it just
     * launched, which is exactly what we want — a superseded scan must
     * not flip the UI back to a tree that no longer matches the current
     * filters. The setTreeUiState call below is what guarantees the panel
     * is shown before the scan is dispatched, so there is never a window
     * in which the scan is running but the panel is hidden.
     *
     * ### Why it returns early
     *
     * The early return on !treeVisible is intentional: the tree section is
     * hidden, so there is nothing to show a spinner over. Tapping Reload
     * while the section is collapsed should not start a scan that has
     * nowhere to render. reloadTree expands the section first if needed.
     */
    private fun startTreeScan() {
        if (!treeVisible || !::treeView.isInitialized) return
        readUIValues()
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
                // Seed the cache as soon as the tree is rendered so an
                // immediate Generate without further interaction still
                // uses the correct state map.
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

    // ==================================================================
    // Git
    // ==================================================================

    /**
     * Renders the Git section without blocking the dialog.
     *
     * JGit class-loading and Repository.status are measurable costs on a
     * cold process, so a placeholder spinner is shown first and the real
     * content is swapped in on the main thread once the background probe
     * finishes.
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

    /** Renders the placeholder text when the selected folder is not a Git repository. */
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

    /** Renders the interactive Git controls (branch info, ref picker, diff preview). */
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

    /** Refreshes the Git branch/commit info in the background. */
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

    /**
     * Debounces ref resolution so every keystroke in the ref field does
     * not spin up a JGit Repository.resolve call.
     */
    private fun scheduleResolve() {
        pendingResolve?.let { resolveHandler.removeCallbacks(it) }
        val r = Runnable { updateResolvedRef() }
        pendingResolve = r
        resolveHandler.postDelayed(r, 300)
    }

    /**
     * Resolves the ref field's current text to a short commit hash on a
     * background thread and writes the result back to tvResolvedRef.
     */
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

    /** Opens the Git reference picker dialog (branches, tags, commits). */
    private fun showRefPickerDialog() {
        GitReferencePickerDialog(activity, projectRoot) { selectedRef ->
            isUpdatingUI = true
            etGitRef.setText(selectedRef)
            isUpdatingUI = false
            updateResolvedRef()
        }.show()
    }

    /** Opens the diff preview dialog for the current ref. */
    private fun showDiffPreview() { GitDiffPreviewDialog(activity, projectRoot).show(etGitRef.text.toString()) }

    // ==================================================================
    // Animations
    // ==================================================================

    /** Applies a staggered fade-in and translation animation to the children of the given view group. */
    private fun playEntrance(root: ViewGroup) {
        if (!animationsEnabled) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.alpha = 0f
            child.translationY = CodeAssistTheme.dp(activity, 10).toFloat()
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 30L).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** Animates a tag view scaling up and fading in. */
    private fun animateTagIn(view: View) {
        if (!animationsEnabled) return
        view.animate().cancel()
        view.scaleX = 0.5f; view.scaleY = 0.5f; view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(240).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    /** Animates a tag view scaling down and fading out before removing it. */
    private fun animateTagOut(view: View, onEnd: () -> Unit) {
        if (!animationsEnabled) { onEnd(); return }
        view.animate().cancel()
        view.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f)
            .setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction(onEnd).start()
    }

    /** Adds a tactile scale-down effect on press for buttons and chips. */
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

    // ==================================================================
    // Formatting
    // ==================================================================

    /** Formats a number to a human-readable abbreviated string (e.g., 1.5K, 2.3M). */
    private fun formatNumber(n: Long): String = when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> "%.1fK".format(n / 1000.0)
        else -> "%.2fM".format(n / 1_000_000.0)
    }

    /** Formats a byte count to a human-readable size string (e.g., 1.5 KB, 2.3 MB). */
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }

    // ==================================================================
    // UI helpers
    // ==================================================================

    /** Creates a standard rounded background panel used for grouping form fields. */
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

    /** Creates a horizontal divider line. */
    private fun divider(): View = View(activity).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, CodeAssistTheme.dp(activity, 1)
        ).apply {
            topMargin = CodeAssistTheme.dp(activity, 13)
            bottomMargin = CodeAssistTheme.dp(activity, 3)
        }
    }

    /** Creates a FlowLayout for displaying tag chips. */
    private fun createFlow(): FlowLayout = FlowLayout(activity).apply {
        setPadding(0, CodeAssistTheme.dp(activity, 2), 0, CodeAssistTheme.dp(activity, 5))
        minimumHeight = CodeAssistTheme.dp(activity, 16)
    }

    /**
     * Creates a section header with an inline add action and help icon.
     *
     * The help icon uses Gravity.END for the same reason as
     * sectionTitleWithHelp: the icon should sit flush with the section's
     * right edge rather than inheriting the frame's extra padding on top
     * of the section's own.
     */
    private fun tagSectionHeader(
        title: String,
        helpTitleRes: Int,
        helpBodyRes: Int,
        action: String,
        onAction: () -> Unit
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, CodeAssistTheme.dp(activity, 8), 0, CodeAssistTheme.dp(activity, 4))
        addView(activity.textView(title, 13f, CodeAssistTheme.TEXT_PRIMARY, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(activity.actionText(action, onAction).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = CodeAssistTheme.dp(activity, 8) }
        })
        addView(helpIcon(helpTitleRes, helpBodyRes, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    /** Applies a FilterConfig to the UI components without triggering "mark as custom" events. */
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

    /** Switches the preset spinner to "Custom" if the user modified a filter manually. */
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

    /** Switches to "Custom" preset mode and schedules a tree rescan. */
    private fun markAsCustom() { markAsCustomOnly(); scheduleRescan() }

    /**
     * Debounces tree re-scans triggered by filter changes so a burst of
     * edits (e.g. adding several tags in a row) does not queue a full
     * project walk per keystroke.
     */
    private fun scheduleRescan() {
        if (rescanScheduled) return
        rescanScheduled = true
        mainHandler.postDelayed({
            rescanScheduled = false
            if (treeVisible) startTreeScan()
        }, 220)
    }

    /**
     * Reads the current values from the UI controls and updates the
     * internal configuration state.
     *
     * The Git ref is not validated here. GitUtils.refExists performs
     * synchronous disk I/O against the repository, and this method is
     * called from many UI paths, so resolving the ref on every call was a
     * measurable source of UI jank on Git-enabled projects.
     */
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
                if (ref.isNotEmpty()) gitRef = ref else useGit = false
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

    /** Creates a unique identifier for a tag view, distinguishing between tag kinds. */
    private fun tagKey(tag: String, kind: TagKind) = "${kind.prefix}:$tag"

    /**
     * The three editable rule lists in the dialog: inclusions,
     * exclusions, and stub patterns.
     *
     * Encoded as an enum so the tag rendering, editing, and removal
     * helpers can be shared across all three lists with a single
     * parameter, instead of having three near-identical copies of the
     * same code.
     */
    private enum class TagKind(val prefix: String) {
        INCLUSION("I"),
        EXCLUSION("E"),
        STUB("S")
    }

    /**
     * Rebuilds the flow layouts (inclusions, exclusions, stub patterns,
     * gitignore) based on the current configuration.
     *
     * ### Gitignore flow
     *
     * The .gitignore flow is delegated to [updateGitignoreFlowOnly], which
     * populates it only when the "Respect .gitignore rules" checkbox is
     * on. That is what makes the checkbox toggle cheap: the flow is
     * cleared and repopulated in place, without touching any other part
     * of the dialog.
     */
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

        // The .gitignore section is never hidden as a whole: the title and
        // the checkbox are always visible, and only the flow's content is
        // controlled by the checkbox. updateGitignoreFlowOnly handles that
        // without touching the rest of the dialog.
        updateGitignoreFlowOnly(currentConfig.respectGitignore)

        if (animate && animationsEnabled) {
            val all = listOf(flowInclusions, flowExclusions, flowStubPatterns, flowGitignore)
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

    /**
     * Rebuilds only the .gitignore flow when the "Respect .gitignore
     * rules" checkbox is toggled, without tearing down the rest of the
     * dialog.
     *
     * When [enabled] is false, the flow is cleared and the section is
     * collapsed to just the title and the checkbox: the user has
     * explicitly told the plugin to ignore those rules, so showing them
     * would be misleading.
     *
     * When [enabled] is true, the flow is repopulated from the cached
     * [gitignoreTags] list. The list was read once when the dialog
     * opened; it is not re-read here, because the .gitignore file itself
     * has not changed and a second file read would be wasted I/O.
     */
    private fun updateGitignoreFlowOnly(enabled: Boolean) {
        if (!::flowGitignore.isInitialized) return
        flowGitignore.removeAllViews()
        if (!enabled) return
        if (gitignoreTags.isEmpty()) {
            addEmptyLabel(flowGitignore, activity.pluginString(R.string.no_gitignore_rules))
            return
        }
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

    /** Adds a muted placeholder label to an empty FlowLayout. */
    private fun addEmptyLabel(flow: FlowLayout, label: String) {
        flow.addView(activity.textView(label, 11f, CodeAssistTheme.TEXT_MUTED).apply {
            setPadding(
                CodeAssistTheme.dp(activity, 2), CodeAssistTheme.dp(activity, 5),
                CodeAssistTheme.dp(activity, 2), CodeAssistTheme.dp(activity, 5)
            )
        })
    }

    /**
     * Creates and adds a tag chip to a FlowLayout, determining its color
     * based on the tag kind and conflicts.
     */
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

    // ==================================================================
    // Gitignore vs user rule matching (help highlighters)
    // ==================================================================

    /** Checks if a specific .gitignore rule is visually overridden by a user rule. */
    private fun isGitignoreRuleOverridden(gitignoreRule: String): Boolean {
        val cleanTag = gitignoreRule.removePrefix("!")
        return currentConfig.inclusions.any { ruleMatchesGitignoreTag(it, cleanTag) } ||
            currentConfig.exclusions.any { ruleMatchesGitignoreTag(it, cleanTag) } ||
            currentConfig.stubPatterns.any { ruleMatchesGitignoreTag(it, cleanTag) }
    }

    /** Checks if a user rule overrides any of the loaded .gitignore rules. */
    private fun userRuleOverridesGitignore(rule: String): Boolean =
        gitignoreTags.any { gitignoreTag ->
            ruleMatchesGitignoreTag(rule, gitignoreTag.removePrefix("!"))
        }

    /** Compares a user rule against a gitignore tag to see if they target the same file/extension. */
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

    /**
     * Minimal glob matcher that mirrors the wildcard support of
     * FileFilter.
     */
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

    /** Creates a styled TextView used as a tag chip. */
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

    /** Validates and adds a new tag to the configuration, then updates the UI. */
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

    /**
     * Shows the edit/delete context menu when a tag is tapped.
     */
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

    /** Shows a dialog with a text field to add or edit a tag. */
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

    /** Builds the list of preset names for the spinner (Custom + Built-in + User presets). */
    private fun getPresetList(): MutableList<String> = mutableListOf(customPresetLabel()).apply {
        addAll(presetManager.getDefaultPresets().keys)
        addAll(presetManager.getUserPresetNames())
    }

    /** Shows a dialog to save the current configuration as a new preset. */
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

    /** Prompts the user to confirm deletion of the currently selected user preset. */
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

    /** Animates the preset save/delete icon when a preset action occurs. */
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

    /** Applies the dark, rounded CodeAssist theme to a standard Android AlertDialog. */
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
        /**
         * Request code for WRITE_EXTERNAL_STORAGE on API 26-28. Distinctive
         * value (hex 0x0C0DE = "code") so it cannot collide with a request
         * code the host CodeAssist app might use.
         */
        const val REQUEST_STORAGE_PERMISSION = 0x0C0DE
    }
}