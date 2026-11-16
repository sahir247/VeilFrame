package com.veilframe.app.cv.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device regression test for atomic, mmap-friendly bundled LiteRT model staging. */
@RunWith(AndroidJUnit4::class)
class LiteRtModelStorageTest {
    @Test
    fun stagesBundledModelOnceAndReusesTheInstalledRevision() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val staged = LiteRtModelStorage.stageBundled(
            context, "fairscan-segmentation-model.tflite", "fairscan-test"
        )
        try {
            assertTrue(staged.isFile)
            assertTrue(staged.length() > 4_000_000L)
            assertEquals(context.noBackupFilesDir, staged.parentFile)
            val reused = LiteRtModelStorage.stageBundled(
                context, "fairscan-segmentation-model.tflite", "fairscan-test"
            )
            assertEquals(staged.absolutePath, reused.absolutePath)
            assertEquals(staged.length(), reused.length())
        } finally {
            staged.delete()
        }
    }
}
