package com.veilframe.app.cv.segmentation.rembg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RembgModelDownloadManagerTest {

    @Test
    fun testOriginAllowlist() {
        assertTrue(RembgModelDownloadManager.isAllowedModelUrl("https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-general-use.onnx"))
        assertTrue(RembgModelDownloadManager.isAllowedModelUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/model.onnx"))
        assertTrue(RembgModelDownloadManager.isAllowedModelUrl("https://huggingface.co/model.onnx"))
        assertTrue(RembgModelDownloadManager.isAllowedModelUrl("https://hf.co/model.onnx"))

        // Reject non-https, local or unknown domains
        assertFalse(RembgModelDownloadManager.isAllowedModelUrl("http://github.com/insecure.onnx"))
        assertFalse(RembgModelDownloadManager.isAllowedModelUrl("https://malicious-site.com/model.onnx"))
        assertFalse(RembgModelDownloadManager.isAllowedModelUrl("file:///android_asset/model.onnx"))
    }

    @Test
    fun testDownloadStateProgressCalculation() {
        val stateZero = RembgDownloadState(totalBytes = 0L, isDownloading = false)
        assertTrue(stateZero.progressFraction == 0f)
        assertTrue(stateZero.progressPercent == 0)

        val stateDownloading = RembgDownloadState(
            bytesDownloaded = 50_000_000L,
            totalBytes = 100_000_000L,
            isDownloading = true
        )
        assertTrue(stateDownloading.progressFraction == 0.5f)
        assertTrue(stateDownloading.progressPercent == 50)
    }
}
