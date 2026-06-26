package com.trafficsign.demo.paddleclas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
    }

    private var sourceWidth = 1
    private var sourceHeight = 1
    private var detections: List<DetectionResult> = emptyList()

    fun setDetections(results: List<DetectionResult>, imageWidth: Int, imageHeight: Int) {
        detections = results
        sourceWidth = max(1, imageWidth)
        sourceHeight = max(1, imageHeight)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) {
            return
        }

        val scale = max(width / sourceWidth.toFloat(), height / sourceHeight.toFloat())
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f

        detections.forEach { detection ->
            val safeRect = RectF(
                (detection.boundingBox.left * scale + offsetX).coerceIn(0f, width.toFloat()),
                (detection.boundingBox.top * scale + offsetY).coerceIn(0f, height.toFloat()),
                (detection.boundingBox.right * scale + offsetX).coerceIn(0f, width.toFloat()),
                (detection.boundingBox.bottom * scale + offsetY).coerceIn(0f, height.toFloat())
            )
            if (safeRect.width() <= 1f || safeRect.height() <= 1f) {
                return@forEach
            }

            boxPaint.color = detection.boxColor
            textBgPaint.color = blendWithBlack(detection.boxColor)
            textPaint.color = if (isBrightColor(detection.boxColor)) Color.BLACK else Color.WHITE

            canvas.drawRoundRect(safeRect, 18f, 18f, boxPaint)

            val label = "${detection.label} ${(detection.score * 100f).toInt()}%"
            val textWidth = textPaint.measureText(label)
            val metrics = textPaint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            val bubbleHeight = textHeight + 22f
            val showAbove = safeRect.top > bubbleHeight + 12f
            val bgTop = if (showAbove) {
                safeRect.top - bubbleHeight - 8f
            } else {
                (safeRect.top + 8f).coerceAtMost(height - bubbleHeight - 4f)
            }
            val bgBottom = min(bgTop + bubbleHeight, height.toFloat())
            val bgRight = min(safeRect.left + textWidth + 28f, width.toFloat())

            canvas.drawRoundRect(
                safeRect.left,
                bgTop,
                bgRight,
                bgBottom,
                14f,
                14f,
                textBgPaint
            )
            val baseline = bgTop + 11f - metrics.ascent
            canvas.drawText(label, safeRect.left + 14f, baseline, textPaint)
        }
    }

    private fun blendWithBlack(color: Int): Int {
        val r = (Color.red(color) * 0.75f).toInt()
        val g = (Color.green(color) * 0.75f).toInt()
        val b = (Color.blue(color) * 0.75f).toInt()
        return Color.argb(220, r, g, b)
    }

    private fun isBrightColor(color: Int): Boolean {
        val brightness = (Color.red(color) * 0.299f) +
            (Color.green(color) * 0.587f) +
            (Color.blue(color) * 0.114f)
        return brightness >= 170f
    }
}
