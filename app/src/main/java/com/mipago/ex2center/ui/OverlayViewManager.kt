package com.mipago.ex2center.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.mipago.ex2center.R
import com.mipago.ex2center.utils.CarApiHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Module 2: overlay windows over every app — the 100% Blackout Mode (Màn hình đêm) with
 * single-tap restore, and the "Về bản đồ" chip of Chạy cùng Vietmap.
 * (The floating bubble and its quick panel were removed in 1.2.1: climate is on the dock,
 * Màn hình đêm on the Dashboard and Khởi động lại in Cài đặt.)
 */
class OverlayViewManager(private val context: Context) {

    companion object {
        const val TAG = "OverlayViewManager"
        private const val BLACKOUT_GEAR_CHECK_MS = 500L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val carHelper = CarApiHelper.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var blackoutView: View? = null
    private var mapReturnView: View? = null
    private val mapReturnTimeout = Runnable { hideMapReturnChip() }
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

    private fun getOverlayWindowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

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
     * Called after the Blackout layer or the "Về bản đồ" chip is closed, so the owning service can stop
     * itself when nothing is left on screen (e.g. the dock climate is disabled in Settings).
     */
    var onOverlayClosed: (() -> Unit)? = null

    val isBlackoutShowing: Boolean get() = blackoutView != null

    /** True while an overlay window (Blackout, "Về bản đồ") is attached. */
    val hasVisibleOverlay: Boolean
        get() = blackoutView != null || mapReturnView != null

    /** Removes every overlay window and stops background work. Call from the owner's onDestroy. */
    fun release() {
        onOverlayClosed = null
        hideMapReturnChip()
        deactivateBlackoutMode()
        mainHandler.removeCallbacksAndMessages(null)
        gearWatchExecutor.shutdownNow()
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
    // "VỀ BẢN ĐỒ" CHIP (Chạy cùng Vietmap, video apps)
    // -------------------------------------------------------------

    /**
     * One-tap way back to VIETMAP LIVE while the driver picks a video. Video apps are not switched
     * away automatically: they first play in their own screen (PipePipe also needs a tap on
     * "Popup"), and leaving earlier could stop the video. Tap = open the map and hide the chip;
     * it also hides itself after [timeoutMs].
     */
    fun showMapReturnChip(mapPackage: String, timeoutMs: Long) {
        mainHandler.removeCallbacks(mapReturnTimeout)
        mainHandler.postDelayed(mapReturnTimeout, timeoutMs)
        if (mapReturnView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, dpToPx(76),
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(16)
        }
        val chip = TextView(context).apply {
            text = "Về bản đồ"
            contentDescription = "Mở lại Vietmap"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(context.getColor(R.color.text_on_accent))
            gravity = Gravity.CENTER
            setPadding(dpToPx(36), 0, dpToPx(36), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(38).toFloat()
                setColor(context.getColor(R.color.accent_primary))
                setStroke(dpToPx(2), Color.WHITE)
            }
            elevation = dpToPx(8).toFloat()
            setOnClickListener {
                hideMapReturnChip()
                context.packageManager.getLaunchIntentForPackage(mapPackage)?.let {
                    try { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                }
            }
        }
        try {
            windowManager.addView(chip, params)
            mapReturnView = chip
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot show map-return chip: ${t.message}")
        }
    }

    fun hideMapReturnChip() {
        mainHandler.removeCallbacks(mapReturnTimeout)
        val view = mapReturnView ?: return
        try { windowManager.removeView(view) } catch (_: Throwable) {}
        mapReturnView = null
        onOverlayClosed?.invoke()
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
