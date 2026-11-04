package com.mipago.ex2center.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.mipago.ex2center.R
import com.mipago.ex2center.ui.MainActivity
import com.mipago.ex2center.utils.CarApiHelper

/**
 * Module 1: Foreground Service & Automation (Driving Settings)
 *
 * Policy (mirrors reference/drive_assist ModeHelperService, proven on IHU629G):
 * - AVAS mute is re-asserted on EVERY tick: the car re-arms AVAS on wake/power-on and the
 *   head unit suspends instead of restarting this process, so "apply once on P→D" is not enough.
 * - Drive/Regen preference is enforced while PARKED and once when the car enters D;
 *   a mode the driver picks live during a drive is respected until the next stop.
 * - Every write is verified by readback on the next tick. A feature whose write does not take
 *   after [MAX_WRITE_ATTEMPTS] tries is suspended until the next wake / Park (never fights the car).
 * - All VHAL calls run on a dedicated HandlerThread, never on the main thread.
 * - Crash Guard trips on repeated unexpected exceptions (bugs), not on the car rejecting a write.
 */
class CarAutomationService : Service() {

    companion object {
        const val TAG = "CarAutomationService"
        const val NOTIFICATION_CHANNEL_ID = "ex2_automation_channel"
        const val NOTIFICATION_ID = 1001

        const val PREFS_NAME = "ex2_settings"
        const val KEY_AUTO_AVAS = "key_auto_avas"
        const val KEY_AUTO_DRIVE_MODE = "key_auto_drive_mode"
        const val KEY_AUTO_REGEN_MODE = "key_auto_regen_mode"
        const val KEY_PREFERRED_DRIVE_MODE = "key_preferred_drive_mode"
        const val KEY_PREFERRED_REGEN_MODE = "key_preferred_regen_mode"
        const val KEY_GUARD_HALTED = "key_guard_halted"
        const val KEY_VALET_MODE = "isValetModeEnabled"

        const val ACTION_TRIGGER_CHECK = "com.mipago.ex2center.action.TRIGGER_CHECK"
        const val ACTION_RESET_GUARD = "com.mipago.ex2center.action.RESET_GUARD"
        const val ACTION_TOGGLE_VALET = "com.mipago.ex2center.action.TOGGLE_VALET"

        private const val HOLDER = "automation-service"

        // Crash Guard: 2 unexpected exceptions within 60 seconds halt the loop.
        private const val MAX_CONSECUTIVE_FAILURES = 2
        private const val FAILURE_WINDOW_MS = 60_000L
        private const val POLL_INTERVAL_MS = 4_000L
        private const val WAKE_SETTLE_MS = 1_500L

        // A write that is not confirmed by readback after this many ticks is suspended.
        private const val MAX_WRITE_ATTEMPTS = 3
        // Gear unreadable this many ticks in a row while "connected" => dead binder, reconnect.
        private const val MAX_GEAR_READ_FAILURES = 5
    }

    /** Tracks write attempts that have not (yet) been confirmed by readback. */
    private class Enforcer(val name: String) {
        var attempts = 0
        var suspended = false

        fun reset() {
            attempts = 0
            suspended = false
        }

        /**
         * [current] = value read from the car, [wanted] = preference, [write] = issues the write.
         */
        fun <T> enforce(current: T?, wanted: T, write: (T) -> Boolean) {
            if (current == null || suspended) return
            if (current == wanted) {
                attempts = 0
                return
            }
            if (attempts >= MAX_WRITE_ATTEMPTS) {
                suspended = true
                Log.w(TAG, "$name: car keeps reporting $current instead of $wanted after " +
                    "$attempts writes — suspended until next wake/Park.")
                return
            }
            attempts++
            val accepted = write(wanted)
            Log.i(TAG, "$name: $current -> $wanted (attempt $attempts, accepted=$accepted)")
        }
    }

    private val carHelper = CarApiHelper.getInstance()
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var prefs: SharedPreferences

    private var isStarted = false
    private var lastGear = -1
    private var gearReadFailures = 0
    private var consecutiveFailures = 0
    private var firstFailureTimestamp = 0L
    @Volatile private var isCrashGuardHalted = false

    private val avasEnforcer = Enforcer("AVAS")
    private val driveEnforcer = Enforcer("DriveMode")
    private val regenEnforcer = Enforcer("RegenMode")

    private val tickRunnable = Runnable { tick() }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                Log.i(TAG, "Screen on (head unit woke) — re-asserting automations.")
                worker.post {
                    resetEnforcers()
                    lastGear = -1
                }
                scheduleTick(WAKE_SETTLE_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isCrashGuardHalted = prefs.getBoolean(KEY_GUARD_HALTED, false)
        startForegroundServiceNotification()

        workerThread = HandlerThread("ex2-automation").also { it.start() }
        worker = Handler(workerThread.looper)

        registerReceiver(wakeReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        carHelper.acquire(this, HOLDER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_GUARD -> worker.post { resetCrashGuard() }
            ACTION_TRIGGER_CHECK -> scheduleTick(0L)
        }
        if (!isStarted) {
            isStarted = true
            Log.i(TAG, "Automation monitor started.")
            scheduleTick(0L)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isStarted = false
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        try { unregisterReceiver(wakeReceiver) } catch (_: Throwable) {}
        carHelper.release(HOLDER)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Single scheduling point: a pending tick is always replaced, so loops can never stack up. */
    private fun scheduleTick(delayMs: Long) {
        if (!::worker.isInitialized) return
        worker.removeCallbacks(tickRunnable)
        worker.postDelayed(tickRunnable, delayMs)
    }

    private fun startForegroundServiceNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notif_service_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_service_desc)
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
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.notif_service_desc))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /** Runs on [workerThread]. */
    private fun tick() {
        if (!isStarted || isCrashGuardHalted) {
            if (isCrashGuardHalted) Log.w(TAG, "Automation halted by Crash Guard — waiting for reset.")
            return // a reset or TRIGGER_CHECK re-schedules us
        }
        try {
            runAutomation()
            recordSuccess()
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected exception in automation loop: ${t.message}", t)
            recordFailure()
        }
        if (!isCrashGuardHalted) scheduleTick(POLL_INTERVAL_MS)
    }

    private fun runAutomation() {
        // Chế độ Bảo dưỡng (Valet / Service Mode): no VHAL writes at all.
        if (prefs.getBoolean(KEY_VALET_MODE, false)) return

        if (!carHelper.isReady) {
            carHelper.connect(this)
            return
        }

        val gear = carHelper.readGear()
        if (gear == null) {
            if (++gearReadFailures >= MAX_GEAR_READ_FAILURES) {
                gearReadFailures = 0
                carHelper.reconnect(this)
            }
            return
        }
        gearReadFailures = 0

        val previousGear = lastGear
        if (gear != previousGear) {
            Log.i(TAG, "Gear $previousGear -> $gear")
            lastGear = gear
            if (gear == CarApiHelper.GEAR_PARK) resetEnforcers() // fresh chances at every stop
        }

        // 1. AVAS mute — every tick, any gear (the car re-arms it on wake).
        if (prefs.getBoolean(KEY_AUTO_AVAS, true)) {
            avasEnforcer.enforce(carHelper.getAvasMode(), 0) { carHelper.setAvasMode(it) }
        }

        // 2./3. Drive & Regen — hold the preference while parked, apply once when entering D.
        val parked = gear == CarApiHelper.GEAR_PARK
        val enteringDrive = gear == CarApiHelper.GEAR_DRIVE && previousGear != CarApiHelper.GEAR_DRIVE
        if (parked || enteringDrive) {
            if (prefs.getBoolean(KEY_AUTO_DRIVE_MODE, true)) {
                val wanted = prefs.getInt(KEY_PREFERRED_DRIVE_MODE, CarApiHelper.DRIVE_ECO)
                driveEnforcer.enforce(carHelper.readDriveMode(), wanted) { carHelper.writeDriveMode(it) }
            }
            if (prefs.getBoolean(KEY_AUTO_REGEN_MODE, true)) {
                val wanted = prefs.getInt(KEY_PREFERRED_REGEN_MODE, CarApiHelper.REGEN_MID)
                regenEnforcer.enforce(carHelper.readRegenMode(), wanted) { carHelper.writeRegenMode(it) }
            }
        }
    }

    private fun resetEnforcers() {
        avasEnforcer.reset()
        driveEnforcer.reset()
        regenEnforcer.reset()
    }

    /**
     * CRASH GUARD SAFETY MECHANISM:
     * Halts the loop after 2 unexpected exceptions within 60 seconds to prevent ANR/bootloops.
     */
    private fun recordFailure() {
        val now = System.currentTimeMillis()
        if (consecutiveFailures == 0 || (now - firstFailureTimestamp > FAILURE_WINDOW_MS)) {
            firstFailureTimestamp = now
            consecutiveFailures = 1
        } else {
            consecutiveFailures++
        }

        Log.w(TAG, "Crash Guard failure: count=$consecutiveFailures windowMs=${now - firstFailureTimestamp}")

        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            isCrashGuardHalted = true
            prefs.edit().putBoolean(KEY_GUARD_HALTED, true).apply()
            Log.e(TAG, "CRASH GUARD TRIPPED! $consecutiveFailures failures in 60s. Halting automation loop.")
        }
    }

    private fun recordSuccess() {
        consecutiveFailures = 0
        firstFailureTimestamp = 0L
    }

    /** Runs on [workerThread]. */
    private fun resetCrashGuard() {
        isCrashGuardHalted = false
        consecutiveFailures = 0
        firstFailureTimestamp = 0L
        resetEnforcers()
        prefs.edit().putBoolean(KEY_GUARD_HALTED, false).apply()
        Log.i(TAG, "Crash Guard reset by user. Resuming automation loop.")
        scheduleTick(0L)
    }
}
