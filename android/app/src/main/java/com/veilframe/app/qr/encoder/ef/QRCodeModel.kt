package com.veilframe.app.qr.encoder.ef

import kotlin.math.abs

/**
 * Pure Kotlin implementation of QRCodeSwift's `QRCodeModel`.
 *
 * Implements 8-bit byte mode encoding, exact Reed-Solomon interleaving,
 * qrcode.js `lostPoint` penalty score mask selection, and the canonical
 * `getTypeTable()` semantic module point classifier.
 */
class QRCodeModel(
    val data: ByteArray,
    val errorCorrectLevel: EfCorrectionLevel,
    val needTypeTable: Boolean = true
) {
    val typeNumber: Int = QRCodeType.typeNumber(data.size, errorCorrectLevel)
    val moduleCount: Int = typeNumber * 4 + 17

    private var modules: Array<Array<Boolean?>> = Array(moduleCount) { arrayOfNulls(moduleCount) }
    private val dataCache: IntArray
    private val position: MutableList<IntArray> = mutableListOf()
    val bestMaskPattern: EfMaskPattern

    init {
        dataCache = createData(typeNumber, errorCorrectLevel, data)
        bestMaskPattern = findBestMaskPattern()
        makeImpl(isTest = false, maskPattern = bestMaskPattern)
    }

    fun isDark(row: Int, col: Int): Boolean {
        if (row !in 0 until moduleCount || col !in 0 until moduleCount) return false
        return modules[row][col] == true
    }

    fun isLight(row: Int, col: Int): Boolean = !isDark(row, col)

    private fun makeImpl(isTest: Boolean, maskPattern: EfMaskPattern) {
        modules = Array(moduleCount) { arrayOfNulls(moduleCount) }
        setupPositionProbePattern(0, 0)
        setupPositionProbePattern(moduleCount - 7, 0)
        setupPositionProbePattern(0, moduleCount - 7)
        setupPositionAdjustPattern()
        setupTimingPattern()
        setupTypeInfo(isTest, maskPattern.rawValue)
        if (typeNumber >= 7) {
            setupTypeNumber(isTest)
        }
        mapData(dataCache, maskPattern)
    }

    private fun setupPositionProbePattern(row: Int, col: Int) {
        for (r in -1..7) {
            if (row + r <= -1 || moduleCount <= row + r) continue
            for (c in -1..7) {
                if (col + c <= -1 || moduleCount <= col + c) continue
                if ((0 <= r && r <= 6 && (c == 0 || c == 6)) ||
                    (0 <= c && c <= 6 && (r == 0 || r == 6)) ||
                    (2 <= r && r <= 4 && 2 <= c && c <= 4)
                ) {
                    modules[row + r][col + c] = true
                } else {
                    modules[row + r][col + c] = false
                }
            }
        }
    }

    private fun setupTimingPattern() {
        for (i in 8 until moduleCount - 8) {
            if (modules[i][6] == null) {
                modules[i][6] = (i % 2 == 0)
            }
            if (modules[6][i] == null) {
                modules[6][i] = (i % 2 == 0)
            }
        }
    }

    private fun setupPositionAdjustPattern() {
        val pos = QRPatternLocator[typeNumber]
        if (needTypeTable) {
            position.clear()
        }

        for (i in pos.indices) {
            for (j in pos.indices) {
                val row = pos[i]
                val col = pos[j]
                if (modules[row][col] != null) continue

                if (needTypeTable) {
                    position.add(intArrayOf(row, col))
                }

                for (r in -2..2) {
                    for (c in -2..2) {
                        if (r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0)) {
                            modules[row + r][col + c] = true
                        } else {
                            modules[row + r][col + c] = false
                        }
                    }
                }
            }
        }
    }

    private fun setupTypeNumber(isTest: Boolean) {
        val bits = BCH.typeNumber(typeNumber)
        for (i in 0 until 18) {
            val mod = (!isTest && ((bits shr i) and 1) == 1)
            modules[i / 3][i % 3 + moduleCount - 8 - 3] = mod
            modules[i % 3 + moduleCount - 8 - 3][i / 3] = mod
        }
    }

    private fun setupTypeInfo(isTest: Boolean, maskPattern: Int) {
        val typeData = (errorCorrectLevel.pattern shl 3) or maskPattern
        val bits = BCH.typeInfo(typeData)
        for (i in 0 until 15) {
            val mod = !isTest && ((bits shr i) and 1) == 1

            if (i < 6) {
                modules[i][8] = mod
            } else if (i < 8) {
                modules[i + 1][8] = mod
            } else {
                modules[moduleCount - 15 + i][8] = mod
            }

            if (i < 8) {
                modules[8][moduleCount - i - 1] = mod
            } else if (i < 9) {
                modules[8][15 - i - 1 + 1] = mod
            } else {
                modules[8][15 - i - 1] = mod
            }
        }
        modules[moduleCount - 8][8] = !isTest
    }

    private fun mapData(data: IntArray, maskPattern: EfMaskPattern) {
        var inc = -1
        var row = moduleCount - 1
        var bitIndex = 7
        var byteIndex = 0

        var col = moduleCount - 1
        while (col > 0) {
            if (col == 6) {
                col -= 1
            }
            while (true) {
                for (c in 0 until 2) {
                    if (modules[row][col - c] == null) {
                        var dark = false
                        if (byteIndex < data.size) {
                            dark = (((data[byteIndex].toLong() and 0xFFL) ushr bitIndex) and 1L) == 1L
                        }
                        val mask = maskPattern.getMask(row, col - c)
                        if (mask) {
                            dark = !dark
                        }
                        modules[row][col - c] = dark
                        bitIndex -= 1
                        if (bitIndex == -1) {
                            byteIndex += 1
                            bitIndex = 7
                        }
                    }
                }
                row += inc
                if (row < 0 || moduleCount <= row) {
                    row -= inc
                    inc = -inc
                    break
                }
            }
            col -= 2
        }
    }

    private fun findBestMaskPattern(): EfMaskPattern {
        var minLostPoint = 0
        var pattern = 0
        for (i in 0 until 8) {
            makeImpl(isTest = true, maskPattern = EfMaskPattern.entries[i])
            val lost = calculateLostPoint()
            if (i == 0 || minLostPoint > lost) {
                minLostPoint = lost
                pattern = i
            }
        }
        return EfMaskPattern.entries[pattern]
    }

    val lostPoint: Int get() = calculateLostPoint()

    private fun calculateLostPoint(): Int {
        var lostPoint = 0
        for (row in 0 until moduleCount) {
            for (col in 0 until moduleCount) {
                var sameCount = 0
                val dark = isDark(row, col)
                for (r in -1..1) {
                    val nr = row + r
                    if (nr !in 0 until moduleCount) continue
                    for (c in -1..1) {
                        val nc = col + c
                        if (nc !in 0 until moduleCount) continue
                        if (r == 0 && c == 0) continue
                        if (dark == isDark(nr, nc)) {
                            sameCount++
                        }
                    }
                }
                if (sameCount > 5) {
                    lostPoint += (3 + sameCount - 5)
                }
            }
        }

        for (row in 0 until moduleCount - 1) {
            for (col in 0 until moduleCount - 1) {
                var count = 0
                if (isDark(row, col)) count++
                if (isDark(row + 1, col)) count++
                if (isDark(row, col + 1)) count++
                if (isDark(row + 1, col + 1)) count++
                if (count == 0 || count == 4) {
                    lostPoint += 3
                }
            }
        }

        for (row in 0 until moduleCount) {
            for (col in 0 until moduleCount - 6) {
                if (isDark(row, col) &&
                    isLight(row, col + 1) &&
                    isDark(row, col + 2) &&
                    isDark(row, col + 3) &&
                    isDark(row, col + 4) &&
                    isLight(row, col + 5) &&
                    isDark(row, col + 6)
                ) {
                    lostPoint += 40
                }
                if (isDark(col, row) &&
                    isLight(col + 1, row) &&
                    isDark(col + 2, row) &&
                    isDark(col + 3, row) &&
                    isDark(col + 4, row) &&
                    isLight(col + 5, row) &&
                    isDark(col + 6, row)
                ) {
                    lostPoint += 40
                }
            }
        }

        var darkCount = 0
        for (col in 0 until moduleCount) {
            for (row in 0 until moduleCount) {
                if (isDark(row, col)) {
                    darkCount++
                }
            }
        }
        val ratio = abs(100 * darkCount / moduleCount / moduleCount - 50) / 5
        lostPoint += ratio * 10
        return lostPoint
    }

    /**
     * Exact semantic type table matching QRCodeSwift's `getTypeTable()`.
     */
    fun getTypeTable(): Array<Array<QRPointType>> {
        val nCount = moduleCount
        val pd = arrayOf(
            intArrayOf(3, 3),
            intArrayOf(3, nCount - 4),
            intArrayOf(nCount - 4, 3)
        )

        val table = Array(nCount) { Array(nCount) { QRPointType.DATA } }

        // 1. Timing
        for (i in 8 until nCount - 7) {
            table[i][6] = QRPointType.TIMING
            table[6][i] = QRPointType.TIMING
        }

        // 2. Alignment
        for (i in position.indices) {
            table[position[i][0]][position[i][1]] = QRPointType.ALIGN_CENTER
            for (r in -2..2) {
                for (c in -2..2) {
                    if (!(r == 0 && c == 0)) {
                        table[position[i][0] + r][position[i][1] + c] = QRPointType.ALIGN_OTHER
                    }
                }
            }
        }

        // 3. Finders & Separators (Strictly 1 center module per finder!)
        for (i in pd.indices) {
            table[pd[i][0]][pd[i][1]] = QRPointType.POS_CENTER
            for (r in -4..4) {
                for (c in -4..4) {
                    val pr = pd[i][0] + r
                    val pc = pd[i][1] + c
                    if (pr in 0 until nCount && pc in 0 until nCount) {
                        if (!(r == 0 && c == 0)) {
                            table[pr][pc] = QRPointType.POS_OTHER
                        }
                    }
                }
            }
        }

        // 4. Format
        for (i in 0..8) {
            if (i != 6) {
                table[i][8] = QRPointType.FORMAT
                table[8][i] = QRPointType.FORMAT
            }
            if (i < 7) {
                table[nCount - i - 1][8] = QRPointType.FORMAT
            }
            if (i < 8) {
                table[8][nCount - i - 1] = QRPointType.FORMAT
            }
        }

        // 5. Version
        for (i in (nCount - 11)..(nCount - 9)) {
            for (j in 0..5) {
                table[i][j] = QRPointType.VERSION
                table[j][i] = QRPointType.VERSION
            }
        }

        return table
    }

    companion object {
        private const val PAD0 = 0xEC
        private const val PAD1 = 0x11

        private fun createData(
            typeNumber: Int,
            errorCorrectLevel: EfCorrectionLevel,
            data: ByteArray
        ): IntArray {
            val rsBlocks = QRRSBlock.getRSBlocks(typeNumber, errorCorrectLevel)
            val buffer = QRBitBuffer()

            // Mode: 8-bit byte mode = 0b0100
            buffer.put(0b0100L, 4)

            // Length indicator
            val lengthBits = if (typeNumber in 1..9) 8 else 16
            buffer.put(data.size.toLong(), lengthBits)

            // Data bytes
            for (b in data) {
                buffer.put((b.toInt() and 0xFF).toLong(), 8)
            }

            val totalBitCount = 8 * rsBlocks.sumOf { it.dataCount }
            require(buffer.bitCount <= totalBitCount) {
                "Data bit count (${buffer.bitCount}) exceeds capacity ($totalBitCount)"
            }

            // Terminator
            if (buffer.bitCount + 4 <= totalBitCount) {
                buffer.put(0L, 4)
            }

            // Pad to byte boundary
            while (buffer.bitCount % 8 != 0) {
                buffer.put(false)
            }

            // Pad bytes
            while (true) {
                if (buffer.bitCount >= totalBitCount) break
                buffer.put(PAD0.toLong(), 8)
                if (buffer.bitCount >= totalBitCount) break
                buffer.put(PAD1.toLong(), 8)
            }

            return createBytes(buffer, rsBlocks)
        }

        private fun createBytes(buffer: QRBitBuffer, rsBlocks: List<QRRSBlock>): IntArray {
            var offset = 0
            var maxDcCount = 0
            var maxEcCount = 0

            val dcdata = mutableListOf<IntArray>()
            val ecdata = mutableListOf<IntArray>()

            for (r in rsBlocks.indices) {
                val dcCount = rsBlocks[r].dataCount
                val ecCount = rsBlocks[r].totalCount - dcCount
                maxDcCount = maxOf(maxDcCount, dcCount)
                maxEcCount = maxOf(maxEcCount, ecCount)

                val blockDc = IntArray(dcCount) { (buffer.buffer[it + offset] and 0xFF) }
                dcdata.add(blockDc)
                offset += dcCount

                val rsPoly = QRPolynomial.errorCorrectPolynomial(ecCount)
                val rawPoly = QRPolynomial(blockDc, shift = rsPoly.count - 1)
                val modPoly = rawPoly.moded(rsPoly)

                val ecdataCount = rsPoly.count - 1
                val blockEc = IntArray(ecdataCount) { idx ->
                    val modIndex = idx + modPoly.count - ecdataCount
                    if (modIndex >= 0) modPoly[modIndex] else 0
                }
                ecdata.add(blockEc)
            }

            val totalCodeCount = rsBlocks.sumOf { it.totalCount }
            val data = IntArray(totalCodeCount)
            var index = 0

            for (i in 0 until maxDcCount) {
                for (r in rsBlocks.indices) {
                    if (i < dcdata[r].size) {
                        data[index++] = dcdata[r][i]
                    }
                }
            }

            for (i in 0 until maxEcCount) {
                for (r in rsBlocks.indices) {
                    if (i < ecdata[r].size) {
                        data[index++] = ecdata[r][i]
                    }
                }
            }

            return data
        }
    }
}
