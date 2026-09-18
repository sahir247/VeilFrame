package com.veilframe.app

import android.app.Application

/**
 * Main application class for VeilFrame Android.
 * Fully native Android runtime with no Python/Chaquopy layer.
 */
class VeilFrameApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
