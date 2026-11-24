package com.veilframe.app.tools

import org.junit.Assert.*
import org.junit.Test

class BundleInventoryFingerprintTest {
    @Test fun byteIdentityDetectsSameLengthContentMutationWithoutProviderMetadata() {
        val before = AiBundleEngine.contentStamp("value=A".toByteArray())
        val unchanged = AiBundleEngine.contentStamp("value=A".toByteArray())
        val after = AiBundleEngine.contentStamp("value=B".toByteArray())
        assertEquals(before.size, after.size)
        assertEquals(before, unchanged)
        AiBundleEngine.requireUnchangedContent(before, unchanged)
        assertThrows(java.io.IOException::class.java) {
            AiBundleEngine.requireUnchangedContent(before, after)
        }
    }

    @Test fun detectsIdentityPathSizeAndDirectoryChangesBeforeTransfer() {
        val file = AiBundleEngine.BundleInput("content://provider/1", "src/a.kt", 120, false, 1732200000000L)
        val sibling = AiBundleEngine.BundleInput("content://provider/2", "src/b.kt", 120, false)
        val baseline = AiBundleEngine.inventoryFingerprint(listOf(file))
        assertEquals(baseline, AiBundleEngine.inventoryFingerprint(listOf(file.copy())))
        for (changed in listOf(file.copy(uri = sibling.uri), file.copy(path = sibling.path),
            file.copy(size = 121), file.copy(directory = true),
            file.copy(lastModified = 1732200000001L), file.copy(lastModified = -1L))) {
            assertNotEquals(baseline, AiBundleEngine.inventoryFingerprint(listOf(changed)))
        }
        assertNotEquals(baseline, AiBundleEngine.inventoryFingerprint(listOf(file, sibling)))
        assertNotEquals(baseline, AiBundleEngine.inventoryFingerprint(emptyList()))
    }
}
