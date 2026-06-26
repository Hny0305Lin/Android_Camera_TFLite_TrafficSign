package com.trafficsign.demo.paddleclas

data class ClassificationModelConfig(
    val engine: InferenceEngine,
    val modelAssetPath: String,
    val labelAssetPath: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val meanRgb: FloatArray = floatArrayOf(127.5f, 127.5f, 127.5f),
    val stdRgb: FloatArray = floatArrayOf(127.5f, 127.5f, 127.5f),
    val topK: Int = 3,
    val quantizedInput: Boolean = false
)

data class DemoPipelineConfig(
    val tfliteClassification: ClassificationModelConfig
)

object DemoModelConfig {
    val current = DemoPipelineConfig(
        tfliteClassification = ClassificationModelConfig(
            engine = InferenceEngine.TFLITE,
            modelAssetPath = "traffic_sign_100ep_float32.tflite",
            labelAssetPath = "traffic_sign_label_list.txt",
            inputWidth = 224,
            inputHeight = 224,
            topK = 5
        )
    )
}
