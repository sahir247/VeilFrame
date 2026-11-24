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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.motion.FlowEstimator
import com.veilframe.app.cv.motion.FrameSynthesizer
import com.veilframe.app.cv.motion.MotionLabTimingContract
import com.veilframe.app.cv.motion.MotionLabSequenceVerifier
import com.veilframe.app.cv.motion.MotionLabRawFramePipe
import com.veilframe.app.cv.motion.MotionLabDiskBudget
import com.veilframe.app.cv.motion.MotionLabAudioTimeline
import com.veilframe.app.cv.motion.VideoStabilizerEngine
import com.veilframe.app.media.AndroidMediaBackend
import com.veilframe.app.media.JobState
import com.veilframe.app.media.JobType
import com.veilframe.app.media.ProcessingJob
import com.veilframe.app.media.rife.RifeModelManager
import com.veilframe.app.media.rife.RifeNcnnNative
import com.veilframe.app.media.executeOwnedFfmpeg
import kotlinx.coroutines.CancellationException
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.core.Mat
import org.opencv.android.Utils
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
            if (jobId != null && current?.id != jobId) return
            if (current == null || current.state != JobState.RUNNING) {
                if (jobId != null) return
                try {
                    context.stopService(Intent(context, VeilFrameProcessingService::class.java))
                } catch (_: Exception) {}
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
            // Keep the owner alive until its native session has drained.
            // Its coroutine finally stops the service.
            if (currentExecutionJob?.isCompleted != false) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                promoteToForeground("Cancelling processing...")
            }
            return START_NOT_STICKY
        }

        // Concurrency guard: if another job is already running, reject duplicate start without mutating active state
        synchronized(this) {
            val current = activeJob
            if (currentExecutionJob?.isCompleted == false || (current != null && current.state == JobState.RUNNING)) {
                Log.w(TAG, "Refusing duplicate start: prior processing session is still owned")
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
            } catch (cancelled: CancellationException) {
                synchronized(this@VeilFrameProcessingService) {
                    if (activeJob?.id == jobId && activeJob?.state == JobState.RUNNING) {
                        activeJob = activeJob?.copy(state = JobState.CANCELLED)
                    }
                }
                throw cancelled
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
            val result = mediaBackend.cleanVideoCancellableDetailed(effectiveInPath, effectiveOutPath)
            kotlin.coroutines.coroutineContext.ensureActive()
            val ok = result.isSuccess
            val fallbackWarning = if (
                result.status == com.veilframe.app.privacy.VideoSanitizationStatus.METADATA_ONLY_FALLBACK
            ) "Metadata-only fallback completed; requested temporal noise filtering was not applied." else null
            synchronized(this) {
                if (activeJob?.id == jobId) {
                    activeJob = if (ok) {
                        activeJob?.copy(
                            state = JobState.COMPLETED,
                            progressPercent = 100,
                            errorMessage = fallbackWarning
                        )
                    } else {
                        activeJob?.copy(
                            state = JobState.FAILED,
                            errorMessage = result.errorMessage ?: "Processing failed in native media backend"
                        )
                    }
                }
            }
            if (ok) {
                jobCallback?.onProgress(jobId, 100, fallbackWarning ?: "Complete")
                jobCallback?.onCompleted(jobId, effectiveOutPath)
                if (fallbackWarning != null) {
                    showCompletionNotification("Metadata-only fallback", fallbackWarning)
                } else {
                    showCompletionNotification("Video Transcode Finished", "Processed video ready; camera PRNU removal is not independently verified")
                }
            } else {
                jobCallback?.onFailed(jobId, result.errorMessage ?: "Native media backend cleaning failed")
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
        val workDir = File(cacheDir, "motionlab_${UUID.randomUUID()}").apply { mkdirs() }
        val inFile = File(workDir, "in.mp4")
        var stagedOutput: File? = null

        try {
            MotionLabDiskBudget.requireHeadroom(workDir)
            updateNotificationProgress("Preparing video source…", 5)
            jobCallback?.onProgress(jobId, 5, "Preparing source…")

            if (inputUri.scheme == "content" || inputUri.scheme == "file") {
                contentResolver.openInputStream(inputUri)?.use { input ->
                    MotionLabDiskBudget.stageInput(input, inFile, workDir)
                } ?: throw IllegalStateException("Cannot open input video URI")
            } else if (inputPath.isNotEmpty()) {
                val resolvedPath = if (inputPath.startsWith("file://")) inputPath.removePrefix("file://") else inputPath
                val source = File(resolvedPath)
                check(source.isFile && source.length() > 0) { "Video source unavailable" }
                MotionLabDiskBudget.requireHeadroom(workDir, source.length())
                source.inputStream().use { MotionLabDiskBudget.stageInput(it, inFile, workDir) }
            } else {
                throw IllegalStateException("No valid input video specified")
            }

            MotionLabDiskBudget.requireHeadroom(workDir)
            CvRuntime.requireAvailable()

            val retriever = MediaMetadataRetriever()
            val durationMs = try {
                retriever.setDataSource(inFile.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: throw IllegalStateException("Video duration unavailable; refusing to guess motion timing")
            } finally {
                retriever.release()
            }
            // Audit source packet presentation timestamps before extracting any frames.
            // Resample the source presentation timeline onto the requested output grid.
            val sourceVideoPts = readMotionVideoPtsUs(inFile)
            // Validate every audio track before expensive synthesis begins.
            val sourceAudioTracks = readMotionAudioTracks(inFile)
            val timing = MotionLabTimingContract.plan(
                presentationUs = sourceVideoPts,
                durationMs = durationMs,
                target60Fps = isTarget60fps,
                requestedMultiplier = multiplier,
                videoDurationUs = readMotionVideoDurationUs(inFile)
            )

            val sourceGeometry = readMotionSourceGeometry(inFile)
            updateNotificationProgress("Preparing streaming decoder…", 15)
            jobCallback?.onProgress(jobId, 15, "Preparing streaming decoder…")

            val destFile = if (outputPath.isNotEmpty()) File(outputPath) else {
                val exportDir = File(filesDir, "exports").apply { mkdirs() }
                File(exportDir, "motionlab_${System.currentTimeMillis()}.mp4")
            }
            val destinationDirectory = requireNotNull(destFile.absoluteFile.parentFile)
            check(destinationDirectory.isDirectory || destinationDirectory.mkdirs()) { "Cannot create Motion Lab destination" }
            val stage = File(destinationDirectory, ".motion_${UUID.randomUUID()}.mp4")
            stagedOutput = stage
            // Consume the entire raw stream to EOF before remuxing audio. An
            // output -t on the pipe can cause the encoder to stop reading early.
            val videoOnly = if (sourceAudioTracks.isEmpty()) stage else File(workDir, "encoded_video.mp4")
            var useRife = false
            if (methodStr.equals("RIFE", ignoreCase = true)) {
                val rifeManager = RifeModelManager(this@VeilFrameProcessingService)
                val selectedModel = rifeManager.getSelectedModel()
                if (rifeManager.verifyModelIntegrity(selectedModel)) {
                    val attemptGpu = !RifeNcnnNative.isEmulator()
                    val requiredPadding = rifeManager.requiredPadding
                    fun initializeRife(gpu: Boolean): Boolean = try {
                        RifeNcnnNative.nativeInit(rifeManager.modelDir.absolutePath, useGpu = gpu, padding = requiredPadding)
                    } catch (failure: Exception) {
                        Log.w(TAG, "RIFE initialization failed (GPU=$gpu)", failure)
                        false
                    } catch (failure: LinkageError) {
                        Log.w(TAG, "RIFE JNI unavailable", failure)
                        false
                    }
                    // JNI reports failed initialization as false as well as throwing.
                    // CPU must be attempted before falling back to a different algorithm.
                    useRife = com.veilframe.app.cv.motion.MotionLabBackendSelection.initialize(attemptGpu, ::initializeRife)
                } else {
                    Log.w(TAG, "RIFE model ${selectedModel.id} unavailable or failed cryptographic checksum; falling back to optical flow")
                    jobCallback?.onProgress(jobId, 18, "RIFE model unverified; using optical flow fallback…")
                    useRife = false
                }
            }

            // A successful RIFE initialization must not disable the promised optical-flow
            // fallback. Initialize one estimator for the whole job, not per frame.
            val flowAlgo = if (methodStr.equals("FARNEBACK", ignoreCase = true)) {
                FlowEstimator.Algorithm.FARNEBACK
            } else FlowEstimator.Algorithm.DIS
            val flowEstimator = FlowEstimator(algorithm = flowAlgo)
            val synthOptions = FrameSynthesizer.Options(estimator = flowEstimator)

            var opticalFlowFallbackFrames = 0
            var prevBmp: Bitmap? = null
            var prevMat: Mat? = null
            // Native RIFE may already be initialized here. Start its guaranteed
            // retirement scope BEFORE decoding or writing the first frame.
            try {
                val plan = sourceGeometry.plan(useRife)
                val geometry = plan.geometry
                val encodeSession = MotionLabDiskBudget.monitor(check = {
                    MotionLabDiskBudget.requireHeadroom(workDir)
                    MotionLabDiskBudget.requireHeadroom(destinationDirectory)
                }) {
                    MotionLabRawFramePipe.encode(
                        geometry, timing.outputFps, timing.expectedOutputFrames,
                        arrayOf("-map", "0:v:0", "-an", "-c:v", "libx264",
                            "-preset", "veryfast", "-crf", "21",
                            "-vf", plan.encodeFilter,
                            "-pix_fmt", "yuv420p", "-fps_mode", "passthrough",
                            videoOnly.absolutePath)
                    ) {
                        val output = this
                        MotionLabRawFramePipe.decode(geometry, timing.sourceFrames,
                            arrayOf("-noautorotate", "-i", inFile.absolutePath, "-vf", plan.decodeFilter),
                            expectedPresentationUs = sourceVideoPts
                        ) {
                            val input = this
                            prevBmp = if (useRife) input.readBitmap() else null
                            prevMat = if (!useRife) input.readMat() else null
                            for (i in 1 until timing.sourceFrames) {
                                kotlin.coroutines.coroutineContext.ensureActive()

                                var curBmp: Bitmap? = null
                                var curMat: Mat? = null
                                var fallbackA: Mat? = null
                                var fallbackB: Mat? = null
                                var pairFlows: FlowEstimator.BidirectionalFlow? = null

                                if (useRife) {
                                    curBmp = input.readBitmap()
                                    if (curBmp == null || prevBmp == null) {
                                        curBmp?.recycle()
                                        throw IllegalStateException("Video frame ${i + 1} could not be decoded; export aborted to preserve the timeline")
                                    }
                                } else {
                                    curMat = input.readMat()
                                    if (curMat == null || curMat.empty()) {
                                        curMat?.release()
                                        throw IllegalStateException("Video frame ${i + 1} could not be decoded; export aborted to preserve the timeline")
                                    }
                                }

                                try {
                                    for (t in timing.pairFractions[i-1]) {
                                        kotlin.coroutines.coroutineContext.ensureActive()
                                        MotionLabDiskBudget.requireHeadroom(workDir)
                                        if (t == 0.0) {
                                            if (useRife) output.writeBitmap(requireNotNull(prevBmp))
                                            else output.writeMat(requireNotNull(prevMat))
                                            continue
                                        }
                                        var interpolatedSuccessfully = false

                                        if (useRife && prevBmp != null && curBmp != null) {
                                            val synthBmp = try {
                                                RifeNcnnNative.nativeInterpolate(prevBmp!!, curBmp, t.toFloat())
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                Log.w(TAG, "RIFE interpolation failed for frame $i: ${e.message}")
                                                null
                                            } catch (e: LinkageError) {
                                                Log.w(TAG, "RIFE interpolation unavailable", e)
                                                null
                                            }
                                            if (synthBmp != null) {
                                                try {
                                                    output.writeBitmap(synthBmp)
                                                    interpolatedSuccessfully = true
                                                } finally {
                                                    if (synthBmp !== prevBmp && synthBmp !== curBmp) synthBmp.recycle()
                                                }
                                            } else {
                                                Log.w(TAG, "RIFE returned null for frame $i (t=$t); attempting optical flow fallback")
                                            }
                                        }

                                        if (!interpolatedSuccessfully) {
                                            // Decode each bitmap to Mat at most once per input pair, even
                                            // if several RIFE subframes require optical-flow recovery.
                                            val matA = prevMat ?: fallbackA ?: bitmapToMotionBgr(
                                                prevBmp ?: error("Missing previous RIFE frame")
                                            ).also { fallbackA = it }
                                            val matB = curMat ?: fallbackB ?: bitmapToMotionBgr(
                                                curBmp ?: error("Missing current RIFE frame")
                                            ).also { fallbackB = it }
                                            if (matA.empty() || matB.empty()) {
                                                throw IllegalStateException("Optical-flow fallback input unavailable for frame ${i + 1}")
                                            }
                                            val result = try {
                                                // Optical flow and source frames do not change between
                                                // k=1..factor-1: estimate once per pair, not once per
                                                // synthesized frame. A RIFE failure lazily populates it.
                                                val flows = pairFlows ?: flowEstimator.estimate(matA, matB)
                                                    .also { pairFlows = it }
                                                FrameSynthesizer.synthesizeWithFlows(matA, matB, t, flows, synthOptions)
                                            } catch (e: Exception) {
                                                throw IllegalStateException("Both RIFE and optical flow failed at frame ${i + 1} (t=$t)", e)
                                            }
                                            try {
                                                output.writeMat(result.frame)
                                                if (useRife) opticalFlowFallbackFrames++
                                            } finally {
                                                result.release()
                                            }
                                        }
                                    }
                                    // Publication belongs to the success path. Transfer ownership
                                    // only after staging succeeds, never from a finally block.
                                    kotlin.coroutines.coroutineContext.ensureActive()
                                    MotionLabDiskBudget.requireHeadroom(workDir)
                                    if (useRife && curBmp != null) {
                                        prevBmp?.recycle()
                                        prevBmp = curBmp
                                        curBmp = null
                                    } else if (curMat != null) {
                                        prevMat?.release()
                                        prevMat = curMat
                                        curMat = null
                                    } else {
                                        error("Missing current motion frame")
                                    }
                                } finally {
                                    pairFlows?.release()
                                    fallbackA?.release()
                                    fallbackB?.release()
                                    curBmp?.recycle()
                                    curMat?.release()
                                }

                                MotionLabDiskBudget.requireHeadroom(workDir)
                                val synthPct = 25 + ((i.toFloat() / timing.sourceFrames.toFloat()) * 60f).toInt().coerceIn(0, 60)
                                updateNotificationProgress("Synthesizing frames ($synthPct%)", synthPct)
                                jobCallback?.onProgress(jobId, synthPct, "Synthesizing frames ($synthPct%)")
                            }
                        // Sample-and-hold the final source image for its actual tail interval.
                        repeat(timing.tailFrames) {
                            if (useRife) output.writeBitmap(requireNotNull(prevBmp))
                            else output.writeMat(requireNotNull(prevMat))
                        }
                        }
                    }
                }
                check(ReturnCode.isSuccess(encodeSession.returnCode) && videoOnly.isFile && videoOnly.length() > 0L) {
                    "Raw video encoding failed"
                }
            } finally {
                try {
                    if (useRife) RifeNcnnNative.nativeRelease()
                } finally {
                    prevBmp?.recycle()
                    prevMat?.release()
                }
            }

            if (opticalFlowFallbackFrames > 0) {
                Log.w(TAG, "RIFE recovered $opticalFlowFallbackFrames intermediate frames with optical flow")
            }
            // Verify the encoded video before remux. The remux must also pass
            // the same frame count/PTS contract, so it cannot truncate video.
            val encodedPts = readMotionVideoPtsUs(videoOnly, timing.expectedOutputFrames)
            MotionLabSequenceVerifier.verifyPresentation(encodedPts, timing.expectedOutputFrames, timing.outputFps)
            if (sourceAudioTracks.isNotEmpty()) {
                updateNotificationProgress("Remuxing source audio…", 88)
                jobCallback?.onProgress(jobId, 88, "Remuxing source audio…")
                val mux = MotionLabDiskBudget.monitor(check = {
                    MotionLabDiskBudget.requireHeadroom(workDir)
                    MotionLabDiskBudget.requireHeadroom(destinationDirectory)
                }) {
                    executeOwnedFfmpeg(arrayOf(
                        "-nostdin", "-y", "-i", videoOnly.absolutePath, "-i", inFile.absolutePath,
                        "-map", "0:v:0", "-map", "1:a", "-c", "copy",
                        "-t", String.format(Locale.US, "%.6f", timing.expectedOutputFrames / timing.outputFps),
                        "-movflags", "+faststart", stage.absolutePath
                    ))
                }
                check(ReturnCode.isSuccess(mux.returnCode) && stage.isFile && stage.length() > 0L) {
                    "Motion Lab audio remux failed"
                }
            }
            // Inspect the actual serialized MP4, not just a successful FFmpeg exit.
            // This also detects output-timeline truncation or extra CFR frames.
            val publishedPts = readMotionVideoPtsUs(stage, timing.expectedOutputFrames)
            MotionLabSequenceVerifier.verifyPresentation(
                publishedPts,
                expectedFrames = timing.expectedOutputFrames,
                expectedFps = timing.outputFps
            )
            val outputRetriever = MediaMetadataRetriever()
            val outputDurationMs = try {
                outputRetriever.setDataSource(stage.absolutePath)
                outputRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: throw IllegalStateException("Encoded MP4 has no readable duration")
            } finally {
                outputRetriever.release()
            }
            check(kotlin.math.abs(outputDurationMs - durationMs) <= 200L) {
                "Motion output duration $outputDurationMs ms disagrees with input $durationMs ms"
            }
            // An apparently complete video is still a bad export if a source
            // audio stream was dropped or shifted during stream-copy remuxing.
            MotionLabAudioTimeline.verifyTracks(
                source = sourceAudioTracks,
                output = readMotionAudioTracks(stage),
                sourceVideoFirstUs = sourceVideoPts.minOrNull() ?: 0L,
                outputVideoFirstUs = publishedPts.minOrNull() ?: 0L,
                durationMs = durationMs
            )

            kotlin.coroutines.coroutineContext.ensureActive()
            synchronized(this) {
                if (activeJob?.id != jobId || activeJob?.state != JobState.RUNNING) {
                    throw CancellationException("Motion Lab operation no longer owns publication")
                }
                java.nio.file.Files.move(stage.toPath(), destFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                stagedOutput = null
                activeJob = activeJob?.copy(state = JobState.COMPLETED, progressPercent = 100)
            }
            val completion = when {
                methodStr.equals("RIFE", ignoreCase = true) && !useRife ->
                    "Complete: optical flow used because RIFE was unavailable"
                opticalFlowFallbackFrames > 0 ->
                    "Complete: $opticalFlowFallbackFrames RIFE frames recovered with optical flow"
                useRife -> "Complete: RIFE interpolation succeeded"
                else -> "Complete: optical-flow interpolation succeeded"
            }
            jobCallback?.onProgress(jobId, 100, completion)
            jobCallback?.onCompleted(jobId, destFile.absolutePath)
            showCompletionNotification("Motion Lab Finished", completion)
        } finally {
            stagedOutput?.delete()
            workDir.deleteRecursively()
        }
    }

    /** Android Bitmap -> RGBA Mat -> explicit BGR Mat for OpenCV optical flow.
     * BitmapToMat returns RGBA, whereas FrameSynthesizer and raw encoding
     * expect standard BGR. Keep channel contracts consistent across fallbacks.
     */
    private fun bitmapToMotionBgr(bitmap: Bitmap): Mat {
        val rgba = Mat()
        val bgr = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            check(!rgba.empty() && rgba.channels() == 4) {
                "RIFE fallback bitmap conversion did not produce RGBA data"
            }
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            check(!bgr.empty() && bgr.channels() == 3) {
                "Failed to convert RIFE fallback bitmap to BGR"
            }
            return bgr
        } catch (t: Throwable) {
            bgr.release()
            throw t
        } finally {
            rgba.release()
        }
    }

    private suspend fun readMotionAudioTracks(input: File): List<MotionLabAudioTimeline.Track> {
        val tracks = MediaExtractor().let { extractor ->
            try {
                extractor.setDataSource(input.absolutePath)
                (0 until extractor.trackCount).mapNotNull { index ->
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("audio/") == true) index to mime else null
                }
            } finally {
                extractor.release()
            }
        }
        check(tracks.size <= MotionLabAudioTimeline.MAX_AUDIO_TRACKS) { "Too many audio tracks" }
        return tracks.map { (index, mime) ->
            kotlin.coroutines.coroutineContext.ensureActive()
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.absolutePath)
                extractor.selectTrack(index)
                var firstPts = Long.MAX_VALUE
                var lastPts = -1L
                var count = 0
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val ptsUs = extractor.sampleTime
                    if (ptsUs < 0L) break
                    check(count < MotionLabAudioTimeline.MAX_AUDIO_SAMPLES) {
                        "Audio track exceeds bounded verification budget"
                    }
                    firstPts = minOf(firstPts, ptsUs)
                    lastPts = maxOf(lastPts, ptsUs)
                    count++
                    if (!extractor.advance()) break
                }
                check(count > 0 && firstPts >= 0L) { "Audio stream has no readable samples" }
                MotionLabAudioTimeline.Track(mime, MotionLabAudioTimeline.AudioSpan(firstPts, lastPts, count))
            } finally {
                extractor.release()
            }
        }
    }

    private fun readMotionVideoDurationUs(input: File): Long? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val video = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("Input has no video track")
            return if (video.containsKey(MediaFormat.KEY_DURATION)) video.getLong(MediaFormat.KEY_DURATION) else null
        } finally { extractor.release() }
    }

    private fun readMotionSourceGeometry(input: File): com.veilframe.app.cv.motion.MotionLabSourceGeometry {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("Input has no video track")
            fun optionalInt(key: String, default: Int) = if (format.containsKey(key)) format.getInteger(key) else default
            return com.veilframe.app.cv.motion.MotionLabSourceGeometry(
                format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT),
                optionalInt("rotation-degrees", 0), optionalInt("sar-width",1), optionalInt("sar-height",1)
            )
        } finally { extractor.release() }
    }

    private suspend fun readMotionVideoPtsUs(
        input: File,
        maxFrames: Int = MotionLabTimingContract.MAX_SOURCE_FRAMES
    ): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("Input has no video track")
            extractor.selectTrack(videoTrack)
            val timestamps = ArrayList<Long>()
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val pts = extractor.sampleTime
                if (pts < 0) break
                if (timestamps.size >= maxFrames) {
                    throw IllegalStateException("Video has more than $maxFrames frames; timing verification refused")
                }
                timestamps.add(pts)
                if (!extractor.advance()) break
            }
            return timestamps
        } finally {
            extractor.release()
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

            val session = executeOwnedFfmpeg(
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
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
        synchronized(this) {
            activeJob = activeJob?.copy(progressPercent = percent)
        }
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

        val dest = when (activeJob?.type) {
            JobType.MOTION_LAB_RIFE -> "MOTION_LAB"
            JobType.UPSCALE -> "IMAGE_UPSCALER"
            JobType.VIDEO_COMPRESSION, JobType.WHATSAPP_STATUS, JobType.METADATA_STRIP, JobType.VIDEO_STABILIZATION -> "VIDEO_STUDIO"
            else -> null
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (dest != null) {
                putExtra("extra_navigate_destination", dest)
                activeJob?.inputUri?.let { putExtra("extra_video_uri", it.toString()) }
            }
        }
        val openPendingIntent = android.app.PendingIntent.getActivity(
            this,
            1,
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VeilFrame Processing Engine")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        if (progressPercent in 0..100) {
            builder.setProgress(100, progressPercent, false)
        } else {
            builder.setProgress(100, 0, true)
        }
        return builder.build()
    }

    private fun showCompletionNotification(title: String, message: String) {
        val dest = when (activeJob?.type) {
            JobType.MOTION_LAB_RIFE -> "MOTION_LAB"
            JobType.UPSCALE -> "IMAGE_UPSCALER"
            JobType.VIDEO_COMPRESSION, JobType.WHATSAPP_STATUS, JobType.METADATA_STRIP, JobType.VIDEO_STABILIZATION -> "VIDEO_STUDIO"
            else -> null
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (dest != null) {
                putExtra("extra_navigate_destination", dest)
                activeJob?.inputUri?.let { putExtra("extra_video_uri", it.toString()) }
            }
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
