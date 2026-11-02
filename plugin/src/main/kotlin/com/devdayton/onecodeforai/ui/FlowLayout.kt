package com.devdayton.onecodeforai.ui

import com.devdayton.onecodeforai.ui.CodeAssistTheme

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Contenedor de etiquetas que distribuye sus hijos en varias filas. */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val horizontalSpacing = CodeAssistTheme.dp(context, 8)
    private val verticalSpacing = CodeAssistTheme.dp(context, 8)

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        ViewGroup.MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): ViewGroup.LayoutParams =
        ViewGroup.MarginLayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams): Boolean =
        p is ViewGroup.MarginLayoutParams

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
            if (child.visibility == View.GONE) continue

            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as ViewGroup.MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            val proposedWidth = if (hasChildInLine) lineWidth + horizontalSpacing + childWidth else childWidth

            if (hasChildInLine && proposedWidth > availableWidth) {
                measuredContentWidth = maxOf(measuredContentWidth, lineWidth)
                measuredContentHeight += lineHeight + verticalSpacing
                lineWidth = childWidth
                lineHeight = childHeight
                hasChildInLine = true
            } else {
                lineWidth = proposedWidth
                lineHeight = maxOf(lineHeight, childHeight)
                hasChildInLine = true
            }
        }

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

            if (hasChildInLine && x - paddingLeft + horizontalSpacing + totalWidth > contentWidth) {
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
