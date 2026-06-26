package com.trafficsign.demo.paddleclas

import android.graphics.RectF

enum class InferenceEngine {
    TFLITE
}

data class ClassificationResult(
    val label: String,
    val score: Float
)

data class DetectionResult(
    val label: String,
    val score: Float,
    val boundingBox: RectF,
    val boxColor: Int
)

data class TaskSummary(
    val engine: InferenceEngine,
    val message: String,
    val latencyMs: Long,
    val isReady: Boolean
)

data class EngineClassificationResult(
    val summary: TaskSummary,
    val topResults: List<ClassificationResult>
)

data class FrameAnalysisResult(
    val frameWidth: Int,
    val frameHeight: Int,
    val detections: List<DetectionResult>,
    val statusMessage: String
)
