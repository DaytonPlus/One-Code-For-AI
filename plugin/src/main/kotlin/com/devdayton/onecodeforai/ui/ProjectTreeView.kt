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
 * ### States and propagation
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
 * Clicking a leaf cycles INCLUDE -> STUB -> EXCLUDE -> INCLUDE. Clicking a
 * directory applies the next state **to its whole non-locked subtree**,
 * but only touches descendants whose current state matches the
 * directory's aggregate state. That is what preserves a user's manual
 * exclusions when they toggle a mixed folder.
 *
 * The aggregate of a directory (and of the sticky root header) is "the
 * most permissive child wins":
 *
 *  - INCLUDE if any non-LOCKED child is INCLUDE,
 *  - STUB otherwise if any non-LOCKED child is STUB,
 *  - EXCLUDE if every non-LOCKED child is EXCLUDE (or there are none).
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
 * lazy again so the next unlock rebuilds it from scratch.
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
 *  - [refreshVisibleRows] updates only the visible rows in place, instead
 *    of tearing down and rebuilding the whole tree when a single node
 *    changes. Cost is O(visible) rather than O(all).
 *  - [refreshNodeRow] guards the state-icon assignment behind a check on
 *    the currently-set drawable, so a single toggle repaints only the rows
 *    whose state actually changed.
 *  - [TreeNode] keeps direct references to its row views so a refresh never
 *    has to search the hierarchy for the widget it needs to update.
 *  - Directory children are populated lazily (see [renderNode] and
 *    [populateChildren]).
 *
 * @param activity      Host activity used to inflate every view. Must be a
 *                      real activity (not a service context) because the
 *                      tree instantiates platform widgets.
 * @param projectRoot   Canonical root of the tree. The scan never leaves
 *                      this directory.
 * @param onStatesChanged invoked on the UI thread whenever the per-path
 *                      state map changes, so the caller can persist it or
 *                      forward it to the export coordinator. The map
 *                      contains every non-LOCKED path currently rendered.
 * @param onStatsChanged invoked on the UI thread whenever the aggregate
 *                      [PreviewStats] change (initial render, state flip,
 *                      expand/collapse when it affects visibility).
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
         * Keeps the notification text updating on large projects without
         * flooding the UI thread on every directory entry.
         */
        const val PROGRESS_FILE_INTERVAL = 200

        /**
         * Minimum wall-clock time, in milliseconds, between two progress
         * callbacks. Whichever threshold is hit first (this or
         * [PROGRESS_FILE_INTERVAL]) triggers an update, so small but slow
         * directory trees still report progress.
         */
        const val PROGRESS_INTERVAL_MS = 120L
    }

    /** Configuration currently applied to the tree. Updated by [scanAsync]. */
    private var currentConfig: FilterConfig = FilterConfig()

    /** Root of the last successfully scanned tree, or `null` before first scan. */
    private var rootNode: TreeNode? = null

    /**
     * Per-path overrides layered on top of the filter-derived states.
     *
     * Set by the configuration dialog *before* the first scan, so the
     * scanner can apply the overrides as it builds the tree instead of
     * requiring a second pass.
     */
    private var currentOverrides: TreeOverrides = TreeOverrides()

    /**
     * Relative paths of currently-expanded directories.
     *
     * Persisted across re-renders so that expanding a folder, changing a
     * filter, and re-scanning does not collapse the user's manual
     * expansion state.
     */
    private val expandedPaths = mutableSetOf<String>()

    /**
     * Relative path of a row that should flash once its view has been
     * attached. An empty string means "pulse the root (sticky) row".
     */
    private var pendingPulsePath: String? = null

    /**
     * Reentrancy guard for the bidirectional scroll synchronization between
     * [stickyScroll] and [horizontalScroll]. Without it, syncing one side
     * would fire the other side's `onScrollChange` and cause an infinite
     * feedback loop.
     */
    private var isSyncingScroll = false

    /**
     * Whether the "Show locked" toggle is currently active.
     *
     * When false (the default), LOCKED nodes are visible but disabled and
     * cannot be expanded. When true, they were flipped to EXCLUDE by
     * [toggleShowLocked] and are fully interactive.
     */
    private var showLocked = false

    /**
     * Whether animations are enabled according to
     * [PluginSettings.areAnimationsEnabled].
     *
     * Read through a getter rather than cached at construction so a change
     * made in the settings page mid-dialog is applied on the next animation
     * without needing to recreate the view.
     */
    private val animationsEnabled: Boolean
        get() = PluginSettings.areAnimationsEnabled(activity)

    /**
     * Generation counter used to cancel in-flight scans and sub-scans.
     *
     * Each [scanAsync] captures its own value; workers check
     * `scanToken.get() == myToken` between entries and bail out silently if
     * a newer scan has started. [cancelScan] simply increments the token.
     */
    private val scanToken = AtomicLong(0L)

    // ------------------------------------------------------------------
    // View hierarchy (constructed once, reused across renders)
    // ------------------------------------------------------------------

    /**
     * Container for every tree row except the sticky header.
     */
    private val rowsContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    /**
     * Horizontal scroller wrapping [rowsContainer]. Lets long paths extend
     * past the right edge instead of forcing every row to wrap.
     */
    private val horizontalScroll = WrapHorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = true
        isVerticalScrollBarEnabled = false
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        addView(rowsContainer, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    /**
     * Vertical scroller wrapping [horizontalScroll].
     */
    private val verticalScroll = NestedScrollView(activity).apply {
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        addView(horizontalScroll, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /**
     * Sticky header showing the root row.
     */
    private val stickyScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        setBackgroundColor(CodeAssistTheme.SURFACE_RAISED)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    /** 1dp separator between the sticky header and the scrollable body. */
    private val dividerView = View(activity).apply {
        setBackgroundColor(CodeAssistTheme.OUTLINE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
    }

    /** Root of the whole view; what [getView] returns. */
    private val outerContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        addView(stickyScroll)
        addView(dividerView)
        addView(verticalScroll)
    }

    /**
     * Minimum width a non-sticky row must occupy.
     *
     * Computed once from the dialog dimensions so short rows still fill the
     * full width, which keeps the "tap the row to cycle state" hit target
     * predictable even for a file with a one-character name.
     */
    private val rowMinWidth: Int = run {
        val screenW = activity.resources.displayMetrics.widthPixels
        val dialogW = (screenW * 0.94f).toInt()
        (dialogW - dp(18 * 2 + 6 * 2)).coerceAtLeast(0)
    }

    init {
        // Keep the sticky header and the scrollable body in lockstep when
        // either side is panned horizontally.
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
     * Returns a snapshot of the current per-path states.
     *
     * LOCKED entries are excluded from the map because they are not part
     * of the export (and including them would inflate the progress
     * denominator with entries the generator is not going to visit).
     *
     * The map is a defensive copy: the caller can safely pass it to a
     * background thread without the UI mutating it under their feet.
     */
    fun currentStates(): Map<String, NodeState> {
        val out = LinkedHashMap<String, NodeState>()
        val root = rootNode ?: return out
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.locked) continue
            if (node.depth > 0) out[node.relativePath] = node.state
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
     * [scanToken], and the older worker silently drops its result.
     *
     * ### Hard-excluded nodes
     *
     * Directories whose name is in the filter's hard-excluded set, and
     * files whose extension is in the binary set while `excludeBinaries`
     * is enabled, are created as LOCKED leaf/directory nodes and are not
     * descended into. This is the single mechanism that keeps a first scan
     * of a `node_modules`-heavy project fast.
     *
     * ### Overrides
     *
     * The [overrides] parameter is applied while building the tree, not
     * after: a path present in [TreeOverrides.includedPaths] is created
     * with [NodeState.INCLUDE] regardless of what the filter says, and
     * likewise for stubs and exclusions.
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

                // Iterative depth-first traversal avoids stack overflows
                // on very deep trees and keeps the token check cheap.
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

                        // Hard-excluded directories become LOCKED without
                        // descent. Binary files become LOCKED leaves. Both
                        // are unlockable from the header toggle.
                        val isHardExcluded = file.isDirectory &&
                            file.name in filter.hardExcludedDirectories()
                        val isBinaryFile = !file.isDirectory &&
                            normalizedConfig.excludeBinaries &&
                            file.name.substringAfterLast('.', "").lowercase() in filter.binaryExtensions()

                        if (isHardExcluded || isBinaryFile) {
                            val lockedNode = TreeNode(
                                file, file.isDirectory, node.depth + 1, relativePath, file.name
                            ).apply {
                                locked = true
                                wasLockedOriginally = true
                                state = NodeState.EXCLUDE
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

                        // Apply overrides on top of the filter-derived state.
                        val state = when {
                            relativePath in overrides.includedPaths -> NodeState.INCLUDE
                            relativePath in overrides.stubPaths -> NodeState.STUB
                            relativePath in overrides.excludedPaths -> NodeState.EXCLUDE
                            else -> filterState
                        }

                        if (file.isDirectory) {
                            // Directories are always descended so their
                            // children can be evaluated individually; a
                            // directory's own state is re-derived from its
                            // children in [computeAggregateState] after
                            // the children have been added.
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
                                relativePath !in overrides.includedPaths &&
                                relativePath !in overrides.stubPaths) {
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

                    // Recompute directory aggregates bottom-up after all
                    // children have been added for this directory.
                    node.state = computeAggregateState(node.children)

                    // Reversed so the traversal visits siblings in the
                    // declared sort order; the stack pops from the tail.
                    childDirectories.asReversed().forEach(pendingDirectories::addLast)
                }

                // Root aggregate is the aggregate of its children.
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
                // Swallow errors from superseded scans: if a newer scan has
                // already started, whatever this worker failed on is
                // irrelevant and surfacing it would confuse the user.
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
     * Cancels the in-flight scan, if any. The worker will finish its current
     * directory entry and then exit silently; no [scanAsync] callback is
     * invoked for a cancelled run.
     */
    fun cancelScan() {
        scanToken.incrementAndGet()
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Renders [root] and notifies the caller of the aggregate stats.
     *
     * Kept as a public entry point so [scanAsync]'s `onComplete` callback
     * can delegate without knowing about [renderInternal]'s `fireStats`
     * flag.
     */
    fun renderTree(root: TreeNode) {
        rootNode = root
        renderInternal(fireStats = true)
    }

    /**
     * Rebuilds the entire view hierarchy from [rootNode].
     *
     * Used only on the initial render and after [expandAll] /
     * [collapseAll], because both of those invalidate the visibility of
     * every row at once. Per-state changes use [refreshVisibleRows].
     *
     * @param fireStats when `false`, the caller is expected to compute and
     *   forward stats itself.
     */
    private fun renderInternal(fireStats: Boolean = true) {
        val root = rootNode ?: return

        // Sticky header: only the root row, never any children.
        stickyScroll.removeAllViews()
        val rootRow = buildRow(root, isSticky = true)
        stickyScroll.addView(rootRow, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        // Body: the root's children, rendered recursively into their own
        // wrapper so expand/collapse can toggle a single sub-ViewGroup.
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

        // An empty pendingPulsePath targets the sticky root row, which has
        // just been rebuilt above.
        if (pendingPulsePath == "") {
            pendingPulsePath = null
            pulseRow(rootRow)
        }

        if (fireStats) onStatsChanged(computeStatsFromTree(root))
    }

    /**
     * Adds [node] (and, if it is an expanded directory, its subtree) under
     * [parent].
     *
     * Children of a directory are rendered lazily: an unexpanded directory
     * gets an empty [TreeNode.childrenWrapper] and its subtree is only
     * inflated the first time it is expanded. That keeps initial render
     * cost proportional to the number of *visible* rows.
     */
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

        if (pendingPulsePath == node.relativePath) {
            pendingPulsePath = null
            pulseRow(row)
        }
    }

    /**
     * Renders [node]'s direct children into its [TreeNode.childrenWrapper].
     *
     * ### Lazy sub-scan for previously-LOCKED directories
     *
     * A node flagged [TreeNode.lazyDirectory] (a hard-excluded directory
     * whose contents were never scanned) spawns a sub-scan the first time
     * it is expanded. This is what makes "Show locked" cheap: unlocking
     * flips every LOCKED node to EXCLUDE at once, but the subtree is only
     * materialized when the user actually opens a folder.
     *
     * The sub-scan reuses [scanToken] for cancellation: collapsing the
     * node before the sub-scan finishes increments the token implicitly
     * via [cancelScan], and the sub-scan worker silently drops its result.
     *
     * ### Idempotency
     *
     * Guarded against double-render: the first call inflates the subtree,
     * subsequent calls are no-ops.
     */
    private fun populateChildren(node: TreeNode) {
        val cw = node.childrenWrapper ?: return
        if (cw.childCount > 0) return

        if (node.lazyDirectory) {
            // Show a loading placeholder while the sub-scan runs.
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
                            node.children.forEach { renderNode(it, cw) }
                            node.state = computeAggregateState(node.children)
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
     * Used only by the lazy sub-scan path of [populateChildren]. It shares
     * the same per-entry rules as [scanAsync] (hard-excluded directories
     * and binary files become LOCKED, overrides apply), so the tree looks
     * consistent whether a folder was scanned eagerly or lazily.
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
        ) ?: return out

        for (file in files) {
            if (scanToken.get() != myToken) return out
            val relativePath = "${node.relativePath}/${file.name}"

            val isHardExcluded = file.isDirectory && file.name in filter.hardExcludedDirectories()
            val isBinaryFile = !file.isDirectory &&
                currentConfig.excludeBinaries &&
                file.name.substringAfterLast('.', "").lowercase() in filter.binaryExtensions()

            if (isHardExcluded || isBinaryFile) {
                out.add(
                    TreeNode(file, file.isDirectory, node.depth + 1, relativePath, file.name).apply {
                        locked = true
                        wasLockedOriginally = true
                        state = NodeState.EXCLUDE
                        lazyDirectory = file.isDirectory
                    }
                )
                continue
            }

            val filterState = filter.resolveMode(
                relativePath = relativePath,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isFile) file.length() else 0L,
                respectExcludedPaths = false
            )
            val state = when {
                relativePath in currentOverrides.includedPaths -> NodeState.INCLUDE
                relativePath in currentOverrides.stubPaths -> NodeState.STUB
                relativePath in currentOverrides.excludedPaths -> NodeState.EXCLUDE
                else -> filterState
            }

            if (state == NodeState.EXCLUDE &&
                filterState == NodeState.EXCLUDE &&
                relativePath !in currentOverrides.includedPaths &&
                relativePath !in currentOverrides.stubPaths) {
                continue
            }

            out.add(
                TreeNode(file, file.isDirectory, node.depth + 1, relativePath, file.name).apply {
                    this.state = state
                }
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // In-place refresh of visible rows
    // ------------------------------------------------------------------

    /**
     * Refreshes only the rows that are currently visible, instead of
     * destroying and rebuilding the whole tree.
     *
     * Cost is O(visible) rather than O(all). The redundant-setter guard
     * inside [refreshNodeRow] is what keeps a single toggle from repainting
     * the entire visible tree.
     *
     * @param fireStats whether to recompute and forward [PreviewStats].
     *   Always `true` in the current call sites, because every path that
     *   reaches this method changes the inclusion state and therefore the
     *   aggregates.
     */
    private fun refreshVisibleRows(fireStats: Boolean = true) {
        val root = rootNode ?: return
        refreshNodeVisible(root)
        if (fireStats) onStatsChanged(computeStatsFromTree(root))
    }

    /**
     * Refreshes every rendered descendant of [root] using an explicit
     * stack, plus the root itself (because its aggregate state is what the
     * sticky header renders).
     *
     * Iterative for the same reason as the scanner's own traversal: a
     * deeply nested expanded tree would otherwise recurse once per level
     * and eventually overflow the JVM stack. The walk only follows
     * *expanded* children, so it touches exactly the rows that are
     * actually rendered.
     */
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

    /**
     * Updates the tri-state indicator, icon tint and text color of a single
     * [node]'s row based on its current [NodeState].
     *
     * ### Redundant-setter guard
     *
     * The state icon assignment is guarded on the currently-set drawable so
     * a row whose state has not changed is not touched. The tint and
     * text-color assignments are left unguarded because they are cheap and
     * idempotent.
     *
     * ### LOCKED rows
     *
     * A LOCKED row has no interactive state indicator (the tri-state icon
     * is replaced by a static lock glyph); the method updates the icon and
     * text colors to the muted palette.
     *
     * ### Aggregate rows
     *
     * A directory's state is not stored independently — it is the
     * aggregate of its children, computed by [computeAggregateState]. The
     * method re-derives it on every refresh so the displayed indicator
     * always matches the state a click would propagate to.
     */
    private fun refreshNodeRow(node: TreeNode) {
        if (node.locked) {
            val drawable = UiFactory.run { activity.loadPluginDrawable(R.drawable.ic_state_locked) }
            node.stateIcon?.let { icon ->
                if (node.stateIconDrawable !== drawable) {
                    icon.setImageDrawable(drawable)
                    node.stateIconDrawable = drawable
                }
            }
            node.iconView?.setColorFilter(CodeAssistTheme.TEXT_MUTED)
            node.nameView?.setTextColor(CodeAssistTheme.TEXT_MUTED)
            return
        }

        // Directories show the aggregate of their children; leaves show
        // their own state.
        val displayState = if (node.isDirectory) computeAggregateState(node.children) else node.state

        val drawable = UiFactory.run {
            activity.loadPluginDrawable(
                when (displayState) {
                    NodeState.INCLUDE -> R.drawable.ic_state_include
                    NodeState.STUB -> R.drawable.ic_state_stub
                    NodeState.EXCLUDE, NodeState.LOCKED -> R.drawable.ic_state_exclude
                }
            )
        }
        node.stateIcon?.let { icon ->
            if (node.stateIconDrawable !== drawable) {
                icon.setImageDrawable(drawable)
                node.stateIconDrawable = drawable
            }
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

    /**
     * Builds a single row view for [node].
     *
     * Row anatomy, left to right: optional expand/collapse chevron, the
     * tri-state indicator (or lock icon for LOCKED rows), type icon, display
     * name, and (for files) size label.
     *
     * ### Initial visual state
     *
     * The row is painted immediately after construction via
     * [refreshNodeRow]. Without that call, the state indicator and the lock
     * glyph would be blank until the first user interaction triggered a
     * refresh, which is why a freshly opened tree used to show empty
     * squares until the user touched something.
     *
     * @param isSticky `true` for the pinned header row (root).
     */
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

        // Chevron — wrapped in a 32dp container so the tap target is
        // generous even though the icon itself is 16dp. Files and the
        // sticky row use an invisible spacer of the same width so that
        // the indicator and icon columns line up across every row.
        val hasChildren = node.isDirectory && (node.children.isNotEmpty() || node.lazyDirectory)
        if (hasChildren && !isSticky) {
            val res = if (node.expanded) R.drawable.ic_tech_chevron_down
                      else R.drawable.ic_tech_chevron_right
            val arrowContainer = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                isClickable = !node.locked
                isFocusable = !node.locked
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
            // Spacer that matches the chevron column.
            row.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), 1)
            })
        }

        // Tri-state indicator — a custom ImageView because Android's
        // native CheckBox only supports two states. LOCKED rows get a
        // static lock glyph and no listener.
        val indicatorContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(32)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            isClickable = !node.locked
            isFocusable = !node.locked
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

        // Type icon (folder or file).
        val iconRes = if (node.isDirectory) R.drawable.ic_tech_folder else R.drawable.ic_tech_file
        val icon = ImageView(activity).apply {
            val drawable = UiFactory.run { activity.loadPluginDrawable(iconRes) }
            if (drawable != null) setImageDrawable(drawable)
            else runCatching { setImageResource(iconRes) }
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
        }
        node.iconView = icon
        row.addView(icon)

        // Display name.
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

        // Size label for files only.
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

        // Row-wide tap cycles the state, but not on the sticky header
        // and not on LOCKED rows.
        if (!isSticky && !node.locked) {
            row.isClickable = true
            row.setOnClickListener { cycleState(node) }
        }

        // Paint the initial visual state so the tri-state indicator and
        // the lock glyph are visible from the very first frame, without
        // waiting for a user interaction to trigger a refresh.
        refreshNodeRow(node)

        return row
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /**
     * Advances [node]'s state through the cycle INCLUDE -> STUB -> EXCLUDE
     * -> INCLUDE and refreshes the visible rows.
     *
     * Called for leaf files, for the state indicator of a directory, and
     * for the row-wide tap on any non-sticky, non-locked row. Directory
     * state is derived from children, so the actual propagation happens
     * in [onParentClick].
     */
    private fun cycleState(node: TreeNode) {
        if (node.locked) return
        if (node.isDirectory) {
            onParentClick(node)
            return
        }
        node.state = when (node.state) {
            NodeState.INCLUDE -> NodeState.STUB
            NodeState.STUB -> NodeState.EXCLUDE
            NodeState.EXCLUDE, NodeState.LOCKED -> NodeState.INCLUDE
        }
        refreshVisibleRows(fireStats = true)
        node.rowView?.let { pulseRow(it) }
        propagateStateChange()
        onStatesChanged(currentStates())
    }

    /**
     * Applies the "advance one step" rule to [node] and its whole
     * non-locked subtree.
     *
     * ### The rule
     *
     * The aggregate of [node]'s children is computed first. The click then
     * advances **only the descendants whose current state matches that
     * aggregate**. Descendants that already have a different state (a
     * manual override, for example) are left untouched. This is what
     * preserves a user's manual exclusions when they toggle a mixed
     * folder.
     *
     * ### Recursion into descendants
     *
     * Directories do not carry their own state — it is derived from their
     * children. So writing to a directory's `state` directly has no
     * visible effect: [refreshNodeRow] immediately overwrites it with the
     * aggregate. The only way to "advance" a directory is to advance its
     * descendants, which is exactly what [applyRecursive] does.
     *
     * Without this recursion a folder could be marked INCLUDE while its
     * children stayed EXCLUDE — a state that is impossible to reach by
     * clicking on the folder's own indicator, but that the user *can*
     * reach by clicking a leaf first and then the folder. Recursing into
     * the whole subtree makes the visible result match what the user
     * expected: clicking the folder affects every file inside it.
     *
     * ### LOCKED descendants
     *
     * Ignored entirely: a manual "keep locked" gesture must never be
     * undone by an ancestor click.
     */
    private fun onParentClick(node: TreeNode) {
        if (node.locked) return

        val children = node.children.filter { !it.locked }
        val aggregate = computeAggregateState(children)
        val toState: NodeState = when (aggregate) {
            NodeState.INCLUDE -> NodeState.STUB
            NodeState.STUB -> NodeState.EXCLUDE
            NodeState.EXCLUDE -> NodeState.INCLUDE
            NodeState.LOCKED -> NodeState.EXCLUDE // unreachable
        }

        children.forEach { child ->
            val childAggregate = if (child.isDirectory) computeAggregateState(child.children) else child.state
            if (childAggregate == aggregate) {
                applyRecursive(child, toState)
            }
        }

        refreshVisibleRows(fireStats = true)
        node.rowView?.let { pulseRow(it) }
        propagateStateChange()
        onStatesChanged(currentStates())
    }

    /**
     * Applies [toState] to [node] and every non-locked descendant.
     *
     * Used by [onParentClick] so that toggling a folder advances its whole
     * subtree, not just its direct children. LOCKED descendants are
     * skipped: a manual "keep locked" gesture must never be undone by an
     * ancestor click.
     */
    private fun applyRecursive(node: TreeNode, toState: NodeState) {
        if (node.locked) return
        if (!node.isDirectory) {
            node.state = toState
        } else {
            node.children.forEach { applyRecursive(it, toState) }
        }
    }

    /**
     * Toggles the expansion of [node] and, on the first expand of a
     * previously-locked directory, triggers the lazy sub-scan through
     * [populateChildren].
     *
     * The expansion and collapse are animated when [animationsEnabled] is
     * true; the non-animated branch sets the final state directly, which
     * is what low-end devices need to avoid dropping frames on large
     * trees.
     */
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

    /**
     * Swaps the chevron drawable and, when animations are enabled, rotates
     * it from right (0 degrees) to down (90 degrees) as a visual cue for
     * the expand state.
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

    /**
     * Unlocks or re-locks every originally-locked node in the tree.
     *
     * ### On unlock (show = true)
     *
     * Every node marked [TreeNode.wasLockedOriginally] becomes
     * interactive: `locked` flips to false and its state is reset to
     * EXCLUDE. Its descendants are **not** touched — they were never
     * loaded (the node was `lazyDirectory`), so the first expand will
     * scan them fresh, and the user has to set them explicitly. This is
     * the safety rule: unlocking node_modules must not silently include
     * or exclude any file inside it.
     *
     * ### On re-lock (show = false)
     *
     * Every originally-locked node goes back to its initial blocked
     * state: `locked` flips to true, `lazyDirectory` flips to true again,
     * any children that were loaded while it was unlocked are discarded,
     * and the node is collapsed. This ensures the next time the user
     * re-unlocks it, the subtree is rebuilt from scratch and no stale
     * per-file state survives from the previous unlocked session.
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
                    // Unlock: keep the subtree lazy and untouched. The
                    // user has to expand and mark children manually.
                    node.locked = false
                    node.state = NodeState.EXCLUDE
                } else {
                    // Re-lock: discard any state accumulated while
                    // unlocked, collapse, and mark lazy again so the next
                    // unlock rebuilds the subtree from scratch.
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
            // When unlocking, descendants of a locked node were never
            // loaded, so walking into node.children here is a no-op for
            // them. When re-locking, we walk the (now-cleared) children
            // of the node we just re-locked — also a no-op. In both
            // cases the traversal stays correct.
            node.children.forEach { stack.addLast(it) }
        }
        refreshVisibleRows(fireStats = true)
    }

    /**
     * Notifies the caller of the current per-path state map.
     *
     * Called after every state-changing operation so the configuration
     * dialog can forward the map to the export coordinator without having
     * to poll the tree after every click.
     */
    private fun propagateStateChange() {
        onStatesChanged(currentStates())
    }

    // ------------------------------------------------------------------
    // Aggregate computation
    // ------------------------------------------------------------------

    /**
     * Computes the aggregate state of a list of siblings.
     *
     * LOCKED children are ignored. If every non-LOCKED child is EXCLUDE
     * (or the list is empty), the aggregate is EXCLUDE. Otherwise the
     * aggregate is the most permissive child: INCLUDE if any child is
     * INCLUDE, STUB if any child is STUB and none is INCLUDE.
     *
     * This is the single rule used by the sticky header's aggregate
     * indicator, every directory's aggregate indicator, the parent-click
     * propagation, and the scanner's post-processing of directory nodes.
     */
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

    /**
     * Walks [root]'s rendered subtree and aggregates file count, byte size,
     * and estimated tokens.
     *
     *  - **INCLUDE** files count toward `totalFiles`, `totalBytes`, and
     *    `totalTokens`.
     *  - **STUB** files count toward `totalFiles` and `stubFiles`, but not
     *    toward `totalBytes` or `totalTokens`, because the export only
     *    writes their header and a short marker.
     *  - **EXCLUDE** and **LOCKED** files are not counted.
     *
     * Iterative for the same reason as [refreshNodeVisible]: a deeply
     * nested tree must not overflow the JVM stack while the stats are
     * being recomputed.
     */
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

    /**
     * Expands every non-locked directory and rebuilds the tree.
     *
     * Uses a full rebuild because the visibility of every row changes at
     * once. LOCKED directories are not expanded, because doing so would
     * require a sub-scan per directory and defeat the purpose of the
     * "Show locked" toggle (which is explicitly lazy).
     */
    fun expandAll() {
        val root = rootNode ?: return
        markAllExpanded(root, true)
        expandedPaths.clear()
        collectExpandable(root).forEach { expandedPaths.add(it) }
        renderInternal(fireStats = false)
    }

    /**
     * Collapses every directory and rebuilds the tree.
     */
    fun collapseAll() {
        val root = rootNode ?: return
        markAllExpanded(root, false)
        expandedPaths.clear()
        renderInternal(fireStats = false)
    }

    /**
     * Sets [expanded] on [node] and every non-locked descendant using an
     * explicit [ArrayDeque] stack instead of recursion.
     */
    private fun markAllExpanded(node: TreeNode, expanded: Boolean) {
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.locked) continue
            current.expanded = expanded
            current.children.forEach { stack.addLast(it) }
        }
    }

    /**
     * Returns the relative paths of every non-locked directory that can be
     * expanded (i.e. every directory with at least one child).
     */
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

    /**
     * Brief alpha fade to draw the eye to a row whose inclusion state just
     * changed. No-op when [animationsEnabled] is false.
     */
    private fun pulseRow(row: View) {
        if (!animationsEnabled) return
        row.alpha = 0.3f
        row.animate().cancel()
        row.animate().alpha(1f).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** Formats a byte count as B, KB or MB, matching the export panel. */
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }

    /** Converts density-independent pixels to actual screen pixels. */
    private fun dp(value: Int): Int = CodeAssistTheme.dp(activity, value)

    // ------------------------------------------------------------------
    // Custom scroll views
    // ------------------------------------------------------------------

    /**
     * A specialized [ScrollView] designed to coordinate touch events with
     * its parent container (typically the configuration dialog's main
     * scroll view).
     *
     * 1. Empty-space handoff: taps in the blank area below the tree's
     *    content are rejected so the parent dialog can scroll.
     * 2. Boundary-aware interception: when the tree is at its top or
     *    bottom edge, the gesture is handed off to the parent dialog.
     */
    private class NestedScrollView(context: Context) : ScrollView(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var lastY = 0f

        private fun isTouchInEmptySpace(ev: MotionEvent): Boolean {
            if (ev.actionMasked != MotionEvent.ACTION_DOWN) return false
            val child = getChildAt(0) ?: return false
            return ev.y > child.bottom
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (isTouchInEmptySpace(ev)) {
                // Reject the event so the parent dialog's ScrollView can
                // take over.
                return false
            }
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

    /**
     * A [HorizontalScrollView] that sizes itself to the *content* width when
     * the parent passes `AT_MOST` or `UNSPECIFIED` measure specs.
     */
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
     * Holds both the immutable identity of the entry (`file`,
     * `relativePath`, `displayName`, `isDirectory`, `depth`) and the
     * mutable UI state that [refreshNodeRow] and [toggleExpanded] need to
     * update without re-rendering the row:
     *
     *  - `state` is the [NodeState] the user has settled on. For leaves
     *    it is the actual state; for directories it is either the
     *    aggregate the scanner computed, or the state a click would
     *    advance the matching children to — [refreshNodeRow] re-derives it
     *    from the children on every refresh so the displayed state always
     *    matches what the next click will produce.
     *  - `locked` marks the node as non-interactive (hard-excluded
     *    directory or binary file that has not been unlocked).
     *  - `wasLockedOriginally` is set once by the scanner and never
     *    cleared. It is what [toggleShowLocked] uses to distinguish
     *    nodes that were structurally locked from nodes that the user
     *    happened to have locked in a previous session, so re-locking
     *    only ever touches the former.
     *  - `lazyDirectory` is true for a locked directory whose children
     *    have never been scanned. Expanding such a directory after
     *    unlocking it triggers a sub-scan through [populateChildren].
     *  - `expanded` tracks whether the children wrapper is visible.
     *
     * Instances are created on the worker thread during [scanAsync] and
     * then mutated on the UI thread during rendering. The two phases do
     * not overlap for the same tree, so no synchronization is required.
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
        var wrapper: View? = null
        var rowView: View? = null
        var childrenWrapper: LinearLayout? = null
        var arrow: ImageView? = null
        var stateIcon: ImageView? = null
        var stateIconDrawable: android.graphics.drawable.Drawable? = null
        var iconView: ImageView? = null
        var nameView: TextView? = null
    }
}