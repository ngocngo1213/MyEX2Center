package com.mipago.ex2center.ui

import android.annotation.SuppressLint
import com.mipago.ex2center.utils.AppDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mipago.ex2center.R
import com.mipago.ex2center.utils.CarApiHelper
import com.mipago.ex2center.utils.ParkGate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Module 2: Overlay View Manager
 *
 * Manages the floating bubble, the expandable in-car quick control panel
 * (Climate, Temperature, Fan, Outside Temp), the safe Reboot shortcut,
 * and the 100% Blackout Mode with single-tap restore.
 */
class OverlayViewManager(private val context: Context) {

    companion object {
        const val TAG = "OverlayViewManager"
        private const val BLACKOUT_GEAR_CHECK_MS = 500L
        private const val BUBBLE_SIZE_DP = 64
        private const val BUBBLE_TOP_CLEARANCE_DP = 112   // header band of every screen
        private const val BUBBLE_BOTTOM_CLEARANCE_DP = 96 // gesture bar / bottom buttons
        private const val BUBBLE_PREFS = "ex2_overlay"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val carHelper = CarApiHelper.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var panelView: View? = null
    private var blackoutView: View? = null
    private var splitControlView: View? = null

    private var bubbleLayoutParams: WindowManager.LayoutParams? = null
    private var splitControlLayoutParams: WindowManager.LayoutParams? = null
    private var isPanelOpen = false
    private var isBlackoutActive = false

    // Safety watcher: blackout must never cover the reverse camera.
    private val gearWatchExecutor = Executors.newSingleThreadExecutor()
    private val gearCheckInFlight = AtomicBoolean(false)
    private val blackoutGearWatch = object : Runnable {
        override fun run() {
            if (!isBlackoutActive) return
            if (gearCheckInFlight.compareAndSet(false, true)) {
                gearWatchExecutor.execute {
                    val mustDismiss = try {
                        shouldAutoDismissBlackout()
                    } finally {
                        gearCheckInFlight.set(false)
                    }
                    if (mustDismiss) {
                        mainHandler.post {
                            if (isBlackoutActive) {
                                Log.w(TAG, "Blackout auto-dismissed (reverse gear / car link lost).")
                                deactivateBlackoutMode()
                            }
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, BLACKOUT_GEAR_CHECK_MS)
        }
    }

    /** Blocking VHAL read — runs on [gearWatchExecutor]. */
    private fun shouldAutoDismissBlackout(): Boolean {
        if (!CarApiHelper.isCarApiPresent) return false // no vehicle (emulator)
        if (!carHelper.isReady) return true // cannot prove we're not reversing → fail safe
        return carHelper.readGear() == CarApiHelper.GEAR_REVERSE
    }

    // Car-reported state for the quick panel (null = unknown, never assumed).
    private var currentTemp: Float? = null
    private var currentFanSpeed: Int? = null
    private var isAcOn: Boolean? = null
    private var outsideTemp: Float? = null

    // All VHAL reads/writes for the panel & split bar run here, never on the main thread.
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private fun getOverlayWindowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /**
     * Initializes and displays the floating draggable bubble.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (bubbleView != null) return

        val size = dpToPx(BUBBLE_SIZE_DP)
        val metrics = context.resources.displayMetrics
        // Default: right edge, lower third — clear of the gear selector / HUD and of the
        // top status bar of navigation apps. A dragged position is remembered.
        val defaultX = metrics.widthPixels - size - dpToPx(12)
        val defaultY = metrics.heightPixels - size - dpToPx(152)
        val bubblePrefs = context.getSharedPreferences(BUBBLE_PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size, size,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = snapToEdge(bubblePrefs.getInt(KEY_BUBBLE_X, defaultX), bubblePrefs.getInt(KEY_BUBBLE_Y, defaultY), size)
            x = saved.first
            y = saved.second
        }
        bubbleLayoutParams = params

        // Create bubble circular container
        val bubble = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // Amber disc with a white ring: same accent as the dashboard, readable on any app.
                colors = intArrayOf(Color.parseColor("#F7BE5E"), Color.parseColor("#E0921F"))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dpToPx(2), Color.WHITE)
            }
            elevation = dpToPx(8).toFloat()
        }

        // A vector glyph sized for the circle — the square launcher artwork (rounded frame,
        // transparent corners) looked like a misplaced tile inside the round bubble.
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble_ex2)
            setColorFilter(Color.parseColor("#1B1205")) // dark glyph: white on amber is too faint
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dpToPx(16)
            setPadding(pad, pad, pad, pad)
            contentDescription = "EX2 Center – điều khiển nhanh"
        }
        bubble.addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Dragging & Click Handling
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = bubbleLayoutParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = p.x
                        initialY = p.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                        }
                        p.x = initialX + dx
                        p.y = initialY + dy
                        windowManager.updateViewLayout(bubble, p)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            togglePanel()
                        } else {
                            // Snap to the nearest side edge and keep clear of header/gesture bar.
                            val (sx, sy) = snapToEdge(p.x, p.y, size)
                            p.x = sx
                            p.y = sy
                            try { windowManager.updateViewLayout(bubble, p) } catch (_: Throwable) {}
                            bubblePrefs.edit().putInt(KEY_BUBBLE_X, p.x).putInt(KEY_BUBBLE_Y, p.y).apply()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(bubble, params)
            bubbleView = bubble
            Log.i(TAG, "Floating bubble displayed successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "Error displaying bubble: ${t.message}", t)
        }
    }

    /**
     * Bubble placement for a landscape tablet: always docked to the left or right edge
     * (12dp inset), never inside the top header band (where every screen has its title,
     * back and action buttons) nor over the bottom gesture area.
     */
    private fun snapToEdge(x: Int, y: Int, size: Int): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        val inset = dpToPx(12)
        val minY = dpToPx(BUBBLE_TOP_CLEARANCE_DP)
        val maxY = (m.heightPixels - size - dpToPx(BUBBLE_BOTTOM_CLEARANCE_DP)).coerceAtLeast(minY)
        val snappedX = if (x + size / 2 < m.widthPixels / 2) inset else m.widthPixels - size - inset
        return snappedX to y.coerceIn(minY, maxY)
    }

    fun hideBubble() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Throwable) {}
        }
        bubbleView = null
    }

    private fun togglePanel() {
        if (isPanelOpen) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    /**
     * Shows the expanded quick control panel.
     */
    private fun showPanel() {
        if (panelView != null || isPanelOpen) return
        isPanelOpen = true
        runIo {
            readCurrentCarState()
            mainHandler.post {
                if (isPanelOpen && panelView == null) attachPanel()
            }
        }
    }

    private fun attachPanel() {
        val width = dpToPx(440)
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val panel = createPanelView()
        try {
            windowManager.addView(panel, params)
            panelView = panel
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to display overlay panel: ${t.message}", t)
            isPanelOpen = false
        }
    }

    fun hidePanel() {
        panelView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Throwable) {}
        }
        panelView = null
        isPanelOpen = false
    }

    /** Runs on [ioExecutor]. */
    private fun readCurrentCarState() {
        try {
            val ready = carHelper.isReady
            val ac = if (ready) carHelper.isAcCompressorOn() else null
            val temp = if (ready) carHelper.readTargetTemperature() else null
            val fan = if (ready) carHelper.readFanSpeed() else null
            val outside = if (ready) carHelper.readOutsideTemperature() else null
            mainHandler.post {
                isAcOn = ac
                currentTemp = temp
                currentFanSpeed = fan
                outsideTemp = outside
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error reading state for overlay: ${t.message}")
        }
    }

    private fun runIo(block: () -> Unit) {
        try {
            ioExecutor.execute(block)
        } catch (_: Throwable) {} // released
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun tempText() = currentTemp?.let { "${Math.round(it)}°C" } ?: "--°C"

    private fun fanText() = when (val f = currentFanSpeed) {
        null -> "--"
        0 -> "Tắt"
        else -> "Cấp $f"
    }

    private fun createPanelView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.surface_card)) // Dark slate
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), context.getColor(R.color.btn_inactive))
            }
        }

        // Header: Title and Close button
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.app_name) + " - Bảng điều khiển"
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(context.getColor(R.color.text_secondary))
            contentDescription = "Đóng"
            val pad = dpToPx(12)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
            setOnClickListener { hidePanel() }
        }
        header.addView(title)
        header.addView(btnClose)
        root.addView(header)

        // Outside Temp readout
        val outside = outsideTemp
        val outsideText = TextView(context).apply {
            text = if (outside != null) {
                context.getString(R.string.overlay_outside_temp, String.format(java.util.Locale.US, "%.1f", outside))
            } else {
                "Ngoài trời: --°C"
            }
            setTextColor(context.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        root.addView(outsideText)

        // --- CLIMATE SECTION ---
        val climateCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.card_inner_surface))
                cornerRadius = dpToPx(12).toFloat()
            }
        }

        // AC Toggle button
        val btnAcToggle = Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(56))
            updateAcButtonAppearance(this, isAcOn == true)
            alpha = if (isAcOn == null) 0.6f else 1f
            setOnClickListener {
                val target = isAcOn != true
                val button = this
                runIo {
                    val ok = carHelper.setAcCompressorOn(target)
                    mainHandler.post {
                        if (ok) {
                            isAcOn = target
                            button.alpha = 1f
                            updateAcButtonAppearance(button, target)
                        } else {
                            toast("Xe không nhận lệnh: A/C")
                        }
                    }
                }
            }
        }
        climateCard.addView(btnAcToggle)

        // Temperature Row
        val tempRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(8), 0, dpToPx(8))
        }
        val lblTemp = TextView(context).apply {
            text = context.getString(R.string.overlay_temp_label)
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnTempMinus = createRoundButton("-")
        val txtTemp = TextView(context).apply {
            text = tempText()
            setTextColor(context.getColor(R.color.accent_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
        }
        val btnTempPlus = createRoundButton("+")

        fun nudgeTemp(delta: Int) {
            val cur = currentTemp
            if (cur == null) {
                toast("Chưa đọc được nhiệt độ từ xe.")
                return
            }
            val target = (Math.round(cur) + delta).coerceIn(17, 32).toFloat()
            runIo {
                val ok = carHelper.writeTargetTemperature(target)
                mainHandler.post {
                    if (ok) {
                        currentTemp = target
                        txtTemp.text = tempText()
                    } else {
                        toast("Xe không nhận lệnh: Nhiệt độ")
                    }
                }
            }
        }
        btnTempMinus.setOnClickListener { nudgeTemp(-1) }
        btnTempPlus.setOnClickListener { nudgeTemp(+1) }

        tempRow.addView(lblTemp)
        tempRow.addView(btnTempMinus)
        tempRow.addView(txtTemp)
        tempRow.addView(btnTempPlus)
        climateCard.addView(tempRow)

        // Fan Speed Row
        val fanRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(8), 0, dpToPx(8))
        }
        val lblFan = TextView(context).apply {
            text = context.getString(R.string.overlay_fan_label)
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnFanMinus = createRoundButton("-")
        val txtFan = TextView(context).apply {
            text = fanText()
            setTextColor(context.getColor(R.color.accent_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
        }
        val btnFanPlus = createRoundButton("+")

        fun nudgeFan(delta: Int) {
            val cur = currentFanSpeed
            if (cur == null) {
                toast("Chưa đọc được mức quạt từ xe.")
                return
            }
            val target = (cur + delta).coerceIn(0, 8)
            if (target == cur) return
            runIo {
                val ok = carHelper.writeFanSpeed(target)
                mainHandler.post {
                    if (ok) {
                        currentFanSpeed = target
                        txtFan.text = fanText()
                    } else {
                        toast("Xe không nhận lệnh: Quạt gió")
                    }
                }
            }
        }
        btnFanMinus.setOnClickListener { nudgeFan(-1) }
        btnFanPlus.setOnClickListener { nudgeFan(+1) }

        fanRow.addView(lblFan)
        fanRow.addView(btnFanMinus)
        fanRow.addView(txtFan)
        fanRow.addView(btnFanPlus)
        climateCard.addView(fanRow)

        root.addView(climateCard)

        // --- ACTIONS SECTION (BLACKOUT & REBOOT) ---
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(12), 0, 0)
        }

        // Blackout button
        val btnBlackout = Button(context).apply {
            text = context.getString(R.string.overlay_blackout)
            setTextColor(context.getColor(R.color.text_primary))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.btn_inactive))
                cornerRadius = dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(56), 1f).apply {
                marginEnd = dpToPx(8)
            }
            setOnClickListener {
                hidePanel()
                activateBlackoutMode()
            }
        }

        // Safe Reboot button
        val btnReboot = Button(context).apply {
            text = context.getString(R.string.overlay_reboot)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.accent_danger))
                cornerRadius = dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(56), 1f)
            setOnClickListener {
                confirmAndReboot()
            }
        }

        actionsRow.addView(btnBlackout)
        actionsRow.addView(btnReboot)
        root.addView(actionsRow)

        return root
    }

    private fun updateAcButtonAppearance(btn: Button, active: Boolean) {
        if (active) {
            btn.text = "Điều hòa A/C: ĐANG BẬT"
            btn.setTextColor(context.getColor(R.color.text_on_accent))
            btn.background = GradientDrawable().apply {
                setColor(context.getColor(R.color.accent_primary))
                cornerRadius = dpToPx(8).toFloat()
            }
        } else {
            btn.text = "Điều hòa A/C: ĐANG TẮT"
            btn.setTextColor(context.getColor(R.color.text_secondary))
            btn.background = GradientDrawable().apply {
                setColor(context.getColor(R.color.btn_inactive))
                cornerRadius = dpToPx(8).toFloat()
            }
        }
    }

    private fun createRoundButton(label: String): Button {
        return Button(context).apply {
            text = label
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.btn_inactive))
                cornerRadius = dpToPx(28).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(56))
            setPadding(0, 0, 0, 0)
        }
    }

    /**
     * MODULE 2: BLACKOUT MODE (Màn hình đêm)
     * Overlays an absolute 100% black layer (#000000) over the entire screen to eliminate cabin glare.
     * Single tap anywhere instantly dismisses the blackout layer and restores visibility.
     */
    fun activateBlackoutMode() {
        if (isBlackoutActive || blackoutView != null) return
        isBlackoutActive = true

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        val blackout = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                deactivateBlackoutMode()
            }
        }
        // Faint hint for the first 3 s so the driver knows how to get the screen back.
        val hint = TextView(context).apply {
            text = "Màn hình đêm • Chạm bất kỳ đâu để thoát"
            setTextColor(context.getColor(R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        blackout.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        )
        hint.animate().alpha(0f).setStartDelay(3000L).setDuration(800L).start()

        try {
            windowManager.addView(blackout, params)
            blackoutView = blackout
            mainHandler.removeCallbacks(blackoutGearWatch)
            mainHandler.post(blackoutGearWatch)
            Log.i(TAG, "Blackout mode activated.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to activate blackout view: ${t.message}", t)
            isBlackoutActive = false
        }
    }

    /**
     * Called after the Blackout layer or the split bar is closed, so the owning service can stop
     * itself when nothing is left on screen (e.g. the bubble is disabled in Settings).
     */
    var onOverlayClosed: (() -> Unit)? = null

    val isBlackoutShowing: Boolean get() = blackoutView != null

    /** True while any overlay window (bubble, panel, Blackout, split bar) is attached. */
    val hasVisibleOverlay: Boolean
        get() = bubbleView != null || panelView != null || blackoutView != null || splitControlView != null

    /** Removes every overlay window and stops background work. Call from the owner's onDestroy. */
    fun release() {
        onOverlayClosed = null
        hideBubble()
        hidePanel()
        hideSplitControlBar()
        deactivateBlackoutMode()
        mainHandler.removeCallbacksAndMessages(null)
        gearWatchExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }

    fun deactivateBlackoutMode() {
        val wasShown = blackoutView != null
        blackoutView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Throwable) {}
        }
        blackoutView = null
        isBlackoutActive = false
        mainHandler.removeCallbacks(blackoutGearWatch)
        Log.i(TAG, "Blackout mode deactivated.")
        if (wasShown) onOverlayClosed?.invoke()
    }

    // -------------------------------------------------------------
    // FEATURE 3: VERTICAL PIP SPLIT-SCREEN CONTROL BAR
    // -------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    fun showSplitControlBar() {
        if (splitControlView != null) return

        // 4 icon buttons of 56dp + 12dp gaps + 8dp padding (8pt grid)
        val barW = dpToPx(72)
        val barH = dpToPx(288)

        val params = WindowManager.LayoutParams(
            barW, barH,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }
        splitControlLayoutParams = params

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(context.getColor(R.color.overlay_bg)) // Obsidian semi-transparent
                setStroke(dpToPx(1), context.getColor(R.color.accent_primary)) // Emerald border
            }
            val pad = dpToPx(8)
            setPadding(pad, dpToPx(8), pad, dpToPx(0))
            elevation = dpToPx(8).toFloat()
        }

        fun createPillButton(iconRes: Int, colorRes: Int, label: String, onClick: () -> Unit): ImageView {
            return ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(context.getColor(colorRes))
                contentDescription = label
                val pad = dpToPx(16)
                setPadding(pad, pad, pad, pad)
                val btnSize = dpToPx(56)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { bottomMargin = dpToPx(12) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(context.getColor(R.color.btn_inactive))
                    setStroke(dpToPx(1), context.getColor(R.color.btn_inactive_stroke))
                }
                setOnClickListener { onClick() }
            }
        }

        // 1. Swap Apps
        val btnSwap = createPillButton(R.drawable.ic_swap, R.color.accent_primary, "Đổi vị trí 2 ứng dụng") {
            com.mipago.ex2center.launcher.SplitScreenLauncher.swapSplitApps(context)
        }

        // 2. Quick Climate Adjust
        val btnClimate = createPillButton(R.drawable.ic_ac, R.color.text_primary, "Bật/tắt A/C") {
            // Quick toggle AC compressor or trigger Quick Cool
            runIo {
                val curAc = carHelper.isAcCompressorOn()
                val ok = curAc != null && carHelper.setAcCompressorOn(!curAc)
                mainHandler.post {
                    toast(
                        when {
                            curAc == null -> "Chưa đọc được trạng thái A/C từ xe."
                            !ok -> "Xe không nhận lệnh: A/C"
                            !curAc -> "A/C: BẬT"
                            else -> "A/C: TẮT"
                        }
                    )
                }
            }
        }

        // 3. Blackout Night Mode
        val btnNight = createPillButton(R.drawable.ic_night, R.color.accent_secondary, "Màn hình đêm") {
            activateBlackoutMode()
        }

        // 4. Close Split & Return to Dashboard
        val btnClose = createPillButton(R.drawable.ic_close, R.color.accent_danger, "Đóng chia đôi màn hình") {
            hideSplitControlBar()
            val dashIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(dashIntent)
        }

        bar.addView(btnSwap)
        bar.addView(btnClimate)
        bar.addView(btnNight)
        bar.addView(btnClose)

        // Allow vertical dragging along screen boundary
        bar.setOnTouchListener(object : View.OnTouchListener {
            private var initY = 0
            private var touchY = 0f
            private var isDrag = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = splitControlLayoutParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initY = p.y
                        touchY = event.rawY
                        isDrag = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = (event.rawY - touchY).toInt()
                        if (Math.abs(dy) > 10) isDrag = true
                        p.y = initY + dy
                        windowManager.updateViewLayout(bar, p)
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(bar, params)
            splitControlView = bar
            Log.i(TAG, "Split-Screen PiP control bar displayed.")
        } catch (t: Throwable) {
            Log.e(TAG, "Error displaying split control bar: ${t.message}", t)
        }
    }

    fun hideSplitControlBar() {
        val wasShown = splitControlView != null
        splitControlView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Throwable) {}
        }
        splitControlView = null
        if (wasShown) onOverlayClosed?.invoke()
    }

    /**
     * MODULE 2: SAFE REBOOT
     * Prompts driver with confirmation dialog before executing system reboot.
     */
    private fun confirmAndReboot() {
        // Never reboot the head unit outside Park: cameras and navigation would drop out.
        ParkGate.runIfAllowed(context) { showRebootDialog() }
    }

    private fun showRebootDialog() {
        try {
            val builder = AppDialog(context)
                .asOverlay()
                .setDestructive()
                .setTitle(context.getString(R.string.confirm_reboot_title))
                .setMessage(context.getString(R.string.confirm_reboot_msg))
                .setPositiveButton(context.getString(R.string.btn_confirm)) { _, _ ->
                    // Re-check: the gear may have changed while the dialog was open.
                    ParkGate.runIfAllowed(context) { executeReboot() }
                }
                .setNegativeButton(context.getString(R.string.btn_cancel), null)

            val dialog = builder.create()
            dialog.show()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to display reboot confirmation dialog: ${t.message}", t)
        }
    }

    private fun executeReboot() {
        Log.w(TAG, "Reboot confirmed by driver. Initiating reboot...")
        try {
            // Using system privilege to reboot
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.reboot(null)
        } catch (t: Throwable) {
            Log.w(TAG, "Direct PowerManager.reboot failed, trying fallback: ${t.message}")
            try {
                val intent = Intent(Intent.ACTION_REBOOT).apply {
                    putExtra("nowait", 1)
                    putExtra("interval", 1)
                    putExtra("window", 0)
                }
                context.sendBroadcast(intent)
            } catch (t2: Throwable) {
                Log.e(TAG, "Broadcast reboot failed: ${t2.message}")
                try {
                    Runtime.getRuntime().exec("reboot")
                } catch (t3: Throwable) {
                    Log.e(TAG, "Shell reboot failed: ${t3.message}")
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
