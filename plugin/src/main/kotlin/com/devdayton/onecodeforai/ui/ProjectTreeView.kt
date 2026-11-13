package com.devdayton.onecodeforai.ui

import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.PluginSettings
import com.devdayton.onecodeforai.core.ExtensionStats
import com.devdayton.onecodeforai.core.FileFilter
import com.devdayton.onecodeforai.core.FilterConfig
import com.devdayton.onecodeforai.core.NodeState
import com.devdayton.onecodeforai.core.PreviewStats
import com.devdayton.onecodeforai.core.TreeOverrides
import com.devdayton.onecodeforai.ui.CodeAssistTheme
import com.devdayton.onecodeforai.ui.UiFactory

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
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
    private val onStatesChanged: (Map<String, NodeState>) -> Unit,
    private val onStatsChanged: (PreviewStats) -> Unit
) {
    private companion object {

        const val PROGRESS_FILE_INTERVAL = 200

        const val PROGRESS_INTERVAL_MS = 120L
    }

    private var currentConfig: FilterConfig = FilterConfig()

    private var rootNode: TreeNode? = null

    private var currentOverrides: TreeOverrides = TreeOverrides()

    private val expandedPaths = mutableSetOf<String>()

    private var isSyncingScroll = false

    private var showLocked = false

    private val animationsEnabled: Boolean
        get() = PluginSettings.areAnimationsEnabled(activity)

    private val scanToken = AtomicLong(0L)

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

    fun setShowLockedPreference(show: Boolean) {
        showLocked = show
    }

    fun currentStates(): Map<String, NodeState> {
        val out = LinkedHashMap<String, NodeState>()
        val root = rootNode ?: return out
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.locked) continue

            if (node.depth > 0 && !node.isDirectory) {
                out[node.relativePath] = node.state
            }
            node.children.forEach { stack.addLast(it) }
        }
        return out
    }

    fun scanAsync(
        config: FilterConfig,
        overrides: TreeOverrides = TreeOverrides(),
        onProgress: (scannedFiles: Int) -> Unit,
        onComplete: (TreeNode) -> Unit,
        onError: (Exception) -> Unit = {}
    ) {
        val normalizedConfig = config.normalized()
        currentConfig = normalizedConfig
        currentOverrides = overrides
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

                while (pendingDirectories.isNotEmpty()) {
                    if (scanToken.get() != myToken) return@Thread
                    val node = pendingDirectories.removeLast()
                    val files = node.file.listFiles()
                        ?.sortedWith(
                            compareBy<File> { !it.isDirectory }
                                .thenBy { it.name.lowercase() }
                                .thenBy { it.name }
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

                        val extension = file.name.substringAfterLast('.', "").lowercase()

                        val overrideState = when {
                            relativePath in overrides.includedPaths -> NodeState.INCLUDE
                            relativePath in overrides.stubPaths -> NodeState.STUB
                            relativePath in overrides.excludedPaths -> NodeState.EXCLUDE
                            else -> null
                        }

                        val isHardExcluded = file.isDirectory &&
                            file.name in filter.hardExcludedDirectories()
                        val isBinaryFile = !file.isDirectory &&
                            normalizedConfig.excludeBinaries &&
                            extension in filter.binaryExtensions()

                        if (isHardExcluded || isBinaryFile) {
                            val lockedNode = TreeNode(
                                file, file.isDirectory, node.depth + 1, relativePath, file.name
                            ).apply {

                                locked = !showLocked
                                wasLockedOriginally = true

                                state = overrideState ?: NodeState.EXCLUDE
                                lazyDirectory = file.isDirectory
                            }
                            node.children.add(lockedNode)
                            if (file.isDirectory) childDirectories.add(lockedNode)
                            continue
                        }

                        val filterState = filter.resolveMode(
                            relativePath = relativePath,
                            isDirectory = file.isDirectory,
                            sizeBytes = if (file.isFile) file.length() else 0L,
                            respectExcludedPaths = false
                        )

                        val state = overrideState ?: filterState

                        if (file.isDirectory) {
                            if (filterState == NodeState.EXCLUDE && state == NodeState.EXCLUDE) continue
                            val child = TreeNode(
                                file, true, node.depth + 1, relativePath, file.name
                            ).apply {
                                this.state = state
                                this.expanded = expandedPaths.contains(relativePath)
                            }
                            node.children.add(child)
                            childDirectories.add(child)
                        } else {
                            if (state == NodeState.EXCLUDE &&
                                filterState == NodeState.EXCLUDE &&
                                overrideState == null) {
                                continue
                            }
                            val child = TreeNode(
                                file, false, node.depth + 1, relativePath, file.name
                            ).apply {
                                this.state = state
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

                    node.state = computeAggregateState(node.children)
                    childDirectories.asReversed().forEach(pendingDirectories::addLast)
                }

                root.state = computeAggregateState(root.children)

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

    fun renderTree(root: TreeNode) {
        rootNode = root
        renderInternal(fireStats = true)
    }

    private fun renderInternal(fireStats: Boolean = true) {
        val root = rootNode ?: return

        stickyScroll.removeAllViews()
        val rootRow = buildRow(root, isSticky = true)
        stickyScroll.addView(rootRow, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

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

        if (node.isDirectory && (node.children.isNotEmpty() || node.lazyDirectory)) {
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
    }

    private fun populateChildren(node: TreeNode) {
        val cw = node.childrenWrapper ?: return
        if (cw.childCount > 0) return

        if (node.lazyDirectory) {
            cw.addView(
                UiFactory.run {
                    activity.textView(
                        activity.pluginString(R.string.tree_unlock_scanning),
                        11f, CodeAssistTheme.TEXT_MUTED
                    )
                }.apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
            )

            val myToken = scanToken.get()
            Thread {
                try {
                    val filter = FileFilter(projectRoot, currentConfig)
                    val subChildren = scanSubtree(node, filter, myToken)
                    if (scanToken.get() != myToken) return@Thread
                    activity.runOnUiThread {
                        if (scanToken.get() == myToken && node.expanded) {
                            cw.removeAllViews()
                            node.lazyDirectory = false
                            node.children.clear()
                            node.children.addAll(subChildren)

                            subChildren.forEach { it.parent = node }

                            if (node.state != NodeState.EXCLUDE) {
                                node.children.forEach { applyRecursiveForced(it, node.state) }
                            }

                            node.children.forEach { renderNode(it, cw) }
                            node.state = computeAggregateState(node.children)
                            propagateToAncestors(node)
                            refreshNodeRow(node)
                        }
                    }
                } catch (_: Exception) {

                }
            }.apply { isDaemon = true; name = "OneCode-subtree-scan"; start() }
            return
        }

        node.children.forEach { child -> renderNode(child, cw) }
    }

    private fun scanSubtree(
        node: TreeNode,
        filter: FileFilter,
        myToken: Long
    ): List<TreeNode> {
        val out = mutableListOf<TreeNode>()
        val files = node.file.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }
                .thenBy { it.name.lowercase() }
                .thenBy { it.name }
        ) ?: return out

        for (file in files) {
            if (scanToken.get() != myToken) return out
            val relativePath = "${node.relativePath}/${file.name}"
            val extension = file.name.substringAfterLast('.', "").lowercase()

            if (!file.isDirectory && currentConfig.useMaxSize &&
                file.length() > currentConfig.maxFileSizeBytes) {
                continue
            }

            val overrideState = when {
                relativePath in currentOverrides.includedPaths -> NodeState.INCLUDE
                relativePath in currentOverrides.stubPaths -> NodeState.STUB
                relativePath in currentOverrides.excludedPaths -> NodeState.EXCLUDE
                else -> null
            }

            val isBinary = !file.isDirectory &&
                currentConfig.excludeBinaries &&
                extension in filter.binaryExtensions()
            val isHardExcludedDir = file.isDirectory &&
                file.name in filter.hardExcludedDirectories()

            if (isBinary || isHardExcludedDir) {
                val child = TreeNode(
                    file, file.isDirectory, node.depth + 1, relativePath, file.name
                ).apply {
                    this.state = overrideState ?: NodeState.EXCLUDE
                    this.lazyDirectory = file.isDirectory
                    if (!showLocked) {
                        this.locked = true
                        this.wasLockedOriginally = true
                    }
                }
                out.add(child)
                continue
            }

            val filterState = filter.resolveMode(
                relativePath = relativePath,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isFile) file.length() else 0L,
                respectExcludedPaths = false,
                respectHardExcludedAncestors = false
            )

            val state = overrideState ?: filterState

            out.add(
                TreeNode(file, file.isDirectory, node.depth + 1, relativePath, file.name).apply {
                    this.state = state
                    this.lazyDirectory = file.isDirectory
                }
            )
        }
        return out
    }

    private fun refreshVisibleRows(fireStats: Boolean = true) {
        val root = rootNode ?: return
        refreshNodeVisible(root)
        if (fireStats) onStatsChanged(computeStatsFromTree(root))
    }

    private fun refreshNodeVisible(root: TreeNode) {
        refreshNodeRow(root)
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            current.children.forEach { child ->
                refreshNodeRow(child)
                if (child.isDirectory && child.expanded) {
                    stack.addLast(child)
                }
            }
        }
    }

    private fun refreshNodeRow(node: TreeNode) {
        if (node.locked) {
            val resId = R.drawable.ic_state_locked
            if (node.stateIconResId != resId) {
                val drawable = UiFactory.run { activity.loadPluginDrawable(resId) }
                node.stateIcon?.setImageDrawable(drawable)
                node.stateIconResId = resId
            }
            node.iconView?.setColorFilter(CodeAssistTheme.TEXT_MUTED)
            node.nameView?.setTextColor(CodeAssistTheme.TEXT_MUTED)
            return
        }

        val displayState = if (node.isDirectory) computeAggregateState(node.children) else node.state
        val resId = when (displayState) {
            NodeState.INCLUDE -> R.drawable.ic_state_include
            NodeState.STUB -> R.drawable.ic_state_stub
            NodeState.EXCLUDE, NodeState.LOCKED -> R.drawable.ic_state_exclude
        }
        if (node.stateIconResId != resId) {
            val drawable = UiFactory.run { activity.loadPluginDrawable(resId) }
            node.stateIcon?.setImageDrawable(drawable)
            node.stateIconResId = resId
        }
        val tinted = displayState == NodeState.EXCLUDE
        node.iconView?.setColorFilter(
            if (tinted) CodeAssistTheme.TEXT_MUTED else CodeAssistTheme.ACCENT
        )
        node.nameView?.setTextColor(
            if (tinted) CodeAssistTheme.TEXT_MUTED else CodeAssistTheme.TEXT_PRIMARY
        )
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

        val hasChildren = node.isDirectory && (node.children.isNotEmpty() || node.lazyDirectory)
        if (hasChildren && !isSticky) {
            val res = if (node.expanded) R.drawable.ic_tech_chevron_down
                      else R.drawable.ic_tech_chevron_right
            val arrowContainer = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                isClickable = true
                isFocusable = true
                setOnClickListener { if (!node.locked) toggleExpanded(node) }
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
            row.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), 1)
            })
        }

        val indicatorContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(32)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (node.locked) return@setOnClickListener
                if (isSticky) onParentClick(node) else cycleState(node)
            }
        }
        val indicator = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        }
        indicatorContainer.addView(indicator)
        node.stateIcon = indicator
        row.addView(indicatorContainer)

        val iconRes = if (node.isDirectory) R.drawable.ic_tech_folder else R.drawable.ic_tech_file
        val icon = ImageView(activity).apply {
            val drawable = UiFactory.run { activity.loadPluginDrawable(iconRes) }
            if (drawable != null) setImageDrawable(drawable)
            else runCatching { setImageResource(iconRes) }
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
        }
        node.iconView = icon
        row.addView(icon)

        val baseName = if (isRoot) "${node.displayName}/" else node.displayName
        val nameText = if (node.locked) "$baseName ${activity.pluginString(R.string.tree_locked_hint)}"
                       else baseName
        val nameView = UiFactory.run {
            activity.textView(nameText, 12f, CodeAssistTheme.TEXT_PRIMARY, isBold = isRoot)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            setPadding(0, 0, dp(6), 0)
        }
        node.nameView = nameView
        row.addView(nameView)

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

        if (!isSticky) {
            row.isClickable = true
            row.setOnClickListener {
                if (!node.locked) cycleState(node)
            }
        }

        refreshNodeRow(node)

        return row
    }

    private fun cycleState(node: TreeNode) {
        if (node.locked) return
        if (node.isDirectory) {
            if (node.lazyDirectory && node.children.isEmpty()) {
                if (!node.expanded) toggleExpanded(node)
                return
            }
            onParentClick(node)
            return
        }
        node.state = when (node.state) {
            NodeState.INCLUDE -> NodeState.STUB
            NodeState.STUB -> NodeState.EXCLUDE
            NodeState.EXCLUDE, NodeState.LOCKED -> NodeState.INCLUDE
        }
        propagateToAncestors(node)
        refreshVisibleRows(fireStats = true)
        node.rowView?.let { pulseRow(it) }
        propagateStateChange()
    }

    private fun onParentClick(node: TreeNode) {
        if (node.locked) return
        if (node.lazyDirectory && node.children.isEmpty()) return

        val nonLockedChildren = node.children.filter { !it.locked }
        if (nonLockedChildren.isEmpty()) return

        val aggregate = computeAggregateState(nonLockedChildren)
        val toState: NodeState = when (aggregate) {
            NodeState.INCLUDE -> NodeState.STUB
            NodeState.STUB -> NodeState.EXCLUDE
            NodeState.EXCLUDE -> NodeState.INCLUDE
            NodeState.LOCKED -> NodeState.EXCLUDE
        }

        nonLockedChildren.forEach { applyRecursiveForced(it, toState) }
        node.state = toState

        propagateToAncestors(node)

        refreshVisibleRows(fireStats = true)
        node.rowView?.let { pulseRow(it) }
        propagateStateChange()
    }

    private fun applyRecursiveForced(node: TreeNode, toState: NodeState) {
        if (node.locked) return
        node.state = toState
        if (node.isDirectory) {
            node.children.forEach { applyRecursiveForced(it, toState) }
        }
    }

    private fun propagateToAncestors(node: TreeNode) {
        var current = node.parent
        while (current != null) {
            val newAggregate = computeAggregateState(current.children)
            if (current.state == newAggregate) return
            current.state = newAggregate
            current = current.parent
        }
    }

    private fun toggleExpanded(node: TreeNode) {
        if (node.locked) return
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

    fun toggleShowLocked(show: Boolean) {
        if (showLocked == show) return
        showLocked = show
        val root = rootNode ?: return

        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.wasLockedOriginally) {
                if (show) {
                    node.locked = false
                    node.state = NodeState.EXCLUDE
                } else {
                    node.locked = true
                    node.state = NodeState.EXCLUDE
                    node.lazyDirectory = true
                    node.expanded = false
                    node.children.clear()
                    node.childrenWrapper?.let { cw ->
                        cw.removeAllViews()
                        cw.visibility = View.GONE
                    }
                    node.arrow?.let { animateChevron(it, false) }
                }
                refreshNodeRow(node)
            }
            node.children.forEach { stack.addLast(it) }
        }

        recomputeAllAggregates()

        refreshVisibleRows(fireStats = true)
        propagateStateChange()
    }

    private fun recomputeAllAggregates() {
        val root = rootNode ?: return
        val stack = ArrayDeque<Pair<TreeNode, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (node, processed) = stack.removeLast()
            if (processed) {
                if (node.isDirectory && !node.locked) {
                    node.state = computeAggregateState(node.children)
                }
            } else {
                stack.addLast(node to true)
                node.children.forEach { stack.addLast(it to false) }
            }
        }
    }

    private fun propagateStateChange() {
        onStatesChanged(currentStates())
    }

    private fun computeAggregateState(children: List<TreeNode>): NodeState {
        var anyInclude = false
        var anyStub = false
        var anyNonLocked = false
        children.forEach { child ->
            if (child.locked) return@forEach
            anyNonLocked = true
            when (child.state) {
                NodeState.INCLUDE -> anyInclude = true
                NodeState.STUB -> anyStub = true
                NodeState.EXCLUDE, NodeState.LOCKED -> Unit
            }
        }
        return when {
            !anyNonLocked -> NodeState.EXCLUDE
            anyInclude -> NodeState.INCLUDE
            anyStub -> NodeState.STUB
            else -> NodeState.EXCLUDE
        }
    }

    private fun computeStatsFromTree(root: TreeNode): PreviewStats {
        var totalFiles = 0
        var stubFiles = 0
        var totalTokens = 0L
        var totalBytes = 0L
        val byExt = mutableMapOf<String, ExtensionStats>()

        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.locked) continue
            node.children.forEach { child ->
                if (child.locked) return@forEach
                when {
                    child.isDirectory -> stack.addLast(child)
                    child.state == NodeState.INCLUDE -> {
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
                    child.state == NodeState.STUB -> {
                        stubFiles++
                        val ext = child.file.extension.lowercase().ifEmpty { "sin-ext" }
                        val stat = byExt.getOrPut(ext) { ExtensionStats(ext) }
                        stat.files++
                    }
                }
            }
        }
        return PreviewStats(
            totalFiles = totalFiles + stubFiles,
            stubFiles = stubFiles,
            totalTokens = totalTokens,
            totalBytes = totalBytes,
            byExtension = byExt.values.sortedByDescending { it.tokens }
        )
    }

    fun expandAll() {
        val root = rootNode ?: return
        expandedPaths.clear()
        collectExpandable(root).forEach { expandedPaths.add(it) }
        root.children.forEach { toggleAllWrappers(it, expand = true) }
    }

    fun collapseAll() {
        val root = rootNode ?: return
        expandedPaths.clear()
        root.children.forEach { toggleAllWrappers(it, expand = false) }
    }

    private fun toggleAllWrappers(node: TreeNode, expand: Boolean) {
        if (node.locked) return
        if (!node.isDirectory) return

        val cw = node.childrenWrapper
        if (cw != null) {
            if (expand && node.children.isNotEmpty()) {
                populateChildren(node)
                cw.visibility = View.VISIBLE
                node.expanded = true
            } else {
                cw.visibility = View.GONE
                node.expanded = false
            }

            node.arrow?.let { arrow ->
                val res = if (node.expanded) R.drawable.ic_tech_chevron_down
                          else R.drawable.ic_tech_chevron_right
                val drawable = UiFactory.run { activity.loadPluginDrawable(res) }
                if (drawable != null) arrow.setImageDrawable(drawable)
                else runCatching { arrow.setImageResource(res) }
                arrow.rotation = 0f
            }
        }

        node.children.forEach { toggleAllWrappers(it, expand) }
    }

    private fun collectExpandable(root: TreeNode): List<String> {
        val out = mutableListOf<String>()
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.locked) continue
            if (current.isDirectory && current.children.isNotEmpty()) {
                out.add(current.relativePath)
                current.children.forEach { stack.addLast(it) }
            }
        }
        return out
    }

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

    private class NestedScrollView(context: Context) : ScrollView(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var lastY = 0f

        private fun isTouchInEmptySpace(ev: MotionEvent): Boolean {
            if (ev.actionMasked != MotionEvent.ACTION_DOWN) return false
            val child = getChildAt(0) ?: return false
            return ev.y > child.bottom
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (isTouchInEmptySpace(ev)) return false
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = ev.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - lastY
                    if (kotlin.math.abs(dy) >= touchSlop) {
                        val fingerDown = dy > 0
                        val treeCanStillScroll = if (fingerDown) {
                            canScrollVertically(-1)
                        } else {
                            canScrollVertically(1)
                        }
                        parent?.requestDisallowInterceptTouchEvent(treeCanStillScroll)
                        lastY = ev.rawY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
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

    class TreeNode(
        val file: File,
        val isDirectory: Boolean,
        val depth: Int,
        val relativePath: String,
        val displayName: String
    ) {
        val children = mutableListOf<TreeNode>()
        var state: NodeState = NodeState.EXCLUDE
        var locked: Boolean = false
        var wasLockedOriginally: Boolean = false
        var lazyDirectory: Boolean = false
        var expanded = false
        var parent: TreeNode? = null
        var wrapper: View? = null
        var rowView: View? = null
        var childrenWrapper: LinearLayout? = null
        var arrow: ImageView? = null
        var stateIcon: ImageView? = null
        var stateIconResId: Int? = null
        var iconView: ImageView? = null
        var nameView: TextView? = null
    }
}