package com.veilframe.app.tools

import org.junit.Assert.*
import org.junit.Test

class BundleInventoryFingerprintTest {
    @Test fun detectsIdentityPathSizeAndDirectoryChangesBeforeTransfer() {
        val file = AiBundleEngine.BundleInput("content://provider/1", "src/a.kt", 120, false)
        val sibling = AiBundleEngine.BundleInput("content://provider/2", "src/b.kt", 120, false)
        val baseline = AiBundleEngine.inventoryFingerprint(listOf(file))
        assertEquals(baseline, AiBundleEngine.inventoryFingerprint(listOf(file.copy())))
        for (changed in listOf(file.copy(uri = sibling.uri), file.copy(path = sibling.path),
            file.copy(size = 121), file.copy(directory = true))) {
            assertNotEquals(baseline, AiBundleEngine.inventoryFingerprint(listOf(changed)))
        }
        assertNotEquals(baseline, AiBundleEngine.inventoryFingerprint(listOf(file, sibling)))
        assertNotEquals(baseline, AiBundleEngine.inventoryFingerprint(emptyList()))
    }
}
