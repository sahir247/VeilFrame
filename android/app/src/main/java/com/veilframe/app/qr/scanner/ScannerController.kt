package com.veilframe.app.qr.scanner

import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class ScannerState {
    IDLE,
    DETECTED,
    STABLE,
    PRESENTED,
    COOLDOWN
}

/**
 * Controller orchestrating the CameraX frame pipeline, state machine,
 * and duplicate suppression.
 *
 * Invariant: CameraX may deliver frames at 30/60 FPS; processing is deliberately
 * throttled to ~8–10 processed frames/sec. Dropped frames are closed immediately.
 */
class ScannerController(
    private val frameThrottleMs: Long = 100L, // ~10 processed FPS
    private val duplicateCooldownMs: Long = 2500L,
    private val requiredStableFrames: Int = 2
) {
    private val _state = MutableStateFlow(ScannerState.IDLE)
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    private val isAnalyzing = AtomicBoolean(false)
    private var lastAnalyzedTimeMs = 0L

    private var lastCandidatePayload: String? = null
    private var candidateFrameCount = 0

    private var lastPresentedPayload: String? = null
    private var lastPresentedTimeMs = 0L

    /**
     * Determines whether the given [imageProxy] should be processed or closed immediately.
     * Returns true if the frame is selected for analysis; otherwise closes the proxy and returns false.
     */
    fun shouldProcessFrame(imageProxy: ImageProxy): Boolean {
        val now = System.currentTimeMillis()

        // 1. Throttle frame rate to ~8-10 FPS
        if (now - lastAnalyzedTimeMs < frameThrottleMs) {
            imageProxy.close()
            return false
        }

        // 2. Concurrency guard: don't queue frames if decoder is still running
        if (!isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close()
            return false
        }

        lastAnalyzedTimeMs = now
        return true
    }

    /**
     * Must be called in a finally block after frame processing completes.
     */
    fun finishFrameProcessing() {
        isAnalyzing.set(false)
    }

    /**
     * Evaluates a raw decoded payload from ML Kit or ZXing.
     * Returns true if the payload has reached stability and should be presented to the user.
     */
    fun onPayloadDecoded(payload: String): Boolean {
        val now = System.currentTimeMillis()

        // Duplicate suppression window
        if (payload == lastPresentedPayload && now - lastPresentedTimeMs < duplicateCooldownMs) {
            _state.value = ScannerState.COOLDOWN
            return false
        }

        if (payload == lastCandidatePayload) {
            candidateFrameCount++
        } else {
            lastCandidatePayload = payload
            candidateFrameCount = 1
            _state.value = ScannerState.DETECTED
        }

        if (candidateFrameCount >= requiredStableFrames) {
            _state.value = ScannerState.STABLE
            lastPresentedPayload = payload
            lastPresentedTimeMs = now
            _state.value = ScannerState.PRESENTED
            return true
        }

        return false
    }

    fun onFrameMiss() {
        // Reset transient candidate if miss occurs
        candidateFrameCount = 0
        lastCandidatePayload = null
        if (_state.value != ScannerState.PRESENTED) {
            _state.value = ScannerState.IDLE
        }
    }

    fun reset() {
        isAnalyzing.set(false)
        candidateFrameCount = 0
        lastCandidatePayload = null
        lastPresentedPayload = null
        lastPresentedTimeMs = 0L
        _state.value = ScannerState.IDLE
    }
}
