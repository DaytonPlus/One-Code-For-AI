package com.devdayton.onecodeforai.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.devdayton.onecodeforai.R
import com.devdayton.onecodeforai.android.LanguageHelper
import com.devdayton.onecodeforai.core.GitUtils
import com.devdayton.onecodeforai.ui.UiFactory.iconButton
import com.devdayton.onecodeforai.ui.UiFactory.pluginString
import com.devdayton.onecodeforai.ui.UiFactory.textView
import com.devdayton.onecodeforai.ui.CodeAssistTheme
import java.io.File

/**
 * Modal preview that shows the diff between a resolved Git reference and the
 * current working tree.
 *
 * ### Purpose
 * Invoked from [ConfigurationDialog] via the "Preview diff" button under the
 * Git section. Its job is to let the user sanity-check what the export would
 * contain *before* pressing Generate — a reference that looks right by name
 * may still point at a tree whose diff is far larger (or smaller) than the
 * user expects, and catching that early saves a full scan/write cycle.
 *
 * ### Two-phase entry
 * [show] has two distinct modes:
 *
 *  1. **Rejected input.** An empty string or a reference that
 *     [GitUtils.resolveRefShort] cannot resolve both show a short
 *     [Toast] and return without instantiating the [Dialog]. This keeps the
 *     failure cheap (no window, no layout pass) and matches the user's
 *     mental model: a typo in the ref field should not open a modal.
 *  2. **Accepted input.** The dialog is built and shown, with the file list
 *     and the diff content pre-computed and pre-laid-out before `show()` so
 *     no empty frame is rendered.
 *
 * ### Layout
 * A single rounded dark card with three vertical regions:
 *
 *  1. **Header**: title with the resolved short hash, plus a summary line
 *     (`N files · +X −Y`) when [GitUtils.getDiffStats] succeeds, and a close
 *     button.
 *  2. **Changed files list**: a fixed-height (150dp) scrollable list of
 *     per-file rows. Every row shows a one-letter status, the file path
 *     (monospace, middle-truncated), and (when non-zero) the insertion and
 *     deletion counts. The fixed 150dp cap is what keeps the diff content
 *     region usable on a phone — a repository with hundreds of changed
 *     files would otherwise push the actual diff below the fold.
 *  3. **Diff content**: a vertically-scrolling region with weight 1 that
 *     wraps a horizontally-scrolling text view. The diff itself is
 *     monospace, pre-formatted, and truncated at
 *     [GitUtils.getDiff]'s line limit (800 lines) with an explicit
 *     "... (N lines truncated)" footer, so the dialog stays responsive on
 *     large diffs without silently hiding the truncation.
 *
 * ### Threading
 * All [GitUtils] calls here are synchronous and run on the calling thread
 * (the UI thread). This is a deliberate tradeoff: the dialog is a
 * *reviewing* tool, not a background operation, and blocking the UI for the
 * 200-500ms a typical diff scan takes is preferable to the complexity of a
 * background thread plus an async re-render pipeline. Users who tap the
 * button on a huge repository will see a brief freeze, but no data is at
 * risk — nothing here writes to disk or mutates repository state.
 *
 * Both [GitUtils.getDiffStats] and [GitUtils.getDiff] each re-scan the diff
 * from scratch (both call `scanDiff` internally). That costs one extra scan
 * per preview, which is intentional: keeping them separate means the stats
 * path can be used alone (e.g. by a future compact summary) without
 * paying for the full unified diff. If profiling ever shows the double scan
 * dominating, they can be merged behind a `getDiffWithStats` helper.
 *
 * ### Failure modes
 *  - Empty or unresolvable ref: a [Toast] and no dialog.
 *  - Repository unreadable: [GitUtils.getDiffStats] returns `null`, which
 *    this dialog renders as "no changes detected" (matching the
 *    precondition that the configuration dialog only opens this preview
 *    when a repo is visible).
 *  - Diff computation returns `null` while stats succeeded (rare I/O race):
 *    the diff region is rendered with an empty string, which shows the
 *    monospace container but no content.
 *
 * @param activity    Host activity used to build the dialog and its views.
 * @param projectRoot Repository root against which the reference is resolved
 *                    and diffed. Must point at a directory that
 *                    [GitUtils.withGit] can open.
 */
class GitDiffPreviewDialog(
    private val activity: Activity,
    private val projectRoot: File
) {
    /**
     * Resolves [rawReference] against the repository and shows the diff
     * preview.
     *
     * The reference is trimmed first so trailing whitespace from the config
     * dialog's text field cannot cause a spurious "invalid reference" toast
     * for a reference that is otherwise valid.
     *
     * See the class-level *Two-phase entry* note for the early-return
     * contract. The validation pass ([GitUtils.resolveRefShort]) runs
     * before any UI is constructed, which is why an invalid reference never
     * even allocates the [Dialog] instance.
     *
     * @param rawReference the reference string as typed or selected in the
     *   configuration dialog. Any valid Git ref is accepted: branch name,
     *   tag name, short hash, full hash, or an expression like `HEAD~3`.
     */
    fun show(rawReference: String) {
        val reference = rawReference.trim()
        if (reference.isEmpty()) {
            showMessage(R.string.git_ref_empty)
            return
        }

        // Resolve first: this is the single check that lets the dialog
        // skip the entire window-construction path for typos.
        val resolvedReference = GitUtils.resolveRefShort(projectRoot, reference)
        if (resolvedReference == null) {
            showMessage(R.string.git_ref_invalid)
            return
        }

        // Stats and diff are computed up front (both synchronous, both
        // re-scanning internally) so the dialog opens fully populated.
        val stats = GitUtils.getDiffStats(projectRoot, reference)
        val diff = GitUtils.getDiff(projectRoot, reference)
        val dialog = Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCancelable(true)
        }

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

        // Header: title + optional stats summary + close button. The title
        // column takes all remaining width; the close button has a fixed
        // 40dp square, matching every other icon button in the plugin.
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(
            activity.textView(
                activity.pluginString(R.string.git_diff_title, resolvedReference), 14f,
                CodeAssistTheme.TEXT_PRIMARY, true
            )
        )
        // The stats line is only rendered when getDiffStats returned
        // non-null; an unreadable repo still gets the dialog, just without
        // the summary (see the "no changes detected" branch below).
        if (stats != null) {
            headerText.addView(
                activity.textView(
                    activity.pluginString(
                        R.string.git_diff_stats,
                        stats.filesChanged, stats.insertions, stats.deletions
                    ),
                    10f, CodeAssistTheme.TEXT_SECONDARY
                ).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 4), 0, 0)
                }
            )
        }
        header.addView(headerText)
        header.addView(
            activity.iconButton(
                R.drawable.ic_tech_close, activity.pluginString(R.string.close)
            ) { dialog.dismiss() }
        )
        root.addView(header)

        // Empty state: either the repo/ref could not be diffed (stats
        // == null) or there is genuinely nothing different between the
        // reference and the working tree. Both cases collapse to the same
        // message because from the user's perspective the actionable
        // outcome is identical: nothing to review.
        if (stats == null || stats.filesChanged == 0) {
            root.addView(emptyMessage(activity.pluginString(R.string.git_diff_no_changes)))
        } else {
            root.addView(buildChangedFilesList(stats.files))
            // Section label between the file list and the unified diff.
            // Uppercased so it reads as chrome rather than content.
            root.addView(
                activity.textView(
                    // Locale-aware uppercase so the label matches the localized content
                    // and the force-English toggle. See UiFactory.sectionTitle.
                    activity.pluginString(R.string.git_diff_content)
                        .uppercase(LanguageHelper.detectIdeLocale(activity)),
                    10f, CodeAssistTheme.TEXT_MUTED, true
                ).apply {
                    setPadding(0, CodeAssistTheme.dp(activity, 10), 0, CodeAssistTheme.dp(activity, 4))
                }
            )
            // `diff.orEmpty()` covers the rare case where stats succeeded
            // but getDiff returned null (concurrent ref change, transient
            // I/O): we still show the container with an empty body rather
            // than crashing or hiding the region.
            root.addView(buildDiffContent(diff.orEmpty()))
        }

        dialog.setContentView(root)
        // Window configuration must happen after the dialog is shown:
        // getWindow() can return null before show() on some Android
        // versions, so doing this in the constructor would silently no-op.
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.95f).toInt(),
                    (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
                )
            }
        }
        dialog.show()
    }

    /**
     * Builds the fixed-height, scrollable list of changed files.
     *
     * ### Layout
     * A [ScrollView] fixed at 150dp, containing one row per [GitUtils.FileChange].
     * The fixed height is the key design decision: without it, the list would
     * grow with the number of changed files, and a repository with hundreds
     * of changed files would push the actual diff content below the visible
     * area — the exact content the user opened this dialog to see.
     *
     * ### Row anatomy
     * Each row is a horizontal strip: `[status] [path ......................] +X −Y`
     *
     *  - **Status**: a single letter (`A`/`D`/`R`/`M`), color-coded to match
     *    the plugin's tag palette (green for additions, red for deletions,
     *    amber for renames, muted for modifications). Monospace so the
     *    column visually aligns with the path.
     *  - **Path**: monospace, `weight = 1` (takes all remaining space),
     *    truncated in the middle with an ellipsis. Middle truncation is
     *    chosen over end truncation because two paths that differ only in
     *    their final segment (e.g. `src/…/UserDao.kt` and
     *    `test/…/UserDao.kt`) would be indistinguishable if only the tail
     *    were kept, while the leading directory is often redundant.
     *  - **Insertions/deletions**: only rendered when at least one is
     *    non-zero. A file that was renamed without content changes has
     *    zero of both and would otherwise show a noisy `+0 −0`.
     *
     * @param files per-file change records produced by [GitUtils.getDiffStats].
     *   Never empty in practice (the caller only calls this when
     *   `filesChanged > 0`), but an empty list is handled gracefully by
     *   simply rendering no rows.
     * @return the fully-built scroll container, ready to be added to the
     *   dialog root.
     */
    private fun buildChangedFilesList(files: List<GitUtils.FileChange>): ScrollView {
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, CodeAssistTheme.dp(activity, 150)
            )
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, CodeAssistTheme.dp(activity, 8), 0, CodeAssistTheme.dp(activity, 8))
        }
        scroll.addView(column)

        files.forEach { file ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    CodeAssistTheme.dp(activity, 4), CodeAssistTheme.dp(activity, 4),
                    CodeAssistTheme.dp(activity, 4), CodeAssistTheme.dp(activity, 4)
                )
            }
            // Status letter and its color. 'C' (copy) intentionally falls
            // through to the modify color: the copy vs. modify distinction
            // matters to Git's rename detection but is noise in a preview.
            val (statusLabel, statusColor) = when (file.status) {
                'A' -> "A" to CodeAssistTheme.TAG_INCLUDE_TEXT
                'D' -> "D" to CodeAssistTheme.TAG_EXCLUDE_TEXT
                'R' -> "R" to CodeAssistTheme.TAG_CONFLICT_TEXT
                else -> "M" to CodeAssistTheme.TEXT_SECONDARY
            }
            row.addView(
                activity.textView(statusLabel, 10f, statusColor, true).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    // Fixed width so every path starts at the same x
                    // position regardless of which status letter is shown.
                    layoutParams = LinearLayout.LayoutParams(
                        CodeAssistTheme.dp(activity, 18), ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )
            row.addView(
                activity.textView(file.path, 10f, CodeAssistTheme.TEXT_PRIMARY).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1
                    // MIDDLE truncation preserves both the leading
                    // namespace/package and the trailing filename, which is
                    // what a reviewer needs to identify the file.
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }
            )
            if (file.insertions > 0 || file.deletions > 0) {
                row.addView(
                    activity.textView(
                        "+${file.insertions} −${file.deletions}",
                        9f, CodeAssistTheme.TEXT_MUTED
                    ).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                )
            }
            column.addView(row)
        }
        return scroll
    }

    /**
     * Builds the scrollable, monospace region that renders the unified diff
     * text.
     *
     * ### Two-axis scrolling
     * The region nests a [HorizontalScrollView] inside a vertical
     * [ScrollView] because unified diffs have both properties that need to
     * be respected:
     *
     *  - Diffs are **long**, so vertical scrolling is mandatory.
     *  - Diff lines can be **wide** (long import statements, minified code,
     *    long string literals), so wrapping would break the visual column
     *    alignment that makes a unified diff readable. Horizontal scrolling
     *    preserves alignment at the cost of one extra pan gesture.
     *
     * `setHorizontallyScrolling(true)` on the [TextView] is what tells it to
     * measure at natural width instead of wrapping; without it, the
     * [HorizontalScrollView] would have nothing to scroll.
     *
     * ### Styling
     * Monospace font, 10sp, generous padding (8dp on all sides), and a
     * rounded surface background with an outline. The surface is a shade
     * darker than the surrounding card so it reads as a distinct content
     * region rather than additional chrome.
     *
     * @param diff the unified diff text produced by [GitUtils.getDiff].
     *   Passed verbatim; this method never inspects or reformats it. If
     *   truncation happened, the marker is already embedded in the text by
     *   [GitUtils.getDiff] itself.
     * @return the fully-built scroll container, ready to be added to the
     *   dialog root with `weight = 1f`.
     */
    private fun buildDiffContent(diff: String): ScrollView {
        val verticalScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            background = CodeAssistTheme.roundedBg(
                activity, CodeAssistTheme.SURFACE, 8f, CodeAssistTheme.OUTLINE
            )
            // weight = 1 so the diff region takes every remaining pixel
            // below the fixed 150dp file list and the header.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val diffText = TextView(activity).apply {
            text = diff
            textSize = 10f
            setTextColor(CodeAssistTheme.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(
                CodeAssistTheme.dp(activity, 8), CodeAssistTheme.dp(activity, 8),
                CodeAssistTheme.dp(activity, 8), CodeAssistTheme.dp(activity, 8)
            )
            // Tell the TextView to measure at natural (unwrapped) width so
            // the outer HorizontalScrollView has something to scroll.
            setHorizontallyScrolling(true)
        }
        verticalScroll.addView(
            HorizontalScrollView(activity).apply { addView(diffText) }
        )
        return verticalScroll
    }

    /**
     * Builds a centered, muted placeholder used when there is nothing to
     * show: either the ref resolves to the same tree as the working
     * directory, or the repository could not be diffed at all.
     *
     * Vertical padding (24dp top and bottom) is intentionally generous so
     * the empty state occupies a similar footprint to a populated dialog
     * and does not cause the modal to visually collapse into a thin strip.
     *
     * @param value localized message to display. Resolved by the caller so
     *   this helper stays locale-agnostic.
     * @return a ready-to-add text view.
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
     * Shows a short [Toast] for the rejected-input path (empty or
     * unresolvable ref). Extracted so the two early returns in [show] stay
     * one-liners and share a single style.
     *
     * @param resourceId localized string resource to display.
     */
    private fun showMessage(resourceId: Int) {
        Toast.makeText(activity, activity.pluginString(resourceId), Toast.LENGTH_SHORT).show()
    }
}