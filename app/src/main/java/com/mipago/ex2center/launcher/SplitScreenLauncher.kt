package com.mipago.ex2center.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.mipago.ex2center.R
import com.mipago.ex2center.utils.PackageInstallerHelper

/**
 * Module 4: Split-Screen Shortcut Launcher
 *
 * Implements one-tap intent execution to invoke Android 9 (Pie) split-screen windowing mode.
 * Positions Vietmap Live (Primary, Left) and SmartTube (Secondary, Right) simultaneously.
 *
 * Employs a multi-tier launch strategy:
 * Tier 1: ActivityOptions.setLaunchWindowingMode + Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT.
 * Tier 2: Privileged system command fallback (`am start --windowingMode 3 / 4`) via UID 1000.
 */
object SplitScreenLauncher {

    private const val TAG = "SplitScreenLauncher"

    const val PKG_VIETMAP_LIVE = "vn.vietmap.live"
    const val PKG_SMART_TUBE = "org.smarttube.stable"

    // Android 9 WindowConfiguration constants
    private const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3
    private const val WINDOWING_MODE_SPLIT_SCREEN_SECONDARY = 4

    private var isVietmapPrimary = true

    fun getInstalledSmartTubePkg(context: Context): String {
        val candidates = listOf(
            "org.smarttube.stable",
            "com.teamsmart.videomanager.tv",
            "com.liskovsoft.smartyoutubetv2.tv",
            "app.revanced.android.apps.youtube.music",
            "com.google.android.youtube"
        )
        for (pkg in candidates) {
            if (PackageInstallerHelper.isPackageInstalled(context, pkg)) {
                return pkg
            }
        }
        return PKG_SMART_TUBE
    }

    fun swapSplitApps(context: Context) {
        isVietmapPrimary = !isVietmapPrimary
        launchSplitScreen(context)
    }

    fun launchSplitScreen(context: Context) {
        val pm = context.packageManager

        val ytPkg = getInstalledSmartTubePkg(context)
        val leftPkg = if (isVietmapPrimary) PKG_VIETMAP_LIVE else ytPkg
        val rightPkg = if (isVietmapPrimary) ytPkg else PKG_VIETMAP_LIVE

        // Verify package installations
        val leftInstalled = PackageInstallerHelper.isPackageInstalled(context, leftPkg)
        val rightInstalled = PackageInstallerHelper.isPackageInstalled(context, rightPkg)

        if (!leftInstalled || !rightInstalled) {
            val missing = mutableListOf<String>()
            if (!leftInstalled) missing.add(leftPkg)
            if (!rightInstalled) missing.add(rightPkg)

            Toast.makeText(
                context,
                "Chưa cài đặt: ${missing.joinToString(", ")}. Vui lòng tải từ Kho ứng dụng.",
                Toast.LENGTH_LONG
            ).show()
            Log.w(TAG, "Cannot launch split-screen. Missing packages: $missing")
            return
        }

        val primaryIntent = pm.getLaunchIntentForPackage(leftPkg)
        val secondaryIntent = pm.getLaunchIntentForPackage(rightPkg)

        if (primaryIntent == null || secondaryIntent == null) {
            Toast.makeText(context, "Không tìm thấy Activity khởi chạy cho ứng dụng.", Toast.LENGTH_SHORT).show()
            return
        }

        // Show floating PiP control bar between windows
        try {
            context.startService(Intent(context, com.mipago.ex2center.service.FloatingControlService::class.java).apply {
                action = com.mipago.ex2center.service.FloatingControlService.ACTION_SHOW_SPLIT_CONTROL
            })
        } catch (ignored: Throwable) {}

        try {
            Log.i(TAG, "Attempting Tier 1 Split-Screen launch via ActivityOptions reflection...")
            launchViaActivityOptions(context, primaryIntent, secondaryIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "Tier 1 launch failed (${t.message}), executing Tier 2 Shell fallback...", t)
            launchViaPrivilegedShell(primaryIntent, secondaryIntent)
        }
    }

    /**
     * Tier 1: Android 9 Standard Intent & ActivityOptions windowing mode invocation.
     */
    private fun launchViaActivityOptions(
        context: Context,
        primaryIntent: Intent,
        secondaryIntent: Intent
    ) {
        // Prepare Primary (Vietmap Live - Left)
        val primaryOptions = ActivityOptions.makeBasic()
        try {
            val setWindowingModeMethod = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType
            )
            setWindowingModeMethod.invoke(primaryOptions, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set launch windowing mode on primary options: ${e.message}")
        }

        primaryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(primaryIntent, primaryOptions.toBundle())

        // Delay slightly before launching Secondary to let primary snap to left window
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val secondaryOptions = ActivityOptions.makeBasic()
                try {
                    val setWindowingModeMethod = ActivityOptions::class.java.getMethod(
                        "setLaunchWindowingMode",
                        Int::class.javaPrimitiveType
                    )
                    setWindowingModeMethod.invoke(secondaryOptions, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set launch windowing mode on secondary options: ${e.message}")
                }

                secondaryIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                context.startActivity(secondaryIntent, secondaryOptions.toBundle())
                Log.i(TAG, "Tier 1 Split-Screen launch completed.")
            } catch (t2: Throwable) {
                Log.w(TAG, "Failed secondary launch in Tier 1, falling back to shell: ${t2.message}")
                launchViaPrivilegedShell(primaryIntent, secondaryIntent)
            }
        }, 500L)
    }

    /**
     * Tier 2: System Command Fallback leveraging UID 1000 privileges
     * Executes `am start --windowingMode 3 / 4`.
     */
    private fun launchViaPrivilegedShell(primaryIntent: Intent, secondaryIntent: Intent) {
        Thread {
            try {
                val primaryComponent = primaryIntent.component?.flattenToShortString() ?: PKG_VIETMAP_LIVE
                val secondaryComponent = secondaryIntent.component?.flattenToShortString() ?: PKG_SMART_TUBE

                Log.i(TAG, "Executing shell: am start -n $primaryComponent --windowingMode $WINDOWING_MODE_SPLIT_SCREEN_PRIMARY")
                val cmd1 = "am start -n $primaryComponent --windowingMode $WINDOWING_MODE_SPLIT_SCREEN_PRIMARY"
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd1)).waitFor()

                Thread.sleep(600)

                Log.i(TAG, "Executing shell: am start -n $secondaryComponent --windowingMode $WINDOWING_MODE_SPLIT_SCREEN_SECONDARY")
                val cmd2 = "am start -n $secondaryComponent --windowingMode $WINDOWING_MODE_SPLIT_SCREEN_SECONDARY"
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd2)).waitFor()

                Log.i(TAG, "Tier 2 Privileged Shell Split-Screen launch dispatched.")
            } catch (e: Exception) {
                Log.e(TAG, "Tier 2 Shell fallback also failed: ${e.message}", e)
            }
        }.start()
    }
}
