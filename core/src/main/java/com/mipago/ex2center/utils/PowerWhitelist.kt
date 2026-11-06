package com.mipago.ex2center.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Battery-optimisation whitelist ("Bỏ qua tối ưu hóa pin") for catalog apps that must keep
 * running in the background (navigation).
 *
 * Why: on the IHU629G (Flyme Auto) the standard screen
 * `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` does not exist. VIETMAP LIVE opens it on
 * first launch without a fallback and crashes with ActivityNotFoundException. Once the package
 * is whitelisted the app skips that request.
 *
 * Uses the hidden IDeviceIdleController (same as `dumpsys deviceidle whitelist +pkg`). Allowed
 * because EX2 Store runs as android.uid.system (DEVICE_POWER, platform-signed: no hidden-API
 * restrictions). The whitelist is persisted by the system across reboots.
 */
object PowerWhitelist {
    private const val TAG = "PowerWhitelist"

    fun isWhitelisted(context: Context, pkg: String): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(pkg)
    } catch (_: Throwable) {
        false
    }

    /** Adds [pkg] if it is not whitelisted yet. Returns true when it is whitelisted afterwards. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun ensure(context: Context, pkg: String): Boolean {
        if (isWhitelisted(context, pkg)) return true
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "deviceidle") as IBinder
            val controller = Class.forName("android.os.IDeviceIdleController\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            controller.javaClass.getMethod("addPowerSaveWhitelistApp", String::class.java)
                .invoke(controller, pkg)
            val ok = isWhitelisted(context, pkg)
            Log.i(TAG, "Battery whitelist $pkg -> $ok")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot whitelist $pkg: ${t.cause?.message ?: t.message}")
            false
        }
    }
}
