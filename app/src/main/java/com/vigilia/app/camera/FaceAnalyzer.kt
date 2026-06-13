package com.vigilia.app.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.vigilia.app.domain.model.FatigueMetrics

/**
 * Extracts face metrics from camera frames using MediaPipe FaceLandmarker.
 *
 * Uses 478-point face mesh with BlendShape coefficients:
 * - [eyeBlinkLeft] / [eyeBlinkRight]: 0=open, 1=closed — inverted to produce eye-open probability
 * - [jawOpen]: 0=closed, 1=fully open — used directly as mouth-open probability for yawn detection
 *
 * The FaceLandmarker model is initialized asynchronously on the main thread (which has a Looper,
 * required by MediaPipe internally) via [Handler.post]. This avoids blocking the calling thread
 * while still satisfying MediaPipe's threading requirements. Frames arriving before initialization
 * completes are emitted as NO_FACE metrics.
 *
 * @param context Used to load the bundled model asset.
 * @param onMetricsAvailable Callback emitted for every processed frame.
 */
class FaceAnalyzer(
    private val context: Context,
    private val onMetricsAvailable: (FatigueMetrics) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile private var faceLandmarker: FaceLandmarker? = null
    @Volatile private var closed = false

    init {
        // FaceLandmarker.createFromOptions() requires a thread with a Looper; schedule on main.
        // analyze() returns NO_FACE for the few frames that arrive before init completes.
        Handler(Looper.getMainLooper()).post {
            if (closed) return@post
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .setMinFaceDetectionConfidence(0.3f)
                    .setMinFacePresenceConfidence(0.3f)
                    .build()
                val lm = FaceLandmarker.createFromOptions(context, options)
                if (closed) lm.close() else faceLandmarker = lm
            } catch (e: Throwable) {
                Log.e("FaceAnalyzer", "FaceLandmarker init failed — face detection disabled", e)
            }
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        val landmarker = faceLandmarker
        if (landmarker == null) {
            onMetricsAvailable(createNoFaceMetrics())
            imageProxy.close()
            return
        }
        try {
            // toBitmap() converts YUV_420_888 → ARGB_8888, avoiding MediaImageBuilder
            // compatibility issues across devices and CameraX versions.
            val bitmap = imageProxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val imageOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
                .build()

            val result = landmarker.detect(mpImage, imageOptions)
            val blendshapesOpt = result.faceBlendshapes()

            val metrics = if (blendshapesOpt.isPresent && blendshapesOpt.get().isNotEmpty()) {
                val shapes = blendshapesOpt.get()[0]
                val eyeBlinkLeft  = shapes.find { it.categoryName() == "eyeBlinkLeft"  }?.score() ?: 0f
                val eyeBlinkRight = shapes.find { it.categoryName() == "eyeBlinkRight" }?.score() ?: 0f
                val jawOpen       = shapes.find { it.categoryName() == "jawOpen"       }?.score() ?: 0f

                Log.d("FaceAnalyzer", "blinkL=$eyeBlinkLeft blinkR=$eyeBlinkRight jawOpen=$jawOpen")

                FatigueMetrics(
                    leftEyeOpenProbability  = (1f - eyeBlinkLeft).coerceIn(0f, 1f),
                    rightEyeOpenProbability = (1f - eyeBlinkRight).coerceIn(0f, 1f),
                    mouthOpenProbability    = jawOpen,
                    isFaceDetected          = true,
                    timestampMs             = System.nanoTime() / 1_000_000,
                )
            } else {
                createNoFaceMetrics()
            }
            onMetricsAvailable(metrics)
        } catch (e: Exception) {
            Log.e("FaceAnalyzer", "Detection failed", e)
            onMetricsAvailable(createNoFaceMetrics())
        } finally {
            imageProxy.close()
        }
    }

    fun close() {
        closed = true
        try {
            faceLandmarker?.close()
        } catch (e: Exception) {
            Log.w("FaceAnalyzer", "Close failed", e)
        }
    }

    private fun createNoFaceMetrics() = FatigueMetrics(
        leftEyeOpenProbability  = 0f,
        rightEyeOpenProbability = 0f,
        mouthOpenProbability    = 0f,
        isFaceDetected          = false,
        timestampMs             = System.nanoTime() / 1_000_000,
    )

    companion object {
        private const val MODEL_ASSET_PATH = "face_landmarker.task"
    }
}
