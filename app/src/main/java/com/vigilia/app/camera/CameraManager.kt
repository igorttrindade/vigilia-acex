package com.vigilia.app.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
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
import com.vigilia.app.lighting.LightingMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages CameraX initialization, lifecycle binding, and use case configuration.
 */
@Suppress("unused")
class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var faceAnalyzer: FaceAnalyzer? = null

    private var currentPreview: Preview? = null
    private var currentAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    // Written from Main (applyLightingMode), read from the analysis thread (FaceAnalyzer
    // getter). @Volatile ensures the analysis thread sees the latest value.
    @Volatile private var currentLightingMode: LightingMode = LightingMode.NORMAL

    /**
     * Starts the camera with Analysis and optional Preview.
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        onMetricsAvailable: (FatigueMetrics) -> Unit,
        surfaceProvider: Preview.SurfaceProvider? = null,
    ) {
        if (cameraProvider != null) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        faceAnalyzer?.close()
        faceAnalyzer = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = Executors.newSingleThreadExecutor()

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        // No Camera2Interop.Extender on the builder — runtime tuning is applied via
        // Camera2CameraControl.setCaptureRequestOptions after bindToLifecycle. This keeps
        // one code path and one set of Camera2 options to reason about.
        currentAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                // Pass a getter so the analyzer sees the up-to-date lighting mode without
                // needing a reference to the whole CameraManager.
                faceAnalyzer = FaceAnalyzer(context, onMetricsAvailable) { currentLightingMode }
                it.setAnalyzer(analysisExecutor!!, faceAnalyzer!!)
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

                    camera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        *useCases.toTypedArray(),
                    )
                    // Reapply the last mode after (re)binding — new sessions default to NORMAL
                    // but if the caller has already switched to LOW_LIGHT/DARK we honor it.
                    applyLightingMode(currentLightingMode)
                } catch (e: Exception) {
                    Log.e("CameraManager", "Use case binding failed", e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /**
     * Hot-applies AE/FPS/scene-mode tuning matched to the current [LightingMode]. Safe to
     * call before the camera is bound (state is remembered and reapplied on next bind).
     * All Camera2 knobs are best-effort — devices that don't support NIGHT mode or the
     * requested FPS range fall back to the AE default.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun applyLightingMode(mode: LightingMode) {
        currentLightingMode = mode
        val cam = camera ?: return

        // Exposure compensation via CameraX (handles device step size automatically).
        try {
            val range = cam.cameraInfo.exposureState.exposureCompensationRange
            val evIndex = when (mode) {
                LightingMode.NORMAL -> 0
                // ~+1 EV nudge; the actual EV depends on the device's compensation step.
                LightingMode.LOW_LIGHT -> (range.upper / 3).coerceIn(range.lower, range.upper)
                // ~+2 EV nudge; clamped so devices with narrow ranges still get the max boost.
                LightingMode.DARK -> (range.upper * 2 / 3).coerceIn(range.lower, range.upper)
            }
            cam.cameraControl.setExposureCompensationIndex(evIndex)
        } catch (e: Throwable) {
            Log.w("CameraManager", "Exposure comp set failed for $mode", e)
        }

        // Target FPS + scene mode via Camera2Interop.
        val optionsBuilder = CaptureRequestOptions.Builder()
        val fpsRange = when (mode) {
            LightingMode.NORMAL -> Range(24, 30)
            LightingMode.LOW_LIGHT -> Range(20, 30)
            LightingMode.DARK -> Range(15, 20)
        }
        optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        optionsBuilder.setCaptureRequestOption(
            CaptureRequest.CONTROL_SCENE_MODE,
            when (mode) {
                LightingMode.NORMAL -> CaptureRequest.CONTROL_SCENE_MODE_DISABLED
                LightingMode.LOW_LIGHT, LightingMode.DARK -> CaptureRequest.CONTROL_SCENE_MODE_NIGHT
            },
        )
        try {
            // setCaptureRequestOptions returns ListenableFuture<Void>; fire-and-forget is fine
            // since applyLightingMode is best-effort and we log on failure via the future's
            // exception path (skipped here — a bad option key is not actionable at runtime).
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(optionsBuilder.build())
            Log.d("CameraManager", "Lighting mode $mode applied (fps=$fpsRange)")
        } catch (e: Throwable) {
            Log.w("CameraManager", "Camera2 options apply failed for $mode", e)
        }
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
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    analysis,
                    currentPreview
                )
            } else {
                currentPreview = null
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    analysis
                )
            }
            // Preview rebind resets the CaptureSession — reapply Camera2 options.
            applyLightingMode(currentLightingMode)
        } catch (e: Exception) {
            Log.e("CameraManager", "Preview update failed", e)
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        faceAnalyzer?.close()
        faceAnalyzer = null
        currentPreview = null
        currentAnalysis = null
        camera = null
        currentLightingMode = LightingMode.NORMAL
    }
}
