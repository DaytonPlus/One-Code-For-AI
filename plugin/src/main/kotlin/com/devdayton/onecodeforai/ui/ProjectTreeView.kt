package com.devdayton.onecodeforai.ui

import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.PluginSettings
import com.devdayton.onecodeforai.core.FileFilter
import com.devdayton.onecodeforai.core.ExtensionStats
import com.devdayton.onecodeforai.core.FilterConfig
import com.devdayton.onecodeforai.core.PreviewStats
import com.devdayton.onecodeforai.ui.UiFactory
import com.devdayton.onecodeforai.ui.CodeAssistTheme

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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

class ProjectTreeView(
    private val activity: Activity,
    private val projectRoot: File,
    private val onExcludedPathsChanged: (List<String>) -> Unit,
    private val onStatsChanged: (PreviewStats) -> Unit
) {
    private companion object {
        const val PROGRESS_FILE_INTERVAL = 200
        const val PROGRESS_INTERVAL_MS = 120L
    }

    private var currentConfig: FilterConfig = FilterConfig()
    private var rootNode: TreeNode? = null
    private val expandedPaths = mutableSetOf<String>()
    private var pendingPulsePath: String? = null
    private var isSyncingScroll = false

    // [PERF] Permite actualizar los checkboxes sin re-disparar sus listeners.
    private var isRefreshingRows = false

    // [PERF] Referencia al checkbox del encabezado sticky (root).
    // Permite actualizarlo in-place en lugar de reconstruirlo.
    private var stickyCheckbox: CheckBox? = null


    private val animationsEnabled: Boolean
        get() = PluginSettings.areAnimationsEnabled(activity)
    

    private val scanToken = AtomicLong(0L)

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
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        addView(horizontalScroll, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

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

    // ------------------------------------------------------------------
    // Escaneo asíncrono
    // ------------------------------------------------------------------

    fun scanAsync(
        config: FilterConfig,
        onProgress: (scannedFiles: Int) -> Unit,
        onComplete: (TreeNode) -> Unit,
        onError: (Exception) -> Unit = {}
    ) {
        val normalizedConfig = config.normalized()
        currentConfig = normalizedConfig
        val myToken = scanToken.incrementAndGet()
        Thread {
            try {
                val filter = FileFilter(projectRoot, normalizedConfig)
                val rootName = projectRoot.name.ifBlank { "root" }
                val root = TreeNode(projectRoot, true, 0, "", rootName).apply { expanded = true }

                var scanned = 0
                var lastProgressAt = 0L
                val pendingDirectories = ArrayDeque<TreeNode>()
                pendingDirectories.addLast(root)

                // Iterative depth-first traversal avoids stack overflows on deeply nested trees.
                while (pendingDirectories.isNotEmpty()) {
                    if (scanToken.get() != myToken) return@Thread
                    val node = pendingDirectories.removeLast()
                    val files = node.file.listFiles()
                        ?.sortedWith(
                            compareBy<File> { !it.isDirectory }
                                .thenBy { it.name.lowercase() }
                        )
                        ?: continue
                    val childDirectories = mutableListOf<TreeNode>()

                    for (file in files) {
                        if (scanToken.get() != myToken) return@Thread
                        val relativePath = if (node.relativePath.isEmpty()) {
                            file.name
                        } else {
                            "${node.relativePath}/${file.name}"
                        }
                        val selfExcluded = normalizedConfig.excludedPaths.any { it == relativePath }
                        val ancestorExcluded = node.selfExcluded || node.ancestorExcluded

                        if (file.isDirectory) {
                            if (!filter.include(file, respectExcludedPaths = false)) continue
                            val child = TreeNode(
                                file, true, node.depth + 1, relativePath, file.name
                            ).apply {
                                this.selfExcluded = selfExcluded
                                this.ancestorExcluded = ancestorExcluded
                                this.expanded = expandedPaths.contains(relativePath)
                            }
                            node.children.add(child)
                            childDirectories.add(child)
                        } else if (filter.include(file, respectExcludedPaths = false)) {
                            val child = TreeNode(
                                file, false, node.depth + 1, relativePath, file.name
                            ).apply {
                                this.selfExcluded = selfExcluded
                                this.ancestorExcluded = ancestorExcluded
                            }
                            node.children.add(child)

                            scanned++
                            val now = System.currentTimeMillis()
                            if (scanned % PROGRESS_FILE_INTERVAL == 0 ||
                                now - lastProgressAt > PROGRESS_INTERVAL_MS
                            ) {
                                lastProgressAt = now
                                val count = scanned
                                activity.runOnUiThread {
                                    if (scanToken.get() == myToken) onProgress(count)
                                }
                            }
                        }
                    }

                    childDirectories.asReversed().forEach(pendingDirectories::addLast)
                }

                if (scanToken.get() != myToken) return@Thread
                val finalRoot = root
                activity.runOnUiThread {
                    if (scanToken.get() == myToken) {
                        rootNode = finalRoot
                        onComplete(finalRoot)
                    }
                }
            } catch (error: Exception) {
                if (scanToken.get() == myToken) {
                    activity.runOnUiThread {
                        if (scanToken.get() == myToken) onError(error)
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "OneCode-tree-scan"
            start()
        }
    }

    fun cancelScan() {
        scanToken.incrementAndGet()
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    fun renderTree(root: TreeNode) {
        rootNode = root
        renderInternal(fireStats = true)
    }

    private fun renderInternal(fireStats: Boolean) {
        val root = rootNode ?: return

        // Sticky (sin chevron)
        stickyScroll.removeAllViews()
        stickyCheckbox = null
        val rootRow = buildRow(root, isSticky = true)
        stickyScroll.addView(rootRow, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        // Contenido
        rowsContainer.removeAllViews()
        if (root.children.isEmpty()) {
            rowsContainer.addView(
                UiFactory.run {
                    activity.textView(
                        activity.pluginString(R.string.no_tree_matches),
                        11f,
                        CodeAssistTheme.TEXT_MUTED
                    )
                }.apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }
            )
        } else {
            root.children.forEach { child -> renderNode(child, rowsContainer) }
        }

        if (pendingPulsePath == "") {
            pendingPulsePath = null
            pulseRow(rootRow)
        }

        if (fireStats) onStatsChanged(computeStatsFromTree(root))
    }

    private fun renderNode(node: TreeNode, parent: LinearLayout) {
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
            node.childrenWrapper = cw
            wrapper.addView(cw)
            if (node.expanded) populateChildren(node)
        }
        node.wrapper = wrapper
        parent.addView(wrapper)

        if (pendingPulsePath == node.relativePath) {
            pendingPulsePath = null
            pulseRow(row)
        }
    }

    private fun populateChildren(node: TreeNode) {
        val cw = node.childrenWrapper ?: return
        if (cw.childCount > 0) return
        node.children.forEach { child -> renderNode(child, cw) }
    }

    // ------------------------------------------------------------------
    // [PERF] Actualización in-place de filas visibles
    // ------------------------------------------------------------------

    /**
     * Refresca únicamente las filas actualmente visibles en lugar de destruir
     * y reconstruir todo el árbol. Coste O(visibles) en vez de O(todos).
     *
     * Necesario tras `setNodeIncluded` / `toggleAllMarked`, donde antes se
     * llamaba a `renderInternal(fireStats = true)` — recreando cientos o miles
     * de Views para reflejar cambios en un solo nodo y sus descendientes.
     */
    private fun refreshVisibleRows(fireStats: Boolean = true) {
        val root = rootNode ?: return
        stickyCheckbox?.let { cb ->
            isRefreshingRows = true
            cb.isChecked = currentConfig.excludedPaths.isEmpty()
            isRefreshingRows = false
        }
        refreshNodeVisible(root)
        if (fireStats) onStatsChanged(computeStatsFromTree(root))
    }

    private fun refreshNodeVisible(directoryNode: TreeNode) {
        directoryNode.children.forEach { child ->
            refreshNodeRow(child)
            if (child.isDirectory && child.expanded) {
                refreshNodeVisible(child)
            }
        }
    }

    private fun refreshNodeRow(node: TreeNode) {
        val included = !node.selfExcluded && !node.ancestorExcluded
        node.checkbox?.let { cb ->
            isRefreshingRows = true
            cb.isChecked = included
            isRefreshingRows = false
            cb.buttonTintList = ColorStateList.valueOf(
                if (node.ancestorExcluded) CodeAssistTheme.TEXT_MUTED
                else CodeAssistTheme.ACCENT
            )
        }
        val tinted = node.ancestorExcluded || node.selfExcluded
        node.iconView?.setColorFilter(
            if (tinted) CodeAssistTheme.TEXT_MUTED else CodeAssistTheme.ACCENT
        )
        node.nameView?.setTextColor(
            if (tinted) CodeAssistTheme.TEXT_MUTED else CodeAssistTheme.TEXT_PRIMARY
        )
    }

    // ------------------------------------------------------------------
    // Construcción de fila
    // ------------------------------------------------------------------

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

        // Chevron con contenedor de 32dp (área táctil ampliada, ícono de 16dp centrado)
        val hasChildren = node.isDirectory && node.children.isNotEmpty()
        if (hasChildren && !isSticky) {
            val res = if (node.expanded) R.drawable.ic_tech_chevron_down
                      else R.drawable.ic_tech_chevron_right
            val arrowContainer = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleExpanded(node) }
            }
            val arrow = ImageView(activity).apply {
                val drawable = UiFactory.run { activity.loadPluginDrawable(res) }
                if (drawable != null) setImageDrawable(drawable)
                else runCatching { setImageResource(res) }
                setColorFilter(CodeAssistTheme.TEXT_SECONDARY)
                layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            }
            arrowContainer.addView(arrow)
            node.arrow = arrow
            row.addView(arrowContainer)
        } else {
            // Spacer (root o archivos sin hijos): mismo ancho que el chevron
            row.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), 1)
            })
        }

        // Checkbox
        val showCheckbox = isSticky || !isRoot
        if (showCheckbox) {
            val cb = CheckBox(activity).apply {
                val included = if (isSticky) currentConfig.excludedPaths.isEmpty()
                               else !node.selfExcluded && !node.ancestorExcluded
                isChecked = included
                isEnabled = true
                buttonTintList = ColorStateList.valueOf(
                    if (!isSticky && node.ancestorExcluded) CodeAssistTheme.TEXT_MUTED
                    else CodeAssistTheme.ACCENT
                )
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(32)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER
                includeFontPadding = false
                text = ""
                compoundDrawablePadding = 0
                scaleX = 0.78f
                scaleY = 0.78f
                setOnCheckedChangeListener { _, checked ->
                    // [PERF] Ignora los cambios programáticos de refreshNodeRow.
                    if (isRefreshingRows) return@setOnCheckedChangeListener
                    if (isSticky) toggleAllMarked() else setNodeIncluded(node, checked)
                }
            }
            node.checkbox = cb
            if (isSticky) stickyCheckbox = cb
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
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
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
        node.nameView = nameView
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

        // Tocar la fila togglea el marcado
        if (!isSticky) {
            row.isClickable = true
            row.setOnClickListener {
                node.checkbox?.let { it.isChecked = !it.isChecked }
            }
        }

        return row
    }

    // ------------------------------------------------------------------
    // Acciones
    // ------------------------------------------------------------------

    /**
     * Expands or collapses [node], with or without animation depending on
     * [animationsEnabled].
     *
     * The state transitions (`node.expanded`, `expandedPaths`, child
     * population) are identical in both branches; only the view-visibility
     * mechanism differs. In the non-animated path the children wrapper is
     * toggled in a single frame, which is what low-end devices need to avoid
     * dropping frames on large trees.
     */
    private fun toggleExpanded(node: TreeNode) {
        val cw = node.childrenWrapper ?: return
        if (node.expanded) {
            node.expanded = false
            expandedPaths.remove(node.relativePath)
            if (animationsEnabled) {
                cw.animate().cancel()
                cw.animate().alpha(0f).translationY(-dp(6).toFloat())
                    .setDuration(150).setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        cw.visibility = View.GONE
                        cw.alpha = 1f
                        cw.translationY = 0f
                    }.start()
            } else {
                cw.animate().cancel()
                cw.alpha = 1f
                cw.translationY = 0f
                cw.visibility = View.GONE
            }
            animateChevron(node.arrow, false)
        } else {
            node.expanded = true
            expandedPaths.add(node.relativePath)
            populateChildren(node)
            cw.visibility = View.VISIBLE
            if (animationsEnabled) {
                cw.alpha = 0f
                cw.translationY = -dp(6).toFloat()
                cw.animate().cancel()
                cw.animate().alpha(1f).translationY(0f)
                    .setDuration(200).setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                cw.animate().cancel()
                cw.alpha = 1f
                cw.translationY = 0f
            }
            animateChevron(node.arrow, true)
        }
    }

    /**
     * Swaps the chevron drawable and, when animations are enabled, rotates it
     * from `right` (0°) to `down` (90°) as a visual cue for the expand state.
     *
     * The non-animated branch sets the target drawable directly and resets the
     * rotation to 0°, so the final visual state is identical to the end of the
     * animated path. The rotation is always reset because the `ImageView` may
     * have inherited a non-zero rotation from a previous animated run.
     */
    private fun animateChevron(arrow: ImageView?, expanded: Boolean) {
        arrow ?: return
        val res = if (expanded) R.drawable.ic_tech_chevron_down
                  else R.drawable.ic_tech_chevron_right

        if (!animationsEnabled) {
            arrow.animate().cancel()
            val drawable = UiFactory.run { activity.loadPluginDrawable(res) }
            if (drawable != null) arrow.setImageDrawable(drawable)
            else runCatching { arrow.setImageResource(res) }
            arrow.rotation = 0f
            return
        }

        arrow.animate().cancel()
        arrow.animate().rotation(if (expanded) 90f else 0f)
            .setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                val drawable = UiFactory.run { activity.loadPluginDrawable(res) }
                if (drawable != null) arrow.setImageDrawable(drawable)
                else runCatching { arrow.setImageResource(res) }
                arrow.rotation = 0f
            }.start()
    }

    private fun setNodeIncluded(node: TreeNode, include: Boolean) {
        val excluded = currentConfig.excludedPaths.toMutableList()
        val originalExcluded = currentConfig.excludedPaths.toSet()
        val parts = node.relativePath.split('/')

        if (include) {
            for (i in 1 until parts.size) {
                val parentPath = parts.subList(0, i).joinToString("/")
                excluded.removeAll { it == parentPath }
            }
            excluded.removeAll { it == node.relativePath }
            if (node.isDirectory) excluded.removeAll { it.startsWith("${node.relativePath}/") }

            var anyAncestorExcluded = false
            for (i in 1 until parts.size) {
                val parentPath = parts.subList(0, i).joinToString("/")
                if (parentPath in originalExcluded) anyAncestorExcluded = true
                if (anyAncestorExcluded) {
                    val parentDir = File(projectRoot, parentPath)
                    parentDir.listFiles()?.forEach { sibling ->
                        if (sibling.name != parts[i]) {
                            val siblingRel = "$parentPath/${sibling.name}"
                            if (siblingRel !in excluded) excluded.add(siblingRel)
                        }
                    }
                }
            }
        } else {
            if (node.relativePath !in excluded) excluded.add(node.relativePath)
            if (node.isDirectory) excluded.removeAll { it.startsWith("${node.relativePath}/") }
        }

        currentConfig = currentConfig.copy(excludedPaths = excluded)
        onExcludedPathsChanged(excluded)

        rootNode?.let { refreshExclusionFlags(it, ancestorExcluded = false) }

        // [PERF] En vez de reconstruir todo el árbol, actualiza solo las filas visibles.
        refreshVisibleRows(fireStats = true)
        node.rowView?.let { pulseRow(it) }
    }

    private fun refreshExclusionFlags(node: TreeNode, ancestorExcluded: Boolean) {
        node.selfExcluded = currentConfig.excludedPaths.any { it == node.relativePath }
        node.ancestorExcluded = ancestorExcluded
        val next = ancestorExcluded || node.selfExcluded
        node.children.forEach { refreshExclusionFlags(it, next) }
    }

    private fun toggleAllMarked() {
        val currentlyAllMarked = currentConfig.excludedPaths.isEmpty()
        val newExcluded: List<String> = if (currentlyAllMarked) {
            val filter = FileFilter(projectRoot, currentConfig)
            projectRoot.listFiles()
                ?.filter { file -> filter.include(file, respectExcludedPaths = false) }
                ?.map { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }
        currentConfig = currentConfig.copy(excludedPaths = newExcluded)
        onExcludedPathsChanged(newExcluded)
        rootNode?.let { refreshExclusionFlags(it, ancestorExcluded = false) }

        // [PERF] Igual que arriba: solo filas visibles + pulso en el sticky.
        refreshVisibleRows(fireStats = true)
        pendingPulsePath = null
        stickyScroll.getChildAt(0)?.let { pulseRow(it) }
    }

    // ------------------------------------------------------------------
    // Stats desde el árbol
    // ------------------------------------------------------------------

    private fun computeStatsFromTree(root: TreeNode): PreviewStats {
        var totalFiles = 0
        var totalTokens = 0L
        var totalBytes = 0L
        val byExt = mutableMapOf<String, ExtensionStats>()

        fun visit(node: TreeNode) {
            if (node.ancestorExcluded || node.selfExcluded) return
            node.children.forEach { child ->
                if (child.isDirectory) visit(child)
                else if (!child.ancestorExcluded && !child.selfExcluded) {
                    val bytes = child.file.length()
                    val tokens = if (bytes <= 0L) 0L else (bytes + 3L) / 4L
                    val ext = child.file.extension.lowercase().ifEmpty { "sin-ext" }
                    totalFiles++
                    totalTokens += tokens
                    totalBytes += bytes
                    val stat = byExt.getOrPut(ext) { ExtensionStats(ext) }
                    stat.files++
                    stat.tokens += tokens
                    stat.bytes += bytes
                }
            }
        }
        visit(root)
        return PreviewStats(
            totalFiles = totalFiles,
            totalTokens = totalTokens,
            totalBytes = totalBytes,
            byExtension = byExt.values.sortedByDescending { it.tokens }
        )
    }

    // ------------------------------------------------------------------
    // Expand / collapse
    // ------------------------------------------------------------------

    fun expandAll() {
        val root = rootNode ?: return
        markAllExpanded(root, true)
        expandedPaths.clear()
        collectExpandable(root).forEach { expandedPaths.add(it) }
        renderInternal(fireStats = false)
    }

    fun collapseAll() {
        val root = rootNode ?: return
        markAllExpanded(root, false)
        expandedPaths.clear()
        renderInternal(fireStats = false)
    }

    private fun markAllExpanded(node: TreeNode, expanded: Boolean) {
        node.expanded = expanded
        node.children.forEach { markAllExpanded(it, expanded) }
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
    // Utils
    // ------------------------------------------------------------------

    /**
     * Brief alpha fade to draw the eye to a row whose inclusion state just
     * changed. No-op when [animationsEnabled] is `false`: the row's final
     * alpha is already 1 by default, and skipping the animation avoids
     * touching the view-property animator on every checkbox tap, which is
     * noticeable on low-end hardware when toggling directories with many
     * visible descendants.
     */
    private fun pulseRow(row: View) {
        if (!animationsEnabled) return
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
            if (child == null) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
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

    class TreeNode(
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
        var nameView: TextView? = null
    }
}