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

    fun saveBitmapsToDisk(context: Context, sessionDir: File) {
        sessionDir.mkdirs()
        // Save original bitmap if present
        if (originalBitmapCache != null) {
            val origFile = File(sessionDir, "page_${id}_orig.jpg")
            FileOutputStream(origFile).use { fos ->
                originalBitmapCache!!.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            originalImagePath = origFile.absolutePath

            // Generate miniature thumbnail (max 200px)
            val thumbFile = File(sessionDir, "page_${id}_thumb.jpg")
            val maxThumbDim = 200
            val scale = (maxThumbDim.toFloat() / originalBitmapCache!!.width.coerceAtLeast(originalBitmapCache!!.height)).coerceAtMost(1f)
            val thumbW = (originalBitmapCache!!.width * scale).toInt().coerceAtLeast(1)
            val thumbH = (originalBitmapCache!!.height * scale).toInt().coerceAtLeast(1)
            val thumbBmp = Bitmap.createScaledBitmap(originalBitmapCache!!, thumbW, thumbH, true)
            FileOutputStream(thumbFile).use { fos ->
                thumbBmp.compress(Bitmap.CompressFormat.JPEG, 80, fos)
            }
            thumbBmp.recycle()
            thumbnailPath = thumbFile.absolutePath
        }

        // Save processed bitmap if present
        if (processedBitmapCache != null) {
            val procFile = File(sessionDir, "page_${id}_proc.png")
            FileOutputStream(procFile).use { fos ->
                processedBitmapCache!!.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            processedImagePath = procFile.absolutePath
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

    fun addPage(page: ScannedPage) {
        _pages.add(page)
        activePageIndex = _pages.size - 1
        lastModifiedAt = System.currentTimeMillis()
    }

    fun addPages(newPages: List<ScannedPage>) {
        if (newPages.isNotEmpty()) {
            _pages.addAll(newPages)
            activePageIndex = _pages.size - 1
            lastModifiedAt = System.currentTimeMillis()
        }
    }

    fun insertPage(index: Int, page: ScannedPage) {
        val target = index.coerceIn(0, _pages.size)
        _pages.add(target, page)
        activePageIndex = target
        lastModifiedAt = System.currentTimeMillis()
    }

    fun removePage(index: Int) {
        if (index in _pages.indices) {
            _pages.removeAt(index)
            if (activePageIndex >= _pages.size) {
                activePageIndex = (_pages.size - 1).coerceAtLeast(0)
            }
            lastModifiedAt = System.currentTimeMillis()
        }
    }

    fun movePage(fromIndex: Int, toIndex: Int) {
        if (fromIndex in _pages.indices && toIndex in _pages.indices && fromIndex != toIndex) {
            val item = _pages.removeAt(fromIndex)
            _pages.add(toIndex, item)
            activePageIndex = toIndex
            lastModifiedAt = System.currentTimeMillis()
        }
    }

    fun duplicatePage(index: Int, context: Context): ScannedPage? {
        val src = _pages.getOrNull(index) ?: return null
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
            corners = src.corners
        )
        insertPage(index + 1, newPage)
        return newPage
    }

    fun rotatePage(index: Int, degreesDelta: Int, context: Context) {
        val page = _pages.getOrNull(index) ?: return
        page.rotationDegrees = (page.rotationDegrees + degreesDelta) % 360
        if (page.rotationDegrees < 0) page.rotationDegrees += 360

        // Rotate in-memory bitmaps
        val matrix = Matrix().apply { postRotate(degreesDelta.toFloat()) }
        page.getOriginalBitmap(context)?.let { bmp ->
            page.originalBitmapCache = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }
        page.processedBitmapCache?.let { bmp ->
            page.processedBitmapCache = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }
        lastModifiedAt = System.currentTimeMillis()
    }

    fun selectPage(index: Int) {
        if (index in _pages.indices) {
            activePageIndex = index
        }
    }

    val currentPage: ScannedPage?
        get() = _pages.getOrNull(activePageIndex)

    val pageCount: Int
        get() = _pages.size

    val isEmpty: Boolean
        get() = _pages.isEmpty()

    fun clear() {
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

            // 1. Save all page images to session folder
            for (page in _pages) {
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
                for (page in _pages) {
                    val pageJson = JSONObject().apply {
                        put("id", page.id)
                        put("originalImagePath", page.originalImagePath)
                        put("processedImagePath", page.processedImagePath ?: "")
                        put("thumbnailPath", page.thumbnailPath ?: "")
                        put("rotationDegrees", page.rotationDegrees)
                        put("mode", page.mode.name)
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

                    val page = ScannedPage(
                        id = p.getString("id"),
                        originalImagePath = p.optString("originalImagePath", ""),
                        processedImagePath = p.optString("processedImagePath", "").takeIf { it.isNotEmpty() },
                        thumbnailPath = p.optString("thumbnailPath", "").takeIf { it.isNotEmpty() },
                        rotationDegrees = p.optInt("rotationDegrees", 0),
                        mode = mode
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
