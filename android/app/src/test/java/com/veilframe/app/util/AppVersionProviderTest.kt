package com.veilframe.app.util

import com.veilframe.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionProviderTest {

    @Test
    fun testVersionNameSourcesFromBuildConfig() {
        val versionName = AppVersionProvider.getVersionName()
        assertEquals(BuildConfig.VERSION_NAME, versionName)
        assertEquals("2.3.0", versionName)
        assertTrue(versionName.isNotEmpty())
    }

    @Test
    fun testDisplayVersionFormatting() {
        val display = AppVersionProvider.getDisplayVersion()
        assertEquals("v${BuildConfig.VERSION_NAME}", display)
        assertEquals("v2.3.0", display)
    }

    @Test
    fun testUserAgentFormatting() {
        val userAgent = AppVersionProvider.getUserAgent()
        assertEquals("VeilFrame-Android/${BuildConfig.VERSION_NAME}", userAgent)
        assertEquals("VeilFrame-Android/2.3.0", userAgent)
    }
}
