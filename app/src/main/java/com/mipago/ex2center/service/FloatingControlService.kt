package com.mipago.ex2center.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.mipago.ex2center.R
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
        private const val VHAL_HOLDER = "floating-service"
    }

    /** The service also hosts Blackout / split bar, so it may run while the bubble is disabled. */
    private fun isBubbleEnabled(): Boolean =
        getSharedPreferences(CarAutomationService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLOATING_BUBBLE_ENABLED, true)

    private var overlayViewManager: OverlayViewManager? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        // The overlay reads/writes HVAC and watches the gear for Blackout: hold the shared link.
        CarApiHelper.getInstance().acquire(this, VHAL_HOLDER)
        overlayViewManager = OverlayViewManager(this)
        if (isBubbleEnabled()) overlayViewManager?.showBubble()
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
            ACTION_TRIGGER_BLACKOUT -> {
                overlayViewManager?.activateBlackoutMode()
            }
            ACTION_SHOW_SPLIT_CONTROL -> {
                overlayViewManager?.showSplitControlBar()
            }
            ACTION_HIDE_SPLIT_CONTROL -> {
                overlayViewManager?.hideSplitControlBar()
            }
            else -> {
                if (isBubbleEnabled()) overlayViewManager?.showBubble()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlayViewManager?.release()
        overlayViewManager = null
        CarApiHelper.getInstance().release(VHAL_HOLDER)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notif_overlay_title),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = notificationBuilder
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.desc_floating_bubble))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
