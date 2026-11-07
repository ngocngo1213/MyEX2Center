package com.mipago.ex2center.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.mipago.ex2center.R
import com.mipago.ex2center.launcher.MediaCompanions
import com.mipago.ex2center.utils.PackageInstallerHelper

/**
 * "Giữ app chạy nền": stops the Flyme launcher from closing Vietmap / the media app.
 *
 * The launcher (com.geely.recent.RecentAppMonitor) removes the task of every app that is not on
 * its built-in white list 30 s after it leaves the screen. Its off switch
 * (persist.launcher.app_monitor_disabled) is dead code on IHU629G. At kill time it skips an app
 * that has an active notification which is NOT a foreground-service one, which is why a playing
 * YouTube (only a media FGS notification) is still closed.
 *
 * MyEX2Center runs as android.uid.system, and Android 9 lets the system post notifications on
 * behalf of any package (NotificationManagerService.resolveNotificationUid). So while a kept app is
 * running, a quiet notification owned by that app ("<app> đang chạy nền", with a "Dừng" button)
 * is posted; when the app is gone it is removed. Nothing in the launcher or system settings is
 * changed: turning the feature off restores the car's behaviour.
 */
object KeepAlive {
    private const val TAG = "KeepAlive"
    private const val PREFS = "keep_alive"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OFF = "off" // packages the driver chose not to keep (new apps default to kept)

    private const val CHANNEL_ID = "ex2_keep_alive"
    private const val NOTIFICATION_TAG = "ex2-keepalive"
    private const val NOTIFICATION_ID = 0x4B41
    private const val USER_ID = 0
    private const val POLL_MS = 5_000L

    const val ACTION_STOP = "com.mipago.ex2center.action.KEEP_ALIVE_STOP"
    const val EXTRA_PACKAGE = "pkg"

    const val PKG_VIETMAP = "vn.vietmap.live"

    data class App(val pkg: String, val label: String)

    /** Apps the feature can keep: the map, then the media apps of "Chạy cùng Vietmap". */
    val candidates: List<App> =
        listOf(App(PKG_VIETMAP, "VIETMAP LIVE")) + MediaCompanions.all.map { App(it.pkg, it.label) }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private lateinit var appContext: Context

    /** Notifications currently posted by us; null = not reconciled yet since start. */
    private var posted: MutableSet<String>? = null
    private val channelsMade = HashSet<String>()

    private val tick = object : Runnable {
        override fun run() {
            try {
                reconcile()
            } catch (t: Throwable) {
                Log.w(TAG, "tick failed: ${t.message}")
            }
            handler?.postDelayed(this, POLL_MS)
        }
    }

    // ------------------------------------------------------------------ settings

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
        refresh()
    }

    fun isKept(c: Context, pkg: String) = pkg !in prefs(c).getStringSet(KEY_OFF, emptySet())!!

    fun setKept(c: Context, pkg: String, keep: Boolean) {
        val off = HashSet(prefs(c).getStringSet(KEY_OFF, emptySet())!!)
        if (keep) off.remove(pkg) else off.add(pkg)
        prefs(c).edit().putStringSet(KEY_OFF, off).apply()
        refresh()
    }

    fun installed(c: Context) = candidates.filter { PackageInstallerHelper.isPackageInstalled(c, it.pkg) }

    // ------------------------------------------------------------------ lifecycle

    /** Called by [CarAutomationService] (always running). */
    fun start(context: Context) {
        if (thread != null) return
        appContext = context.applicationContext
        thread = HandlerThread("ex2-keepalive").also { it.start() }
        handler = Handler(thread!!.looper).also { it.post(tick) }
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        posted = null
    }

    /** Re-checks now (after a settings change or a stop). */
    fun refresh() {
        handler?.let { it.removeCallbacks(tick); it.post(tick) }
    }

    /** Running packages (system uid sees every process). */
    fun runningPackages(c: Context): Set<String> =
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
            ?.flatMapTo(HashSet()) { it.pkgList?.asList() ?: emptyList() } ?: emptySet()

    /** "Dừng": closes [pkg] now, as the driver asked (same as the launcher's force stop). */
    @SuppressLint("PrivateApi")
    fun stopApp(c: Context, pkg: String): Boolean = try {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager::class.java.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
        Log.i(TAG, "Stopped $pkg")
        refresh() // the worker sees it is gone and removes our notification
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Cannot stop $pkg: ${t.cause?.message ?: t.message}")
        false
    }

    // ------------------------------------------------------------------ worker

    private fun reconcile() {
        val c = appContext
        val enabled = isEnabled(c)
        val running = runningPackages(c)
        val wanted = if (!enabled) emptySet() else candidates
            .filter { it.pkg in running && isKept(c, it.pkg) }
            .mapTo(HashSet()) { it.pkg }

        val current = posted ?: HashSet<String>().also {
            // First pass after (re)start: notifications from a previous process may still be up.
            candidates.forEach { app -> if (app.pkg !in wanted) cancel(app.pkg) }
            posted = it
        }
        (current - wanted).forEach { pkg -> cancel(pkg); current.remove(pkg) }
        candidates.filter { it.pkg in wanted && it.pkg !in current }.forEach { app ->
            if (post(c, app)) current.add(app.pkg)
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun service(): Any = NotificationManager::class.java.getMethod("getService").invoke(null)!!

    private fun iface() = Class.forName("android.app.INotificationManager")

    @SuppressLint("PrivateApi")
    private fun ensureChannel(pkg: String, uid: Int) {
        if (!channelsMade.add(pkg)) return
        val channel = NotificationChannel(CHANNEL_ID, "Giữ chạy nền (MyEX2Center)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "MyEX2Center giữ app này không bị launcher của xe tự tắt."
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val slice = Class.forName("android.content.pm.ParceledListSlice")
            .getConstructor(List::class.java).newInstance(listOf(channel))
        iface().getMethod("createNotificationChannelsForPackage", String::class.java,
            Int::class.javaPrimitiveType, slice.javaClass).invoke(service(), pkg, uid, slice)
    }

    private fun post(c: Context, app: App): Boolean = try {
        val uid = c.packageManager.getApplicationInfo(app.pkg, 0).uid
        ensureChannel(app.pkg, uid)
        val open = c.packageManager.getLaunchIntentForPackage(app.pkg)?.let {
            PendingIntent.getActivity(c, app.pkg.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getBroadcast(c, app.pkg.hashCode(),
            Intent(c, KeepAliveReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_PACKAGE, app.pkg),
            PendingIntent.FLAG_UPDATE_CURRENT)
        // Not a foreground-service notification: the launcher ignores those (flag 0x40).
        val n = Notification.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${app.label} đang chạy nền")
            .setContentText("Giữ bởi MyEX2Center. Bấm Dừng để tắt app.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Dừng", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        iface().getMethod("enqueueNotificationWithTag", String::class.java, String::class.java, String::class.java,
            Int::class.javaPrimitiveType, Notification::class.java, Int::class.javaPrimitiveType)
            .invoke(service(), app.pkg, app.pkg, NOTIFICATION_TAG, NOTIFICATION_ID, n, USER_ID)
        Log.i(TAG, "Keeping ${app.pkg}")
        true
    } catch (t: Throwable) {
        channelsMade.remove(app.pkg)
        Log.w(TAG, "Cannot keep ${app.pkg}: ${t.cause?.message ?: t.message}")
        false
    }

    private fun cancel(pkg: String) {
        try {
            iface().getMethod("cancelNotificationWithTag", String::class.java, String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(service(), pkg, NOTIFICATION_TAG, NOTIFICATION_ID, USER_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot cancel for $pkg: ${t.cause?.message ?: t.message}")
        }
    }
}

/** "Dừng" on a keep-alive notification. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != KeepAlive.ACTION_STOP) return
        val pkg = intent.getStringExtra(KeepAlive.EXTRA_PACKAGE) ?: return
        if (KeepAlive.candidates.none { it.pkg == pkg }) return
        KeepAlive.stopApp(context, pkg)
    }
}
