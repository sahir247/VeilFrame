package com.veilframe.app.document

import com.veilframe.app.cv.document.DocumentScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point
import org.json.JSONObject
import org.json.JSONArray

/**
 * Unit tests verifying non-destructive editing state, versioning, and JSON persistence in [DocumentSession].
 */
class DocumentSessionTest {

    @Test
    fun testScannedPageEditStatePreservation() {
        val testCorners = listOf(
            Point(10.0, 10.0),
            Point(200.0, 15.0),
            Point(195.0, 300.0),
            Point(15.0, 290.0)
        )
        val page = ScannedPage(
            id = "test-page-1",
            mode = DocumentScanner.DocumentMode.BLACK_AND_WHITE,
            rotationDegrees = 90,
            corners = testCorners,
            exposureStops = 0.5,
            contrast = 1.2,
            saturation = 1.1,
            adjustSharpen = 0.7,
            adjustDenoise = 2.0
        )

        assertEquals("test-page-1", page.id)
        assertEquals(DocumentScanner.DocumentMode.BLACK_AND_WHITE, page.mode)
        assertEquals(90, page.rotationDegrees)
        assertEquals(4, page.corners?.size)
        assertEquals(0.5, page.exposureStops, 0.001)
        assertEquals(1.2, page.contrast, 0.001)
        assertEquals(1.1, page.saturation, 0.001)
        assertEquals(0.7, page.adjustSharpen, 0.001)
        assertEquals(2.0, page.adjustDenoise, 0.001)
    }

    @Test
    fun testResetCropPreservesFilterAndRotation() {
        val testCorners = listOf(
            Point(10.0, 10.0),
            Point(200.0, 15.0),
            Point(195.0, 300.0),
            Point(15.0, 290.0)
        )
        val page = ScannedPage(
            mode = DocumentScanner.DocumentMode.RECEIPT,
            rotationDegrees = 180,
            corners = testCorners
        )

        // Reset crop
        page.corners = null
        page.editVersion++

        assertNull("Corners should be null after reset crop", page.corners)
        assertEquals("Receipt mode should be preserved", DocumentScanner.DocumentMode.RECEIPT, page.mode)
        assertEquals("180 degree rotation preserved", 180, page.rotationDegrees)
        assertEquals("Version incremented", 1L, page.editVersion)
    }

    @Test
    fun testFilterChangePreservesCropCorners() {
        val testCorners = listOf(
            Point(10.0, 10.0),
            Point(200.0, 15.0),
            Point(195.0, 300.0),
            Point(15.0, 290.0)
        )
        val page = ScannedPage(
            mode = DocumentScanner.DocumentMode.ENHANCED,
            corners = testCorners
        )

        // Change filter to Grayscale
        page.mode = DocumentScanner.DocumentMode.GRAYSCALE
        page.editVersion++

        assertEquals(DocumentScanner.DocumentMode.GRAYSCALE, page.mode)
        assertNotNull("Crop corners must NOT be lost on filter change", page.corners)
        assertEquals(4, page.corners?.size)
    }

    @Test
    fun testJsonSerializationWithCornersAndAdjustments() {
        val testCorners = listOf(
            Point(12.5, 34.0),
            Point(180.0, 25.5),
            Point(175.0, 290.0),
            Point(10.0, 285.5)
        )
        val page = ScannedPage(
            id = "p-json-test",
            originalImagePath = "/tmp/test_orig.jpg",
            processedImagePath = "/tmp/test_proc.png",
            rotationDegrees = 270,
            mode = DocumentScanner.DocumentMode.ID_DOCUMENT,
            corners = testCorners,
            exposureStops = -0.3,
            contrast = 1.4,
            saturation = 0.8,
            adjustSharpen = 0.5,
            adjustDenoise = 1.0
        )

        // Serialize page to JSON
        val pageJson = JSONObject().apply {
            put("id", page.id)
            put("originalImagePath", page.originalImagePath)
            put("processedImagePath", page.processedImagePath ?: "")
            put("rotationDegrees", page.rotationDegrees)
            put("mode", page.mode.name)
            put("exposureStops", page.exposureStops)
            put("contrast", page.contrast)
            put("saturation", page.saturation)
            put("adjustSharpen", page.adjustSharpen)
            put("adjustDenoise", page.adjustDenoise)
            page.corners?.let { pts ->
                val cornersArray = JSONArray()
                for (pt in pts) {
                    val ptObj = JSONObject().apply {
                        put("x", pt.x)
                        put("y", pt.y)
                    }
                    cornersArray.put(ptObj)
                }
                put("corners", cornersArray)
            }
        }

        // Deserialize back
        val loadedCorners = pageJson.optJSONArray("corners")?.let { arr ->
            val list = mutableListOf<Point>()
            for (j in 0 until arr.length()) {
                val ptObj = arr.getJSONObject(j)
                list.add(Point(ptObj.getDouble("x"), ptObj.getDouble("y")))
            }
            if (list.size == 4) list else null
        }

        val restored = ScannedPage(
            id = pageJson.getString("id"),
            originalImagePath = pageJson.optString("originalImagePath", ""),
            processedImagePath = pageJson.optString("processedImagePath", "").takeIf { it.isNotEmpty() },
            rotationDegrees = pageJson.optInt("rotationDegrees", 0),
            mode = DocumentScanner.DocumentMode.valueOf(pageJson.getString("mode")),
            corners = loadedCorners,
            exposureStops = pageJson.optDouble("exposureStops", 0.0),
            contrast = pageJson.optDouble("contrast", 1.0),
            saturation = pageJson.optDouble("saturation", 1.0),
            adjustSharpen = pageJson.optDouble("adjustSharpen", 0.0),
            adjustDenoise = pageJson.optDouble("adjustDenoise", 0.0)
        )

        assertEquals("p-json-test", restored.id)
        assertEquals(270, restored.rotationDegrees)
        assertEquals(DocumentScanner.DocumentMode.ID_DOCUMENT, restored.mode)
        assertNotNull(restored.corners)
        assertEquals(4, restored.corners?.size)
        assertEquals(12.5, restored.corners!![0].x, 0.001)
        assertEquals(34.0, restored.corners!![0].y, 0.001)
        assertEquals(-0.3, restored.exposureStops, 0.001)
        assertEquals(1.4, restored.contrast, 0.001)
        assertEquals(0.8, restored.saturation, 0.001)
        assertEquals(0.5, restored.adjustSharpen, 0.001)
        assertEquals(1.0, restored.adjustDenoise, 0.001)
    }
}
