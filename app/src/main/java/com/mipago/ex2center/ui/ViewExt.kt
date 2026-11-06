package com.mipago.ex2center.ui

import android.text.TextUtils
import android.widget.TextView

/**
 * Sets the text only when it actually changes.
 *
 * TextView.setText() on a wrap_content view requests a layout pass of the whole window even
 * when the text is identical. On the IHU629G a full Dashboard layout pass costs ~2.8 s of main
 * thread time, so the 1 s clock tick and the telemetry refresh froze the UI almost permanently.
 */
fun TextView.setTextIfChanged(value: CharSequence) {
    if (!TextUtils.equals(text, value)) text = value
}
