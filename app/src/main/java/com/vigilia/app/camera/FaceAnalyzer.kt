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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.vigilia.app.domain.model.FatigueMetrics
import kotlin.math.abs

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
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
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

            val landmarksList = result.faceLandmarks()

            val metrics = if (blendshapesOpt.isPresent && blendshapesOpt.get().isNotEmpty()) {
                val shapes = blendshapesOpt.get()[0]
                val eyeBlinkLeft  = shapes.find { it.categoryName() == "eyeBlinkLeft"  }?.score()
                val eyeBlinkRight = shapes.find { it.categoryName() == "eyeBlinkRight" }?.score()
                val jawOpen       = shapes.find { it.categoryName() == "jawOpen"       }?.score() ?: 0f

                // If either eye blendshape is absent the model is uncertain — treat as no face
                // rather than silently defaulting to 0f (which would be read as "eyes wide open")
                if (eyeBlinkLeft == null || eyeBlinkRight == null) {
                    Log.w("FaceAnalyzer", "Eye blendshapes missing — discarding frame as NO_FACE")
                    createNoFaceMetrics()
                } else {
                    val blendLeft  = (1f - eyeBlinkLeft).coerceIn(0f, 1f)
                    val blendRight = (1f - eyeBlinkRight).coerceIn(0f, 1f)

                    // EAR (Eye Aspect Ratio) uses geometric eyelid distances — immune to lens
                    // reflections that inflate blendshape-based openness for glasses wearers.
                    // We take the minimum of blendshape and EAR so reflections never hide a blink.
                    val (earLeft, earRight) = if (landmarksList.isNotEmpty()) {
                        calculateEarOpenness(landmarksList[0])
                    } else Pair(blendLeft, blendRight)

                    val finalLeft  = minOf(blendLeft, earLeft)
                    val finalRight = minOf(blendRight, earRight)

                    Log.d("FaceAnalyzer", "blinkL=$eyeBlinkLeft blinkR=$eyeBlinkRight jawOpen=$jawOpen earL=$earLeft earR=$earRight")
                    FatigueMetrics(
                        leftEyeOpenProbability  = finalLeft,
                        rightEyeOpenProbability = finalRight,
                        mouthOpenProbability    = jawOpen,
                        isFaceDetected          = true,
                        timestampMs             = System.nanoTime() / 1_000_000,
                    )
                }
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

    /**
     * Computes eye openness [0,1] for both eyes using Eye Aspect Ratio (EAR).
     *
     * EAR = vertical_eyelid_gap / horizontal_eye_width. Unlike blendshapes it is
     * purely geometric, so lens reflections from glasses do not corrupt the result.
     *
     * Landmark indices (MediaPipe 478-point mesh):
     *   Left  — outer:33  inner:133  upper:159  lower:145
     *   Right — outer:263 inner:362  upper:386  lower:374
     */
    private fun calculateEarOpenness(landmarks: List<NormalizedLandmark>): Pair<Float, Float> {
        if (landmarks.size < 478) return Pair(1f, 1f)

        fun ear(outerIdx: Int, innerIdx: Int, upperIdx: Int, lowerIdx: Int): Float {
            val vertical   = abs(landmarks[upperIdx].y() - landmarks[lowerIdx].y())
            val horizontal = abs(landmarks[outerIdx].x() - landmarks[innerIdx].x())
            return if (horizontal < 0.001f) 0f else vertical / horizontal
        }

        val leftEar  = ear(33, 133, 159, 145)
        val rightEar = ear(263, 362, 386, 374)

        return Pair(
            (leftEar  / EAR_OPEN_REFERENCE).coerceIn(0f, 1f),
            (rightEar / EAR_OPEN_REFERENCE).coerceIn(0f, 1f),
        )
    }

    companion object {
        private const val MODEL_ASSET_PATH = "face_landmarker.task"

        // EAR for a fully-open eye in normalized landmark coordinates.
        // Used to map raw EAR → [0,1] openness probability.
        private const val EAR_OPEN_REFERENCE = 0.28f
    }
}
