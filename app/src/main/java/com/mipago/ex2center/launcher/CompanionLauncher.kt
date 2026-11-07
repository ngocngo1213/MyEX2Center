package com.mipago.ex2center.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.mipago.ex2center.service.FloatingControlService
import com.mipago.ex2center.utils.MediaSessions
import com.mipago.ex2center.utils.OverlayPermission

/**
 * "Chạy cùng Vietmap": VIETMAP LIVE together with a video / music app.
 *
 * The media app opens so the driver can pick something, then the map comes back while it keeps
 * playing:
 * - Music (OuterTune / Kreate): as soon as it plays (MediaSession, checked on a background
 *   thread) Vietmap comes back; the music continues in the background.
 * - Video: a floating "Về bản đồ" chip brings Vietmap back with one tap. YouTube ReVanced keeps
 *   the sound playing in the background; PipePipe keeps the video in its popup window once the
 *   driver taps "Popup" (overlay permission granted here and by EX2 Store).
 *
 * No split screen: on IHU629G the firmware flags allow it, but Flyme's SystemUI creates no split
 * divider window, so WindowManager throws NullPointerException in
 * DockedStackDividerController.getTouchRegion on every focus change and crashes the apps on
 * screen — Vietmap included (seen on the car, 08/11/2026). Picture-in-picture has no SystemUI
 * support either (no PiP menu), so it is not relied on.
 */
object CompanionLauncher {

    private const val TAG = "CompanionLauncher"
    const val PKG_VIETMAP_LIVE = "vn.vietmap.live"

    private const val WATCH_TIMEOUT_MS = 180_000L
    private const val WATCH_POLL_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    /** MediaSession polling (binder calls) stays off the main thread. */
    private val watcher by lazy { Handler(HandlerThread("companion-watch").apply { start() }.looper) }
    @Volatile private var watch: Runnable? = null

    /** Dashboard "Chạy cùng Vietmap" / "Mở". */
    fun launch(context: Context) {
        val app = context.applicationContext
        val pm = app.packageManager
        val companion = MediaCompanions.current(app)
        val map = pm.getLaunchIntentForPackage(PKG_VIETMAP_LIVE)
        val media = companion?.let { pm.getLaunchIntentForPackage(it.pkg) }
        if (companion == null || map == null || media == null) {
            val missing = listOfNotNull(
                "VIETMAP LIVE".takeIf { map == null },
                "một ứng dụng video/nhạc (${MediaCompanions.namesForMessage})".takeIf { companion == null }
            )
            toast(app, "Chưa cài ${missing.joinToString(" và ")}. Tải từ EX2 Store.")
            return
        }
        stopWatching()
        // EX2 Store grants it too; here only as a fallback, off the main thread (binder calls).
        if (companion.hasPopupPlayer) watcher.post { OverlayPermission.ensure(app, companion.pkg) }
        // Always open the media app (also when it is already playing): the driver may want to
        // pick something else; the map comes back with the chip / when music starts.
        app.startActivity(Intent(media).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (companion.isVideo) {
            // Video apps first play in their own screen; switching away on "is playing" would cut
            // the picture before PipePipe's "Popup" is tapped. One tap on "Về bản đồ" instead.
            toast(app, if (companion.hasPopupPlayer)
                "Chọn video, bấm \"Popup\" (cửa sổ nổi), rồi chạm \"Về bản đồ\"."
            else "Chọn video rồi chạm \"Về bản đồ\": tiếng vẫn phát nền cùng bản đồ.")
            showMapReturnChip(app)
        } else {
            toast(app, "Chọn bài hát. Bản đồ sẽ tự mở lại, nhạc vẫn phát.")
            val showMap = Runnable { app.startActivity(Intent(map).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            watchUntilNewSong(app, companion.pkg) { main.post(showMap) }
        }
    }

    private fun showMapReturnChip(context: Context) {
        try {
            context.startService(Intent(context, FloatingControlService::class.java).apply {
                action = FloatingControlService.ACTION_SHOW_MAP_RETURN
                putExtra(FloatingControlService.EXTRA_PACKAGE, PKG_VIETMAP_LIVE)
                putExtra(FloatingControlService.EXTRA_TIMEOUT_MS, WATCH_TIMEOUT_MS)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot show the map-return chip: ${t.message}")
        }
    }

    /**
     * Checks once a second, off the main thread, for up to 3 minutes until [pkg] starts playing
     * something new: playback starts, or the title changes. A song that was already playing when
     * the app opened does not count, so the driver can still pick another one.
     */
    private fun watchUntilNewSong(context: Context, pkg: String, onPlaying: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + WATCH_TIMEOUT_MS
        var before: String? = null
        var first = true
        val r = object : Runnable {
            override fun run() {
                val now = MediaSessions.playingTitle(context, pkg)
                if (first) { first = false; before = now }
                when {
                    now != null && now != before -> { watch = null; onPlaying() }
                    SystemClock.elapsedRealtime() < deadline -> watcher.postDelayed(this, WATCH_POLL_MS)
                    else -> watch = null
                }
            }
        }
        watch = r
        watcher.post(r) // first run records what was already playing
    }

    private fun stopWatching() {
        watch?.let { watcher.removeCallbacks(it) }
        watch = null
    }

    private fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}
