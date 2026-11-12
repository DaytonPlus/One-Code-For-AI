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

/**
 * Interactive preview of the project tree shown inside the configuration
 * dialog, and the authoritative source of every export decision.
 *
 * ### Purpose (Tree v2)
 *
 * In Tree v2 the tree is no longer just a preview of what the filters
 * would produce: it **is** the export selection. Every entry that appears
 * in the generated dump — file content or stub marker — is an entry the
 * tree selector decided to include, either because the filter said so
 * (initial state) or because the user overrode it (per-path override).
 *
 * This inversion of responsibility is what makes the three-way cycle
 * (INCLUDE / STUB / EXCLUDE) and the LOCKED concept possible without
 * duplicating filtering logic between the tree and the exporter.
 *
 * ### States and cycles
 *
 * Every rendered node has a [NodeState]:
 *
 *  - **[NodeState.INCLUDE]** — the file's full content is written.
 *  - **[NodeState.STUB]** — the file's header and size marker are written,
 *    content is not read.
 *  - **[NodeState.EXCLUDE]** — the file is not written to the dump.
 *  - **[NodeState.LOCKED]** — the file is present but not interactive:
 *    a hard-excluded directory or a binary extension that the user has
 *    not unlocked. Produced only by the scanner.
 *
 * **Both leaves and folders use the same three-step cycle:**
 *
 *  INCLUDE -> STUB -> EXCLUDE -> INCLUDE
 *
 * Clicking a **file** cycles only that file's own state. Clicking a
 * **folder** cycles the folder's aggregate AND forces every non-locked
 * descendant (folders and files, at every depth) to the same next state.
 * A folder click is therefore a uniform operation: after it, the folder
 * and everything inside it share the same state, with no mixed
 * intermediate values. This is what makes the folder-level tri-state
 * indicator meaningful — it never disagrees with its children.
 *
 * ### Upward propagation
 *
 * When a node's state changes, the change must be reflected not only in
 * that node's row but also in the aggregate indicators of every ancestor.
 * [propagateToAncestors] walks up the parent chain and updates each
 * ancestor's `state` field to the aggregate of its children. It stops as
 * soon as an ancestor's aggregate matches its current `state` (because
 * the higher ancestors read this `state` and therefore cannot change
 * either) and it **never** touches the other children of the ancestors.
 *
 * ### Hard-excluded and binary nodes
 *
 * These are created as LOCKED nodes during the scan and are not descended
 * into, which is what keeps a fresh open on a large project fast. The
 * "Show locked" toggle in the header unlocks them by flipping their state
 * to EXCLUDE and rendering them as normal (but still collapsed) nodes. A
 * subsequent expand of a previously-locked directory triggers a lazy
 * sub-scan of that directory only.
 *
 * When the toggle is turned off, every node that was originally LOCKED
 * goes back to its initial blocked state: children that were loaded while
 * it was unlocked are discarded, the node is collapsed, and it is marked
 * lazy again so the next unlock rebuilds it from scratch. The whole tree
 * is re-aggregated bottom-up (see [recomputeAllAggregates]) and the
 * state-changed callback is fired (see [propagateStateChange]) so the
 * dialog's cached export map is invalidated.
 *
 * ### `showLocked` is persisted across scans
 *
 * [scanAsync] and [scanSubtree] read [showLocked] when they create new
 * nodes. This is what makes the toggle survive a filter change: a rescan
 * that ran with `showLocked = false` in the previous implementation would
 * silently create all hard-excluded nodes as LOCKED, leaving the checkbox
 * visibly on while the tree showed nothing interactive. The dialog now
 * calls [setShowLockedPreference] before dispatching a scan so the new
 * tree is built with the correct initial state.
 *
 * ### Clickability is dynamic
 *
 * Every interactive surface (chevron, tri-state indicator, row-wide tap)
 * is always registered as clickable. The `locked` check happens **inside**
 * the click handler, not at row construction time. This is what makes
 * `toggleShowLocked` take effect immediately on already-rendered rows:
 * flipping `locked` from false to true disables every interaction on the
 * row without needing to rebuild it, and flipping it back re-enables them
 * just as cheaply.
 *
 * ### Lazy directories respond to the first click
 *
 * A directory whose children have never been loaded (a hard-excluded
 * folder that was just unlocked) has an EXCLUDE aggregate by construction,
 * so a naive state-cycle click would be a silent no-op. The first click
 * on such a directory expands it and triggers the sub-scan, giving the
 * user a visible response; subsequent clicks cycle the loaded children
 * normally. Clicks that arrive while the sub-scan is still running are
 * ignored (the children list is empty but `expanded` is already true,
 * so `onParentClick` would otherwise cycle an empty list).
 *
 * ### The unlocked subtree shows everything, not just INCLUDE
 *
 * The initial scan ([scanAsync]) hides EXCLUDE entries so the tree shows
 * only what will be exported. That is fast and useful for a fresh project
 * open, but it would make an unlocked `build/` folder look empty, because
 * every entry inside it resolves to EXCLUDE by extension or by gitignore.
 *
 * The lazy sub-scan ([scanSubtree]) therefore shows **every** child it
 * finds: files that the rules would exclude appear as unchecked EXCLUDE
 * rows the user can click to cycle. Size remains a hard skip (an
 * oversized file would dominate the tree and could not be included
 * anyway); binaries and nested hard-excluded folders appear as EXCLUDE
 * rows but stay unlocked or locked depending on [showLocked]. This is
 * what lets the user navigate to `build/outputs/apk/release/` and see
 * the APK on demand.
 *
 * ### Cancellation
 *
 * Scanning is cancellable through [cancelScan], which increments the
 * [scanToken] generation counter. Every callback the worker dispatches is
 * guarded by a token check, so a superseded scan silently drops its
 * result and never touches the UI.
 *
 * ### Performance
 *
 *  - [refreshVisibleRows] updates only the visible rows in place.
 *  - [refreshNodeRow] guards the state-icon assignment behind a check on
 *    the currently-set **resource ID**, not the Drawable instance.
 *  - Directory children are populated lazily.
 *  - [expandAll] / [collapseAll] toggle existing wrappers in place.
 *  - `cycleState` and `onParentClick` call [propagateStateChange] once.
 *
 * ### `currentStates()` returns files only
 *
 * Directories are containers, not exportable entries. Including them in
 * the state map would make
 * [com.devdayton.onecodeforai.core.CodeDumpGenerator] attempt to read
 * them as files and emit a `[File read error: ...]` marker for every
 * directory in the project.
 */
class ProjectTreeView(
    private val activity: Activity,
    private val projectRoot: File,
    private val onStatesChanged: (Map<String, NodeState>) -> Unit,
    private val onStatsChanged: (PreviewStats) -> Unit
) {
    private companion object {
        /**
         * Minimum number of scanned files between two progress callbacks.
         */
        const val PROGRESS_FILE_INTERVAL = 200

        /**
         * Minimum wall-clock time, in milliseconds, between two progress
         * callbacks.
         */
        const val PROGRESS_INTERVAL_MS = 120L
    }

    /** Configuration currently applied to the tree. Updated by [scanAsync]. */
    private var currentConfig: FilterConfig = FilterConfig()

    /** Root of the last successfully scanned tree, or `null` before first scan. */
    private var rootNode: TreeNode? = null

    /**
     * Per-path overrides layered on top of the filter-derived states.
     */
    private var currentOverrides: TreeOverrides = TreeOverrides()

    /**
     * Relative paths of currently-expanded directories. Persisted across
     * re-renders so a filter change does not collapse the user's manual
     * expansion state.
     */
    private val expandedPaths = mutableSetOf<String>()

    /**
     * Reentrancy guard for the bidirectional scroll synchronization
     * between [stickyScroll] and [horizontalScroll].
     */
    private var isSyncingScroll = false

    /**
     * Whether the "Show locked" toggle is currently active.
     *
     * Read by [scanAsync] and [scanSubtree] when creating new nodes so a
     * rescan preserves the toggle's intent, and flipped by
     * [toggleShowLocked] when the user toggles it on already-rendered
     * rows.
     */
    private var showLocked = false

    /**
     * Whether animations are enabled according to
     * [PluginSettings.areAnimationsEnabled].
     */
    private val animationsEnabled: Boolean
        get() = PluginSettings.areAnimationsEnabled(activity)

    /**
     * Generation counter used to cancel in-flight scans and sub-scans.
     */
    private val scanToken = AtomicLong(0L)

    // ------------------------------------------------------------------
    // View hierarchy
    // ------------------------------------------------------------------

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

    /** Returns the view to embed in the parent layout. */
    fun getView(): View = outerContainer

    /**
     * Sets the show-locked preference without triggering any node
     * transition.
     *
     * Called by the configuration dialog before each scan so the new tree
     * is built with the correct initial locked/unlocked state. Without
     * this, a fresh dialog whose "Show locked" checkbox is already
     * checked (because the process-wide preference is on) would render a
     * tree with all hard-excluded nodes still locked until the user
     * toggles the checkbox off and back on.
     *
     * The method does not walk the tree and does not touch any existing
     * node. It only updates the flag that [scanAsync] and [scanSubtree]
     * read to decide whether a new node should start unlocked. All
     * interactive toggling continues to go through [toggleShowLocked].
     */
    fun setShowLockedPreference(show: Boolean) {
        showLocked = show
    }

    /**
     * Returns a snapshot of the current per-file states.
     *
     * ### Directories are excluded on purpose
     *
     * Directories are containers, not exportable entries. Including them
     * in the map would make
     * [com.devdayton.onecodeforai.core.CodeDumpGenerator] attempt to read
     * them as files and emit a `[File read error: ...]` marker for every
     * directory in the project.
     *
     * ### LOCKED entries are excluded
     *
     * LOCKED entries are not part of the export, so including them would
     * inflate the progress denominator.
     *
     * The map is a defensive copy.
     */
    fun currentStates(): Map<String, NodeState> {
        val out = LinkedHashMap<String, NodeState>()
        val root = rootNode ?: return out
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.locked) continue
            // Files only. See the KDoc above.
            if (node.depth > 0 && !node.isDirectory) {
                out[node.relativePath] = node.state
            }
            node.children.forEach { stack.addLast(it) }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Async scan
    // ------------------------------------------------------------------

    /**
     * Walks [projectRoot] on a background thread and delivers the resulting
     * tree to [onComplete] on the UI thread.
     *
     * Cancellation is implicit: calling this method again before the
     * previous walk has finished invalidates the older worker via
     * [scanToken].
     *
     * ### Hard-excluded nodes
     *
     * Directories whose name is in the filter's hard-excluded set, and
     * files whose extension is in the binary set while `excludeBinaries`
     * is enabled, are created as LOCKED leaf/directory nodes and are not
     * descended into. This is the single mechanism that keeps a first scan
     * of a `node_modules`-heavy project fast.
     *
     * ### `showLocked` is respected
     *
     * When the "Show locked" toggle is currently ON, hard-excluded and
     * binary nodes are created **unlocked** (their `locked` flag is
     * false, their state is EXCLUDE). This is what makes a rescan — for
     * example after a filter change — leave the tree interactive even
     * though the "Show locked" checkbox is still checked. Without it, the
     * new tree would be built with all those nodes locked and the toggle
     * would appear to have no effect until the user toggled it off and
     * back on.
     *
     * ### Overrides are checked first
     *
     * Manual decisions from [TreeOverrides] are checked **before** the
     * hard-excluded/binary branch, so a file the user has explicitly
     * included or excluded keeps that state even if its extension is
     * binary or its parent is a hard-excluded directory. Without this,
     * including an APK from an unlocked subtree would be lost on the
     * next rescan, because the binary branch would overwrite the
     * override with EXCLUDE.
     */
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

                        // Overrides come first: if the user explicitly
                        // chose a state for this path, respect it. This
                        // is what makes "manually include an APK from
                        // the unlocked build/ folder and have it survive
                        // the next rescan" work.
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
                                // Respect the current "Show locked" state:
                                // if the user has the toggle ON, the new
                                // node must start unlocked so the
                                // rescan does not silently break the
                                // toggle.
                                locked = !showLocked
                                wasLockedOriginally = true
                                // If the user explicitly overrode this
                                // path, keep the override instead of
                                // forcing EXCLUDE.
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

    /**
     * Cancels the in-flight scan, if any.
     */
    fun cancelScan() {
        scanToken.incrementAndGet()
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Renders [root] and notifies the caller of the aggregate stats. */
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

    /**
     * Renders [node]'s direct children into its [TreeNode.childrenWrapper].
     *
     * ### Lazy sub-scan for previously-LOCKED directories
     *
     * A node flagged [TreeNode.lazyDirectory] spawns a sub-scan the first
     * time it is expanded. This is what makes "Show locked" cheap:
     * unlocking flips every LOCKED node to EXCLUDE at once, but the
     * subtree is only materialized when the user actually opens a folder.
     *
     * The sub-scan reuses [scanToken] for cancellation: collapsing the
     * node before the sub-scan finishes increments the token implicitly
     * via [cancelScan], and the sub-scan worker silently drops its result.
     *
     * ### Forced-state inheritance
     *
     * When the sub-scan completes, the node's current `state` (which may
     * have been forced by a folder-level click on an ancestor) is applied
     * to the newly scanned children, unless the node's state is EXCLUDE.
     * Without this inheritance, expanding a folder that was just forced
     * to INCLUDE would revert the new children to their filter-derived
     * (mostly EXCLUDE) states.
     *
     * ### Upward propagation
     *
     * After the sub-scan populates the children, the node's own `state`
     * may change (e.g. it was EXCLUDE and the new children are all
     * INCLUDE). [propagateToAncestors] is called so every ancestor's
     * `state` is updated to reflect the change.
     */
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
                            // Re-establish the parent link: the children
                            // were created on the worker thread without
                            // a parent reference.
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
                    // Sub-scan failures are non-fatal: the node stays as an
                    // empty directory. The user can collapse and re-expand
                    // to retry.
                }
            }.apply { isDaemon = true; name = "OneCode-subtree-scan"; start() }
            return
        }

        node.children.forEach { child -> renderNode(child, cw) }
    }

    /**
     * Builds the direct children of [node] by walking its filesystem entry
     * with [filter] applied, without descending past the first level.
     *
     * Used only by the lazy sub-scan path of [populateChildren]. It is
     * called when the user expands a folder that was previously locked
     * (unlocked via "Show locked" but never descended into), and its job
     * is to make the contents of that folder **visible** so the user can
     * see and pick files. This is why it behaves differently from the
     * initial [scanAsync] scan:
     *
     * ### Priority order
     *
     * The rules are evaluated in this exact order:
     *
     *  1. **Size.** Files larger than [FilterConfig.maxFileSizeBytes]
     *     (when [FilterConfig.useMaxSize] is on) are skipped entirely.
     *     They would dominate the tree and the generator would refuse
     *     to include them anyway.
     *  2. **Overrides.** A path present in [TreeOverrides] keeps the
     *     user's manual state, whatever it is.
     *  3. **Binaries.** When [FilterConfig.excludeBinaries] is on, a
     *     known binary extension becomes EXCLUDE. This takes priority
     *     over reference-only: a binary cannot be promoted to a stub,
     *     because a stub still writes the file's size marker and a
     *     binary is not a useful size reference.
     *  4. **Nested hard-excluded directories.** A directory whose name
     *     is in [FileFilter.hardExcludedDirectories] becomes EXCLUDE
     *     (and locked or unlocked depending on [showLocked]).
     *  5. **Reference only / Inclusions / Exclusions / Gitignore**, in
     *     [FileFilter.resolveMode]'s standard order.
     *  6. **Hidden files and unknown extensions** default to EXCLUDE;
     *     text extensions and extensionless files default to INCLUDE.
     *
     * ### The hard-excluded ancestor check is skipped
     *
     * A file inside `build/` or `node_modules/` would normally return
     * EXCLUDE from [FileFilter.resolveMode] because its ancestor is in
     * the hard-excluded set. Here that check is skipped
     * (`respectHardExcludedAncestors = false`), so the file's own rules
     * decide its state. Without it, expanding an unlocked `build/`
     * folder would produce an empty tree.
     *
     * ### EXCLUDE entries are still added to the tree
     *
     * The initial scan hides EXCLUDE files so the tree shows only what
     * will be exported. This method shows them too, as unchecked rows
     * the user can click to cycle. Without it the user could never
     * navigate to `build/outputs/apk/release/` and see the APK.
     *
     * ### `showLocked` is respected for binaries and nested hard-excluded
     *
     * When the "Show locked" toggle is off, binaries and nested
     * hard-excluded dirs are created as LOCKED nodes (visible but not
     * interactive), exactly like in the initial scan. When the toggle is
     * on, they are created unlocked (EXCLUDE) so the user can interact
     * with them.
     *
     * ### Every directory child is lazy
     *
     * Sub-directories created here are marked `lazyDirectory = true`
     * because the sub-scan only reads one level. The next expand will
     * trigger its own sub-scan.
     */
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

            // 1. Size: hard skip. The generator would refuse to include
            //    an oversized file anyway, and showing it would just
            //    clutter the unlocked subtree.
            if (!file.isDirectory && currentConfig.useMaxSize &&
                file.length() > currentConfig.maxFileSizeBytes) {
                continue
            }

            // 2. Overrides take precedence over everything else.
            val overrideState = when {
                relativePath in currentOverrides.includedPaths -> NodeState.INCLUDE
                relativePath in currentOverrides.stubPaths -> NodeState.STUB
                relativePath in currentOverrides.excludedPaths -> NodeState.EXCLUDE
                else -> null
            }

            // 3-4. Binaries and nested hard-excluded dirs are EXCLUDE.
            //      They stay visible (the user can click them to
            //      include) but are not stubbable: a binary cannot be
            //      promoted to a stub by reference-only patterns.
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

            // 5. Standard filter resolution. The ancestor check is
            //    skipped so files inside the unlocked folder are
            //    evaluated by their own rules.
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

    // ------------------------------------------------------------------
    // In-place refresh of visible rows
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Row construction
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

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

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
            NodeState.LOCKED -> NodeState.EXCLUDE // unreachable
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

    /**
     * Walks up the parent chain of [node] and updates each ancestor's
     * `state` field to the aggregate of its children.
     *
     * Stops as soon as an ancestor's aggregate matches its current
     * `state`. Never touches the ancestors' other children.
     */
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

    /**
     * Unlocks or re-locks every originally-locked node in the tree.
     *
     * ### On unlock (`show = true`)
     *
     * Every node marked [TreeNode.wasLockedOriginally] becomes
     * interactive: `locked` flips to false and its state is reset to
     * EXCLUDE. Its descendants are **not** touched — they were never
     * loaded (the node was `lazyDirectory`), so the first expand will
     * scan them fresh, and the user has to set them explicitly.
     *
     * ### On re-lock (`show = false`)
     *
     * Every originally-locked node goes back to its initial blocked
     * state: `state` resets to EXCLUDE, `locked` flips to true,
     * `lazyDirectory` flips to true again, any children that were loaded
     * while it was unlocked are discarded, and the node is collapsed.
     *
     * ### Bottom-up aggregate recomputation
     *
     * After the re-lock, every node's `state` may have changed. The
     * aggregates of the ancestors of the affected nodes are recomputed
     * bottom-up via [recomputeAllAggregates].
     *
     * ### Cache invalidation
     *
     * `onStatesChanged` is fired at the end so the dialog's cached export
     * map matches the new tree.
     */
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

    /**
     * Recomputes every non-locked directory's `state` from its children,
     * bottom-up. Implemented as a post-order iterative traversal.
     */
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

    // ------------------------------------------------------------------
    // Aggregate computation
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Aggregate statistics
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Expand / collapse all
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

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
    // Custom scroll views
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------

    /**
     * One node in the rendered tree.
     *
     *  - `state` is the [NodeState] the user has settled on.
     *  - `locked` marks the node as non-interactive.
     *  - `wasLockedOriginally` is set once by the scanner and never
     *    cleared.
     *  - `lazyDirectory` is true for a directory whose children have never
     *    been scanned.
     *  - `expanded` tracks whether the children wrapper is visible.
     *  - `parent` is the enclosing [TreeNode], set when the node is added
     *    to its parent's children list. Used by [propagateToAncestors].
     *  - `stateIconResId` is the resource ID of the drawable currently
     *    assigned to `stateIcon`. Used by [refreshNodeRow] as a fast
     *    redundant-setter guard: `UiFactory.loadPluginDrawable` returns a
     *    fresh Drawable instance on every call, so an identity check on
     *    the Drawable would always be true and the guard would never fire.
     */
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