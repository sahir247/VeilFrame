package com.veilframe.app.upscale.inference.qnn

/**
 * Validates whether the bundled ONNX Runtime version supports the Plugin EP ABI
 * required by the downloadable Qualcomm QNN Plugin EP package.
 *
 * Current QNN 2.6.0 Plugin EP releases require ONNX Runtime >= 1.24.1.
 */
object QnnRuntimeCompatibility {

    const val MINIMUM_ORT_VERSION = "1.24.1"
    const val RECOMMENDED_ORT_VERSION = "1.27.0"
    const val TARGET_QNN_PLUGIN_VERSION = "2.6.0"
    const val TARGET_QAIRT_VERSION = "2.50.0"

    /**
     * Checks if the given ORT version string meets or exceeds the minimum required version.
     */
    fun isOrtCompatible(ortVersion: String): Boolean {
        return compareVersions(ortVersion, MINIMUM_ORT_VERSION) >= 0
    }

    /**
     * Compares two semantic version strings (e.g. "1.20.0" vs "1.24.1").
     * Returns:
     *  < 0 if v1 < v2
     *  0 if v1 == v2
     *  > 0 if v1 > v2
     */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split('.').mapNotNull { it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val parts2 = v2.split('.').mapNotNull { it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }
}
