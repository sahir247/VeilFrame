package com.veilframe.app.cv.motion

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.DISOpticalFlow
import org.opencv.video.Video

/**
 * Dense optical-flow estimation for the FPS/interpolation engine.
 *
 * Primary algorithm:   DIS optical flow (fast, good quality).
 * Fallback:            Farnebäck (always available in the core video module).
 * High-quality option: Dual TV-L1 — only when an optflow-enabled OpenCV build
 *                      is packaged; resolved reflectively so the module stays
 *                      compilable against the pinned main-module build.
 *
 * The estimator produces BIDIRECTIONAL flow (A→B and B→A) — the consistency
 * checker and occlusion detector depend on both directions.
 */
class FlowEstimator(
    private val algorithm: Algorithm = Algorithm.DIS,
    private val workingMaxEdge: Int = 960,
) {
    enum class Algorithm { DIS, FARNEBACK, AUTO }

    /** One direction of dense flow: CV_32FC2, one vector per pixel. */
    data class FlowField(
        val flow: Mat,
        /** Scale between the flow resolution and the original frame. */
        val scale: Double,
    ) {
        fun release() {
            flow.release()
        }
    }

    data class BidirectionalFlow(
        val forward: FlowField,
        val backward: FlowField,
        val algorithmUsed: Algorithm,
    ) {
        fun release() {
            forward.release()
            backward.release()
        }
    }

    /** Estimates A→B and B→A flow on a downscaled working image. */
    fun estimate(previous: Mat, current: Mat): BidirectionalFlow {
        require(!previous.empty() && !current.empty()) { "frames must be non-empty" }
        require(previous.size() == current.size()) { "frames must have identical size" }

        val prevGray = toGray(previous)
        val nextGray = toGray(current)
        val prevWork = downscale(prevGray)
        val nextWork = downscale(nextGray)
        val scale = prevWork.cols().toDouble() / prevGray.cols()
        try {
            val chosen = when (algorithm) {
                Algorithm.DIS, Algorithm.FARNEBACK -> algorithm
                Algorithm.AUTO -> Algorithm.DIS
            }
            val forward = computeFlow(prevWork, nextWork, chosen)
            val backward = computeFlow(nextWork, prevWork, chosen)
            return BidirectionalFlow(
                forward = FlowField(forward, scale),
                backward = FlowField(backward, scale),
                algorithmUsed = chosen,
            )
        } finally {
            prevGray.release()
            nextGray.release()
            prevWork.release()
            nextWork.release()
        }
    }

    private fun computeFlow(from: Mat, to: Mat, algorithm: Algorithm): Mat = when (algorithm) {
        Algorithm.DIS, Algorithm.AUTO -> disFlow(from, to)
        Algorithm.FARNEBACK -> farnebackFlow(from, to)
    }

    private fun disFlow(from: Mat, to: Mat): Mat {
        val flow = Mat()
        val dis = obtainDis()
        return try {
            if (dis == null) {
                flow.release()
                farnebackFlow(from, to)
            } else {
                // The cached instance is reusable but not thread-safe.
                synchronized(dis) { dis.calc(from, to, flow) }
                flow
            }
        } catch (e: Exception) {
            // Any DIS failure (missing symbols, invalid input) → Farnebäck.
            flow.release()
            farnebackFlow(from, to)
        }
    }

    private fun farnebackFlow(from: Mat, to: Mat): Mat {
        val flow = Mat()
        Video.calcOpticalFlowFarneback(
            from,
            to,
            flow,
            0.5,  // pyrScale
            4,    // levels
            21,   // winsize
            3,    // iterations
            5,    // polyN
            1.2,  // polySigma
            0,
        )
        return flow
    }

    /**
     * Dual TV-L1 via reflection when the optflow contrib module is present.
     * Returns null when unavailable — callers fall back to [estimate].
     */
    fun tvL1Flow(from: Mat, to: Mat): Mat? {
        val flow = Mat()
        return try {
            val clazz = Class.forName("org.opencv.optflow.DualTVL1OpticalFlow")
            val create = clazz.getMethod("create")
            val instance = create.invoke(null)
            val calc = clazz.getMethod("calc", Mat::class.java, Mat::class.java, Mat::class.java)
            calc.invoke(instance, from, to, flow)
            flow
        } catch (e: Exception) {
            // Release on the failure path too — the old try-expression leaked
            // the freshly-allocated Mat whenever reflection failed.
            flow.release()
            null
        } catch (e: LinkageError) {
            flow.release()
            null
        }
    }

    private fun toGray(frame: Mat): Mat = when (frame.channels()) {
        1 -> frame.clone()
        else -> {
            val gray = Mat()
            Imgproc.cvtColor(
                frame,
                gray,
                if (frame.channels() == 4) Imgproc.COLOR_BGRA2GRAY else Imgproc.COLOR_BGR2GRAY,
            )
            gray
        }
    }

    companion object {
        /**
         * DISOpticalFlow.create() allocates non-trivial internal state and the
         * instance is reusable across pairs but NOT thread-safe — cache exactly
         * one and serialize all calc() calls on it (was: created per pair, twice
         * per estimate() call).
         */
        private val disLock = Any()

        @Volatile
        private var cachedDis: DISOpticalFlow? = null

        @Volatile
        private var disUnavailable = false

        private fun obtainDis(): DISOpticalFlow? = synchronized(disLock) {
            when {
                disUnavailable -> null
                cachedDis != null -> cachedDis
                else -> try {
                    DISOpticalFlow.create(DISOpticalFlow.PRESET_MEDIUM).also { cachedDis = it }
                } catch (e: Exception) {
                    disUnavailable = true
                    null
                } catch (e: LinkageError) {
                    disUnavailable = true
                    null
                }
            }
        }
    }

    private fun downscale(gray: Mat): Mat {
        val maxEdge = maxOf(gray.cols(), gray.rows())
        if (maxEdge <= workingMaxEdge) return gray.clone()
        val out = Mat()
        val scaleFactor = workingMaxEdge.toDouble() / maxEdge
        Imgproc.resize(
            gray,
            out,
            Size(
                maxOf(1, Math.round(gray.cols() * scaleFactor).toInt()).toDouble(),
                maxOf(1, Math.round(gray.rows() * scaleFactor).toInt()).toDouble(),
            ),
            0.0, 0.0, Imgproc.INTER_AREA,
        )
        return out
    }
}
