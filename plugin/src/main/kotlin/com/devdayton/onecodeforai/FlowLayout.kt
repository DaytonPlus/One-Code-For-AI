package com.devdayton.onecodeforai

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class FlowLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val horizontalSpacing = 12
    private val verticalSpacing = 12

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        
        // 🔥 FALTABA ESTO: Extraer el modo y tamaño de la altura
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        
        var width = 0
        var height = 0
        var lineWidth = 0
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            // Si el hijo no cabe en la línea actual, saltamos a la siguiente
            if (lineWidth + childWidth + horizontalSpacing > widthSize && lineWidth > 0) {
                width = Math.max(width, lineWidth)
                height += lineHeight + verticalSpacing
                lineWidth = childWidth + horizontalSpacing
                lineHeight = childHeight
            } else {
                lineWidth += childWidth + horizontalSpacing
                lineHeight = Math.max(lineHeight, childHeight)
            }
        }
        width = Math.max(width, lineWidth)
        height += lineHeight

        val finalWidth = if (widthMode == MeasureSpec.UNSPECIFIED) width else widthSize
        val finalHeight = if (heightMode == MeasureSpec.UNSPECIFIED) height else Math.min(height, heightSize)
        
        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0
        var y = 0
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            // Si el hijo no cabe a lo ancho, saltamos de línea
            if (x + childWidth + horizontalSpacing > width && x > 0) {
                x = 0
                y += lineHeight + verticalSpacing
                lineHeight = 0
            }
            
            child.layout(x, y, x + childWidth, y + childHeight)
            x += childWidth + horizontalSpacing
            lineHeight = Math.max(lineHeight, childHeight)
        }
    }
}