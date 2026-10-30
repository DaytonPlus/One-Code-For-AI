package com.devdayton.onecodeforai

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.devdayton.onecodeforai.UiFactory.actionText
import com.devdayton.onecodeforai.UiFactory.checkBox
import com.devdayton.onecodeforai.UiFactory.editText
import com.devdayton.onecodeforai.UiFactory.iconButton
import com.devdayton.onecodeforai.UiFactory.sectionTitle
import com.devdayton.onecodeforai.UiFactory.spinnerAdapter
import com.devdayton.onecodeforai.UiFactory.textView

class ConfigurationDialog(
    private val activity: Activity,
    private val projectRoot: java.io.File,
    private val gitEnabledGlobally: Boolean,
    private val onExport: (FilterConfig) -> Unit
) {
    private val presetManager = PresetManager(activity)
    private var currentConfig = FilterConfig()
    private var isCustomMode = false
    private var isUpdatingUI = false
    private var gitignoreTags: List<String> = emptyList()

    private lateinit var spinnerPresets: Spinner
    private lateinit var presetAdapter: ArrayAdapter<String>
    private lateinit var btnSavePreset: ImageButton
    private lateinit var cbBinaries: CheckBox
    private lateinit var cbGitignore: CheckBox
    private lateinit var etMaxSize: EditText
    private lateinit var flowInclusions: FlowLayout
    private lateinit var flowExclusions: FlowLayout
    private lateinit var flowGitignore: FlowLayout
    private lateinit var gitignoreSection: LinearLayout
    private lateinit var layoutGitBox: LinearLayout
    private lateinit var cbUseGit: CheckBox
    private lateinit var spinnerBranches: Spinner
    private lateinit var etCommitHash: EditText

    fun show() {
        gitignoreTags = FileFilter(projectRoot).loadGitignoreRules()
        val lastState = presetManager.loadLastState()
        currentConfig = lastState?.first ?: FilterConfig()
        isCustomMode = (lastState?.second ?: "Personalizado") == "Personalizado"

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

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(activity.textView("One Code For AI", 17f, CodeAssistTheme.TEXT_PRIMARY, true))
        headerText.addView(
            activity.textView(
                "GENERADOR DE CONTEXTO  •  ${projectRoot.name}",
                10f,
                CodeAssistTheme.TEXT_SECONDARY
            ).apply {
                setPadding(0, CodeAssistTheme.dp(activity, 5), 0, 0)
            }
        )
        header.addView(headerText)
        header.addView(
            activity.iconButton(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cerrar"
            ) { dialog.dismiss() }
        )
        root.addView(header)
        root.addView(divider())

        root.addView(activity.sectionTitle("Perfil de exportación"))
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
            // 🔥 Corrección: Se usa el método set en lugar de asignación directa
            setPopupBackgroundDrawable(ColorDrawable(CodeAssistTheme.SURFACE_RAISED))
            layoutParams = LinearLayout.LayoutParams(0, CodeAssistTheme.dp(activity, 42), 1f)
        }
        btnSavePreset = activity.iconButton(
            android.R.drawable.ic_menu_save,
            "Guardar perfil"
        ) { showSavePresetDialog() }.apply {
            visibility = if (isCustomMode) View.VISIBLE else View.GONE
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = CodeAssistTheme.dp(activity, 8)
            }
        }
        presetRow.addView(spinnerPresets)
        presetRow.addView(btnSavePreset)
        root.addView(presetRow)

        root.addView(activity.sectionTitle("Filtros base"))
        val basePanel = panel()
        cbBinaries = activity.checkBox("Excluir archivos binarios").apply {
            isChecked = currentConfig.excludeBinaries
            setOnCheckedChangeListener { _, _ ->
                if (!isUpdatingUI) markAsCustom()
            }
        }
        cbGitignore = activity.checkBox("Respetar reglas de .gitignore").apply {
            isChecked = currentConfig.respectGitignore
            setOnCheckedChangeListener { _, _ ->
                if (!isUpdatingUI) {
                    markAsCustom()
                    updateTagsUI()
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
            activity.textView("Tamaño máximo", 12f, CodeAssistTheme.TEXT_SECONDARY).apply {
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
            activity.textView(" KB", 11f, CodeAssistTheme.TEXT_SECONDARY).apply {
                setPadding(CodeAssistTheme.dp(activity, 7), 0, 0, 0)
            }
        )
        basePanel.addView(sizeRow)
        root.addView(basePanel)

        root.addView(tagSectionHeader("Inclusiones", "＋ Añadir") {
            showAddTagDialog("Añadir inclusión") { addTag(it, true) }
        })
        flowInclusions = createFlow()
        root.addView(flowInclusions)

        root.addView(tagSectionHeader("Exclusiones", "＋ Añadir") {
            showAddTagDialog("Añadir exclusión") { addTag(it, false) }
        })
        flowExclusions = createFlow()
        root.addView(flowExclusions)

        gitignoreSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        gitignoreSection.addView(activity.sectionTitle("Reglas detectadas en .gitignore"))
        flowGitignore = createFlow()
        gitignoreSection.addView(flowGitignore)
        root.addView(gitignoreSection)

        if (gitEnabledGlobally) {
            root.addView(activity.sectionTitle("Control de versiones"))
            layoutGitBox = panel()
            root.addView(layoutGitBox)
            setupGitUI()
        }

        val exportButton = android.widget.Button(activity).apply {
            text = "Exportar a Descargas"
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
                    spinnerPresets.selectedItem?.toString() ?: "Personalizado"
                )
                dialog.dismiss()
                onExport(currentConfig)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                CodeAssistTheme.dp(activity, 46)
            ).apply {
                topMargin = CodeAssistTheme.dp(activity, 22)
            }
        }
        root.addView(exportButton)
        root.addView(
            activity.textView(
                "Se generará un único archivo .txt con los archivos permitidos por los filtros.",
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

        dialog.setContentView(scroll)
        dialog.setOnDismissListener {
            readUIValues()
            presetManager.saveLastState(
                currentConfig,
                spinnerPresets.selectedItem?.toString() ?: "Personalizado"
            )
        }

        val initialIndex = presetAdapter.getPosition(lastState?.second ?: "Personalizado")
            .takeIf { it >= 0 } ?: 0
        spinnerPresets.setSelection(initialIndex, false)
        updateTagsUI()

        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (isUpdatingUI) return
                val selected = parent?.getItemAtPosition(position)?.toString() ?: return
                isCustomMode = selected == "Personalizado"
                btnSavePreset.visibility = if (isCustomMode) View.VISIBLE else View.GONE
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
    }

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

        if (::cbUseGit.isInitialized) {
            cbUseGit.isChecked = config.useGit
            etCommitHash.setText(config.gitRef ?: "")
            spinnerBranches.visibility = if (config.useGit) View.VISIBLE else View.GONE
            etCommitHash.visibility = if (config.useGit) View.VISIBLE else View.GONE
            val branchIndex = config.gitRef?.let { ref ->
                (0 until spinnerBranches.count).firstOrNull {
                    spinnerBranches.getItemAtPosition(it)?.toString() == ref
                }
            }
            if (branchIndex != null) spinnerBranches.setSelection(branchIndex)
        }

        updateTagsUI()
        isUpdatingUI = false
    }

    private fun markAsCustom() {
        if (!isCustomMode) {
            isCustomMode = true
            btnSavePreset.visibility = View.VISIBLE
            val customIndex = presetAdapter.getPosition("Personalizado")
            if (customIndex >= 0 && spinnerPresets.selectedItemPosition != customIndex) {
                isUpdatingUI = true
                spinnerPresets.setSelection(customIndex)
                isUpdatingUI = false
            }
        }
    }

    private fun readUIValues() {
        val size = etMaxSize.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 512
        var useGit = false
        var gitRef: String? = null

        if (gitEnabledGlobally && ::cbUseGit.isInitialized) {
            useGit = cbUseGit.isChecked
            if (useGit) {
                gitRef = etCommitHash.text.toString().trim().takeIf { it.isNotEmpty() }
                    ?: spinnerBranches.selectedItem?.toString()?.takeIf { it != "Actual (Directorio)" }
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

    private fun updateTagsUI() {
        if (!::flowInclusions.isInitialized) return

        flowInclusions.removeAllViews()
        flowExclusions.removeAllViews()
        flowGitignore.removeAllViews()

        if (currentConfig.inclusions.isEmpty()) {
            addEmptyLabel(flowInclusions, "Sin extensiones adicionales")
        } else {
            currentConfig.inclusions.forEach { addTagToFlow(flowInclusions, it, true) }
        }

        if (currentConfig.exclusions.isEmpty()) {
            addEmptyLabel(flowExclusions, "Sin exclusiones personalizadas")
        } else {
            currentConfig.exclusions.forEach { addTagToFlow(flowExclusions, it, false) }
        }

        gitignoreSection.visibility = if (currentConfig.respectGitignore) View.VISIBLE else View.GONE
        if (currentConfig.respectGitignore) {
            if (gitignoreTags.isEmpty()) {
                addEmptyLabel(flowGitignore, "No se encontraron reglas en .gitignore")
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
        flow.addView(createTagView(tag, backgroundColor, foregroundColor) {
            showTagOptions(tag, isInclusion)
        })
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
        updateTagsUI()
    }

    private fun showTagOptions(tag: String, isInclusion: Boolean) {
        AlertDialog.Builder(activity)
            .setTitle(tag)
            .setItems(arrayOf("Editar extensión", "Eliminar regla")) { _, which ->
                if (which == 0) {
                    showAddTagDialog("Editar extensión", tag) { updated ->
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
                            updateTagsUI()
                        }
                    }
                } else {
                    currentConfig = if (isInclusion) {
                        currentConfig.copy(inclusions = currentConfig.inclusions - tag)
                    } else {
                        currentConfig.copy(exclusions = currentConfig.exclusions - tag)
                    }
                    markAsCustom()
                    updateTagsUI()
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
            hint = "Ej.: kt, json, src/config"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Aceptar") { _, _ ->
                input.text.toString().trim().lowercase().takeIf { it.isNotEmpty() }?.let(onAccept)
            }
            .setNegativeButton("Cancelar", null)
            .show()
            .styleDialog()
    }

    private fun setupGitUI() {
        val branch = GitUtils.getCurrentBranch(projectRoot) ?: "sin rama"
        val commit = GitUtils.getCurrentCommit(projectRoot) ?: "sin commit"

        layoutGitBox.addView(
            activity.textView(
                "Rama: $branch  ·  Commit: $commit",
                11f,
                CodeAssistTheme.TEXT_SECONDARY
            ).apply {
                setPadding(0, 0, 0, CodeAssistTheme.dp(activity, 5))
            }
        )

        cbUseGit = activity.checkBox("Leer desde una referencia Git").apply {
            isChecked = currentConfig.useGit
            setOnCheckedChangeListener { _, checked ->
                spinnerBranches.visibility = if (checked) View.VISIBLE else View.GONE
                etCommitHash.visibility = if (checked) View.VISIBLE else View.GONE
                if (!isUpdatingUI) markAsCustom()
            }
        }
        layoutGitBox.addView(cbUseGit)

        val branches = GitUtils.getBranches(projectRoot)
        spinnerBranches = Spinner(activity).apply {
            adapter = activity.spinnerAdapter(listOf("Actual (Directorio)") + branches)
            visibility = if (currentConfig.useGit) View.VISIBLE else View.GONE
            background = CodeAssistTheme.roundedBg(
                activity,
                CodeAssistTheme.SURFACE_RAISED,
                8f,
                CodeAssistTheme.OUTLINE
            )
            setPopupBackgroundDrawable(ColorDrawable(CodeAssistTheme.SURFACE_RAISED)) // 🔥 Corrección
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                CodeAssistTheme.dp(activity, 42)
            ).apply {
                topMargin = CodeAssistTheme.dp(activity, 8)
            }
        }
        layoutGitBox.addView(spinnerBranches)

        etCommitHash = activity.editText(currentConfig.gitRef ?: "").apply {
            hint = "Hash de commit (opcional)"
            visibility = if (currentConfig.useGit) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                CodeAssistTheme.dp(activity, 42)
            ).apply {
                topMargin = CodeAssistTheme.dp(activity, 8)
            }
        }
        layoutGitBox.addView(etCommitHash)
    }

    private fun getPresetList(): MutableList<String> = mutableListOf("Personalizado").apply {
        addAll(presetManager.getDefaultPresets().keys)
        addAll(presetManager.getUserPresetNames())
    }

    private fun showSavePresetDialog() {
        val input = activity.editText("").apply { hint = "Nombre del perfil" }
        AlertDialog.Builder(activity)
            .setTitle("Guardar perfil")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != "Personalizado") {
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
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
            .styleDialog()
    }

    // 🔥 Reconstruido (el tuyo tenía un error de sintaxis por copiar/pegar)
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