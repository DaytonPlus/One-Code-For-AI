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
 * Opening flow
 * ------------
 * The dialog deliberately shows a lightweight skeleton first, then swaps to the
 * real form once the slow inputs are ready — that way it appears instantly even
 * on devices where JGit class-loading, `.gitignore` parsing, or the last-saved
 * state read take a few hundred milliseconds:
 *
 *  1. [show] displays the skeleton screen and requests the legacy storage
 *     permission (API 26–28 only) so the system prompt has time to appear
 *     while the UI is still loading.
 *  2. `.gitignore` rules and the last saved state are read on a background
 *     thread (`OneCode-dialog-init`).
 *  3. [buildFullUI] is invoked on the main thread once that data is available.
 *  4. The Git section (JGit access) is rendered with its own loading
 *     placeholder and resolved asynchronously on `OneCode-git-info`, so the
 *     dialog is never blocked by repository I/O.
 *
 * Legacy storage permission
 * -------------------------
 * On API 26–28 the export writes to `Download/OneCodeForAI` through the
 * legacy public-Downloads path, which requires `WRITE_EXTERNAL_STORAGE`. API
 * 29+ uses MediaStore and needs no permission at all.
 * [requestLegacyStoragePermissionIfNeeded] fires the system prompt on the
 * first dialog open; the OS records the decision, and
 * [com.devdayton.onecodeforai.android.ExportCoordinator.writeToDownloadsLegacy]
 * re-checks it at write time.
 *
 * If the user denied the permission, the export **fails visibly** with
 * [R.string.error_storage_permission] — there is no cache fallback and no
 * `ContentProvider`. Users on API 26–28 can grant the permission once in
 * system settings and retry. Shipping `androidx.core` purely to expose a
 * fallback cache file was not considered a good trade-off for a plugin that
 * otherwise has no AndroidX dependency.
 *
 * Stale URIs
 * ----------
 * The result state ([ExportStateHolder]) is a process-wide singleton and
 * survives for the lifetime of the CodeAssist process. Its `Snapshot.uri`
 * points to the file the last export produced. If the user deletes or moves
 * that file from a system file manager while the dialog is still open, the
 * URI becomes stale: the underlying MediaStore entry no longer exists.
 *
 * The "Share" and "Save" chips therefore validate the URI via
 * [isSnapshotUriReadable] before using it. A stale URI is handled by
 * [handleStaleResult], which resets the state to [ExportStateHolder.State.IDLE]
 * (restoring the configuration form so the user can regenerate) and shows
 * [R.string.error_source_file_missing] instead of a misleading write error.
 *
 * Git master switch
 * -----------------
 * [gitEnabledGlobally] mirrors `PluginSettings.isGitEnabled`. When `false`,
 * the Git section still renders but displays a static "disabled globally"
 * notice instead of the interactive branch/commit controls. This explains
 * to the user why Git features are missing, regardless of whether the
 * selected folder is a repository or not.
 *
 * Git ref validation
 * ------------------
 * The Git ref typed into the ref field is validated **asynchronously** at
 * the moment the user clicks "Generate" (see [startExport]). The previous
 * implementation called `GitUtils.refExists` synchronously from
 * [readUIValues], which meant every filter change on a Git-enabled project
 * triggered a JGit `Repository.resolve` on the UI thread — up to several
 * hundred milliseconds of jank on network-mounted repositories. The new
 * flow captures the config at click time, validates the ref on a background
 * thread, and only then starts the export. `readUIValues` no longer
 * validates the ref, so it is now cheap enough to call on every UI event.
 *
 * Tree preview
 * ------------
 * The interactive file-tree preview is always rendered. A previous version
 * exposed a `treeEnabledGlobally` flag that was always `true` because the
 * corresponding preference was never exposed in the settings page; the flag
 * has been removed to keep the code honest. See
 * [com.devdayton.onecodeforai.OneCodeForAIPlugin] for the matching cleanup
 * on the plugin entry point.
 *
 * `.gitignore` negations
 * ----------------------
 * `FileFilter.loadGitignoreRules()` returns negated rules (`!foo`) with the
 * `!` prefix preserved, so the UI can render them with a distinct style and
 * the user can see that their `.gitignore` is force-including a file. The
 * helper functions that compare user rules against `.gitignore` tags strip
 * the `!` defensively before matching, because the negation prefix is not
 * part of the pattern itself.
 *
 * Total export size limit
 * -----------------------
 * The total export size limit is configured on the plugin settings page (see
 * [com.devdayton.onecodeforai.OneCodeForAISettingsPage]) and enforced by
 * [com.devdayton.onecodeforai.core.CodeDumpGenerator]. This dialog does not
 * expose it: the value is read fresh by
 * [com.devdayton.onecodeforai.android.ExportCoordinator] at the start of
 * every export, so the user can change it from Settings while the dialog is
 * minimized and the next export will already respect the new cap.
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
    private var isCustomMode = false
    private var isUpdatingUI = false
    private var gitignoreTags: List<String> = emptyList()

    // [PERF] Distinguishes "UI built" from "still loading", so the dismiss handler
    // can safely skip readUIValues()/saveLastState() when the user closes the
    // dialog while the initial data is still being loaded.
    private var uiBuilt = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rescanScheduled = false

    /**
     * Master gate for every fade / scale / rotation animation in this dialog.
     *
     * Uses a custom getter instead of `by lazy` so that if the user disables
     * animations in the global settings while this dialog is open, the change
     * is picked up immediately for subsequent animations.
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

    /**
     * Picks the initial spinner position from the last-saved preset name.
     *
     * `lastStatePreset` is the string that was stored in
     * `PresetManager.saveLastState`, which today comes from the spinner's
     * currently-selected item. That means it is already the *localized* custom
     * label — hence the `lastStatePreset == customLabel` branch — but two
     * literal values ("Personalizado" and "Custom") are kept for backward
     * compatibility: they were the hardcoded custom label before it was moved
     * into `R.string.custom_preset`, so profiles saved by earlier plugin
     * versions would otherwise silently fall through to index 0 anyway. A
     * future cleanup can drop the two literals once the migration window is
     * closed.
     *
     * An empty string (the new fallback returned by
     * `PresetManager.loadLastState` when no preset has ever been saved) also
     * resolves to index 0 through the `getPosition("") == -1` path.
     */
    private fun resolveInitialPresetIndex(lastStatePreset: String?): Int {
        val customLabel = customPresetLabel()
        return when {
            lastStatePreset == null -> 0
            lastStatePreset == customLabel -> 0
            // Legacy hardcoded custom labels kept for backward compatibility.
            lastStatePreset == "Personalizado" -> 0
            lastStatePreset == "Custom" -> 0
            else -> presetAdapter.getPosition(lastStatePreset).takeIf { it >= 0 } ?: 0
        }
    }

    // ==================================================================
    // [PERF] Public entry — shows loading immediately, then builds the UI.
    // ==================================================================

    /**
     * Opens the dialog. See the class-level *Opening flow* note for the
     * reasoning behind the skeleton-first approach.
     *
     * The legacy storage permission is requested before the dialog is shown
     * (API 26–28 only) so the system prompt can appear while the skeleton is
     * still on screen. The permission decision is not awaited: the export
     * coordinator re-checks at write time. If the permission is still
     * missing, the export fails visibly with [R.string.error_storage_permission]
     * instead of silently falling back to a cache copy — see the class-level
     * *Legacy storage permission* note.
     */
    fun show() {
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
            val lastState = runCatching { presetManager.loadLastState() }.getOrNull()
            activity.runOnUiThread {
                if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                gitignoreTags = gitignore
                buildFullUI(dialog, lastState)
            }
        }, "OneCode-dialog-init").apply { isDaemon = true; start() }
    }

    /**
     * Requests `WRITE_EXTERNAL_STORAGE` on the API range that still needs it
     * (26–28). No-op on API 29+ (MediaStore handles Downloads without any
     * permission) and on API < 23 (implicit at install time).
     *
     * The request is intentionally fire-and-forget: the host Activity does not
     * override `onRequestPermissionsResult`, so we cannot await the answer
     * here. That is fine — the OS records the decision, and
     * [com.devdayton.onecodeforai.android.ExportCoordinator.writeToDownloadsLegacy]
     * re-checks the current permission state at write time. If it is still
     * missing, the coordinator reports a localized error through
     * [com.devdayton.onecodeforai.android.ExportStateHolder.fail] and no
     * output file is produced. The user can grant the permission once and
     * retry.
     *
     * Uses the native `Activity.checkSelfPermission(...)` (API 23+, and the
     * plugin's minSdk is 26) instead of `ContextCompat` so the plugin does
     * not need `androidx.core` for this single call.
     *
     * The manifest declares the permission with `android:maxSdkVersion="28"`,
     * so on API 29+ the system will not even offer it — this guard just makes
     * the intent explicit at the call site.
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
     * Skeleton shown while `.gitignore` + last state are read on the background
     * thread. Layout intentionally mirrors the shape of the real form (header,
     * two medium panels, one flexible panel) so the swap in [buildFullUI] does
     * not cause a visible reflow.
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

        // Two fixed-height skeleton panels + one flexible panel, to match the
        // real form's proportions.
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
     * Cleans up resources and saves the current UI state when the dialog is dismissed.
     * This handler guarantees that even if the user closes the dialog mid-export,
     * the correct callbacks are triggered and observers are unregistered.
     */
    private fun handleDismiss(dialog: Dialog) {
        if (activeDialog === dialog) activeDialog = null
        stateUnsubscribe?.invoke()
        stateUnsubscribe = null
        try {
            pendingResolve?.let { resolveHandler.removeCallbacks(it) }
            resolveHandler.removeCallbacksAndMessages(null)
            if (::treeView.isInitialized) treeView.cancelScan()
            // Only persist the current form values if the UI was actually built:
            // otherwise the lateinit fields are not initialized yet and the
            // saved state would overwrite the user's previous choices with
            // defaults.
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
    // [PERF] Full UI construction.
    // ==================================================================

    /**
     * Constructs the main configuration form.
     *
     * This method is called on the main thread once the slow initial data
     * (gitignore rules, last saved state) is ready. It assembles the header,
     * the preset spinner, the base filter checkboxes, tag sections, the Git
     * interactive section, the file tree preview, and the inline progress/result
     * container.
     */
    private fun buildFullUI(dialog: Dialog, lastState: Pair<FilterConfig, String>?) {
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
        // Binary exclusion ships with a dedicated help icon so the fixed
        // extension list and its override rules are discoverable without
        // opening the FAQ. The checkbox is wrapped in a horizontal row
        // because the help icon must sit outside the checkbox's touch target
        // to avoid toggling the value when the user taps the "?" icon.
        basePanel.addView(rowWithHelp(cbBinaries, R.string.help_binaries_title, R.string.help_binaries_body))
        basePanel.addView(cbGitignore)

        // Max size row
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

        // Tags — Inclusions / Exclusions.
        configContainer.addView(tagSectionHeader(
            activity.pluginString(R.string.section_inclusions),
            R.string.help_inclusions_title,
            R.string.help_inclusions_body,
            activity.pluginString(R.string.action_add)
        ) { showAddTagDialog(activity.pluginString(R.string.add_inclusion_title)) { addTag(it, true) } })
        flowInclusions = createFlow()
        configContainer.addView(flowInclusions)

        configContainer.addView(tagSectionHeader(
            activity.pluginString(R.string.section_exclusions),
            R.string.help_exclusions_title,
            R.string.help_exclusions_body,
            activity.pluginString(R.string.action_add)
        ) { showAddTagDialog(activity.pluginString(R.string.add_exclusion_title)) { addTag(it, false) } })
        flowExclusions = createFlow()
        configContainer.addView(flowExclusions)

        gitignoreSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        gitignoreSection.addView(sectionTitleWithHelp(
            R.string.section_gitignore_rules,
            R.string.help_gitignore_title,
            R.string.help_gitignore_body
        ))
        flowGitignore = createFlow()
        gitignoreSection.addView(flowGitignore)
        configContainer.addView(gitignoreSection)

        // Git
        //
        // If Git is enabled globally, we render the interactive UI.
        // If Git is disabled, we still render the section but show a static
        // "disabled globally" notice. This informs the user why branch/tag
        // controls are missing, regardless of whether the folder is a repo.
        if (gitEnabledGlobally) {
            configContainer.addView(activity.sectionTitle(activity.pluginString(R.string.section_version_control)))
            layoutGitBox = panel()
            configContainer.addView(layoutGitBox)
            setupGitUIAsync()
        } else {
            configContainer.addView(activity.sectionTitle(activity.pluginString(R.string.section_version_control)))
            layoutGitBox = panel()
            configContainer.addView(layoutGitBox)
            layoutGitBox.addView(activity.textView(
                activity.pluginString(R.string.git_disabled_globally),
                11f, CodeAssistTheme.TEXT_MUTED
            ).apply {
                setPadding(
                    0,
                    CodeAssistTheme.dp(activity, 4),
                    0,
                    CodeAssistTheme.dp(activity, 4)
                )
            })
        }

        // Tree
        //
        // The tree preview is always rendered. A previous version gated it
        // behind a `treeEnabledGlobally` flag that was always `true` because
        // the corresponding preference was never exposed in the settings
        // page; the flag has been removed and the section is now
        // unconditional. See the class-level *Tree preview* note.
        //
        // The tree section ships with a help icon (same "?" affordance as
        // the filter sections) so the include/exclude semantics of the
        // checkboxes are discoverable without leaving the dialog.
        configContainer.addView(sectionTitleWithHelp(
            R.string.section_tree,
            R.string.help_tree_title,
            R.string.help_tree_body
        ))
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

        // Single "Generate" button
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
        scroll.post { playEntrance(configContainer) }
    }

    // ==================================================================
    // Section help (?)
    // ==================================================================

    /**
     * Creates an inline help icon ("?") with a moderate visual footprint and
     * a touch-friendly hit area.
     *
     * The icon is an 18 dp vector centered inside a 40 dp transparent
     * [FrameLayout]. The frame (not the icon itself) owns the click handler,
     * so the user can reliably tap the affordance without accidentally
     * triggering the adjacent section title or checkbox. This matches the
     * plugin's minimum tap-target rule while keeping the visual weight low
     * enough that it does not compete with the section title.
     *
     * 18 dp is the sweet spot between the previous 14 dp (which was reported
     * as visually too small against bold section titles) and the underlying
     * 24 dp vector (which would dominate the row). It also leaves a 2 dp
     * breathing room on every side inside the 40 dp frame, which keeps the
     * icon comfortably centered even when the container gets a stroke or
     * background in a future iteration.
     *
     * `FIT_CENTER` (rather than `CENTER`) is used because the underlying
     * vector is 24 dp and must be scaled down inside the 18 dp frame; using
     * `CENTER` would crop it.
     */
    private fun helpIcon(helpTitleRes: Int, helpBodyRes: Int): View {
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
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            }
        )
        return container
    }

    /**
     * Wraps a [CheckBox] in a horizontal row with a trailing help icon.
     *
     * Used for checkboxes that benefit from an inline "?" affordance (binary
     * exclusion today, potentially others in the future). The checkbox is
     * given `weight = 1` so its touch target fills the remaining width, and
     * the help icon sits at the far right without overlapping the checkbox's
     * own hit area — that isolation is what prevents a tap on the "?" from
     * also toggling the value.
     */
    private fun rowWithHelp(cb: CheckBox, helpTitleRes: Int, helpBodyRes: Int): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cb.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(cb)
        row.addView(helpIcon(helpTitleRes, helpBodyRes))
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

    /** Creates a section title with an inline help icon attached to the right side. */
    private fun sectionTitleWithHelp(titleRes: Int, helpTitleRes: Int, helpBodyRes: Int): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, CodeAssistTheme.dp(activity, 18), 0, CodeAssistTheme.dp(activity, 9))
        addView(activity.textView(
            // See UiFactory.sectionTitle for the rationale: `Locale.getDefault()`
            // diverges from the IDE locale on non-Latin-script systems, and
            // `LanguageHelper.detectIdeLocale` keeps the uppercase consistent
            // with the localized content and the force-English toggle.
            activity.pluginString(titleRes).uppercase(LanguageHelper.detectIdeLocale(activity)),
            11f, CodeAssistTheme.TEXT_SECONDARY, true
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(helpIcon(helpTitleRes, helpBodyRes))
    }

    // ==================================================================
    // Inline progress / result section
    // ==================================================================

    /**
     * Constructs the panel shown during `RUNNING`, `SUCCESS`, and `ERROR` states.
     * It replaces the configuration form via visibility toggles handled by [applyState].
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
     * because [ExportStateHolder] is a process-wide singleton: a background
     * export started from a *different* project (e.g. a second dialog that
     * was opened after this one) would otherwise hijack this dialog's UI and
     * show its progress here. In that case we simply keep showing the config
     * form — the other dialog is already displaying the correct state.
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
     * stability, but "Share" and "Save" early-return when
     * [ExportStateHolder.Snapshot.uri] is null, which is always the case
     * after [com.devdayton.onecodeforai.android.ExportStateHolder.fail]
     * (there is no cache fallback any more, see the class-level
     * *Legacy storage permission* note). In practice only "Regenerate" and
     * the "Edit settings" link are functional in this state — which is
     * exactly what the user needs to either retry with the permission
     * granted or adjust the filters.
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
     * Formats the final file count, estimated tokens, and total size into a
     * single summary line.
     *
     * The file-count label goes through [pluginQuantityString] so the
     * correct CLDR plural form is selected for the current locale. This
     * replaces the previous `if (count == 1) one else many` hack, which
     * only worked for two-form languages (English, Spanish, Portuguese)
     * and silently produced the wrong form for languages that need
     * `few`/`many` (Russian, Polish, Czech) or `zero`/`two` (Arabic).
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
        return "$filesLabel  ·  $tokensLabel  ·  ${formatSize(snapshot.totalBytes)}"
    }

    /**
     * Verifies that the URI stored in the last success snapshot can still be
     * opened for reading.
     *
     * MediaStore URIs (and, on API 26–28, the `file://` URI of a registered
     * entry) can both become invalid if the user deletes or moves the file from
     * a file manager while the dialog is open. In that case the "Share" and
     * "Save" actions must not try to use the stale URI:
     *
     *  - Share would launch the chooser with a URI the receiving app cannot
     *    read, so the app would fail silently from the user's perspective.
     *  - Save would hit `copyFromUri`'s `openInputStream` → `IOException` and
     *    surface a misleading "File write failed" error, even though the
     *    problem is a missing source file, not a failed write.
     *
     * ### Implementation note
     * The check uses a `query` for the `_ID` column rather than opening the
     * input stream. A query is a pure metadata round-trip that never touches
     * the file data, so it returns in ~1 ms even on slow eMMC storage where
     * `openInputStream` can block for 50-100 ms while the filesystem
     * resolves the URI and permissions.
     *
     * The trade-off is that an extremely rare edge case — a MediaStore row
     * that still exists but whose backing file was deleted by a non-MediaStore
     * path on API 26–28 — is not caught here. If that happens, the subsequent
     * `shareFile` call fails gracefully with a localized toast, which is an
     * acceptable outcome for a case that requires two separate mutations of
     * the same file while the dialog is open.
     *
     * @return `true` when [ExportStateHolder.Snapshot.uri] is still readable.
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
     * readable — typically because the user deleted or moved the file from a
     * system file manager while the dialog was open.
     *
     * [ExportStateHolder.reset] is the right response here: it returns the
     * dialog to `IDLE`, which restores the configuration form and makes the
     * "Generate" button available again. The user does not need a separate
     * "Regenerate" CTA because the normal flow already leads there.
     *
     * The toast is deliberately long (`LENGTH_LONG`) and explains the cause
     * ("may have been deleted or moved") rather than the symptom, so the
     * user understands what actually happened.
     */
    private fun handleStaleResult(messageRes: Int) {
        Toast.makeText(activity, activity.pluginString(messageRes), Toast.LENGTH_LONG).show()
        ExportStateHolder.reset()
    }

    /**
     * Starts an export after asynchronously validating the Git reference, if
     * one is configured.
     *
     * ### Why this is asynchronous
     * `GitUtils.refExists` opens a JGit repository and resolves a reference,
     * which is a synchronous filesystem operation that can take hundreds of
     * milliseconds on a network mount or a very large repository. The
     * previous implementation called it from [readUIValues], which meant
     * every filter change on a Git-enabled export triggered disk I/O on the
     * UI thread and produced visible jank.
     *
     * ### Config snapshot
     * `currentConfig` is captured **once**, before the thread is spawned, so
     * the eventual export uses exactly the state the user saw when they
     * pressed Generate. If the user changes a filter while validation is
     * running, the change applies to the *next* export, not the current one.
     * Capturing the reference also guarantees that the main-thread callback
     * does not read `currentConfig` from a different point in time than the
     * one that was validated.
     *
     * ### Short-circuit
     * When Git is disabled or no ref is set, the validation is skipped
     * entirely and the export starts on the calling thread, so the common
     * case has no extra thread hop.
     *
     * @param action the export action to start once the ref (if any) is
     *   validated.
     */
    private fun startExport(action: ExportAction) {
        val configSnapshot = currentConfig
        val ref = configSnapshot.gitRef?.trim().orEmpty()
        if (!configSnapshot.useGit || ref.isEmpty()) {
            launchExport(configSnapshot, action)
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
                launchExport(configSnapshot, action)
            }
        }.apply { isDaemon = true; name = "OneCode-ref-validate"; start() }
    }

    /**
     * Delegates the export start to the coordinator and shows a toast if
     * another export is already running.
     *
     * Extracted so both [startExport] branches (the synchronous one and the
     * async Git-validation callback) funnel through the same coordinator
     * call. The `config` parameter is captured by the caller and is
     * deliberately **not** read from `currentConfig` here, so the reference
     * that was validated is exactly the one that gets exported.
     */
    private fun launchExport(config: FilterConfig, action: ExportAction) {
        if (!exportCoordinator.export(projectRoot, config, action)) {
            Toast.makeText(
                activity, activity.pluginString(R.string.export_already_running), Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Shares the current success URI through the plugin's static share helper.
     *
     * Before launching the chooser, verifies that the URI is still readable.
     * A MediaStore URI can become invalid if the user deleted or moved the
     * file while the dialog was open; in that case launching the chooser
     * would give the receiving app a URI it cannot read, and the failure
     * would be silent from the user's perspective. See
     * [isSnapshotUriReadable] for the full rationale.
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
     * Re-runs the export with a timestamped filename, reusing the current dump
     * as the source so the project does not have to be scanned again.
     *
     * Before starting the SAVE run, verifies that the source URI is still
     * readable. If the file was deleted or moved while the dialog was open,
     * the coordinator's `copyFromUri` would hit `openInputStream` → `null`,
     * throw `IOException("Could not open source URI.")`, and the user would
     * see a misleading "File write failed" message. The pre-check turns that
     * into an accurate "file missing" message and resets the state. See
     * [isSnapshotUriReadable] for the full rationale.
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

    /**
     * Triggers a fresh export run from the current configuration.
     *
     * Calls [readUIValues] to refresh `currentConfig` from the UI, then
     * delegates to [startExport], which performs the (possibly asynchronous)
     * Git-ref validation before actually starting the export.
     */
    private fun onRegenerateClicked() {
        readUIValues()
        startExport(ExportAction.GENERATE)
    }

    // ==================================================================
    // Tree
    // ==================================================================

    /** Manages the expansion and scanning of the project tree preview. */
    private fun setTreeVisibility(visible: Boolean) {
        treeVisible = visible
        if (::treeActionsRow.isInitialized) treeActionsRow.visibility = if (visible) View.VISIBLE else View.GONE
        if (::treeWrapper.isInitialized) treeWrapper.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) startTreeScan() else if (::treeView.isInitialized) treeView.cancelScan()
    }

    /** Re-triggers the tree scan if the tree is currently visible. */
    private fun reloadTree() { if (treeVisible) startTreeScan() }

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

    /** Triggers the asynchronous project tree scan with current filters applied. */
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
     * Renders the Git section without blocking the dialog.
     *
     * JGit class-loading and `Repository.status()` are measurable costs on a
     * cold process, so a placeholder spinner is shown first and the real
     * content is swapped in from `OneCode-git-info` on the main thread once
     * the background probe finishes. The `uiBuilt` guard prevents the swap
     * from touching views after the dialog was dismissed mid-load.
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
     * Debounces ref resolution so every keystroke in the ref field does not
     * spin up a JGit `Repository.resolve` call. The 300 ms window matches the
     * typical typing cadence; a slower user still gets the same responsiveness
     * as a fast one because the previous runnable is always cancelled.
     */
    private fun scheduleResolve() {
        pendingResolve?.let { resolveHandler.removeCallbacks(it) }
        val r = Runnable { updateResolvedRef() }
        pendingResolve = r
        resolveHandler.postDelayed(r, 300)
    }

    /**
     * Resolves the ref field's current text to a short commit hash on a
     * background thread and writes the result back to [tvResolvedRef].
     *
     * The `etGitRef.text.toString().trim() != ref` guard discards the result
     * if the user kept typing while the background resolve was in flight,
     * so a stale "Resolved: abc1234" can never be shown against a newer
     * value in the field.
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

    /** Creates a section header with an inline add action and help icon. */
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
        addView(helpIcon(helpTitleRes, helpBodyRes))
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
     * Debounces tree re-scans triggered by filter changes so a burst of edits
     * (e.g. adding several tags in a row) does not queue a full project walk
     * per keystroke.
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
     * Reads the current values from the UI controls and updates the internal
     * configuration state.
     *
     * The Git ref is **not** validated here. `GitUtils.refExists` performs
     * synchronous disk I/O against the repository, and this method is called
     * from many UI paths (filter changes, tree scans, dialog dismissal), so
     * resolving the ref on every call was a measurable source of UI jank on
     * Git-enabled projects. Validation is deferred to [startExport], which
     * runs it on a background thread and only proceeds with the export after
     * a positive result. The ref is stored verbatim here; the export
     * coordinator re-reads it from the captured `config` and a failed
     * resolution is reported as a normal `GitRefUnresolvedException`.
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

    /** Creates a unique identifier for a tag view, distinguishing between inclusions and exclusions. */
    private fun tagKey(tag: String, isInclusion: Boolean) = "${if (isInclusion) "I" else "E"}:$tag"

    /**
     * Rebuilds the flow layouts (inclusions, exclusions, gitignore) based on
     * the current configuration.
     *
     * ### `.gitignore` negations
     * `FileFilter.loadGitignoreRules()` returns negated rules with their `!`
     * prefix intact, so this method can render them with a distinct visual
     * style and the user can see that a `!foo` rule in their `.gitignore` is
     * force-including a file. Negated rules use the raised-surface palette
     * rather than the exclude-red palette to signal that their effect is the
     * opposite of a normal ignore rule.
     *
     * ### Conflict highlighting
     * Positive rules are highlighted in amber when a user inclusion or
     * exclusion overrides them. Negated rules are not checked for overrides
     * because the override logic compares rule text against a clean tag, and
     * the negation prefix would defeat the comparison. This is intentional:
     * the "overridden by user rule" badge is meaningless for a rule that
     * already expresses "include this anyway".
     */
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
        }

        if (animate && animationsEnabled) {
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

    /** Adds a muted placeholder label to an empty FlowLayout. */
    private fun addEmptyLabel(flow: FlowLayout, label: String) {
        flow.addView(activity.textView(label, 11f, CodeAssistTheme.TEXT_MUTED).apply {
            setPadding(
                CodeAssistTheme.dp(activity, 2), CodeAssistTheme.dp(activity, 5),
                CodeAssistTheme.dp(activity, 2), CodeAssistTheme.dp(activity, 5)
            )
        })
    }

    /** Creates and adds a tag chip to a FlowLayout, determining its color based on conflicts. */
    private fun addTagToFlow(flow: FlowLayout, tag: String, isInclusion: Boolean) {
        val gitignoreOverride = userRuleOverridesGitignore(tag)
        val listConflict = if (isInclusion) currentConfig.exclusions.any { it.equals(tag, ignoreCase = true) }
                           else currentConfig.inclusions.any { it.equals(tag, ignoreCase = true) }
        val conflict = gitignoreOverride || listConflict
        val bg = when { conflict -> CodeAssistTheme.TAG_CONFLICT; isInclusion -> CodeAssistTheme.TAG_INCLUDE; else -> CodeAssistTheme.TAG_EXCLUDE }
        val fg = when { conflict -> CodeAssistTheme.TAG_CONFLICT_TEXT; isInclusion -> CodeAssistTheme.TAG_INCLUDE_TEXT; else -> CodeAssistTheme.TAG_EXCLUDE_TEXT }
        val view = createTagView(tag, bg, fg) { showTagOptions(tag, isInclusion) }
        flow.addView(view)
        tagViews[tagKey(tag, isInclusion)] = view
    }

    // ==================================================================
    // Gitignore vs user rule matching (help highlighters)
    //
    // The two helpers below compare a user rule (an extension, a path, or
    // a glob) against the raw `.gitignore` tags shown in the UI. Since
    // `loadGitignoreRules` now preserves the `!` negation prefix for
    // display, both helpers strip that prefix before matching so a
    // `!important.txt` rule can still be compared against a user rule
    // that targets `important.txt`.
    // ==================================================================

    /** Checks if a specific .gitignore rule is visually overridden by a user inclusion or exclusion. */
    private fun isGitignoreRuleOverridden(gitignoreRule: String): Boolean {
        val cleanTag = gitignoreRule.removePrefix("!")
        return currentConfig.inclusions.any { ruleMatchesGitignoreTag(it, cleanTag) } ||
            currentConfig.exclusions.any { ruleMatchesGitignoreTag(it, cleanTag) }
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
     * Minimal glob matcher that mirrors the wildcard support of [FileFilter]:
     * single-char `?`, single-segment `*`, and the cross-segment double-star
     * wildcard (including its directory-prefix form).
     *
     * Only invoked from the UI thread on small rule strings (one per chip), so
     * the cost of recompiling a [Regex] per call is negligible and not worth
     * caching.
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

    /**
     * Shows the edit/delete context menu when a tag is tapped.
     *
     * The rename branch uses `distinctBy { it.lowercase() }` rather than the
     * plain `distinct()`, because the latter is case-sensitive. Renaming
     * `"kt"` to `"Kt"` would otherwise produce a list containing both
     * entries (the two strings differ), and both would be rendered as
     * separate chips even though the normalizer in
     * `FilterConfig.normalized()` would have collapsed them into one on the
     * next export. Applying the case-insensitive dedup here keeps the
     * intermediate state consistent with what the exporter will see.
     */
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
                                inclusions = currentConfig.inclusions
                                    .map { if (it == tag) normalized else it }
                                    .distinctBy { it.lowercase() }
                            ) else currentConfig.copy(
                                exclusions = currentConfig.exclusions
                                    .map { if (it == tag) normalized else it }
                                    .distinctBy { it.lowercase() }
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
         * Request code for `WRITE_EXTERNAL_STORAGE` on API 26–28. Distinctive
         * value (hex `0x0C0DE` = "code") so it cannot collide with a request
         * code the host CodeAssist app might use, in case it ever starts
         * overriding `onRequestPermissionsResult`.
         */
        const val REQUEST_STORAGE_PERMISSION = 0x0C0DE
    }
}