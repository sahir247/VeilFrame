package com.veilframe.app

import android.app.Application

/**
 * Main application class for VeilFrame Android.
 * Fully native Android runtime with no Python/Chaquopy layer.
 */
class VeilFrameApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.veilframe.app.settings.ThemeSettingsManager.init(this)
        // Initialise the primary QR engine (cv::wechat_qrcode::WeChatQRCode):
        // extracts detector + super-resolution models from assets and loads the
        // native engine. Safe to call in tests / stripped builds — degrades to
        // "unavailable" and the QR chain falls back to ML Kit.
        runCatching { com.veilframe.app.cv.qr.WeChatQrEngine.install(this) }
    }
}
