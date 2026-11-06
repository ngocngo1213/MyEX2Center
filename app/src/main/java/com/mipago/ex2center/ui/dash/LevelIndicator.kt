package com.mipago.ex2center.ui.dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.mipago.ex2center.R

/**
 * Small read-only level display drawn in one pass (no child views):
 * - [Mode.TICKS]: one thin tick per step, the current step taller and in the accent
 *   (temperature 17–32 °C),
 * - [Mode.BARS]: rising bars, filled up to the current level (fan 1–8).
 * A null [value] draws every step neutral, so the scale never suggests a setting the car did not report.
 */
class LevelIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { TICKS, BARS }

    var mode = Mode.TICKS
        set(v) { field = v; invalidate() }
    var steps = 16
        set(v) { field = v.coerceAtLeast(1); invalidate() }
    /** 0-based index of the current step (TICKS) or number of filled bars (BARS); null = unknown. */
    var value: Int? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var activeColor = context.getColor(R.color.dash_accent_text)
        set(v) { field = v; invalidate() }

    private val inactive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.dash_line) }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        active.color = activeColor
        val d = density
        if (mode == Mode.TICKS) {
            val w = 4 * d
            val gapPx = 7 * d
            val total = steps * w + (steps - 1) * gapPx
            var x = (width - total) / 2f
            for (i in 0 until steps) {
                val on = value == i
                val h = (if (on) 26 else 14) * d
                rect.set(x, height - h, x + w, height.toFloat())
                canvas.drawRoundRect(rect, 2 * d, 2 * d, if (on) active else inactive)
                x += w + gapPx
            }
        } else {
            val w = 14 * d
            val gapPx = 5 * d
            val total = steps * w + (steps - 1) * gapPx
            var x = (width - total) / 2f
            val filled = value ?: 0
            for (i in 0 until steps) {
                val h = ((12 + i * 4) * d).coerceAtMost(height.toFloat())
                rect.set(x, height - h, x + w, height.toFloat())
                canvas.drawRoundRect(rect, 3 * d, 3 * d, if (i < filled) active else inactive)
                x += w + gapPx
            }
        }
    }
}
