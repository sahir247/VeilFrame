package com.veilframe.app.cv.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CvRuntime — the single native bootstrap + shared CV runtime services.
 *
 * Reliability contract (ADR 0006 / CV_RELIABILITY_UPGRADE_PLAN A1):
 *  - [initialize] is idempotent and thread-safe; it is invoked by
 *    [OpenCVInitProvider] BEFORE Application.onCreate, and again (belt &
 *    braces) from VeilFrameApplication and WeChatQrEngine.install.
 *  - The outcome is RECORDED, never swallowed: [isNativeAvailable] and
 *    [initError] are diagnosis facts every CV entry point must consult via
 *    [requireAvailable] instead of crashing with UnsatisfiedLinkError.
 *  - [cameraExecutor] is the ONE app-scoped single-thread executor for camera
 *    frame analysis, replacing per-controller executors that leaked a thread
 *    on every activity recreate (theme change).
 */
object CvRuntime {

    @Volatile
    var isNativeAvailable: Boolean = false
        private set

    @Volatile
    var initError: Throwable? = null
        private set

    @Volatile
    var initializedAtMs: Long = 0L
        private set

    private val initLock = Any()
    private val initAttempted = AtomicBoolean(false)

    /**
     * Loads the OpenCV native library exactly once. Safe to call from any
     * thread and any number of times (provider, Application, engine install).
     *
     * @return true when the native library is loaded and CV features may run.
     */
    fun initialize(): Boolean {
        synchronized(initLock) {
            if (initAttempted.get()) return isNativeAvailable
            initAttempted.set(true)
            val outcome = runCatching { org.opencv.android.OpenCVLoader.initLocal() }
            val error: Throwable? = outcome.exceptionOrNull()
                ?: if (outcome.getOrNull() == false) {
                    IllegalStateException("OpenCVLoader.initLocal() returned false")
                } else {
                    null
                }
            initError = error
            isNativeAvailable = error == null
            initializedAtMs = System.currentTimeMillis()
            return isNativeAvailable
        }
    }

    /**
     * Gate for every CV entry point. Throws a typed, mapped exception
     * ([CvNativeUnavailableException] → [CvErrorCode.NATIVE_UNAVAILABLE])
     * instead of letting an UnsatisfiedLinkError escape into a crash or a
     * silent catch-all.
     */
    fun requireAvailable() {
        if (!isNativeAvailable) {
            initialize() // cheap retry: covers exotic provider-disabled environments
        }
        if (!isNativeAvailable) {
            throw CvNativeUnavailableException(
                "OpenCV native library is not loaded on this device",
                initError
            )
        }
    }

    /**
     * A2: the app-scoped governed engine — lanes (INTERACTIVE/BACKGROUND/VIDEO),
     * memory admission via CvMemoryManager/MemInfoMemoryProbe, structured
     * CvResult failures. Heavy one-shot CV work (segmentation, quality analysis,
     * document warp/filter) submits here instead of raw Dispatchers.IO.
     */
    val engine: CvEngine by lazy {
        CvEngine(CvMemoryManager(MemInfoMemoryProbe()))
    }

    /** Rough admission estimate: bitmap bytes x intermediate-buffer multiplier. */
    fun estimateBytes(width: Int, height: Int, multiplier: Int): Long =
        width.toLong() * height.toLong() * 4L * multiplier.toLong()

    /** Shared single-thread executor for camera frame analysis (daemon; app-scoped). */
    val cameraExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vf-cv-camera").apply { isDaemon = true }
        }
    }

    /** One-line status for diagnostics panels. */
    fun statusLine(): String = when {
        isNativeAvailable -> "CV runtime: OpenCV native loaded in ${initDurationMs()}ms"
        initAttempted.get() -> "CV runtime: UNAVAILABLE (${initError?.message ?: "unknown"})"
        else -> "CV runtime: not initialized"
    }

    private fun initDurationMs(): Long = (initializedAtMs - processStartMs).coerceAtLeast(0L)
    private val processStartMs: Long = System.currentTimeMillis()

    /** Test seam. */
    internal fun resetForTests() {
        synchronized(initLock) {
            initAttempted.set(false)
            isNativeAvailable = false
            initError = null
            initializedAtMs = 0L
        }
    }
}
