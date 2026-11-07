package com.mipago.ex2center.launcher

import android.content.Context
import com.mipago.ex2center.utils.PackageInstallerHelper

/** A video / music app from EX2 Store that can run next to VIETMAP LIVE. */
data class MediaCompanion(
    val pkg: String,
    val label: String,
    val isVideo: Boolean,
    /** Has a floating popup player (TYPE_APPLICATION_OVERLAY) that keeps playing over the map. */
    val hasPopupPlayer: Boolean,
    /** Offered by EX2 Store (YouTube ReVanced is not; it is used when installed otherwise). */
    val inStore: Boolean = true
)

/** The media app the driver runs next to the map. */
object MediaCompanions {
    private const val PREFS = "companion"
    private const val KEY_COMPANION = "pkg"

    /**
     * Media apps that can run next to the map, most suitable first.
     * - YouTube ReVanced: picture-in-picture (enters it when the map comes to the front) and
     *   background playback; installed outside EX2 Store, used when present.
     * - PipePipe: floating popup player.
     * NewPipe 0.29.1 is left out: it crashes at start on IHU629G (no BatteryManager service for apps).
     */
    val all = listOf(
        MediaCompanion("app.revanced.android.youtube", "YouTube ReVanced", isVideo = true, hasPopupPlayer = false, inStore = false),
        MediaCompanion("org.smarttube.stable", "SmartTube", isVideo = true, hasPopupPlayer = false),
        MediaCompanion("InfinityLoop1309.NewPipeEnhanced", "PipePipe", isVideo = true, hasPopupPlayer = true),
        MediaCompanion("com.dd3boh.outertune", "OuterTune", isVideo = false, hasPopupPlayer = false),
        MediaCompanion("me.knighthat.kreate", "Kreate", isVideo = false, hasPopupPlayer = false)
    )

    /** "SmartTube, PipePipe, OuterTune hoặc Kreate": the ones EX2 Store can install (for messages). */
    val namesForMessage: String = all.filter { it.inStore }.map { it.label }.let { n ->
        n.dropLast(1).joinToString(", ") + " hoặc " + n.last()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isInstalled(c: Context, app: MediaCompanion) = PackageInstallerHelper.isPackageInstalled(c, app.pkg)

    fun installed(context: Context) = all.filter { isInstalled(context, it) }

    /** The driver's choice if it is still installed, else the first installed one (null = none). */
    fun current(context: Context): MediaCompanion? {
        val chosen = all.firstOrNull { it.pkg == prefs(context).getString(KEY_COMPANION, null) }
        if (chosen != null && isInstalled(context, chosen)) return chosen
        return all.firstOrNull { it != chosen && isInstalled(context, it) }
    }

    fun setCurrent(context: Context, pkg: String) = prefs(context).edit().putString(KEY_COMPANION, pkg).apply()
}
