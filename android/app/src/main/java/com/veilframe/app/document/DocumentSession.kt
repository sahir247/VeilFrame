package com.veilframe.app.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.veilframe.app.cv.document.DocumentScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Single scanned page inside a multi-page document scanning session with disk persistence.
 */
data class ScannedPage(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri? = null,
    var originalImagePath: String = "",
    var processedImagePath: String? = null,
    var thumbnailPath: String? = null,
    var rotationDegrees: Int = 0,
    var mode: DocumentScanner.DocumentMode = DocumentScanner.DocumentMode.ENHANCED,
    var corners: List<org.opencv.core.Point>? = null,
    var exposureStops: Double = 0.0,
    var contrast: Double = 1.0,
    var saturation: Double = 1.0,
    var adjustSharpen: Double = 0.0,
    var adjustDenoise: Double = 0.0,
    var warmth: Double = 0.0,
    var filterPreset: String = "None",
    var watermarkText: String = "",
    var watermarkPosition: String = "bottom-right",
    var watermarkSize: Int = 28,
    var watermarkColor: String = "#80FFFFFF",
    var watermarkOpacity: Float = 0.6f,
    var watermarkFont: String = "Sans-Serif",
    // Revision counter to discard stale async render jobs
    var editVersion: Long = 0L,
    // Transient in-memory cached bitmaps for smooth rendering
    @Transient var originalBitmapCache: Bitmap? = null,
    @Transient var processedBitmapCache: Bitmap? = null,
    @Transient var thumbnailBitmapCache: Bitmap? = null
) {
    var appliedQuad: List<org.opencv.core.Point>?
        get() = corners
        set(value) { corners = value }

    var appliedFilter: String
        get() = mode.name
        set(value) {
            mode = try {
                DocumentScanner.DocumentMode.valueOf(value)
            } catch (_: Throwable) {
                DocumentScanner.DocumentMode.ENHANCED
            }
        }

    fun getDisplayBitmap(context: Context): Bitmap? {
        if (processedBitmapCache != null && !processedBitmapCache!!.isRecycled) {
            return processedBitmapCache
        }
        if (processedImagePath != null && File(processedImagePath!!).exists()) {
            processedBitmapCache = BitmapFactory.decodeFile(processedImagePath)
            return processedBitmapCache
        }
        val rendered = render(context)
        if (rendered != null) {
            return rendered
        }
        return getOriginalBitmap(context)
    }

    fun getOriginalBitmap(context: Context): Bitmap? {
        if (originalBitmapCache != null && !originalBitmapCache!!.isRecycled) {
            return originalBitmapCache
        }
        if (originalImagePath.isNotEmpty() && File(originalImagePath).exists()) {
            originalBitmapCache = BitmapFactory.decodeFile(originalImagePath)
            return originalBitmapCache
        }
        return null
    }

    /**
     * Deterministic, non-destructive render pipeline.
     * Starts from the immutable original image and executes:
     * 1. Perspective warp crop (if valid 4 corners exist in original coordinates)
     * 2. Output rotation
     * 3. Document enhancement mode (ENHANCED, BLACK_AND_WHITE, GRAYSCALE, etc.)
     * 4. Fine adjustments (exposure, contrast, saturation, sharpen, denoise)
     */
    fun updateCaches(resultBmp: Bitmap) {
        processedBitmapCache = resultBmp
        val maxThumbDim = 200
        val scale = (maxThumbDim.toFloat() / resultBmp.width.coerceAtLeast(resultBmp.height)).coerceAtMost(1f)
        val thumbW = (resultBmp.width * scale).toInt().coerceAtLeast(1)
        val thumbH = (resultBmp.height * scale).toInt().coerceAtLeast(1)
        thumbnailBitmapCache = Bitmap.createScaledBitmap(resultBmp, thumbW, thumbH, true)
    }

    fun render(
        context: Context,
        cvContext: com.veilframe.app.cv.core.CvContext? = null,
        updateCaches: Boolean = true
    ): Bitmap? {
        val orig = getOriginalBitmap(context) ?: return null
        var currentMat = com.veilframe.app.cv.core.BitmapBridge.toMat(orig)
        try {
            cvContext?.ensureActive()

            // 1. Perspective Warp Crop (if 4 corners specified in original image coordinates)
            val quad = corners
            if (quad != null && quad.size == 4) {
                try {
                    val warped = com.veilframe.app.cv.geometry.PerspectiveCorrector.correct(currentMat, quad)
                    currentMat.release()
                    currentMat = warped
                } catch (_: Throwable) {
                    // Fall back to unwarped image
                }
            }
            cvContext?.ensureActive()

            // 2. Rotation
            val rotAngle = (rotationDegrees % 360 + 360) % 360
            if (rotAngle != 0) {
                val rotCode = when (rotAngle) {
                    90 -> org.opencv.core.Core.ROTATE_90_CLOCKWISE
                    180 -> org.opencv.core.Core.ROTATE_180
                    270 -> org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE
                    else -> null
                }
                if (rotCode != null) {
                    val rotMat = org.opencv.core.Mat()
                    org.opencv.core.Core.rotate(currentMat, rotMat, rotCode)
                    currentMat.release()
                    currentMat = rotMat
                }
            }
            cvContext?.ensureActive()

            // 3. Document enhancement mode
            if (mode != DocumentScanner.DocumentMode.ORIGINAL) {
                val enhanced = DocumentScanner.process(currentMat, mode, cvContext)
                currentMat.release()
                currentMat = enhanced
            }
            cvContext?.ensureActive()

            // 4. Color & Detail Adjustments
            val colorEngine = com.veilframe.app.cv.color.ColorEngine
            if (exposureStops != 0.0) {
                val exposed = colorEngine.exposure(currentMat, exposureStops)
                currentMat.release()
                currentMat = exposed
            }
            if (contrast != 1.0) {
                val contrasted = colorEngine.contrast(currentMat, contrast)
                currentMat.release()
                currentMat = contrasted
            }
            if (saturation != 1.0) {
                val saturated = colorEngine.saturation(currentMat, saturation)
                currentMat.release()
                currentMat = saturated
            }
            if (adjustSharpen > 0.0) {
                val sharpened = com.veilframe.app.cv.sharpen.SmartSharpener.sharpen(
                    currentMat,
                    com.veilframe.app.cv.sharpen.SmartSharpener.Params(amount = adjustSharpen, radius = 1.0)
                )
                currentMat.release()
                currentMat = sharpened
            }
            if (adjustDenoise > 0.0) {
                val strength = maxOf(1, adjustDenoise.toInt())
                val denoised = com.veilframe.app.cv.preprocess.Preprocessor.denoise(
                    currentMat,
                    strength = strength,
                    method = com.veilframe.app.cv.preprocess.DenoiseMethod.BILATERAL
                )
                currentMat.release()
                currentMat = denoised
            }
            cvContext?.ensureActive()

            var resultBmp = com.veilframe.app.cv.core.BitmapBridge.toBitmap(currentMat)

            // 5. Warmth / Temperature Adjustment
            if (warmth != 0.0) {
                try {
                    val wBmp = Bitmap.createBitmap(resultBmp.width, resultBmp.height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(wBmp)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    val rScale = (1.0 + warmth * 0.2).toFloat().coerceIn(0.5f, 1.5f)
                    val bScale = (1.0 - warmth * 0.2).toFloat().coerceIn(0.5f, 1.5f)
                    val cm = android.graphics.ColorMatrix(floatArrayOf(
                        rScale, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, bScale, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                    paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                    canvas.drawBitmap(resultBmp, 0f, 0f, paint)
                    resultBmp = wBmp
                } catch (_: Exception) {}
            }

            // 6. Color Filter Presets (Sepia, Vintage, Cool, Warm)
            if (filterPreset != "None" && filterPreset != "Default") {
                try {
                    val fBmp = Bitmap.createBitmap(resultBmp.width, resultBmp.height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(fBmp)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    val cm = android.graphics.ColorMatrix()
                    when (filterPreset) {
                        "Sepia" -> {
                            cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                                0.393f, 0.769f, 0.189f, 0f, 0f,
                                0.349f, 0.686f, 0.168f, 0f, 0f,
                                0.272f, 0.534f, 0.131f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )))
                        }
                        "Cool" -> {
                            cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                                0.8f, 0f, 0f, 0f, 0f,
                                0f, 0.9f, 0f, 0f, 0f,
                                0f, 0f, 1.2f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )))
                        }
                        "Warm" -> {
                            cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                                1.2f, 0f, 0f, 0f, 0f,
                                0f, 1.0f, 0f, 0f, 0f,
                                0f, 0f, 0.8f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )))
                        }
                        "Vintage" -> {
                            cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                                0.9f, 0f, 0f, 0f, 10f,
                                0f, 0.8f, 0f, 0f, 10f,
                                0f, 0.6f, 0f, 0f, 20f,
                                0f, 0f, 0f, 1f, 0f
                            )))
                        }
                    }
                    paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                    canvas.drawBitmap(resultBmp, 0f, 0f, paint)
                    resultBmp = fBmp
                } catch (_: Exception) {}
            }

            // 7. Text Watermark / Document Stamp Overlay
            if (watermarkText.isNotBlank()) {
                val state = com.veilframe.app.media.ImageEditState(
                    watermarkText = watermarkText,
                    watermarkPosition = watermarkPosition,
                    watermarkSize = watermarkSize,
                    watermarkColor = watermarkColor,
                    watermarkOpacity = watermarkOpacity,
                    watermarkFont = watermarkFont
                )
                resultBmp = com.veilframe.app.media.watermark.WatermarkEngine.applyWatermark(resultBmp, state)
            }

            if (updateCaches) {
                updateCaches(resultBmp)
            }

            return resultBmp
        } finally {
            currentMat.release()
        }
    }

    fun saveBitmapsToDisk(context: Context, sessionDir: File) {
        sessionDir.mkdirs()
        // Save original bitmap if present and not on disk
        val origFile = File(sessionDir, "page_${id}_orig.jpg")
        if (originalBitmapCache != null && !originalBitmapCache!!.isRecycled) {
            if (!origFile.exists() || originalImagePath.isEmpty()) {
                FileOutputStream(origFile).use { fos ->
                    originalBitmapCache!!.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                }
                originalImagePath = origFile.absolutePath
            }
        }

        // Save processed bitmap if present
        if (processedBitmapCache != null && !processedBitmapCache!!.isRecycled) {
            val procFile = File(sessionDir, "page_${id}_proc.png")
            FileOutputStream(procFile).use { fos ->
                processedBitmapCache!!.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            processedImagePath = procFile.absolutePath

            // Save thumbnail
            val thumbFile = File(sessionDir, "page_${id}_thumb.jpg")
            val thumbBmp = thumbnailBitmapCache ?: run {
                val maxThumbDim = 200
                val scale = (maxThumbDim.toFloat() / processedBitmapCache!!.width.coerceAtLeast(processedBitmapCache!!.height)).coerceAtMost(1f)
                val thumbW = (processedBitmapCache!!.width * scale).toInt().coerceAtLeast(1)
                val thumbH = (processedBitmapCache!!.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(processedBitmapCache!!, thumbW, thumbH, true)
            }
            FileOutputStream(thumbFile).use { fos ->
                thumbBmp.compress(Bitmap.CompressFormat.JPEG, 80, fos)
            }
            thumbnailPath = thumbFile.absolutePath
        }
    }
}

/**
 * Summary metadata for saved sessions displayed in Library.
 */
data class SavedSessionMeta(
    val sessionId: String,
    val title: String,
    val pageCount: Int,
    val lastModifiedAt: Long,
    val thumbnailPath: String?
)

/**
 * Persistent document session manager with process-death survival:
 * - Multi-page document management (reorder, duplicate, rotate, delete, insert)
 * - Serialization to private app storage: filesDir/document_sessions/{sessionId}/session.json
 * - Session restoration for Library hub integration
 */
class DocumentSession(
    var id: String = UUID.randomUUID().toString(),
    var title: String = "Scanned Document"
) {
    var sessionId: String
        get() = id
        set(value) { id = value }

    internal val _pages = mutableListOf<ScannedPage>()
    val pages: List<ScannedPage> get() = _pages.toList()

    var createdAt: Long = System.currentTimeMillis()
    var lastModifiedAt: Long = System.currentTimeMillis()

    var activePageIndex: Int = 0

    fun replaceAllPages(newPages: List<ScannedPage>) {
        _pages.clear()
        _pages.addAll(newPages)
        activePageIndex = 0
        lastModifiedAt = System.currentTimeMillis()
    }

    fun addPage(page: ScannedPage) = synchronized(_pages) {
        _pages.add(page)
        activePageIndex = _pages.size - 1
        lastModifiedAt = System.currentTimeMillis()
    }

    fun addPages(newPages: List<ScannedPage>) = synchronized(_pages) {
        if (newPages.isNotEmpty()) {
            _pages.addAll(newPages)
            activePageIndex = _pages.size - 1
            lastModifiedAt = System.currentTimeMillis()
        }
    }

    fun insertPage(index: Int, page: ScannedPage) = synchronized(_pages) {
        val target = index.coerceIn(0, _pages.size)
        _pages.add(target, page)
        activePageIndex = target
        lastModifiedAt = System.currentTimeMillis()
    }

    fun removePage(index: Int) = synchronized(_pages) {
        if (index in _pages.indices) {
            _pages.removeAt(index)
            if (activePageIndex >= _pages.size) {
                activePageIndex = (_pages.size - 1).coerceAtLeast(0)
            }
            lastModifiedAt = System.currentTimeMillis()
        }
    }

    fun movePage(fromIndex: Int, toIndex: Int) = synchronized(_pages) {
        if (fromIndex in _pages.indices && toIndex in _pages.indices && fromIndex != toIndex) {
            val item = _pages.removeAt(fromIndex)
            _pages.add(toIndex, item)
            activePageIndex = toIndex
            lastModifiedAt = System.currentTimeMillis()
        }
    }

    fun duplicatePage(index: Int, context: Context): ScannedPage? {
        val src = synchronized(_pages) { _pages.getOrNull(index) } ?: return null
        val origBmp = src.getOriginalBitmap(context) ?: return null
        val copyOrig = origBmp.copy(origBmp.config ?: Bitmap.Config.ARGB_8888, true)
        val copyProc = src.getDisplayBitmap(context)?.let {
            it.copy(it.config ?: Bitmap.Config.ARGB_8888, true)
        }
        val newPage = ScannedPage(
            originalBitmapCache = copyOrig,
            processedBitmapCache = copyProc,
            rotationDegrees = src.rotationDegrees,
            mode = src.mode,
            corners = src.corners?.map { org.opencv.core.Point(it.x, it.y) },
            exposureStops = src.exposureStops,
            contrast = src.contrast,
            saturation = src.saturation,
            adjustSharpen = src.adjustSharpen,
            adjustDenoise = src.adjustDenoise,
            warmth = src.warmth,
            filterPreset = src.filterPreset,
            watermarkText = src.watermarkText,
            watermarkPosition = src.watermarkPosition,
            watermarkSize = src.watermarkSize,
            watermarkColor = src.watermarkColor,
            watermarkOpacity = src.watermarkOpacity,
            watermarkFont = src.watermarkFont
        )
        insertPage(index + 1, newPage)
        return newPage
    }

    fun rotatePage(index: Int, degreesDelta: Int, context: Context) {
        val page = synchronized(_pages) { _pages.getOrNull(index) } ?: return
        page.rotationDegrees = (page.rotationDegrees + degreesDelta) % 360
        if (page.rotationDegrees < 0) page.rotationDegrees += 360
        page.editVersion++
        lastModifiedAt = System.currentTimeMillis()
    }

    fun selectPage(index: Int) {
        synchronized(_pages) {
            if (index in _pages.indices) {
                activePageIndex = index
            }
        }
    }

    val currentPage: ScannedPage?
        get() = synchronized(_pages) { _pages.getOrNull(activePageIndex) }

    val pageCount: Int
        get() = synchronized(_pages) { _pages.size }

    val isEmpty: Boolean
        get() = synchronized(_pages) { _pages.isEmpty() }

    fun clear() = synchronized(_pages) {
        _pages.clear()
        activePageIndex = 0
        lastModifiedAt = System.currentTimeMillis()
    }

    // =========================================================================
    // DISK PERSISTENCE LAYER (Process-Death Survival)
    // =========================================================================

    fun saveToDisk(context: Context) {
        try {
            val sessionDir = getSessionDirectory(context, id)
            sessionDir.mkdirs()

            val pagesSnapshot = synchronized(_pages) { _pages.toList() }

            // 1. Save all page images to session folder
            for (page in pagesSnapshot) {
                page.saveBitmapsToDisk(context, sessionDir)
            }

            // 2. Write session JSON manifest
            val json = JSONObject().apply {
                put("id", id)
                put("title", title)
                put("createdAt", createdAt)
                put("lastModifiedAt", lastModifiedAt)
                put("activePageIndex", activePageIndex)

                val pagesArray = JSONArray()
                for (page in pagesSnapshot) {
                    val pageJson = JSONObject().apply {
                        put("id", page.id)
                        put("originalImagePath", page.originalImagePath)
                        put("processedImagePath", page.processedImagePath ?: "")
                        put("thumbnailPath", page.thumbnailPath ?: "")
                        put("rotationDegrees", page.rotationDegrees)
                        put("mode", page.mode.name)
                        put("exposureStops", page.exposureStops)
                        put("contrast", page.contrast)
                        put("saturation", page.saturation)
                        put("adjustSharpen", page.adjustSharpen)
                        put("adjustDenoise", page.adjustDenoise)
                        put("warmth", page.warmth)
                        put("filterPreset", page.filterPreset)
                        put("watermarkText", page.watermarkText)
                        put("watermarkPosition", page.watermarkPosition)
                        put("watermarkSize", page.watermarkSize)
                        put("watermarkColor", page.watermarkColor)
                        put("watermarkOpacity", page.watermarkOpacity.toDouble())
                        put("watermarkFont", page.watermarkFont)
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
                    pagesArray.put(pageJson)
                }
                put("pages", pagesArray)
            }

            val manifestFile = File(sessionDir, "manifest.json")
            manifestFile.writeText(json.toString())
        } catch (e: Exception) {
            // Log / ignore persistence failure during transient teardown
        }
    }

    companion object {
        fun getSessionsBaseDir(context: Context): File {
            return File(context.filesDir, "document_sessions").apply { mkdirs() }
        }

        fun getSessionDirectory(context: Context, sessionId: String): File {
            return File(getSessionsBaseDir(context), sessionId)
        }

        fun loadFromDisk(context: Context, sessionId: String): DocumentSession? {
            val sessionDir = getSessionDirectory(context, sessionId)
            val manifestFile = File(sessionDir, "manifest.json")
            if (!manifestFile.exists()) return null

            return try {
                val json = JSONObject(manifestFile.readText())
                val session = DocumentSession(
                    id = json.getString("id"),
                    title = json.optString("title", "Scanned Document")
                ).apply {
                    createdAt = json.optLong("createdAt", System.currentTimeMillis())
                    lastModifiedAt = json.optLong("lastModifiedAt", System.currentTimeMillis())
                }

                val pagesArray = json.optJSONArray("pages") ?: JSONArray()
                val loadedPages = mutableListOf<ScannedPage>()
                for (i in 0 until pagesArray.length()) {
                    val p = pagesArray.getJSONObject(i)
                    val modeStr = p.optString("mode", DocumentScanner.DocumentMode.ENHANCED.name)
                    val mode = try {
                        DocumentScanner.DocumentMode.valueOf(modeStr)
                    } catch (e: Exception) {
                        DocumentScanner.DocumentMode.ENHANCED
                    }

                    val cornersList = p.optJSONArray("corners")?.let { arr ->
                        val list = mutableListOf<org.opencv.core.Point>()
                        for (j in 0 until arr.length()) {
                            val ptObj = arr.getJSONObject(j)
                            list.add(org.opencv.core.Point(ptObj.getDouble("x"), ptObj.getDouble("y")))
                        }
                        if (list.size == 4) list else null
                    }

                    val page = ScannedPage(
                        id = p.getString("id"),
                        originalImagePath = p.optString("originalImagePath", ""),
                        processedImagePath = p.optString("processedImagePath", "").takeIf { it.isNotEmpty() },
                        thumbnailPath = p.optString("thumbnailPath", "").takeIf { it.isNotEmpty() },
                        rotationDegrees = p.optInt("rotationDegrees", 0),
                        mode = mode,
                        corners = cornersList,
                        exposureStops = p.optDouble("exposureStops", 0.0),
                        contrast = p.optDouble("contrast", 1.0),
                        saturation = p.optDouble("saturation", 1.0),
                        adjustSharpen = p.optDouble("adjustSharpen", 0.0),
                        adjustDenoise = p.optDouble("adjustDenoise", 0.0),
                        warmth = p.optDouble("warmth", 0.0),
                        filterPreset = p.optString("filterPreset", "None"),
                        watermarkText = p.optString("watermarkText", ""),
                        watermarkPosition = p.optString("watermarkPosition", "bottom-right"),
                        watermarkSize = p.optInt("watermarkSize", 28),
                        watermarkColor = p.optString("watermarkColor", "#80FFFFFF"),
                        watermarkOpacity = run {
                            val raw = p.optDouble("watermarkOpacity", 0.6).toFloat()
                            if (raw > 1.0f) (raw / 100f).coerceIn(0.05f, 1.0f) else raw.coerceIn(0.05f, 1.0f)
                        },
                        watermarkFont = p.optString("watermarkFont", "Sans-Serif")
                    )
                    loadedPages.add(page)
                }
                session._pages.addAll(loadedPages)
                session.activePageIndex = json.optInt("activePageIndex", 0).coerceIn(0, (loadedPages.size - 1).coerceAtLeast(0))
                session
            } catch (e: Exception) {
                null
            }
        }

        fun listSavedSessions(context: Context): List<SavedSessionMeta> {
            val baseDir = getSessionsBaseDir(context)
            val list = mutableListOf<SavedSessionMeta>()
            val dirs = baseDir.listFiles() ?: return emptyList()

            for (dir in dirs) {
                if (dir.isDirectory) {
                    val manifest = File(dir, "manifest.json")
                    if (manifest.exists()) {
                        try {
                            val json = JSONObject(manifest.readText())
                            val id = json.getString("id")
                            val title = json.optString("title", "Scanned Document")
                            val lastMod = json.optLong("lastModifiedAt", dir.lastModified())
                            val pagesArr = json.optJSONArray("pages")
                            val count = pagesArr?.length() ?: 0
                            val firstThumb = if (count > 0) {
                                val firstPage = pagesArr!!.getJSONObject(0)
                                firstPage.optString("thumbnailPath", "").takeIf { it.isNotEmpty() && File(it).exists() }
                            } else null

                            list.add(
                                SavedSessionMeta(
                                    sessionId = id,
                                    title = title,
                                    pageCount = count,
                                    lastModifiedAt = lastMod,
                                    thumbnailPath = firstThumb
                                )
                            )
                        } catch (_: Exception) {}
                    }
                }
            }
            return list.sortedByDescending { it.lastModifiedAt }
        }

        fun deleteSession(context: Context, sessionId: String) {
            try {
                val dir = getSessionDirectory(context, sessionId)
                dir.deleteRecursively()
            } catch (_: Exception) {}
        }
    }
}
