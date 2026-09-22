package com.veilframe.app.upscale.inference

import java.io.File
import kotlin.math.ceil

data class TileArea(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class TilePosition(
    val column: Int,
    val row: Int
)

data class TileFiles(
    val input: File,
    val output: File
)

data class Tile(
    val index: Int,
    val area: TileArea,
    val position: TilePosition,
    val files: TileFiles
)

data class TileGrid(
    val imageWidth: Int,
    val imageHeight: Int,
    val tileLimit: Int,
    val overlap: Int,
    val step: Int,
    val columns: Int,
    val rows: Int
) {
    fun tiles(filesFor: (Int) -> TileFiles): List<Tile> = buildList {
        var index = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = column * step
                val y = row * step
                val width = minOf(x + tileLimit, imageWidth) - x
                val height = minOf(y + tileLimit, imageHeight) - y

                if (width > 0 && height > 0) {
                    add(
                        Tile(
                            index = index,
                            area = TileArea(
                                x = x,
                                y = y,
                                width = width,
                                height = height
                            ),
                            position = TilePosition(
                                column = column,
                                row = row
                            ),
                            files = filesFor(index)
                        )
                    )
                    index++
                }
            }
        }
    }

    companion object {
        fun from(
            imageWidth: Int,
            imageHeight: Int,
            tileLimit: Int,
            overlap: Int
        ): TileGrid {
            val step = tileLimit - overlap
            val columns = if (imageWidth <= tileLimit) {
                1
            } else {
                ceil((imageWidth - overlap).toFloat() / step).toInt()
            }
            val rows = if (imageHeight <= tileLimit) {
                1
            } else {
                ceil((imageHeight - overlap).toFloat() / step).toInt()
            }

            return TileGrid(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                tileLimit = tileLimit,
                overlap = overlap,
                step = step,
                columns = columns,
                rows = rows
            )
        }
    }
}
