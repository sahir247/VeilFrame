package com.veilframe.app.upscale.inference

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlinx.coroutines.CancellationException

class PendingPublicationTest {
    @Test fun failedWriteNeverPublishesAndRollsBack() {
        var rolledBack = 0
        var published = 0
        assertThrows(IOException::class.java) {
            PendingPublication.run({ rolledBack++ }, { throw IOException("null stream") }, { published++; true })
        }
        assertEquals(1, rolledBack)
        assertEquals(0, published)
    }

    @Test fun cancellationAndFailedPublicationRollBack() {
        var rolledBack = 0
        assertThrows(CancellationException::class.java) {
            PendingPublication.run({ rolledBack++ }, { throw CancellationException("cancel") }, { true })
        }
        assertThrows(IOException::class.java) {
            PendingPublication.run({ rolledBack++ }, {}, { false })
        }
        assertEquals(2, rolledBack)
        PendingPublication.run({ rolledBack++ }, {}, { true })
        assertEquals(2, rolledBack)
    }
}
