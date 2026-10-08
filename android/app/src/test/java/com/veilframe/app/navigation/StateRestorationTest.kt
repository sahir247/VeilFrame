package com.veilframe.app.navigation

import android.os.Bundle
import com.veilframe.app.tools.ToolMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StateRestorationTest {

    @Test
    fun `all ScreenState values serialize and deserialize losslessly`() {
        for (state in ScreenState.values()) {
            val bundle = Bundle()
            bundle.putString("screen_state", state.name)

            val restoredName = bundle.getString("screen_state")
            assertNotNull(restoredName)
            val restored = ScreenState.valueOf(restoredName!!)
            assertEquals("ScreenState must restore identically", state, restored)
        }
    }

    @Test
    fun `all ToolMode values serialize and deserialize losslessly`() {
        for (mode in ToolMode.values()) {
            val bundle = Bundle()
            bundle.putString("tool_mode", mode.name)

            val restoredName = bundle.getString("tool_mode")
            assertNotNull(restoredName)
            val restored = ToolMode.valueOf(restoredName!!)
            assertEquals("ToolMode must restore identically", mode, restored)
        }
    }

    @Test
    fun `media URI collections and string references round-trip via bundle`() {
        val bundle = Bundle()
        val testUris = arrayListOf(
            "content://media/external/images/media/101",
            "content://media/external/images/media/102"
        )
        bundle.putStringArrayList("studio_image_uris", testUris)
        bundle.putString("selected_file_uri", "content://media/external/video/media/201")
        bundle.putBoolean("is_folder_selected", false)

        val restoredUris = bundle.getStringArrayList("studio_image_uris")
        assertNotNull(restoredUris)
        assertEquals(2, restoredUris!!.size)
        assertEquals("content://media/external/images/media/101", restoredUris[0])
        assertEquals("content://media/external/video/media/201", bundle.getString("selected_file_uri"))
        assertTrue(!bundle.getBoolean("is_folder_selected"))
    }
}
