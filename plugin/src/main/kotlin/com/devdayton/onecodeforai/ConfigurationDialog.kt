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
import android.widget.*
import com.devdayton.onecodeforai.UiFactory.checkBox
import com.devdayton.onecodeforai.UiFactory.editText
import com.devdayton.onecodeforai.UiFactory.iconButton
import com.devdayton.onecodeforai.UiFactory.sectionTitle
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

    // UI refs
    private lateinit var spinnerPresets: Spinner
    private lateinit var presetAdapter: ArrayAdapter<String>
    private lateinit var btnSavePreset: ImageButton
    private lateinit var cbBinaries: CheckBox
    private lateinit var cbGitignore: CheckBox
    private lateinit var etMaxSize: EditText
    private lateinit var flowInclusions: FlowLayout
    private lateinit var flowExclusions: FlowLayout
    private lateinit var flowGitignore: FlowLayout
    private lateinit var scrollGitignore: ScrollView
    private lateinit var layoutGitBox: LinearLayout
    private lateinit var cbUseGit: CheckBox
    private lateinit var spinnerBranches: Spinner
    private lateinit var etCommitHash: EditText

    fun show() {
        gitignoreTags = FileFilter(projectRoot).loadGitignoreRules()
        val lastState = presetManager.loadLastState()
        currentConfig = lastState?.first ?: FilterConfig()
        isCustomMode = (lastState?.second ?: "Personalizado") == "Personalizado"

        val dialog = Dialog(activity).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val scroll = ScrollView(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = CodeAssistTheme.roundedBg(CodeAssistTheme.BG, 24f, CodeAssistTheme.OUTLINE)
        }
        scroll.addView(root)

        // --- TÍTULO (con padding inferior para separar del input) ---
        root.addView(activity.textView("One Code For AI", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, CodeAssistTheme.dp(activity, 32))
        })

        // --- PRESETS ---
        val presetRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        presetAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, getPresetList())
        spinnerPresets = Spinner(activity).apply {
            adapter = presetAdapter
            background = CodeAssistTheme.roundedBg(CodeAssistTheme.SURFACE, 8f, CodeAssistTheme.OUTLINE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSavePreset = activity.iconButton(android.R.drawable.ic_menu_save) { showSavePresetDialog() }.apply {
            visibility = if (isCustomMode) View.VISIBLE else View.GONE
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 12 }
        }
        presetRow.addView(spinnerPresets)
        presetRow.addView(btnSavePreset)
        root.addView(presetRow)

        // --- FILTROS BASE ---
        root.addView(activity.sectionTitle("Filtros Base"))
        cbBinaries = activity.checkBox("Excluir binarios").apply {
            isChecked = currentConfig.excludeBinaries
            setOnCheckedChangeListener { _, _ -> if (!isUpdatingUI) markAsCustom() }
        }
        cbGitignore = activity.checkBox("Respetar .gitignore").apply {
            isChecked = currentConfig.respectGitignore
            setOnCheckedChangeListener { _, _ -> if (!isUpdatingUI) { markAsCustom(); updateTagsUI() } }
        }
        root.addView(cbBinaries)
        root.addView(cbGitignore)

        // --- TAMAÑO ---
        val sizeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 12, 0, 12) }
        sizeRow.addView(activity.textView("Tamaño máx (KB):", 13f, CodeAssistTheme.TEXT_SECONDARY))
        etMaxSize = activity.editText(currentConfig.maxSizeKb.toString(), InputType.TYPE_CLASS_NUMBER).apply {
            setOnFocusChangeListener { _, _ -> if (!isUpdatingUI) markAsCustom() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 16 }
        }
        sizeRow.addView(etMaxSize)
        root.addView(sizeRow)

        // --- TAGS: INCLUSIONES ---
        root.addView(activity.sectionTitle("Inclusiones"))
        flowInclusions = FlowLayout(activity).apply { minimumHeight = 80 }
        root.addView(flowInclusions)

        // --- TAGS: EXCLUSIONES ---
        root.addView(activity.sectionTitle("Exclusiones"))
        flowExclusions = FlowLayout(activity).apply { minimumHeight = 80 }
        root.addView(flowExclusions)

        // --- TAGS: GITIGNORE ---
        scrollGitignore = ScrollView(activity).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(activity.sectionTitle(".gitignore (Solo lectura)"))
                flowGitignore = FlowLayout(activity).apply { minimumHeight = 60 }
                addView(flowGitignore)
            })
        }
        root.addView(scrollGitignore)

        // --- GIT ---
        if (gitEnabledGlobally) {
            layoutGitBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                background = CodeAssistTheme.roundedBg(CodeAssistTheme.SURFACE, 12f, CodeAssistTheme.OUTLINE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 }
            }
            root.addView(layoutGitBox)
            setupGitUI()
        }

        // --- EXPORTAR ---
        val btnExport = Button(activity).apply {
            text = "Exportar a Descargas"
            setTextColor(Color.WHITE)
            background = CodeAssistTheme.roundedBg(CodeAssistTheme.ACCENT, 12f)
            setOnClickListener {
                readUIValues()
                presetManager.saveLastState(currentConfig, spinnerPresets.selectedItem?.toString() ?: "Personalizado")
                dialog.dismiss()
                onExport(currentConfig)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 32 }
        }
        root.addView(btnExport)

        dialog.setContentView(scroll)
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.92).toInt(), (activity.resources.displayMetrics.heightPixels * 0.80).toInt())
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // 2. Guardar el estado real al cerrar (incluso si se cancela)
        dialog.setOnDismissListener {
            readUIValues()
            presetManager.saveLastState(currentConfig, spinnerPresets.selectedItem?.toString() ?: "Personalizado")
        }

        // Restaurar estado inicial
        isUpdatingUI = true
        val initialIdx = presetAdapter.getPosition(lastState?.second ?: "Personalizado").takeIf { it >= 0 } ?: 0
        spinnerPresets.setSelection(initialIdx, false)
        isUpdatingUI = false

        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUI) return
                val selected = parent?.getItemAtPosition(position).toString()
                isCustomMode = (selected == "Personalizado")
                btnSavePreset.visibility = if (isCustomMode) View.VISIBLE else View.GONE
                if (!isCustomMode) presetManager.loadPreset(selected)?.let { applyConfigToUI(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateTagsUI()
        dialog.show()
    }

    // --- LÓGICA INTERNA ---

    private fun applyConfigToUI(config: FilterConfig) {
        isUpdatingUI = true
        currentConfig = config
        cbBinaries.isChecked = config.excludeBinaries
        cbGitignore.isChecked = config.respectGitignore
        etMaxSize.setText(config.maxSizeKb.toString())
        if (::cbUseGit.isInitialized) {
            cbUseGit.isChecked = config.useGit
            etCommitHash.setText(config.gitRef ?: "")
        }
        updateTagsUI()
        isUpdatingUI = false
    }

    private fun markAsCustom() {
        if (!isCustomMode) {
            isCustomMode = true
            btnSavePreset.visibility = View.VISIBLE
            val idx = presetAdapter.getPosition("Personalizado")
            if (idx >= 0 && spinnerPresets.selectedItemPosition != idx) {
                isUpdatingUI = true
                spinnerPresets.setSelection(idx)
                isUpdatingUI = false
            }
        }
    }

    private fun readUIValues() {
        val size = etMaxSize.text.toString().toIntOrNull() ?: 512
        var useGit = false
        var gitRef: String? = null
        if (gitEnabledGlobally && ::cbUseGit.isInitialized) {
            useGit = cbUseGit.isChecked
            if (useGit) gitRef = etCommitHash.text.toString().trim().takeIf { it.isNotEmpty() }
                ?: spinnerBranches.selectedItem?.toString()?.takeIf { it != "Actual (Directorio)" }
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

        currentConfig.inclusions.forEach { addTagToFlow(flowInclusions, it, true) }
        currentConfig.exclusions.forEach { addTagToFlow(flowExclusions, it, false) }

        scrollGitignore.visibility = if (currentConfig.respectGitignore) View.VISIBLE else View.GONE
        if (currentConfig.respectGitignore) {
            gitignoreTags.forEach { tag ->
                val isOverridden = currentConfig.inclusions.contains(tag) || currentConfig.exclusions.contains(tag)
                // En gitignore, onClick es null (no editable)
                flowGitignore.addView(createTagView(tag, if (isOverridden) CodeAssistTheme.TAG_CONFLICT else CodeAssistTheme.TAG_EXCLUDE, null))
            }
        }
    }

    private fun addTagToFlow(flow: FlowLayout, tag: String, isInclusion: Boolean) {
        val isConflict = gitignoreTags.contains(tag) || (if (isInclusion) currentConfig.exclusions.contains(tag) else currentConfig.inclusions.contains(tag))
        val bgColor = if (isConflict) CodeAssistTheme.TAG_CONFLICT else if (isInclusion) CodeAssistTheme.TAG_INCLUDE else CodeAssistTheme.TAG_EXCLUDE
        // Siempre clickeable, sin importar el modo
        flow.addView(createTagView(tag, bgColor) { showTagOptions(tag, isInclusion) })
    }

    private fun createTagView(text: String, color: Int, onClick: (() -> Unit)?): TextView {
        return activity.textView(text, 12f, Color.WHITE).apply {
            setPadding(20, 10, 20, 10)
            background = CodeAssistTheme.roundedBg(color, 16f)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
    }

    private fun showTagOptions(tag: String, isInclusion: Boolean) {
        AlertDialog.Builder(activity).setTitle("Tag: $tag").setItems(arrayOf("Editar", "Eliminar")) { _, which ->
            if (which == 0) {
                showAddTagDialog("Editar Tag", tag) { newTag ->
                    if (newTag != tag) {
                        currentConfig = if (isInclusion) currentConfig.copy(inclusions = currentConfig.inclusions.map { if (it == tag) newTag else it })
                        else currentConfig.copy(exclusions = currentConfig.exclusions.map { if (it == tag) newTag else it })
                        markAsCustom()
                        updateTagsUI()
                    }
                }
            } else {
                currentConfig = if (isInclusion) currentConfig.copy(inclusions = currentConfig.inclusions - tag)
                else currentConfig.copy(exclusions = currentConfig.exclusions - tag)
                markAsCustom()
                updateTagsUI()
            }
        }.show()
    }

    private fun showAddTagDialog(title: String, initial: String = "", onAccept: (String) -> Unit) {
        val input = activity.editText(initial).apply { hint = "Ej: kt, json" }
        AlertDialog.Builder(activity).setTitle(title).setView(input).setPositiveButton("Aceptar") { _, _ ->
            input.text.toString().trim().lowercase().takeIf { it.isNotEmpty() }?.let(onAccept)
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun setupGitUI() {
        val branch = GitUtils.getCurrentBranch(projectRoot)
        val commit = GitUtils.getCurrentCommit(projectRoot)
        layoutGitBox.addView(activity.textView("Git: $branch (${commit ?: "sin commit"})", 12f, CodeAssistTheme.TEXT_SECONDARY))

        cbUseGit = activity.checkBox("Usar versión de Git").apply {
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
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Actual (Directorio)") + branches)
            visibility = if (currentConfig.useGit) View.VISIBLE else View.GONE
            background = CodeAssistTheme.roundedBg(CodeAssistTheme.SURFACE, 8f, CodeAssistTheme.OUTLINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 }
        }
        layoutGitBox.addView(spinnerBranches)

        etCommitHash = activity.editText(currentConfig.gitRef ?: "").apply {
            hint = "Hash de commit (opcional)"
            visibility = if (currentConfig.useGit) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 }
        }
        layoutGitBox.addView(etCommitHash)
    }

    private fun getPresetList(): MutableList<String> {
        return mutableListOf("Personalizado").apply {
            addAll(presetManager.getDefaultPresets().keys)
            addAll(presetManager.getUserPresetNames())
        }
    }

    private fun showSavePresetDialog() {
        val input = activity.editText("").apply { hint = "Nombre del preset" }
        AlertDialog.Builder(activity).setTitle("Guardar Preset").setView(input).setPositiveButton("Aceptar") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty() && name != "Personalizado") {
                readUIValues()
                presetManager.savePreset(name, currentConfig)
                presetManager.saveLastState(currentConfig, name)
                presetAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, getPresetList())
                spinnerPresets.adapter = presetAdapter
                isUpdatingUI = true
                spinnerPresets.setSelection(presetAdapter.getPosition(name))
                isUpdatingUI = false
                isCustomMode = false
                btnSavePreset.visibility = View.GONE
            }
        }.setNegativeButton("Cancelar", null).show()
    }
}