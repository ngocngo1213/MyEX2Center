package com.mipago.ex2center.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.mipago.ex2center.R
import com.mipago.ex2center.ui.DockClimateOverlay
import com.mipago.ex2center.ui.MainActivity
import com.mipago.ex2center.ui.OverlayViewManager
import com.mipago.ex2center.utils.CarApiHelper

/**
 * Module 2: Floating Control Foreground Service
 *
 * Runs continuously in foreground, managing the floating bubble and overlay window.
 */
class FloatingControlService : Service() {

    companion object {
        const val TAG = "FloatingControlService"
        const val NOTIFICATION_CHANNEL_ID = "ex2_overlay_channel"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START_OVERLAY = "com.mipago.ex2center.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.mipago.ex2center.action.STOP_OVERLAY"
        const val ACTION_TRIGGER_BLACKOUT = "com.mipago.ex2center.action.TRIGGER_BLACKOUT"
        const val ACTION_SHOW_SPLIT_CONTROL = "com.mipago.ex2center.action.SHOW_SPLIT_CONTROL"
        const val ACTION_HIDE_SPLIT_CONTROL = "com.mipago.ex2center.action.HIDE_SPLIT_CONTROL"

        const val KEY_FLOATING_BUBBLE_ENABLED = "key_floating_bubble_enabled"
        /** "Nhiệt độ & quạt trên thanh dưới" (DockClimateOverlay), on by default. */
        const val KEY_DOCK_CLIMATE_ENABLED = "key_dock_climate_enabled"
        /** Re-read the bubble / dock-label preferences and show or hide them accordingly. */
        const val ACTION_SYNC_PREFS = "com.mipago.ex2center.action.SYNC_OVERLAY_PREFS"

        /** True when some overlay preference needs this service running. */
        fun isWanted(context: Context): Boolean {
            val p = context.getSharedPreferences(CarAutomationService.PREFS_NAME, Context.MODE_PRIVATE)
            return p.getBoolean(KEY_FLOATING_BUBBLE_ENABLED, true) || p.getBoolean(KEY_DOCK_CLIMATE_ENABLED, true)
        }
        private const val VHAL_HOLDER = "floating-service"
    }

    /** The service also hosts Blackout / split bar, so it may run while the bubble is disabled. */
    private fun isBubbleEnabled(): Boolean =
        getSharedPreferences(CarAutomationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLOATING_BUBBLE_ENABLED, true)

    private fun isDockClimateEnabled(): Boolean =
        getSharedPreferences(CarAutomationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DOCK_CLIMATE_ENABLED, true)

    private var overlayViewManager: OverlayViewManager? = null
    private var dockClimate: DockClimateOverlay? = null

    private fun syncDockClimate() {
        if (isDockClimateEnabled()) {
            val dock = dockClimate ?: DockClimateOverlay(com.mipago.ex2center.utils.ThemeManager.wrap(this)).also { dockClimate = it }
            dock.show()
            dock.setBlocked(overlayViewManager?.isBlackoutShowing == true)
        } else {
            dockClimate?.release()
            dockClimate = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        // The overlay reads/writes HVAC and watches the gear for Blackout: hold the shared link.
        CarApiHelper.getInstance().acquire(this, VHAL_HOLDER)
        overlayViewManager = OverlayViewManager(com.mipago.ex2center.utils.ThemeManager.wrap(this)).also {
            it.onOverlayClosed = {
                dockClimate?.setBlocked(it.isBlackoutShowing)
                stopIfIdle()
            }
        }
        if (isBubbleEnabled()) overlayViewManager?.showBubble()
        syncDockClimate()
    }

    /**
     * With the bubble disabled the service only exists for Blackout / the split bar: once both
     * are closed, stop so it does not keep a foreground notification and the VHAL link alive.
     */
    private fun stopIfIdle() {
        if (isBubbleEnabled() || isDockClimateEnabled()) return
        if (overlayViewManager?.hasVisibleOverlay == true) return
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                overlayViewManager?.hideBubble()
                overlayViewManager?.hidePanel()
                overlayViewManager?.hideSplitControlBar()
                overlayViewManager?.deactivateBlackoutMode()
                stopSelf()
            }
            ACTION_SYNC_PREFS -> {
                if (isBubbleEnabled()) overlayViewManager?.showBubble() else {
                    overlayViewManager?.hideBubble()
                    overlayViewManager?.hidePanel()
                }
                syncDockClimate()
                stopIfIdle()
            }
            ACTION_TRIGGER_BLACKOUT -> {
                overlayViewManager?.activateBlackoutMode()
                dockClimate?.setBlocked(overlayViewManager?.isBlackoutShowing == true)
                stopIfIdle() // stops only if the window could not be added
            }
            ACTION_SHOW_SPLIT_CONTROL -> {
                overlayViewManager?.showSplitControlBar()
                stopIfIdle() // stops only if the window could not be added
            }
            ACTION_HIDE_SPLIT_CONTROL -> {
                overlayViewManager?.hideSplitControlBar()
                stopIfIdle()
            }
            else -> {
                if (isBubbleEnabled()) overlayViewManager?.showBubble() else stopIfIdle()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        dockClimate?.release()
        dockClimate = null
        overlayViewManager?.release()
        overlayViewManager = null
        CarApiHelper.getInstance().release(VHAL_HOLDER)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notif_overlay_title),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)

        val notification = notificationBuilder
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.desc_floating_bubble))
            .setSmallIcon(R.drawable.ic_notification) // monochrome: status bar icons must be single-colour
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
