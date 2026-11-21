package com.veilframe.app.upscale

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.upscale.inference.NcnnNative
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NcnnCancellationOwnershipTest {
    @Test
    fun cancellationBeforeNativeWorkCannotBeResetByLaterCall() {
        assertTrue(NcnnNative.isAvailable)
        val handle = NcnnNative.createInstance(gpuid = -1, numThreads = 2)
        assertTrue(handle > 0)
        try {
            NcnnNative.cancel(handle)
            assertEquals(NcnnNative.STATUS_CANCELLED,
                NcnnNative.loadModel(handle, "missing.param", "missing.bin", 2, 64))
            assertEquals(NcnnNative.STATUS_CANCELLED,
                NcnnNative.processStripStreaming(handle, 16, 16, 3,
                    stripCallback = { _, _, _ -> fail("No strip expected"); -1 },
                    rowCallback = { _, _, _, _ -> fail("No row expected"); -1 }))
        } finally { NcnnNative.destroyInstance(handle) }
    }
}
