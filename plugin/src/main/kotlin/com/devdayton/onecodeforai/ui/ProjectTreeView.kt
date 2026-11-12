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
import android.view.ViewConfiguration
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

/**
 * Interactive preview of the project tree shown inside the configuration
 * dialog.
 *
 * ### Purpose
 * Renders every entry that matches the current filters, letting the user
 * include or exclude files and folders before the export runs. Excluded
 * entries are written into [FilterConfig.excludedPaths], which is the same
 * state the export pipeline reads from — so what the user sees is exactly
 * what will be included in the dump.
 *
 * ### Layout structure
 * The view is a three-part vertical stack:
 *
 *  1. **[stickyScroll]**: a horizontally-scrollable header that always shows
 *     the root row, pinned above the scrolling content so the "include all"
 *     checkbox stays reachable no matter how far the user scrolls.
 *  2. **[dividerView]**: a 1dp separator.
 *  3. **[verticalScroll]**: the scrollable body. It wraps a
 *     [WrapHorizontalScrollView] that lets long paths extend past the right
 *     edge without forcing the whole row to wrap.
 *
 * The two horizontal scroll views are synchronized via
 * [isSyncingScroll], so the sticky header always lines up with the body
 * column even after the user pans left or right.
 *
 * ### Scanning
 * [scanAsync] walks the project on a background thread and reports progress
 * back on the UI thread. Cancellation is token-based: every call to
 * [scanAsync] increments [scanToken] and captures its own copy, so a new
 * scan implicitly cancels the previous one without any flags or thread
 * interruption. [cancelScan] simply bumps the token, which is enough to
 * make the in-flight worker drop its result on the floor.
 *
 * The walk is **iterative** (an explicit [ArrayDeque] of pending
 * directories) rather than recursive, because a deliberately deep directory
 * tree on network storage would otherwise blow the stack on some devices.
 * The same constraint applies to every other traversal in this file
 * (`refreshExclusionFlags`, `computeStatsFromTree`, `markAllExpanded`,
 * `collectExpandable`, `refreshNodeVisible`): each one uses an explicit
 * `ArrayDeque` stack instead of recursion so that a pathological tree
 * (`node_modules` with hundreds of nested levels, generated source trees,
 * deeply nested submodule paths) can never cause a `StackOverflowError`
 * while the tree is being rendered or the stats recomputed.
 *
 * ### Exclusion semantics
 * Exclusions are stored as a flat list of relative paths in
 * [FilterConfig.excludedPaths]. The contract is:
 *
 *  - Excluding a folder implicitly excludes every child. Children are **not**
 *    individually added to the list; instead [TreeNode.ancestorExcluded] is
 *    computed top-down after any change.
 *  - Re-including a child inside an excluded folder removes the ancestor
 *    from the list *and* re-adds the sibling subtrees that the ancestor
 *    exclusion had implicitly covered. That is what [setNodeIncluded]
 *    implements in its `include = true` branch, and it mirrors the negation
 *    behavior of `.gitignore`.
 *
 * ### Sticky header semantics
 * The pinned root row is a **summary of the tree below it**, not an
 * independent toggle. Its checkbox reflects "at least one direct child is
 * included" (see [hasAnyIncludedNode]) rather than the previous "nothing
 * is excluded" rule, so unchecking a single child no longer unchecks the
 * root as long as some other child is still included. Clicking the sticky
 * checkbox still performs a bulk operation in both directions — see
 * [setAllIncluded] — but the visual state shown is what the user would
 * expect from a normal parent-child checkbox group.
 *
 * ### Performance
 * Three optimizations keep large trees responsive:
 *
 *  - [refreshVisibleRows] updates the checkbox, icon and text color of the
 *    currently visible rows in place, instead of tearing down and
 *    rebuilding the whole tree when a single checkbox flips. Without it,
 *    toggling the root checkbox on a project with thousands of nodes would
 *    drop frames for seconds.
 *  - [refreshVisibleRows] and [refreshNodeRow] guard every `isChecked`
 *    assignment behind a "value actually changed" check. A single child
 *    toggle therefore repaints only the rows whose visual state differs
 *    from the model, instead of re-writing every visible checkbox on every
 *    refresh — a measurable win on wide project trees where a checkbox
 *    toggle happens with dozens or hundreds of rows on screen.
 *  - [TreeNode] keeps direct references to its row views so a refresh
 *    never has to search the hierarchy for the widget it needs to update.
 *
 * ### Related views
 *  - [com.devdayton.onecodeforai.ui.ConfigurationDialog] embeds this view and
 *    owns the surrounding form.
 *  - [NestedScrollView] and [WrapHorizontalScrollView] are private helper
 *    subclasses that solve the nested-scroll and wrap-content measurement
 *    problems respectively; their KDocs live next to the subclasses.
 *
 * @param activity        Host activity used to inflate every view. Must be a
 *                        real activity (not a service context) because the
 *                        tree instantiates platform widgets.
 * @param projectRoot     Canonical root of the tree. The scan never leaves
 *                        this directory.
 * @param onExcludedPathsChanged invoked on the UI thread whenever the set of
 *                        excluded relative paths changes, so the caller can
 *                        mirror the new list into its own [FilterConfig].
 * @param onStatsChanged  invoked on the UI thread whenever the aggregate
 *                        [PreviewStats] change (initial render, checkbox
 *                        flip, expand/collapse when it affects visibility).
 */
class ProjectTreeView(
    private val activity: Activity,
    private val projectRoot: File,
    private val onExcludedPathsChanged: (List<String>) -> Unit,
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
     * Relative paths of currently-expanded directories.
     *
     * Persisted across re-renders so that expanding a folder, changing a
     * filter, and re-scanning does not collapse the user's manual expansion
     * state.
     */
    private val expandedPaths = mutableSetOf<String>()

    /**
     * Relative path of a row that should flash once its view has been
     * attached. Used to draw the eye to the row that just changed inclusion
     * state without blocking the caller on layout.
     *
     * An empty string means "pulse the root (sticky) row".
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
     * [PERF] Suppresses the checkbox listeners while [refreshNodeRow] is
     * updating `isChecked` programmatically. Without this guard, updating a
     * checkbox in response to a state change would re-trigger
     * [setNodeIncluded] with the new value, creating an echo that flips the
     * exclusion list back and forth on every refresh.
     */
    private var isRefreshingRows = false

    /**
     * [PERF] Live reference to the sticky header's "include all" checkbox, so
     * [refreshVisibleRows] can update it in place instead of rebuilding the
     * header.
     */
    private var stickyCheckbox: CheckBox? = null

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
     * Generation counter used to cancel in-flight scans.
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
     *
     * Each row is added as a direct child and hidden/shown by the
     * expand/collapse logic rather than being re-inflated.
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
     * Vertical scroller wrapping [horizontalScroll]. Owns the tall part of
     * the layout and coordinates with the parent dialog through
     * [NestedScrollView].
     */
    private val verticalScroll = NestedScrollView(activity).apply {
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        addView(horizontalScroll, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /**
     * Sticky header showing the root row. Never scrolls vertically, so the
     * "include all" checkbox stays reachable regardless of body scroll
     * position.
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
     * full width, which keeps the "tap the row to toggle" hit target
     * predictable even for a file with a one-character name. Values are
     * approximated from [ConfigurationDialog]'s hard-coded padding.
     */
    private val rowMinWidth: Int = run {
        val screenW = activity.resources.displayMetrics.widthPixels
        val dialogW = (screenW * 0.94f).toInt()
        (dialogW - dp(18 * 2 + 6 * 2)).coerceAtLeast(0)
    }

    init {
        // Keep the sticky header and the scrollable body in lockstep when
        // either side is panned horizontally. The `isSyncingScroll` guard is
        // what prevents the sync call on one side from bouncing back into
        // the other side and creating an infinite loop.
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

    // ------------------------------------------------------------------
    // Async scan
    // ------------------------------------------------------------------

    /**
     * Walks [projectRoot] on a background thread and delivers the resulting
     * tree to [onComplete] on the UI thread.
     *
     * Cancellation is implicit: calling this method again before the
     * previous walk has finished invalidates the older worker via
     * [scanToken], and the older worker silently drops its result. That makes
     * "user changed a filter" trivial to handle: the caller just calls
     * [scanAsync] again and the previous walk becomes a no-op.
     *
     * The tree itself is built on the worker thread; only [onProgress] and
     * [onComplete] / [onError] are dispatched to the UI thread, so a large
     * scan never blocks the dialog.
     *
     * @param config      Filter configuration to apply. The value is
     *                    normalized internally so a misconfigured caller
     *                    cannot crash [FileFilter].
     * @param onProgress  invoked periodically on the UI thread with the
     *                    number of files scanned so far. The callbacks
     *                    respect [PROGRESS_FILE_INTERVAL] and
     *                    [PROGRESS_INTERVAL_MS] to avoid spamming the UI.
     * @param onComplete  invoked on the UI thread with the fully-built tree
     *                    root. The caller is expected to call [renderTree].
     * @param onError     invoked on the UI thread if the scan throws. A new
     *                    scan will not be started automatically; the caller
     *                    is responsible for retrying.
     */
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

                // Iterative depth-first traversal avoids stack overflows on
                // very deep trees and keeps the token check cheap.
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
                        // Exclusion flags are computed top-down so an excluded
                        // ancestor propagates to every descendant without the
                        // excludedPaths list having to enumerate them all.
                        val selfExcluded = normalizedConfig.excludedPaths.any { it == relativePath }
                        val ancestorExcluded = node.selfExcluded || node.ancestorExcluded

                        if (file.isDirectory) {
                            // `respectExcludedPaths = false` so the tree still
                            // shows directories that the user manually
                            // excluded — otherwise the user could not
                            // re-include them from this very view.
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

                    // Reversed so the traversal visits siblings in the
                    // declared sort order; the stack pops from the tail.
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
     * Kept as a public entry point so [scanAsync]'s `onComplete` callback can
     * delegate without knowing about [renderInternal]'s `fireStats` flag.
     */
    fun renderTree(root: TreeNode) {
        rootNode = root
        renderInternal(fireStats = true)
    }

    /**
     * Rebuilds the entire view hierarchy from [rootNode].
     *
     * Used only on the initial render and after [expandAll] / [collapseAll],
     * because both of those invalidate the visibility of every row at once
     * and an in-place refresh would be more expensive than a clean rebuild.
     * Per-checkbox changes use [refreshVisibleRows] instead.
     *
     * @param fireStats when `false`, the caller is expected to compute and
     *   forward stats itself. [expandAll] and [collapseAll] pass `false`
     *   because visibility does not affect the aggregates; re-emitting them
     *   would only cause redundant work in the parent dialog.
     */
    private fun renderInternal(fireStats: Boolean = true) {
        val root = rootNode ?: return

        // Sticky header: only the root row, never any children.
        stickyScroll.removeAllViews()
        stickyCheckbox = null
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
        // just been rebuilt above — so this is the only place that pulse
        // can be applied.
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
     * Children of a directory are rendered **lazily**: an unexpanded
     * directory gets an empty [TreeNode.childrenWrapper] and its subtree is
     * only inflated the first time it is expanded. That is what keeps
     * initial render cost proportional to the number of *visible* rows
     * rather than to the total number of nodes in the project.
     *
     * Recursion is bounded here by the number of *expanded* directories,
     * which is user-controlled and cannot exceed the number of levels the
     * user actually navigated into. Unlike [scanAsync] and the tree
     * traversal helpers, this is not a hot path on pathological trees: a
     * user who deliberately expands hundreds of nested levels has already
     * paid that cost by tapping through them.
     */
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

    /**
     * Renders [node]'s direct children into its [TreeNode.childrenWrapper].
     *
     * Guarded against double-render: the first call inflates the subtree,
     * subsequent calls are no-ops. This is what makes the lazy expansion
     * pattern work — [renderNode] can safely call this whenever a node is
     * expanded without having to track whether the children have already
     * been created.
     */
    private fun populateChildren(node: TreeNode) {
        val cw = node.childrenWrapper ?: return
        if (cw.childCount > 0) return
        node.children.forEach { child -> renderNode(child, cw) }
    }

    // ------------------------------------------------------------------
    // [PERF] In-place refresh of visible rows
    // ------------------------------------------------------------------

    /**
     * [PERF] Refreshes only the rows that are currently visible, instead of
     * destroying and rebuilding the whole tree.
     *
     * Cost is O(visible) rather than O(all). Called from [setNodeIncluded]
     * and [setAllIncluded], where previously every call went through
     * `renderInternal(fireStats = true)` — recreating hundreds or thousands
     * of Views just to reflect a change in one subtree.
     *
     * ### Sticky header state
     * The sticky checkbox is updated to [hasAnyIncludedNode], not to
     * `excludedPaths.isEmpty()`. The two differ whenever *some* — but not
     * all — children are excluded, and the new rule is what makes the
     * header behave like a normal parent checkbox: unchecking a single
     * child no longer unchecks the root while other children remain
     * included. See the class-level *Sticky header semantics* note.
     *
     * ### Redundant-setter guard
     * The `if (cb.isChecked != newState)` check is not cosmetic. Without
     * it, a single child toggle would walk every visible row and reassign
     * `isChecked` on each of them, even though the vast majority still hold
     * the same value. Each assignment goes through `CompoundButton`'s
     * property setter, invalidates the drawable state, and (on some OEM
     * builds) triggers a layout pass — measurable on trees with dozens of
     * rows on screen. Guarding the assignment turns a bulk repaint into a
     * paint of only the rows whose state actually changed.
     *
     * @param fireStats whether to recompute and forward [PreviewStats].
     *   Always `true` in the current call sites, because every path that
     *   reaches this method changes the inclusion state and therefore the
     *   aggregates.
     */
    private fun refreshVisibleRows(fireStats: Boolean = true) {
        val root = rootNode ?: return
        stickyCheckbox?.let { cb ->
            val newState = hasAnyIncludedNode()
            // [PERF] Only touch the widget when the visual state actually
            // changes. See the KDoc section "Redundant-setter guard" for
            // the rationale.
            if (cb.isChecked != newState) {
                isRefreshingRows = true
                cb.isChecked = newState
                isRefreshingRows = false
            }
        }
        refreshNodeVisible(root)
        if (fireStats) onStatsChanged(computeStatsFromTree(root))
    }

    /**
     * Refreshes every rendered descendant of [root] using an explicit stack.
     *
     * Iterative for the same reason as [refreshExclusionFlags]: a deeply
     * nested expanded tree (a user who expanded thirty levels of
     * `node_modules` to find a stray file) would otherwise recurse once per
     * level and eventually overflow the JVM stack. The walk only follows
     * *expanded* children, so it touches exactly the rows that are actually
     * rendered — unrendered subtrees are skipped and will pick up the fresh
     * state automatically the first time they are expanded.
     */
    private fun refreshNodeVisible(root: TreeNode) {
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
     * Updates the checkbox, icon tint and text color of a single [node]'s
     * row based on its current exclusion state.
     *
     * The checkbox toggle is wrapped in [isRefreshingRows] so that the
     * programmatic `isChecked = included` assignment does not fire the
     * `setOnCheckedChangeListener` installed in [buildRow], which would
     * otherwise call [setNodeIncluded] and re-trigger the whole refresh.
     *
     * ### Redundant-setter guard
     * As in [refreshVisibleRows], the `isChecked` assignment is guarded by
     * an equality check so a row whose state has not changed is not
     * touched. Combined with the same guard on the sticky checkbox, this
     * is what keeps a single child toggle from repainting the entire
     * visible tree. The tint and text-color assignments are left
     * unguarded because they are cheap and idempotent — `setColorFilter`
     * and `setTextColor` do not invalidate layout, only the drawable
     * state, which is already the minimum repaint cost.
     */
    private fun refreshNodeRow(node: TreeNode) {
        val included = !node.selfExcluded && !node.ancestorExcluded
        node.checkbox?.let { cb ->
            // [PERF] Skip redundant setter when the state already matches.
            // See this method's KDoc section "Redundant-setter guard".
            if (cb.isChecked != included) {
                isRefreshingRows = true
                cb.isChecked = included
                isRefreshingRows = false
            }
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
    // Row construction
    // ------------------------------------------------------------------

    /**
     * Builds a single row view for [node].
     *
     * Row anatomy, left to right: optional expand/collapse chevron, optional
     * checkbox, type icon, display name, and (for files) size label. Each
     * widget is stored on the node so [refreshNodeRow] can update it
     * without a lookup.
     *
     * The row is made clickable in the non-sticky case so tapping anywhere
     * outside the chevron toggles inclusion. This is a deliberate ergonomic
     * choice — the checkbox itself is only 36dp wide, which would be a poor
     * primary hit target on a phone.
     *
     * @param isSticky `true` for the pinned header row (root). Sticky rows
     *   skip the chevron, use the "include all" checkbox handler, and never
     *   respond to row-wide taps. The sticky checkbox reflects
     *   [hasAnyIncludedNode] rather than `excludedPaths.isEmpty()` — see
     *   the class-level *Sticky header semantics* note for why.
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
        // generous even though the icon itself is 16dp. Files and root use
        // an invisible spacer of the same width so that the checkbox and
        // icon columns line up across every row.
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
            // Spacer that matches the chevron column so subsequent widgets
            // stay aligned regardless of whether the row can expand.
            row.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), 1)
            })
        }

        // Checkbox — enabled for every node except the non-sticky root.
        // The root's "include all" affordance lives on the sticky header.
        if (isSticky || !isRoot) {
            val cb = CheckBox(activity).apply {
                // Sticky header reflects "at least one direct child is
                // included", NOT "nothing is excluded". See the class-level
                // *Sticky header semantics* note.
                val included = if (isSticky) hasAnyIncludedNode()
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
                // The platform checkbox indicator is shrunk via zero padding
                // and the fixed 36x32dp container; the hit target is the
                // container, not the drawn indicator, so taps near the edge
                // register reliably. A previous version also applied
                // `scaleX = scaleY = 0.78f` to the widget, which visually
                // shrank the indicator but broke text alignment with the
                // rest of the row and made the drawn size diverge from the
                // touchable size (a tap on the visual edge hit the button,
                // but the button looked smaller than it was). The scale
                // factors are gone; the padding and the container are what
                // compress the indicator now, keeping the visual and hit
                // areas coherent.
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER
                includeFontPadding = false
                text = ""
                compoundDrawablePadding = 0
                setOnCheckedChangeListener { _, checked ->
                    // [PERF] Ignore programmatic updates from refreshNodeRow.
                    if (isRefreshingRows) return@setOnCheckedChangeListener
                    // For the sticky header the new value of the checkbox
                    // is the *target* state for the whole tree: checked
                    // means "include every child", unchecked means
                    // "exclude every child". `setAllIncluded` takes that
                    // flag directly instead of inferring it from the
                    // current exclusion list, because the list and the
                    // visual state no longer agree after the sticky-header
                    // fix (some-but-not-all-children included shows a
                    // checked box while the list is non-empty).
                    if (isSticky) setAllIncluded(checked) else setNodeIncluded(node, checked)
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

        // Type icon (folder or file).
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

        // Display name — the root gets a trailing slash so it reads as a
        // directory, and bold styling so the sticky header stands out from
        // the body rows.
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

        // Size label for files only; directories do not have a meaningful
        // single size to show.
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

        // Row-wide tap toggles inclusion, but not on the sticky header
        // (which has its own dedicated checkbox). The nested click listeners
        // on the chevron and checkbox take precedence over this one.
        if (!isSticky) {
            row.isClickable = true
            row.setOnClickListener {
                node.checkbox?.let { it.isChecked = !it.isChecked }
            }
        }

        return row
    }

    // ------------------------------------------------------------------
    // Actions
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

    /**
     * Applies an inclusion decision to [node], updating
     * [FilterConfig.excludedPaths] and refreshing the visible rows.
     *
     * ### Including
     * Walks from the node up to the root and removes every ancestor from
     * the excluded list, since including a child of an excluded folder
     * implies the folder is no longer fully excluded. Because the ancestor
     * exclusion was implicit (a single path in the list covered the whole
     * subtree), the sibling subtrees it used to cover have to be
     * re-excluded explicitly here: the loop over `parts` re-adds each
     * sibling of every ancestor that was originally excluded. That mirrors
     * the negation semantics of `.gitignore` and keeps the list minimal.
     *
     * ### Excluding
     * Adds the node's path and drops any explicit child exclusions, since
     * the ancestor's exclusion now subsumes them. Keeping the children in
     * the list would be redundant and would break the ancestor/subtree
     * invariant that [refreshExclusionFlags] depends on.
     *
     * The aggregate stats are recomputed once at the end via
     * [refreshVisibleRows] rather than incrementally, because the
     * sibling re-exclusion loop above can add or remove an arbitrary number
     * of paths.
     *
     * ### Sticky header propagation
     * The sticky checkbox is not updated here directly. Instead,
     * [refreshVisibleRows] reads [hasAnyIncludedNode] and reflects the
     * result on the header, so a single child toggle leaves the header
     * checked while at least one other child is still included, and
     * automatically unchecks it when the last included child is excluded.
     * That is the children-to-parent direction of the standard checkbox
     * group behavior described in the class-level *Sticky header
     * semantics* note.
     */
    private fun setNodeIncluded(node: TreeNode, include: Boolean) {
        val excluded = currentConfig.excludedPaths.toMutableList()
        val originalExcluded = currentConfig.excludedPaths.toSet()
        val parts = node.relativePath.split('/')

        if (include) {
            // Clear ancestor exclusions so the target node is reachable.
            for (i in 1 until parts.size) {
                val parentPath = parts.subList(0, i).joinToString("/")
                excluded.removeAll { it == parentPath }
            }
            excluded.removeAll { it == node.relativePath }
            if (node.isDirectory) excluded.removeAll { it.startsWith("${node.relativePath}/") }

            // Re-add siblings of every originally-excluded ancestor: the
            // ancestor's single exclusion used to cover those siblings, and
            // removing it above would otherwise implicitly re-include them.
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
            // Explicit exclusion of the node; drop any now-redundant child
            // exclusions that the ancestor path subsumes.
            if (node.relativePath !in excluded) excluded.add(node.relativePath)
            if (node.isDirectory) excluded.removeAll { it.startsWith("${node.relativePath}/") }
        }

        currentConfig = currentConfig.copy(excludedPaths = excluded)
        onExcludedPathsChanged(excluded)

        rootNode?.let { refreshExclusionFlags(it) }

        // [PERF] Update only the visible rows rather than rebuilding the
        // whole tree. The sticky header is refreshed by the same call
        // (see [hasAnyIncludedNode]).
        refreshVisibleRows(fireStats = true)
        node.rowView?.let { pulseRow(it) }
    }

    /**
     * Recomputes every node's exclusion flags based on the current
     * [FilterConfig.excludedPaths].
     *
     * ### Iterative traversal
     * The walk uses an explicit [ArrayDeque] stack of `(node,
     * ancestorExcluded)` pairs rather than recursion. A deeply nested tree
     * (a user who expanded thirty levels to find a stray file, or a project
     * whose directory depth is measured in the hundreds) would otherwise
     * recurse once per level and eventually overflow the JVM stack. The
     * same reasoning applies to every other traversal in this file — see
     * the class-level *Scanning* note.
     *
     * ### Top-down propagation
     * The traversal is top-down: a node is excluded if its own path is in
     * the list *or* its parent was excluded. Caching the ancestor result in
     * [TreeNode.ancestorExcluded] is what lets [refreshNodeRow] answer
     * "should this row look included?" with a single boolean read, and what
     * lets [hasAnyIncludedNode] answer "is any direct child of the root
     * still included?" in O(children-of-root) without walking the whole
     * tree.
     */
    private fun refreshExclusionFlags(root: TreeNode) {
        val stack = ArrayDeque<Pair<TreeNode, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (node, ancestorExcluded) = stack.removeLast()
            node.selfExcluded = currentConfig.excludedPaths.any { it == node.relativePath }
            node.ancestorExcluded = ancestorExcluded
            val next = ancestorExcluded || node.selfExcluded
            node.children.forEach { stack.addLast(it to next) }
        }
    }

    /**
     * Returns `true` if at least one direct child of the root is currently
     * included.
     *
     * ### Why direct children are sufficient
     * Excluding a folder also excludes every descendant, because
     * [refreshExclusionFlags] propagates `ancestorExcluded` top-down. A
     * direct child whose own flags are both `false` therefore implies the
     * entire subtree under it is included (no descendant can be
     * `selfExcluded` without the scan rebuild having flipped the flags, and
     * the scan always sets `ancestorExcluded` from the parent). Conversely,
     * if every direct child is excluded, every descendant is excluded as
     * well. That is why the check can stop at the first level and stay
     * O(children-of-root) — a meaningful constant on any realistic project
     * even when the tree below is huge.
     *
     * ### What this fixes
     * The sticky header used to reflect `excludedPaths.isEmpty()`, which is
     * equivalent to "every single node in the whole tree is included". That
     * made unchecking any one descendant uncheck the root, which is the
     * behavior this method replaces. See the class-level *Sticky header
     * semantics* note for the full contract.
     *
     * ### Used by
     *  - [refreshVisibleRows] to update the sticky checkbox after any
     *    inclusion change.
     *  - [buildRow] for the sticky header's initial `isChecked` value on
     *    render (including re-render after filter changes).
     *  - [setAllIncluded] indirectly, via the [refreshVisibleRows] call at
     *    the end of the bulk operation.
     *
     * @return `true` when at least one direct child of the root is visible
     *   and included. `false` when the tree has not been scanned yet, when
     *   the root has no visible children, or when every visible child is
     *   excluded (either directly or via an excluded ancestor).
     */
    private fun hasAnyIncludedNode(): Boolean {
        val root = rootNode ?: return false
        return root.children.any { !it.selfExcluded && !it.ancestorExcluded }
    }

    /**
     * Applies a bulk inclusion decision to the whole tree.
     *
     * When [include] is `true`, every exclusion is cleared. When `false`,
     * every top-level entry that would otherwise be visible is written to
     * the exclusion list; because excluding a directory implicitly excludes
     * its whole subtree (see [refreshExclusionFlags]), the single-level list
     * covers the entire project without having to enumerate every node.
     *
     * ### Why the direction is a parameter
     * The previous implementation inferred the direction from
     * `excludedPaths.isEmpty()`: if nothing was excluded, the click meant
     * "exclude everything"; otherwise it meant "include everything". That
     * worked while the sticky checkbox showed "nothing excluded", but
     * stopped matching the moment the sticky checkbox was fixed to show
     * "at least one child included" — the checkbox can now be checked with
     * a non-empty exclusion list, and inferring direction from the list
     * would then invert the user's click. Taking the new checkbox state as
     * the target makes the bulk operation follow the checkbox exactly in
     * both directions, which is the behavior a standard parent checkbox is
     * expected to have.
     *
     * ### Caller contract
     * Only [buildRow]'s sticky checkbox listener calls this method, and it
     * always passes the post-toggle `isChecked` value it just received
     * from the listener. `checked = true` therefore always means "include
     * everything", and `checked = false` always means "exclude
     * everything", regardless of what the previous state happened to be.
     *
     * @param include target inclusion state for every visible child of the
     *   root. `true` clears all exclusions; `false` excludes every visible
     *   top-level entry (which, by subtree propagation, excludes the whole
     *   project).
     */
    private fun setAllIncluded(include: Boolean) {
        val newExcluded: List<String> = if (include) {
            emptyList()
        } else {
            val filter = FileFilter(projectRoot, currentConfig)
            projectRoot.listFiles()
                ?.filter { file -> filter.include(file, respectExcludedPaths = false) }
                ?.map { it.name }
                ?: emptyList()
        }
        currentConfig = currentConfig.copy(excludedPaths = newExcluded)
        onExcludedPathsChanged(newExcluded)
        rootNode?.let { refreshExclusionFlags(it) }

        // [PERF] Same in-place refresh as setNodeIncluded, plus a pulse on
        // the sticky row to draw attention to the bulk change.
        refreshVisibleRows(fireStats = true)
        pendingPulsePath = null
        stickyScroll.getChildAt(0)?.let { pulseRow(it) }
    }

    // ------------------------------------------------------------------
    // Aggregate statistics
    // ------------------------------------------------------------------

    /**
     * Walks [root]'s rendered subtree and aggregates file count, byte size,
     * and estimated tokens.
     *
     * ### Iterative traversal
     * Uses an explicit [ArrayDeque] stack rather than recursion, for the
     * same reason as [refreshExclusionFlags]: a deeply nested tree must not
     * overflow the JVM stack while the stats are being recomputed. The walk
     * only descends into non-excluded directories, so excluded subtrees are
     * skipped in O(1) regardless of their depth.
     *
     * Token estimation uses the ~4-bytes-per-token heuristic the rest of the
     * plugin already relies on, so the number shown here matches the number
     * shown in the export result panel. Files inside an excluded subtree are
     * skipped entirely — this is what makes the preview respond live to
     * inclusion changes.
     */
    private fun computeStatsFromTree(root: TreeNode): PreviewStats {
        var totalFiles = 0
        var totalTokens = 0L
        var totalBytes = 0L
        val byExt = mutableMapOf<String, ExtensionStats>()

        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.ancestorExcluded || node.selfExcluded) continue
            node.children.forEach { child ->
                if (child.isDirectory) {
                    stack.addLast(child)
                } else if (!child.ancestorExcluded && !child.selfExcluded) {
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
        return PreviewStats(
            totalFiles = totalFiles,
            totalTokens = totalTokens,
            totalBytes = totalBytes,
            byExtension = byExt.values.sortedByDescending { it.tokens }
        )
    }

    // ------------------------------------------------------------------
    // Expand / collapse all
    // ------------------------------------------------------------------

    /**
     * Expands every directory and rebuilds the tree.
     *
     * Uses a full rebuild because the visibility of every row changes at
     * once; an in-place refresh would be strictly more expensive than a
     * clean render here. Stats are not re-fired because the aggregate set
     * of files is unchanged — only their visibility is.
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
     *
     * Same rationale as [expandAll] regarding the full rebuild and the
     * `fireStats = false` flag.
     */
    fun collapseAll() {
        val root = rootNode ?: return
        markAllExpanded(root, false)
        expandedPaths.clear()
        renderInternal(fireStats = false)
    }

    /**
     * Sets [expanded] on [node] and every descendant using an explicit
     * [ArrayDeque] stack instead of recursion. See [refreshExclusionFlags]
     * for the rationale on pathological tree depth.
     */
    private fun markAllExpanded(node: TreeNode, expanded: Boolean) {
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            current.expanded = expanded
            current.children.forEach { stack.addLast(it) }
        }
    }

    /**
     * Returns the relative paths of every directory that can be expanded
     * (i.e. every directory with at least one child), so [expandAll] can
     * populate [expandedPaths] in one pass.
     *
     * Iterative for the same reason as [refreshExclusionFlags].
     */
    private fun collectExpandable(root: TreeNode): List<String> {
        val out = mutableListOf<String>()
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
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

    /** Formats a byte count as `B`, `KB` or `MB`, matching the export panel. */
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
     * A specialized [ScrollView] designed to coordinate touch events with its parent container
     * (typically the configuration dialog's main scroll view).
     *
     * ### The Scroll Conflict Problem
     * By default, nested scroll containers in Android aggressively consume touch events, preventing
     * the parent from scrolling. This creates a "scroll trap" where the user can scroll the inner
     * tree but cannot scroll the outer dialog to reach UI elements above or below the tree.
     *
     * ### Solutions Implemented
     * 1. **Empty Space Handoff:** The [dispatchTouchEvent] method checks if the initial touch
     *    occurred in the blank area below the tree's actual content. Since [ScrollView] inherently
     *    consumes `ACTION_DOWN` even when it has no content to scroll, we explicitly reject the
     *    event in this area so the parent dialog can handle it naturally.
     * 2. **Boundary-Aware Interception:** The [onInterceptTouchEvent] method dynamically toggles
     *    `requestDisallowInterceptTouchEvent` based on the scroll direction and the tree's current
     *    scroll position. If the tree is at its top or bottom edge, the gesture is handed off
     *    to the parent dialog.
     */
    private class NestedScrollView(context: Context) : ScrollView(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var lastY = 0f

        /**
         * Determines if the initial touch falls within the empty, non-scrollable space
         * below the tree's content (the [horizontalScroll] wrapper).
         *
         * @param ev The motion event to evaluate.
         * @return `true` if the touch is in the empty space during `ACTION_DOWN`, `false` otherwise.
         */
        private fun isTouchInEmptySpace(ev: MotionEvent): Boolean {
            if (ev.actionMasked != MotionEvent.ACTION_DOWN) return false
            val child = getChildAt(0) ?: return false
            return ev.y > child.bottom
        }

        /**
         * Intercepts the touch event stream before it reaches child views or the default
         * `onTouchEvent` handler.
         *
         * If the user taps in the empty space below the tree, we return `false` to signal
         * that this `ViewGroup` does not want to handle the gesture. This allows the parent
         * `ScrollView` to receive the complete gesture sequence (from `ACTION_DOWN` onwards)
         * and scroll the dialog normally.
         */
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (isTouchInEmptySpace(ev)) {
                // Reject the event so the parent dialog's ScrollView can take over.
                return false
            }
            return super.dispatchTouchEvent(ev)
        }

        /**
         * Monitors touch events to determine when to yield control to the parent view.
         *
         * We evaluate the vertical swipe direction after the movement exceeds [touchSlop]:
         * - Swipe down (finger moving down) checks if the tree can scroll up (`canScrollVertically(-1)`).
         * - Swipe up (finger moving up) checks if the tree can scroll down (`canScrollVertically(1)`).
         *
         * If the tree cannot scroll further in the intended direction, we release the
         * `requestDisallowInterceptTouchEvent` lock, allowing the parent `ScrollView`
         * to intercept and handle the remaining gesture.
         */
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = ev.rawY
                    // Initially assume the user wants to scroll the tree.
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
                        
                        // Handoff: if the tree cannot scroll further, let the
                        // parent handle the remaining gesture.
                        parent?.requestDisallowInterceptTouchEvent(treeCanStillScroll)
                        lastY = ev.rawY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Always release the lock when the gesture ends or is cancelled.
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return super.onInterceptTouchEvent(ev)
        }
    }

    /**
     * A [HorizontalScrollView] that sizes itself to the *content* width when
     * the parent passes `AT_MOST` or `UNSPECIFIED` measure specs.
     *
     * The default [HorizontalScrollView.onMeasure] always reports the full
     * available width, which would make the tree always scroll horizontally
     * even when every path fits. By measuring the child with
     * `UNSPECIFIED` first and clamping the final width to either the content
     * width or the available width — whichever is smaller — long paths
     * still scroll while short paths render naturally at full width.
     *
     * This is the single reason the tree does not need to compute path
     * widths itself: the scroll view answers that question once, during the
     * measure pass, using the same `TextView` measurements the renderer
     * already produced.
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
     *  - `expanded` / `selfExcluded` / `ancestorExcluded` describe the
     *    user-visible state at any point in time.
     *  - `rowView`, `childrenWrapper`, `arrow`, `checkbox`, `iconView`,
     *    `nameView` are the widget references used for in-place updates.
     *    They are nullable because a row may not have been rendered yet
     *    (collapsed subtrees) or may have been discarded by a rebuild.
     *
     * Instances are created on the worker thread during [scanAsync] and
     * then mutated on the UI thread during rendering. The two phases do not
     * overlap for the same tree, so no synchronization is required.
     */
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