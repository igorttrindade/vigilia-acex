package com.vigilia.app.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
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
 */
@Suppress("unused")
class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    
    // Track active use cases to allow partial unbinding
    private var currentPreview: Preview? = null
    private var currentAnalysis: ImageAnalysis? = null

    /**
     * Starts the camera with Analysis and optional Preview.
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        onMetricsAvailable: (FatigueMetrics) -> Unit,
        surfaceProvider: Preview.SurfaceProvider? = null,
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        analysisExecutor = Executors.newSingleThreadExecutor()

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        currentAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor!!, FaceAnalyzer(onMetricsAvailable))
            }

        if (surfaceProvider != null) {
            currentPreview = Preview.Builder().build().also {
                it.surfaceProvider = surfaceProvider
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                try {
                    cameraProvider?.unbindAll()
                    
                    val useCases = mutableListOf<UseCase>()
                    currentAnalysis?.let { useCases.add(it) }
                    currentPreview?.let { useCases.add(it) }

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
     * Rebinds or unbinds just the Preview use case without stopping Analysis.
     */
    fun updatePreview(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider?
    ) {
        val provider = cameraProvider ?: return
        val analysis = currentAnalysis ?: return
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            provider.unbindAll()
            
            if (surfaceProvider != null) {
                currentPreview = Preview.Builder().build().also {
                    it.surfaceProvider = surfaceProvider
                }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    analysis,
                    currentPreview
                )
            } else {
                currentPreview = null
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    analysis
                )
            }
        } catch (e: Exception) {
            Log.e("CameraManager", "Preview update failed", e)
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        currentPreview = null
        currentAnalysis = null
    }
}
