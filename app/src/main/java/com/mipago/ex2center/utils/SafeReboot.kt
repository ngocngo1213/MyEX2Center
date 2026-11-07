package com.mipago.ex2center.utils

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.mipago.ex2center.R

/** "Khởi động lại màn hình": confirmed by the driver, only in Park (cameras / map drop out). */
object SafeReboot {
    private const val TAG = "SafeReboot"

    /** [context] must be an Activity (the confirmation is a normal dialog). */
    fun confirm(context: Context) {
        ParkGate.runIfAllowed(context) {
            AppDialog(context)
                .setDestructive()
                .setTitle(context.getString(R.string.confirm_reboot_title))
                .setMessage(context.getString(R.string.confirm_reboot_msg))
                .setPositiveButton(context.getString(R.string.btn_confirm)) { _, _ ->
                    // Re-check: the gear may have changed while the dialog was open.
                    ParkGate.runIfAllowed(context) { reboot(context) }
                }
                .setNegativeButton(context.getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun reboot(context: Context) {
        Log.w(TAG, "Reboot confirmed by driver.")
        try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).reboot(null)
        } catch (t: Throwable) {
            Log.w(TAG, "PowerManager.reboot failed, trying broadcast: ${t.message}")
            try {
                context.sendBroadcast(Intent(Intent.ACTION_REBOOT).putExtra("nowait", 1)
                    .putExtra("interval", 1).putExtra("window", 0))
            } catch (t2: Throwable) {
                Log.e(TAG, "Reboot failed: ${t2.message}")
            }
        }
    }
}
