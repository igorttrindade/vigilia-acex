package com.vigilia.app.camera

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.vigilia.app.domain.model.FatigueMetrics
import kotlin.math.abs

/**
 * Extracts face metrics from camera frames using ML Kit Face Detection.
 *
 * This analyzer processes individual [ImageProxy] frames, identifies the primary face,
 * and extracts eye openness and mouth opening probabilities.
 *
 * Mouth opening is calculated from the vertical distance between [FaceLandmark.NOSE_BASE]
 * and [FaceLandmark.MOUTH_BOTTOM], normalised by face height. This requires
 * [FaceDetectorOptions.LANDMARK_MODE_ALL] and replaces the former smiling-probability proxy,
 * which conflated smiling with yawning and produced unreliable fatigue signals.
 *
 * Note: ML Kit does not expose a MOUTH_TOP landmark. NOSE_BASE (bottom of the nose bridge)
 * is the closest stable upper reference — the nose-to-lower-lip distance reliably increases
 * as the jaw drops during a yawn.
 *
 * @param onMetricsAvailable Callback emitted for every processed frame (with or without a face).
 */
class FaceAnalyzer(
    private val onMetricsAvailable: (FatigueMetrics) -> Unit,
) : ImageAnalysis.Analyzer {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.15f)
        .build()

    private val detector = FaceDetection.getClient(options)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        try {
            detector.process(image)
                .addOnSuccessListener { faces ->
                    val face = faces.firstOrNull()
                    val metrics = if (face != null) {
                        val noseBase = face.getLandmark(FaceLandmark.NOSE_BASE)
                        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)
                        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)
                        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)

                        Log.d("FaceAnalyzer", "landmarks available: " +
                            "MOUTH_BOTTOM=${mouthBottom != null} " +
                            "MOUTH_LEFT=${mouthLeft != null} " +
                            "MOUTH_RIGHT=${mouthRight != null} " +
                            "NOSE_BASE=${noseBase != null}")

                        val faceHeight = face.boundingBox.height().toFloat()
                        val mouthOpenScore = if (faceHeight <= 0f) 0f else when {
                            noseBase != null && mouthBottom != null -> {
                                val distance = abs(noseBase.position.y - mouthBottom.position.y)
                                (distance / faceHeight).coerceIn(0f, 1f)
                            }
                            noseBase != null && mouthLeft != null && mouthRight != null -> {
                                val mouthCenterY = (mouthLeft.position.y + mouthRight.position.y) / 2f
                                val distance = abs(noseBase.position.y - mouthCenterY)
                                (distance / faceHeight).coerceIn(0f, 1f)
                            }
                            else -> 0f
                        }
                        FatigueMetrics(
                            leftEyeOpenProbability = face.leftEyeOpenProbability ?: 0f,
                            rightEyeOpenProbability = face.rightEyeOpenProbability ?: 0f,
                            mouthOpenProbability = mouthOpenScore,
                            isFaceDetected = true,
                            timestampMs = System.nanoTime() / 1_000_000,
                        )
                    } else {
                        createNoFaceMetrics()
                    }
                    onMetricsAvailable(metrics)
                }
                .addOnFailureListener { e ->
                    Log.e("FaceAnalyzer", "Face detection failed", e)
                    onMetricsAvailable(createNoFaceMetrics())
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Log.e("FaceAnalyzer", "Analysis process exception", e)
            imageProxy.close()
            onMetricsAvailable(createNoFaceMetrics())
        }
    }

    private fun createNoFaceMetrics() = FatigueMetrics(
        leftEyeOpenProbability = 0f,
        rightEyeOpenProbability = 0f,
        mouthOpenProbability = 0f,
        isFaceDetected = false,
        timestampMs = System.nanoTime() / 1_000_000,
    )
}
