package com.trafficsign.demo.paddleclas

import android.Manifest
import android.hardware.display.DisplayManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var detectionOverlay: DetectionOverlayView
    private lateinit var statusText: TextView
    private lateinit var engineText: TextView
    private lateinit var resolutionText: TextView
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val recognizer: TrafficSignRecognizer by lazy {
        TrafficSignRecognizer(applicationContext)
    }
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val viewDisplay = previewView.display ?: return
            if (viewDisplay.displayId != displayId) {
                return
            }
            syncUseCaseRotation(viewDisplay.rotation)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                statusText.text = getString(R.string.camera_permission_required)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        detectionOverlay = findViewById(R.id.detectionOverlay)
        statusText = findViewById(R.id.statusText)
        engineText = findViewById(R.id.engineText)
        resolutionText = findViewById(R.id.resolutionText)

        enterImmersiveMode()
        updateEngineStatus()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersiveMode()
        }
    }

    override fun onStart() {
        super.onStart()
        displayManager?.registerDisplayListener(displayListener, null)
    }

    override fun onStop() {
        displayManager?.unregisterDisplayListener(displayListener)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startCamera() {
        statusText.text = getString(R.string.camera_starting)
        val requestedAnalysisSize = getBackCameraMaxAnalysisSize()
        resolutionText.text = requestedAnalysisSize?.let {
            getString(R.string.camera_resolution_format, it.width, it.height)
        } ?: getString(R.string.camera_resolution_waiting)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider, requestedAnalysisSize)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(
        cameraProvider: ProcessCameraProvider,
        requestedAnalysisSize: Size?
    ) {
        val currentRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(currentRotation)
            .build()
            .apply {
            surfaceProvider = previewView.surfaceProvider
        }

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(currentRotation)

        val resolutionSelector = requestedAnalysisSize?.let {
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        it,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        } ?: ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        val imageAnalysis = analysisBuilder
            .setResolutionSelector(resolutionSelector)
            .build()
            .apply {
                setAnalyzer(
                    cameraExecutor,
                    RealtimeAnalyzer(recognizer) { result ->
                        runOnUiThread {
                            statusText.text = result.statusMessage
                            detectionOverlay.setDetections(
                                result.detections,
                                result.frameWidth,
                                result.frameHeight
                            )
                        }
                    }
                )
            }

        previewUseCase = preview
        imageAnalysisUseCase = imageAnalysis

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
            syncUseCaseRotation(currentRotation)
            statusText.text = getString(R.string.camera_running)
        } catch (exception: Exception) {
            statusText.text = getString(R.string.camera_error)
            engineText.text = exception.message ?: getString(R.string.camera_error)
        }
    }

    private fun getBackCameraMaxAnalysisSize(): Size? {
        val cameraManager = getSystemService(CameraManager::class.java) ?: return null
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) {
                continue
            }

            val streamConfigMap =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: continue
            return streamConfigMap.getLargestYuvSize()
        }
        return null
    }

    private fun StreamConfigurationMap.getLargestYuvSize(): Size? {
        return getOutputSizes(ImageFormat.YUV_420_888)
            ?.maxByOrNull { size -> size.width.toLong() * size.height.toLong() }
    }

    private fun updateEngineStatus() {
        engineText.text = getString(R.string.overlay_engine_status)
    }

    private fun syncUseCaseRotation(displayRotation: Int) {
        previewUseCase?.targetRotation = displayRotation
        imageAnalysisUseCase?.targetRotation = displayRotation
    }

    private class RealtimeAnalyzer(
        private val recognizer: TrafficSignRecognizer,
        private val onResult: (FrameAnalysisResult) -> Unit
    ) : ImageAnalysis.Analyzer {
        private val isProcessing = AtomicBoolean(false)
        private var lastAnalysisTimestamp = 0L

        override fun analyze(image: ImageProxy) {
            val now = SystemClock.elapsedRealtime()
            val canProcess = now - lastAnalysisTimestamp >= ANALYSIS_INTERVAL_MS
            if (!canProcess || !isProcessing.compareAndSet(false, true)) {
                image.close()
                return
            }

            lastAnalysisTimestamp = now
            try {
                onResult(recognizer.analyze(image))
            } finally {
                isProcessing.set(false)
                image.close()
            }
        }

        private companion object {
            const val ANALYSIS_INTERVAL_MS = 33L
        }
    }
}
