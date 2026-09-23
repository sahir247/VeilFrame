package com.veilframe.app.qr.renderer

import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrModuleRole
import java.util.Random
import kotlin.math.sqrt

/**
 * Geometric primitives produced by [BubbleClusterEngine].
 *
 * All coordinates and dimensions are in normalized QR module units (1 module = 1.0f).
 * Has zero dependencies on Android Canvas or SVG graphics subsystems.
 */
data class ClusterPrimitive(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val isCross: Boolean = false,
    val hasInnerDot: Boolean = false,
    val innerRadius: Float = 0f,
    val strokeWidthRatio: Float = 0f,
    val isAmbient: Boolean = false,
    val isSolid: Boolean = false
)

/**
 * Pure topological clustering algorithm based on EFQRCode's multi-module grouping.
 *
 * Transforms the discrete binary QR grid into a hierarchical set of circular cluster primitives:
 * 1. 3x3 Crosses: 5 dark data modules -> R = 1.0f macro-bubble with outer ring and inner core
 * 2. 2x2 Squares: 4 dark data modules -> R = sqrt(0.5) ≈ 0.7071f macro-bubble
 * 3. 1x2 / 2x1 Pairs: 2 adjacent dark data modules -> R = 0.5f bubble
 * 4. Singletons: isolated dark data modules -> R = 0.25f..0.5f solid circular dots
 * 5. Ambient Micro-Bubbles: light data modules -> seeded deterministic ambient micro-bubbles
 *
 * Functional patterns (Finders, Separators, Timing, Alignment, Format, Version) are strictly protected
 * and never consumed or modified.
 */
object BubbleClusterEngine {

    fun computeClusters(matrix: QrMatrix, design: QrDesign): List<ClusterPrimitive> {
        val n = matrix.size
        val rnd = Random(design.clusterStyle.seed)

        // Track consumption of modules
        val available = Array(n) { BooleanArray(n) }
        val ava2 = Array(n) { BooleanArray(n) }

        // Initialize: Only non-protected DATA modules are available for clustering
        for (r in 0 until n) {
            for (c in 0 until n) {
                val isData = matrix.roleAt(c, r) == QrModuleRole.DATA && !matrix.isProtected(c, r)
                available[c][r] = isData
                ava2[c][r] = isData
            }
        }

        val pointList = ArrayList<ClusterPrimitive>()
        val g1 = ArrayList<ClusterPrimitive>() // Macro clusters (3x3 crosses, 2x2 squares)
        val g2 = ArrayList<ClusterPrimitive>() // Ambient micro-bubbles

        val crossOuterStroke = design.clusterStyle.crossOuterStrokeRatio
        val pairStroke = design.clusterStyle.pairStrokeRatio

        for (y in 0 until n) {
            for (x in 0 until n) {
                // Skip if not a data module
                if (matrix.roleAt(x, y) != QrModuleRole.DATA || matrix.isProtected(x, y)) {
                    continue
                }

                // 1. 3x3 Cross detection
                if (available[x][y] && ava2[x][y] && x < n - 2 && y < n - 2) {
                    var canFormCross = true
                    for (i in 0 until 3) {
                        for (j in 0 until 3) {
                            if (!ava2[x + i][y + j]) {
                                canFormCross = false
                                break
                            }
                        }
                        if (!canFormCross) break
                    }

                    if (canFormCross &&
                        matrix.isDark(x + 1, y) &&
                        matrix.isDark(x + 1, y + 2) &&
                        matrix.isDark(x, y + 1) &&
                        matrix.isDark(x + 2, y + 1)
                    ) {
                        val strokeW = 0.33f + rnd.nextFloat() * 0.27f // [0.33..0.60]
                        val isCenterDark = matrix.isDark(x + 1, y + 1)
                        val innerR = if (isCenterDark) 0.5f * (0.5f + rnd.nextFloat() * 0.5f) else 0f

                        g1.add(
                            ClusterPrimitive(
                                cx = x + 1.5f,
                                cy = y + 1.5f,
                                radius = 1.0f,
                                isCross = true,
                                hasInnerDot = isCenterDark,
                                innerRadius = innerR,
                                strokeWidthRatio = strokeW,
                                isSolid = false
                            )
                        )

                        available[x + 1][y] = false
                        available[x][y + 1] = false
                        available[x + 2][y + 1] = false
                        available[x + 1][y + 2] = false
                        available[x + 1][y + 1] = false

                        for (i in 0 until 3) {
                            for (j in 0 until 3) {
                                ava2[x + i][y + j] = false
                            }
                        }
                    }
                }

                // 2. 2x2 Square detection
                if (x < n - 1 && y < n - 1) {
                    if (available[x][y] && available[x + 1][y] && available[x][y + 1] && available[x + 1][y + 1] &&
                        matrix.isDark(x, y) && matrix.isDark(x + 1, y) && matrix.isDark(x, y + 1) && matrix.isDark(x + 1, y + 1)
                    ) {
                        val strokeW = 0.33f + rnd.nextFloat() * 0.27f
                        g1.add(
                            ClusterPrimitive(
                                cx = x + 1.0f,
                                cy = y + 1.0f,
                                radius = sqrt(0.5f), // ~0.7071f
                                strokeWidthRatio = strokeW,
                                isSolid = false
                            )
                        )

                        for (i in 0 until 2) {
                            for (j in 0 until 2) {
                                available[x + i][y + j] = false
                                ava2[x + i][y + j] = false
                            }
                        }
                    }
                }

                // 3. Vertical pair
                if (available[x][y] && y < n - 1) {
                    if (matrix.isDark(x, y) && matrix.isDark(x, y + 1) && available[x][y + 1]) {
                        val r = 0.5f * (0.95f + rnd.nextFloat() * 0.10f)
                        val strokeW = 0.36f + rnd.nextFloat() * 0.04f
                        pointList.add(
                            ClusterPrimitive(
                                cx = x + 0.5f,
                                cy = y + 1.0f,
                                radius = r,
                                strokeWidthRatio = strokeW,
                                isSolid = false
                            )
                        )
                        available[x][y] = false
                        available[x][y + 1] = false
                    }
                }

                // 4. Horizontal pair
                if (available[x][y] && x < n - 1) {
                    if (matrix.isDark(x, y) && matrix.isDark(x + 1, y) && available[x + 1][y]) {
                        val r = 0.5f * (0.95f + rnd.nextFloat() * 0.10f)
                        val strokeW = 0.36f + rnd.nextFloat() * 0.04f
                        pointList.add(
                            ClusterPrimitive(
                                cx = x + 1.0f,
                                cy = y + 0.5f,
                                radius = r,
                                strokeWidthRatio = strokeW,
                                isSolid = false
                            )
                        )
                        available[x][y] = false
                        available[x + 1][y] = false
                    }
                }

                // 5. Singletons and Ambient micro-bubbles
                if (available[x][y]) {
                    if (matrix.isDark(x, y)) {
                        val r = 0.5f * (0.5f + rnd.nextFloat() * 0.5f)
                        pointList.add(
                            ClusterPrimitive(
                                cx = x + 0.5f,
                                cy = y + 0.5f,
                                radius = r,
                                strokeWidthRatio = 0f,
                                isSolid = true
                            )
                        )
                    } else if (design.clusterStyle.ambientBubbles) {
                        // Ambient micro-bubbles in light data module space
                        if (rnd.nextFloat() > (1.0f - design.clusterStyle.ambientDensity)) {
                            val maxR = design.clusterStyle.ambientMaxRadius.coerceIn(0.08f, 0.25f)
                            val r = 0.5f * (0.85f + rnd.nextFloat() * 0.45f) * (maxR / 0.5f)
                            val strokeW = 0.15f + rnd.nextFloat() * 0.18f
                            g2.add(
                                ClusterPrimitive(
                                    cx = x + 0.5f,
                                    cy = y + 0.5f,
                                    radius = r,
                                    strokeWidthRatio = strokeW,
                                    isAmbient = true,
                                    isSolid = false
                                )
                            )
                        }
                    }
                }
            }
        }

        // Layer order: Singletons/Pairs -> Macro crosses/squares (g1) -> Ambient micro-bubbles (g2)
        val result = ArrayList<ClusterPrimitive>(pointList.size + g1.size + g2.size)
        result.addAll(pointList)
        result.addAll(g1)
        result.addAll(g2)
        return result
    }
}
