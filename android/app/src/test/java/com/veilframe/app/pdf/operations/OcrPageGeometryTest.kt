package com.veilframe.app.pdf.operations

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrPageGeometryTest {
    @Test fun mapsAllRotationsWithNonzeroCropOrigin() {
        val expected = listOf(
            OcrPageGeometry.Placement(30f, 260f, 1f, 0f, 40f, 64f),
            OcrPageGeometry.Placement(60f, 52f, 0f, 1f, 64f, 40f),
            OcrPageGeometry.Placement(190f, 100f, -1f, 0f, 40f, 64f),
            OcrPageGeometry.Placement(160f, 308f, 0f, -1f, 64f, 40f)
        )
        for ((i, rotation) in listOf(0,90,180,270).withIndex()) {
            val v = OcrPageGeometry(10f,20f,200f,320f,rotation,1000,800).place(100,40,300,200)
            val e = expected[i]
            assertEquals(e.x,v.x,0.001f); assertEquals(e.y,v.y,0.001f)
            assertEquals(e.ux,v.ux,0.001f); assertEquals(e.uy,v.uy,0.001f)
            // Height uses 160 of 800 pixels, not the nominal requested DPI.
            assertEquals(e.width,v.width,0.001f); assertEquals(e.height,v.height,0.001f)
        }
    }
}
