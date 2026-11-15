package com.veilframe.app.media.blur

import com.veilframe.app.media.ImageBlurScope
import com.veilframe.app.media.ImageBlurType
import com.veilframe.app.media.ImageEditState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageBlurStateTest {

    @Test
    fun testDefaultBlurState() {
        val state = ImageEditState()
        assertEquals(ImageBlurScope.NONE, state.blurScope)
        assertEquals(ImageBlurType.GAUSSIAN, state.blurType)
        assertEquals(25, state.blurRadius)
        assertNull(state.blurBrushMask)
        assertNull(state.blurBgSubjectCutout)
    }

    @Test
    fun testBlurStateReset() {
        val state = ImageEditState()
        state.blurScope = ImageBlurScope.BACKGROUND
        state.blurType = ImageBlurType.BOX
        state.blurRadius = 40

        state.reset()

        assertEquals(ImageBlurScope.NONE, state.blurScope)
        assertEquals(ImageBlurType.GAUSSIAN, state.blurType)
        assertEquals(25, state.blurRadius)
        assertNull(state.blurBrushMask)
        assertNull(state.blurBgSubjectCutout)
    }

    @Test
    fun testCopyFromPreservesBlurState() {
        val src = ImageEditState().apply {
            blurScope = ImageBlurScope.FULL
            blurType = ImageBlurType.MOTION
            blurRadius = 15
        }
        val target = ImageEditState()
        target.copyFrom(src)

        assertEquals(ImageBlurScope.FULL, target.blurScope)
        assertEquals(ImageBlurType.MOTION, target.blurType)
        assertEquals(15, target.blurRadius)
        assertNull(target.blurBrushMask)
        assertNull(target.blurBgSubjectCutout)
    }

    @Test
    fun testBlurTypesEnumValues() {
        val types = ImageBlurType.values()
        assertEquals(10, types.size)
        val names = types.map { it.name }
        assert(names.contains("GAUSSIAN"))
        assert(names.contains("BOX"))
        assert(names.contains("MOSAIC"))
        assert(names.contains("GLASS"))
        assert(names.contains("MOTION"))
        assert(names.contains("BOKEH"))
        assert(names.contains("SOLID"))
        assert(names.contains("PAINT"))
        assert(names.contains("WATER"))
        assert(names.contains("ZOOM"))
    }

    @Test
    fun testBlurScopesEnumValues() {
        val scopes = ImageBlurScope.values()
        assertEquals(4, scopes.size)
        val names = scopes.map { it.name }
        assert(names.contains("NONE"))
        assert(names.contains("FULL"))
        assert(names.contains("BACKGROUND"))
        assert(names.contains("BRUSH"))
    }
}
