package com.mipago.ex2center.ui.dash

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.mipago.ex2center.R
import kotlin.math.roundToInt

/**
 * Fixed Bento grid: [columns] × [rows] equal cells separated by [gap], always filling 100% of
 * its own size (no scrolling, no layout_weight gaps). Each child spans whole cells through
 * `app:bento_col / bento_row / bento_colSpan / bento_rowSpan`.
 *
 * Every child is measured exactly once with EXACTLY specs, so a widget's internal text updates
 * never make its neighbours measure again — the cost that made the old Dashboard slow.
 */
class BentoGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var columns = 6
        set(value) { field = value.coerceAtLeast(1); requestLayout() }
    var rows = 3
        set(value) { field = value.coerceAtLeast(1); requestLayout() }
    var gap = 0
        set(value) { field = value.coerceAtLeast(0); requestLayout() }

    init {
        context.obtainStyledAttributes(attrs, R.styleable.BentoGridLayout).apply {
            columns = getInt(R.styleable.BentoGridLayout_bento_columns, 6)
            rows = getInt(R.styleable.BentoGridLayout_bento_rows, 3)
            gap = getDimensionPixelSize(R.styleable.BentoGridLayout_bento_gap, 0)
            recycle()
        }
    }

    class LayoutParams : ViewGroup.LayoutParams {
        var col = 0
        var row = 0
        var colSpan = 1
        var rowSpan = 1

        // The grid decides every child's size, so layout_width / layout_height are not needed in XML.
        constructor(c: Context, attrs: AttributeSet?) : super(MATCH_PARENT, MATCH_PARENT) {
            c.obtainStyledAttributes(attrs, R.styleable.BentoGridLayout_Layout).apply {
                col = getInt(R.styleable.BentoGridLayout_Layout_bento_col, 0)
                row = getInt(R.styleable.BentoGridLayout_Layout_bento_row, 0)
                colSpan = getInt(R.styleable.BentoGridLayout_Layout_bento_colSpan, 1)
                rowSpan = getInt(R.styleable.BentoGridLayout_Layout_bento_rowSpan, 1)
                recycle()
            }
        }

        constructor(col: Int, row: Int, colSpan: Int, rowSpan: Int) : super(MATCH_PARENT, MATCH_PARENT) {
            this.col = col; this.row = row; this.colSpan = colSpan; this.rowSpan = rowSpan
        }

        constructor(source: ViewGroup.LayoutParams) : super(source)
    }

    private fun cellW(width: Int) = (width - paddingLeft - paddingRight - gap * (columns - 1)).toFloat() / columns
    private fun cellH(height: Int) = (height - paddingTop - paddingBottom - gap * (rows - 1)).toFloat() / rows

    /** Pixel edge of track [i] (0..count), rounded once so neighbouring cells never leave a seam. */
    private fun edge(start: Int, cell: Float, i: Int) = (start + i * (cell + gap)).roundToInt()

    /** Pixel rectangle (in this view's coordinates) of a block of cells. */
    fun cellRect(col: Int, row: Int, colSpan: Int, rowSpan: Int, out: android.graphics.Rect) {
        val cw = cellW(width)
        val ch = cellH(height)
        out.set(
            edge(paddingLeft, cw, col), edge(paddingTop, ch, row),
            edge(paddingLeft, cw, col + colSpan) - gap, edge(paddingTop, ch, row + rowSpan) - gap
        )
    }

    /** Size of one cell plus the gap after it (the distance between two cell origins). */
    fun pitchX() = cellW(width) + gap
    fun pitchY() = cellH(height) + gap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = cellW(w)
        val ch = cellH(h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as LayoutParams
            val width = edge(0, cw, lp.col + lp.colSpan) - gap - edge(0, cw, lp.col)
            val height = edge(0, ch, lp.row + lp.rowSpan) - gap - edge(0, ch, lp.row)
            child.measure(
                MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height.coerceAtLeast(0), MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellW(r - l)
        val ch = cellH(b - t)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as LayoutParams
            val left = edge(paddingLeft, cw, lp.col)
            val top = edge(paddingTop, ch, lp.row)
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun shouldDelayChildPressedState() = false
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateDefaultLayoutParams() = LayoutParams(0, 0, 1, 1)
    override fun generateLayoutParams(attrs: AttributeSet?) = LayoutParams(context, attrs)
    override fun generateLayoutParams(p: ViewGroup.LayoutParams) = LayoutParams(p)
}
