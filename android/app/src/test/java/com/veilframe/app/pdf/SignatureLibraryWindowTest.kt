package com.veilframe.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class SignatureLibraryWindowTest {
    @Test fun paginationClampsAfterDeletionAndNeverAdmitsTooManyThumbnails() {
        assertEquals(SignatureLibraryWindow(0,1,0,0),SignatureLibraryWindow.at(0,10))
        assertEquals(SignatureLibraryWindow(2,3,32,33),SignatureLibraryWindow.at(33,100))
        assertEquals(SignatureLibraryWindow(1,2,16,32),SignatureLibraryWindow.at(32,2))
        assertEquals(SignatureLibraryWindow(0,128,0,16),SignatureLibraryWindow.at(2048,-1))
    }
}
