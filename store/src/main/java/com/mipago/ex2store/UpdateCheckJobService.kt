package com.mipago.ex2store

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mipago.ex2center.model.AppInfo
import com.mipago.ex2center.repository.AppRepository

import com.mipago.ex2center.utils.ParkGate

/**
 * Background update check for EX2 Store.
 *
 * Loads the catalog (only a signature-verified remote catalog counts — a stale bundled one never
 * announces anything), finds installed apps with a newer published version and shows ONE
 * notification. Never while driving: if the car is not in Park the check is retried in
 * [RETRY_NOT_PARKED_MS]. The same set of updates is announced only once per [RENOTIFY_MS].
 */
class UpdateCheckJobService : JobService() {

    companion object {
        private const val TAG = "UpdateCheck"
        private const val CHANNEL_ID = "ex2store_updates"
        private const val NOTIFICATION_ID = 4201
        private const val PREFS = "ex2store_updates"
        private const val KEY_LAST_SET = "last_notified_set"
        private const val KEY_LAST_AT = "last_notified_at"
        private const val RETRY_NOT_PARKED_MS = 10 * 60 * 1000L
        private const val RENOTIFY_MS = 24 * 60 * 60 * 1000L
    }

    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            try {
                runCheck()
            } catch (t: Throwable) {
                Log.w(TAG, "Update check failed: ${t.message}")
            } finally {
                jobFinished(params, false)
            }
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true // retry later
    }

    private fun runCheck() {
        val result = AppRepository(this).loadCatalogBlocking(timeoutMs = 10_000)
        if (result == null || result.source != AppRepository.CatalogSource.REMOTE_VERIFIED) {
            Log.i(TAG, "No verified remote catalog (${result?.source}) — nothing announced.")
            return
        }
        val updates = result.apps.filter { it.status == AppInfo.InstallStatus.UPDATE_AVAILABLE && it.isPublished }
        val nm = getSystemService(NotificationManager::class.java)
        if (updates.isEmpty()) {
            nm?.cancel(NOTIFICATION_ID)
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_SET).apply()
            Log.i(TAG, "All apps up to date.")
            return
        }
        if (ParkGate.checkParked(this) !is ParkGate.Decision.Allowed) {
            Log.i(TAG, "${updates.size} update(s) waiting — car not parked, retry later.")
            UpdateScheduler.checkSoon(this, RETRY_NOT_PARKED_MS)
            return
        }
        val set = updates.joinToString(",") { "${it.packageName}:${it.versionCode}" }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recent = System.currentTimeMillis() - prefs.getLong(KEY_LAST_AT, 0L) < RENOTIFY_MS
        if (set == prefs.getString(KEY_LAST_SET, null) && recent) return
        notify(nm ?: return, updates)
        prefs.edit().putString(KEY_LAST_SET, set).putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
    }

    private fun notify(nm: NotificationManager, updates: List<AppInfo>) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cập nhật ứng dụng", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Báo khi MyEX2Center hoặc ứng dụng trong EX2 Store có bản mới"
                setShowBadge(true)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, StoreActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (updates.size == 1) "Có bản cập nhật: ${updates[0].name}" else "Có ${updates.size} bản cập nhật"
        val text = updates.joinToString(" • ") { "${it.name} ${it.versionName}" }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.mipago.ex2center.core.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\nMở EX2 Store để cập nhật (khi xe ở số P)."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        Log.i(TAG, "Notified ${updates.size} update(s): $text")
    }
}
