package com.trafficsign.demo.paddleclas

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TrafficSignCandidateDetector {
    fun detect(bitmap: Bitmap): List<CandidateRegion> {
        val longestEdge = max(bitmap.width, bitmap.height)
        val scale = if (longestEdge > MAX_ANALYSIS_EDGE) {
            MAX_ANALYSIS_EDGE.toFloat() / longestEdge.toFloat()
        } else {
            1f
        }
        val scaledWidth = max(1, (bitmap.width * scale).roundToInt())
        val scaledHeight = max(1, (bitmap.height * scale).roundToInt())
        val scaledBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        } else {
            bitmap
        }

        try {
            val pixels = IntArray(scaledWidth * scaledHeight)
            scaledBitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
            val colorMask = IntArray(pixels.size) { index -> classifyColor(pixels[index]) }
            val visited = BooleanArray(pixels.size)
            val queue = IntArray(pixels.size)
            val detected = mutableListOf<CandidateRegion>()

            for (index in colorMask.indices) {
                val colorId = colorMask[index]
                if (colorId == COLOR_NONE || visited[index]) {
                    continue
                }
                val region = exploreRegion(
                    startIndex = index,
                    targetColor = colorId,
                    mask = colorMask,
                    visited = visited,
                    width = scaledWidth,
                    height = scaledHeight,
                    queue = queue,
                    scaleBack = if (scale == 0f) 1f else 1f / scale
                )
                if (region != null) {
                    detected += region
                }
            }
            return mergeOverlapping(detected)
                .sortedByDescending { it.area }
                .take(MAX_CANDIDATE_COUNT)
        } finally {
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
        }
    }

    private fun exploreRegion(
        startIndex: Int,
        targetColor: Int,
        mask: IntArray,
        visited: BooleanArray,
        width: Int,
        height: Int,
        queue: IntArray,
        scaleBack: Float
    ): CandidateRegion? {
        val frameArea = width * height
        val minFrameEdge = min(width, height)
        val minComponentPixels = max(MIN_COMPONENT_PIXELS_FLOOR, (frameArea * MIN_COMPONENT_AREA_RATIO).roundToInt())
        val minEdgePixels = max(MIN_EDGE_PIXELS_FLOOR, (minFrameEdge * MIN_EDGE_RATIO).roundToInt())
        var head = 0
        var tail = 0
        queue[tail++] = startIndex
        visited[startIndex] = true

        var minX = startIndex % width
        var maxX = minX
        var minY = startIndex / width
        var maxY = minY
        var pixelCount = 0

        while (head < tail) {
            val current = queue[head++]
            val x = current % width
            val y = current / width
            pixelCount++
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)

            for (offsetY in -1..1) {
                for (offsetX in -1..1) {
                    if (offsetX == 0 && offsetY == 0) {
                        continue
                    }
                    val nextX = x + offsetX
                    val nextY = y + offsetY
                    if (nextX !in 0 until width || nextY !in 0 until height) {
                        continue
                    }
                    val nextIndex = nextY * width + nextX
                    if (!visited[nextIndex] && mask[nextIndex] == targetColor) {
                        visited[nextIndex] = true
                        queue[tail++] = nextIndex
                    }
                }
            }
        }

        val regionWidth = maxX - minX + 1
        val regionHeight = maxY - minY + 1
        val bboxArea = regionWidth * regionHeight
        if (pixelCount < minComponentPixels || regionWidth < minEdgePixels || regionHeight < minEdgePixels) {
            return null
        }
        val aspectRatio = regionWidth / regionHeight.toFloat()
        if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) {
            return null
        }
        if (bboxArea <= 0 || pixelCount / bboxArea.toFloat() < MIN_FILL_RATIO) {
            return null
        }
        if (bboxArea / frameArea.toFloat() > MAX_AREA_RATIO) {
            return null
        }
        if (regionWidth / width.toFloat() > MAX_EDGE_RATIO || regionHeight / height.toFloat() > MAX_EDGE_RATIO) {
            return null
        }
        val edgeMarginX = max(EDGE_MARGIN_PX, (width * EDGE_MARGIN_RATIO).roundToInt())
        val edgeMarginY = max(EDGE_MARGIN_PX, (height * EDGE_MARGIN_RATIO).roundToInt())
        val touchesFrameEdge =
            minX <= edgeMarginX ||
                minY <= edgeMarginY ||
                maxX >= width - edgeMarginX ||
                maxY >= height - edgeMarginY
        if (touchesFrameEdge) {
            return null
        }
        if (!passesShapeProfileFilter(mask, width, minX, maxX, minY, maxY, targetColor, pixelCount)) {
            return null
        }

        val expandedRect = RectF(
            (minX - regionWidth * EXPAND_RATIO) * scaleBack,
            (minY - regionHeight * EXPAND_RATIO) * scaleBack,
            (maxX + 1 + regionWidth * EXPAND_RATIO) * scaleBack,
            (maxY + 1 + regionHeight * EXPAND_RATIO) * scaleBack
        )
        return CandidateRegion(
            boundingBox = expandedRect,
            boxColor = colorToOverlay(targetColor),
            area = expandedRect.width() * expandedRect.height()
        )
    }

    private fun mergeOverlapping(input: List<CandidateRegion>): List<CandidateRegion> {
        val merged = mutableListOf<CandidateRegion>()
        input.forEach { candidate ->
            var wasMerged = false
            for (index in merged.indices) {
                val existing = merged[index]
                if (shouldMerge(existing.boundingBox, candidate.boundingBox)) {
                    merged[index] = CandidateRegion(
                        boundingBox = RectF(
                            min(existing.boundingBox.left, candidate.boundingBox.left),
                            min(existing.boundingBox.top, candidate.boundingBox.top),
                            max(existing.boundingBox.right, candidate.boundingBox.right),
                            max(existing.boundingBox.bottom, candidate.boundingBox.bottom)
                        ),
                        boxColor = if (existing.area >= candidate.area) existing.boxColor else candidate.boxColor,
                        area = max(existing.area, candidate.area)
                    )
                    wasMerged = true
                    break
                }
            }
            if (!wasMerged) {
                merged += candidate
            }
        }
        return merged
    }

    private fun classifyColor(pixel: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        return when {
            saturation < MIN_SATURATION || value < MIN_VALUE -> COLOR_NONE
            hue <= 24f || hue >= 332f -> COLOR_RED
            hue in 180f..265f -> COLOR_BLUE
            hue in 28f..82f -> COLOR_YELLOW
            else -> COLOR_NONE
        }
    }

    private fun shouldMerge(first: RectF, second: RectF): Boolean {
        if (!RectF.intersects(first, second)) {
            return false
        }
        val intersectionLeft = max(first.left, second.left)
        val intersectionTop = max(first.top, second.top)
        val intersectionRight = min(first.right, second.right)
        val intersectionBottom = min(first.bottom, second.bottom)
        val intersectionArea =
            max(0f, intersectionRight - intersectionLeft) * max(0f, intersectionBottom - intersectionTop)
        if (intersectionArea <= 0f) {
            return false
        }
        val firstArea = first.width() * first.height()
        val secondArea = second.width() * second.height()
        val unionArea = firstArea + secondArea - intersectionArea
        if (unionArea <= 0f) {
            return false
        }
        val iou = intersectionArea / unionArea
        val overlapOnSmaller = intersectionArea / min(firstArea, secondArea)
        return iou >= MERGE_IOU_THRESHOLD || overlapOnSmaller >= MERGE_SMALLER_RATIO
    }

    private fun passesShapeProfileFilter(
        mask: IntArray,
        imageWidth: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        targetColor: Int,
        pixelCount: Int
    ): Boolean {
        val boxWidth = maxX - minX + 1
        val boxHeight = maxY - minY + 1
        if (boxWidth <= 0 || boxHeight <= 0) {
            return false
        }

        val rowWidths = IntArray(boxHeight)
        val colHeights = IntArray(boxWidth)
        var centerPixels = 0
        val centerLeft = minX + (boxWidth * 0.25f).roundToInt()
        val centerRight = maxX - (boxWidth * 0.25f).roundToInt()
        val centerTop = minY + (boxHeight * 0.25f).roundToInt()
        val centerBottom = maxY - (boxHeight * 0.25f).roundToInt()

        for (y in minY..maxY) {
            var rowMin = Int.MAX_VALUE
            var rowMax = Int.MIN_VALUE
            for (x in minX..maxX) {
                if (mask[y * imageWidth + x] != targetColor) {
                    continue
                }
                rowMin = min(rowMin, x)
                rowMax = max(rowMax, x)
                val colIndex = x - minX
                colHeights[colIndex] += 1
                if (x in centerLeft..centerRight && y in centerTop..centerBottom) {
                    centerPixels += 1
                }
            }
            val rowIndex = y - minY
            if (rowMin != Int.MAX_VALUE) {
                rowWidths[rowIndex] = rowMax - rowMin + 1
            }
        }

        val topAvg = averageNonZero(rowWidths, 0, max(1, boxHeight / 3))
        val middleAvg = averageNonZero(rowWidths, boxHeight / 3, max(boxHeight / 3 + 1, boxHeight * 2 / 3))
        val bottomAvg = averageNonZero(rowWidths, boxHeight * 2 / 3, boxHeight)
        val leftAvg = averageNonZero(colHeights, 0, max(1, boxWidth / 3))
        val middleColAvg = averageNonZero(colHeights, boxWidth / 3, max(boxWidth / 3 + 1, boxWidth * 2 / 3))
        val rightAvg = averageNonZero(colHeights, boxWidth * 2 / 3, boxWidth)
        val centerRatio = centerPixels / pixelCount.toFloat()
        val aspectRatio = boxWidth / boxHeight.toFloat()
        val nearSquare = aspectRatio in 0.68f..1.34f
        val broadSquare = aspectRatio in 0.6f..1.5f

        val roundLikeStrong =
            nearSquare &&
                middleAvg > topAvg * 1.14f &&
                middleAvg > bottomAvg * 1.14f &&
                middleColAvg > leftAvg * 1.08f &&
                middleColAvg > rightAvg * 1.08f

        val roundLikeSoft =
            broadSquare &&
                middleAvg > topAvg * 1.06f &&
                middleAvg > bottomAvg * 1.06f &&
                middleColAvg > leftAvg * 1.03f &&
                middleColAvg > rightAvg * 1.03f &&
                centerRatio in 0.1f..0.65f

        val octagonLikeStrong =
            nearSquare &&
                topAvg > boxWidth * 0.38f &&
                bottomAvg > boxWidth * 0.38f &&
                middleAvg > topAvg * 1.03f &&
                middleAvg > bottomAvg * 1.03f &&
                centerRatio in 0.08f..0.6f

        val octagonLikeSoft =
            broadSquare &&
                topAvg > boxWidth * 0.3f &&
                bottomAvg > boxWidth * 0.3f &&
                middleAvg >= topAvg &&
                middleAvg >= bottomAvg &&
                centerRatio in 0.06f..0.7f

        val triangleUpStrong =
            aspectRatio in 0.68f..1.42f &&
                bottomAvg > topAvg * 1.32f &&
                middleAvg > topAvg * 1.12f &&
                centerRatio < 0.5f

        val triangleUpSoft =
            aspectRatio in 0.62f..1.5f &&
                bottomAvg > topAvg * 1.18f &&
                middleAvg > topAvg * 1.05f &&
                centerRatio < 0.6f

        val triangleDownStrong =
            aspectRatio in 0.68f..1.42f &&
                topAvg > bottomAvg * 1.32f &&
                middleAvg > bottomAvg * 1.12f &&
                centerRatio < 0.5f

        val triangleDownSoft =
            aspectRatio in 0.62f..1.5f &&
                topAvg > bottomAvg * 1.18f &&
                middleAvg > bottomAvg * 1.05f &&
                centerRatio < 0.6f

        return roundLikeStrong ||
            roundLikeSoft ||
            octagonLikeStrong ||
            octagonLikeSoft ||
            triangleUpStrong ||
            triangleUpSoft ||
            triangleDownStrong ||
            triangleDownSoft
    }

    private fun averageNonZero(values: IntArray, startInclusive: Int, endExclusive: Int): Float {
        var sum = 0f
        var count = 0
        for (index in startInclusive until min(endExclusive, values.size)) {
            val value = values[index]
            if (value > 0) {
                sum += value
                count += 1
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun colorToOverlay(colorId: Int): Int {
        return when (colorId) {
            COLOR_RED -> Color.parseColor("#FF3B30")
            COLOR_BLUE -> Color.parseColor("#0A84FF")
            COLOR_YELLOW -> Color.parseColor("#FFD60A")
            else -> Color.WHITE
        }
    }

    data class CandidateRegion(
        val boundingBox: RectF,
        val boxColor: Int,
        val area: Float
    )

    private companion object {
        const val MAX_ANALYSIS_EDGE = 480
        const val MAX_CANDIDATE_COUNT = 6
        const val MIN_COMPONENT_PIXELS_FLOOR = 48
        const val MIN_COMPONENT_AREA_RATIO = 0.00018f
        const val MIN_EDGE_PIXELS_FLOOR = 10
        const val MIN_EDGE_RATIO = 0.024f
        const val MIN_FILL_RATIO = 0.35f
        const val MIN_ASPECT_RATIO = 0.55f
        const val MAX_ASPECT_RATIO = 1.45f
        const val MAX_AREA_RATIO = 0.16f
        const val MAX_EDGE_RATIO = 0.42f
        const val EDGE_MARGIN_RATIO = 0.03f
        const val EDGE_MARGIN_PX = 3
        const val EXPAND_RATIO = 0.12f
        const val MIN_SATURATION = 0.24f
        const val MIN_VALUE = 0.16f
        const val MERGE_IOU_THRESHOLD = 0.2f
        const val MERGE_SMALLER_RATIO = 0.55f

        const val COLOR_NONE = 0
        const val COLOR_RED = 1
        const val COLOR_BLUE = 2
        const val COLOR_YELLOW = 3
    }
}
