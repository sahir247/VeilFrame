package com.veilframe.app.pdf.operations

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google ML Kit OCR Engine for on-device text recognition.
 */
class OcrEngine(private val context: Context) {
    
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    
    suspend fun initialize(): Boolean {
        return true
    }

    suspend fun recognizeFullText(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    continuation.resume(text.text)
                }
                .addOnFailureListener { e ->
                    android.util.Log.w("OcrEngine", "ML Kit recognition failed: ${e.message}", e)
                    continuation.resume("")
                }
        }
    }
    
    suspend fun recognizeText(bitmap: Bitmap): List<OcrWord> {
        return suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            
            recognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    val words = text.textBlocks.flatMap { block ->
                        block.lines.flatMap { line ->
                            line.elements.map { element ->
                                OcrWord(
                                    text = element.text,
                                    boundingBox = element.boundingBox?.let { rect ->
                                        OcrBoundingBox(rect.left, rect.top, rect.right, rect.bottom)
                                    },
                                    confidence = element.confidence ?: 0.9f
                                )
                            }
                        }
                    }
                    continuation.resume(words)
                }
                .addOnFailureListener { e ->
                    android.util.Log.w("OcrEngine", "ML Kit recognition failed: ${e.message}", e)
                    continuation.resume(emptyList())
                }
        }
    }
    
    fun close() {
        recognizer.close()
    }
}