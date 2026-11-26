package com.veilframe.app.pdf.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a signature with drawing data.
 */
data class SignatureData(
    val paths: List<SignaturePath>,
    val strokeWidth: Float = 3f,
    val strokeColor: Int = Color.BLACK
)

/**
 * Represents a path in the signature (continuous stroke).
 */
data class SignaturePath(
    val points: List<SignaturePoint>
)

/**
 * Represents a point in the signature path.
 */
data class SignaturePoint(
    val x: Float,
    val y: Float
)

/**
 * Signature placement configuration.
 */
data class SignaturePlacement(
    val pageIndex: Int, // 0-indexed
    val x: Float, // X position on page (PDF coordinates, origin at bottom-left)
    val y: Float, // Y position on page
    val width: Float = 200f,
    val height: Float = 100f,
    val rotationDegrees: Float = 0f
)

/**
 * Individual signature item placed on canvas with independent bitmap and rotation.
 */
data class SignatureItemPlacement(
    val pageIndex: Int,
    val x: Float, // PDF points from left of media/crop box
    val y: Float, // PDF points from top of media/crop box (top-down screen convention)
    val width: Float,
    val height: Float,
    val rotationDegrees: Float = 0f,
    val bitmap: Bitmap,
    val previewWidth: Float? = null,
    val previewHeight: Float? = null,
    val sourceSha256: String? = null
)

/**
 * Additional elements to add with signature.
 */
data class SignatureExtras(
    val addDate: Boolean = false,
    val dateFormat: String = "yyyy-MM-dd",
    val addName: Boolean = false,
    val name: String = "",
    val addInitials: Boolean = false,
    val initials: String = ""
)

/**
 * Saved signature for reuse.
 */
data class SavedSignature(
    val id: String,
    val name: String,
    val bitmapPath: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Result of signature operation.
 */
data class SignatureResult(
    val success: Boolean,
    val errorMessage: String? = null
)

/**
 * PDF Signer - Adds digital signatures (visual representation) to PDF documents.
 * This creates a visual signature image on the PDF, not a cryptographic signature.
 * Uses Apache PDFBox-Android (Apache 2.0 License).
 */
class PdfSigner(private val context: Context) {
    
    init {
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        } catch (ignored: Throwable) {}
    }

    private val signaturesDir: File by lazy {
        File(context.filesDir, "signatures").also { it.mkdirs() }
    }
    
    /**
     * Add a signature to a PDF document.
     *
     * @param inputUri Source PDF file URI
     * @param outputUri Destination PDF file URI  
     * @param signatureData Signature drawing data
     * @param placement Where to place the signature
     * @param extras Optional date, name, initials
     * @param progressCallback Progress callback (0-100)
     * @return SignatureResult with operation status
     */
    suspend fun addSignature(
        inputUri: Uri,
        outputUri: Uri,
        signatureData: SignatureData,
        placement: SignaturePlacement,
        extras: SignatureExtras = SignatureExtras(),
        progressCallback: (Int) -> Unit = {}
    ): SignatureResult = withContext(Dispatchers.IO) {
        try {
            progressCallback(0)
            val bitmap = createSignatureBitmap(signatureData,placement.width.toInt(),placement.height.toInt())
                ?: return@withContext SignatureResult(false,"Cannot create signature bitmap")
            try {
                progressCallback(20)
                renderAbsoluteSignatures(inputUri,bitmap,listOf(placement),extras) {
                    context.contentResolver.openOutputStream(outputUri,"wt")
                        ?: throw IOException("Cannot open signature destination")
                }.also { if (it.success) progressCallback(100) }
            } finally { bitmap.recycle() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SignatureResult(false,failure.message ?: "Failed to sign PDF")
        }
    }

    /** Add a saved visual signature without resampling the source image. */
    suspend fun addSignatureFromSaved(
        inputUri: Uri,
        outputUri: Uri,
        savedSignature: SavedSignature,
        placement: SignaturePlacement,
        extras: SignatureExtras = SignatureExtras(),
        progressCallback: (Int) -> Unit = {}
    ): SignatureResult = withContext(Dispatchers.IO) {
        try {
            progressCallback(0)
            val signatureFile = File(savedSignature.bitmapPath)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(signatureFile.absolutePath,bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong()*bounds.outHeight <= 4_000_000) { "Invalid or oversized saved signature" }
            val bitmap = android.graphics.BitmapFactory.decodeFile(signatureFile.absolutePath)
                ?: return@withContext SignatureResult(false,"Cannot load saved signature")
            try {
                progressCallback(20)
                renderAbsoluteSignatures(inputUri,bitmap,listOf(placement),extras) {
                    context.contentResolver.openOutputStream(outputUri,"wt")
                        ?: throw IOException("Cannot open signature destination")
                }.also { if (it.success) progressCallback(100) }
            } finally { bitmap.recycle() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SignatureResult(false,failure.message ?: "Failed to sign PDF")
        }
    }

    private val indexFile: File by lazy { File(signaturesDir, "signatures_index.json") }

    private companion object {
        val libraryLock = Any()
        const val MAX_SIGNATURE_PIXELS = 4_000_000L
        const val MAX_INDEX_BYTES = 2_097_152L
        const val MAX_LIBRARY_ENTRIES = 2048
    }

    private fun readIndexMap(): MutableMap<String, String> {
        if (!indexFile.exists()) return linkedMapOf()
        require(indexFile.length() in 1..MAX_INDEX_BYTES) { "Invalid signature library index size" }
        val array = org.json.JSONArray(indexFile.readText())
        require(array.length() <= MAX_LIBRARY_ENTRIES) { "Signature library index too large" }
        val map = linkedMapOf<String, String>()
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val id = entry.getString("id")
            require(SignatureLibraryFiles.validId(id) && !map.containsKey(id)) { "Invalid signature library entry" }
            map[id] = entry.getString("name")
        }
        return map
    }

    private fun writeIndexMap(map: Map<String, String>) {
        require(map.size <= MAX_LIBRARY_ENTRIES) { "Signature library is full" }
        val array = org.json.JSONArray()
        for ((id, name) in map) {
            array.put(org.json.JSONObject().put("id", id).put("name", name))
        }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_INDEX_BYTES) { "Signature library index too large" }
        SignatureLibraryFiles.atomicWrite(indexFile) { it.write(bytes) }
    }

    /** Save both the image and its index entry, or report failure. */
    suspend fun saveSignatureBitmap(bitmap: Bitmap, name: String): SavedSignature = withContext(Dispatchers.IO) {
        require(!bitmap.isRecycled && bitmap.width.toLong() * bitmap.height <= MAX_SIGNATURE_PIXELS) {
            "Invalid or oversized signature"
        }
        synchronized(libraryLock) {
            val index = readIndexMap()
            require(index.size < MAX_LIBRARY_ENTRIES) { "Signature library is full" }
            val id = java.util.UUID.randomUUID().toString()
            val file = File(signaturesDir, "signature_$id.png")
            val cleanName = name.trim().ifEmpty { "Signature ${index.size + 1}" }
            require(cleanName.length <= 256) { "Signature name is too long" }
            SignatureLibraryFiles.atomicWrite(file) { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Signature encoding failed")
            }
            try {
                index[id] = cleanName
                writeIndexMap(index)
            } catch (failure: Exception) {
                file.delete()
                throw failure
            }
            SavedSignature(id, cleanName, file.absolutePath, file.lastModified())
        }
    }

    suspend fun saveSignature(signatureData: SignatureData, name: String): SavedSignature = withContext(Dispatchers.IO) {
        val bitmap = createSignatureBitmap(signatureData, 400, 200)
            ?: throw IOException("Cannot create signature bitmap")
        try { saveSignatureBitmap(bitmap, name) } finally { bitmap.recycle() }
    }

    fun getSavedSignatures(): List<SavedSignature> = synchronized(libraryLock) {
        // Keep existing images visible if an old index is damaged, but never overwrite
        // that damaged index during a mutation.
        val index = try { readIndexMap() } catch (failure: Exception) {
            android.util.Log.w("PdfSigner", "Cannot read signature index", failure)
            emptyMap()
        }
        signaturesDir.listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile || !file.name.startsWith("signature_") || !file.name.endsWith(".png")) return@mapNotNull null
            val id = file.name.removePrefix("signature_").removeSuffix(".png")
            if (!SignatureLibraryFiles.validId(id)) return@mapNotNull null
            SavedSignature(id, index[id] ?: "Signature $id", file.absolutePath, file.lastModified())
        }.sortedByDescending { it.createdAt }
    }

    /** Reject oversized images before decode; list thumbnails have a separate small budget. */
    fun loadSavedSignatureBitmap(path: String, thumbnail: Boolean = false): Bitmap? = synchronized(libraryLock) {
        val file = File(path)
        if (file.parentFile != signaturesDir || !file.isFile) return@synchronized null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_SIGNATURE_PIXELS) return@synchronized null
        val options = android.graphics.BitmapFactory.Options()
        if (thumbnail) {
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 192) {
                options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
            }
        }
        android.graphics.BitmapFactory.decodeFile(path, options)
    }

    fun deleteSignature(signature: SavedSignature): Boolean = deleteSignatureById(signature.id)

    fun deleteSignatureById(id: String): Boolean = synchronized(libraryLock) {
        if (!SignatureLibraryFiles.validId(id)) return@synchronized false
        val file = File(signaturesDir, "signature_$id.png")
        if (!file.isFile) return@synchronized false
        val tombstone = File(signaturesDir, ".deleted-${java.util.UUID.randomUUID()}.png")
        try {
            val index = readIndexMap()
            Files.move(file.toPath(), tombstone.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            try {
                index.remove(id)
                writeIndexMap(index)
            } catch (failure: Exception) {
                Files.move(tombstone.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                throw failure
            }
            if (!tombstone.delete()) throw IOException("Cannot remove signature image")
            true
        } catch (failure: Exception) {
            android.util.Log.w("PdfSigner", "Signature deletion failed", failure)
            false
        }
    }

    fun renameSignature(id: String, newName: String): Boolean = synchronized(libraryLock) {
        if (!SignatureLibraryFiles.validId(id) || newName.trim().length !in 1..256 ||
            !File(signaturesDir, "signature_$id.png").isFile) return@synchronized false
        try {
            val index = readIndexMap()
            index[id] = newName.trim()
            writeIndexMap(index)
            true
        } catch (failure: Exception) {
            android.util.Log.w("PdfSigner", "Signature rename failed", failure)
            false
        }
    }

    /**
     * Create a bitmap from signature drawing data.
     */
    private fun createSignatureBitmap(
        data: SignatureData,
        width: Int,
        height: Int
    ): Bitmap? {
        if (width <= 0 || height <= 0 || width.toLong()*height > 4_000_000 ||
            data.paths.none { it.points.isNotEmpty() } ||
            !data.strokeWidth.isFinite() || data.strokeWidth <= 0f ||
            data.paths.any { path -> path.points.any { !it.x.isFinite() || !it.y.isFinite() } }) return null
        
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        
        // Guard: check bitmap is valid before creating canvas
        val canvas = if (!bitmap.isRecycled) Canvas(bitmap) else return null
        
        // Transparent background
        canvas.drawColor(Color.TRANSPARENT)
        
        val paint = Paint().apply {
            color = data.strokeColor
            strokeWidth = data.strokeWidth
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        
        // Find bounds of the signature
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        
        for (path in data.paths) {
            for (point in path.points) {
                minX = minOf(minX, point.x)
                maxX = maxOf(maxX, point.x)
                minY = minOf(minY, point.y)
                maxY = maxOf(maxY, point.y)
            }
        }
        
        // Scale and center the signature
        val signatureWidth = maxX - minX
        val signatureHeight = maxY - minY
        
        val padding = minOf(10f,width/4f,height/4f)
        val scaleX = if (signatureWidth > 0) (width - 2f*padding) / signatureWidth else Float.POSITIVE_INFINITY
        val scaleY = if (signatureHeight > 0) (height - 2f*padding) / signatureHeight else Float.POSITIVE_INFINITY
        val scale = minOf(scaleX,scaleY).let { if (it.isFinite()) it else 1f }
        
        val offsetX = (width - signatureWidth * scale) / 2 - minX * scale
        val offsetY = (height - signatureHeight * scale) / 2 - minY * scale
        
        // Draw paths
        for (signaturePath in data.paths) {
            if (signaturePath.points.isEmpty()) continue
            
            val path = Path()
            val firstPoint = signaturePath.points.first()
            if (signaturePath.points.size == 1) {
                canvas.drawPoint(firstPoint.x * scale + offsetX,firstPoint.y * scale + offsetY,paint)
                continue
            }
            path.moveTo(
                firstPoint.x * scale + offsetX,
                firstPoint.y * scale + offsetY
            )
            
            for (i in 1 until signaturePath.points.size) {
                val point = signaturePath.points[i]
                path.lineTo(
                    point.x * scale + offsetX,
                    point.y * scale + offsetY
                )
            }
            
            canvas.drawPath(path, paint)
        }
        
        return bitmap
    }
    
    /**
     * Add signature image to a PDF page.
     */
    private fun addSignatureToPage(
        document: PDDocument,
        page: PDPage,
        bitmap: Bitmap,
        placement: SignaturePlacement
    ) {
        drawSignatureImage(document,page,bitmap,SignaturePageGeometry.absolute(
            placement.x,placement.y,placement.width,placement.height,placement.rotationDegrees))
    }

    private fun drawSignatureImage(document: PDDocument, page: PDPage, bitmap: Bitmap, affine: SignatureAffine) {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "Invalid signature bitmap" }
        val image = LosslessFactory.createFromImage(document,bitmap)
        PDPageContentStream(document,page,PDPageContentStream.AppendMode.APPEND,true,true).use { stream ->
            stream.drawImage(image,affine.matrix())
        }
    }

    /**
     * Add signature extras (date, name, initials) below the signature.
     */
    private fun addSignatureExtras(
        document: PDDocument,
        page: PDPage,
        placement: SignaturePlacement,
        extras: SignatureExtras
    ) {
        if (!extras.addDate && (!extras.addName || extras.name.isBlank()) && (!extras.addInitials || extras.initials.isBlank())) {
            return
        }

        val lines = buildList {
            if (extras.addName && extras.name.isNotBlank()) add(extras.name)
            if (extras.addDate) add("Date: ${SimpleDateFormat(extras.dateFormat,Locale.getDefault()).format(Date())}")
            if (extras.addInitials && extras.initials.isNotBlank()) add("Initials: ${extras.initials}")
        }
        val fonts = OfficeVisibleFonts(context,document)
        val selected = lines.map { text -> text to fonts.select(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,text) }
        PDPageContentStream(document,page,PDPageContentStream.AppendMode.APPEND,true,true).use { stream ->
            stream.setNonStrokingColor(0,0,0)
            var y = placement.y - 15f
            for ((text,font) in selected) {
                stream.beginText()
                stream.setFont(font,10f)
                stream.newLineAtOffset(placement.x,y)
                stream.showText(normalizeOfficeText(text))
                stream.endText()
                y -= 12f
            }
        }
    }

    /** The stream belongs to the caller; verified export must not close it. */
    private fun borrowedOutput(output: java.io.OutputStream): java.io.OutputStream =
        object : java.io.OutputStream() {
            override fun write(value: Int) = output.write(value)
            override fun write(bytes: ByteArray,offset: Int,length: Int) = output.write(bytes,offset,length)
            override fun flush() = output.flush()
            override fun close() = flush()
        }

    suspend fun addSignatureBitmap(
        inputUri: Uri,
        outputStream: java.io.OutputStream,
        signatureBitmap: Bitmap,
        placement: SignaturePlacement,
        extras: SignatureExtras = SignatureExtras()
    ): SignatureResult = addSignatureBitmaps(inputUri, outputStream, signatureBitmap, listOf(placement), extras)

    suspend fun addSignatureBitmaps(
        inputUri: Uri,
        outputStream: java.io.OutputStream,
        signatureBitmap: Bitmap,
        placements: List<SignaturePlacement>,
        extras: SignatureExtras = SignatureExtras()
    ): SignatureResult = renderAbsoluteSignatures(inputUri,signatureBitmap,placements,extras) {
        borrowedOutput(outputStream)
    }

    private suspend fun renderAbsoluteSignatures(
        inputUri: Uri,
        bitmap: Bitmap,
        placements: List<SignaturePlacement>,
        extras: SignatureExtras,
        openDestination: () -> java.io.OutputStream
    ): SignatureResult = withContext(Dispatchers.IO) {
        try {
            loadMergePdf(context,inputUri).use { loaded ->
                val document = loaded.document
                requirePdfRewriteAllowed(document)
                val count = document.numberOfPages
                require(placements.isNotEmpty() && placements.all { it.pageIndex in 0 until count }) {
                    "Invalid signature page selection"
                }
                for (placement in placements) {
                    ensureActive()
                    val page = document.getPage(placement.pageIndex)
                    addSignatureToPage(document,page,bitmap,placement)
                    addSignatureExtras(document,page,placement,extras)
                }
                exportVerifiedPdf(context,document,count,openDestination = openDestination)
            }
            SignatureResult(true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SignatureResult(false,failure.message ?: "Failed to sign PDF")
        }
    }

    /**
     * Sign PDF with multiple items placed across pages, each having its own coordinates,
     * rotation, and bitmap (supporting duplicates and multiple saved signatures in one session).
     */
    suspend fun signPdfWithItems(
        inputUri: Uri,
        outputStream: java.io.OutputStream,
        items: List<SignatureItemPlacement>
    ): SignatureResult = renderCanvasSignatures(inputUri,outputStream) { items }

    /** Quick placement is relative to the actual displayed crop box of each selected page. */
    internal suspend fun addRelativeSignatureBitmaps(
        inputUri: Uri,
        outputStream: java.io.OutputStream,
        bitmap: Bitmap,
        pages: List<Int>,
        xFraction: Float,
        yFractionFromBottom: Float,
        scale: Float
    ): SignatureResult = renderCanvasSignatures(inputUri,outputStream) { document ->
        require(!bitmap.isRecycled && pages.isNotEmpty() && pages.all { it in 0 until document.numberOfPages }) {
            "Invalid signature page selection"
        }
        pages.map { index ->
            val geometry = SignaturePageGeometry.from(document.getPage(index))
            val rect = geometry.relativeRect(xFraction,yFractionFromBottom,scale,
                bitmap.width.toFloat()/bitmap.height.toFloat())
            SignatureItemPlacement(index,rect[0],rect[1],rect[2],rect[3],bitmap=bitmap)
        }
    }

    private suspend fun renderCanvasSignatures(
        inputUri: Uri,
        outputStream: java.io.OutputStream,
        placements: (PDDocument) -> List<SignatureItemPlacement>
    ): SignatureResult = withContext(Dispatchers.IO) {
        try {
            loadMergePdf(context,inputUri).use { loaded ->
                val document = loaded.document
                requirePdfRewriteAllowed(document)
                val items = placements(document)
                require(items.all { it.sourceSha256 == null || it.sourceSha256 == loaded.sha256 }) {
                    "Signature source changed since preview; reopen the document"
                }
                require(items.isNotEmpty() && items.all { it.pageIndex in 0 until document.numberOfPages }) {
                    "Invalid signature page selection"
                }
                for (item in items) {
                    ensureActive()
                    val page = document.getPage(item.pageIndex)
                    val geometry = SignaturePageGeometry.from(page)
                    val affine = geometry.transform(item.x,item.y,item.width,item.height,item.rotationDegrees,
                        item.previewWidth ?: geometry.displayWidth,item.previewHeight ?: geometry.displayHeight)
                    drawSignatureImage(document,page,item.bitmap,affine)
                }
                exportVerifiedPdf(context,document,document.numberOfPages) { borrowedOutput(outputStream) }
            }
            SignatureResult(true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SignatureResult(false,e.message ?: "Failed to sign PDF")
        }
    }
}
