package com.mipago.ex2center.utils

import android.widget.Switch
import com.mipago.ex2center.R

/** Shared styling helpers so toggles look identical on the Dashboard and in Cài đặt. */
object UiStyle {

    /** Emerald track when on, neutral when off — readable in both light and dark mode. */
    fun styleSwitch(sw: Switch) {
        sw.thumbTintList = null
        sw.trackTintList = null
        sw.setThumbResource(R.drawable.sw_thumb)
        sw.setTrackResource(R.drawable.sw_track)
    }
}
