package com.veilframe.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.pdf.ui.PdfThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Main application class for VeilFrame Android. */
class VeilFrameApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.veilframe.app.settings.ThemeSettingsManager.init(this)
        // CV native bootstrap (idempotent — OpenCVInitProvider normally ran first).
        runCatching { CvRuntime.initialize() }
        // Initialize the primary QR engine asynchronously to avoid cold-start disk I/O and hashing.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { com.veilframe.app.cv.qr.WeChatQrEngine.install(this@VeilFrameApplication) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        PdfThumbnailLoader.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) CvRuntime.trimMemory()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        PdfThumbnailLoader.clearAllCaches()
        CvRuntime.trimMemory()
    }
}
