package com.mipago.ex2center.ui.dash

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.mipago.ex2center.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Edit-mode layer drawn on top of the Bento grid (same bounds). It takes every touch while
 * editing, so a widget's own buttons can never fire by accident.
 *
 * - Each widget: dashed accent outline, "−" (hide) in the top-left corner and "⇲" (next size)
 *   in the top-right corner when the widget has other sizes. Handles are 56dp touch targets.
 * - Empty cells: dashed slot with "+" (tap = show the tray of hidden widgets).
 * - Drag a widget: it follows the finger (translation only, no relayout while dragging, so it
 *   stays smooth on the head unit); the target slot is outlined; on release the host decides
 *   (move / swap / push aside / bounce back) and relays the grid out once.
 */
class EditOverlay(context: Context) : View(context) {

    interface Host {
        fun items(): List<Placement>
        fun viewFor(p: Placement): View?
        fun onRemove(p: Placement)
        fun onResize(p: Placement)
        fun onMove(p: Placement, col: Int, row: Int)
        fun onEmptyTap()
    }

    lateinit var grid: BentoGridLayout
    var host: Host? = null

    private val d = resources.displayMetrics.density
    private val accent = context.getColor(R.color.dash_accent)
    private val handleR = 26 * d
    private val hitR = 34 * d
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        color = accent
        pathEffect = DashPathEffect(floatArrayOf(14 * d, 10 * d), 0f)
    }
    private val slotLine = Paint(outline).apply {
        color = context.getColor(R.color.dash_line)
        strokeWidth = 2 * d
    }
    private val targetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0x00FFFFFF) or 0x33000000 }
    private val targetLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * d
        color = accent
    }
    private val badTarget = Paint(targetLine).apply { color = context.getColor(R.color.dash_danger) }
    private val removeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD7262E.toInt() }
    private val resizeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.dash_text) }
    private val glyphOnRemove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 4 * d
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val glyphOnResize = Paint(glyphOnRemove).apply { color = context.getColor(R.color.dash_bg) }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dash_muted)
        strokeWidth = 3 * d
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = Rect()
    private val rf = RectF()

    // Touch state
    private var downX = 0f
    private var downY = 0f
    private var pressedHandle: Pair<Placement, Boolean>? = null // (widget, isRemove)
    private var dragItem: Placement? = null
    private var dragView: View? = null
    private var dragging = false
    private var targetCol = 0
    private var targetRow = 0
    private var targetValid = true

    override fun onDraw(canvas: Canvas) {
        val h = host ?: return
        val items = h.items()

        // Empty cells
        for (r in 0 until DashLayout.ROWS) for (c in 0 until DashLayout.COLUMNS) {
            if (items.any { it.covers(c, r) }) continue
            grid.cellRect(c, r, 1, 1, rect)
            rf.set(rect)
            rf.inset(4 * d, 4 * d)
            canvas.drawRoundRect(rf, 16 * d, 16 * d, slotLine)
            val cx = rf.centerX()
            val cy = rf.centerY()
            canvas.drawLine(cx - 16 * d, cy, cx + 16 * d, cy, plus)
            canvas.drawLine(cx, cy - 16 * d, cx, cy + 16 * d, plus)
        }

        // Drop target while dragging
        val drag = dragItem
        if (dragging && drag != null) {
            grid.cellRect(targetCol, targetRow, drag.colSpan, drag.rowSpan, rect)
            rf.set(rect)
            canvas.drawRoundRect(rf, 16 * d, 16 * d, targetFill)
            canvas.drawRoundRect(rf, 16 * d, 16 * d, if (targetValid) targetLine else badTarget)
        }

        // Widgets: outline + handles (not on the one being dragged)
        for (p in items) {
            if (dragging && p == drag) continue
            grid.cellRect(p.col, p.row, p.colSpan, p.rowSpan, rect)
            rf.set(rect)
            rf.inset(2 * d, 2 * d)
            canvas.drawRoundRect(rf, 16 * d, 16 * d, outline)
            drawRemove(canvas, rect.left + handleR * 0.6f, rect.top + handleR * 0.6f)
            if (p.type.variants.size > 1) drawResize(canvas, rect.right - handleR * 0.6f, rect.top + handleR * 0.6f)
        }
    }

    private fun drawRemove(c: Canvas, x: Float, y: Float) {
        c.drawCircle(x, y, handleR, removeFill)
        c.drawLine(x - 11 * d, y, x + 11 * d, y, glyphOnRemove)
    }

    private fun drawResize(c: Canvas, x: Float, y: Float) {
        c.drawCircle(x, y, handleR, resizeFill)
        val a = 10 * d
        c.drawLine(x - a, y + a, x + a, y - a, glyphOnResize)
        c.drawLine(x + a, y - a, x + a - 9 * d, y - a, glyphOnResize)
        c.drawLine(x + a, y - a, x + a, y - a + 9 * d, glyphOnResize)
        c.drawLine(x - a, y + a, x - a + 9 * d, y + a, glyphOnResize)
        c.drawLine(x - a, y + a, x - a, y + a - 9 * d, glyphOnResize)
    }

    /** Handle under (x, y): widget and whether it is the remove handle. */
    private fun handleAt(x: Float, y: Float): Pair<Placement, Boolean>? {
        val items = host?.items() ?: return null
        for (p in items) {
            grid.cellRect(p.col, p.row, p.colSpan, p.rowSpan, rect)
            val rx = rect.left + handleR * 0.6f
            val ry = rect.top + handleR * 0.6f
            if (hypot(x - rx, y - ry) <= hitR) return p to true
            if (p.type.variants.size > 1) {
                val sx = rect.right - handleR * 0.6f
                if (hypot(x - sx, y - ry) <= hitR) return p to false
            }
        }
        return null
    }

    private fun itemAt(x: Float, y: Float): Placement? = host?.items()?.firstOrNull { p ->
        grid.cellRect(p.col, p.row, p.colSpan, p.rowSpan, rect)
        rect.contains(x.toInt(), y.toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = host ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                pressedHandle = handleAt(e.x, e.y)
                dragItem = if (pressedHandle == null) itemAt(e.x, e.y) else null
                dragView = dragItem?.let { h.viewFor(it) }
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val item = dragItem ?: return true
                val view = dragView ?: return true
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    view.animate().scaleX(1.03f).scaleY(1.03f).setDuration(80).start()
                    view.translationZ = 24 * d
                }
                if (dragging) {
                    view.translationX = dx
                    view.translationY = dy
                    targetCol = (item.col + dx / grid.pitchX()).roundToInt()
                        .coerceIn(0, DashLayout.COLUMNS - item.colSpan)
                    targetRow = (item.row + dy / grid.pitchY()).roundToInt()
                        .coerceIn(0, DashLayout.ROWS - item.rowSpan)
                    targetValid = true
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val handle = pressedHandle
                if (handle != null) {
                    if (handleAt(e.x, e.y) == handle) {
                        if (handle.second) h.onRemove(handle.first) else h.onResize(handle.first)
                    }
                } else if (dragging) {
                    val item = dragItem
                    resetDrag()
                    if (item != null && (targetCol != item.col || targetRow != item.row)) h.onMove(item, targetCol, targetRow)
                } else if (dragItem == null && abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
                    h.onEmptyTap()
                } else {
                    resetDrag()
                }
                pressedHandle = null
                dragItem = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                resetDrag()
                pressedHandle = null
                dragItem = null
                invalidate()
            }
        }
        return true
    }

    private fun resetDrag() {
        dragView?.let { v ->
            v.animate().translationX(0f).translationY(0f).scaleX(0.97f).scaleY(0.97f).setDuration(120).start()
            v.translationZ = 0f
        }
        dragView = null
        dragging = false
    }
}
