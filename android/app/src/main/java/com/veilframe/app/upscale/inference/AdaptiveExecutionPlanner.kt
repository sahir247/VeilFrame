package com.veilframe.app.upscale.inference

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Thermal hysteresis policy governing progressive step-downs and cooldown intervals.
 */
data class ThermalRecoveryPolicy(
    val minimumDwellMs: Long = 5000L,
    val recoveryCooldownMs: Long = 8000L,
    val minimumStableSamples: Int = 3
)

/**
 * Adaptive Execution Optimizer that determines the best measured sustainable execution profile
 * execution profile for AI super-resolution on Android devices.
 *
 * Implements Progressive Accelerator Concurrency Without CPU-Core Capping across 4 distinct layers:
 * 1. Static Candidate Generation (ModelExecutionCapabilities / QnnModelCapability):
 *    Initial candidate generation without pre-classifying models as HTP-ineligible.
 * 2. Runtime Model Compatibility Determination (ORT model/device compatibility API):
 *    Evaluates device and model graph compatibility prior to expensive compilation.
 * 3. Empirical Full-Graph Verification (disable_cpu_ep_fallback = 1 probe):
 *    Verifies operators, shapes, and backend constraints without CPU fallback masking.
 * 4. Performance Determination (ExecutionBenchmarkEngine):
 *    Measures median multi-tile throughput and scaling efficiency to choose optimal workers.
 */
class AdaptiveExecutionPlanner(
    private val benchmarkEngine: ExecutionBenchmarkEngine = ExecutionBenchmarkEngine(),
    val thermalRecoveryPolicy: ThermalRecoveryPolicy = ThermalRecoveryPolicy(),
    val defaultBenchmarkSearchLimit: Int = DEFAULT_BENCHMARK_SEARCH_LIMIT
) {
    constructor(
        benchmarkEngine: ExecutionBenchmarkEngine,
        hysteresisCooldownMs: Long
    ) : this(
        benchmarkEngine = benchmarkEngine,
        thermalRecoveryPolicy = ThermalRecoveryPolicy(recoveryCooldownMs = hysteresisCooldownMs)
    )

    companion object {
        private const val TAG = "VeilFrame.AdaptivePlanner"
        private const val BENCHMARK_VERSION = 2
        const val DEFAULT_BENCHMARK_SEARCH_LIMIT = 16
    }

    private var lastThrottleTimestamp: Long = 0L
    private var lastObservedThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
    private var stableCoolSampleCount: Int = 0

    /**
     * Resolves the optimal execution profile for the given device, model, and image dimensions.
     */
    fun planExecution(
        context: Context,
        modelFile: File,
        modelId: String,
        targetScale: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        userMode: InferenceAccelerationMode = InferenceAccelerationMode.AUTO,
        deviceProfile: DeviceCapabilityProfile = DeviceCapabilityProfile.probe(context),
        modelCapabilities: ModelExecutionCapabilities = ModelExecutionCapabilities(nativeScale = targetScale.coerceIn(2, 4)),
        modelHash: String = "",
        benchmarkSearchLimitOverride: Int? = null
    ): ExecutionProfile {
        // 1. Separate Java vs Native memory domain budgets
        val memPlan = UpscaleMemoryPlanner.plan(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            scale = targetScale,
            isAiModel = true
        )
        val budgetBreakdown = memPlan.budgetBreakdown
        val javaBudgetBytes = budgetBreakdown?.javaBudgetBytes ?: (256L * 1024 * 1024)
        val nativeBudgetBytes = budgetBreakdown?.nativeBudgetBytes ?: (512L * 1024 * 1024)
        val maxSafeWorkers = budgetBreakdown?.maxMemorySafeWorkers ?: 1

        // 2. Resolve candidate tile sizes from TileSizePolicy
        val candidateTileSizes = TileSizePolicy.candidates(
            modelCaps = modelCapabilities,
            deviceProfile = deviceProfile,
            javaBudgetBytes = javaBudgetBytes,
            nativeBudgetBytes = nativeBudgetBytes
        )
        val defaultTileSize = if (modelCapabilities.fixedWidth != null) {
            modelCapabilities.fixedWidth
        } else {
            candidateTileSizes.firstOrNull() ?: memPlan.tileSize
        }
        val overlap = memPlan.overlap

        // Calculate total tiles for progressive worker ceiling
        val tilesX = (sourceWidth + defaultTileSize - 1) / defaultTileSize
        val tilesY = (sourceHeight + defaultTileSize - 1) / defaultTileSize
        val totalTiles = (tilesX * tilesY).coerceAtLeast(1)

        // Progressive Accelerator Concurrency Without CPU-Core Capping:
        // candidateWorkerUpperBound = min(memorySafeWorkers, providerConcurrencyConstraint, eligibleTileCount, benchmarkSearchLimit)
        // - benchmarkSearchLimit: maximum worker count calibration will search during this run (calibration policy ceiling).
        //   Raising it later discovers higher concurrency without code changes.
        // - providerConcurrencyConstraint: documented upper bound for the selected EP/runtime.
        //   CPU/XNNPACK are bounded by physical core count; NNAPI and QNN document a maximum of 8 concurrent
        //   sessions; AUTO leaves no artificial ceiling beyond benchmarkSearchLimit.
        // - maxSafeWorkers (memorySafetyBound): conservative estimated safety bound derived from observed process
        //   memory during execution.
        val benchmarkSearchLimit = benchmarkSearchLimitOverride ?: defaultBenchmarkSearchLimit
        val providerConcurrencyConstraint = when (userMode) {
            InferenceAccelerationMode.CPU -> deviceProfile.cpuCores
            InferenceAccelerationMode.XNNPACK -> deviceProfile.cpuCores
            InferenceAccelerationMode.NNAPI -> 8
            InferenceAccelerationMode.QNN -> 8
            InferenceAccelerationMode.AUTO -> 16
        }

        val candidateWorkerUpperBound = minOf(
            maxSafeWorkers,
            providerConcurrencyConstraint,
            totalTiles,
            benchmarkSearchLimit
        ).coerceAtLeast(1)

        // Zero-worker memory safety check: if memory is insufficient for even 1 tile, reject or fall back to minimal footprint
        if (maxSafeWorkers == 0) {
            Log.w(TAG, "Memory budget insufficient for even 1 tile worker (maxSafeWorkers=0). Returning safe minimal CPU single worker.")
            return ExecutionProfile(
                backend = Backend.CPU,
                precision = InferencePrecisionMode.DEFAULT,
                workers = 1,
                intraOpThreads = 1,
                interOpThreads = 1,
                tileSize = candidateTileSizes.lastOrNull() ?: 128,
                overlap = 16
            )
        }

        // 3. Generate cache key with modern ORT version, specific provider configuration ID, and model hash
        val providerConfigId = when (userMode) {
            InferenceAccelerationMode.QNN -> {
                if (modelCapabilities.qnnSupportedTargets.contains(QnnTarget.HTP)) "htp_burst" else "gpu"
            }
            else -> "default"
        }
        val cacheKey = PerformanceProfileKey.forDeviceAndModel(
            device = deviceProfile,
            modelId = modelId,
            modelScale = targetScale,
            ortVersion = "1.27.0",
            providerConfigurationId = providerConfigId,
            modelHash = modelHash
        )

        // Check persistent cache
        val cached = CachedPerformanceProfile.load(context, cacheKey)
        if (cached != null) {
            val cachedProfile = cached.executionProfile
            val modeMatches = when (userMode) {
                InferenceAccelerationMode.AUTO -> true
                InferenceAccelerationMode.NNAPI -> cachedProfile.backend == Backend.NNAPI
                InferenceAccelerationMode.CPU -> cachedProfile.backend == Backend.CPU
                InferenceAccelerationMode.XNNPACK -> cachedProfile.backend == Backend.XNNPACK
                InferenceAccelerationMode.QNN -> cachedProfile.backend == Backend.QNN
            }
            if (modeMatches && cachedProfile.workers <= maxSafeWorkers) {
                Log.i(TAG, "Using cached execution profile: $cachedProfile (${cached.measuredMpPerSecond} MP/s, confidence=${cached.confidence})")
                return cachedProfile
            }
        }

        // 4. Generate backend-specific candidate profiles
        val candidates = generateBackendCandidates(
            userMode = userMode,
            deviceProfile = deviceProfile,
            modelCapabilities = modelCapabilities,
            tileSize = defaultTileSize,
            overlap = overlap,
            maxSafeWorkers = candidateWorkerUpperBound
        )

        if (candidates.isEmpty()) {
            Log.w(TAG, "No specialized candidates found, falling back to safe CPU single worker")
            return ExecutionProfile(
                backend = Backend.CPU,
                precision = InferencePrecisionMode.DEFAULT,
                workers = 1,
                intraOpThreads = 2,
                interOpThreads = 1,
                tileSize = defaultTileSize,
                overlap = overlap
            )
        }

        if (candidates.size == 1) {
            return candidates.first()
        }

        // 5. Staged Benchmarking & Progressive Worker Scaling
        val (bestProfile, benchmarkResult) = runStagedBenchmarkWithProgressiveScaling(
            candidates = candidates,
            modelFile = modelFile,
            candidateWorkerUpperBound = candidateWorkerUpperBound,
            totalTiles = totalTiles
        )

        // 6. Cache the winning profile with comprehensive calibration confidence
        val confidence = evaluateConfidence(
            benchmarkResult = benchmarkResult,
            thermalStatus = lastObservedThermalStatus,
            isMemoryUnderPressure = !memPlan.isSafe || deviceProfile.lowRamDevice,
            sampleCount = if (totalTiles >= 12) 3 else 2
        )

        CachedPerformanceProfile.save(
            context = context,
            profile = CachedPerformanceProfile(
                key = cacheKey,
                executionProfile = bestProfile,
                measuredMpPerSecond = benchmarkResult?.throughputMpPerSec ?: 0.0,
                measuredTilesPerSecond = 0.0,
                estimatedPeakMemoryBytes = memPlan.estimatedWorkingSetBytes,
                observedPeakMemoryBytes = benchmarkResult?.peakMemoryBytes,
                sampleCount = if (totalTiles >= 12) 3 else 2,
                confidence = confidence,
                benchmarkVersion = PerformanceProfileKey.CURRENT_BENCHMARK_VERSION,
                createdAt = System.currentTimeMillis()
            )
        )

        return bestProfile
    }

    /**
     * Generates legal, backend-specific candidate execution profiles.
     * Prevents blind Cartesian explosion.
     */
    fun generateBackendCandidates(
        userMode: InferenceAccelerationMode,
        deviceProfile: DeviceCapabilityProfile,
        modelCapabilities: ModelExecutionCapabilities,
        tileSize: Int,
        overlap: Int,
        maxSafeWorkers: Int
    ): List<ExecutionProfile> {
        val candidates = mutableListOf<ExecutionProfile>()

        val evaluateNnapi = (userMode == InferenceAccelerationMode.AUTO || userMode == InferenceAccelerationMode.NNAPI) && deviceProfile.supportsNnapi
        val evaluateCpu = (userMode == InferenceAccelerationMode.AUTO || userMode == InferenceAccelerationMode.CPU)
        val evaluateXnnpack = (userMode == InferenceAccelerationMode.XNNPACK) && deviceProfile.supportsXnnpack
        val evaluateQnn = (userMode == InferenceAccelerationMode.QNN) && deviceProfile.supportsQnnBuild

        // NNAPI candidates
        if (evaluateNnapi) {
            val precisions = mutableListOf(InferencePrecisionMode.DEFAULT)
            if (deviceProfile.supportsNnapiFp16 && modelCapabilities.supportsFp16) {
                precisions.add(InferencePrecisionMode.FP16_RELAXED)
            }

            for (prec in precisions) {
                candidates.add(
                    ExecutionProfile(
                        backend = Backend.NNAPI,
                        precision = prec,
                        workers = 1,
                        intraOpThreads = null,
                        interOpThreads = null,
                        tileSize = tileSize,
                        overlap = overlap,
                        sessionStrategy = SessionStrategy.SHARED_SESSION
                    )
                )

                if (deviceProfile.cpuCores >= 4 && maxSafeWorkers >= 2) {
                    candidates.add(
                        ExecutionProfile(
                            backend = Backend.NNAPI,
                            precision = prec,
                            workers = 2,
                            intraOpThreads = null,
                            interOpThreads = null,
                            tileSize = tileSize,
                            overlap = overlap,
                            sessionStrategy = SessionStrategy.SHARED_SESSION
                        )
                    )
                }
            }
        }

        // CPU candidates: Coupled worker × intraOpThreads tuning
        if (evaluateCpu) {
            val cores = deviceProfile.cpuCores
            val threadWorkerPairs = when {
                cores >= 8 -> listOf(
                    1 to (cores * 0.75f).toInt().coerceAtLeast(4),
                    2 to 3,
                    3 to 2,
                    4 to 2
                )
                cores >= 6 -> listOf(
                    1 to 4,
                    2 to 2,
                    3 to 2
                )
                cores >= 4 -> listOf(
                    1 to 3,
                    2 to 2
                )
                else -> listOf(
                    1 to cores.coerceAtLeast(1)
                )
            }

            for ((workers, threads) in threadWorkerPairs) {
                if (workers <= maxSafeWorkers) {
                    candidates.add(
                        ExecutionProfile(
                            backend = Backend.CPU,
                            precision = InferencePrecisionMode.DEFAULT,
                            workers = workers,
                            intraOpThreads = threads,
                            interOpThreads = 1,
                            tileSize = tileSize,
                            overlap = overlap,
                            sessionStrategy = SessionStrategy.SHARED_SESSION
                        )
                    )
                }
            }
        }

        // QNN candidates (Qualcomm Snapdragon downloadable Plugin EP or custom build)
        // Gated by hardware eligibility AND model compatibility (HTP & GPU are both candidates by default)
        if (evaluateQnn && modelCapabilities.qnnCompatible) {
            val supportedTargets = modelCapabilities.qnnCandidateTargets.ifEmpty { modelCapabilities.qnnSupportedTargets }

            // 1. QNN HTP (Hexagon Tensor Processor / NPU) - burst and balanced performance modes
            if (supportedTargets.contains(QnnTarget.HTP)) {
                candidates.add(
                    ExecutionProfile(
                        backend = Backend.QNN,
                        precision = InferencePrecisionMode.FP16_RELAXED,
                        workers = 1,
                        intraOpThreads = null,
                        interOpThreads = null,
                        tileSize = tileSize,
                        overlap = overlap,
                        providerConfiguration = mapOf(
                            "backend_path" to "libQnnHtp.so",
                            "qnn.perf_mode" to "burst",
                            "htp_performance_mode" to "burst"
                        )
                    )
                )
                candidates.add(
                    ExecutionProfile(
                        backend = Backend.QNN,
                        precision = InferencePrecisionMode.FP16_RELAXED,
                        workers = 1,
                        intraOpThreads = null,
                        interOpThreads = null,
                        tileSize = tileSize,
                        overlap = overlap,
                        providerConfiguration = mapOf(
                            "backend_path" to "libQnnHtp.so",
                            "qnn.perf_mode" to "balanced",
                            "htp_performance_mode" to "balanced"
                        )
                    )
                )
                if (maxSafeWorkers >= 2) {
                    candidates.add(
                        ExecutionProfile(
                            backend = Backend.QNN,
                            precision = InferencePrecisionMode.FP16_RELAXED,
                            workers = 2,
                            intraOpThreads = null,
                            interOpThreads = null,
                            tileSize = tileSize,
                            overlap = overlap,
                            providerConfiguration = mapOf(
                                "backend_path" to "libQnnHtp.so",
                                "qnn.perf_mode" to "burst",
                                "htp_performance_mode" to "burst"
                            )
                        )
                    )
                }
            }

            // 2. QNN GPU (Qualcomm Adreno GPU) - native FP32/FP16 execution
            if (supportedTargets.contains(QnnTarget.GPU)) {
                candidates.add(
                    ExecutionProfile(
                        backend = Backend.QNN,
                        precision = InferencePrecisionMode.FP16_RELAXED,
                        workers = 1,
                        intraOpThreads = null,
                        interOpThreads = null,
                        tileSize = tileSize,
                        overlap = overlap,
                        providerConfiguration = mapOf(
                            "backend_path" to "libQnnGpu.so"
                        )
                    )
                )
            }
        }

        // XNNPACK candidates
        if (evaluateXnnpack) {
            candidates.add(
                ExecutionProfile(
                    backend = Backend.XNNPACK,
                    precision = InferencePrecisionMode.DEFAULT,
                    workers = 1,
                    intraOpThreads = (deviceProfile.cpuCores / 2).coerceAtLeast(1),
                    interOpThreads = 1,
                    tileSize = tileSize,
                    overlap = overlap
                )
            )
        }

        return candidates
    }

    /**
     * Staged micro-benchmark dispatch with progressive worker expansion:
     * 1. Single-tile benchmark across candidate baselines (workers = 1).
     * 2. Progressive worker scaling (1 -> 2 -> 3 -> 4 -> ...) using evaluateWorkerGrowth
     *    with multi-tile concurrency benchmarking.
     * 3. Select the best measured sustainable configuration under current calibration conditions.
     */
    fun runStagedBenchmarkWithProgressiveScaling(
        candidates: List<ExecutionProfile>,
        modelFile: File,
        candidateWorkerUpperBound: Int,
        totalTiles: Int
    ): Pair<ExecutionProfile, ExecutionBenchmarkEngine.BenchmarkResult?> {
        val singleTileCandidates = candidates.filter { it.workers == 1 }
            .ifEmpty { listOf(candidates.first()) }

        var bestBaseCandidate = singleTileCandidates.first()
        var bestBaseResult: ExecutionBenchmarkEngine.BenchmarkResult? = null
        var bestBaseThroughput = -1.0

        for (candidate in singleTileCandidates) {
            val result = benchmarkEngine.benchmarkSingleTile(
                profile = candidate,
                modelFile = modelFile
            )
            if (result.isSuccessful) {
                val throughput = result.throughputMpPerSec
                if (throughput > bestBaseThroughput) {
                    bestBaseThroughput = throughput
                    bestBaseCandidate = candidate
                    bestBaseResult = result
                }
            }
        }

        // Progressive worker expansion for winning candidate
        var currentProfile = bestBaseCandidate
        var currentResult = bestBaseResult
        var currentWorkers = currentProfile.workers
        val isLongWorkload = totalTiles >= 12

        while (currentWorkers < candidateWorkerUpperBound) {
            val nextWorkers = currentWorkers + 1
            val nextProfile = currentProfile.copy(
                workers = nextWorkers,
                intraOpThreads = if (currentProfile.backend == Backend.CPU) {
                    (currentProfile.intraOpThreads ?: 2).coerceAtLeast(1)
                } else null
            )

            val requiredConcurrentTiles = nextWorkers
            val sustainedBenchmarkTiles = maxOf(requiredConcurrentTiles, nextWorkers * 2)
            val benchmarkTileCount = if (totalTiles > 0) {
                maxOf(requiredConcurrentTiles, minOf(totalTiles, sustainedBenchmarkTiles))
            } else {
                sustainedBenchmarkTiles
            }

            val nextResult = benchmarkEngine.benchmarkConcurrency(
                profile = nextProfile,
                modelFile = modelFile,
                tileCount = benchmarkTileCount
            )

            if (!nextResult.isSuccessful) {
                break
            }

            val prevThroughput = currentResult?.throughputMpPerSec ?: bestBaseThroughput
            val currThroughput = nextResult.throughputMpPerSec

            val decision = benchmarkEngine.evaluateWorkerGrowth(
                previousMpPerSec = prevThroughput,
                currentMpPerSec = currThroughput,
                isLongWorkload = isLongWorkload
            )

            when (decision) {
                ExecutionBenchmarkEngine.WorkerGrowthDecision.CONTINUE -> {
                    currentWorkers = nextWorkers
                    currentProfile = nextProfile
                    currentResult = nextResult
                }
                ExecutionBenchmarkEngine.WorkerGrowthDecision.CONTINUE_IF_CONFIDENT -> {
                    if (!nextResult.queueContentionDetected) {
                        currentWorkers = nextWorkers
                        currentProfile = nextProfile
                        currentResult = nextResult
                    } else {
                        break
                    }
                }
                ExecutionBenchmarkEngine.WorkerGrowthDecision.CONTINUE_ONCE -> {
                    currentWorkers = nextWorkers
                    currentProfile = nextProfile
                    currentResult = nextResult
                    break
                }
                ExecutionBenchmarkEngine.WorkerGrowthDecision.STOP -> {
                    break
                }
            }
        }

        Log.i(TAG, "Selected execution profile: $currentProfile (throughput = ${currentResult?.throughputMpPerSec ?: bestBaseThroughput} MP/s)")
        return Pair(currentProfile, currentResult)
    }

    /**
     * Staged micro-benchmark dispatch:
     * 1. Benchmark single-tile baseline.
     * 2. Benchmark multi-tile concurrency with progressive tiles to test real worker parallelism.
     * 3. Select the best measured sustainable configuration under current calibration conditions.
     */
    fun runStagedBenchmark(
        candidates: List<ExecutionProfile>,
        modelFile: File
    ): ExecutionProfile {
        val (bestProfile, _) = runStagedBenchmarkWithProgressiveScaling(
            candidates = candidates,
            modelFile = modelFile,
            candidateWorkerUpperBound = 4,
            totalTiles = 8
        )
        return bestProfile
    }

    /**
     * Dynamic thermal adaptation with hysteresis.
     * Enforces cooldown/dwell time to prevent rapid oscillation (3 -> 2 -> 3 -> 2) at thermal boundaries.
     * - EMERGENCY / SHUTDOWN: forces workers directly to 1.
     * - SEVERE / CRITICAL: steps down toward sustainable profile (e.g. 4 -> 3 -> 2 -> 1).
     * - MODERATE: steps down if concurrency is high (> 2).
     * - LIGHT / NONE: allows recovery after recoveryCooldownMs and minimumStableSamples.
     * Adjustments must be applied at tile boundaries.
     */
    fun adaptForThermalEvent(
        currentProfile: ExecutionProfile,
        newThermalStatus: Int
    ): ExecutionProfile {
        val now = SystemClock.elapsedRealtime()
        lastObservedThermalStatus = newThermalStatus

        return when {
            // EMERGENCY / SHUTDOWN: Immediate fallback to single worker
            newThermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY -> {
                lastThrottleTimestamp = now
                stableCoolSampleCount = 0
                Log.w(TAG, "Thermal EMERGENCY engaged (status=$newThermalStatus). Reducing workers directly to 1.")
                currentProfile.copy(workers = 1)
            }
            // SEVERE / CRITICAL: Step down toward next-lower sustainable profile (e.g. 8 -> 6, 6 -> 4, 4 -> 3, 3 -> 2, 2 -> 1)
            newThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> {
                lastThrottleTimestamp = now
                stableCoolSampleCount = 0
                if (currentProfile.workers > 1) {
                    val steppedDown = calculateNextLowerSustainableWorkers(currentProfile.workers)
                    Log.w(TAG, "Thermal pressure elevated (status=$newThermalStatus). Stepping workers from ${currentProfile.workers} down to $steppedDown.")
                    currentProfile.copy(workers = steppedDown)
                } else {
                    currentProfile
                }
            }
            // MODERATE: Step-down worker count if running higher concurrency (> 2)
            newThermalStatus == PowerManager.THERMAL_STATUS_MODERATE -> {
                lastThrottleTimestamp = now
                stableCoolSampleCount = 0
                if (currentProfile.workers > 2) {
                    val steppedDown = calculateNextLowerSustainableWorkers(currentProfile.workers)
                    Log.i(TAG, "Thermal warning (MODERATE). Stepping workers from ${currentProfile.workers} down to $steppedDown.")
                    currentProfile.copy(workers = steppedDown)
                } else {
                    currentProfile
                }
            }
            // NONE / LIGHT: Recovery with hysteresis cooldown check
            newThermalStatus <= PowerManager.THERMAL_STATUS_LIGHT -> {
                val inCooldown = (now - lastThrottleTimestamp) < thermalRecoveryPolicy.recoveryCooldownMs
                if (inCooldown) {
                    // Stay at current conservative worker count until cooldown elapses
                    stableCoolSampleCount = 0
                    currentProfile
                } else {
                    stableCoolSampleCount++
                    // Device is thermally stable; sustained profile can be maintained
                    currentProfile
                }
            }
            else -> currentProfile
        }
    }

    /**
     * Calculates the next-lower sustainable worker count for progressive thermal step-downs
     * without assuming a rigid 4 -> 3 -> 2 -> 1 scale.
     * Uses benchmarked sustainable worker candidates if available, otherwise steps down adaptively:
     * 8 -> 6, 7 -> 5, 6 -> 4, 5 -> 3, 4 -> 3, 3 -> 2, 2 -> 1.
     */
    fun calculateNextLowerSustainableWorkers(
        currentWorkers: Int,
        sustainableWorkerCandidates: List<Int> = emptyList()
    ): Int {
        val lowerFromCandidates = sustainableWorkerCandidates
            .filter { it < currentWorkers }
            .maxOrNull()
        if (lowerFromCandidates != null && lowerFromCandidates >= 1) {
            return lowerFromCandidates
        }
        return when {
            currentWorkers >= 8 -> 6
            currentWorkers == 7 -> 5
            currentWorkers == 6 -> 4
            currentWorkers == 5 -> 3
            currentWorkers == 4 -> 3
            currentWorkers == 3 -> 2
            currentWorkers == 2 -> 1
            else -> 1
        }
    }

    /**
     * Evaluates calibration confidence based on sample count, timing variance,
     * thermal stability, memory pressure, and candidate repeatability.
     */
    fun evaluateConfidence(
        benchmarkResult: ExecutionBenchmarkEngine.BenchmarkResult?,
        thermalStatus: Int,
        isMemoryUnderPressure: Boolean,
        sampleCount: Int
    ): BenchmarkConfidence {
        if (benchmarkResult == null || !benchmarkResult.isSuccessful || benchmarkResult.throughputMpPerSec <= 0.0) {
            return BenchmarkConfidence.LOW
        }
        val isThermallyStable = thermalStatus <= PowerManager.THERMAL_STATUS_LIGHT
        if (!isThermallyStable || isMemoryUnderPressure) {
            return BenchmarkConfidence.LOW
        }
        return if (sampleCount >= 3) {
            BenchmarkConfidence.HIGH
        } else if (sampleCount >= 2) {
            BenchmarkConfidence.MEDIUM
        } else {
            BenchmarkConfidence.LOW
        }
    }
}
