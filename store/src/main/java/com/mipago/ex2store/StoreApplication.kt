package com.mipago.ex2store

import android.app.Application

/** Makes sure the periodic update check exists whenever the Store process starts. */
class StoreApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateScheduler.schedulePeriodic(this)
    }
}
