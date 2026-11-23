package com.veilframe.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import com.veilframe.app.privacy.PiiDetection
import com.veilframe.app.privacy.PiiMultiModalDetector
import com.veilframe.app.privacy.PiiNcnnEngine
import com.veilframe.app.privacy.PiiScanSummary
import java.io.File

/**
 * Source-pixel PII coverage, not preview-scale inference. Bounded overlapping
 * region decodes keep memory independent from a 48MP/108MP source's dimensions.
 * A failed tile is not silently treated as "no PII".
 */
object ImagePiiRegionScanner {
    const val TILE_EDGE = 1280
    const val OVERLAP = 160
    const val MAX_TILES = 256
    const val MAX_DETECTIONS = 8000

    data class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val rect: Rect get() = Rect(left, top, right, bottom)
    }

    fun planTiles(width: Int, height: Int): List<Tile> {
        require(width > 0 && height > 0) { "Invalid image dimensions for PII coverage" }
        val step = TILE_EDGE - OVERLAP
        fun starts(length: Int): List<Int> {
            val last = (length - TILE_EDGE).coerceAtLeast(0)
            val result = ArrayList<Int>()
            var start = 0
            while (true) {
                result += start
                if (start == last) return result
                require(result.size <= MAX_TILES) { "Image exceeds bounded PII region budget" }
                start = (start + step).coerceAtMost(last)
            }
        }
        val columns = starts(width)
        val rows = starts(height)
        require(columns.size.toLong() * rows.size <= MAX_TILES) {
            "Image requires more than $MAX_TILES PII regions; no incomplete scan may succeed"
        }
        return rows.flatMap { y -> columns.map { x ->
            Tile(x, y, minOf(x + TILE_EDGE, width), minOf(y + TILE_EDGE, height))
        } }
    }

    fun toSource(detection: PiiDetection, tile: Tile, fullWidth: Int, fullHeight: Int): PiiDetection {
        val local = detection.rect
        require(local.right > local.left && local.bottom > local.top) { "Invalid local PII box" }
        return detection.copy(rect = RectF(
            (tile.left + local.left).coerceIn(0f, fullWidth.toFloat()),
            (tile.top + local.top).coerceIn(0f, fullHeight.toFloat()),
            (tile.left + local.right).coerceIn(0f, fullWidth.toFloat()),
            (tile.top + local.bottom).coerceIn(0f, fullHeight.toFloat())
        ))
    }

    fun scan(
        context: Context,
        file: File,
        expectedWidth: Int,
        expectedHeight: Int,
        ncnnEngine: PiiNcnnEngine?,
        confThreshold: Float,
        checkActive: () -> Unit = {}
    ): PiiScanSummary {
        require(file.isFile && file.length() > 0L) { "PII source file unavailable" }
        val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
            ?: throw IllegalStateException("PII source cannot be region-decoded without downsampling")
        try {
            check(decoder.width == expectedWidth && decoder.height == expectedHeight) {
                "PII source dimensions changed since import; refusing stale detections"
            }
            val tiles = planTiles(decoder.width, decoder.height)
            val found = mutableListOf<PiiDetection>()
            val successful = mutableSetOf<String>()
            val failed = mutableMapOf<String, String>()
            for ((index, tile) in tiles.withIndex()) {
                checkActive()
                val bitmap = decoder.decodeRegion(tile.rect, BitmapFactory.Options().apply {
                    inSampleSize = 1
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }) ?: throw IllegalStateException("PII region ${index + 1}/${tiles.size} cannot be decoded")
                try {
                    check(bitmap.width == tile.right - tile.left && bitmap.height == tile.bottom - tile.top) {
                        "PII region decoded at reduced resolution; refusing false-clean result"
                    }
                    val summary = PiiMultiModalDetector.detectAllSummary(
                        context = context,
                        ncnnEngine = ncnnEngine,
                        bitmap = bitmap,
                        targetWidth = bitmap.width,
                        targetHeight = bitmap.height,
                        confThreshold = confThreshold,
                        includeNcnn = true,
                        includeBarcodes = true,
                        includeOcrText = true
                    )
                    successful += summary.successfulEngines
                    summary.failedEngines.forEach { (key, reason) ->
                        failed["$key (tile ${index + 1})"] = reason
                    }
                    found += summary.detections.map { toSource(it, tile, decoder.width, decoder.height) }
                    check(found.size <= MAX_DETECTIONS) {
                        "PII detection count exceeds bounded review capacity"
                    }
                } finally {
                    bitmap.recycle()
                }
                checkActive()
            }
            return PiiScanSummary(
                detections = PiiNcnnEngine.applyNMS(found),
                successfulEngines = successful,
                failedEngines = failed
            )
        } finally {
            decoder.recycle()
        }
    }
}
