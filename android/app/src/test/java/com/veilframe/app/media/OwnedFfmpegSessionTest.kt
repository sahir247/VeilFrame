package com.veilframe.app.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedFfmpegSessionTest {
    @Test fun cancellationTargetsOnlyOwnedSessionAndWaitsForNativeCompletion() = runBlocking {
        val requests = mutableListOf<Long>()
        val entered = CompletableDeferred<Unit>()
        lateinit var finish: (String) -> Unit
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOwnedNativeSession<String>(
                start = { callback -> finish = callback; entered.complete(Unit); 42L },
                cancel = { requests.add(it) }
            )
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOwnedNativeSession<String>(start = { it("other"); 99L }, cancel = { requests.add(it) })
        }
        entered.await()
        first.cancel()
        yield()
        assertEquals(listOf(42L), requests)
        assertFalse(first.isCompleted)
        assertEquals("other", second.await())
        finish("cancelled")
        first.join()
        assertTrue(first.isCancelled)
    }
}
