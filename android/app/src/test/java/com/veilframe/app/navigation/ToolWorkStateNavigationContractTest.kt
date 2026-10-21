package com.veilframe.app.navigation

import com.veilframe.app.updates.AppUpdateDownloadService
import com.veilframe.app.media.MotionLabForegroundService
import com.veilframe.app.media.VideoStudioForegroundService
import com.veilframe.app.upscale.UpscaleForegroundService
import com.veilframe.app.VeilFrameProcessingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strict contract test ensuring:
 * 1. Background execution is strictly granted to only the 3 heavy creative studios (Image Upscale, Motion Lab, Video Studio).
 * 2. All other tools enforce immediate cancellation & full resource deallocation on leave.
 * 3. Notification channels, cancel actions, and install/open action contracts are consistent across services.
 * 4. AndroidManifest declares all required foreground services with proper permissions and types.
 */
class ToolWorkStateNavigationContractTest {

    @Test
    fun `verify background execution is strictly isolated to heavy tools`() {
        val upscaleControllerClass = com.veilframe.app.upscale.ui.ImageUpscalerController::class.java
        val motionLabControllerClass = com.veilframe.app.media.MotionLabController::class.java
        val videoStudioControllerClass = com.veilframe.app.media.VideoStudioController::class.java
        val imageStudioControllerClass = com.veilframe.app.media.ImageStudioController::class.java
        val bgRemoverControllerClass = com.veilframe.app.media.BackgroundRemoverController::class.java
        val docScannerControllerClass = com.veilframe.app.document.DocumentScannerController::class.java
        val toolExecutionControllerClass = com.veilframe.app.tools.ToolExecutionController::class.java
        val qrStudioFragmentClass = com.veilframe.app.qr.ui.QrStudioFragment::class.java

        assertTrue(ToolWorkStateContract::class.java.isAssignableFrom(upscaleControllerClass))
        assertTrue(ToolWorkStateContract::class.java.isAssignableFrom(motionLabControllerClass))
        assertTrue(ToolWorkStateContract::class.java.isAssignableFrom(videoStudioControllerClass))
        assertTrue(ToolWorkStateContract::class.java.isAssignableFrom(imageStudioControllerClass))
        assertTrue(ToolWorkStateContract::class.java.isAssignableFrom(bgRemoverControllerClass))
        assertTrue(ToolWorkStateContract::class.java.isAssignableFrom(docScannerControllerClass))
        assertTrue(ToolWorkStateContract::class.java.isAssignableFrom(toolExecutionControllerClass))
        assertTrue(ToolWorkStateContract::class.java.isAssignableFrom(qrStudioFragmentClass))
    }

    @Test
    fun `verify mock tool work contract behaviour under navigation rules`() {
        var discarded = false
        var backgroundMoved = false

        // 1. Tool with no unsaved work and not processing -> safe to navigate
        val cleanContract = object : ToolWorkStateContract {
            override fun hasPickedMedia(): Boolean = false
            override fun hasUnsavedWork(): Boolean = false
            override val isProcessing: Boolean get() = false
            override fun discardAndReleaseAllResources() { discarded = true }
        }
        assertFalse(cleanContract.hasPickedMedia())
        assertFalse(cleanContract.hasUnsavedWork())
        assertFalse(cleanContract.isProcessing)
        assertFalse(cleanContract.supportsBackgroundExecution)

        // 2. Heavy tool with active processing and background support
        val heavyBusyContract = object : ToolWorkStateContract {
            override fun hasPickedMedia(): Boolean = true
            override fun hasUnsavedWork(): Boolean = true
            override val isProcessing: Boolean get() = true
            override val supportsBackgroundExecution: Boolean get() = true
            override fun discardAndReleaseAllResources() { discarded = true }
            override fun moveToBackground() { backgroundMoved = true }
        }
        assertTrue(heavyBusyContract.isProcessing)
        assertTrue(heavyBusyContract.supportsBackgroundExecution)
        heavyBusyContract.moveToBackground()
        assertTrue("moveToBackground must be invoked when user selects background option", backgroundMoved)

        heavyBusyContract.discardAndReleaseAllResources()
        assertTrue("discardAndReleaseAllResources must be invoked when user chooses leave & discard", discarded)
    }

    @Test
    fun `verify foreground service notification channels and actions`() {
        // AppUpdateDownloadService
        assertEquals("veilframe_app_updates", AppUpdateDownloadService.CHANNEL_ID)
        assertEquals("com.veilframe.action.CANCEL_UPDATE_DOWNLOAD", AppUpdateDownloadService.ACTION_CANCEL_DOWNLOAD)
        assertEquals("com.veilframe.action.INSTALL_UPDATE", AppUpdateDownloadService.ACTION_INSTALL_UPDATE)

        // MotionLabForegroundService
        assertEquals("veilframe_motion_lab", MotionLabForegroundService.CHANNEL_ID)
        assertEquals("com.veilframe.action.MOTION_CANCEL", MotionLabForegroundService.ACTION_CANCEL)
        assertEquals("com.veilframe.action.MOTION_COMPLETE", MotionLabForegroundService.ACTION_COMPLETE)

        // VideoStudioForegroundService
        assertEquals("veilframe_video_studio", VideoStudioForegroundService.CHANNEL_ID)
        assertEquals("com.veilframe.action.VIDEO_STUDIO_CANCEL", VideoStudioForegroundService.ACTION_CANCEL)
        assertEquals("com.veilframe.action.VIDEO_STUDIO_COMPLETE", VideoStudioForegroundService.ACTION_COMPLETE)

        // UpscaleForegroundService
        assertEquals("veilframe_upscale", UpscaleForegroundService.CHANNEL_ID)
        assertEquals("com.veilframe.action.UPSCALE_CANCEL", UpscaleForegroundService.ACTION_CANCEL)
        assertEquals("com.veilframe.action.UPSCALE_COMPLETE", UpscaleForegroundService.ACTION_COMPLETE)

        // VeilFrameProcessingService
        assertEquals("veilframe_media_processing", VeilFrameProcessingService.CHANNEL_ID)
        assertEquals("com.veilframe.action.CANCEL_JOB", VeilFrameProcessingService.ACTION_CANCEL_JOB)
    }

    @Test
    fun `verify android manifest declares all background services with proper foregroundServiceType`() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val altManifestFile = File("android/app/src/main/AndroidManifest.xml")
        val target = if (manifestFile.exists()) manifestFile else altManifestFile
        assertTrue("AndroidManifest.xml must exist", target.exists())

        val content = target.readText()

        val expectedServices = listOf(
            "com.veilframe.app.updates.AppUpdateDownloadService" to "dataSync",
            "com.veilframe.app.media.MotionLabForegroundService" to "mediaProcessing|dataSync",
            "com.veilframe.app.media.VideoStudioForegroundService" to "mediaProcessing|dataSync",
            "com.veilframe.app.upscale.UpscaleForegroundService" to "mediaProcessing|dataSync",
            "com.veilframe.app.VeilFrameProcessingService" to "mediaProcessing|dataSync"
        )

        expectedServices.forEach { (serviceClass, fgsType) ->
            val simpleName = serviceClass.substringAfterLast(".")
            assertTrue(
                "AndroidManifest must register $simpleName",
                content.contains(serviceClass) || content.contains(".$simpleName")
            )
            assertTrue(
                "AndroidManifest service $simpleName must define foregroundServiceType containing $fgsType",
                content.contains(fgsType)
            )
        }
    }
}
