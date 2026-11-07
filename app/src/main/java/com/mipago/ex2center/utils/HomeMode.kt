package com.mipago.ex2center.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import com.mipago.ex2center.ui.HomeActivity

/**
 * "Dùng MyEX2Center làm màn hình chính": the HOME intent (car's "Quay lại Màn hình chính",
 * start-up, closing an app) opens the Dashboard instead of the Flyme launcher.
 *
 * On: enable [HomeActivity] and make it the preferred home (like choosing a home app in Android
 * settings; allowed because MyEX2Center runs as android.uid.system). Off: clear that choice and
 * disable it again, so the Flyme launcher is the only home left. Nothing else is changed.
 */
object HomeMode {
    private const val TAG = "HomeMode"
    const val FLYME_LAUNCHER = "com.flyme.auto.launcher"
    private const val FLYME_HOME = "com.flyme.auto.launcher.main.LauncherActivity"

    private fun component(c: Context) = ComponentName(c, HomeActivity::class.java)

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    fun isEnabled(c: Context): Boolean =
        c.packageManager.getComponentEnabledSetting(component(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    @Suppress("DEPRECATION")
    fun setEnabled(c: Context, on: Boolean): Boolean = try {
        val pm = c.packageManager
        val ours = component(c)
        if (on) {
            pm.setComponentEnabledSetting(ours, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            val all = pm.queryIntentActivities(homeIntent(), 0)
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                .toTypedArray()
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            // Hidden in the SDK (what Android's home picker calls); public in the framework.
            PackageManager::class.java.getMethod("replacePreferredActivity", IntentFilter::class.java,
                Int::class.javaPrimitiveType, Array<ComponentName>::class.java, ComponentName::class.java)
                .invoke(pm, filter, IntentFilter.MATCH_CATEGORY_EMPTY, all, ours)
        } else {
            pm.clearPackagePreferredActivities(c.packageName)
            pm.setComponentEnabledSetting(ours, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        Log.i(TAG, "Home = ${if (on) "MyEX2Center" else "Flyme launcher"}")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Cannot change home: ${t.cause?.message ?: t.message}")
        // Never leave a second home without a preferred one: the car would show a chooser.
        if (on) try {
            c.packageManager.setComponentEnabledSetting(component(c),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        } catch (_: Throwable) {}
        false
    }

    /** The car's own home screen (Flyme launcher), reachable while MyEX2Center is home. */
    fun openCarHome(c: Context) {
        try {
            c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(FLYME_LAUNCHER, FLYME_HOME))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot open the car home: ${t.message}")
        }
    }
}
