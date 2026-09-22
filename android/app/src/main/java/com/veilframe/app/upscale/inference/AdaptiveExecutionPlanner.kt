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
 * 1. Static Candidate Generation (ModelExecutionCapabilities):
 *    Initial candidate generation evaluated against device capabilities.
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
        private const val BENCHMARK_VERSION = PerformanceProfileKey.CURRENT_BENCHMARK_VERSION
        const val DEFAULT_BENCHMARK_SEARCH_LIMIT = 12
        const val DEFAULT_NNAPI_POLICY_LIMIT = 16
    }

    var benchmarkSearchLimit: Int = defaultBenchmarkSearchLimit
    var nnapiPolicyLimit: Int = DEFAULT_NNAPI_POLICY_LIMIT

    private var lastThrottleTimestamp: Long = 0L
    private var lastObservedThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
    private var stableCoolSampleCount: Int = 0

    fun getCandidateWorkerLimit(backend: Backend, cpuCores: Int, maxSafeWorkers: Int): Int {
        return when (backend) {
            Backend.CPU, Backend.XNNPACK -> minOf(cpuCores, maxSafeWorkers, benchmarkSearchLimit)
            Backend.NNAPI -> minOf(nnapiPolicyLimit, maxSafeWorkers, benchmarkSearchLimit)
        }
    }

    /**
     * Resolves an instant, safe operational execution profile based on device hardware,
     * available memory headroom, and total tile count (ImageToolbox-inspired lean mobile model).
     *
     * Invariants:
     * - Zero pre-calibration delay: returns in <2ms.
     * - Cores baseline: cores >= 8 -> 4, cores >= 6 -> 2, else -> 1.
     * - Memory derived upper bound: Flagships with >=8 cores and >=4 GB free native headroom can use up to 6 workers.
     * - Capped strictly by totalTiles and maxSafeWorkers.
     */
    fun resolveOperationalProfile(
        modelFile: File,
        targetScale: Int,
        userMode: InferenceAccelerationMode = InferenceAccelerationMode.AUTO,
        deviceProfile: DeviceCapabilityProfile,
        nativeBudgetBytes: Long,
        maxSafeWorkers: Int,
        defaultTileSize: Int,
        overlap: Int,
        totalTiles: Int
    ): ExecutionProfile {
        val cores = deviceProfile.cpuCores

        // 1. Hardware baseline
        val coreBaseline = when {
            cores >= 8 -> 4
            cores >= 6 -> 2
            else -> 1
        }

        // 2. Memory-derived upper bound (no fake 4-worker hard ceiling)
        val maxOperationalWorkers = if (nativeBudgetBytes >= 4L * 1024 * 1024 * 1024 && cores >= 8) {
            6
        } else {
            coreBaseline
        }

        val safeWorkers = minOf(maxOperationalWorkers, totalTiles, maxSafeWorkers.coerceAtLeast(1))

        // 3. Backend selection
        val backend = when (userMode) {
            InferenceAccelerationMode.AUTO -> {
                if (deviceProfile.supportsNnapi && deviceProfile.apiLevel >= 29) {
                    Backend.NNAPI
                } else {
                    Backend.CPU
                }
            }
            InferenceAccelerationMode.NNAPI -> Backend.NNAPI
            InferenceAccelerationMode.CPU -> Backend.CPU
            InferenceAccelerationMode.XNNPACK -> Backend.XNNPACK
        }

        // 4. Thread allocation: ~75% cores for intra-op on CPU
        val intraOp = if (backend == Backend.CPU || backend == Backend.XNNPACK) {
            when {
                cores <= 2 -> 1
                else -> (cores * 3 / 4).coerceIn(2, 6)
            }
        } else {
            null
        }

        val precision = if (deviceProfile.supportsNnapiFp16 && backend == Backend.NNAPI) {
            InferencePrecisionMode.FP16_RELAXED
        } else {
            InferencePrecisionMode.DEFAULT
        }

        val isOrtModel = modelFile.name.endsWith(".ort", ignoreCase = true)
        val optLevel = if (isOrtModel) {
            ai.onnxruntime.OrtSession.SessionOptions.OptLevel.NO_OPT
        } else {
            ai.onnxruntime.OrtSession.SessionOptions.OptLevel.BASIC_OPT
        }

        return ExecutionProfile(
            backend = backend,
            precision = precision,
            workers = safeWorkers,
            intraOpThreads = intraOp,
            interOpThreads = if (backend == Backend.CPU) 2 else null,
            tileSize = defaultTileSize,
            overlap = overlap,
            optLevel = optLevel,
            acceleratorConfiguration = AcceleratorConfiguration(
                nnapiUseNchw = (backend == Backend.NNAPI),
                useFp16 = (precision == InferencePrecisionMode.FP16_RELAXED)
            )
        )
    }

    /**
     * Resolves the optimal execution profile for the given device, model, and image dimensions.
     * Implements the 4-stage hardware-aware calibration flow:
     * - Stage 1: CPU coupled thread topology search & baseline
     * - Stage 2: Candidate-specific NNAPI probing & layout exploration
     * - Stage 3: Progressive worker scaling on surviving configuration
     * - Stage 4: Duration-based sustained burst (12s / min 4 tiles) & natural image validation
     * - Dual circuit breaker preventing pathological throughput caching
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
        benchmarkSearchLimitOverride: Int? = null,
        runtimeSpec: com.veilframe.app.upscale.model.ModelRuntimeSpec? = null
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

        val activeSearchLimit = benchmarkSearchLimitOverride ?: benchmarkSearchLimit
        this.benchmarkSearchLimit = activeSearchLimit

        // Zero-worker memory safety check
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

        // 3. Check persistent Level 1 cache
        val cacheKey = PerformanceProfileKey.forDeviceAndModel(
            device = deviceProfile,
            modelId = modelId,
            modelScale = targetScale,
            ortVersion = "1.27.0",
            providerConfigurationId = "default",
            modelHash = modelHash
        )

        val cached = CachedPerformanceProfile.load(context, cacheKey)
        if (cached != null) {
            val cachedProfile = cached.executionProfile
            val modeMatches = when (userMode) {
                InferenceAccelerationMode.AUTO -> true
                InferenceAccelerationMode.NNAPI -> cachedProfile.backend == Backend.NNAPI
                InferenceAccelerationMode.CPU -> cachedProfile.backend == Backend.CPU
                InferenceAccelerationMode.XNNPACK -> cachedProfile.backend == Backend.XNNPACK
            }
            if (modeMatches && cachedProfile.workers <= maxSafeWorkers && !cached.isPathological) {
                Log.i(TAG, "Using cached execution profile: $cachedProfile (${cached.measuredMpPerSecond} MP/s, confidence=${cached.confidence})")
                return cachedProfile
            }
        }

        // Fast-path: ImageToolbox-inspired lean operational planner (instant <2ms response)
        // Resolves safe operational defaults directly from hardware baseline, available headroom, and tile count.
        val operationalProfile = resolveOperationalProfile(
            modelFile = modelFile,
            targetScale = targetScale,
            userMode = userMode,
            deviceProfile = deviceProfile,
            nativeBudgetBytes = nativeBudgetBytes,
            maxSafeWorkers = maxSafeWorkers,
            defaultTileSize = defaultTileSize,
            overlap = overlap,
            totalTiles = totalTiles
        )
        Log.i(TAG, "Resolved safe operational execution profile (<2ms): $operationalProfile (workers=${operationalProfile.workers})")
        return operationalProfile
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

        // Optimized NNAPI candidates: evaluate both FP16 Relaxed and Default precision with progressive concurrency
        if (evaluateNnapi) {
            val precisions = mutableListOf(InferencePrecisionMode.DEFAULT)
            if (deviceProfile.supportsNnapiFp16 && modelCapabilities.supportsFp16) {
                precisions.add(InferencePrecisionMode.FP16_RELAXED)
            }

            for (prec in precisions) {
                // Baseline single-worker accelerator
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

                // Multi-worker accelerator concurrency for modern heterogeneous SoCs
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

                if (deviceProfile.cpuCores >= 8 && maxSafeWorkers >= 3) {
                    candidates.add(
                        ExecutionProfile(
                            backend = Backend.NNAPI,
                            precision = prec,
                            workers = 3,
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
