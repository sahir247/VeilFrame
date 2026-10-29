package com.veilframe.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.motion.FlowEstimator
import com.veilframe.app.cv.motion.FrameSynthesizer
import com.veilframe.app.cv.motion.VideoStabilizerEngine
import com.veilframe.app.media.AndroidMediaBackend
import com.veilframe.app.media.JobState
import com.veilframe.app.media.JobType
import com.veilframe.app.media.ProcessingJob
import com.veilframe.app.media.rife.RifeModelManager
import com.veilframe.app.media.rife.RifeNcnnNative
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import org.opencv.imgcodecs.Imgcodecs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Android Foreground Service for heavy media cleaning, batch folder analysis,
 * Motion Lab RIFE neural synthesis, and Video Stabilization.
 * Uses Android 15 mediaProcessing foreground service type on API 35+,
 * with graceful dataSync fallback for API 34 and below.
 *
 * Moving heavy synthesis and stabilization into this foreground service guarantees
 * processing survival across incoming calls, screen locks, and app switching.
 */
class VeilFrameProcessingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentExecutionJob: kotlinx.coroutines.Job? = null
    private lateinit var mediaBackend: AndroidMediaBackend
    private var isForegroundStarted = false

    companion object {
        private const val TAG = "VeilFrameProcessing"
        const val CHANNEL_ID = "veilframe_media_processing"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PROCESS_VIDEO = "com.veilframe.action.PROCESS_VIDEO"
        const val ACTION_PROCESS_MOTION_LAB = "com.veilframe.action.PROCESS_MOTION_LAB"
        const val ACTION_PROCESS_STABILIZATION = "com.veilframe.action.PROCESS_STABILIZATION"
        const val ACTION_CANCEL_JOB = "com.veilframe.action.CANCEL_JOB"

        const val EXTRA_INPUT_PATH = "extra_input_path"
        const val EXTRA_OUTPUT_PATH = "extra_output_path"
        const val EXTRA_INPUT_URI = "extra_input_uri"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        const val EXTRA_JOB_ID = "extra_job_id"
        const val EXTRA_JOB_TYPE = "extra_job_type"

        const val EXTRA_MOTION_METHOD = "extra_motion_method"
        const val EXTRA_MOTION_MULTIPLIER = "extra_motion_multiplier"
        const val EXTRA_MOTION_TARGET_60FPS = "extra_motion_target_60fps"
        const val EXTRA_STABILIZATION_MARGIN = "extra_stabilization_margin"

        interface JobCallback {
            fun onProgress(jobId: UUID, percent: Int, status: String) {}
            fun onCompleted(jobId: UUID, outputPath: String) {}
            fun onFailed(jobId: UUID, error: String) {}
        }

        @Volatile
        var jobCallback: JobCallback? = null

        @Volatile
        var activeJob: ProcessingJob? = null
            internal set

        fun cancelProcessing(context: Context, jobId: UUID? = null) {
            val current = activeJob
            if (current == null || current.state != JobState.RUNNING) {
                try {
                    context.stopService(Intent(context, VeilFrameProcessingService::class.java))
                } catch (_: Exception) {}
                return
            }

            if (jobId != null && current.id != jobId) {
                return
            }

            activeJob = current.copy(state = JobState.CANCELLED)

            val intent = Intent(context, VeilFrameProcessingService::class.java).apply {
                action = ACTION_CANCEL_JOB
                if (jobId != null) putExtra(EXTRA_JOB_ID, jobId.toString())
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                try {
                    context.stopService(Intent(context, VeilFrameProcessingService::class.java))
                } catch (_: Exception) {}
            }
        }

        fun startVideoCleaning(context: Context, inputPath: String, outputPath: String, jobId: UUID = UUID.randomUUID()) {
            val intent = Intent(context, VeilFrameProcessingService::class.java).apply {
                action = ACTION_PROCESS_VIDEO
                putExtra(EXTRA_INPUT_PATH, inputPath)
                putExtra(EXTRA_OUTPUT_PATH, outputPath)
                putExtra(EXTRA_JOB_ID, jobId.toString())
                putExtra(EXTRA_JOB_TYPE, JobType.METADATA_STRIP.name)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun startVideoCleaning(context: Context, inputUri: Uri, outputUri: Uri, jobId: UUID = UUID.randomUUID()) {
            val intent = Intent(context, VeilFrameProcessingService::class.java).apply {
                action = ACTION_PROCESS_VIDEO
                putExtra(EXTRA_INPUT_URI, inputUri)
                putExtra(EXTRA_OUTPUT_URI, outputUri)
                putExtra(EXTRA_INPUT_PATH, inputUri.toString())
                putExtra(EXTRA_OUTPUT_PATH, outputUri.toString())
                putExtra(EXTRA_JOB_ID, jobId.toString())
                putExtra(EXTRA_JOB_TYPE, JobType.METADATA_STRIP.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun startMotionLab(
            context: Context,
            inputUri: Uri,
            outputPath: String,
            method: String = "RIFE",
            multiplier: Int = 2,
            target60fps: Boolean = true,
            jobId: UUID = UUID.randomUUID()
        ) {
            val intent = Intent(context, VeilFrameProcessingService::class.java).apply {
                action = ACTION_PROCESS_MOTION_LAB
                putExtra(EXTRA_INPUT_URI, inputUri)
                putExtra(EXTRA_OUTPUT_PATH, outputPath)
                putExtra(EXTRA_INPUT_PATH, inputUri.toString())
                putExtra(EXTRA_JOB_ID, jobId.toString())
                putExtra(EXTRA_JOB_TYPE, JobType.MOTION_LAB_RIFE.name)
                putExtra(EXTRA_MOTION_METHOD, method)
                putExtra(EXTRA_MOTION_MULTIPLIER, multiplier)
                putExtra(EXTRA_MOTION_TARGET_60FPS, target60fps)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun startVideoStabilization(
            context: Context,
            inputUri: Uri,
            outputPath: String,
            stabilizationMarginPercent: Int = 5,
            jobId: UUID = UUID.randomUUID()
        ) {
            val intent = Intent(context, VeilFrameProcessingService::class.java).apply {
                action = ACTION_PROCESS_STABILIZATION
                putExtra(EXTRA_INPUT_URI, inputUri)
                putExtra(EXTRA_OUTPUT_PATH, outputPath)
                putExtra(EXTRA_INPUT_PATH, inputUri.toString())
                putExtra(EXTRA_JOB_ID, jobId.toString())
                putExtra(EXTRA_JOB_TYPE, JobType.VIDEO_STABILIZATION.name)
                putExtra(EXTRA_STABILIZATION_MARGIN, stabilizationMarginPercent)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaBackend = AndroidMediaBackend(this)
        createNotificationChannel()
    }

    private fun promoteToForeground(message: String): Boolean {
        if (isForegroundStarted) return true
        return try {
            val notification = buildForegroundNotification(message)
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
            } else if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_JOB) {
            val targetJobIdStr = intent.getStringExtra(EXTRA_JOB_ID)
            var isTargetJob = false
            synchronized(this) {
                if (targetJobIdStr == null || activeJob?.id.toString() == targetJobIdStr) {
                    isTargetJob = true
                    if (activeJob?.state == JobState.RUNNING) {
                        activeJob = activeJob?.copy(state = JobState.CANCELLED)
                    }
                    currentExecutionJob?.cancel()
                }
            }
            if (!isTargetJob) {
                return START_NOT_STICKY
            }
            promoteToForeground("Cancelling processing...")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Concurrency guard: if another job is already running, reject duplicate start without mutating active state
        synchronized(this) {
            val current = activeJob
            if (current != null && current.state == JobState.RUNNING) {
                Log.w(TAG, "Refusing duplicate start: job ${current.id} is already RUNNING")
                if (!isForegroundStarted) {
                    promoteToForeground("Processing media with VeilFrame...")
                }
                return START_NOT_STICKY
            }
        }

        val inputPath = intent?.getStringExtra(EXTRA_INPUT_PATH) ?: ""
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT_PATH) ?: ""

        val inputUriExtra: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_INPUT_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_INPUT_URI)
        }

        val outputUriExtra: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_OUTPUT_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_OUTPUT_URI)
        }

        val inputUri = inputUriExtra ?: if (inputPath.startsWith("content://") || inputPath.startsWith("file://")) {
            Uri.parse(inputPath)
        } else if (inputPath.isNotEmpty()) {
            Uri.fromFile(File(inputPath))
        } else {
            Uri.EMPTY
        }

        val outputUri = outputUriExtra ?: if (outputPath.startsWith("content://") || outputPath.startsWith("file://")) {
            Uri.parse(outputPath)
        } else if (outputPath.isNotEmpty()) {
            Uri.fromFile(File(outputPath))
        } else null

        val jobIdStr = intent?.getStringExtra(EXTRA_JOB_ID)
        val jobId = try {
            if (!jobIdStr.isNullOrEmpty()) UUID.fromString(jobIdStr) else UUID.randomUUID()
        } catch (_: Exception) {
            UUID.randomUUID()
        }

        val defaultJobType = when (intent?.action) {
            ACTION_PROCESS_MOTION_LAB -> JobType.MOTION_LAB_RIFE
            ACTION_PROCESS_STABILIZATION -> JobType.VIDEO_STABILIZATION
            else -> JobType.VIDEO_COMPRESSION
        }

        val jobTypeStr = intent?.getStringExtra(EXTRA_JOB_TYPE) ?: defaultJobType.name
        val jobType = try {
            JobType.valueOf(jobTypeStr)
        } catch (_: Exception) {
            defaultJobType
        }

        val initialServiceMsg = when (intent?.action) {
            ACTION_PROCESS_MOTION_LAB -> "Synthesizing motion frames with VeilFrame..."
            ACTION_PROCESS_STABILIZATION -> "Stabilizing video with VeilFrame..."
            else -> "Processing media with VeilFrame..."
        }

        val promotionSuccess = promoteToForeground(initialServiceMsg)

        if (!promotionSuccess) {
            synchronized(this) {
                activeJob = ProcessingJob(
                    id = jobId,
                    type = jobType,
                    inputUri = inputUri,
                    outputUri = outputUri,
                    state = JobState.FAILED,
                    errorMessage = "Foreground service promotion failed",
                    startedAt = System.currentTimeMillis()
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val job = ProcessingJob(
            id = jobId,
            type = jobType,
            inputUri = inputUri,
            outputUri = outputUri,
            state = JobState.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        synchronized(this) {
            val current = activeJob
            if (current != null && current.state == JobState.RUNNING) {
                return START_NOT_STICKY
            }
            activeJob = job
        }

        currentExecutionJob = serviceScope.launch {
            try {
                when (intent?.action) {
                    ACTION_PROCESS_MOTION_LAB -> {
                        val method = intent.getStringExtra(EXTRA_MOTION_METHOD) ?: "RIFE"
                        val multiplier = intent.getIntExtra(EXTRA_MOTION_MULTIPLIER, 2)
                        val isTarget60fps = intent.getBooleanExtra(EXTRA_MOTION_TARGET_60FPS, true)
                        executeMotionLab(jobId, inputUri, inputPath, outputPath, method, multiplier, isTarget60fps)
                    }
                    ACTION_PROCESS_STABILIZATION -> {
                        val margin = intent.getIntExtra(EXTRA_STABILIZATION_MARGIN, 5)
                        executeVideoStabilization(jobId, inputUri, inputPath, outputPath, margin)
                    }
                    else -> {
                        executeVideoCleaning(jobId, inputUri, inputPath, outputUri, outputPath)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Foreground processing failed: ${e.message}", e)
                synchronized(this@VeilFrameProcessingService) {
                    if (activeJob?.id == jobId) {
                        activeJob = activeJob?.copy(state = JobState.FAILED, errorMessage = e.message)
                    }
                }
                jobCallback?.onFailed(jobId, e.message ?: "Processing failed")
            } finally {
                val wasSuccess = synchronized(this@VeilFrameProcessingService) { activeJob?.state == JobState.COMPLETED }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(if (wasSuccess) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(!wasSuccess)
                    }
                } catch (_: Exception) {}
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun executeVideoCleaning(
        jobId: UUID,
        inputUri: Uri,
        inputPath: String,
        outputUri: Uri?,
        outputPath: String
    ) {
        val effectiveInPath = if (inputUri.scheme == "content") {
            try {
                FFmpegKitConfig.getSafParameterForRead(this, inputUri)
            } catch (_: Exception) {
                inputPath
            }
        } else {
            inputPath
        }

        val effectiveOutPath = if (outputUri != null && outputUri.scheme == "content") {
            try {
                FFmpegKitConfig.getSafParameterForWrite(this, outputUri)
            } catch (_: Exception) {
                outputPath
            }
        } else {
            outputPath
        }

        if (effectiveInPath.isNotEmpty() && effectiveOutPath.isNotEmpty()) {
            val ok = mediaBackend.cleanVideoCancellable(effectiveInPath, effectiveOutPath)
            synchronized(this) {
                if (activeJob?.id == jobId) {
                    activeJob = if (ok) {
                        activeJob?.copy(state = JobState.COMPLETED, progressPercent = 100)
                    } else {
                        activeJob?.copy(state = JobState.FAILED, errorMessage = "Processing failed in native media backend")
                    }
                }
            }
            if (ok) {
                jobCallback?.onCompleted(jobId, effectiveOutPath)
                showCompletionNotification("Privacy Processing Finished", "Cleaned video ready")
            } else {
                jobCallback?.onFailed(jobId, "Native media backend cleaning failed")
            }
        } else {
            synchronized(this) {
                if (activeJob?.id == jobId) {
                    activeJob = activeJob?.copy(state = JobState.FAILED, errorMessage = "Invalid input or output path")
                }
            }
            jobCallback?.onFailed(jobId, "Invalid input or output path")
        }
    }

    private suspend fun executeMotionLab(
        jobId: UUID,
        inputUri: Uri,
        inputPath: String,
        outputPath: String,
        methodStr: String,
        multiplier: Int,
        isTarget60fps: Boolean
    ) {
        val workDir = File(cacheDir, "motionlab_${System.currentTimeMillis()}").apply { mkdirs() }
        val framesDir = File(workDir, "frames").apply { mkdirs() }
        val outDir = File(workDir, "out").apply { mkdirs() }
        val inFile = File(workDir, "in.mp4")

        try {
            updateNotificationProgress("Preparing video source…", 5)
            jobCallback?.onProgress(jobId, 5, "Preparing source…")

            if (inputUri.scheme == "content") {
                contentResolver.openInputStream(inputUri)?.use { input ->
                    inFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot open input video URI")
            } else if (inputPath.isNotEmpty()) {
                File(inputPath).copyTo(inFile, overwrite = true)
            } else {
                throw IllegalStateException("No valid input video specified")
            }

            CvRuntime.requireAvailable()

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(inFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            runCatching { retriever.release() }

            updateNotificationProgress("Extracting video frames…", 15)
            jobCallback?.onProgress(jobId, 15, "Extracting frames…")

            val maxSeconds = 60
            val extractSession = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-y",
                    "-i", inFile.absolutePath,
                    "-t", maxSeconds.toString(),
                    "-q:v", "2",
                    "${framesDir.absolutePath}/f_%05d.jpg"
                )
            )
            if (!ReturnCode.isSuccess(extractSession.returnCode)) {
                throw IllegalStateException("Frame extraction failed (rc=${extractSession.returnCode})")
            }

            val frames = framesDir.listFiles { f -> f.name.startsWith("f_") && (f.name.endsWith(".jpg") || f.name.endsWith(".png")) }
                ?.sortedBy { it.name } ?: emptyList()
            if (frames.size < 2) throw IllegalStateException("Need at least 2 frames (extracted ${frames.size})")
            if (frames.size > 1800) throw IllegalStateException("${frames.size} frames exceeds the maximum limit of 1800")

            val effectiveDurationS = if (durationMs > 0) minOf(durationMs / 1000.0, maxSeconds.toDouble()) else 0.0
            val fps = if (effectiveDurationS > 0.1) {
                (frames.size / effectiveDurationS).toFloat().coerceIn(10f, 120f)
            } else {
                captureFps ?: 30f
            }

            val effectiveMultiplier = if (isTarget60fps) {
                if (fps <= 20f) 4 else 2
            } else {
                multiplier.coerceIn(2, 4)
            }

            var useRife = false
            if (methodStr.equals("RIFE", ignoreCase = true)) {
                val rifeManager = RifeModelManager(this@VeilFrameProcessingService)
                try {
                    if (!rifeManager.isModelReady()) {
                        updateNotificationProgress("Downloading RIFE AI model…", 18)
                        jobCallback?.onProgress(jobId, 18, "Downloading RIFE AI model…")
                        val dlResult = rifeManager.ensureModelDownloaded { pct, msg ->
                            updateNotificationProgress(msg, (18 + pct * 0.05).toInt())
                            jobCallback?.onProgress(jobId, (18 + pct * 0.05).toInt(), msg)
                        }
                        if (dlResult.isSuccess) {
                            useRife = try {
                                RifeNcnnNative.nativeInit(rifeManager.modelDir.absolutePath, useGpu = true)
                            } catch (t: Throwable) {
                                Log.w(TAG, "RIFE GPU init failed, trying CPU: ${t.message}")
                                try {
                                    RifeNcnnNative.nativeInit(rifeManager.modelDir.absolutePath, useGpu = false)
                                } catch (_: Throwable) { false }
                            }
                        } else {
                            Log.w(TAG, "RIFE model download failed: ${dlResult.exceptionOrNull()?.message}")
                            jobCallback?.onProgress(jobId, 19, "RIFE download unavailable; using optical flow fallback…")
                            useRife = false
                        }
                    } else {
                        useRife = try {
                            RifeNcnnNative.nativeInit(rifeManager.modelDir.absolutePath, useGpu = true)
                        } catch (t: Throwable) {
                            Log.w(TAG, "RIFE GPU init failed, trying CPU: ${t.message}")
                            try {
                                RifeNcnnNative.nativeInit(rifeManager.modelDir.absolutePath, useGpu = false)
                            } catch (_: Throwable) { false }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "RIFE init failed; falling back to optical flow: ${t.message}")
                    useRife = false
                }
            }

            val estimator = if (!useRife) {
                val flowAlgo = if (methodStr.equals("FARNEBACK", ignoreCase = true)) {
                    FlowEstimator.Algorithm.FARNEBACK
                } else {
                    FlowEstimator.Algorithm.DIS
                }
                FlowEstimator(algorithm = flowAlgo)
            } else null
            val synthOptions = if (estimator != null) FrameSynthesizer.Options(estimator = estimator) else null

            fun loadRifeBitmap(file: File): Bitmap? {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                return if (raw.config != Bitmap.Config.ARGB_8888) {
                    val converted = raw.copy(Bitmap.Config.ARGB_8888, false)
                    raw.recycle()
                    converted
                } else {
                    raw
                }
            }

            var outIdx = 0
            var prevBmp: Bitmap? = if (useRife) loadRifeBitmap(frames[0]) else null
            var prevMat: org.opencv.core.Mat? = if (!useRife) Imgcodecs.imread(frames[0].absolutePath, Imgcodecs.IMREAD_COLOR) else null

            if (useRife && prevBmp != null) {
                val outFile = File(outDir, "o_%05d.jpg".format(outIdx++))
                outFile.outputStream().use { fos ->
                    prevBmp!!.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                }
            } else if (prevMat != null && !prevMat.empty()) {
                Imgcodecs.imwrite("${outDir.absolutePath}/o_%05d.jpg".format(outIdx++), prevMat)
            } else {
                throw IllegalStateException("Initial frame unreadable")
            }

            try {
                for (i in 1 until frames.size) {
                    kotlin.coroutines.coroutineContext.ensureActive()

                    var curBmp: Bitmap? = null
                    var curMat: org.opencv.core.Mat? = null

                    if (useRife) {
                        curBmp = loadRifeBitmap(frames[i])
                        if (curBmp == null || prevBmp == null) {
                            curBmp?.recycle()
                            continue
                        }
                    } else {
                        curMat = Imgcodecs.imread(frames[i].absolutePath, Imgcodecs.IMREAD_COLOR)
                        if (curMat == null || curMat.empty()) {
                            curMat?.release()
                            continue
                        }
                    }

                    try {
                        for (k in 1 until effectiveMultiplier) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val t = k.toDouble() / effectiveMultiplier
                            var interpolatedSuccessfully = false

                            if (useRife && prevBmp != null && curBmp != null) {
                                val synthBmp = try {
                                    RifeNcnnNative.nativeInterpolate(prevBmp!!, curBmp, t.toFloat())
                                } catch (e: Throwable) {
                                    Log.w(TAG, "RIFE interpolation failed for frame $i: ${e.message}")
                                    null
                                }
                                if (synthBmp != null) {
                                    val outFile = File(outDir, "o_%05d.jpg".format(outIdx++))
                                    outFile.outputStream().use { fos ->
                                        synthBmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                                    }
                                    synthBmp.recycle()
                                    interpolatedSuccessfully = true
                                } else {
                                    val outFile = File(outDir, "o_%05d.jpg".format(outIdx++))
                                    outFile.outputStream().use { fos ->
                                        prevBmp!!.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                                    }
                                    interpolatedSuccessfully = true
                                }
                            }

                            if (!interpolatedSuccessfully && synthOptions != null && prevMat != null && curMat != null) {
                                val result = FrameSynthesizer.synthesize(prevMat, curMat, t, synthOptions)
                                Imgcodecs.imwrite("${outDir.absolutePath}/o_%05d.jpg".format(outIdx++), result.frame)
                                result.frame.release()
                                result.confidence.release()
                                result.fallbackMask.release()
                            }
                        }
                    } finally {
                        if (useRife && curBmp != null) {
                            val outFile = File(outDir, "o_%05d.jpg".format(outIdx++))
                            outFile.outputStream().use { fos ->
                                curBmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                            }
                            prevBmp?.recycle()
                            prevBmp = curBmp
                        } else if (curMat != null) {
                            Imgcodecs.imwrite("${outDir.absolutePath}/o_%05d.jpg".format(outIdx++), curMat)
                            prevMat?.release()
                            prevMat = curMat
                        }
                    }

                    val synthPct = 25 + ((i.toFloat() / frames.size.toFloat()) * 60f).toInt().coerceIn(0, 60)
                    updateNotificationProgress("Synthesizing frames ($synthPct%)", synthPct)
                    jobCallback?.onProgress(jobId, synthPct, "Synthesizing frames ($synthPct%)")
                }
            } finally {
                if (useRife) {
                    RifeNcnnNative.nativeRelease()
                    prevBmp?.recycle()
                } else {
                    prevMat?.release()
                }
            }

            val finalFps = if (isTarget60fps) 60f else (fps * effectiveMultiplier)
            val framerateStr = if (isTarget60fps) "60.000" else String.format(Locale.US, "%.3f", fps * effectiveMultiplier)
            updateNotificationProgress("Encoding video ($framerateStr fps)…", 88)
            jobCallback?.onProgress(jobId, 88, "Encoding video…")

            val destFile = if (outputPath.isNotEmpty()) {
                File(outputPath)
            } else {
                val exportDir = File(filesDir, "exports").apply { mkdirs() }
                File(exportDir, "motionlab_${System.currentTimeMillis()}.mp4")
            }
            destFile.parentFile?.mkdirs()

            val encodeSession = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-y",
                    "-framerate", framerateStr,
                    "-i", "${outDir.absolutePath}/o_%05d.jpg",
                    "-i", inFile.absolutePath,
                    "-map", "0:v",
                    "-map", "1:a?",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "21",
                    "-pix_fmt", "yuv420p",
                    "-c:a", "copy",
                    "-shortest",
                    destFile.absolutePath
                )
            )
            if (!ReturnCode.isSuccess(encodeSession.returnCode) || !destFile.exists() || destFile.length() == 0L) {
                throw IllegalStateException("Video encoding failed (rc=${encodeSession.returnCode})")
            }

            synchronized(this) {
                if (activeJob?.id == jobId) {
                    activeJob = activeJob?.copy(state = JobState.COMPLETED, progressPercent = 100)
                }
            }
            jobCallback?.onProgress(jobId, 100, "Complete")
            jobCallback?.onCompleted(jobId, destFile.absolutePath)
            showCompletionNotification("Motion Lab Finished", "Synthesized video ready")
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun executeVideoStabilization(
        jobId: UUID,
        inputUri: Uri,
        inputPath: String,
        outputPath: String,
        marginPercent: Int
    ) {
        var tempInFile: File? = null
        try {
            updateNotificationProgress("Stabilizing video…", 10)
            jobCallback?.onProgress(jobId, 10, "Analyzing motion…")

            val effectiveInPath = if (inputUri.scheme == "content") {
                val safPath = try {
                    FFmpegKitConfig.getSafParameterForRead(this, inputUri)
                } catch (_: Exception) {
                    null
                }
                if (!safPath.isNullOrBlank()) {
                    safPath
                } else {
                    val temp = File(cacheDir, "temp_stab_${System.currentTimeMillis()}.mp4")
                    contentResolver.openInputStream(inputUri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Cannot open input video for stabilization")
                    tempInFile = temp
                    temp.absolutePath
                }
            } else if (inputPath.isNotEmpty()) {
                inputPath
            } else {
                throw IllegalStateException("No valid input video specified for stabilization")
            }

            val effectiveOutPath = if (outputPath.isNotEmpty()) {
                outputPath
            } else {
                val exportDir = File(filesDir, "exports").apply { mkdirs() }
                File(exportDir, "stabilized_${System.currentTimeMillis()}.mp4").absolutePath
            }

            CvRuntime.requireAvailable()

            val clampedMargin = marginPercent.coerceIn(0, 20)
            val deshakeFilter = VideoStabilizerEngine.generateFfmpegDeshakeFilter(clampedMargin)

            val session = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-y",
                    "-i", effectiveInPath,
                    "-vf", deshakeFilter,
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "20",
                    "-c:a", "copy",
                    effectiveOutPath
                )
            )

            val ok = ReturnCode.isSuccess(session.returnCode) && File(effectiveOutPath).exists() && File(effectiveOutPath).length() > 0L
            if (!ok) {
                throw IllegalStateException("Video stabilization failed (rc=${session.returnCode})")
            }

            synchronized(this) {
                if (activeJob?.id == jobId) {
                    activeJob = activeJob?.copy(state = JobState.COMPLETED, progressPercent = 100)
                }
            }
            jobCallback?.onProgress(jobId, 100, "Complete")
            jobCallback?.onCompleted(jobId, effectiveOutPath)
            showCompletionNotification("Video Stabilizer Finished", "Stabilized video ready")
        } catch (e: Exception) {
            synchronized(this) {
                if (activeJob?.id == jobId) {
                    activeJob = activeJob?.copy(state = JobState.FAILED, errorMessage = e.message)
                }
            }
            jobCallback?.onFailed(jobId, e.message ?: "Stabilization failed")
            throw e
        } finally {
            tempInFile?.delete()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        currentExecutionJob?.cancel()
        synchronized(this) {
            if (activeJob?.state == JobState.RUNNING) {
                activeJob = activeJob?.copy(state = JobState.FAILED, errorMessage = "Processing timed out by Android system")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isForegroundStarted = false
        currentExecutionJob?.cancel()
        serviceScope.cancel()
        if (activeJob?.state == JobState.RUNNING) {
            activeJob = activeJob?.copy(state = JobState.CANCELLED)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VeilFrame Processing Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress during video sanitization, synthesis and stabilization"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotificationProgress(contentText: String, percent: Int) {
        val notification = buildForegroundNotification(contentText, percent)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildForegroundNotification(contentText: String, progressPercent: Int = -1): Notification {
        val cancelIntent = android.app.PendingIntent.getService(
            this,
            0,
            Intent(this, VeilFrameProcessingService::class.java).setAction(ACTION_CANCEL_JOB),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VeilFrame Processing Engine")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        if (progressPercent in 0..100) {
            builder.setProgress(100, progressPercent, false)
        } else {
            builder.setProgress(100, 0, true)
        }
        return builder.build()
    }

    private fun showCompletionNotification(title: String, message: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = android.app.PendingIntent.getActivity(
            this,
            10,
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$message • Tap Open to view.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_view, "Open", openPendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }
}
