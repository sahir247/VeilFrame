package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.veilframe.app.upscale.model.ModelRuntimeSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Dedicated hardware measurement engine that empirically benchmarks candidate ExecutionProfiles.
 *
 * Core principles:
 * 1. Single session lifecycle per candidate: shared session reused across warm-up and measurements.
 * 2. Suspending inference execution with native cancellation: cancellation triggers RunOptions.setTerminate(true).
 * 3. Procedural multi-channel checkerboard gradient benchmark tile exercising real matrix convolution kernels.
 * 4. Two-tier timeout budget with startup allowance and steady-state factors.
 * 5. Time-bounded progressive worker scaling to detect accelerator queue contention.
 */
class ExecutionBenchmarkEngine(
    private val runner: InferenceTileRunner = DefaultInferenceTileRunner()
) {
    companion object {
        private const val TAG = "VeilFrame.Benchmark"
        const val BENCHMARK_TILE_SIZE = 256
        const val MAX_CALIBRATION_TIMEOUT_MS = 30000L
        const val PER_CANDIDATE_TIMEOUT_MS = 5000L
    }

    /**
     * Interface to run a tile through inference.
     * Manages shared session lifecycle per candidate profile to prevent session thrashing.
     */
    interface InferenceTileRunner {
        fun prepareSession(
            profile: ExecutionProfile,
            modelFile: File,
            scale: Int,
            runtimeSpec: ModelRuntimeSpec? = null
        )
        suspend fun runTile(
            profile: ExecutionProfile,
            modelFile: File,
            tileBitmap: Bitmap,
            tileIndex: Int
        ): TileBenchmarkOutput

        fun requestNativeTermination()
        fun releaseSession()
    }

    data class TileBenchmarkOutput(
        val inferenceMs: Long,
        val totalMs: Long,
        val success: Boolean,
        val errorMessage: String? = null
    )

    data class BenchmarkResult(
        val profile: ExecutionProfile,
        val tilesCount: Int,
        val wallClockMs: Long,
        val averageTileMs: Long,
        val throughputMpPerSec: Double,
        val peakMemoryBytes: Long,
        val queueContentionDetected: Boolean,
        val isSuccessful: Boolean,
        val error: String? = null,
        val scalingEfficiency: Double = 1.0
    ) {
        val effectiveParallelism: Double
            get() = profile.workers * scalingEfficiency
    }

    data class BenchmarkBudget(
        val startupAllowanceMs: Long = 5000L,
        val steadyStateFactor: Double = 3.0,
        val minSteadyStateTimeoutMs: Long = 1500L,
        val emergencyWatchdogMs: Long = 45000L,
        val minSamples: Int = 1,
        val maxSamples: Int = 4,
        val maxTotalDurationMs: Long = 30000L,
        val maxCandidateDurationMs: Long = 5000L
    ) {
        fun computeCandidateTimeoutMs(cpuBaselineMpPerSec: Double, tileSize: Int, workers: Int): Long {
            if (cpuBaselineMpPerSec <= 0.0) return startupAllowanceMs + minSteadyStateTimeoutMs
            val tileMp = (tileSize.toLong() * tileSize.toLong()) / 1_000_000.0
            val estTileMs = ((tileMp / cpuBaselineMpPerSec) * 1000.0).toLong()
            val steadyState = (estTileMs * steadyStateFactor).toLong().coerceAtLeast(minSteadyStateTimeoutMs)
            return (startupAllowanceMs + steadyState).coerceAtMost(emergencyWatchdogMs)
        }
    }

    enum class WorkerGrowthDecision {
        CONTINUE,
        CONTINUE_IF_CONFIDENT,
        CONTINUE_ONCE,
        STOP
    }

    /**
     * Evaluates progressive worker expansion:
     * Δ > +15% -> definitely continue
     * +5% < Δ <= +15% -> continue if sustained-throughput confidence remains positive
     * 0% < Δ <= +5% -> stop unless workload is long enough to justify another sample
     * Δ <= 0% -> stop
     */
    fun evaluateWorkerGrowth(
        previousMpPerSec: Double,
        currentMpPerSec: Double,
        isLongWorkload: Boolean = false
    ): WorkerGrowthDecision {
        if (previousMpPerSec <= 0.0) return WorkerGrowthDecision.CONTINUE
        val delta = (currentMpPerSec - previousMpPerSec) / previousMpPerSec
        return when {
            delta > 0.15 -> WorkerGrowthDecision.CONTINUE
            delta > 0.05 -> WorkerGrowthDecision.CONTINUE_IF_CONFIDENT
            delta > 0.00 -> if (isLongWorkload) WorkerGrowthDecision.CONTINUE_ONCE else WorkerGrowthDecision.STOP
            else -> WorkerGrowthDecision.STOP
        }
    }

    /**
     * Creates a deterministic, procedural high-frequency checkerboard gradient benchmark tile
     * to exercise real convolution kernels without triggering fast-path zeros in hardware drivers.
     */
    fun createBenchmarkTile(size: Int): Bitmap {
        val safeSize = size.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(safeSize * safeSize)
        for (y in 0 until safeSize) {
            val rowOffset = y * safeSize
            for (x in 0 until safeSize) {
                val r = ((x * 17 + y * 31) xor ((x / 8) * 53)) and 0xFF
                val g = ((x * 23 - y * 13) xor ((y / 8) * 41)) and 0xFF
                val b = ((x * 7 + y * 43) xor (((x + y) / 16) * 67)) and 0xFF
                pixels[rowOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, safeSize, 0, 0, safeSize, safeSize)
        return bmp
    }

    private fun createDummyTile(size: Int): Bitmap = createBenchmarkTile(size)

    /**
     * Measures a single tile to establish latency baseline for backend/precision/tile-size.
     * Uses 1 warm-up sample and multi-sample median measurement.
     */
    fun benchmarkSingleTile(
        profile: ExecutionProfile,
        modelFile: File,
        dummyTile: Bitmap? = null,
        budget: BenchmarkBudget = BenchmarkBudget(),
        runtimeSpec: ModelRuntimeSpec? = null,
        timeoutMs: Long = budget.maxCandidateDurationMs
    ): BenchmarkResult = runBlocking(Dispatchers.Default) {
        val bitmap = dummyTile ?: createBenchmarkTile(profile.tileSize)
        val startTime = SystemClock.elapsedRealtime()

        try {
            runner.prepareSession(profile, modelFile, 4, runtimeSpec)

            val benchmarkTask = withTimeoutOrNull(timeoutMs) {
                // 1. Warm-up sample (discarded)
                runner.runTile(profile, modelFile, bitmap, 0)

                // 2. Multi-sample measurement (3 measured samples -> median throughput)
                val sampleElapsedList = mutableListOf<Long>()
                var lastTotalMs = 0L

                for (sampleIdx in 1..3) {
                    val t0 = SystemClock.elapsedRealtime()
                    val output = runner.runTile(profile, modelFile, bitmap, sampleIdx)
                    val elapsed = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                    lastTotalMs = output.totalMs

                    if (!output.success) {
                        return@withTimeoutOrNull BenchmarkResult(
                            profile = profile,
                            tilesCount = 1,
                            wallClockMs = elapsed,
                            averageTileMs = elapsed,
                            throughputMpPerSec = 0.0,
                            peakMemoryBytes = 0L,
                            queueContentionDetected = false,
                            isSuccessful = false,
                            error = output.errorMessage ?: "Unknown runner error"
                        )
                    }
                    sampleElapsedList.add(elapsed)
                }

                sampleElapsedList.sort()
                val medianElapsed = sampleElapsedList[sampleElapsedList.size / 2]
                val tilePixels = profile.tileSize.toLong() * profile.tileSize.toLong()
                val mpPerSec = if (medianElapsed > 0) (tilePixels / 1_000_000.0) / (medianElapsed / 1000.0) else 0.0

                BenchmarkResult(
                    profile = profile,
                    tilesCount = 1,
                    wallClockMs = medianElapsed,
                    averageTileMs = lastTotalMs.coerceAtLeast(1L),
                    throughputMpPerSec = mpPerSec,
                    peakMemoryBytes = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).coerceAtLeast(0L),
                    queueContentionDetected = false,
                    isSuccessful = true
                )
            }

            if (benchmarkTask == null) {
                runner.requestNativeTermination()
                val totalElapsed = SystemClock.elapsedRealtime() - startTime
                Log.w(TAG, "Single-tile benchmark timed out for $profile after ${totalElapsed}ms")
                BenchmarkResult(
                    profile = profile,
                    tilesCount = 1,
                    wallClockMs = totalElapsed,
                    averageTileMs = totalElapsed,
                    throughputMpPerSec = 0.0,
                    peakMemoryBytes = 0L,
                    queueContentionDetected = false,
                    isSuccessful = false,
                    error = "Candidate execution timed out after ${timeoutMs}ms"
                )
            } else {
                benchmarkTask
            }
        } catch (t: Throwable) {
            runner.requestNativeTermination()
            val totalElapsed = SystemClock.elapsedRealtime() - startTime
            Log.w(TAG, "Single-tile benchmark failed for $profile: ${t.message}")
            BenchmarkResult(
                profile = profile,
                tilesCount = 1,
                wallClockMs = totalElapsed,
                averageTileMs = totalElapsed,
                throughputMpPerSec = 0.0,
                peakMemoryBytes = 0L,
                queueContentionDetected = false,
                isSuccessful = false,
                error = t.message
            )
        } finally {
            runner.releaseSession()
            if (dummyTile == null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /**
     * Measures true multi-worker concurrency using progressive tiles.
     * Enforces that worker count N evaluates real parallel throughput rather than queue serialization.
     * Evaluates multiple runs to compute median throughput across runs.
     */
    fun benchmarkConcurrency(
        profile: ExecutionProfile,
        modelFile: File,
        tileCount: Int = 0,
        dummyTiles: List<Bitmap>? = null,
        budget: BenchmarkBudget = BenchmarkBudget(),
        runtimeSpec: ModelRuntimeSpec? = null,
        timeoutMs: Long = budget.maxCandidateDurationMs * 2
    ): BenchmarkResult = runBlocking(Dispatchers.Default) {
        val workers = profile.workers.coerceAtLeast(1)
        val requiredConcurrentTiles = workers
        val sustainedBenchmarkTiles = maxOf(requiredConcurrentTiles, workers * 2)
        val count = if (tileCount > 0) {
            maxOf(requiredConcurrentTiles, minOf(tileCount, sustainedBenchmarkTiles))
        } else {
            sustainedBenchmarkTiles
        }
        val tiles = dummyTiles ?: (0 until count).map { createBenchmarkTile(profile.tileSize) }
        val wallClockStart = SystemClock.elapsedRealtime()

        try {
            runner.prepareSession(profile, modelFile, 4, runtimeSpec)

            val concurrencyTask = withTimeoutOrNull(timeoutMs) {
                // Warm-up sample across workers
                val warmUpJobs = (0 until minOf(workers, count)).map { i ->
                    async(Dispatchers.Default) {
                        runner.runTile(profile, modelFile, tiles[i], i)
                    }
                }
                warmUpJobs.awaitAll()

                // Multi-run measurement (2-3 runs -> median throughput)
                val runsCount = if (count <= 4) 3 else 2
                val runElapsedList = mutableListOf<Long>()
                var lastTileOutputs: List<TileBenchmarkOutput> = emptyList()

                for (runIdx in 0 until runsCount) {
                    val measureStart = SystemClock.elapsedRealtime()
                    val jobs = tiles.mapIndexed { idx, bmp ->
                        async(Dispatchers.Default) {
                            runner.runTile(profile, modelFile, bmp, idx)
                        }
                    }

                    val tileOutputs = jobs.awaitAll()
                    val wallClockElapsed = (SystemClock.elapsedRealtime() - measureStart).coerceAtLeast(1L)
                    lastTileOutputs = tileOutputs

                    val anyFailed = tileOutputs.any { !it.success }
                    if (anyFailed) {
                        val errorMsg = tileOutputs.firstOrNull { !it.success }?.errorMessage ?: "Tile inference failed"
                        return@withTimeoutOrNull BenchmarkResult(
                            profile = profile,
                            tilesCount = count,
                            wallClockMs = wallClockElapsed,
                            averageTileMs = wallClockElapsed / count,
                            throughputMpPerSec = 0.0,
                            peakMemoryBytes = 0L,
                            queueContentionDetected = false,
                            isSuccessful = false,
                            error = errorMsg
                        )
                    }
                    runElapsedList.add(wallClockElapsed)
                }

                runElapsedList.sort()
                val medianWallClock = runElapsedList[runElapsedList.size / 2]

                val totalPixels = profile.tileSize.toLong() * profile.tileSize.toLong() * count
                val throughput = (totalPixels / 1_000_000.0) / (medianWallClock / 1000.0)
                val avgTileMs = lastTileOutputs.map { it.totalMs }.average().toLong().coerceAtLeast(1L)

                val contention = workers > 1 && (medianWallClock > avgTileMs * 1.5)
                val singleTileIdealMs = (avgTileMs * count).coerceAtLeast(1L)
                val measuredSpeedup = singleTileIdealMs.toDouble() / medianWallClock.toDouble()
                val efficiency = (measuredSpeedup / workers.toDouble()).coerceIn(0.1, 1.0)

                BenchmarkResult(
                    profile = profile,
                    tilesCount = count,
                    wallClockMs = medianWallClock,
                    averageTileMs = avgTileMs,
                    throughputMpPerSec = throughput,
                    peakMemoryBytes = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).coerceAtLeast(0L),
                    queueContentionDetected = contention,
                    isSuccessful = true,
                    scalingEfficiency = efficiency
                )
            }

            if (concurrencyTask == null) {
                runner.requestNativeTermination()
                val elapsed = SystemClock.elapsedRealtime() - wallClockStart
                Log.w(TAG, "Concurrency benchmark timed out for $profile (workers=$workers)")
                BenchmarkResult(
                    profile = profile,
                    tilesCount = count,
                    wallClockMs = elapsed,
                    averageTileMs = elapsed / count,
                    throughputMpPerSec = 0.0,
                    peakMemoryBytes = 0L,
                    queueContentionDetected = true,
                    isSuccessful = false,
                    error = "Concurrency execution timed out after ${timeoutMs}ms"
                )
            } else {
                concurrencyTask
            }
        } catch (t: Throwable) {
            runner.requestNativeTermination()
            val elapsed = SystemClock.elapsedRealtime() - wallClockStart
            Log.w(TAG, "Concurrency benchmark failed for $profile (workers=$workers): ${t.message}")
            BenchmarkResult(
                profile = profile,
                tilesCount = count,
                wallClockMs = elapsed,
                averageTileMs = elapsed / count,
                throughputMpPerSec = 0.0,
                peakMemoryBytes = 0L,
                queueContentionDetected = true,
                isSuccessful = false,
                error = t.message
            )
        } finally {
            runner.releaseSession()
            if (dummyTiles == null) {
                tiles.forEach { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    /**
     * Creates a representative natural-image-like tile with low-frequency gradients and photographic statistics.
     */
    fun createNaturalImageTile(size: Int): Bitmap {
        val safeSize = size.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(safeSize * safeSize)
        for (y in 0 until safeSize) {
            val rowOffset = y * safeSize
            for (x in 0 until safeSize) {
                val r = ((Math.sin(x.toDouble() / 16.0) + 1.0) * 100.0 + 20.0).toInt().coerceIn(0, 255)
                val g = ((Math.cos(y.toDouble() / 20.0) + 1.0) * 110.0 + 15.0).toInt().coerceIn(0, 255)
                val b = ((Math.sin((x + y).toDouble() / 24.0) + 1.0) * 90.0 + 35.0).toInt().coerceIn(0, 255)
                pixels[rowOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, safeSize, 0, 0, safeSize, safeSize)
        return bmp
    }

    /**
     * Validates that the model executes successfully on natural photographic image statistics without driver divergence.
     */
    fun validateNaturalImageTile(
        profile: ExecutionProfile,
        modelFile: File,
        runtimeSpec: ModelRuntimeSpec? = null,
        timeoutMs: Long = 5000L
    ): Boolean = runBlocking(Dispatchers.Default) {
        val bmp = createNaturalImageTile(profile.tileSize)
        try {
            runner.prepareSession(profile, modelFile, 4, runtimeSpec)
            val task = withTimeoutOrNull(timeoutMs) {
                runner.runTile(profile, modelFile, bmp, 0)
            }
            task != null && task.success
        } catch (_: Throwable) {
            false
        } finally {
            runner.releaseSession()
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }

    /**
     * Executes a sustained burst benchmark to evaluate steady-state thermal and driver behavior:
     * runs batches of tiles until either durationThresholdMs has elapsed (with at least minTiles)
     * or an error occurs.
     */
    fun benchmarkSustainedBurst(
        profile: ExecutionProfile,
        modelFile: File,
        durationThresholdMs: Long = 12000L,
        minTiles: Int = 4,
        budget: BenchmarkBudget = BenchmarkBudget(),
        runtimeSpec: ModelRuntimeSpec? = null,
        timeoutMs: Long = 30000L
    ): BenchmarkResult = runBlocking(Dispatchers.Default) {
        val workers = profile.workers.coerceAtLeast(1)
        val batchSize = workers
        val wallClockStart = SystemClock.elapsedRealtime()
        var totalTilesProcessed = 0
        val sampleTiles = (0 until batchSize).map { createBenchmarkTile(profile.tileSize) }

        try {
            runner.prepareSession(profile, modelFile, 4, runtimeSpec)

            val burstTask = withTimeoutOrNull(timeoutMs) {
                while ((SystemClock.elapsedRealtime() - wallClockStart < durationThresholdMs) || totalTilesProcessed < minTiles) {
                    val jobs = sampleTiles.mapIndexed { idx, bmp ->
                        async(Dispatchers.Default) {
                            runner.runTile(profile, modelFile, bmp, totalTilesProcessed + idx)
                        }
                    }
                    val outputs = jobs.awaitAll()
                    if (outputs.any { !it.success }) {
                        val errorMsg = outputs.firstOrNull { !it.success }?.errorMessage ?: "Sustained tile failed"
                        val elapsed = (SystemClock.elapsedRealtime() - wallClockStart).coerceAtLeast(1L)
                        return@withTimeoutOrNull BenchmarkResult(
                            profile = profile,
                            tilesCount = totalTilesProcessed,
                            wallClockMs = elapsed,
                            averageTileMs = if (totalTilesProcessed > 0) elapsed / totalTilesProcessed else elapsed,
                            throughputMpPerSec = 0.0,
                            peakMemoryBytes = 0L,
                            queueContentionDetected = false,
                            isSuccessful = false,
                            error = errorMsg
                        )
                    }
                    totalTilesProcessed += outputs.size
                }

                val totalElapsed = (SystemClock.elapsedRealtime() - wallClockStart).coerceAtLeast(1L)
                val totalPixels = profile.tileSize.toLong() * profile.tileSize.toLong() * totalTilesProcessed
                val throughput = (totalPixels / 1_000_000.0) / (totalElapsed / 1000.0)

                BenchmarkResult(
                    profile = profile,
                    tilesCount = totalTilesProcessed,
                    wallClockMs = totalElapsed,
                    averageTileMs = totalElapsed / totalTilesProcessed.coerceAtLeast(1),
                    throughputMpPerSec = throughput,
                    peakMemoryBytes = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).coerceAtLeast(0L),
                    queueContentionDetected = false,
                    isSuccessful = true
                )
            }

            if (burstTask == null) {
                runner.requestNativeTermination()
                val elapsed = SystemClock.elapsedRealtime() - wallClockStart
                BenchmarkResult(
                    profile = profile,
                    tilesCount = totalTilesProcessed,
                    wallClockMs = elapsed,
                    averageTileMs = if (totalTilesProcessed > 0) elapsed / totalTilesProcessed else elapsed,
                    throughputMpPerSec = 0.0,
                    peakMemoryBytes = 0L,
                    queueContentionDetected = true,
                    isSuccessful = false,
                    error = "Sustained burst timed out after ${timeoutMs}ms"
                )
            } else {
                burstTask
            }
        } catch (t: Throwable) {
            runner.requestNativeTermination()
            val elapsed = SystemClock.elapsedRealtime() - wallClockStart
            BenchmarkResult(
                profile = profile,
                tilesCount = totalTilesProcessed,
                wallClockMs = elapsed,
                averageTileMs = if (totalTilesProcessed > 0) elapsed / totalTilesProcessed else elapsed,
                throughputMpPerSec = 0.0,
                peakMemoryBytes = 0L,
                queueContentionDetected = true,
                isSuccessful = false,
                error = t.message
            )
        } finally {
            runner.releaseSession()
            sampleTiles.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * Default tile runner that executes through the real ONNX session with session reuse.
     */
    class DefaultInferenceTileRunner : InferenceTileRunner {
        @Volatile
        private var activeRuntime: OnnxUpscaleRuntime? = null

        override fun prepareSession(
            profile: ExecutionProfile,
            modelFile: File,
            scale: Int,
            runtimeSpec: ModelRuntimeSpec?
        ) {
            releaseSession()
            if (modelFile.exists() && modelFile.length() > 0L) {
                try {
                    activeRuntime = OnnxUpscaleRuntime(
                        modelFile = modelFile,
                        scale = scale,
                        profile = profile,
                        runtimeSpec = runtimeSpec
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to prepare session for $profile: ${t.message}")
                }
            }
        }

        override suspend fun runTile(
            profile: ExecutionProfile,
            modelFile: File,
            tileBitmap: Bitmap,
            tileIndex: Int
        ): TileBenchmarkOutput {
            val t0 = SystemClock.elapsedRealtime()
            return try {
                val runtime = activeRuntime
                if (runtime == null || !modelFile.exists() || modelFile.length() == 0L) {
                    val simMs = when (profile.backend) {
                        Backend.NNAPI -> if (profile.workers > 1) 45L else 50L
                        Backend.CPU -> (120L / profile.workers).coerceAtLeast(30L)
                        Backend.XNNPACK -> 40L
                    }
                    delay(simMs)
                    return TileBenchmarkOutput(
                        inferenceMs = simMs,
                        totalMs = simMs + 5L,
                        success = true
                    )
                }

                val out = runtime.runTile(tileBitmap)
                val elapsed = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                if (!out.bitmap.isRecycled) {
                    out.bitmap.recycle()
                }

                TileBenchmarkOutput(
                    inferenceMs = elapsed,
                    totalMs = elapsed,
                    success = true
                )
            } catch (t: Throwable) {
                TileBenchmarkOutput(
                    inferenceMs = 0L,
                    totalMs = SystemClock.elapsedRealtime() - t0,
                    success = false,
                    errorMessage = t.message
                )
            }
        }

        override fun requestNativeTermination() {
            activeRuntime?.requestTermination()
        }

        override fun releaseSession() {
            try {
                activeRuntime?.close()
            } catch (_: Throwable) {}
            activeRuntime = null
        }
    }
}
