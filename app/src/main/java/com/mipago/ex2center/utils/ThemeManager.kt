package com.mipago.ex2center.utils

import android.content.Context
import android.content.res.Configuration

/**
 * Light / dark appearance for the whole app (Cài đặt → Giao diện).
 *
 * Colours live in res/values (light) and res/values-night (dark — the original
 * Cyber Emerald & Obsidian palette). The chosen mode is applied by overriding the
 * UI-mode of each Activity / overlay context, so plain framework Activities work
 * without AppCompat.
 */
object ThemeManager {

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    private const val PREFS = "ex2_appearance"
    private const val KEY_MODE = "theme_mode"

    /** Dark by default: the cockpit was designed dark and is easiest on the eyes at night. */
    fun getMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_DARK)

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply()
    }

    fun label(mode: Int) = when (mode) {
        MODE_LIGHT -> "Sáng"
        MODE_DARK -> "Tối"
        else -> "Theo hệ thống"
    }

    /** Wraps [base] so its resources resolve the chosen light/dark colours. */
    fun wrap(base: Context): Context {
        val mode = getMode(base)
        if (mode == MODE_SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        val night = if (mode == MODE_DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    /** Status / navigation bar colours that match the current appearance. */
    fun applyWindow(activity: android.app.Activity) {
        val window = activity.window
        val bg = activity.getColor(com.mipago.ex2center.R.color.bg_primary)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val decor = window.decorView
        var flags = decor.systemUiVisibility
        flags = if (isDark(activity)) {
            flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        decor.systemUiVisibility = flags
    }

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
