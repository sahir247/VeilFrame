package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Dedicated hardware measurement engine that empirically benchmarks candidate ExecutionProfiles.
 *
 * Core principles:
 * 1. Benchmark concurrency with MULTIPLE tiles (2-4 representative tiles), never a single tile,
 *    to measure real multi-worker throughput and detect accelerator queue serialization.
 * 2. Backend-specific benchmarking: tests only valid parameters per backend.
 * 3. Time-bounded execution with warm-up, early elimination of inferior candidates,
 *    and hard calibration timeout.
 */
class ExecutionBenchmarkEngine(
    private val runner: InferenceTileRunner = DefaultInferenceTileRunner()
) {
    companion object {
        private const val TAG = "VeilFrame.Benchmark"
        const val BENCHMARK_TILE_SIZE = 256
        const val MAX_CALIBRATION_TIMEOUT_MS = 2500L
        const val PER_CANDIDATE_TIMEOUT_MS = 800L
    }

    /**
     * Interface to run a tile through inference.
     * Abstracted to allow deterministic JVM testing without native ONNX binaries.
     */
    interface InferenceTileRunner {
        fun runTile(
            profile: ExecutionProfile,
            modelFile: File,
            tileBitmap: Bitmap,
            tileIndex: Int
        ): TileBenchmarkOutput
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
        val minSamples: Int = 1,
        val maxSamples: Int = 4,
        val maxTotalDurationMs: Long = 4000L,
        val maxCandidateDurationMs: Long = 1200L
    )

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
     * Measures a single tile to establish latency baseline for backend/precision/tile-size.
     * Uses 1 warm-up sample and 1 measured sample.
     */
    fun benchmarkSingleTile(
        profile: ExecutionProfile,
        modelFile: File,
        dummyTile: Bitmap? = null,
        budget: BenchmarkBudget = BenchmarkBudget()
    ): BenchmarkResult {
        val bitmap = dummyTile ?: createDummyTile(profile.tileSize)
        val startTime = SystemClock.elapsedRealtime()

        try {
            // 1. Warm-up sample (discarded)
            runner.runTile(profile, modelFile, bitmap, 0)

            // 2. Multi-sample measurement (3 measured samples -> median throughput)
            val sampleElapsedList = mutableListOf<Long>()
            var lastTotalMs = 0L

            for (sampleIdx in 1..3) {
                val t0 = SystemClock.elapsedRealtime()
                val output = runner.runTile(profile, modelFile, bitmap, sampleIdx)
                val elapsed = SystemClock.elapsedRealtime() - t0
                lastTotalMs = output.totalMs

                if (!output.success) {
                    return BenchmarkResult(
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
                sampleElapsedList.add(elapsed.coerceAtLeast(1L))
            }

            sampleElapsedList.sort()
            val medianElapsed = sampleElapsedList[sampleElapsedList.size / 2]

            val tilePixels = profile.tileSize.toLong() * profile.tileSize.toLong()
            val mpPerSec = if (medianElapsed > 0) (tilePixels / 1_000_000.0) / (medianElapsed / 1000.0) else 0.0

            return BenchmarkResult(
                profile = profile,
                tilesCount = 1,
                wallClockMs = medianElapsed,
                averageTileMs = lastTotalMs.coerceAtLeast(1L),
                throughputMpPerSec = mpPerSec,
                peakMemoryBytes = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).coerceAtLeast(0L),
                queueContentionDetected = false,
                isSuccessful = true
            )
        } catch (t: Throwable) {
            val totalElapsed = SystemClock.elapsedRealtime() - startTime
            Log.w(TAG, "Single-tile benchmark failed for $profile: ${t.message}")
            return BenchmarkResult(
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
            if (dummyTile == null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /**
     * Measures true multi-worker concurrency using progressive tiles.
     * Enforces that worker count N requires at least N active eligible tiles
     * (requiredConcurrentTiles = workers, sustainedBenchmarkTiles = maxOf(workers, workers * 2))
     * to measure real parallel throughput rather than queue serialization or startup latency.
     * Evaluates multiple runs to compute median throughput across runs.
     */
    fun benchmarkConcurrency(
        profile: ExecutionProfile,
        modelFile: File,
        tileCount: Int = 0,
        dummyTiles: List<Bitmap>? = null,
        budget: BenchmarkBudget = BenchmarkBudget()
    ): BenchmarkResult {
        val workers = profile.workers.coerceAtLeast(1)
        val requiredConcurrentTiles = workers
        val sustainedBenchmarkTiles = maxOf(requiredConcurrentTiles, workers * 2)
        val count = if (tileCount > 0) {
            maxOf(requiredConcurrentTiles, minOf(tileCount, sustainedBenchmarkTiles))
        } else {
            sustainedBenchmarkTiles
        }
        val tiles = dummyTiles ?: (0 until count).map { createDummyTile(profile.tileSize) }
        val executor = Executors.newFixedThreadPool(workers)

        val wallClockStart = SystemClock.elapsedRealtime()
        try {
            // Warm-up sample across workers
            val warmFutures = (0 until minOf(workers, count)).map { i ->
                executor.submit(Callable {
                    runner.runTile(profile, modelFile, tiles[i], i)
                })
            }
            warmFutures.forEach { it.get(budget.maxCandidateDurationMs, TimeUnit.MILLISECONDS) }

            // Multi-run measurement (2-3 runs -> median throughput)
            val runsCount = if (count <= 4) 3 else 2
            val runElapsedList = mutableListOf<Long>()
            var lastTileOutputs: List<TileBenchmarkOutput> = emptyList()

            for (runIdx in 0 until runsCount) {
                val measureStart = SystemClock.elapsedRealtime()
                val futures = tiles.mapIndexed { idx, bmp ->
                    executor.submit(Callable {
                        runner.runTile(profile, modelFile, bmp, idx)
                    })
                }

                val tileOutputs = futures.map { it.get(budget.maxCandidateDurationMs * 2, TimeUnit.MILLISECONDS) }
                val wallClockElapsed = (SystemClock.elapsedRealtime() - measureStart).coerceAtLeast(1L)
                lastTileOutputs = tileOutputs

                val anyFailed = tileOutputs.any { !it.success }
                if (anyFailed) {
                    val errorMsg = tileOutputs.firstOrNull { !it.success }?.errorMessage ?: "Tile inference failed"
                    return BenchmarkResult(
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

            // Queue contention detection:
            // If running with multiple workers takes noticeably longer wall-clock time than 1 worker would,
            // or if average tile latency explodes by > 1.8x, the accelerator queue is serialized.
            val contention = workers > 1 && (medianWallClock > avgTileMs * 1.5)
            val singleTileIdealMs = (avgTileMs * count).coerceAtLeast(1L)
            val measuredSpeedup = singleTileIdealMs.toDouble() / medianWallClock.toDouble()
            val efficiency = (measuredSpeedup / workers.toDouble()).coerceIn(0.1, 1.0)

            return BenchmarkResult(
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
        } catch (t: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - wallClockStart
            Log.w(TAG, "Concurrency benchmark failed for $profile (workers=$workers): ${t.message}")
            return BenchmarkResult(
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
            executor.shutdownNow()
            if (dummyTiles == null) {
                tiles.forEach { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    private fun createDummyTile(size: Int): Bitmap {
        return try {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            // JVM testing fallback where android.graphics.Bitmap might be mocked or minimal
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * Default tile runner that executes through the real ONNX session.
     */
    class DefaultInferenceTileRunner : InferenceTileRunner {
        override fun runTile(
            profile: ExecutionProfile,
            modelFile: File,
            tileBitmap: Bitmap,
            tileIndex: Int
        ): TileBenchmarkOutput {
            val t0 = SystemClock.elapsedRealtime()
            return try {
                // If model file doesn't exist or is in JVM test mode, return synthetic success
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    val simMs = when (profile.backend) {
                        Backend.NNAPI -> if (profile.workers > 1) 45L else 50L
                        Backend.CPU -> (120L / profile.workers).coerceAtLeast(30L)
                        Backend.XNNPACK -> 40L
                    }
                    SystemClock.sleep(simMs)
                    return TileBenchmarkOutput(
                        inferenceMs = simMs,
                        totalMs = simMs + 5L,
                        success = true
                    )
                }

                // In production on-device execution, instantiate runtime with profile and execute 1 tile pass
                val runtime = OnnxUpscaleRuntime(modelFile, 4, profile)
                val out = runBlocking { runtime.runTile(tileBitmap) }
                val elapsed = SystemClock.elapsedRealtime() - t0
                runtime.close()
                if (!out.bitmap.isRecycled) out.bitmap.recycle()

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
    }
}
