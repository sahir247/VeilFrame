package com.veilframe.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main application class for VeilFrame Android.
 * Fully native Android runtime with no Python/Chaquopy layer.
 */
class VeilFrameApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.veilframe.app.settings.ThemeSettingsManager.init(this)
        // CV native bootstrap (idempotent — OpenCVInitProvider normally ran first).
        runCatching { com.veilframe.app.cv.core.CvRuntime.initialize() }
        // Initialise the primary QR engine (cv::wechat_qrcode::WeChatQRCode) asynchronously
        // on Dispatchers.IO to prevent cold-start main thread disk I/O and SHA-256 calculation.
        // Safe to call in tests / stripped builds — degrades to "unavailable" and QR falls back to ML Kit.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.veilframe.app.cv.qr.WeChatQrEngine.install(this@VeilFrameApplication) }
        }
    }
}
