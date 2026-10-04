package com.veilframe.app.qr.ui

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying that QrStudioViewModel manages superseded QR bitmap lifecycles safely,
 * preventing premature recycling while active render or export jobs are running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QrStudioViewModelBitmapLifecycleTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createTestBitmap(): Bitmap {
        val bmp: Bitmap? = try {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            null
        }
        return bmp ?: allocateBitmapReflectively()
    }

    private fun allocateBitmapReflectively(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    @Test
    fun testReplacingSourceImageDuringActiveRenderDefersRecycling() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        val bmp1 = createTestBitmap()
        val bmp2 = createTestBitmap()

        // 1. Initial source image
        vm.updateSourceImage(bmp1)
        assertSame(bmp1, vm.state.value.sourceImage)

        // 2. Simulate an active render job referencing generation 1
        vm.registerActiveRenderGeneration(1L)
        assertTrue(vm.getActiveRenderGenerations().contains(1L))

        // 3. Replace source image with bmp2
        vm.updateSourceImage(bmp2)
        assertSame(bmp2, vm.state.value.sourceImage)

        // bmp1 is now superseded, but must NOT be recycled because generation 1 is active
        assertTrue(
            "bmp1 must be queued for retirement while generation 1 is active",
            vm.isBitmapQueuedForRetirement(bmp1)
        )
        assertEquals(1, vm.getSupersededBitmapsCount())

        // Explicit drain while render is active still preserves bmp1
        vm.drainSupersededBitmaps()
        assertTrue(
            "bmp1 must remain in retirement queue as long as active render is running",
            vm.isBitmapQueuedForRetirement(bmp1)
        )

        // 4. Complete the active render job
        vm.unregisterActiveRenderGeneration(1L)
        assertFalse(vm.getActiveRenderGenerations().contains(1L))

        // Queue must now be drained and bmp1 retired
        assertFalse(
            "bmp1 must be drained once active render completes",
            vm.isBitmapQueuedForRetirement(bmp1)
        )
        assertEquals(0, vm.getSupersededBitmapsCount())
    }

    @Test
    fun testActiveExportPreventsSupersededBitmapRecyclingUntilComplete() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        val oldBmp = createTestBitmap()

        // 1. Simulate an active export job
        vm.incrementActiveExportCount()
        assertEquals(1, vm.getActiveExportCount())

        // 2. Retire bitmap while export is in-flight
        vm.retireBitmap(oldBmp, generation = 1L)
        assertTrue(
            "oldBmp must be queued for retirement while export is active",
            vm.isBitmapQueuedForRetirement(oldBmp)
        )

        // Drain while export is running must keep it queued
        vm.drainSupersededBitmaps()
        assertTrue(
            "oldBmp must not be recycled while export job is reading state snapshots",
            vm.isBitmapQueuedForRetirement(oldBmp)
        )

        // 3. Export finishes
        vm.decrementActiveExportCount()
        assertEquals(0, vm.getActiveExportCount())

        // Now it must be drained
        assertFalse(
            "oldBmp must be drained once export finishes",
            vm.isBitmapQueuedForRetirement(oldBmp)
        )
        assertEquals(0, vm.getSupersededBitmapsCount())
    }

    @Test
    fun testSuccessivePreviewBitmapReplacementRetiresOldPreviewOnceRenderFinishes() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        val preview1 = createTestBitmap()
        val preview2 = createTestBitmap()

        // 1. Simulate an active preview render at generation 5
        vm.registerActiveRenderGeneration(5L)

        // 2. Queue preview1 for retirement at generation 5
        vm.retireBitmap(preview1, generation = 5L, ownership = QrStudioViewModel.BitmapOwnership.VIEW_MODEL)
        assertTrue(vm.isBitmapQueuedForRetirement(preview1))

        // 3. Finish generation 5
        vm.unregisterActiveRenderGeneration(5L)
        assertFalse(
            "preview1 must be drained and recycled once referencing render finishes",
            vm.isBitmapQueuedForRetirement(preview1)
        )
    }

    @Test
    fun testTerminateSessionWithActiveRenderQueuesBitmapsUntilActiveRenderExits() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        val bmpSrc = createTestBitmap()
        val bmpBg = createTestBitmap()
        val bmpLogo = createTestBitmap()

        vm.updateSourceImage(bmpSrc)
        vm.updateBackgroundImage(bmpBg)
        vm.updateLogo(bmpLogo)

        // 1. Simulate active render generation 1
        vm.registerActiveRenderGeneration(1L)

        // 2. Terminate session (e.g. fragment back / screen exit)
        vm.terminateSession()

        // State is cleared immediately
        assertEquals("", vm.state.value.content)
        assertNull(vm.state.value.sourceImage)
        assertNull(vm.state.value.backgroundImage)
        assertNull(vm.state.value.logo)

        // All 3 bitmaps must be queued, but NOT yet recycled because render generation 1 is still running
        assertTrue(vm.isBitmapQueuedForRetirement(bmpSrc))
        assertTrue(vm.isBitmapQueuedForRetirement(bmpBg))
        assertTrue(vm.isBitmapQueuedForRetirement(bmpLogo))
        assertEquals(3, vm.getSupersededBitmapsCount())

        // 3. Active render completes and exits
        vm.unregisterActiveRenderGeneration(1L)

        // All superseded bitmaps must now be drained
        assertFalse(vm.isBitmapQueuedForRetirement(bmpSrc))
        assertFalse(vm.isBitmapQueuedForRetirement(bmpBg))
        assertFalse(vm.isBitmapQueuedForRetirement(bmpLogo))
        assertEquals(0, vm.getSupersededBitmapsCount())
    }

    @Test
    fun testBitmapsCurrentlyHeldInStateOrUndoSnapshotAreNeverDrained() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        val bmpSrc = createTestBitmap()
        vm.updateSourceImage(bmpSrc)

        // Attempting to retire a bitmap that is still held in current state
        vm.retireBitmap(bmpSrc, generation = 1L)
        assertTrue(vm.isBitmapQueuedForRetirement(bmpSrc))

        // drain must NOT discard or recycle bmpSrc because it is still state.value.sourceImage
        vm.drainSupersededBitmaps()
        assertTrue(
            "Bitmap held in active state must never be drained",
            vm.isBitmapQueuedForRetirement(bmpSrc)
        )

        // Now remove it from state
        vm.removeSourceImage()
        assertNull(vm.state.value.sourceImage)

        // Now drain will safely recycle it
        vm.drainSupersededBitmaps()
        assertFalse(
            "Bitmap removed from state can now be drained",
            vm.isBitmapQueuedForRetirement(bmpSrc)
        )
    }

    @Test
    fun testLaunchExportJobTracksActiveExportCountAndDrainsOnCompletion() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        val bmp = createTestBitmap()
        var insideJobExportCount = -1

        val job = vm.launchExportJob {
            insideJobExportCount = vm.getActiveExportCount()
            vm.retireBitmap(bmp, generation = 1L)
            assertTrue("Bitmap must be queued during export", vm.isBitmapQueuedForRetirement(bmp))
        }

        kotlinx.coroutines.runBlocking {
            job.join()
        }

        assertEquals(1, insideJobExportCount)
        assertEquals(0, vm.getActiveExportCount())
        assertFalse(
            "Bitmap must be drained immediately upon export job completion",
            vm.isBitmapQueuedForRetirement(bmp)
        )
    }
}
