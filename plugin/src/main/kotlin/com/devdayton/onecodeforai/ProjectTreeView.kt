package com.devdayton.onecodeforai

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

class ProjectTreeView(
    private val activity: Activity,
    private val projectRoot: File,
    private val onExcludedPathsChanged: (List<String>) -> Unit
) {
    private var currentConfig: FilterConfig = FilterConfig()
    private val expandedPaths = mutableSetOf<String>()
    private var pendingPulsePath: String? = null
    private var isSyncingScroll = false

    // --- Contenido (debajo del sticky) ---
    private val rowsContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private val horizontalScroll = WrapHorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = true
        isVerticalScrollBarEnabled = false
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        addView(rowsContainer, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private val verticalScroll = NestedScrollView(activity).apply {
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) // weight = 1
        addView(horizontalScroll, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // --- Sticky header (fila de la carpeta raíz) ---
    private val stickyScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        setBackgroundColor(CodeAssistTheme.SURFACE_RAISED)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private val dividerView = View(activity).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
    }

    // --- Contenedor externo ---
    private val outerContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        addView(stickyScroll)
        addView(dividerView)
        addView(verticalScroll)
    }

    private val rowMinWidth: Int = run {
        val screenW = activity.resources.displayMetrics.widthPixels
        val dialogW = (screenW * 0.94f).toInt()
        (dialogW - dp(18 * 2 + 6 * 2)).coerceAtLeast(0)
    }

    init {
        // Sincronización bidireccional del scroll horizontal
        stickyScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (isSyncingScroll) return@setOnScrollChangeListener
            isSyncingScroll = true
            if (horizontalScroll.scrollX != scrollX) horizontalScroll.scrollTo(scrollX, 0)
            isSyncingScroll = false
        }
        horizontalScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (isSyncingScroll) return@setOnScrollChangeListener
            isSyncingScroll = true
            if (stickyScroll.scrollX != scrollX) stickyScroll.scrollTo(scrollX, 0)
            isSyncingScroll = false
        }
    }

    fun getView(): View = outerContainer

    fun render(config: FilterConfig) {
        currentConfig = config

        val filter = FileFilter(projectRoot, currentConfig)
        val rootNode = buildTree(filter)

        // 1. Sticky: fila de la raíz (chevron decorativo, checkbox funcional)
        stickyScroll.removeAllViews()
        val rootRow = buildRow(rootNode, isSticky = true)
        stickyScroll.addView(
            rootRow,
            ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        )

        // 2. Contenido: hijos de la raíz
        rowsContainer.removeAllViews()
        if (rootNode.children.isEmpty()) {
            rowsContainer.addView(
                UiFactory.run {
                    activity.textView(
                        "Sin archivos que coincidan con los filtros",
                        11f,
                        CodeAssistTheme.TEXT_MUTED
                    )
                }.apply {
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                }
            )
        } else {
            rootNode.children.forEach { child ->
                rowsContainer.addView(renderNode(child))
            }
        }

        if (pendingPulsePath == "") {
            pendingPulsePath = null
            pulseRow(rootRow)
        }
    }

    fun expandAll() {
        val filter = FileFilter(projectRoot, currentConfig)
        collectExpandable(buildTree(filter)).forEach { expandedPaths.add(it) }
        render(currentConfig)
    }

    fun collapseAll() {
        expandedPaths.clear()
        render(currentConfig)
    }

    private fun collectExpandable(node: TreeNode): List<String> {
        val out = mutableListOf<String>()
        if (node.isDirectory && node.children.isNotEmpty()) {
            out.add(node.relativePath)
            node.children.forEach { out.addAll(collectExpandable(it)) }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Construcción
    // ------------------------------------------------------------------

    private fun buildTree(filter: FileFilter): TreeNode {
        val rootName = projectRoot.name.ifBlank { "root" }
        val rootNode = TreeNode(projectRoot, true, 0, "", rootName).apply {
            expanded = true
        }
        populate(rootNode, filter)
        return rootNode
    }

    private fun populate(node: TreeNode, filter: FileFilter) {
        val files = node.file.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: return

        for (file in files) {
            val rel = if (node.relativePath.isEmpty()) file.name else "${node.relativePath}/${file.name}"
            val selfExcluded = currentConfig.excludedPaths.any { it == rel }
            val ancestorExcluded = node.selfExcluded || node.ancestorExcluded

            if (file.isDirectory) {
                if (!filter.include(file, respectExcludedPaths = false)) continue
                val child = TreeNode(file, true, node.depth + 1, rel, file.name).apply {
                    this.selfExcluded = selfExcluded
                    this.ancestorExcluded = ancestorExcluded
                    this.expanded = expandedPaths.contains(rel)
                }
                populate(child, filter)
                node.children.add(child)
            } else if (filter.include(file, respectExcludedPaths = false)) {
                val child = TreeNode(file, false, node.depth + 1, rel, file.name).apply {
                    this.selfExcluded = selfExcluded
                    this.ancestorExcluded = ancestorExcluded
                }
                node.children.add(child)
            }
        }
    }

    // ------------------------------------------------------------------
    // Render del contenido
    // ------------------------------------------------------------------

    private fun renderNode(node: TreeNode): View {
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        val row = buildRow(node)
        wrapper.addView(row)
        node.rowView = row

        if (node.isDirectory && node.children.isNotEmpty()) {
            val cw = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                visibility = if (node.expanded) View.VISIBLE else View.GONE
            }
            node.children.forEach { child -> cw.addView(renderNode(child)) }
            node.childrenWrapper = cw
            wrapper.addView(cw)
        }
        node.wrapper = wrapper

        if (pendingPulsePath == node.relativePath) {
            pendingPulsePath = null
            pulseRow(row)
        }

        return wrapper
    }

    private fun buildRow(node: TreeNode, isSticky: Boolean = false): View {
        val isRoot = node.depth == 0
        val indent = if (isSticky) 0 else dp(14 * node.depth)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(indent, dp(2), 0, dp(2))
            minimumHeight = dp(32)
            minimumWidth = if (isSticky) 0 else rowMinWidth
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }

        // Chevron
        val hasChildren = node.isDirectory && node.children.isNotEmpty()
        if (hasChildren) {
            val res = if (isSticky || node.expanded) R.drawable.ic_tech_chevron_down
                      else R.drawable.ic_tech_chevron_right
            val arrow = ImageView(activity).apply {
                val drawable = UiFactory.run { activity.loadPluginDrawable(res) }
                if (drawable != null) setImageDrawable(drawable)
                else runCatching { setImageResource(res) }
                setColorFilter(CodeAssistTheme.TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                if (!isSticky) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { toggleExpanded(node) }
                }
            }
            node.arrow = arrow
            row.addView(arrow)
        } else {
            row.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), 1)
            })
        }

        // Checkbox
        val showCheckbox = isSticky || !isRoot
        if (showCheckbox) {
            val cb = CheckBox(activity).apply {
                if (isSticky) {
                    isChecked = currentConfig.excludedPaths.isEmpty()
                    isEnabled = true
                    buttonTintList = ColorStateList.valueOf(CodeAssistTheme.ACCENT)
                    setOnCheckedChangeListener { _, _ -> toggleAllMarked() }
                } else {
                    isChecked = !node.selfExcluded && !node.ancestorExcluded
                    isEnabled = !node.ancestorExcluded
                    buttonTintList = ColorStateList.valueOf(
                        if (node.ancestorExcluded) CodeAssistTheme.TEXT_MUTED
                        else CodeAssistTheme.ACCENT
                    )
                    setOnCheckedChangeListener { _, _ -> toggleNode(node) }
                }
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(32)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(6)
                }
                scaleX = 0.85f
                scaleY = 0.85f
            }
            node.checkbox = cb
            row.addView(cb)
        } else {
            row.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), 1)
            })
        }

        // Icono
        val iconRes = if (node.isDirectory) R.drawable.ic_tech_folder else R.drawable.ic_tech_file
        val tint = when {
            !isSticky && (node.ancestorExcluded || node.selfExcluded) -> CodeAssistTheme.TEXT_MUTED
            else -> CodeAssistTheme.ACCENT
        }
        val icon = ImageView(activity).apply {
            val drawable = UiFactory.run { activity.loadPluginDrawable(iconRes) }
            if (drawable != null) setImageDrawable(drawable)
            else runCatching { setImageResource(iconRes) }
            setColorFilter(tint)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginEnd = dp(6)
            }
        }
        node.iconView = icon
        row.addView(icon)

        // Nombre
        val nameColor = if (!isSticky && (node.ancestorExcluded || node.selfExcluded))
            CodeAssistTheme.TEXT_MUTED else CodeAssistTheme.TEXT_PRIMARY
        val nameText = if (isRoot) "${node.displayName}/" else node.displayName
        val nameView = UiFactory.run {
            activity.textView(nameText, 12f, nameColor, isBold = isRoot)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            setPadding(0, 0, dp(6), 0)
        }
        row.addView(nameView)

        // Tamaño
        if (!node.isDirectory) {
            row.addView(
                UiFactory.run {
                    activity.textView(formatSize(node.file.length()), 10f, CodeAssistTheme.TEXT_MUTED)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    setPadding(0, 0, dp(6), 0)
                }
            )
        }

        if (hasChildren && !isSticky) {
            row.isClickable = true
            row.setOnClickListener { toggleExpanded(node) }
        }

        return row
    }

    // ------------------------------------------------------------------
    // Acciones
    // ------------------------------------------------------------------

    private fun toggleExpanded(node: TreeNode) {
        val cw = node.childrenWrapper ?: return
        if (node.expanded) {
            node.expanded = false
            expandedPaths.remove(node.relativePath)
            cw.animate().cancel()
            cw.animate().alpha(0f).translationY(-dp(6).toFloat())
                .setDuration(150).setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    cw.visibility = View.GONE
                    cw.alpha = 1f
                    cw.translationY = 0f
                }.start()
            animateChevron(node.arrow, false)
        } else {
            node.expanded = true
            expandedPaths.add(node.relativePath)
            cw.visibility = View.VISIBLE
            cw.alpha = 0f
            cw.translationY = -dp(6).toFloat()
            cw.animate().cancel()
            cw.animate().alpha(1f).translationY(0f)
                .setDuration(200).setInterpolator(DecelerateInterpolator())
                .start()
            animateChevron(node.arrow, true)
        }
    }

    private fun animateChevron(arrow: ImageView?, expanded: Boolean) {
        arrow ?: return
        arrow.animate().cancel()
        arrow.animate().rotation(if (expanded) 90f else 0f)
            .setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                val res = if (expanded) R.drawable.ic_tech_chevron_down
                          else R.drawable.ic_tech_chevron_right
                val drawable = UiFactory.run { activity.loadPluginDrawable(res) }
                if (drawable != null) arrow.setImageDrawable(drawable)
                else runCatching { arrow.setImageResource(res) }
                arrow.rotation = 0f
            }.start()
    }

    private fun toggleNode(node: TreeNode) {
        val excluded = currentConfig.excludedPaths.toMutableList()
        if (node.selfExcluded) {
            excluded.removeAll { it == node.relativePath }
        } else if (node.relativePath !in excluded) {
            excluded.add(node.relativePath)
        }
        val newConfig = currentConfig.copy(excludedPaths = excluded)
        currentConfig = newConfig
        onExcludedPathsChanged(excluded)
        pendingPulsePath = node.relativePath
        render(newConfig)
    }

    /** Checkbox de la fila sticky: select-all / deselect-all. */
    private fun toggleAllMarked() {
        val currentlyAllMarked = currentConfig.excludedPaths.isEmpty()
        val newExcluded: List<String> = if (currentlyAllMarked) {
            val filter = FileFilter(projectRoot, currentConfig)
            projectRoot.listFiles()
                ?.filter { file ->
                    if (file.isDirectory) filter.include(file, respectExcludedPaths = false)
                    else filter.include(file, respectExcludedPaths = false)
                }
                ?.map { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }
        currentConfig = currentConfig.copy(excludedPaths = newExcluded)
        onExcludedPathsChanged(newExcluded)
        pendingPulsePath = ""
        render(currentConfig)
    }

    private fun pulseRow(row: View) {
        row.alpha = 0.3f
        row.animate().cancel()
        row.animate().alpha(1f).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun dp(value: Int): Int = CodeAssistTheme.dp(activity, value)

    // ------------------------------------------------------------------
    // ScrollViews custom
    // ------------------------------------------------------------------

    private class NestedScrollView(context: Context) : ScrollView(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            return super.onInterceptTouchEvent(ev)
        }
    }

    private class WrapHorizontalScrollView(context: Context) : HorizontalScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val child = getChildAt(0)
            if (child == null) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            child.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val childWidth = child.measuredWidth + paddingLeft + paddingRight
            val childHeight = child.measuredHeight + paddingTop + paddingBottom
            val finalWidth = when (widthMode) {
                MeasureSpec.EXACTLY -> widthSize
                MeasureSpec.AT_MOST -> minOf(childWidth, widthSize)
                else -> childWidth
            }
            setMeasuredDimension(finalWidth, childHeight)
        }
    }

    // ------------------------------------------------------------------

    private class TreeNode(
        val file: File,
        val isDirectory: Boolean,
        val depth: Int,
        val relativePath: String,
        val displayName: String
    ) {
        val children = mutableListOf<TreeNode>()
        var expanded = false
        var selfExcluded = false
        var ancestorExcluded = false
        var wrapper: View? = null
        var rowView: View? = null
        var childrenWrapper: LinearLayout? = null
        var arrow: ImageView? = null
        var checkbox: CheckBox? = null
        var iconView: ImageView? = null
    }
}