package com.veilframe.app.upscale.inference

import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject

/**
 * Non-sensitive device and model fingerprint key used to index cached benchmark results.
 * Alias CalibrationIdentity for level-1 cache lookup.
 */
typealias CalibrationIdentity = PerformanceProfileKey

data class PerformanceProfileKey(
    val deviceModel: String,
    val androidApi: Int,
    val abi: String,
    val ortVersion: String,
    val providerBuildId: String = "standard",
    val providerConfigurationId: String = "default",
    val modelId: String,
    val modelHash: String = "",
    val modelScale: Int,
    val socHardware: String = "",
    val benchmarkVersion: Int = CURRENT_BENCHMARK_VERSION
) {
    companion object {
        const val CURRENT_BENCHMARK_VERSION = 3

        fun forDeviceAndModel(
            device: DeviceCapabilityProfile,
            modelId: String,
            modelScale: Int,
            ortVersion: String = "1.27.0",
            providerBuildId: String = "standard",
            providerConfigurationId: String = "default",
            modelHash: String = ""
        ): PerformanceProfileKey {
            val primaryAbi = device.supportedAbis.firstOrNull() ?: "arm64-v8a"
            return PerformanceProfileKey(
                deviceModel = "${device.manufacturer} ${device.model}".trim(),
                androidApi = device.apiLevel,
                abi = primaryAbi,
                ortVersion = ortVersion,
                providerBuildId = providerBuildId,
                providerConfigurationId = providerConfigurationId,
                modelId = modelId,
                modelHash = modelHash,
                modelScale = modelScale,
                socHardware = device.hardware,
                benchmarkVersion = CURRENT_BENCHMARK_VERSION
            )
        }
    }

    fun toKeyString(): String {
        val hashPart = if (modelHash.isNotEmpty()) "_h${modelHash.take(8)}" else ""
        val socPart = if (socHardware.isNotEmpty() && socHardware != "Unknown") "_soc${socHardware.replace(' ', '_')}" else ""
        return "${deviceModel.replace(' ', '_')}${socPart}_api${androidApi}_${abi}_ort${ortVersion}_pb${providerBuildId}_pc${providerConfigurationId}_${modelId}${hashPart}_scale${modelScale}_v${benchmarkVersion}"
    }
}

/**
 * Calibration confidence level based on variance, sample count, and thermal stability.
 */
enum class BenchmarkConfidence {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Persisted performance measurement for an ExecutionProfile on a specific hardware/model combination.
 * Includes refined observed peak memory for future execution planning and calibration confidence.
 */
data class CachedPerformanceProfile(
    val key: PerformanceProfileKey,
    val executionProfile: ExecutionProfile,
    val measuredMpPerSecond: Double,
    val measuredTilesPerSecond: Double = 0.0,
    val estimatedPeakMemoryBytes: Long? = null,
    val observedPeakMemoryBytes: Long? = null,
    val sampleCount: Int = 1,
    val confidence: BenchmarkConfidence = BenchmarkConfidence.HIGH,
    val benchmarkVersion: Int = PerformanceProfileKey.CURRENT_BENCHMARK_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val configurationFingerprint: String = generateConfigurationFingerprint(executionProfile)
) {
    val measuredPeakMemoryBytes: Long get() = observedPeakMemoryBytes ?: estimatedPeakMemoryBytes ?: 0L
    val isPathological: Boolean get() = measuredMpPerSecond < MIN_HEALTHY_THROUGHPUT_MP_PER_SEC

    companion object {
        private const val PREFS_NAME = "veilframe_perf_profiles"
        const val MIN_HEALTHY_THROUGHPUT_MP_PER_SEC = 0.05

        fun generateConfigurationFingerprint(profile: ExecutionProfile): String {
            val backend = profile.backend.name.lowercase()
            val prec = profile.precision.name.lowercase()
            val layout = if (profile.acceleratorConfiguration.nnapiUseNchw) "nchw" else "default"
            val intra = profile.intraOpThreads?.let { "_intra$it" } ?: ""
            return "${backend}_${prec}_${layout}_t${profile.tileSize}_w${profile.workers}$intra"
        }

        fun load(context: Context, key: PerformanceProfileKey): CachedPerformanceProfile? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(key.toKeyString(), null) ?: return null
            return try {
                val json = JSONObject(jsonStr)
                if (json.optInt("benchmarkVersion", 0) != key.benchmarkVersion) return null

                val mpPerSec = json.getDouble("measuredMpPerSecond")
                // Circuit breaker: auto-evict pathological cached profiles
                if (mpPerSec < MIN_HEALTHY_THROUGHPUT_MP_PER_SEC) {
                    prefs.edit().remove(key.toKeyString()).apply()
                    return null
                }

                val epJson = json.getJSONObject("executionProfile")
                val backend = Backend.valueOf(epJson.getString("backend"))
                val precision = InferencePrecisionMode.valueOf(epJson.getString("precision"))
                val workers = epJson.getInt("workers")
                val intra = if (epJson.has("intraOpThreads")) epJson.optInt("intraOpThreads") else null
                val inter = if (epJson.has("interOpThreads")) epJson.optInt("interOpThreads") else null
                val tileSize = epJson.getInt("tileSize")
                val overlap = epJson.getInt("overlap")
                val sessionStrategy = SessionStrategy.valueOf(epJson.optString("sessionStrategy", SessionStrategy.SHARED_SESSION.name))

                val provConfig = mutableMapOf<String, String>()
                if (epJson.has("providerConfiguration")) {
                    val pJson = epJson.getJSONObject("providerConfiguration")
                    pJson.keys().forEach { k -> provConfig[k] = pJson.getString(k) }
                }

                val accelConfig = if (epJson.has("acceleratorConfiguration")) {
                    val aJson = epJson.getJSONObject("acceleratorConfiguration")
                    val nchw = aJson.optBoolean("nnapiUseNchw", false)
                    val fp16 = aJson.optBoolean("useFp16", false)
                    AcceleratorConfiguration(nnapiUseNchw = nchw, useFp16 = fp16)
                } else {
                    AcceleratorConfiguration()
                }

                val optLevel = if (epJson.has("optLevel")) {
                    try {
                        OrtSession.SessionOptions.OptLevel.valueOf(epJson.getString("optLevel"))
                    } catch (_: Throwable) {
                        null
                    }
                } else null

                val ep = ExecutionProfile(
                    backend = backend,
                    precision = precision,
                    workers = workers,
                    intraOpThreads = if (intra != null && intra > 0) intra else null,
                    interOpThreads = if (inter != null && inter > 0) inter else null,
                    tileSize = tileSize,
                    overlap = overlap,
                    sessionStrategy = sessionStrategy,
                    providerConfiguration = provConfig,
                    acceleratorConfiguration = accelConfig,
                    optLevel = optLevel
                )

                val obsPeak = if (json.has("observedPeakMemoryBytes")) json.optLong("observedPeakMemoryBytes") else null
                val estPeak = if (json.has("estimatedPeakMemoryBytes")) json.optLong("estimatedPeakMemoryBytes") else null
                val conf = try {
                    BenchmarkConfidence.valueOf(json.optString("confidence", BenchmarkConfidence.HIGH.name))
                } catch (_: Throwable) {
                    BenchmarkConfidence.HIGH
                }

                CachedPerformanceProfile(
                    key = key,
                    executionProfile = ep,
                    measuredMpPerSecond = mpPerSec,
                    measuredTilesPerSecond = json.optDouble("measuredTilesPerSecond", 0.0),
                    estimatedPeakMemoryBytes = estPeak,
                    observedPeakMemoryBytes = obsPeak,
                    sampleCount = json.optInt("sampleCount", 1),
                    confidence = conf,
                    benchmarkVersion = json.optInt("benchmarkVersion", key.benchmarkVersion),
                    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                    configurationFingerprint = json.optString("configurationFingerprint", generateConfigurationFingerprint(ep))
                )
            } catch (_: Throwable) {
                null
            }
        }

        fun save(context: Context, profile: CachedPerformanceProfile) {
            // Do not save pathological throughputs
            if (profile.isPathological) return

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject().apply {
                put("benchmarkVersion", profile.benchmarkVersion)
                put("measuredMpPerSecond", profile.measuredMpPerSecond)
                put("measuredTilesPerSecond", profile.measuredTilesPerSecond)
                put("configurationFingerprint", profile.configurationFingerprint)
                profile.estimatedPeakMemoryBytes?.let { put("estimatedPeakMemoryBytes", it) }
                profile.observedPeakMemoryBytes?.let { put("observedPeakMemoryBytes", it) }
                put("sampleCount", profile.sampleCount)
                put("confidence", profile.confidence.name)
                put("createdAt", profile.createdAt)

                val epJson = JSONObject().apply {
                    put("backend", profile.executionProfile.backend.name)
                    put("precision", profile.executionProfile.precision.name)
                    put("workers", profile.executionProfile.workers)
                    profile.executionProfile.intraOpThreads?.let { put("intraOpThreads", it) }
                    profile.executionProfile.interOpThreads?.let { put("interOpThreads", it) }
                    put("tileSize", profile.executionProfile.tileSize)
                    put("overlap", profile.executionProfile.overlap)
                    put("sessionStrategy", profile.executionProfile.sessionStrategy.name)
                    profile.executionProfile.optLevel?.let { put("optLevel", it.name) }

                    if (profile.executionProfile.providerConfiguration.isNotEmpty()) {
                        val pJson = JSONObject()
                        profile.executionProfile.providerConfiguration.forEach { (k, v) -> pJson.put(k, v) }
                        put("providerConfiguration", pJson)
                    }

                    val aJson = JSONObject().apply {
                        put("nnapiUseNchw", profile.executionProfile.acceleratorConfiguration.nnapiUseNchw)
                        put("useFp16", profile.executionProfile.acceleratorConfiguration.useFp16)
                    }
                    put("acceleratorConfiguration", aJson)
                }
                put("executionProfile", epJson)
            }

            prefs.edit().putString(profile.key.toKeyString(), json.toString()).apply()
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        }

        fun clearBackend(context: Context, backend: Backend) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.all.keys.forEach { key ->
                val jsonStr = prefs.getString(key, null)
                if (jsonStr != null && jsonStr.contains("\"backend\":\"${backend.name}\"")) {
                    editor.remove(key)
                }
            }
            editor.apply()
        }
    }
}
