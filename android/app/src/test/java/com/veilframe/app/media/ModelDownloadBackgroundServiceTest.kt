package com.veilframe.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadBackgroundServiceTest {

    @Test
    fun `verify service constants contract`() {
        assertEquals("veilframe_model_downloads", ModelDownloadBackgroundService.CHANNEL_ID)
        assertEquals(1202, ModelDownloadBackgroundService.NOTIFICATION_ID)
        assertEquals("com.veilframe.action.START_MODEL_DOWNLOAD", ModelDownloadBackgroundService.ACTION_START_DOWNLOAD)
        assertEquals("com.veilframe.action.CANCEL_MODEL_DOWNLOAD", ModelDownloadBackgroundService.ACTION_CANCEL_DOWNLOAD)
        assertEquals("com.veilframe.action.PROMOTE_MODEL_DOWNLOAD_TO_BACKGROUND", ModelDownloadBackgroundService.ACTION_PROMOTE_TO_BACKGROUND)

        assertEquals("extra_model_type", ModelDownloadBackgroundService.EXTRA_MODEL_TYPE)
        assertEquals("extra_model_id", ModelDownloadBackgroundService.EXTRA_MODEL_ID)
        assertEquals("extra_model_name", ModelDownloadBackgroundService.EXTRA_MODEL_NAME)
        assertEquals("extra_run_in_background", ModelDownloadBackgroundService.EXTRA_RUN_IN_BACKGROUND)
    }

    @Test
    fun `verify listener management`() {
        var progressCalled = false
        val listener = object : ModelDownloadBackgroundService.DownloadListener {
            override fun onProgress(modelId: String, downloaded: Long, total: Long, percent: Int, speedBytesPerSec: Long) {
                progressCalled = true
            }
            override fun onVerifying(modelId: String) {}
            override fun onSuccess(modelId: String, modelType: String) {}
            override fun onError(modelId: String, error: String) {}
            override fun onCancelled(modelId: String) {}
        }

        ModelDownloadBackgroundService.addListener(listener)
        assertTrue(ModelDownloadBackgroundService.listeners.contains(listener))

        listener.onProgress("test_model", 100, 1000, 10, 500)
        assertTrue(progressCalled)

        ModelDownloadBackgroundService.removeListener(listener)
        assertFalse(ModelDownloadBackgroundService.listeners.contains(listener))
    }

    @Test
    fun `verify initial download state is idle`() {
        assertFalse(ModelDownloadBackgroundService.isAnyDownloading())
        assertFalse(ModelDownloadBackgroundService.isDownloading("u2netp"))
        assertFalse(ModelDownloadBackgroundService.isDownloading("modnet"))
        assertFalse(ModelDownloadBackgroundService.isDownloading("realesrgan-x4plus"))
    }
}
