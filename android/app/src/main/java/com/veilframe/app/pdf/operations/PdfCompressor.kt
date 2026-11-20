package com.veilframe.app.pdf.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Defines compression levels for PDF compression.
 * Different levels use different strategies based on content type.
 */
enum class CompressionLevel(val dpi: Float, val jpegQuality: Float, val description: String) {
    LOW(150f, 0.85f, "Low - Minor size reduction"),
    MEDIUM(135f, 0.70f, "Medium - Balanced"),
    HIGH(110f, 0.55f, "High - Significant reduction"),
    MAXIMUM(85f, 0.40f, "Maximum - Smallest size")
}

/**
 * Compression mode determines the compression strategy.
 */
enum class CompressionMode {
    HYBRID,      // Legacy enum name; current default is structure-preserving image optimization
    TARGET_SIZE  // Progressively compress until the target is reached or no smaller result remains
}

/**
 * Compression strategy based on PDF content analysis.
 */
enum class CompressionStrategy {
    IMAGE_OPTIMIZATION,  // Optimize embedded images only (best for text-heavy PDFs)
    FULL_RERENDER       // Re-render as images (best for scanned/image-heavy PDFs)
}

/**
 * Result of a compression operation.
 */
data class CompressionResult(
    val originalSize: Long,
    val compressedSize: Long,
    val compressionRatio: Float,
    val timeTakenMs: Long,
    val pagesProcessed: Int,
    val strategyUsed: CompressionStrategy = CompressionStrategy.IMAGE_OPTIMIZATION
) {
    val savedBytes: Long get() = originalSize - compressedSize
    val savedPercentage: Float get() = if (originalSize > 0) (savedBytes.toFloat() / originalSize) * 100 else 0f
    val wasReduced: Boolean get() = compressedSize < originalSize
}

/**
 * Result of a target-size compression operation.
 */
data class TargetSizeResult(
    val base: CompressionResult,
    val targetBytes: Long,
    val targetAchieved: Boolean
)

data class CompressionProfile(
    val dpi: Float,
    val jpegQuality: Float,
    val scaleFactor: Float
)

/**
 * Preflight every source, including passthrough paths. A full PDFBox save invalidates
 * existing digital signatures and can violate document modification permissions.
 */
internal fun inspectCompressionSource(document: PDDocument): Int {
    require(document.numberOfPages > 0) { "PDF contains no pages" }
    require(document.signatureDictionaries.isEmpty() &&
        !document.documentCatalog.cosObject.containsKey(COSName.getPDFName("Perms"))) {
        "Digitally signed or rights-enabled PDF cannot be safely rewritten. Preserve the original."
    }
    require(document.currentAccessPermission.canModify()) { "PDF does not permit modification" }
    // The workspace does not accept an owner password or promise preservation of
    // encryption settings; never turn a protected source into an ordinary PDF.
    require(!document.isEncrypted) { "Encrypted PDF cannot be safely compressed in this workspace" }
    return document.numberOfPages
}

/**
 * Only known plain RGB image dictionaries can be safely replaced with JPEG.
 * Identical decoded bytes alone do not establish equivalent image semantics.
 */
internal fun isPlainRgbCompressionImage(dictionary: COSDictionary): Boolean {
    val allowed = setOf("Type", "Subtype", "Width", "Height", "ColorSpace", "BitsPerComponent", "Filter", "DecodeParms", "Length")
    return dictionary.getDictionaryObject(COSName.getPDFName("ColorSpace")) == COSName.getPDFName("DeviceRGB") &&
        dictionary.getInt(COSName.getPDFName("BitsPerComponent")) == 8 &&
        dictionary.keySet().all { it.name in allowed }
}

/**
 * A structural optimizer is allowed to change eligible image resources, not page
 * drawing commands, boxes, rotation, or annotation identity/geometry. Compare
 * decoded command bytes rather than compressed streams (their filters may differ).
 * This is a conservative structural guard, not a visual equivalence proof.
 */
internal fun requireCompressionStructure(
    source: PDDocument,
    candidate: PDDocument,
    checkpoint: () -> Unit = {}
) {
    check(source.numberOfPages == candidate.numberOfPages) { "Compressed PDF page count changed" }
    check(source.isEncrypted == candidate.isEncrypted) { "PDF encryption state changed" }

    fun box(rect: PDRectangle?): List<Float>? = rect?.let {
        listOf(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY)
    }
    fun geometry(page: PDPage) = listOf(
        box(page.mediaBox), box(page.cropBox), box(page.bleedBox), box(page.trimBox), box(page.artBox)
    )
    fun annotations(page: PDPage) = page.annotations.map {
        Triple(it.cosObject.getNameAsString(COSName.SUBTYPE), box(it.rectangle), it.cosObject.getString(COSName.getPDFName("NM")))
    }
    fun digest(page: PDPage): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        page.contents?.use { input ->
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) sha.update(buffer, 0, count)
            }
        }
        return sha.digest()
    }

    for (i in 0 until source.numberOfPages) {
        checkpoint()
        val before = source.getPage(i)
        val after = candidate.getPage(i)
        check(before.rotation == after.rotation && before.userUnit == after.userUnit &&
            geometry(before) == geometry(after)) { "Compression altered page ${i + 1} geometry" }
        check(annotations(before) == annotations(after)) { "Compression altered page ${i + 1} annotations" }
        check(digest(before).contentEquals(digest(after))) { "Compression altered page ${i + 1} drawing commands" }
    }
}

private suspend fun copyCompressionStream(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) output.write(buffer, 0, count)
    }
    currentCoroutineContext().ensureActive()
}

/** Compress eligible opaque RGB images without rasterizing pages or rewriting signed documents. */
class PdfCompressor {

    private fun sourcePageCount(file: File): Int =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use(::inspectCompressionSource)

    private fun requirePageCount(file: File, expected: Int): Int =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            check(document.numberOfPages == expected) { "Compressed PDF page count changed" }
            expected
        }

    private fun createOperationDirectory(context: Context): File {
        val root = File(context.cacheDir, "compress_cache")
        check(root.isDirectory || root.mkdirs() || root.isDirectory) { "Cannot create PDF compression cache" }
        return Files.createTempDirectory(root.toPath(), "operation_").toFile()
    }

    /**
     * Quick check: does this PDF have any embedded raster images?
     * Used to decide compression strategy without loading the full document.
     */
    private fun pdfHasImages(file: File): Boolean {
        return try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                for (pageIndex in 0 until doc.numberOfPages) {
                    val resources = doc.getPage(pageIndex).resources ?: continue
                    val names = resources.xObjectNames?.toList() ?: continue
                    for (name in names) {
                        try {
                            if (resources.getXObject(name) is PDImageXObject) return true
                        } catch (e: Exception) { continue }
                    }
                }
                false
            }
        } catch (e: Exception) {
            false // assume no images if we can't read
        }
    }
    
    /**
     * Compress a PDF file using the optimal strategy.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to compress
     * @param outputStream Output stream for the compressed PDF
     * @param level Compression level (affects quality and size)
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return CompressionResult with size statistics
     */

    /**
     * Iteratively compress a PDF file to reach a target size.
     * Uses binary search to find the highest quality (lowest qualityPercent) that meets the target size.
     * Capped at 6-8 iterations.
     *
     * Note: renamed from compressPdfToTargetSize to avoid a JVM signature clash with the
     * TargetSizeResult overload below (parameter names are not part of the signature).
     */
    suspend fun compressPdfToTargetSizeStrict(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        targetSizeBytes: Long,
        onProgress: (Float) -> Unit = {}
    ): Result<CompressionResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var tempFile: File? = null
        var operationDir: File? = null
        try {
            require(targetSizeBytes > 0) { "Target size must be positive" }
            ensureActive()
            onProgress(0.05f)

            operationDir = createOperationDirectory(context)
            val cacheDir = operationDir
            tempFile = File(cacheDir, "source.pdf")
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    copyCompressionStream(input, output)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Cannot open input file"))

            val originalSize = tempFile.length()
            val originalPages = sourcePageCount(tempFile)
            if (originalSize <= targetSizeBytes) {
                tempFile.inputStream().use { copyCompressionStream(it, outputStream) }
                outputStream.flush()
                onProgress(1.0f)
                return@withContext Result.success(
                    CompressionResult(
                        originalSize = originalSize,
                        compressedSize = originalSize,
                        compressionRatio = 1f,
                        timeTakenMs = System.currentTimeMillis() - startTime,
                        pagesProcessed = originalPages,
                        strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
                    )
                )
            }

            // Binary search setup
            var low = 0 // Best quality
            var high = 100 // Max compression
            var bestFile: File? = tempFile
            var bestQuality = -1
            var bestSize = originalSize
            var bestStrategy = CompressionStrategy.IMAGE_OPTIMIZATION
            val maxIterations = 6
            var iterationCount = 0

            while (low <= high && iterationCount < maxIterations) {
                ensureActive()
                val mid = (low + high) / 2
                iterationCount++

                val currentProgress = 0.05f + 0.85f * (iterationCount.toFloat() / maxIterations)
                onProgress(currentProgress)

                val profile = profileFromSlider(mid) ?: profileFromLevel(CompressionLevel.MEDIUM)

                val currentStrategy = CompressionStrategy.IMAGE_OPTIMIZATION
                val optFile = tryImageOptimization(context, tempFile, profile, {})


                // Preserve document structure; rasterization requires a separate explicit opt-in.

                if (optFile != null) {
                    val currentSize = optFile.length()

                    if (currentSize <= targetSizeBytes) {
                        // Success for this target size. We can try for better quality (lower mid)
                        if (bestFile != null && bestFile != tempFile) bestFile.delete()
                        bestFile = optFile
                        bestQuality = mid
                        bestSize = currentSize
                        bestStrategy = currentStrategy
                        high = mid - 1
                    } else {
                        // We need more compression (higher mid)
                        if (bestFile == null || currentSize < bestSize) {
                            // Keep it as a fallback in case we can't reach the target
                            if (bestFile != null && bestFile != tempFile) bestFile.delete()
                            bestFile = optFile
                            bestSize = currentSize
                            bestQuality = mid
                            bestStrategy = currentStrategy
                        } else {
                            optFile.delete()
                        }
                        low = mid + 1
                    }
                } else {
                    // Compression failed for this parameter, try more compression
                    low = mid + 1
                }
            }

            onProgress(0.95f)

            if (bestFile != null) {
                val pages = requirePageCount(bestFile, originalPages)
                val isTargetReached = bestSize <= targetSizeBytes
                ensureActive()

                return@withContext if (isTargetReached) {
                    bestFile.inputStream().use { copyCompressionStream(it, outputStream) }
                    outputStream.flush()
                    onProgress(1.0f)
                    Result.success(
                        CompressionResult(
                            originalSize = originalSize,
                            compressedSize = bestSize,
                            compressionRatio = bestSize.toFloat() / originalSize,
                            timeTakenMs = System.currentTimeMillis() - startTime,
                            pagesProcessed = pages,
                            strategyUsed = bestStrategy
                        )
                    )
                } else {
                    // Let the caller handle the failure or fallback
                    // Transfer the best candidate out of the operation directory.
                    // The exception's recipient owns this one independently named file.
                    val fallback = File.createTempFile("compress_fallback_", ".pdf", context.cacheDir)
                    try {
                        Files.move(bestFile.toPath(), fallback.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } catch (failure: Exception) {
                        fallback.delete()
                        throw failure
                    }
                    Result.failure(
                        TargetSizeNotReachedException(
                            message = "Could not reach target size. Smallest size among tested settings is ${bestSize} bytes.",
                            smallestAchievableSize = bestSize,
                            fallbackFile = fallback,
                            originalSize = originalSize,
                            strategyUsed = bestStrategy,
                            pagesProcessed = pages
                        )
                    )
                }
            } else {
                return@withContext Result.failure(IllegalStateException("Compression failed at all levels."))
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        } finally {
            operationDir?.deleteRecursively()
        }
    }

    suspend fun compressPdf(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        level: CompressionLevel = CompressionLevel.MEDIUM,
        qualityPercent: Int? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<CompressionResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var tempFile: File? = null
        var operationDir: File? = null
        val profile = profileFromSlider(qualityPercent) ?: profileFromLevel(level)
        
        try {
            ensureActive()
            onProgress(0.05f)
            
            // Create a temp file to avoid loading everything into memory
            operationDir = createOperationDirectory(context)
            tempFile = File(operationDir, "source.pdf")

            // Copy URI content to temp file
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    copyCompressionStream(input, output)
                }
            } ?: return@withContext Result.failure(
                IllegalStateException("Cannot open input file")
            )
            
            val originalSize = tempFile.length()
            val originalPages = sourcePageCount(tempFile)

            // Skip for very small files
            if (originalSize < 10 * 1024) {
                tempFile.inputStream().use { copyCompressionStream(it, outputStream) }
                outputStream.flush()
                onProgress(1.0f)
                return@withContext Result.success(CompressionResult(
                    originalSize = originalSize, compressedSize = originalSize,
                    compressionRatio = 1f, timeTakenMs = System.currentTimeMillis() - startTime,
                    pagesProcessed = originalPages,
                    strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
                ))
            }

            onProgress(0.10f)

            // Try lossless structural and image optimization first
            var resultFile: File? = null
            var strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION

            val opt = tryImageOptimization(context, tempFile, profile, onProgress)
            

            
            if (opt != null && opt.length() < originalSize) {
                resultFile = opt
                strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
            } else {
                opt?.delete()
            }

                // Preserve document structure; rasterization requires a separate explicit opt-in.

            onProgress(0.95f)

            val finalFile = resultFile ?: tempFile
            val pagesProcessed = requirePageCount(finalFile, originalPages)
            ensureActive()
            finalFile.inputStream().use { copyCompressionStream(it, outputStream) }
            outputStream.flush()

            onProgress(1.0f)

            val compressedSize = finalFile.length()
            if (resultFile != null && resultFile != tempFile) resultFile.delete()

            return@withContext Result.success(CompressionResult(
                originalSize = originalSize,
                compressedSize = compressedSize,
                compressionRatio = if (originalSize > 0) compressedSize.toFloat() / originalSize else 1f,
                timeTakenMs = System.currentTimeMillis() - startTime,
                pagesProcessed = pagesProcessed,
                strategyUsed = strategyUsed
            ))
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Result.failure(
                IllegalStateException(
                    "Compression failed due to low memory. Try closing other apps or lowering compression level.",
                    e
                )
            )
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    e.message ?: "Compression failed. The PDF may be encrypted or unsupported.",
                    e
                )
            )
        } finally {
            operationDir?.deleteRecursively()
        }
    }
    
    /**
     * Try to compress by optimizing embedded images only.
     * This preserves text quality and searchability.
     */
    private suspend fun tryImageOptimization(
        context: Context,
        inputFile: File,
        profile: CompressionProfile,
        onProgress: (Float) -> Unit
    ): File? {
        var document: PDDocument? = null
        val outputFile = File.createTempFile("opt_", ".pdf", inputFile.parentFile)
        
        return try {
            // Use MemoryUsageSetting to enable temp file buffering instead of full memory load
            document = PDDocument.load(inputFile, MemoryUsageSetting.setupTempFileOnly())
            val totalPages = document.numberOfPages
            
            if (totalPages == 0) return null
            
            // Compression must not silently remove document metadata or private application data.
            
            var imagesOptimized = 0
            // Cache by actual original stream identity, not a name in the parent
            // resource dictionary (names are local to the XObject subdictionary).
            val optimizedImages = IdentityHashMap<COSStream, PDImageXObject>()
            
            // Process each page
            for (pageIndex in 0 until totalPages) {
                currentCoroutineContext().ensureActive()
                val page = document.getPage(pageIndex)
                val resources = page.resources ?: continue
                
                // Optimize images in this page
                imagesOptimized += optimizePageImages(document, resources, profile, optimizedImages)
                
                val pageProgress = 0.10f + (0.45f * (pageIndex + 1).toFloat() / totalPages)
                onProgress(pageProgress)
            }
            
            // Do not rewrite a document if no eligible image was reduced.
            if (imagesOptimized == 0) {
                outputFile.delete()
                return null
            }
            document.save(outputFile)
            val cc = currentCoroutineContext()
            PDDocument.load(inputFile, MemoryUsageSetting.setupTempFileOnly()).use { original ->
                PDDocument.load(outputFile, MemoryUsageSetting.setupTempFileOnly()).use { candidate ->
                    requireCompressionStructure(original, candidate) { cc.ensureActive() }
                }
            }
            outputFile
            
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
        } catch (e: Exception) {
            outputFile.delete()
            null
        } finally {
            document?.close()
        }
    }
    
    /**
     * Optimize images in a page's resources.
     * Returns the number of images optimized.
     */
    private suspend fun optimizePageImages(
        document: PDDocument,
        resources: PDResources,
        profile: CompressionProfile,
        optimizedCache: MutableMap<COSStream, PDImageXObject>
    ): Int {
        var optimizedCount = 0
        
        try {
            val xObjectNames = resources.xObjectNames?.toList() ?: return 0
            
            for (name in xObjectNames) {
                currentCoroutineContext().ensureActive()
                try {
                    val xObject = resources.getXObject(name)
                    if (xObject is PDImageXObject) {
                        val original = xObject.cosObject
                        val cached = optimizedCache[original]
                        val optimized = cached ?: optimizeImage(document, xObject, profile)
                        if (optimized != null) {
                            resources.put(name, optimized)
                            optimizedCache[original] = optimized
                            optimizedCount++
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Skip this image if we can't process it
                    continue
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Resources iteration failed
        }
        
        return optimizedCount
    }
    
    /**
     * Optimize a single image by recompressing it as JPEG.
     */
    private fun optimizeImage(
        document: PDDocument,
        image: PDImageXObject,
        profile: CompressionProfile
    ): PDImageXObject? {
        return try {
            if (!isPlainRgbCompressionImage(image.cosObject)) return null
            // Avoid decoding oversized images into unbounded pixel buffers.
            if (image.width < 128 || image.height < 128 ||
                image.width.toLong() * image.height > 16_000_000L) return null

            // Get original stream length to compare later
            val originalLength = image.cosObject.getLength()
            
            val originalImage = image.image ?: return null
            
            // Skip small logos/icons (width or height < 128px) as compression saves nothing and causes blur
            if (originalImage.width < 128 || originalImage.height < 128) {
                return null
            }
            
            // Calculate target dimensions based on compression level
            val scaleFactor = profile.scaleFactor
            val targetWidth = (originalImage.width * scaleFactor).toInt().coerceAtLeast(32)
            val targetHeight = (originalImage.height * scaleFactor).toInt().coerceAtLeast(32)
            
            // Create scaled bitmap
            val scaledBitmap = if (scaleFactor < 1.0f) {
                Bitmap.createScaledBitmap(originalImage, targetWidth, targetHeight, true)
            } else {
                originalImage
            }
            
            try {
                // A plain dictionary can still decode to a transparent bitmap. Never
                // flatten it to white; that changes the appearance over page content.
                if (scaledBitmap.hasAlpha()) {
                    val row = IntArray(scaledBitmap.width)
                    for (y in 0 until scaledBitmap.height) {
                        scaledBitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                        if (row.any { (it ushr 24) != 255 }) return null
                    }
                }
                val optimizedImage = JPEGFactory.createFromImage(document, scaledBitmap, profile.jpegQuality)

                if (optimizedImage != null) {
                    // Compare compressed stream length with original
                    val newLength = optimizedImage.cosObject.getLength()
                    if (newLength > 0 && newLength < originalLength) {
                        optimizedImage
                    } else {
                        null // Reject if the compressed stream is actually larger!
                    }
                } else {
                    null
                }
            } finally {
                if (scaledBitmap !== originalImage) {
                    scaledBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Try full re-render approach - converts each page to JPEG image.
     * Best for scanned documents but loses text searchability.
     */
    private suspend fun tryFullRerender(
        context: Context,
        inputFile: File,
        profile: CompressionProfile,
        onProgress: (Float) -> Unit
    ): File? {
        var outputDocument: PDDocument? = null
        var androidRenderer: android.graphics.pdf.PdfRenderer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        val outputFile = File.createTempFile("rerender_", ".pdf", inputFile.parentFile)
        
        return try {
            // A. Seekable File Descriptors
            // Ensure the source PDF URI is processed as a local, temporary cache-file copy (inputFile is guaranteed to be a temp file here)
            pfd = android.os.ParcelFileDescriptor.open(inputFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            androidRenderer = android.graphics.pdf.PdfRenderer(pfd)
            val totalPages = androidRenderer.pageCount
            
            if (totalPages == 0) return null
            
            outputDocument = PDDocument()
            
            for (pageIndex in 0 until totalPages) {
                currentCoroutineContext().ensureActive()
                var page: android.graphics.pdf.PdfRenderer.Page? = null
                var bitmap: Bitmap? = null
                
                try {
                    page = androidRenderer.openPage(pageIndex)

                    // C. Memory-Safe Dynamic Downsampling
                    // Apply an absolute resolution cap (e.g., max width/height of 2048px)
                    val maxDim = 2048f
                    val scale = profile.dpi / 72f
                    var targetWidth = (page.width * scale).toInt()
                    var targetHeight = (page.height * scale).toInt()

                    if (targetWidth > maxDim || targetHeight > maxDim) {
                        val downscale = maxDim / maxOf(targetWidth, targetHeight)
                        targetWidth = (targetWidth * downscale).toInt()
                        targetHeight = (targetHeight * downscale).toInt()
                    }

                    targetWidth = targetWidth.coerceAtLeast(1)
                    targetHeight = targetHeight.coerceAtLeast(1)

                    // Wrap the bitmap allocation and compression cycle in a try-catch (t: Throwable)
                    bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

                    // B. Canvas Initialization & Color Erase
                    bitmap.eraseColor(android.graphics.Color.WHITE)

                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val pageRect = PDRectangle(page.width.toFloat(), page.height.toFloat())
                    val newPage = PDPage(pageRect)
                    outputDocument.addPage(newPage)
                    
                    // Use high-efficiency compression formats
                    val pdImage = JPEGFactory.createFromImage(
                        outputDocument,
                        bitmap,
                        profile.jpegQuality
                    )
                    
                    PDPageContentStream(
                        outputDocument, 
                        newPage,
                        PDPageContentStream.AppendMode.OVERWRITE,
                        true,
                        true
                    ).use { contentStream ->
                        contentStream.drawImage(pdImage, pageRect.lowerLeftX, pageRect.lowerLeftY, pageRect.width, pageRect.height)
                    }
                } catch (t: Throwable) {
                    // A candidate with a missing/blank page must never win by file size.
                    if (t is CancellationException) throw t
                    throw java.io.IOException("Cannot rasterize source page ${pageIndex + 1}", t)
                } finally {
                    page?.close()
                    // Recycle the bitmap explicitly at the end of every page processing iteration
                    bitmap?.recycle()
                }
                
                onProgress((pageIndex + 1).toFloat() / totalPages)
            }
            
            check(outputDocument.numberOfPages == totalPages) { "Rasterization lost source pages" }
            outputDocument.save(outputFile)
            outputFile
            
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
        } catch (t: Throwable) {
            outputFile.delete()
            null
        } finally {
            androidRenderer?.close()
            pfd?.close()
            outputDocument?.close()
        }
    }
    
    /**
     * Estimate the compressed size based on compression level.
     * This is a rough estimate - actual results depend on PDF content.
     */
    fun estimateCompressedSize(originalSize: Long, level: CompressionLevel): Long {
        val reductionFactor = when (level) {
            CompressionLevel.LOW -> 0.85f
            CompressionLevel.MEDIUM -> 0.60f
            CompressionLevel.HIGH -> 0.40f
            CompressionLevel.MAXIMUM -> 0.30f
        }
        return (originalSize * reductionFactor).toLong()
    }

    fun estimateCompressedSize(originalSize: Long, qualityPercent: Int): Long {
        val clamped = qualityPercent.coerceIn(0, 100)
        // 0 = best quality (minimal reduction), 100 = max compression.
        val reductionFactor = 0.85f - (0.55f * (clamped / 100f))
        return (originalSize * reductionFactor).toLong()
    }

    private fun profileFromLevel(level: CompressionLevel): CompressionProfile {
        val scale = when (level) {
            CompressionLevel.LOW -> 1.0f
            CompressionLevel.MEDIUM -> 0.85f
            CompressionLevel.HIGH -> 0.70f
            CompressionLevel.MAXIMUM -> 0.55f
        }
        return CompressionProfile(
            dpi = level.dpi,
            jpegQuality = level.jpegQuality,
            scaleFactor = scale
        )
    }

    private fun profileFromSlider(qualityPercent: Int?): CompressionProfile? {
        if (qualityPercent == null) return null
        val clamped = qualityPercent.coerceIn(0, 100)
        val ratio = clamped / 100f

        val dpi = 150f - (65f * ratio) // 150 -> 85
        val jpegQuality = 0.9f - (0.52f * ratio) // 0.90 -> 0.38
        val scaleFactor = (1.0f - (0.45f * ratio)).coerceAtLeast(0.55f) // 1.00 -> 0.55, never below 0.55

        return CompressionProfile(
            dpi = dpi.coerceIn(85f, 150f),
            jpegQuality = jpegQuality.coerceIn(0.35f, 0.92f),
            scaleFactor = scaleFactor.coerceIn(0.55f, 1.0f)
        )
    }

    /** Compress PDF to a target file size using progressively stronger profiles. */
    suspend fun compressPdfToTargetSize(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        targetBytes: Long,
        onProgress: (Float) -> Unit = {}
    ): Result<TargetSizeResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var tempFile: File? = null
        var operationDir: File? = null

        try {
            require(targetBytes > 0) { "Target size must be positive" }
            ensureActive()
            onProgress(0.05f)

            // Create a temp file to avoid loading everything into memory
            operationDir = createOperationDirectory(context)
            tempFile = File(operationDir, "source.pdf")

            // Copy URI content to temp file
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    copyCompressionStream(input, output)
                }
            } ?: return@withContext Result.failure(
                IllegalStateException("Cannot open input file")
            )

            val originalSize = tempFile.length()
            val originalPages = sourcePageCount(tempFile)

            // A small file above target must still be eligible for optimization.
            if (originalSize <= targetBytes) {
                tempFile.inputStream().use { copyCompressionStream(it, outputStream) }
                outputStream.flush()
                onProgress(1.0f)
                val baseResult = CompressionResult(
                    originalSize = originalSize,
                    compressedSize = originalSize,
                    compressionRatio = 1f,
                    timeTakenMs = System.currentTimeMillis() - startTime,
                    pagesProcessed = originalPages,
                    strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
                )
                return@withContext Result.success(TargetSizeResult(
                    base = baseResult,
                    targetBytes = targetBytes,
                    targetAchieved = originalSize <= targetBytes
                ))
            }

            onProgress(0.10f)

            // Try increasingly aggressive profiles. A single estimate cannot reliably hit a
            // byte target because PDFs differ radically in image content and encoding.
            val targetRatio = if (originalSize > 0) targetBytes.toFloat() / originalSize else 1f
            val profiles = profilesForTargetRatio(targetRatio)
            var resultFile: File? = null
            var strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION

            for (index in profiles.indices) {
                val profile = profiles[index]
                currentCoroutineContext().ensureActive()
                val progressStart = 0.10f + (0.80f * index / profiles.size)
                val progressSpan = 0.80f / profiles.size

                val opt = tryImageOptimization(context, tempFile, profile) { p ->
                    onProgress(progressStart + p * progressSpan * 0.45f)
                }
                if (opt != null && opt.length() < (resultFile?.length() ?: originalSize)) {
                    resultFile?.delete()
                    resultFile = opt
                    strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
                } else {
                    opt?.delete()
                }

                // Preserve document structure; rasterization requires a separate explicit opt-in.

                if ((resultFile?.length() ?: originalSize) <= targetBytes) break
            }

            onProgress(0.95f)

            val finalFile = resultFile ?: tempFile
            val pagesProcessed = requirePageCount(finalFile, originalPages)
            ensureActive()
            finalFile.inputStream().use { copyCompressionStream(it, outputStream) }
            outputStream.flush()

            onProgress(1.0f)

            val compressedSize = finalFile.length()
            if (resultFile != null && resultFile != tempFile) resultFile.delete()

            val baseResult = CompressionResult(
                originalSize = originalSize,
                compressedSize = compressedSize,
                compressionRatio = if (originalSize > 0) compressedSize.toFloat() / originalSize else 1f,
                timeTakenMs = System.currentTimeMillis() - startTime,
                pagesProcessed = pagesProcessed,
                strategyUsed = strategyUsed
            )

            return@withContext Result.success(TargetSizeResult(
                base = baseResult,
                targetBytes = targetBytes,
                targetAchieved = compressedSize <= targetBytes
            ))

        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Result.failure(
                IllegalStateException(
                    "Compression failed due to low memory. Try closing other apps or lowering compression level.",
                    e
                )
            )
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    e.message ?: "Compression failed. The PDF may be encrypted or unsupported.",
                    e
                )
            )
        } finally {
            operationDir?.deleteRecursively()
        }
    }

    /**
     * Create a compression profile based on the target size ratio.
     * targetRatio = targetBytes / originalSize (0.0 to 1.0+)
     */
    private fun profileFromTargetRatio(targetRatio: Float): CompressionProfile {
        val clampedRatio = targetRatio.coerceIn(0.1f, 1.0f)
        // Invert: smaller target ratio = more aggressive compression
        val aggression = 1f - clampedRatio // 0.0 (mild) to ~0.9 (aggressive)

        // Map aggression to profile parameters
        // aggression 0.0 -> quality like LOW (dpi 150, q 0.85, scale 1.0)
        // aggression 0.9 -> quality like MAXIMUM (dpi 85, q 0.40, scale 0.55)
        val dpi = (150f - 65f * aggression).coerceIn(85f, 150f)
        val jpegQuality = (0.85f - 0.45f * aggression).coerceIn(0.40f, 0.85f)
        val scaleFactor = (1.0f - 0.45f * aggression).coerceIn(0.55f, 1.0f)

        return CompressionProfile(
            dpi = dpi,
            jpegQuality = jpegQuality,
            scaleFactor = scaleFactor
        )
    }

    /** Profiles used for a requested size, from quality-preserving to hard fallback. */
    private fun profilesForTargetRatio(targetRatio: Float): List<CompressionProfile> {
        val initial = profileFromTargetRatio(targetRatio)
        return listOf(
            initial,
            CompressionProfile(85f, 0.40f, 0.55f),
            CompressionProfile(65f, 0.28f, 0.42f),
            CompressionProfile(45f, 0.18f, 0.30f)
        ).distinct()
    }
}


class TargetSizeNotReachedException(
    message: String,
    val smallestAchievableSize: Long,
    val fallbackFile: File,
    val originalSize: Long,
    val strategyUsed: CompressionStrategy,
    val pagesProcessed: Int
) : Exception(message)
