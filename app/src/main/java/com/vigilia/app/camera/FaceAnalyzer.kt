package com.vigilia.app.camera

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.vigilia.app.domain.model.FatigueMetrics

/**
 * Extracts face metrics from camera frames using ML Kit Face Detection.
 *
 * This analyzer processes individual [ImageProxy] frames, identifies the primary face,
 * and extracts eye openness and mouth opening probabilities.
 *
 * NOTE: ML Kit does not provide a direct mouth-open probability. As a proxy, this
 * implementation currently uses smiling probability to estimate mouth state.
 *
 * @param onMetricsAvailable Callback emitted for every processed frame (with or without a face).
 */
class FaceAnalyzer(
    private val onMetricsAvailable: (FatigueMetrics) -> Unit,
) : ImageAnalysis.Analyzer {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
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
                        FatigueMetrics(
                            leftEyeOpenProbability = face.leftEyeOpenProbability ?: 0f,
                            rightEyeOpenProbability = face.rightEyeOpenProbability ?: 0f,
                            mouthOpenProbability = face.smilingProbability ?: 0f,
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
