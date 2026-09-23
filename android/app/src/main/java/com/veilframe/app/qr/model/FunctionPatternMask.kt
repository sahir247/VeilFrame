package com.veilframe.app.qr.model

/**
 * Classifies modules in a QR matrix into canonical functional patterns
 * per ISO/IEC 18004 specification.
 *
 * Finder patterns, separators, timing strips, alignment patterns, format information,
 * and version information are strictly protected from disruptive artistic modifications.
 */
enum class FunctionPatternType {
    FINDER_CORE,      // 3x3 center of finder pattern
    FINDER_LIGHT,     // 5x5 light ring inside finder pattern
    FINDER_OUTER,     // 7x7 dark outer border of finder pattern
    SEPARATOR,        // 1-module light border around finders
    TIMING,           // Row 6 and Column 6 alternating timing strips
    ALIGNMENT_CENTER, // Center module of alignment pattern
    ALIGNMENT_OTHER,  // 5x5 ring around alignment center
    FORMAT,           // Format information areas (around finders)
    VERSION,          // Version information blocks (Version >= 7)
    DATA              // Regular data and error correction codewords
}

class FunctionPatternMask(val size: Int, val version: Int) {

    private val mask = Array(size) { Array(size) { FunctionPatternType.DATA } }

    init {
        buildMask()
    }

    operator fun get(col: Int, row: Int): FunctionPatternType {
        if (col !in 0 until size || row !in 0 until size) return FunctionPatternType.DATA
        return mask[col][row]
    }

    /**
     * Maps the module at (col, row) to canonical [QrModuleRole].
     */
    fun roleAt(col: Int, row: Int): QrModuleRole {
        if (col !in 0 until size || row !in 0 until size) return QrModuleRole.QUIET_ZONE
        return when (mask[col][row]) {
            FunctionPatternType.FINDER_CORE,
            FunctionPatternType.FINDER_LIGHT -> QrModuleRole.FINDER_INNER
            FunctionPatternType.FINDER_OUTER -> QrModuleRole.FINDER_OUTER
            FunctionPatternType.SEPARATOR -> QrModuleRole.SEPARATOR
            FunctionPatternType.TIMING -> QrModuleRole.TIMING
            FunctionPatternType.ALIGNMENT_CENTER -> QrModuleRole.ALIGNMENT_CENTER
            FunctionPatternType.ALIGNMENT_OTHER -> QrModuleRole.ALIGNMENT_BORDER
            FunctionPatternType.FORMAT -> QrModuleRole.FORMAT
            FunctionPatternType.VERSION -> QrModuleRole.VERSION
            FunctionPatternType.DATA -> QrModuleRole.DATA
        }
    }

    /**
     * Returns true if the module at (col, row) is part of a protected functional pattern
     * that must maintain strict contrast and geometry for barcode decoders.
     */
    fun isProtected(col: Int, row: Int): Boolean {
        if (col !in 0 until size || row !in 0 until size) return false
        val type = mask[col][row]
        return type != FunctionPatternType.DATA
    }

    /**
     * Returns true if (col, row) belongs specifically to a position detection (finder) pattern.
     */
    fun isFinder(col: Int, row: Int): Boolean {
        if (col !in 0 until size || row !in 0 until size) return false
        val type = mask[col][row]
        return type == FunctionPatternType.FINDER_CORE ||
                type == FunctionPatternType.FINDER_LIGHT ||
                type == FunctionPatternType.FINDER_OUTER
    }

    /**
     * Returns true if (col, row) is part of the 1-module quiet separator around finder patterns.
     */
    fun isSeparator(col: Int, row: Int): Boolean {
        if (col !in 0 until size || row !in 0 until size) return false
        return mask[col][row] == FunctionPatternType.SEPARATOR
    }

    private fun buildMask() {
        // 1. Mark Finders and Separators in Top-Left, Bottom-Left, Top-Right
        markFinderWithSeparator(0, 0)
        markFinderWithSeparator(0, size - 7)
        markFinderWithSeparator(size - 7, 0)

        // 2. Mark Timing Strips (col 6 and row 6 between finders)
        for (i in 8 until size - 8) {
            if (mask[6][i] == FunctionPatternType.DATA) mask[6][i] = FunctionPatternType.TIMING
            if (mask[i][6] == FunctionPatternType.DATA) mask[i][6] = FunctionPatternType.TIMING
        }

        // 3. Mark Alignment Patterns (Version >= 2)
        if (version >= 2) {
            val centers = getAlignmentPatternCenters(version)
            for (cy in centers) {
                for (cx in centers) {
                    // Skip alignment patterns that overlap finder/separator regions
                    if ((cx == 6 && cy == 6) ||
                        (cx == 6 && cy == centers.last()) ||
                        (cx == centers.last() && cy == 6)
                    ) continue

                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val x = cx + dx
                            val y = cy + dy
                            if (x in 0 until size && y in 0 until size && mask[x][y] == FunctionPatternType.DATA) {
                                mask[x][y] = if (dx == 0 && dy == 0) {
                                    FunctionPatternType.ALIGNMENT_CENTER
                                } else {
                                    FunctionPatternType.ALIGNMENT_OTHER
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Mark Format Information (15 bits around finders)
        markFormatInfo()

        // 5. Mark Version Information (Version >= 7, 3x6 modules above BL and left of TR)
        if (version >= 7) {
            markVersionInfo()
        }
    }

    private fun markFinderWithSeparator(startCol: Int, startRow: Int) {
        // 9x9 bounding box including 1-module separator
        for (dy in -1..7) {
            for (dx in -1..7) {
                val col = startCol + dx
                val row = startRow + dy
                if (col !in 0 until size || row !in 0 until size) continue

                if (dx in 0..6 && dy in 0..6) {
                    // Inside 7x7 Finder
                    val distFromEdge = minOf(dx, 6 - dx, dy, 6 - dy)
                    mask[col][row] = when (distFromEdge) {
                        0 -> FunctionPatternType.FINDER_OUTER
                        1 -> FunctionPatternType.FINDER_LIGHT
                        else -> FunctionPatternType.FINDER_CORE
                    }
                } else {
                    // 1-module light separator
                    mask[col][row] = FunctionPatternType.SEPARATOR
                }
            }
        }
    }

    private fun markFormatInfo() {
        // Around top-left finder
        for (i in 0..8) {
            if (i != 6 && mask[i][8] == FunctionPatternType.DATA) mask[i][8] = FunctionPatternType.FORMAT
            if (i != 6 && mask[8][i] == FunctionPatternType.DATA) mask[8][i] = FunctionPatternType.FORMAT
        }
        // Around bottom-left finder
        for (i in 0..7) {
            val r = size - 1 - i
            if (mask[8][r] == FunctionPatternType.DATA) mask[8][r] = FunctionPatternType.FORMAT
        }
        // Around top-right finder
        for (i in 0..7) {
            val c = size - 1 - i
            if (mask[c][8] == FunctionPatternType.DATA) mask[c][8] = FunctionPatternType.FORMAT
        }
    }

    private fun markVersionInfo() {
        // Top-right 3x6 block (left of TR finder: cols size-11..size-9, rows 0..5)
        for (c in size - 11 until size - 8) {
            for (r in 0..5) {
                if (mask[c][r] == FunctionPatternType.DATA) mask[c][r] = FunctionPatternType.VERSION
            }
        }
        // Bottom-left 6x3 block (above BL finder: cols 0..5, rows size-11..size-9)
        for (c in 0..5) {
            for (r in size - 11 until size - 8) {
                if (mask[c][r] == FunctionPatternType.DATA) mask[c][r] = FunctionPatternType.VERSION
            }
        }
    }

    private fun getAlignmentPatternCenters(version: Int): List<Int> {
        if (version <= 1) return emptyList()
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
