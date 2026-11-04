package com.mipago.ex2center.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receiver for PackageInstaller commit / uninstall results.
 *
 * Callbacks are keyed by an explicit `request_key` extra carried in our own PendingIntent
 * ("s:<sessionId>" for installs, "u:<package>" for uninstalls). Uninstall results carry no
 * EXTRA_SESSION_ID, so keying by session id alone made uninstall callbacks never fire.
 */
class PackageInstallerStatusReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "InstallerReceiver"
        const val ACTION_INSTALL_COMMIT = "com.mipago.ex2center.action.INSTALL_COMMIT"
        private const val EXTRA_REQUEST_KEY = "request_key"

        private val callbacks = mutableMapOf<String, (status: Int, message: String?, packageName: String?) -> Unit>()

        fun registerCallback(key: String, callback: (status: Int, message: String?, packageName: String?) -> Unit) {
            synchronized(callbacks) { callbacks[key] = callback }
        }

        fun unregisterCallback(key: String) {
            synchronized(callbacks) { callbacks.remove(key) }
        }

        fun pendingIntent(context: Context, key: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, PackageInstallerStatusReceiver::class.java).apply {
                action = ACTION_INSTALL_COMMIT
                putExtra(EXTRA_REQUEST_KEY, key)
            }
            return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val key = intent.getStringExtra(EXTRA_REQUEST_KEY)
            ?: intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).takeIf { it >= 0 }?.let { "s:$it" }

        Log.i(TAG, "Installer callback: key=$key status=$status message=$message pkg=$packageName")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Fallback when silent install is not permitted: show the system confirmation.
            @Suppress("DEPRECATION")
            val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmIntent)
                } catch (t: Throwable) {
                    Log.w(TAG, "Cannot show system install confirmation: ${t.message}")
                }
            }
            return // the final status arrives in a later broadcast
        }

        val callback = key?.let { k -> synchronized(callbacks) { callbacks.remove(k) } }
        callback?.invoke(status, message, packageName)
    }
}
