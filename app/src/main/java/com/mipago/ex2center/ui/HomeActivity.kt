package com.mipago.ex2center.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Home entry ("Dùng MyEX2Center làm màn hình chính"): the car's "Quay lại Màn hình chính" button,
 * start-up and closing an app all send the HOME intent; this opens the Dashboard and finishes.
 *
 * Disabled in the manifest; [com.mipago.ex2center.utils.HomeMode] enables it and makes it the
 * preferred home. A trampoline instead of a HOME filter on MainActivity: the Dashboard keeps its
 * own single task whether it was opened from the app list or as home.
 */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
        overridePendingTransition(0, 0)
    }
}
