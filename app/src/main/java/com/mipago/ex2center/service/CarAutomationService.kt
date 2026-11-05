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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
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
 * - Drive/Regen ("Ghi nhớ"): after a start/wake the last used mode is restored; afterwards the
 *   driver's choices are only remembered, never fought (see [ModeMemory]).
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

        // "Ghi nhớ": window after start/wake in which the saved mode is restored.
        private const val RESTORE_WINDOW_MS = 20_000L
        private const val UI_CHANGE_GRACE_MS = 10_000L
        /** Set by the Dashboard when the driver picks a mode there (epoch ms). */
        const val KEY_MODE_CHANGED_AT = "key_mode_changed_at"
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
        worker.post { resetEnforcers() } // service start == car start (boot / app update)
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

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notif_service_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_service_desc)
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
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.notif_service_desc))
            .setSmallIcon(R.drawable.ic_notification) // monochrome: status bar icons must be single-colour
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
            if (gear == CarApiHelper.GEAR_PARK) avasEnforcer.reset() // fresh chances at every stop
        }

        // 1. AVAS mute — every tick, any gear (the car re-arms it on wake).
        if (prefs.getBoolean(KEY_AUTO_AVAS, true)) {
            avasEnforcer.enforce(carHelper.getAvasMode(), 0) { carHelper.setAvasMode(it) }
        }

        // 2./3. "Ghi nhớ" Drive & Regen: restore after a start/wake, then remember changes.
        driveMemory.tick()
        regenMemory.tick()
    }

    /**
     * Remembers the last used mode across car restarts (Dashboard switch "Ghi nhớ").
     *
     * - Restore phase ([RESTORE_WINDOW_MS] after the service starts or the head unit wakes):
     *   the car boots into its own default, so the saved mode is written back (readback-verified,
     *   at most [MAX_WRITE_ATTEMPTS] tries).
     * - Tracking phase: whatever mode the driver selects (here or on the car's own screen) is
     *   saved once it has been stable for two ticks, so it is the one restored next time.
     * Nothing is ever enforced while driving after the restore window.
     */
    private inner class ModeMemory(
        private val name: String,
        private val enabledKey: String,
        private val savedKey: String,
        private val read: () -> Int?,
        private val write: (Int) -> Boolean
    ) {
        private val enforcer = Enforcer(name)
        private var restoreUntil = 0L
        private var candidate: Int? = null

        fun startRestore() {
            restoreUntil = SystemClock.elapsedRealtime() + RESTORE_WINDOW_MS
            candidate = null
            enforcer.reset()
        }

        fun tick() {
            if (!prefs.getBoolean(enabledKey, true)) return
            val current = read() ?: return
            val saved = prefs.getInt(savedKey, -1)
            if (saved == -1) {
                prefs.edit().putInt(savedKey, current).apply() // first run: adopt the car's mode
                return
            }
            if (SystemClock.elapsedRealtime() < restoreUntil) {
                enforcer.enforce(current, saved) { write(it) }
                return
            }
            // A selection made in our own UI is saved directly; give the car time to report it.
            if (System.currentTimeMillis() - prefs.getLong(KEY_MODE_CHANGED_AT, 0L) < UI_CHANGE_GRACE_MS) return
            if (current == saved) {
                candidate = null
            } else if (candidate == current) {
                prefs.edit().putInt(savedKey, current).apply()
                candidate = null
                Log.i(TAG, "$name: remembered driver's choice $current")
            } else {
                candidate = current
            }
        }
    }

    private val driveMemory = ModeMemory(
        "DriveMode", KEY_AUTO_DRIVE_MODE, KEY_PREFERRED_DRIVE_MODE,
        { carHelper.readDriveMode() }, { carHelper.writeDriveMode(it) }
    )
    private val regenMemory = ModeMemory(
        "RegenMode", KEY_AUTO_REGEN_MODE, KEY_PREFERRED_REGEN_MODE,
        { carHelper.readRegenMode() }, { carHelper.writeRegenMode(it) }
    )

    /** Start / wake: re-arm AVAS enforcement and restore the remembered modes. */
    private fun resetEnforcers() {
        avasEnforcer.reset()
        driveMemory.startRestore()
        regenMemory.startRestore()
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
