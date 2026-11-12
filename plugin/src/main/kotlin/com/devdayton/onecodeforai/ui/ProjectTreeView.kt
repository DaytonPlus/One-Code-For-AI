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
 * Without upward propagation, marking a deep file INCLUDE would update
 * its immediate parent's row (because [refreshNodeRow] recomputes the
 * display state from the aggregate) but leave the grandparent, great-
 * grandparent and root rows stuck on their old display state — their
 * `state` fields would still hold the pre-change value, and their
 * aggregates are computed by reading `child.state`, not by recursively
 * recomputing the child's aggregate.
 *
 * [propagateToAncestors] fixes this by walking up the parent chain and
 * updating each ancestor's `state` field to the aggregate of its
 * children. It stops as soon as an ancestor's aggregate matches its
 * current `state` (because the higher ancestors read this `state` and
 * therefore cannot change either) and it **never** touches the other
 * children of the ancestors. This is what preserves the "sin forzar
 * cambiar otro tipo, solo los que están desmarcados" contract: the
 * ancestors' state moves from EXCLUDE to INCLUDE/STUB as needed, but
 * no sibling is forced to change.
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
 * dialog's cached export map is invalidated — otherwise a previously-
 * included descendant of a now-locked folder would remain in the export.
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
 *    the currently-set **resource ID**, not the Drawable instance.
 *    `UiFactory.loadPluginDrawable` returns a fresh Drawable per call, so
 *    an identity check on the Drawable would always fail and the guard
 *    would never fire. Storing the resource ID lets the guard actually
 *    short-circuit `setImageDrawable`.
 *  - [TreeNode] keeps direct references to its row views so a refresh never
 *    has to search the hierarchy for the widget it needs to update.
 *  - Directory children are populated lazily (see [renderNode] and
 *    [populateChildren]).
 *  - [expandAll] / [collapseAll] toggle the visibility of existing wrappers
 *    in place instead of calling [renderInternal], which would destroy and
 *    recreate every row on the UI thread.
 *  - `cycleState` and `onParentClick` call [propagateStateChange] once and
 *    never redundantly call `onStatesChanged(currentStates())` after it.
 *    The duplicate walk the previous implementation performed is what made
 *    each click cost three full traversals of the tree.
 *
 * ### `currentStates()` returns files only
 *
 * Directories are containers, not exportable entries. Including them in
 * the state map would make
 * [com.devdayton.onecodeforai.core.CodeDumpGenerator] attempt to read
 * them as files and emit a `[File read error: ...]` marker for every
 * directory in the project. The tree header is built from the *paths*
 * of the included files (see `DirectoryTree.writePathsTo`), so the folder
 * structure is inferred without any directory entry in the map. See
 * [currentStates] for the full rationale.
 *
 * @param activity      Host activity used to inflate every view. Must be a
 *                      real activity (not a service context) because the
 *                      tree instantiates platform widgets.
 * @param projectRoot   Canonical root of the tree. The scan never leaves
 *                      this directory.
 * @param onStatesChanged invoked on the UI thread whenever the per-path
 *                      state map changes, so the caller can persist it or
 *                      forward it to the export coordinator. The map
 *                      contains only files (see [currentStates]).
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
     * Returns a snapshot of the current per-file states.
     *
     * ### Directories are excluded on purpose
     *
     * Directories are containers, not exportable entries. Including them
     * in the map would make
     * [com.devdayton.onecodeforai.core.CodeDumpGenerator] attempt to read
     * them as files and emit a `[File read error: ...]` marker for every
     * directory in the project. The tree header is built from the
     * *paths* of the included files (see `DirectoryTree.writePathsTo`),
     * so the folder structure is inferred without any directory entry in
     * the map.
     *
     * ### LOCKED entries are excluded
     *
     * LOCKED entries are not part of the export (the user has not opted
     * into seeing them yet), so including them would inflate the
     * progress denominator with entries the generator is not going to
     * visit.
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
            // Files only. Directories are containers, not exportable
            // entries; see the KDoc above.
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
     *
     * ### Parent links
     *
     * Every child is given a `parent` reference to its enclosing node as
     * it is added. This is what makes [propagateToAncestors] possible: a
     * state change can walk up the parent chain without having to find
     * the path from the root first.
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

                        // Hard-excluded directories become LOCKED without
                        // descent. Binary files become LOCKED leaves. Both
                        // are unlockable from the header toggle.
                        val isHardExcluded = file.isDirectory &&
                            file.name in filter.hardExcludedDirectories()
                        val isBinaryFile = !file.isDirectory &&
                            normalizedConfig.excludeBinaries &&
                            file.name.substringAfterLast('.', "").lowercase() in filter.binaryExtensions()

                        if (isHardExcluded || isBinaryFile) {
                            // The initial scan always creates LOCKED nodes:
                            // the user has not toggled "Show locked" yet
                            // (the toggle starts OFF). Unlocking happens
                            // post-scan via toggleShowLocked().
                            val lockedNode = TreeNode(
                                file, file.isDirectory, node.depth + 1, relativePath, file.name
                            ).apply {
                                locked = true
                                wasLockedOriginally = true
                                state = NodeState.EXCLUDE
                                lazyDirectory = file.isDirectory
                                parent = node
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
                                parent = node
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
                                parent = node
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
     * ### Forced-state inheritance
     *
     * When the sub-scan completes, the node's current `state` (which may
     * have been forced by a folder-level click on an ancestor) is applied
     * to the newly scanned children, unless the node's state is EXCLUDE.
     * Without this inheritance, expanding a folder that was just forced to
     * INCLUDE would revert the new children to their filter-derived
     * (mostly EXCLUDE) states, breaking the "folder click changes
     * everything inside" contract.
     *
     * ### Upward propagation
     *
     * After the sub-scan populates the children, the node's own `state`
     * may change (e.g. it was EXCLUDE and the new children are all
     * INCLUDE). [propagateToAncestors] is called so every ancestor's
     * `state` is updated to reflect the change. Without this, expanding
     * a previously-empty folder would update the folder's own row but
     * leave the parent and grandparents stuck on their old display state.
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
                            // Re-establish the parent link: the children
                            // were created on the worker thread without
                            // a parent reference, and the tree's upward
                            // propagation depends on it.
                            subChildren.forEach { it.parent = node }

                            // Inherit the folder's forced state, if any.
                            // EXCLUDE is the default and needs no
                            // propagation — the filter already produced
                            // EXCLUDE children for an excluded folder.
                            if (node.state != NodeState.EXCLUDE) {
                                node.children.forEach { applyRecursiveForced(it, node.state) }
                            }

                            node.children.forEach { renderNode(it, cw) }
                            node.state = computeAggregateState(node.children)
                            // The folder's own state may have moved to
                            // INCLUDE/STUB now that its children exist;
                            // propagate so ancestors reflect it.
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
     * Used only by the lazy sub-scan path of [populateChildren]. It shares
     * the same per-entry rules as [scanAsync] (hard-excluded directories
     * and binary files become LOCKED, overrides apply), so the tree looks
     * consistent whether a folder was scanned eagerly or lazily.
     *
     * ### showLocked is respected
     *
     * If "Show locked" is currently ON, hard-excluded sub-folders and
     * binary files are created as **unlocked** EXCLUDE nodes instead of
     * LOCKED ones. This is what makes the toggle consistent below the
     * first level: without this, expanding a just-unlocked folder would
     * recreate LOCKED children for the same hard-excluded names, and the
     * user would see locked nodes appearing inside a folder they had
     * explicitly unlocked.
     *
     * ### Every directory child is lazy
     *
     * Sub-directories created here are marked `lazyDirectory = true`,
     * even when they are not hard-excluded. The sub-scan only reads one
     * level, so a sub-directory's children are not known yet; marking it
     * lazy is what makes the next expand trigger its own sub-scan.
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

            val isHardExcluded = file.isDirectory && file.name in filter.hardExcludedDirectories()
            val isBinaryFile = !file.isDirectory &&
                currentConfig.excludeBinaries &&
                file.name.substringAfterLast('.', "").lowercase() in filter.binaryExtensions()

            if (isHardExcluded || isBinaryFile) {
                out.add(
                    TreeNode(file, file.isDirectory, node.depth + 1, relativePath, file.name).apply {
                        // If show-locked is currently ON, create the node
                        // as already unlocked. The show-locked toggle has
                        // already run for the rest of the tree, so
                        // creating a LOCKED child here would produce an
                        // inconsistently locked sub-tree.
                        locked = !showLocked
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
                    // Sub-scan only reads one level, so any directory child
                    // needs another sub-scan when the user expands it.
                    this.lazyDirectory = file.isDirectory
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
     * The state icon assignment is guarded on the currently-set **resource
     * ID**, not on the Drawable instance. `UiFactory.loadPluginDrawable`
     * returns a fresh Drawable per call (the ConstantState is shared, the
     * instance is not), so an identity check on the Drawable would always
     * be true and the guard would never fire. Storing the resource ID lets
     * the guard actually short-circuit `setImageDrawable` when the state
     * has not changed.
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

        // Directories show the aggregate of their children; leaves show
        // their own state.
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
     * ### Dynamic clickability
     *
     * Every interactive surface (chevron, tri-state indicator, row-wide
     * tap) is registered as clickable unconditionally. The `locked` check
     * happens inside the handler, not at construction time, so flipping
     * `locked` from [toggleShowLocked] takes effect immediately on
     * already-rendered rows. This is what allows the "Show locked" toggle
     * to work without a full tree rebuild.
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

        // Chevron — always clickable; the locked check happens inside the
        // handler so unlock via toggleShowLocked takes effect immediately
        // without needing to rebuild the row.
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
            // Spacer that matches the chevron column.
            row.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), 1)
            })
        }

        // Tri-state indicator — always clickable; locked check inside.
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

        // Row-wide tap — always clickable; locked check inside.
        if (!isSticky) {
            row.isClickable = true
            row.setOnClickListener {
                if (!node.locked) cycleState(node)
            }
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
     * Advances [node]'s state and refreshes the visible rows.
     *
     * - For a leaf, cycles INCLUDE -> STUB -> EXCLUDE -> INCLUDE.
     * - For a directory, delegates to [onParentClick], which uses the same
     *   three-step cycle but forces the next state onto every descendant.
     *
     * ### Lazy directories
     *
     * A directory that has never been loaded (a hard-excluded folder that
     * was just unlocked via the "Show locked" toggle) has no children
     * yet. Its aggregate is EXCLUDE by construction, so calling
     * [onParentClick] on it would be a silent no-op. Instead, the first
     * click on the indicator expands the directory, which triggers the
     * sub-scan and gives the user a visible response. Subsequent clicks
     * will cycle the loaded children normally.
     *
     * This is the only place where "clicking a directory" is treated as
     * "expand the directory": everywhere else the click applies the state
     * cycle to the loaded children.
     *
     * ### Upward propagation
     *
     * After a leaf's state changes, [propagateToAncestors] walks up the
     * parent chain and updates each ancestor's `state` to its new
     * aggregate. Without this, the leaf would update visually, its
     * immediate parent would update visually (because [refreshNodeRow]
     * recomputes the display state from the aggregate), but the
     * grandparent and higher ancestors would stay stuck on their old
     * display state — their `state` fields would still hold the pre-change
     * value, and their aggregates are computed by reading `child.state`,
     * not by recursively recomputing the child's aggregate.
     */
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
        // Update ancestors' state fields before refreshing the rows, so
        // the grandparent-and-up rows read the correct child.state when
        // their aggregate is recomputed.
        propagateToAncestors(node)
        refreshVisibleRows(fireStats = true)
        node.rowView?.let { pulseRow(it) }
        // propagateStateChange already calls onStatesChanged(currentStates());
        // a duplicate explicit call here would walk the entire tree a
        // second time on every click.
        propagateStateChange()
    }

    /**
     * Applies the folder-cycle rule to [node] and its entire non-locked
     * subtree.
     *
     * ### Three-step cycle
     *
     * The aggregate of the folder determines the next state, using the
     * same INCLUDE -> STUB -> EXCLUDE -> INCLUDE cycle as a leaf. The
     * next state is applied **uniformly** to every non-locked descendant
     * (folders and files), so after the click the folder and its entire
     * subtree share the same state. This is what makes the folder-level
     * tri-state indicator trustworthy: it never disagrees with its
     * children.
     *
     * ### All descendants are forced
     *
     * Unlike a "only matching children" propagation, this method forces
     * every descendant to the target state. That means a user who had
     * manually excluded a child inside an otherwise-included folder will
     * see that child become INCLUDE when the folder is cycled to INCLUDE.
     * This is a deliberate trade-off in favour of uniformity: the folder
     * click is a bulk operation, and the user expects bulk results.
     * Individual overrides are still reachable by clicking the child
     * directly after the folder click.
     *
     * ### LOCKED descendants
     *
     * Ignored entirely: a manual "keep locked" gesture must never be
     * undone by an ancestor click.
     *
     * ### All-children-locked guard
     *
     * If the folder has no non-locked children (e.g. every child is
     * LOCKED), the click is a no-op. Forcing a state on a folder whose
     * every entry is locked would set `node.state` to a value the
     * aggregate would immediately overwrite to EXCLUDE, and the user
     * would see the row's indicator flip silently back to EXCLUDE with
     * no children changing. Ignoring the click is more honest.
     *
     * ### Upward propagation
     *
     * After the folder's state is forced, [propagateToAncestors] walks
     * up the parent chain and updates each ancestor's `state` to its new
     * aggregate. This is what makes a deep folder click visible all the
     * way up to the root.
     */
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

        // Force every non-locked descendant to the target state, at every
        // depth. Folders are included in the force so their `state` field
        // stays in sync with the aggregate their children will produce.
        nonLockedChildren.forEach { applyRecursiveForced(it, toState) }
        node.state = toState

        // Update ancestors before refreshing the rows, so the ancestors'
        // displays reflect the new aggregate of this folder.
        propagateToAncestors(node)

        refreshVisibleRows(fireStats = true)
        node.rowView?.let { pulseRow(it) }
        // See cycleState: propagateStateChange already fires the state
        // callback; the duplicate call was removed.
        propagateStateChange()
    }

    /**
     * Applies [toState] to [node] and every non-locked descendant,
     * including directories.
     *
     * Unlike the previous `applyRecursive`, this method sets the folder's
     * own `state` too, so the folder's displayed tri-state (which is
     * derived from its children's aggregate) matches the value that the
     * user's click produced. Without setting the folder state explicitly,
     * a folder whose children are all forced to STUB would display as
     * STUB anyway (because the aggregate is STUB), but its own `state`
     * field would remain stale — and any code that reads the folder's
     * state directly (e.g. a future feature, or a persisted config map
     * that ever includes folders) would see an inconsistent value.
     *
     * LOCKED descendants are skipped: a manual "keep locked" gesture
     * must never be undone by an ancestor click.
     */
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
     * ### Why this is necessary
     *
     * When a node's state changes, only its own `state` field is updated
     * by [cycleState] / [onParentClick]. The node's parent (and grandparent,
     * etc.) has its own `state` field that can become stale: for example,
     * if the parent had every child at EXCLUDE, its `state` was EXCLUDE;
     * if one of those children now becomes INCLUDE, the parent's new
     * aggregate is INCLUDE, but its `state` field is still EXCLUDE.
     *
     * That stale `state` is invisible in the parent's row, because
     * [refreshNodeRow] recomputes the display state from the aggregate
     * every time. But it **does** affect the ancestor's aggregation of its
     * own children: [computeAggregateState] reads `child.state` directly,
     * not the recursively-recomputed display state. Without this
     * propagation, marking a deep file INCLUDE would update that file's
     * row and its immediate parent's row, but leave the grandparent, great-
     * grandparent, and root rows stuck on their old display state.
     *
     * ### Propagation rule
     *
     * The method walks up the parent chain. For each ancestor it
     * recomputes the aggregate of its children. If the aggregate matches
     * the ancestor's current `state`, the walk **stops**: the higher
     * ancestors read this `state`, which did not change, so they cannot
     * change either. If the aggregate does not match, the ancestor's
     * `state` is updated and the walk continues.
     *
     * ### What it does NOT do
     *
     * It never touches the ancestor's other children. This is the
     * contract the user asked for: "sin forzar cambiar otro tipo, solo
     * los que están desmarcados". The ancestor's `state` moves from
     * EXCLUDE to INCLUDE/STUB (or vice versa) as needed, but no sibling
     * is forced to change. If another sibling already keeps the
     * aggregate at INCLUDE, the ancestor does not even need to be
     * updated and the walk stops at that point.
     */
    private fun propagateToAncestors(node: TreeNode) {
        var current = node.parent
        while (current != null) {
            val newAggregate = computeAggregateState(current.children)
            if (current.state == newAggregate) {
                // The ancestor's state did not change; its own ancestors
                // read this state and therefore cannot change either.
                // Stopping here keeps the propagation O(depth) even on
                // trees where most of the ancestor chain is already in
                // the correct state.
                return
            }
            current.state = newAggregate
            current = current.parent
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
     * ### On unlock (`show = true`)
     *
     * Every node marked [TreeNode.wasLockedOriginally] becomes interactive:
     * `locked` flips to false and its state is reset to EXCLUDE. Its
     * descendants are **not** touched — they were never loaded (the node
     * was `lazyDirectory`), so the first expand will scan them fresh, and
     * the user has to set them explicitly. Unlocking node_modules must not
     * silently include or exclude any file inside it.
     *
     * ### On re-lock (`show = false`)
     *
     * Every originally-locked node goes back to its initial blocked state:
     * `state` resets to EXCLUDE, `locked` flips to true, `lazyDirectory`
     * flips to true again, any children that were loaded while it was
     * unlocked are discarded, and the node is collapsed. This is the
     * "deselects everything" contract: the locked node and everything
     * below it return to EXCLUDE and will not appear in the export.
     *
     * ### Bottom-up aggregate recomputation
     *
     * After the re-lock, every node's `state` may have changed. The
     * aggregates of the ancestors of the affected nodes are recomputed
     * bottom-up via [recomputeAllAggregates]. Without this, a parent that
     * had a locked child now unlocked and included would still think it
     * was EXCLUDE, and its own row would not update.
     *
     * ### Cache invalidation
     *
     * `onStatesChanged` is fired at the end so the dialog's cached export
     * map matches the new tree. Without this, toggling "Show locked" OFF
     * after having previously included one of its descendants would leave
     * a stale INCLUDE entry in the cache, and Generate would export a
     * file the user can no longer see in the tree.
     */
    fun toggleShowLocked(show: Boolean) {
        if (showLocked == show) return
        showLocked = show
        val root = rootNode ?: return

        // Phase 1: unlock / re-lock, in pre-order.
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

        // Phase 2: recompute every directory's aggregate bottom-up. Some
        // ancestors may now have a different aggregate because a locked
        // child was re-locked (state flipped to EXCLUDE) or unlocked
        // (state flipped to EXCLUDE too, but `locked` flipped to false
        // so it now counts towards the aggregate — which can move a
        // previously "all children locked" parent from EXCLUDE to a
        // different aggregate).
        recomputeAllAggregates()

        refreshVisibleRows(fireStats = true)
        // Fire the state-changed callback so the dialog's cached export
        // map is invalidated when previously-exported descendants of a
        // locked folder become locked again. Without this, the cache
        // keeps the stale INCLUDE/STUB entries and Generate would export
        // entries the tree no longer shows.
        propagateStateChange()
    }

    /**
     * Recomputes every non-locked directory's `state` from its children,
     * bottom-up.
     *
     * Implemented as a post-order iterative traversal (push a marker on
     * the way down, recompute on the way back up) because a recursive
     * implementation would risk a StackOverflowError on pathologically
     * deep trees, and a naive top-down traversal would recompute a
     * parent's aggregate before its children had been updated.
     *
     * Only non-locked directories are recomputed. Locked nodes keep
     * EXCLUDE as their `state`, and leaves keep whatever state the user
     * (or the filter) gave them — this method must not touch either.
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
     * propagation, the scanner's post-processing of directory nodes, and
     * the upward propagation in [propagateToAncestors].
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
     * Expands every non-locked directory, in place.
     *
     * Used to call [renderInternal], which destroyed and recreated every
     * row on the UI thread. That produced a visible flicker and a
     * 200-500ms main-thread block on large projects. The current
     * implementation walks the existing wrappers and only flips their
     * visibility, which is O(visible) and produces no flicker.
     *
     * Lazy directories with no loaded children stay collapsed: expanding
     * them would require a per-folder sub-scan, which is a user-initiated
     * action triggered by a direct click, not by a bulk operation.
     */
    fun expandAll() {
        val root = rootNode ?: return
        expandedPaths.clear()
        collectExpandable(root).forEach { expandedPaths.add(it) }
        root.children.forEach { toggleAllWrappers(it, expand = true) }
    }

    /**
     * Collapses every directory, in place. Same rationale as [expandAll].
     */
    fun collapseAll() {
        val root = rootNode ?: return
        expandedPaths.clear()
        root.children.forEach { toggleAllWrappers(it, expand = false) }
    }

    /**
     * Toggles the visibility of [node]'s children wrapper in place.
     *
     * Only existing wrappers are touched: a directory whose subtree was
     * never materialized (because it was collapsed when the tree was
     * first rendered) has no wrapper, so it is left as-is. This matches
     * the previous full-rebuild behavior, which also only rendered rows
     * for nodes that had been expanded at render time.
     *
     * The chevron of each toggled directory is updated to the target
     * state **without animation**. Bulk expand/collapse should be
     * instantaneous; animating every chevron in the tree would produce a
     * cascade of rotations that is strictly worse than an instant update.
     *
     * `node.expanded` is kept in sync with the wrapper's visibility, so a
     * subsequent direct click on a chevron behaves correctly: it will
     * collapse an expanded wrapper, or expand a collapsed one, without
     * needing a double click.
     */
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

    /**
     * Returns the relative paths of every non-locked directory that can be
     * expanded (i.e. every directory with at least one child). Excludes
     * lazy-empty directories, matching [expandAll]'s policy of not
     * triggering per-folder sub-scans from a bulk operation.
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
     *    matches what the next click will produce. The field is also
     *    kept up to date by [propagateToAncestors], so an ancestor's
     *    aggregation of its children sees the correct value.
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
     *  - `parent` is the enclosing [TreeNode], set when the node is added
     *    to its parent's children list. Used by [propagateToAncestors] to
     *    walk up the parent chain on a state change without having to
     *    find the path from the root first. Null for the root node.
     *  - `stateIconResId` is the resource ID of the drawable currently
     *    assigned to `stateIcon`. Used by [refreshNodeRow] as a fast
     *    redundant-setter guard: `UiFactory.loadPluginDrawable` returns a
     *    fresh Drawable instance on every call, so an identity check on
     *    the Drawable would always be true and the guard would never fire.
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