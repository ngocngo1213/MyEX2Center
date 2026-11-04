package com.mipago.ex2store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.mipago.ex2center.utils.PackageInstallerHelper

/**
 * Boot / wake / self-update hooks for EX2 Store:
 * - schedules the update check (periodic + one soon after boot or wake),
 * - confirms a self-update: the old process was killed while its own package was replaced.
 */
class StoreUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        UpdateScheduler.schedulePeriodic(context)
        UpdateScheduler.checkSoon(context, 2 * 60 * 1000L)
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(PackageInstallerHelper.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(PackageInstallerHelper.KEY_PENDING_SELF_UPDATE)) return
        prefs.edit().remove(PackageInstallerHelper.KEY_PENDING_SELF_UPDATE).apply()
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Throwable) { null }
        Log.i("StoreUpdateReceiver", "EX2 Store updated to v$version")
        Toast.makeText(context, "EX2 Store đã được cập nhật lên v${version ?: "?"}", Toast.LENGTH_LONG).show()
    }
}
