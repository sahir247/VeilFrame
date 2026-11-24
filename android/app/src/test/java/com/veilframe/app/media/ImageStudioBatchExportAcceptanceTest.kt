package com.veilframe.app.media

import org.junit.Assert.assertThrows
import org.junit.Test

class ImageStudioBatchExportAcceptanceTest {
    @Test fun rollbackDeletesOnlyCreatedIdentitiesInReverseOrderAndReportsRefusal() {
        val owned = mutableListOf("new-a", "new-b", "new-c")
        val visited = mutableListOf<String>()
        val failed = ImageStudioBatchExportAcceptance.rollbackCreated(owned) {
            visited.add(it)
            it != "new-b"
        }
        org.junit.Assert.assertEquals(listOf("new-c", "new-b", "new-a"), visited)
        org.junit.Assert.assertEquals(1, failed)
        org.junit.Assert.assertTrue(owned.isEmpty())
    }

    @Test fun fullResolutionExportNeverAcceptsPreviewFallback() {
        ImageStudioBatchExportAcceptance.requireOriginalDimensions(4000, 3000, 4000, 3000)
        assertThrows(IllegalStateException::class.java) {
            ImageStudioBatchExportAcceptance.requireOriginalDimensions(4000, 3000, 2000, 1500)
        }
    }

    @Test fun allLocalImagesMayCompleteWithoutExternalDestination() {
        ImageStudioBatchExportAcceptance.requireComplete(3, 3, 0, false)
    }

    @Test fun externalDestinationRequiresEveryImageToBeCopied() {
        ImageStudioBatchExportAcceptance.requireComplete(3, 3, 3, true)
        assertThrows(IllegalStateException::class.java) {
            ImageStudioBatchExportAcceptance.requireComplete(3, 3, 2, true)
        }
    }

    @Test fun evenOneFailedDecodeOrEncodePreventsGreenBatchResult() {
        assertThrows(IllegalStateException::class.java) {
            ImageStudioBatchExportAcceptance.requireComplete(3, 2, 2, true)
        }
        assertThrows(IllegalStateException::class.java) {
            ImageStudioBatchExportAcceptance.requireComplete(3, 2, 0, false)
        }
    }

    @Test fun bogusCountersCannotMasqueradeAsCompletion() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageStudioBatchExportAcceptance.requireComplete(2, 3, 0, false)
        }
    }
}
