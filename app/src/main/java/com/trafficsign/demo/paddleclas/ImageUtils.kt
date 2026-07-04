package com.trafficsign.demo.paddleclas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

object ImageUtils {
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val nv21 = yuv420888ToNv21(imageProxy)
        val yuvImage = YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 95, stream)
        val jpegBytes = stream.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) {
            return source
        }

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated != source) {
            source.recycle()
        }
        return rotated
    }

    fun cropBitmap(source: Bitmap, rectF: RectF): Bitmap {
        val left = rectF.left.coerceIn(0f, source.width - 1f).roundToInt()
        val top = rectF.top.coerceIn(0f, source.height - 1f).roundToInt()
        val right = rectF.right.coerceIn(left + 1f, source.width.toFloat()).roundToInt()
        val bottom = rectF.bottom.coerceIn(top + 1f, source.height.toFloat()).roundToInt()
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    fun buildHsvOverlayBitmap(source: Bitmap, targetColorId: Int, overlayColor: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = IntArray(pixels.size)
        val overlayAlpha = 176

        for (index in pixels.indices) {
            val pixel = pixels[index]
            if (TrafficSignCandidateDetector.classifyColorId(pixel) == targetColorId) {
                val originalAlpha = Color.alpha(pixel)
                output[index] = Color.argb(
                    minOf(originalAlpha, overlayAlpha),
                    Color.red(overlayColor),
                    Color.green(overlayColor),
                    Color.blue(overlayColor)
                )
            } else {
                output[index] = Color.TRANSPARENT
            }
        }

        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun yuv420888ToNv21(imageProxy: ImageProxy): ByteArray {
        val image = imageProxy.image ?: error("ImageProxy does not contain image data")
        val yBuffer = image.planes[0].buffer.toByteArray()
        val uBuffer = image.planes[1].buffer.toByteArray()
        val vBuffer = image.planes[2].buffer.toByteArray()

        val ySize = yBuffer.size
        val uSize = uBuffer.size
        val vSize = vBuffer.size
        val output = ByteArray(ySize + uSize + vSize)

        System.arraycopy(yBuffer, 0, output, 0, ySize)

        val chromaRowStride = image.planes[1].rowStride
        val chromaPixelStride = image.planes[1].pixelStride
        var outputOffset = ySize
        val chromaHeight = image.height / 2
        val chromaWidth = image.width / 2

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val index = row * chromaRowStride + col * chromaPixelStride
                output[outputOffset++] = vBuffer[index]
                output[outputOffset++] = uBuffer[index]
            }
        }
        return output
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        val bytes = ByteArray(remaining())
        get(bytes)
        return bytes
    }
}
