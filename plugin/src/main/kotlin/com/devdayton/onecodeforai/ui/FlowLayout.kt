package com.devdayton.onecodeforai.ui

import com.devdayton.onecodeforai.ui.CodeAssistTheme

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * A [ViewGroup] that lays out its children left-to-right and wraps them onto
 * a new line whenever the next child would not fit within the available
 * width.
 *
 * Used by [ConfigurationDialog] to render the inclusion, exclusion, and
 * `.gitignore` tags, where the number of chips and their individual widths
 * are both unknown ahead of time. A plain [LinearLayout] would need a nested
 * horizontal scroller and manual wrapping logic; a `FlowLayout` handles both
 * in one measurement pass.
 *
 * ### Spacing model
 * Two fixed gaps, both taken from the plugin theme ([CodeAssistTheme.dp]) so
 * every chip section reads consistently:
 *
 *  - `horizontalSpacing` (8dp) is inserted **between** two children that
 *    share a line. It is never applied before the first child or after the
 *    last child on a line, so a line of one chip is exactly as wide as the
 *    chip plus its own margins.
 *  - `verticalSpacing` (8dp) is inserted **between** two lines. It is never
 *    applied above the first line or below the last line.
 *
 * ### Margins
 * Children must use [ViewGroup.MarginLayoutParams] — the factory overrides
 * below force this — so the layout can honour per-chip `marginStart` /
 * `marginEnd` when computing line width. This is what lets a chip leave a
 * gap to its neighbour *in addition to* the shared [horizontalSpacing]
 * without the caller having to encode that gap as a custom `View`.
 *
 * ### Visibility
 * Children with `visibility == View.GONE` are skipped in both measure and
 * layout, so hiding a chip via `View.GONE` reflows the line exactly as if
 * the chip had been removed. `INVISIBLE` children are still measured and
 * laid out (taking up their full slot) — this matches the platform
 * convention for `INVISIBLE` and is not overridden here.
 *
 * ### Measurement semantics
 * The measured size is the tightest box that contains every line:
 *  - **Width**: the maximum line width, so the parent can shrink-wrap the
 *    flow if it uses `wrap_content`.
 *  - **Height**: the sum of every line's height plus the inter-line gaps,
 *    so the parent can grow the container vertically as chips wrap.
 *
 * When the width mode is [View.MeasureSpec.UNSPECIFIED], the layout treats
 * the available width as infinite and never wraps. This makes the flow
 * usable inside a horizontally-scrolling parent without special-casing it.
 *
 * ### Deliberate limitations
 *  - **LTR only.** `marginStart` / `marginEnd` are applied as `leftMargin` /
 *    `rightMargin` and the cursor always advances rightward. The plugin UI
 *    is currently English and Spanish, both LTR; adding RTL support would
 *    require reading `layoutDirection` and mirroring the cursor without
 *    changing any public behaviour on LTR builds, so it is deferred.
 *  - **No per-child gravity.** Every child is placed at the top-left of its
 *    line slot. Chip sections do not need baseline or center alignment, and
 *    a `gravity` API would expand the public surface for no current consumer.
 *  - **No `measureChildBeforeLayout` hook.** Children are measured with
 *    `measureChildWithMargins` and the full parent spec, which is sufficient
 *    because none of the plugin's chips contain nested scrollable content.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /** Gap between two children on the same line, in pixels. */
    private val horizontalSpacing = CodeAssistTheme.dp(context, 8)

    /** Gap between two consecutive lines, in pixels. */
    private val verticalSpacing = CodeAssistTheme.dp(context, 8)

    /**
     * Returns [ViewGroup.MarginLayoutParams] when a child is added without
     * explicit params (e.g. via `addView(view)`).
     *
     * Margin support is required by [onMeasure] and [onLayout], which read
     * `leftMargin` / `rightMargin` / `topMargin` / `bottomMargin` to size
     * each slot. Returning plain `LayoutParams` here would cause the cast in
     * those methods to throw at measure time.
     */
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /**
     * Returns [ViewGroup.MarginLayoutParams] when a child is inflated from an
     * XML layout. The attribute set is parsed by
     * `MarginLayoutParams(Context, AttributeSet)`, so `android:layout_margin*`
     * attributes on the child are honoured automatically.
     */
    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        ViewGroup.MarginLayoutParams(context, attrs)

    /**
     * Wraps incoming params in [ViewGroup.MarginLayoutParams] when a caller
     * passes plain [ViewGroup.LayoutParams] via
     * `addView(view, params)`. Any existing margins are preserved through
     * the copy constructor, so a caller that already uses margin params
     * keeps them.
     */
    override fun generateLayoutParams(p: ViewGroup.LayoutParams): ViewGroup.LayoutParams =
        ViewGroup.MarginLayoutParams(p)

    /**
     * Reports whether [p] is compatible with this group's layout contract.
     * Only [ViewGroup.MarginLayoutParams] (or a subclass) is accepted,
     * mirroring the [generateDefaultLayoutParams] contract above.
     */
    override fun checkLayoutParams(p: ViewGroup.LayoutParams): Boolean =
        p is ViewGroup.MarginLayoutParams

    /**
     * Measures every child and resolves this view's dimensions.
     *
     * ### Algorithm
     * A single pass over the children accumulates two "current line" values:
     *
     *  - `lineWidth`: the total width consumed by the children on the
     *    current line, **including** their horizontal margins and the
     *    `horizontalSpacing` between consecutive children. It starts at zero
     *    for each new line.
     *  - `lineHeight`: the tallest child on the current line (including its
     *    vertical margins). Used only when the line is finalized to advance
     *    the accumulated content height.
     *
     * For each child, the algorithm computes the width the line **would
     * have** if the child were added (`proposedWidth`). If that exceeds the
     * available width and the line already has at least one child, the
     * current line is committed (its width and height are folded into the
     * running maxima / totals) and a fresh line is started with the child
     * as its first element. Otherwise the child is appended to the current
     * line and `lineWidth` / `lineHeight` are updated in place.
     *
     * The `hasChildInLine` flag distinguishes "the line is empty" (where a
     * wrap is meaningless) from "the line has content" (where a wrap
     * matters), and is also what tells the finalization block at the bottom
     * whether the last line was ever populated.
     *
     * ### Measure specs
     * Children are measured with `measureChildWithMargins` and the parent's
     * spec, which respects the child's own `layout_width` /
     * `layout_height` (typically `WRAP_CONTENT` for chips). The parent spec
     * is passed through rather than reconstructed so a child that declares
     * `MATCH_PARENT` still sees the correct available width.
     *
     * ### UNSPECIFIED width
     * When the parent imposes no width limit (typical inside a horizontal
     * scroll view), `availableWidth` becomes [Int.MAX_VALUE] and the wrap
     * check can never trigger. The children still contribute to
     * `measuredContentWidth` so the resulting dimension is the natural
     * width of a single unwrapped line — which is what a horizontally
     * scrolling parent expects.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }

        var lineWidth = 0
        var lineHeight = 0
        var measuredContentWidth = 0
        var measuredContentHeight = 0
        var hasChildInLine = false

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            // GONE children must not contribute to the line metrics,
            // exactly as if they had been removed from the hierarchy.
            if (child.visibility == View.GONE) continue

            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as ViewGroup.MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            // If the line already has at least one child, the proposed
            // width includes the horizontal gap between it and this child;
            // otherwise the child starts the line by itself.
            val proposedWidth = if (hasChildInLine) lineWidth + horizontalSpacing + childWidth else childWidth

            if (hasChildInLine && proposedWidth > availableWidth) {
                // Commit the current line and start a new one with this
                // child as the first element.
                measuredContentWidth = maxOf(measuredContentWidth, lineWidth)
                measuredContentHeight += lineHeight + verticalSpacing
                lineWidth = childWidth
                lineHeight = childHeight
                hasChildInLine = true
            } else {
                // Append the child to the current line.
                lineWidth = proposedWidth
                lineHeight = maxOf(lineHeight, childHeight)
                hasChildInLine = true
            }
        }

        // Commit the last (possibly only) line, if any child was measured.
        if (hasChildInLine) {
            measuredContentWidth = maxOf(measuredContentWidth, lineWidth)
            measuredContentHeight += lineHeight
        }

        val desiredWidth = measuredContentWidth + paddingLeft + paddingRight
        val desiredHeight = measuredContentHeight + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    /**
     * Places every child into the slots produced by the line-breaking logic
     * in [onMeasure].
     *
     * ### Algorithm
     * The cursor `x` starts at `paddingLeft` and advances rightward after
     * each child by its width plus its right margin plus
     * [horizontalSpacing]. Before laying out a child, the algorithm checks
     * whether the child's full slot (its own width plus both margins) would
     * overflow the content area; if it would and the line already has at
     * least one child, the cursor resets to `paddingLeft` and `y` advances
     * by the previous line's height plus [verticalSpacing].
     *
     * The wrap decision is made **before** applying the child's left
     * margin, so a child that is wider than the entire content area still
     * gets placed (on its own line) rather than wrapping forever. This
     * matches the behaviour of the standard LinearLayout and prevents
     * pathological child sizes from producing an infinite layout loop.
     *
     * ### GONE children
     * Skipped, mirroring [onMeasure]. Because both methods iterate in the
     * same order and skip the same children, the wrap decisions here exactly
     * reproduce the ones made during measurement, so a child that fit on a
     * line during measure also fits on that line during layout.
     *
     * ### Trailing spacing
     * After the last child on a line, `x` is advanced past the child's
     * right margin plus a trailing [horizontalSpacing] that is never used
     * by anyone. This is harmless (the cursor is reset on the next wrap)
     * and lets the loop body stay uniform for every child.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentWidth = right - left - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        var hasChildInLine = false

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue

            val lp = child.layoutParams as ViewGroup.MarginLayoutParams
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val totalWidth = childWidth + lp.leftMargin + lp.rightMargin
            val totalHeight = childHeight + lp.topMargin + lp.bottomMargin

            // Wrap before placing this child if its full slot (including
            // both margins) would exceed the content area. The check uses
            // the x position that already includes the trailing spacing
            // from the previous child, so it correctly accounts for the
            // gap that a wrap would replace.
            if (hasChildInLine && x - paddingLeft + totalWidth > contentWidth) {
                x = paddingLeft
                y += lineHeight + verticalSpacing
                lineHeight = 0
                hasChildInLine = false
            }

            x += lp.leftMargin
            val childTop = y + lp.topMargin
            child.layout(x, childTop, x + childWidth, childTop + childHeight)
            x += childWidth + lp.rightMargin + horizontalSpacing
            lineHeight = maxOf(lineHeight, totalHeight)
            hasChildInLine = true
        }
    }
}