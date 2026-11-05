package com.mipago.ex2center.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.SeekBar
import androidx.core.content.res.ResourcesCompat
import com.mipago.ex2center.R

/**
 * Discrete SeekBar that shows every step: a notch on the track and a label under it.
 *
 * The driver can see (and tap) the exact position of e.g. 22°C or fan level 5 instead of
 * guessing where to drag. Tapping anywhere in the label strip jumps to the nearest step,
 * because the whole view (padding included) is the SeekBar's touch area.
 *
 * Layout contract: give the view equal paddingTop / paddingBottom (the bottom one holds the
 * labels) so the track stays vertically centred and lines up with the -/+ buttons beside it.
 */
@SuppressLint("AppCompatCustomView") // platform theme (no AppCompat), tint is not needed
class StepSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : SeekBar(context, attrs, defStyleAttr) {

    /** Value shown under step i (progress = min + i). Defaults to the progress value itself. */
    var labelFor: (Int) -> String = { it.toString() }
        set(value) { field = value; invalidate() }

    /** False while the car has not reported a value (or reports one off the scale, e.g. fan off):
     *  no step is highlighted then, so the scale never suggests a setting that is not real. */
    var hasValue: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface_card) // reads as a notch cut into the track
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(14f)
        color = context.getColor(R.color.text_muted)
        typeface = safeFont(R.font.be_vietnam_pro_medium)
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(16f)
        color = context.getColor(R.color.accent_emerald)
        typeface = safeFont(R.font.be_vietnam_pro_bold)
    }

    private fun safeFont(id: Int) = try { ResourcesCompat.getFont(context, id) } catch (_: Throwable) { null }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = max - min
        if (count < 1) return

        // Same geometry AbsSeekBar uses for the track and the thumb centre.
        val left = paddingLeft.toFloat()
        val usable = (width - paddingLeft - paddingRight).toFloat()
        val trackY = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val notchR = dp(2f)
        val labelBaseline = height - paddingBottom + dp(16f) // inside the bottom padding strip

        for (i in 0..count) {
            val x = left + usable * i / count
            val value = min + i
            val selected = hasValue && value == progress
            // The thumb covers the current step; the first/last notch would sit on the rounded track end.
            if (!selected && i != 0 && i != count) canvas.drawCircle(x, trackY, notchR, notchPaint)
            canvas.drawText(labelFor(value), x, labelBaseline, if (selected) selectedPaint else labelPaint)
        }
    }
}
