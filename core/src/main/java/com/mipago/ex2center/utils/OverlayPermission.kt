package com.mipago.ex2center.utils

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.util.Log

/**
 * "Hiển thị trên ứng dụng khác" (SYSTEM_ALERT_WINDOW) for catalog apps that need a floating
 * window over the map — PipePipe popup video player.
 *
 * Flyme Auto has no settings screen to grant it, so the popup player would never start. The app
 * op is set directly with the hidden AppOpsManager.setMode(code, uid, pkg, mode) (same as
 * `appops set <pkg> SYSTEM_ALERT_WINDOW allow`); allowed because MyEX2Center / EX2 Store run as
 * android.uid.system. The system keeps the setting across reboots.
 */
object OverlayPermission {
    private const val TAG = "OverlayPermission"
    private const val OP_SYSTEM_ALERT_WINDOW = 24 // AppOpsManager.OP_SYSTEM_ALERT_WINDOW (hidden)

    fun isAllowed(context: Context, pkg: String): Boolean = try {
        isAllowed(context, context.packageManager.getApplicationInfo(pkg, 0).uid, pkg)
    } catch (_: Throwable) {
        false
    }

    private fun isAllowed(context: Context, uid: Int, pkg: String) =
        (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
            .checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg) == AppOpsManager.MODE_ALLOWED

    /** Grants the overlay app op to [pkg] if needed. Returns true when it is allowed afterwards. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun ensure(context: Context, pkg: String): Boolean = try {
        val uid = context.packageManager.getApplicationInfo(pkg, 0).uid
        if (isAllowed(context, uid, pkg)) true else {
            AppOpsManager::class.java
                .getMethod("setMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                .invoke(context.getSystemService(Context.APP_OPS_SERVICE), OP_SYSTEM_ALERT_WINDOW, uid, pkg, AppOpsManager.MODE_ALLOWED)
            isAllowed(context, uid, pkg).also { Log.i(TAG, "Overlay permission $pkg -> $it") }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Cannot grant overlay to $pkg: ${t.cause?.message ?: t.message}")
        false
    }
}
