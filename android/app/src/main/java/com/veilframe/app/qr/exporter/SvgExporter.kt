package com.veilframe.app.qr.exporter

import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrMatrix

/**
 * Resolution-independent Vector SVG Exporter.
 *
 * Generates true vector markup (<svg>, <rect>, <circle>, <path>) directly from
 * [QrMatrix] and [QrDesign] without rasterizing to PNG.
 * Includes the 4-module Quiet Zone in the viewBox.
 */
object SvgExporter {

    fun generateSvg(matrix: QrMatrix, design: QrDesign): String {
        val qz = design.quietZoneModules
        val totalSize = matrix.size + (2 * qz)
        val fgHex = String.format("#%06X", 0xFFFFFF and design.palette.foreground)
        val bgHex = String.format("#%06X", 0xFFFFFF and design.palette.background)

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $totalSize $totalSize" width="100%" height="100%">""").append("\n")

        // 1. Background
        sb.append("""  <rect width="$totalSize" height="$totalSize" fill="$bgHex" />""").append("\n")

        // 2. Finders (TL: 0,0; BL: 0, n-7; TR: n-7, 0) offset by qz
        val finders = listOf(
            Pair(qz, qz),
            Pair(qz, qz + matrix.size - 7),
            Pair(qz + matrix.size - 7, qz)
        )

        for ((fx, fy) in finders) {
            when (design.eyeStyle.style) {
                FinderStyle.CIRCLE -> {
                    val cx = fx + 3.5
                    val cy = fy + 3.5
                    sb.append("""  <circle cx="$cx" cy="$cy" r="3" fill="none" stroke="$fgHex" stroke-width="1" />""").append("\n")
                    sb.append("""  <circle cx="$cx" cy="$cy" r="1.5" fill="$fgHex" />""").append("\n")
                }
                else -> {
                    // Canonical 7x7 outer, 5x5 light, 3x3 inner
                    sb.append("""  <rect x="$fx" y="$fy" width="7" height="7" fill="$fgHex" rx="0.5" />""").append("\n")
                    sb.append("""  <rect x="${fx + 1}" y="${fy + 1}" width="5" height="5" fill="$bgHex" rx="0.3" />""").append("\n")
                    sb.append("""  <rect x="${fx + 2}" y="${fy + 2}" width="3" height="3" fill="$fgHex" rx="0.2" />""").append("\n")
                }
            }
        }

        // 3. Data & Functional Modules
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)
        val shape = design.moduleStyle.shape

        for (col in 0 until matrix.size) {
            for (row in 0 until matrix.size) {
                if (!matrix.isDark(col, row)) continue
                if (matrix.functionMask.isFinder(col, row) || matrix.functionMask.isSeparator(col, row)) {
                    continue
                }

                val x = col + qz
                val y = row + qz

                when (shape) {
                    ModuleShape.CIRCLE, ModuleShape.DOT -> {
                        val r = (scale / 2.0) * (if (shape == ModuleShape.DOT) 0.75 else 1.0)
                        val cx = x + 0.5
                        val cy = y + 0.5
                        sb.append("""  <circle cx="$cx" cy="$cy" r="$r" fill="$fgHex" />""").append("\n")
                    }
                    ModuleShape.ROUNDED -> {
                        val offset = (1.0 - scale) / 2.0
                        val rx = scale * 0.25
                        sb.append("""  <rect x="${x + offset}" y="${y + offset}" width="$scale" height="$scale" rx="$rx" fill="$fgHex" />""").append("\n")
                    }
                    else -> {
                        val offset = (1.0 - scale) / 2.0
                        sb.append("""  <rect x="${x + offset}" y="${y + offset}" width="$scale" height="$scale" fill="$fgHex" />""").append("\n")
                    }
                }
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }
}
