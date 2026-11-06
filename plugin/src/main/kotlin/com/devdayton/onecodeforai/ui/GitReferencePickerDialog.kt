package com.devdayton.onecodeforai.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.core.GitUtils
import com.devdayton.onecodeforai.ui.UiFactory.iconButton
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import com.devdayton.onecodeforai.ui.UiFactory.textView
import com.devdayton.onecodeforai.ui.CodeAssistTheme

/**
 * Modal picker for a Git reference: branches, tags, and recent commits.
 *
 * ### Purpose
 * Invoked from [ConfigurationDialog] when the user taps the "Browse refs"
 * affordance next to the Git ref text field. The dialog is intentionally
 * isolated from the configuration dialog's state: it only knows how to read
 * the repository through [GitUtils] and how to hand a reference string back
 * to its caller via [onReferenceSelected]. The caller decides what to do
 * with the selection (typically write it into the ref field), which keeps
 * this class free of any dependency on `FilterConfig` or the export pipeline.
 *
 * ### Layout
 * Three vertical regions inside a rounded dark card:
 *
 *  1. **Header**: title on the left, close button on the right.
 *  2. **Tab row**: three equal-width tabs (Branches / Tags / Commits). The
 *     active tab is highlighted with an accent color and a raised surface
 *     background; the others are muted text without a background.
 *  3. **Scrollable content**: the currently selected tab's rendered list.
 *     It shares its 1f weight with the tab row so the dialog is dominated
 *     by content, not chrome.
 *
 * ### Threading
 * [GitUtils] calls in [renderBranches], [renderTags], and [renderCommits]
 * are **synchronous**. They run on the calling thread (the UI thread), which
 * is acceptable here because they only enumerate refs — a cheap operation
 * even on large repositories, since JGit already caches the ref database
 * after the initial scan. Longer-running Git work (diff computation, snapshot
 * streaming) is deferred to a background thread by the calling dialog; this
 * picker only fires metadata queries.
 *
 * ### Selection lifecycle
 * Tapping any row triggers [selectReference], which **dismisses the dialog
 * before invoking the callback**. The order matters: the callback typically
 * writes into an `EditText` on the configuration dialog and starts an
 * asynchronous ref-resolution pass, and doing that while the picker is still
 * on top of it would cause the resolved-ref label to briefly render behind
 * the modal. Dismissing first guarantees a clean visual handoff.
 *
 * @param activity            Host activity used to inflate the dialog and
 *                            its content views.
 * @param projectRoot         Repository root the picker reads refs from.
 *                            Must be a readable Git repository, otherwise
 *                            every tab renders its empty state.
 * @param onReferenceSelected invoked on the UI thread with the chosen
 *                            reference string (a branch name, tag name, or
 *                            full commit hash) after the dialog has been
 *                            dismissed. Callers that need to keep a
 *                            reference to the dialog must use a weak
 *                            reference; this class does not retain it.
 */
class GitReferencePickerDialog(
    private val activity: Activity,
    private val projectRoot: java.io.File,
    private val onReferenceSelected: (String) -> Unit
) {
    /**
     * Builds and shows the dialog.
     *
     * The branches tab is always selected on first render because branches
     * are by far the most common target. [selectTab] is called once before
     * [Dialog.show] to populate the content region, and again on every tab
     * tap; there is no lazy loading per tab, so switching is instantaneous.
     *
     * The window is sized to 92% width / 78% height and given a transparent
     * background so the rounded card inside [root] is the only visible
     * chrome. The explicit layout call in [Dialog.setOnShowListener] is
     * required because `Dialog.getWindow()` returns `null` until the dialog
     * is actually shown on some Android versions.
     */
    fun show() {
        val dialog = Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCancelable(true)
        }

        // Rounded dark card with 14dp radius and a hairline outline,
        // matching the visual language of ConfigurationDialog.
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 14),
                CodeAssistTheme.dp(activity, 16), CodeAssistTheme.dp(activity, 14)
            )
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.BG, 14f, CodeAssistTheme.OUTLINE
            )
        }

        // Header: title takes all remaining width, close button stays 40dp.
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            activity.textView(
                activity.pluginString(R.string.git_pick_ref_title), 15f,
                CodeAssistTheme.TEXT_PRIMARY, true
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        header.addView(
            activity.iconButton(
                R.drawable.ic_tech_close, activity.pluginString(R.string.close)
            ) { dialog.dismiss() }
        )
        root.addView(header)

        // Tab row and scrollable content region. The row is not weighted;
        // the content region takes the remaining height via weight = 1f.
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, CodeAssistTheme.dp(activity, 10), 0, CodeAssistTheme.dp(activity, 6))
        }
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = true }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(tabRow)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        /**
         * Switches the active tab and re-renders the content region.
         *
         * Highlights the selected tab (accent text + raised surface
         * background) and mutes every other tab. Re-rendering is
         * unconditional — even re-selecting the currently-active tab
         * rebuilds its list — because the repository refs may have changed
         * since the last render and there is no cheaper invalidation signal
         * available without adding background watchers.
         *
         * Extracted as a local lambda rather than a private method because
         * it closes over [content], [dialog], and [tabs]; threading those
         * three through a method signature would just move the noise
         * elsewhere without improving testability (the whole method is
         * UI-construction, not pure logic).
         */
        val tabs = mutableListOf<TextView>()
        fun selectTab(index: Int) {
            tabs.forEachIndexed { currentIndex, tab ->
                tab.setTextColor(
                    if (currentIndex == index) CodeAssistTheme.ACCENT
                    else CodeAssistTheme.TEXT_SECONDARY
                )
                tab.background = if (currentIndex == index) {
                    CodeAssistTheme.roundedBg(
                        activity, CodeAssistTheme.SURFACE_RAISED, 8f, CodeAssistTheme.OUTLINE
                    )
                } else {
                    null
                }
            }

            content.removeAllViews()
            when (index) {
                TAB_BRANCHES -> renderBranches(content, dialog)
                TAB_TAGS -> renderTags(content, dialog)
                TAB_COMMITS -> renderCommits(content, dialog)
            }
        }

        // Build one tab per entry, all equally weighted so they fill the
        // row. The click handler captures its index via `index` (the
        // forEachIndexed parameter), which is what lets each tab close over
        // the right value without an explicit `val i = index` capture.
        listOf(
            activity.pluginString(R.string.git_tab_branches),
            activity.pluginString(R.string.git_tab_tags),
            activity.pluginString(R.string.git_tab_commits)
        ).forEachIndexed { index, label ->
            val tab = activity.textView(label, 12f, CodeAssistTheme.TEXT_SECONDARY, true).apply {
                gravity = Gravity.CENTER
                setPadding(
                    CodeAssistTheme.dp(activity, 12), CodeAssistTheme.dp(activity, 8),
                    CodeAssistTheme.dp(activity, 12), CodeAssistTheme.dp(activity, 8)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(index) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            tabs.add(tab)
            tabRow.addView(tab)
        }

        // Populate the initial tab before showing so the dialog opens with
        // its content already laid out; otherwise the user would see a
        // brief empty frame between `show()` and the first `selectTab`.
        selectTab(TAB_BRANCHES)
        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                    (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
                )
            }
        }
        dialog.show()
    }

    /**
     * Renders the Branches tab: local branches first (with a "current"
     * badge on the checked-out one), then remote branches (all with a
     * "remote" badge), each group preceded by a section label.
     *
     * The two groups come from a single [GitUtils.getBranches] call because
     * JGit enumerates local and remote refs in one pass over the ref
     * database; splitting them client-side is cheaper than two calls.
     *
     * Tapping a branch writes its raw name into the ref field. Remote
     * branches keep their `origin/`-style prefix, which is exactly what
     * `Repository.resolve` expects on the export side.
     *
     * @param container the content region to add rows to.
     * @param dialog    passed through to [selectReference] so the row can
     *                  dismiss the picker.
     */
    private fun renderBranches(container: LinearLayout, dialog: Dialog) {
        val branches = GitUtils.getBranches(projectRoot)
        if (branches.isEmpty()) {
            container.addView(emptyMessage(activity.pluginString(R.string.git_no_branches)))
            return
        }

        val localBranches = branches.filterNot { it.isRemote }
        val remoteBranches = branches.filter { it.isRemote }

        if (localBranches.isNotEmpty()) {
            container.addView(sectionLabel(activity.pluginString(R.string.git_local_branches)))
            localBranches.forEach { branch ->
                container.addView(
                    referenceRow(
                        branch.name,
                        // Only the checked-out branch gets a badge so the
                        // "current" state is visible at a glance.
                        if (branch.isCurrent) activity.pluginString(R.string.git_current_marker) else null,
                        CodeAssistTheme.TAG_INCLUDE_TEXT
                    ) { selectReference(branch.name, dialog) }
                )
            }
        }

        if (remoteBranches.isNotEmpty()) {
            container.addView(sectionLabel(activity.pluginString(R.string.git_remote_branches)))
            remoteBranches.forEach { branch ->
                container.addView(
                    referenceRow(
                        branch.name,
                        // Every remote branch gets the "remote" badge so it
                        // is impossible to confuse a remote tracking branch
                        // (e.g. `origin/main`) with the local `main`.
                        activity.pluginString(R.string.git_remote_marker),
                        CodeAssistTheme.TEXT_SECONDARY
                    ) { selectReference(branch.name, dialog) }
                )
            }
        }
    }

    /**
     * Renders the Tags tab: one row per tag, with the abbreviated target
     * commit hash as the badge.
     *
     * The short hash is a useful disambiguator when two tags point at the
     * same commit or when the user wants to confirm that an annotated tag
     * peels to the expected commit before choosing it.
     *
     * @param container the content region to add rows to.
     * @param dialog    passed through to [selectReference].
     */
    private fun renderTags(container: LinearLayout, dialog: Dialog) {
        val tags = GitUtils.getTags(projectRoot)
        if (tags.isEmpty()) {
            container.addView(emptyMessage(activity.pluginString(R.string.git_no_tags)))
            return
        }

        tags.forEach { tag ->
            container.addView(
                referenceRow(tag.name, tag.shortHash, CodeAssistTheme.TEXT_MUTED) {
                    selectReference(tag.name, dialog)
                }
            )
        }
    }

    /**
     * Renders the Commits tab: the most recent commits reachable from HEAD,
     * newest first, up to [RECENT_COMMIT_LIMIT].
     *
     * Commit rows use a two-line layout (hash · date, then author, then
     * message) because a commit is qualitatively richer than a ref: the
     * message is often what the user is actually looking for, and a single
     * line would truncate it to the point of uselessness.
     *
     * Selecting a row writes the **full** commit hash into the ref field,
     * not the short hash that is displayed. Full hashes are unambiguous and
     * resolve faster in JGit (`Repository.resolve` does not have to scan
     * for the abbreviated form), while the short hash remains visible for
     * the user to recognise.
     *
     * @param container the content region to add rows to.
     * @param dialog    passed through to [selectReference].
     */
    private fun renderCommits(container: LinearLayout, dialog: Dialog) {
        val commits = GitUtils.getRecentCommits(projectRoot, limit = RECENT_COMMIT_LIMIT)
        if (commits.isEmpty()) {
            container.addView(emptyMessage(activity.pluginString(R.string.git_no_commits)))
            return
        }

        commits.forEach { commit ->
            // Whole-row clickable container. Padding and background mirror
            // `referenceRow` so the vertical rhythm matches the other tabs.
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 8),
                    CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 8)
                )
                background = CodeAssistTheme.selectableBg(activity)
                isClickable = true
                isFocusable = true
                setOnClickListener { selectReference(commit.hash, dialog) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = CodeAssistTheme.dp(activity, 4)
                }
            }

            // Line 1: short hash (monospace, accent) + separator + date
            // (muted). The date field takes the remaining width so long
            // messages cannot push it off-screen.
            val heading = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(
                activity.textView(commit.shortHash, 11f, CodeAssistTheme.ACCENT, true).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            )
            heading.addView(
                activity.textView("  ·  ${commit.date}", 10f, CodeAssistTheme.TEXT_MUTED).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            row.addView(heading)

            // Line 2: author, muted so it does not compete with the message.
            row.addView(
                activity.textView(commit.author, 10f, CodeAssistTheme.TEXT_SECONDARY).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 2), 0, 0)
                }
            )

            // Line 3: commit message, capped at two lines. Two lines is
            // enough to identify the commit on a phone screen, and the
            // ellipsis makes it obvious that more text exists without the
            // dialog having to ship a detail view.
            row.addView(
                activity.textView(commit.message, 12f, CodeAssistTheme.TEXT_PRIMARY).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 2), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
            container.addView(row)
        }
    }

    /**
     * Dismisses the picker and forwards the chosen reference to the caller.
     *
     * The dismissal happens **first** on purpose: the callback typically
     * writes into an `EditText` on the configuration dialog and starts a
     * background resolution pass, and doing that while this dialog is still
     * on top would briefly render the resolved-ref label behind the modal.
     * See the class-level *Selection lifecycle* note.
     */
    private fun selectReference(reference: String, dialog: Dialog) {
        dialog.dismiss()
        onReferenceSelected(reference)
    }

    /**
     * Builds a small uppercase section header used to separate local from
     * remote branches. Muted color and 10sp size keep it visually below the
     * branch rows without disappearing entirely.
     */
    private fun sectionLabel(value: String) = activity.textView(
        value.uppercase(), 10f, CodeAssistTheme.TEXT_MUTED, true
    ).apply {
        setPadding(0, CodeAssistTheme.dp(activity, 10), 0, CodeAssistTheme.dp(activity, 4))
    }

    /**
     * Builds a centered, muted placeholder shown when a tab has no entries
     * (empty repo, no tags, no commits, unreadable repository).
     *
     * Uses a generous 24dp vertical padding so the empty state occupies a
     * similar vertical footprint to a populated list, which prevents the
     * dialog from collapsing to a thin strip on empty tabs.
     */
    private fun emptyMessage(value: String) = activity.textView(
        value, 12f, CodeAssistTheme.TEXT_MUTED
    ).apply {
        gravity = Gravity.CENTER
        setPadding(0, CodeAssistTheme.dp(activity, 24), 0, CodeAssistTheme.dp(activity, 24))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Builds a single clickable row: label on the left (weight = 1, so it
     * takes all remaining space), optional badge on the right.
     *
     * The label is rendered in monospace because branch names, tag names and
     * hashes are visually aligned better in a fixed-width font — especially
     * when several rows have common prefixes like `feature/`.
     *
     * @param label      primary text of the row (branch/tag name).
     * @param badge      optional short text shown at the right edge (e.g.
     *                   "current", "remote", short commit hash). `null` for
     *                   no badge.
     * @param badgeColor text color for the badge; the caller picks an
     *                   accent that matches the semantic of the badge (e.g.
     *                   include-green for "current", muted for "remote").
     * @param onClick    invoked when the row is tapped, before any state
     *                   change; the caller decides whether to dismiss the
     *                   dialog.
     */
    private fun referenceRow(
        label: String,
        badge: String?,
        badgeColor: Int,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 9),
            CodeAssistTheme.dp(activity, 10), CodeAssistTheme.dp(activity, 9)
        )
        background = CodeAssistTheme.selectableBg(activity)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // 4dp gap between rows so the pressable background has visible
            // separation even when two rows share a color.
            bottomMargin = CodeAssistTheme.dp(activity, 4)
        }

        addView(
            activity.textView(label, 12f, CodeAssistTheme.TEXT_PRIMARY).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                typeface = android.graphics.Typeface.MONOSPACE
            }
        )
        if (badge != null) {
            addView(
                activity.textView(badge, 9f, badgeColor, true).apply {
                    setPadding(
                        CodeAssistTheme.dp(activity, 6), CodeAssistTheme.dp(activity, 2),
                        CodeAssistTheme.dp(activity, 6), CodeAssistTheme.dp(activity, 2)
                    )
                    background = CodeAssistTheme.roundedBg(
                        activity, CodeAssistTheme.SURFACE_RAISED, 5f
                    )
                }
            )
        }
    }

    private companion object {
        /**
         * Index of the Branches tab in the tab row and in the `when` inside
         * [show]'s `selectTab`. Kept as a named constant rather than a bare
         * `0` so that adding a fourth tab only requires renumbering this
         * block, not hunting for magic literals.
         */
        const val TAB_BRANCHES = 0

        /** Index of the Tags tab. See [TAB_BRANCHES]. */
        const val TAB_TAGS = 1

        /** Index of the Commits tab. See [TAB_BRANCHES]. */
        const val TAB_COMMITS = 2

        /**
         * Maximum number of commits listed in the Commits tab.
         *
         * 60 is a deliberate compromise: enough to cover a typical day or
         * week of work, small enough that rendering the list stays under a
         * frame even on low-end devices and that the user can scroll to the
         * oldest entry without losing patience. Users who need an older
         * commit can still paste its hash into the ref field manually, so
         * this limit is a convenience ceiling, not a hard functional cap.
         */
        const val RECENT_COMMIT_LIMIT = 60
    }
}