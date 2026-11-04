package com.mipago.ex2store

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Schedules [UpdateCheckJobService]:
 * - a periodic check every [PERIOD_MS] whenever the head unit has network (persisted across boots),
 * - a one-shot check soon after boot / wake / a Store update, or later when the car was not parked.
 */
object UpdateScheduler {

    private const val TAG = "UpdateScheduler"
    const val JOB_PERIODIC = 4101
    const val JOB_ONESHOT = 4102
    private const val PERIOD_MS = 3 * 60 * 60 * 1000L
    private const val FLEX_MS = 60 * 60 * 1000L

    private fun component(context: Context) = ComponentName(context, UpdateCheckJobService::class.java)

    fun schedulePeriodic(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_PERIODIC) != null) return
        val job = JobInfo.Builder(JOB_PERIODIC, component(context))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIOD_MS, FLEX_MS)
            .setPersisted(true)
            .build()
        val r = js.schedule(job)
        Log.i(TAG, "Periodic update check scheduled (result=$r)")
    }

    /** Runs a check after [delayMs] once network is available (replaces a pending one-shot). */
    fun checkSoon(context: Context, delayMs: Long) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(JOB_ONESHOT, component(context))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delayMs)
            .setOverrideDeadline(delayMs + 30 * 60 * 1000L)
            .build()
        js.schedule(job)
        Log.i(TAG, "One-shot update check in ${delayMs / 1000}s")
    }
}
