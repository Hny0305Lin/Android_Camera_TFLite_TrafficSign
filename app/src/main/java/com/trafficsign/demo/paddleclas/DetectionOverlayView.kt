package com.trafficsign.demo.paddleclas

import android.graphics.Bitmap
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
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 210
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
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
        recycleDetectionBitmaps(detections, except = results)
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

            canvas.drawBitmap(detection.hsvOverlayCrop, null, safeRect, overlayPaint)
            canvas.drawRoundRect(safeRect, 18f, 18f, boxPaint)

            drawOriginalPreview(canvas, safeRect, detection.originalCrop)

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

    override fun onDetachedFromWindow() {
        recycleDetectionBitmaps(detections, except = emptyList())
        detections = emptyList()
        super.onDetachedFromWindow()
    }

    private fun drawOriginalPreview(canvas: Canvas, frameRect: RectF, bitmap: Bitmap) {
        val maxPreviewWidth = min(frameRect.width() * 0.38f, 180f)
        val maxPreviewHeight = min(frameRect.height() * 0.38f, 140f)
        if (maxPreviewWidth < 28f || maxPreviewHeight < 28f) {
            return
        }

        val previewAspect = bitmap.width / bitmap.height.toFloat()
        var previewWidth = maxPreviewWidth
        var previewHeight = previewWidth / previewAspect
        if (previewHeight > maxPreviewHeight) {
            previewHeight = maxPreviewHeight
            previewWidth = previewHeight * previewAspect
        }

        val margin = 12f
        val previewRect = RectF(
            frameRect.right - previewWidth - margin,
            frameRect.top + margin,
            frameRect.right - margin,
            frameRect.top + margin + previewHeight
        )

        canvas.drawRoundRect(
            previewRect.left - 4f,
            previewRect.top - 4f,
            previewRect.right + 4f,
            previewRect.bottom + 4f,
            12f,
            12f,
            previewPaint
        )
        canvas.drawBitmap(bitmap, null, previewRect, null)
        canvas.drawRoundRect(previewRect, 10f, 10f, boxPaint)
    }

    private fun recycleDetectionBitmaps(oldDetections: List<DetectionResult>, except: List<DetectionResult>) {
        val preserved = mutableSetOf<Bitmap>()
        except.forEach { detection ->
            preserved += detection.originalCrop
            preserved += detection.hsvOverlayCrop
        }
        oldDetections.forEach { detection ->
            if (!preserved.contains(detection.originalCrop) && !detection.originalCrop.isRecycled) {
                detection.originalCrop.recycle()
            }
            if (!preserved.contains(detection.hsvOverlayCrop) && !detection.hsvOverlayCrop.isRecycled) {
                detection.hsvOverlayCrop.recycle()
            }
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
