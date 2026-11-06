package com.mipago.ex2center.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mipago.ex2center.utils.CarApiHelper
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Quick climate controls on the car's bottom bar: "[−] 24°C [+]   [−] Quạt 2 [+]".
 *
 * Replaces only the middle of the car's own bar (CarNavigationBar) — its unlabeled temperature
 * scale — and leaves every other button of the car (home, apps, energy, 360, HAZARD) untouched.
 *
 * - Window NAVIGATION_BAR_PANEL (2024, above the nav bar, allowed for android.uid.system),
 *   NOT_FOCUSABLE + NOT_TOUCH_MODAL: touches outside this panel go to the car's bar.
 * - Opaque in the bar's own colour (#12161A) so it reads as part of the bar.
 * - Tap: the value changes immediately, the write goes to the car off the main thread, then the
 *   2 s poll shows what the car confirmed (polling pauses briefly after a tap).
 * - Tapping a value (temperature or fan) opens the car's climate screen, as the car's own scale did.
 * - Hidden while the system hides the nav bar (full-screen apps) and during Blackout.
 * - Geometry tuned for the IHU629G (1920x1080, bar 968..1080, car scale at x 971..1180) and
 *   scaled with the real display size.
 */
class DockClimateOverlay(private val context: Context) {

    companion object {
        private const val TAG = "DockClimateOverlay"
        private const val TYPE_NAVIGATION_BAR_PANEL = 2024 // WindowManager.LayoutParams (hidden API)
        private const val HVAC_PACKAGE = "com.flyme.auto.hvac"
        private const val HVAC_ACTION_HOME = "com.flyme.auto.hvac.action.ACTION_CLIMATE_HOME"
        private const val POLL_MS = 2_000L
        private const val HOLD_AFTER_TAP_MS = 2_500L
        private const val LEFT_FRACTION = 890f / 1920f
        private const val RIGHT_FRACTION = 1290f / 1920f
        private const val BAR_HEIGHT_FRACTION = 112f / 1080f
        private const val TEMP_MIN = 17
        private const val TEMP_MAX = 32
        private const val FAN_MIN = 1
        private const val FAN_MAX = 8
        private val BAR_BG = Color.parseColor("#12161A")
        private val BTN_BG = Color.parseColor("#232A33")
        private val RIPPLE = Color.parseColor("#40FFFFFF")
        private val TEXT = Color.WHITE
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val car = CarApiHelper.getInstance()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var panel: View? = null
    private var tvTemp: TextView? = null
    private var tvFan: TextView? = null
    private var navHidden = false
    private var blocked = false
    private var temp: Int? = null
    private var fan: Int? = null
    private var lastTapAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()

    fun show() {
        if (panel != null) return
        val real = DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        val left = (real.widthPixels * LEFT_FRACTION).roundToInt()
        val width = (real.widthPixels * RIGHT_FRACTION).roundToInt() - left
        val height = (real.heightPixels * BAR_HEIGHT_FRACTION).roundToInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(BAR_BG)
            visibility = View.INVISIBLE
            @Suppress("DEPRECATION")
            setOnSystemUiVisibilityChangeListener { vis ->
                navHidden = vis and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0
                applyVisibility()
            }
        }
        tvTemp = value()
        tvFan = value()
        row.addView(group(tvTemp!!, { stepTemp(-1) }, { stepTemp(+1) }))
        row.addView(View(context), LinearLayout.LayoutParams(dp(20), 1))
        row.addView(group(tvFan!!, { stepFan(-1) }, { stepFan(+1) }))

        val params = WindowManager.LayoutParams(
            width, height, TYPE_NAVIGATION_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = left
            y = real.heightPixels - height
            title = "EX2DockClimate"
        }
        try {
            wm.addView(row, params)
            panel = row
            main.post(poll)
            Log.i(TAG, "Dock climate controls shown at $left..${left + width}, y=${params.y}")
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot add dock controls: ${t.message}")
        }
    }

    fun hide() {
        main.removeCallbacks(poll)
        panel?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        panel = null
        tvTemp = null
        tvFan = null
    }

    fun release() {
        hide()
        io.shutdownNow()
    }

    /** Blackout covers the screen: the panel (above it in z-order) must not show on black. */
    fun setBlocked(value: Boolean) {
        blocked = value
        applyVisibility()
    }

    // -------------------------------------------------------------------------------------------

    private fun value() = TextView(context).apply {
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 1
        text = "--"
        val shape = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE), null, shape)
        isClickable = true
        setOnClickListener { openClimateScreen() }
    }

    /** The car's own climate screen (what tapping the car's temperature scale used to open). */
    private fun openClimateScreen() {
        val intent = Intent(HVAC_ACTION_HOME).setPackage(HVAC_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Climate screen not available: ${t.message}")
            context.packageManager.getLaunchIntentForPackage(HVAC_PACKAGE)?.let {
                try { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
            }
        }
    }

    private fun stepButton(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        val shape = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(BTN_BG)
        }
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null)
        isClickable = true
        setOnClickListener { onTap() }
    }

    /** [−] value [+] with 56dp touch targets. */
    private fun group(valueView: TextView, minus: () -> Unit, plus: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(stepButton("−", minus), LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(valueView, LinearLayout.LayoutParams(dp(76), dp(56)))
        addView(stepButton("+", plus), LinearLayout.LayoutParams(dp(56), dp(56)))
    }

    private fun applyVisibility() {
        panel?.visibility = if (!navHidden && !blocked) View.VISIBLE else View.INVISIBLE
    }

    private fun render() {
        tvTemp?.setTextIfChanged(temp?.let { "$it°C" } ?: "--°C")
        tvFan?.setTextIfChanged(when (val f = fan) {
            null -> "Quạt --"
            0 -> "Quạt tắt"
            else -> "Quạt $f"
        })
    }

    private fun stepTemp(delta: Int) {
        val target = ((temp ?: return) + delta).coerceIn(TEMP_MIN, TEMP_MAX)
        if (target == temp) return
        temp = target
        lastTapAt = SystemClock.elapsedRealtime()
        render()
        io.execute { car.writeTargetTemperature(target.toFloat()) }
    }

    private fun stepFan(delta: Int) {
        val current = fan ?: return
        if (current == 0 && delta < 0) return // already off: "−" must not switch the fan on
        val target = (current + delta).coerceIn(FAN_MIN, FAN_MAX)
        if (target == fan) return
        fan = target
        lastTapAt = SystemClock.elapsedRealtime()
        render()
        io.execute { car.writeFanSpeed(target) }
    }

    private fun refresh() {
        if (panel == null) return
        // Right after a tap the car may still report the old value: keep showing the request.
        if (SystemClock.elapsedRealtime() - lastTapAt < HOLD_AFTER_TAP_MS) return
        try {
            io.execute {
                val t = car.readTargetTemperature()?.roundToInt()
                val f = car.readFanSpeed()
                main.post {
                    if (SystemClock.elapsedRealtime() - lastTapAt < HOLD_AFTER_TAP_MS) return@post
                    temp = t
                    fan = f
                    render()
                    applyVisibility()
                }
            }
        } catch (_: Throwable) {}
    }
}
