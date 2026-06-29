package com.trafficsign.demo.paddleclas

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.MappedByteBuffer

class TrafficSignRecognizer(
    private val context: Context,
    private val config: DemoPipelineConfig = DemoModelConfig.current
) {
    private val candidateDetector = TrafficSignCandidateDetector()

    private val tfliteRunner: ClassifierRunner by lazy {
        TFLiteClassifierRunner(context, config.tfliteClassification)
    }

    fun analyze(imageProxy: ImageProxy): FrameAnalysisResult {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val rawBitmap = ImageUtils.imageProxyToBitmap(imageProxy)
        val bitmap = ImageUtils.rotateBitmap(rawBitmap, rotationDegrees)

        return try {
            val detections = detectTrafficSigns(bitmap)
            FrameAnalysisResult(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                detections = detections,
                statusMessage = if (detections.isEmpty()) {
                    "未发现交通标志"
                } else {
                    "已框选 ${detections.size} 个交通标志"
                }
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun detectTrafficSigns(bitmap: Bitmap): List<DetectionResult> {
        val regions = candidateDetector.detect(bitmap)
        if (regions.isEmpty()) {
            return emptyList()
        }

        val detections = mutableListOf<DetectionResult>()
        for (region in regions) {
            val crop = ImageUtils.cropBitmap(bitmap, region.boundingBox)
            val classification = tfliteRunner.classify(crop)
            val top1 = classification.topResults.firstOrNull() ?: continue
            if (!classification.summary.isReady || top1.score < MIN_DETECTION_SCORE) {
                continue
            }
            detections += DetectionResult(
                label = toDisplayLabel(top1.label),
                score = top1.score,
                boundingBox = region.boundingBox,
                boxColor = region.boxColor
            )
        }
        return detections
            .sortedByDescending { it.score }
            .take(MAX_DETECTIONS)
    }

    private interface ClassifierRunner {
        fun classify(bitmap: Bitmap): EngineClassificationResult
    }

    private class TFLiteClassifierRunner(
        private val context: Context,
        private val config: ClassificationModelConfig
    ) : ClassifierRunner {
        private val labels by lazy { AssetUtils.readLabels(context, config.labelAssetPath) }
        private val interpreter by lazy(LazyThreadSafetyMode.NONE) {
            if (!AssetUtils.assetExists(context, config.modelAssetPath)) {
                null
            } else {
                val options = org.tensorflow.lite.Interpreter.Options().apply {
                    setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                }
                Interpreter(loadMappedModel(context, config.modelAssetPath), options)
            }
        }

        override fun classify(bitmap: Bitmap): EngineClassificationResult {
            val tfInterpreter = interpreter
                ?: return EngineClassificationResult(
                    summary = TaskSummary(
                        engine = InferenceEngine.TFLITE,
                        message = "TFLite 分类模型缺失",
                        latencyMs = 0L,
                        isReady = false
                    ),
                    topResults = emptyList()
                )

            return try {
                val startedAt = SystemClock.elapsedRealtime()
                val inputBuffer = createImageInputBuffer(
                    bitmap = bitmap,
                    inputWidth = config.inputWidth,
                    inputHeight = config.inputHeight,
                    quantized = config.quantizedInput,
                    meanRgb = config.meanRgb,
                    stdRgb = config.stdRgb,
                    isNchw = false
                )
                val outputShape = tfInterpreter.getOutputTensor(0).shape()
                val classCount = outputShape.last().coerceAtLeast(1)
                val output = Array(1) { FloatArray(classCount) }
                tfInterpreter.run(inputBuffer, output)

                EngineClassificationResult(
                    summary = TaskSummary(
                        engine = InferenceEngine.TFLITE,
                        message = "TFLite 分类运行中",
                        latencyMs = SystemClock.elapsedRealtime() - startedAt,
                        isReady = true
                    ),
                    topResults = scoresToTopK(output[0], labels, config.topK)
                )
            } catch (exception: Exception) {
                EngineClassificationResult(
                    summary = TaskSummary(
                        engine = InferenceEngine.TFLITE,
                        message = "TFLite 分类异常: ${exception.message}",
                        latencyMs = 0L,
                        isReady = false
                    ),
                    topResults = emptyList()
                )
            }
        }
    }

    private companion object {
        const val MIN_DETECTION_SCORE = 0.45f
        const val MAX_DETECTIONS = 24

        private fun toDisplayLabel(rawLabel: String): String {
            return when {
                rawLabel.isBlank() -> "未命名标志"
                rawLabel.startsWith("class_") -> {
                    val suffix = rawLabel.removePrefix("class_")
                    if (suffix.all { it.isDigit() }) "类别 $suffix" else "未命名标志"
                }
                else -> rawLabel
            }
        }

        private fun loadMappedModel(context: Context, assetPath: String): MappedByteBuffer {
            val assetFileDescriptor = context.assets.openFd(assetPath)
            FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                return fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )
            }
        }

        private fun scoresToTopK(
            scores: FloatArray,
            labels: List<String>,
            topK: Int
        ): List<ClassificationResult> {
            return scores.indices
                .map { index ->
                    ClassificationResult(
                        label = labels.getOrElse(index) { "Class $index" },
                        score = scores[index]
                    )
                }
                .sortedByDescending { it.score }
                .take(topK)
        }

        private fun createImageInputBuffer(
            bitmap: Bitmap,
            inputWidth: Int,
            inputHeight: Int,
            quantized: Boolean,
            meanRgb: FloatArray,
            stdRgb: FloatArray,
            isNchw: Boolean
        ): ByteBuffer {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val pixelCount = inputWidth * inputHeight
            val channelCount = 3
            val bytesPerValue = if (quantized) 1 else 4
            val buffer = ByteBuffer.allocateDirect(pixelCount * channelCount * bytesPerValue)
                .order(ByteOrder.nativeOrder())
            val pixels = IntArray(pixelCount)
            scaledBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

            if (isNchw) {
                val floatArray = createFloatArrayFromPixels(
                    pixels = pixels,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    meanRgb = meanRgb,
                    stdRgb = stdRgb,
                    isNchw = true
                )
                floatArray.forEach { buffer.putFloat(it) }
            } else {
                pixels.forEach { pixel ->
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (quantized) {
                        buffer.put(r.toByte())
                        buffer.put(g.toByte())
                        buffer.put(b.toByte())
                    } else {
                        buffer.putFloat((r - meanRgb[0]) / stdRgb[0])
                        buffer.putFloat((g - meanRgb[1]) / stdRgb[1])
                        buffer.putFloat((b - meanRgb[2]) / stdRgb[2])
                    }
                }
            }

            buffer.rewind()
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            return buffer
        }

        private fun createFloatArrayFromPixels(
            pixels: IntArray,
            inputWidth: Int,
            inputHeight: Int,
            meanRgb: FloatArray,
            stdRgb: FloatArray,
            isNchw: Boolean
        ): FloatArray {
            val output = FloatArray(inputWidth * inputHeight * 3)
            if (isNchw) {
                var redIndex = 0
                var greenIndex = inputWidth * inputHeight
                var blueIndex = inputWidth * inputHeight * 2
                pixels.forEach { pixel ->
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    output[redIndex++] = (r - meanRgb[0]) / stdRgb[0]
                    output[greenIndex++] = (g - meanRgb[1]) / stdRgb[1]
                    output[blueIndex++] = (b - meanRgb[2]) / stdRgb[2]
                }
            } else {
                var outputIndex = 0
                pixels.forEach { pixel ->
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    output[outputIndex++] = (r - meanRgb[0]) / stdRgb[0]
                    output[outputIndex++] = (g - meanRgb[1]) / stdRgb[1]
                    output[outputIndex++] = (b - meanRgb[2]) / stdRgb[2]
                }
            }
            return output
        }
    }
}
