package com.veilframe.app.pdf

import com.veilframe.app.pdf.operations.SignaturePageGeometry
import org.junit.Assert.*
import org.junit.Test

class SignaturePageGeometryTest {
    private fun point(expected: Pair<Float,Float>, actual: Pair<Float,Float>) {
        assertEquals(expected.first,actual.first,0.001f)
        assertEquals(expected.second,actual.second,0.001f)
    }

    @Test fun croppedPageInverseRotationMapsDisplayedCorners() {
        val expected = listOf(10f to 220f,10f to 20f,110f to 20f,110f to 220f)
        for ((index,rotation) in listOf(0,90,180,270).withIndex()) {
            val g = SignaturePageGeometry(10f,20f,100f,200f,rotation)
            point(expected[index],g.toPdf(0f,0f))
            point(expected[(index+2)%4],g.toPdf(g.displayWidth,g.displayHeight))
        }
    }

    @Test fun imageMatrixPreservesCenterAndFootprintAtAnyAngle() {
        for (rotation in listOf(0,90,180,270)) {
            val g = SignaturePageGeometry(10f,20f,100f,200f,rotation)
            for (angle in listOf(0f,90f,135f,180f,270f,359f,-135f)) {
                val m = g.transform(10f,20f,40f,12f,angle)
                point(g.toPdf(30f,26f),m.e+(m.a+m.c)/2f to m.f+(m.b+m.d)/2f)
                assertEquals(480f,m.a*m.d-m.b*m.c,0.01f)
                assertEquals(40f,kotlin.math.sqrt(m.a*m.a+m.b*m.b),0.01f)
                assertEquals(12f,kotlin.math.sqrt(m.c*m.c+m.d*m.d),0.01f)
            }
        }
    }

    @Test fun quickSignatureFitsSmallLandscapePageAndKeepsAspect() {
        val g = SignaturePageGeometry(15f,25f,90f,60f,90)
        val rect = g.relativeRect(1f,0f,2.5f,3f)
        assertEquals(g.displayWidth,rect[0]+rect[2],0.001f)
        assertEquals(g.displayHeight,rect[1]+rect[3],0.001f)
        assertEquals(3f,rect[2]/rect[3],0.001f)
    }

    @Test fun incompatiblePreviewGeometryAndNonFiniteInputFailClosed() {
        val g = SignaturePageGeometry(0f,0f,100f,200f,0)
        assertThrows(IllegalArgumentException::class.java) { g.transform(0f,0f,40f,12f,0f,200f,100f) }
        assertThrows(IllegalArgumentException::class.java) { g.transform(Float.NaN,0f,40f,12f,0f) }
    }
}
