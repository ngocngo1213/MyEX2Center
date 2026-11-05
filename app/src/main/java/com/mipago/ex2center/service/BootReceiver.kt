package com.mipago.ex2center.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "Received boot broadcast: $action")

        // MY_PACKAGE_REPLACED: after a self-update the process is killed and BOOT_COMPLETED
        // will not fire again (the head unit suspends rather than rebooting).
        if (Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            val installerPrefs = context.getSharedPreferences(
                com.mipago.ex2center.utils.PackageInstallerHelper.PREFS_NAME, Context.MODE_PRIVATE
            )
            if (installerPrefs.contains(com.mipago.ex2center.utils.PackageInstallerHelper.KEY_PENDING_SELF_UPDATE)) {
                installerPrefs.edit()
                    .remove(com.mipago.ex2center.utils.PackageInstallerHelper.KEY_PENDING_SELF_UPDATE).apply()
                val version = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (_: Throwable) { null }
                Log.i(TAG, "Self-update completed -> v$version")
                android.widget.Toast.makeText(
                    context, "MyEX2Center đã được cập nhật lên v${version ?: "?"}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        if (Intent.ACTION_BOOT_COMPLETED == action || Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            // Start CarAutomationService
            val autoServiceIntent = Intent(context, CarAutomationService::class.java)
            context.startForegroundService(autoServiceIntent)

            // Check if floating overlay was enabled in preferences
            val prefs = context.getSharedPreferences(CarAutomationService.PREFS_NAME, Context.MODE_PRIVATE)
            val floatingEnabled = prefs.getBoolean("key_floating_bubble_enabled", true)
            if (floatingEnabled) {
                val overlayServiceIntent = Intent(context, FloatingControlService::class.java)
                context.startForegroundService(overlayServiceIntent)
            }
        }
    }
}
