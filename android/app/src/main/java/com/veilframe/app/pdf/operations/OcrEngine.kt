package com.veilframe.app.pdf.operations

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Google ML Kit OCR Engine for on-device text recognition.
 */
class OcrEngine(private val context: Context) {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun initialize(): Boolean = true

    // ML Kit does not expose cancellation of this recognition task. Retain the caller's
    // bitmap until its task actually completes: prompt coroutine cancellation would let
    // the caller's finally block recycle the bitmap while ML Kit still owns the input.
    // Only this task wait is non-cancellable; honor cancellation immediately afterward.
    private suspend fun recognize(bitmap: Bitmap): Text {
        currentCoroutineContext().ensureActive()
        val result = try {
            suspendCoroutine<Text> { continuation ->
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(inputImage).addOnCompleteListener(Executor { it.run() }) { task ->
                    if (task.isSuccessful) {
                        continuation.resume(task.result)
                    } else {
                        continuation.resumeWithException(
                            IOException("ML Kit OCR failed", task.exception)
                        )
                    }
                }
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throw failure
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    suspend fun recognizeFullText(bitmap: Bitmap): String = recognize(bitmap).text

    suspend fun recognizeText(bitmap: Bitmap): List<OcrWord> {
        val text = recognize(bitmap)
        return text.textBlocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.elements.map { element ->
                    OcrWord(
                        text = element.text,
                        boundingBox = element.boundingBox?.let { rect ->
                            OcrBoundingBox(rect.left, rect.top, rect.right, rect.bottom)
                        },
                        confidence = element.confidence?.takeIf { it.isFinite() && it in 0f..1f }
                    )
                }
            }
        }
    }

    fun close() {
        recognizer.close()
    }
}
