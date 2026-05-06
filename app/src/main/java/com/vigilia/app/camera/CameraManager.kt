package com.vigilia.app.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vigilia.app.domain.model.FatigueMetrics
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages CameraX initialization, lifecycle binding, and use case configuration.
 *
 * This class provides a high-level API to start and stop the camera feed,
 * integrating [FaceAnalyzer] for real-time fatigue monitoring.
 */
@Suppress("unused")
class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null

    /**
     * Starts the camera and binds the Preview and ImageAnalysis use cases to the provided lifecycle.
     *
     * @param lifecycleOwner The lifecycle owner (e.g., Activity or Fragment) to bind to.
     * @param onMetricsAvailable Callback for extracted face metrics.
     * @param surfaceProvider The SurfaceProvider to link the preview to. Optional.
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        onMetricsAvailable: (FatigueMetrics) -> Unit,
        surfaceProvider: Preview.SurfaceProvider? = null,
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        val preview = if (surfaceProvider != null) {
            Preview.Builder().build().also {
                it.surfaceProvider = surfaceProvider
            }
        } else null

        analysisExecutor = Executors.newSingleThreadExecutor()

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor!!, FaceAnalyzer(onMetricsAvailable))
            }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                try {
                    cameraProvider?.unbindAll()
                    
                    val useCases = mutableListOf<androidx.camera.core.UseCase>(imageAnalysis)
                    preview?.let { useCases.add(it) }

                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        *useCases.toTypedArray(),
                    )
                } catch (e: Exception) {
                    Log.e("CameraManager", "Use case binding failed", e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /**
     * Unbinds all CameraX use cases and shuts down the analysis executor.
     */
    fun stopCamera() {
        cameraProvider?.unbindAll()
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }
}
