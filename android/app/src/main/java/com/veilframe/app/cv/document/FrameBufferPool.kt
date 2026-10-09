package com.veilframe.app.cv.document

import com.veilframe.app.cv.core.CvRuntime
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Pre-allocated zero-allocation buffer pool for live CameraX document frame analysis.
 *
 * Pre-allocates native Mats and flat byte arrays when camera stream resolution
 * is negotiated. Reuses buffers across frames so the Garbage Collector is never
 * invoked in the 30/60 fps viewfinder loop.
 */
class FrameBufferPool(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val maxEdge: Int = 1024,
    private val matFactory: ((Int, Int, Int) -> Mat)? = null,
) {
    val yPlaneBytes = ByteArray(width * height)

    private fun createMat(rows: Int, cols: Int, type: Int): Mat? {
        return when {
            matFactory != null -> matFactory.invoke(rows, cols, type)
            CvRuntime.isNativeAvailable -> Mat(rows, cols, type)
            else -> null
        }
    }

    // Raw luma plane Mat
    val rawMat: Mat? = createMat(height, width, CvType.CV_8UC1)

    // Upright dimensions after sensor rotation (typically 90 or 270 deg on Android phones)
    val uprightWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
    val uprightHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

    val uprightMat: Mat? = if (rotationDegrees != 0) {
        createMat(uprightHeight, uprightWidth, CvType.CV_8UC1)
    } else {
        rawMat
    }

    // Scaled analysis dimensions bounded to maxEdge (e.g. <=1024)
    val maxDim = maxOf(uprightWidth, uprightHeight)
    val scale = if (maxDim > maxEdge) maxEdge.toDouble() / maxDim.toDouble() else 1.0
    val analysisWidth = maxOf(1, Math.round(uprightWidth * scale).toInt())
    val analysisHeight = maxOf(1, Math.round(uprightHeight * scale).toInt())

    val analysisMat: Mat? = if (scale < 1.0) {
        createMat(analysisHeight, analysisWidth, CvType.CV_8UC1)
    } else {
        uprightMat
    }

    val blurredMat: Mat? = createMat(analysisHeight, analysisWidth, CvType.CV_8UC1)
    val edgesMat: Mat? = createMat(analysisHeight, analysisWidth, CvType.CV_8UC1)

    private fun createMatOfDouble(): org.opencv.core.MatOfDouble? {
        return when {
            matFactory != null -> matFactory.invoke(1, 1, CvType.CV_64FC1) as? org.opencv.core.MatOfDouble
            CvRuntime.isNativeAvailable -> org.opencv.core.MatOfDouble()
            else -> null
        }
    }

    // Pre-allocated Mats for real-time quality analysis (Sharpness, Glare, Exposure)
    val laplacianMat: Mat? = createMat(analysisHeight, analysisWidth, CvType.CV_32F)
    val glareMaskMat: Mat? = createMat(analysisHeight, analysisWidth, CvType.CV_8UC1)
    val meanMat: org.opencv.core.MatOfDouble? = createMatOfDouble()
    val stddevMat: org.opencv.core.MatOfDouble? = createMatOfDouble()

    @Volatile
    var isReleased = false
        private set

    fun matches(w: Int, h: Int, rot: Int): Boolean =
        !isReleased && width == w && height == h && rotationDegrees == rot

    /**
     * Extracts Y plane from [mediaImage] into [yPlaneBytes] and uploads into [rawMat].
     * Handles non-contiguous rowStride cleanly without heap allocations.
     * Rotates upright into [uprightMat] and resizes into [analysisMat].
     *
     * @return [analysisMat] ready for the OpenCV detection pipeline.
     */
    fun extractFrame(mediaImage: android.media.Image): Mat? {
        if (isReleased || rawMat == null) return null
        val plane = mediaImage.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride

        try {
            if (rowStride == width) {
                buffer.position(0)
                buffer.get(yPlaneBytes, 0, width * height)
            } else {
                var offset = 0
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(yPlaneBytes, offset, width)
                    offset += width
                }
            }
        } catch (_: Throwable) {
            return null
        }

        rawMat.put(0, 0, yPlaneBytes)

        // Rotate upright
        if (rotationDegrees != 0 && uprightMat != null && uprightMat !== rawMat) {
            when (rotationDegrees) {
                90 -> Core.rotate(rawMat, uprightMat, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(rawMat, uprightMat, Core.ROTATE_180)
                270 -> Core.rotate(rawMat, uprightMat, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> rawMat.copyTo(uprightMat)
            }
        }

        // Downscale to analysis resolution if needed
        val targetUpright = uprightMat ?: rawMat
        if (analysisMat != null && analysisMat !== targetUpright) {
            Imgproc.resize(
                targetUpright,
                analysisMat,
                Size(analysisWidth.toDouble(), analysisHeight.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA
            )
        }

        return analysisMat ?: targetUpright
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        if (CvRuntime.isNativeAvailable || matFactory != null) {
            try {
                if (analysisMat !== uprightMat && analysisMat !== rawMat) analysisMat?.release()
                if (uprightMat !== rawMat) uprightMat?.release()
                rawMat?.release()
                blurredMat?.release()
                edgesMat?.release()
                laplacianMat?.release()
                glareMaskMat?.release()
                meanMat?.release()
                stddevMat?.release()
            } catch (_: Throwable) {}
        }
    }
}
