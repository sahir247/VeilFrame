package com.veilframe.app.cv.core

import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * CvContracts — centralized, deterministic parameter and Mat contract assertions
 * for all VeilFrame CV modules.
 *
 * Enforces uniform preconditions before operations cross into OpenCV native code:
 * - Buffer states (non-empty, matching dimensions, color spaces, depths, flow types)
 * - Numeric parameters (finite, non-negative, odd kernel sizes, valid ranges)
 */
object CvContracts {

    /** Requires [mat] to be non-empty (rows > 0, cols > 0, allocated buffer). */
    fun requireNonEmpty(mat: Mat, name: String = "mat") {
        require(!mat.empty() && mat.rows() > 0 && mat.cols() > 0) {
            "$name must be non-empty (got ${mat.rows()}x${mat.cols()})"
        }
    }

    /** Requires [mat] to be 8-bit unsigned (CV_8U). */
    fun require8Bit(mat: Mat, name: String = "mat") {
        require(mat.depth() == CvType.CV_8U) {
            "$name must have 8-bit unsigned depth (depth ${mat.depth()})"
        }
    }

    /** Requires [mat] to be a single-channel 8-bit mask or grayscale image (CV_8UC1). */
    fun requireGray8(mat: Mat, name: String = "mat") {
        require(mat.type() == CvType.CV_8UC1) {
            "$name must be single-channel 8-bit (CV_8UC1, type ${mat.type()})"
        }
    }

    /** Requires [mat] to be a 3-channel 8-bit BGR image (CV_8UC3). */
    fun requireBgr8(mat: Mat, name: String = "mat") {
        require(mat.type() == CvType.CV_8UC3) {
            "$name must be 3-channel 8-bit (CV_8UC3, type ${mat.type()})"
        }
    }

    /** Requires [mat] to be a 2-channel 32-bit floating point flow field (CV_32FC2). */
    fun requireFlow32FC2(mat: Mat, name: String = "flow") {
        require(mat.type() == CvType.CV_32FC2) {
            "$name must be a 2-channel 32-bit float vector field (CV_32FC2, type ${mat.type()})"
        }
    }

    /** Requires [a] and [b] to have identical width and height. */
    fun requireSameSize(a: Mat, b: Mat, nameA: String = "first", nameB: String = "second") {
        require(a.rows() == b.rows() && a.cols() == b.cols()) {
            "$nameA size (${a.cols()}x${a.rows()}) must match $nameB size (${b.cols()}x${b.rows()})"
        }
    }

    /** Requires [value] to be a finite number (not NaN, not Infinite). */
    fun requireFinite(value: Double, name: String) {
        require(value.isFinite()) { "$name must be finite (got $value)" }
    }

    /** Requires [value] to be a finite float (not NaN, not Infinite). */
    fun requireFinite(value: Float, name: String) {
        require(value.isFinite()) { "$name must be finite (got $value)" }
    }

    /** Requires [value] to be strictly positive (> 0). */
    fun requirePositive(value: Int, name: String) {
        require(value > 0) { "$name must be strictly positive (got $value)" }
    }

    /** Requires [value] to be strictly positive and finite (> 0.0). */
    fun requirePositive(value: Double, name: String) {
        require(value.isFinite() && value > 0.0) { "$name must be strictly positive and finite (got $value)" }
    }

    /** Requires [value] to be non-negative (>= 0). */
    fun requireNonNegative(value: Int, name: String) {
        require(value >= 0) { "$name must be non-negative (got $value)" }
    }

    /** Requires [value] to be non-negative and finite (>= 0.0). */
    fun requireNonNegative(value: Double, name: String) {
        require(value.isFinite() && value >= 0.0) { "$name must be non-negative and finite (got $value)" }
    }

    /** Requires [value] to be within [[min], [max]] inclusive. */
    fun requireInRange(value: Double, min: Double, max: Double, name: String) {
        require(value.isFinite() && value in min..max) {
            "$name must be within [$min, $max] (got $value)"
        }
    }

    /** Requires [value] to be an odd positive integer (3, 5, 7, ...). */
    fun requireOddPositive(value: Int, name: String) {
        require(value > 0 && value % 2 == 1) {
            "$name must be an odd positive integer (got $value)"
        }
    }
}
