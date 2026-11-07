package com.mipago.ex2center.launcher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Log

/**
 * "Chia đôi" Vietmap + media app with freeform windows (side by side, no split divider).
 *
 * Real split screen crashes on IHU629G (Flyme SystemUI has no divider window, see
 * [CompanionLauncher]). Freeform windows do not use the divider: with
 * `settings put global enable_freeform_support 1` and a restart of the head unit, the map opens
 * in a window on the left and the media app on the right (tested on the car 08/11/2026).
 *
 * Each window has Android's small caption bar (maximize / close; close ends Vietmap, which exits
 * when its screen is closed). [exit] puts both back to full screen, the map on top.
 */
object FreeformSplit {
    private const val TAG = "FreeformSplit"
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val RESIZE_MODE_SYSTEM = 0
    /** Share of the width given to the map. */
    private const val MAP_SHARE = 0.52f
    private const val SETTLE_MS = 700L

    private val worker by lazy { Handler(HandlerThread("freeform-split").apply { start() }.looper) }

    /** Freeform support is read by the system at start: the setting must be on since the last restart. */
    fun isAvailable(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) != 0

    /** Left / right window bounds under the status bar, above the car's dock. */
    private fun bounds(context: Context): Pair<Rect, Rect> {
        val res = context.resources
        val density = res.displayMetrics.density
        val sbId = res.getIdentifier("status_bar_height", "dimen", "android")
        val top = if (sbId != 0) res.getDimensionPixelSize(sbId) else 0
        val width = (res.configuration.screenWidthDp * density).toInt()
        val bottom = top + (res.configuration.screenHeightDp * density).toInt()
        val split = (width * MAP_SHARE).toInt()
        return Rect(0, top, split, bottom) to Rect(split, top, width, bottom)
    }

    /** Opens [map] on the left and [media] on the right (media focused, to pick something). */
    fun open(context: Context, map: Intent, media: Intent) {
        val app = context.applicationContext
        val (left, right) = bounds(app)
        start(app, map, left)
        start(app, media, right)
        // An app that was already running keeps its task, and the launch options do not move a
        // full-screen task into a window (seen on the car): move and place both explicitly.
        worker.postDelayed({
            place(app, map.`package`, left)
            place(app, media.`package`, right) // last = on top, focused
        }, SETTLE_MS)
    }

    private fun place(context: Context, pkg: String?, bounds: Rect) {
        val task = tasks(context).firstOrNull { it.pkg == pkg } ?: return
        if (task.mode != WINDOWING_MODE_FREEFORM) setWindowingMode(task.id, WINDOWING_MODE_FREEFORM, toTop = true)
        resize(task.id, bounds)
    }

    private fun start(context: Context, intent: Intent, bounds: Rect) {
        val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        try {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, WINDOWING_MODE_FREEFORM)
        } catch (t: Throwable) {
            Log.w(TAG, "setLaunchWindowingMode: ${t.message}")
        }
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
    }

    /** True while the map or the given media app is in a freeform window. */
    fun isActive(context: Context, packages: Collection<String>): Boolean =
        tasks(context).any { it.pkg in packages && it.mode == WINDOWING_MODE_FREEFORM }

    /** Both windows back to full screen; the map ends up on top, the media app keeps playing behind. */
    fun exit(context: Context, mapPkg: String, otherPackages: Collection<String>) {
        val app = context.applicationContext
        val full = fullBounds(app)
        worker.post {
            val freeform = tasks(app).filter { it.mode == WINDOWING_MODE_FREEFORM }
            // A task switched to full screen keeps its window bounds on this firmware (the media
            // app stayed in the right half with the rest black), and resizing is refused once it is
            // full screen. The mode change applies to the whole stack, which may hold both apps:
            // so grow every window first, then change the modes.
            val ours = freeform.filter { it.pkg in otherPackages || it.pkg == mapPkg }
            ours.forEach { resize(it.id, full) }
            ours.filter { it.pkg != mapPkg }.forEach { setWindowingMode(it.id, WINDOWING_MODE_FULLSCREEN, toTop = false) }
            ours.filter { it.pkg == mapPkg }.forEach { setWindowingMode(it.id, WINDOWING_MODE_FULLSCREEN, toTop = true) }
            // The map always ends on top, also when it was not in a window.
            app.packageManager.getLaunchIntentForPackage(mapPkg)?.let {
                app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** The whole display (what a full-screen task has on this car: 1920×1080). */
    private fun fullBounds(context: Context): Rect {
        val size = android.graphics.Point()
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealSize(size)
        return Rect(0, 0, size.x, size.y)
    }


    // ------------------------------------------------------------------ hidden APIs (uid system)

    private data class Task(val id: Int, val pkg: String?, val mode: Int)

    @Suppress("DEPRECATION")
    private fun tasks(context: Context): List<Task> = try {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningTasks(30).map { info ->
            Task(info.id, info.baseActivity?.packageName, windowingMode(info))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "getRunningTasks: ${t.message}")
        emptyList()
    }

    private fun windowingMode(info: ActivityManager.RunningTaskInfo): Int = try {
        val config = info.javaClass.getField("configuration").get(info)
        val wc = config.javaClass.getField("windowConfiguration").get(config)
        wc.javaClass.getMethod("getWindowingMode").invoke(wc) as Int
    } catch (_: Throwable) {
        0
    }

    @SuppressLint("PrivateApi")
    private fun am(): Any = ActivityManager::class.java.getMethod("getService").invoke(null)!!

    private fun resize(taskId: Int, bounds: Rect) {
        try {
            val am = am()
            am.javaClass.getMethod("resizeTask", Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType)
                .invoke(am, taskId, bounds, RESIZE_MODE_SYSTEM)
            Log.i(TAG, "task $taskId -> $bounds")
        } catch (t: Throwable) {
            Log.w(TAG, "resizeTask $taskId: ${t.cause?.message ?: t.message}")
        }
    }

    private fun setWindowingMode(taskId: Int, mode: Int, toTop: Boolean) {
        try {
            val am = am()
            am.javaClass.getMethod("setTaskWindowingMode", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(am, taskId, mode, toTop)
            Log.i(TAG, "task $taskId -> mode $mode")
        } catch (t: Throwable) {
            Log.w(TAG, "setTaskWindowingMode $taskId: ${t.cause?.message ?: t.message}")
        }
    }
}
