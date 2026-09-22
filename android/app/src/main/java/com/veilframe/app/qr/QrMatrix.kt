package com.veilframe.app.qr

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode

/**
 * Wraps a ZXing [QRCode] and exposes module classification metadata
 * (position patterns, alignment patterns, timing patterns, data modules)
 * mirroring EFQRCode's QRPointType taxonomy.
 *
 * All coordinates use col=x (horizontal), row=y (vertical), 0-indexed.
 */
class QrMatrix(val content: String, val ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M) {

    enum class ModuleType {
        DATA, POS_CENTER, POS_OTHER, ALIGN_CENTER, ALIGN_OTHER, TIMING
    }

    private val qrCode: QRCode = Encoder.encode(content, ecLevel, null)
    private val matrix = qrCode.matrix

    val size: Int = matrix.width

    /** True if module at (col, row) is dark. */
    fun isDark(col: Int, row: Int): Boolean =
        if (col < 0 || row < 0 || col >= size || row >= size) false
        else matrix[col, row].toInt() != 0

    /** Classification table, lazily built once. */
    private val typeTable: Array<Array<ModuleType>> by lazy { buildTypeTable() }

    fun typeAt(col: Int, row: Int): ModuleType = typeTable[col][row]

    private fun buildTypeTable(): Array<Array<ModuleType>> {
        val t = Array(size) { Array(size) { ModuleType.DATA } }
        val version = qrCode.version?.versionNumber ?: 1

        // Timing strips
        for (i in 8 until size - 8) {
            t[6][i] = ModuleType.TIMING
            t[i][6] = ModuleType.TIMING
        }

        // Alignment patterns (skip those overlapping finder patterns)
        val ap = getAlignmentPatternCenters(version)
        for (ay in ap) {
            for (ax in ap) {
                if ((ax == 6 && ay == 6) ||
                    (ax == 6 && ay == ap.last()) ||
                    (ax == ap.last() && ay == 6)
                ) continue
                for (dy in -2..2) for (dx in -2..2) {
                    if (ax + dx in 0 until size && ay + dy in 0 until size) {
                        t[ax + dx][ay + dy] =
                            if (dx == 0 && dy == 0) ModuleType.ALIGN_CENTER else ModuleType.ALIGN_OTHER
                    }
                }
            }
        }

        // Position detection patterns (finder patterns)
        markFinderPattern(t, 3, 3)          // top-left
        markFinderPattern(t, 3, size - 4)   // bottom-left
        markFinderPattern(t, size - 4, 3)   // top-right

        return t
    }

    private fun markFinderPattern(t: Array<Array<ModuleType>>, cx: Int, cy: Int) {
        for (dy in -3..3) for (dx in -3..3) {
            val x = cx + dx; val y = cy + dy
            if (x < 0 || y < 0 || x >= size || y >= size) continue
            t[x][y] = if (dx == 0 && dy == 0) ModuleType.POS_CENTER else ModuleType.POS_OTHER
        }
    }

    private fun getAlignmentPatternCenters(version: Int): List<Int> {
        if (version == 1) return emptyList()
        val first = 6
        val last = 4 * version + 10
        if (version == 2) return listOf(first, last)
        val numSteps = version / 7 + 1
        val step = ((last - first) + numSteps - 1) / numSteps
        val positions = mutableListOf(first)
        var cur = last
        while (cur > first) {
            positions.add(1, cur)
            cur -= step
        }
        return positions.distinct().sorted()
    }
}
