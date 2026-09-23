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
 * Represents an exclusive lease on a single camera frame analysis flight.
 * Guaranteed idempotent closing of [imageProxy].
 */
class FrameToken(
    val id: Long,
    private val imageProxy: ImageProxy
) {
    private val isClosed = AtomicBoolean(false)

    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                imageProxy.close()
            } catch (_: Exception) {}
        }
    }
}

/**
 * Controller orchestrating the CameraX frame pipeline, state machine,
 * single-flight token concurrency, and duplicate suppression.
 *
 * Invariant: CameraX may deliver frames at 30/60 FPS; processing is deliberately
 * throttled to ~8–10 processed frames/sec. Dropped frames are closed immediately.
 */
class ScannerController(
    private val frameThrottleMs: Long = 100L, // ~10 processed FPS
    private val duplicateCooldownMs: Long = 2500L,
    private val requiredStableFrames: Int = 2,
    private val analysisTimeoutMs: Long = 1500L
) {
    private val _state = MutableStateFlow(ScannerState.IDLE)
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    private val tokenGenerator = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var activeTokenId: Long = 0L
    @Volatile
    private var lastAnalyzedTimeMs = 0L

    private var lastCandidatePayload: String? = null
    private var candidateFrameCount = 0

    private var lastPresentedPayload: String? = null
    private var lastPresentedTimeMs = 0L

    /**
     * Attempts to acquire an exclusive [FrameToken] for processing the given [imageProxy].
     * If throttled or another frame is actively analyzing within its timeout window, closes [imageProxy] and returns null.
     */
    fun acquireFrameToken(imageProxy: ImageProxy): FrameToken? {
        val now = System.currentTimeMillis()

        synchronized(this) {
            // Watchdog guard: if previous analysis stalled past timeout, invalidate it
            if (activeTokenId != 0L && now - lastAnalyzedTimeMs > analysisTimeoutMs) {
                activeTokenId = 0L
            }

            // 1. Throttle frame rate to ~8-10 FPS
            if (now - lastAnalyzedTimeMs < frameThrottleMs) {
                imageProxy.close()
                return null
            }

            // 2. Concurrency guard: single-flight in progress
            if (activeTokenId != 0L) {
                imageProxy.close()
                return null
            }

            val nextId = tokenGenerator.incrementAndGet()
            activeTokenId = nextId
            lastAnalyzedTimeMs = now
            return FrameToken(nextId, imageProxy)
        }
    }

    /**
     * Checks if the given [token] is still the active, non-expired flight.
     */
    fun isTokenActive(token: FrameToken): Boolean {
        return activeTokenId == token.id
    }

    /**
     * Completes frame processing for [token]. Only releases the concurrency lock if [token] owns the active flight.
     * Safely closes the underlying imageProxy.
     */
    fun finishFrameProcessing(token: FrameToken) {
        synchronized(this) {
            if (activeTokenId == token.id) {
                activeTokenId = 0L
            }
        }
        token.close()
    }

    /**
     * Legacy helper for callers not utilizing FrameToken.
     */
    fun shouldProcessFrame(imageProxy: ImageProxy): Boolean {
        val token = acquireFrameToken(imageProxy) ?: return false
        // Keep activeTokenId set and close proxy only when finishFrameProcessing is called
        return true
    }

    /**
     * Legacy release for callers not utilizing FrameToken.
     */
    fun finishFrameProcessing() {
        synchronized(this) {
            activeTokenId = 0L
        }
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
            candidateFrameCount = 0
            lastCandidatePayload = null
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
            lastPresentedPayload = payload
            lastPresentedTimeMs = now
            _state.value = ScannerState.STABLE
            return true
        }

        return false
    }

    fun markPresented() {
        _state.value = ScannerState.PRESENTED
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
        synchronized(this) {
            activeTokenId = 0L
            lastAnalyzedTimeMs = 0L
            candidateFrameCount = 0
            lastCandidatePayload = null
            lastPresentedPayload = null
            lastPresentedTimeMs = 0L
            _state.value = ScannerState.IDLE
        }
    }
}
