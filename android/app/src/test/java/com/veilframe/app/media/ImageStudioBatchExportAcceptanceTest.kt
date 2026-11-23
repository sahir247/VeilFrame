package com.veilframe.app.media

import org.junit.Assert.assertThrows
import org.junit.Test

class ImageStudioBatchExportAcceptanceTest {
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
